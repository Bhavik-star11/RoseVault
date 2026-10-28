-- RoseVault: encrypted payloads only; profiles and rate counters are private.
create schema if not exists private;
create extension if not exists pgcrypto with schema extensions;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated;

create table public.rv_conversations (
 id text primary key check (id ~ '^[a-f0-9]{64}$'),
 members uuid[] not null check (cardinality(members)=2 and members[1] is not null and members[2] is not null and members[1]<>members[2]),
 keys jsonb not null check (jsonb_typeof(keys)='object'),
 kind text not null default 'phone' check (kind in ('phone','code'))
);
create table public.rv_messages (
 id uuid primary key, conversation_id text not null references public.rv_conversations(id) on delete cascade,
 sender uuid not null, envelope text not null check (envelope ~ '^ENCv1:' and octet_length(envelope)<100000),
 created_at bigint not null, expires_at bigint not null default 0,
 seq bigint generated always as identity unique,
 check (expires_at=0 or (expires_at>created_at and expires_at<=created_at+86400000))
);
create index rv_messages_conversation_seq on public.rv_messages(conversation_id,seq);
create table public.rv_events (
 id uuid primary key, conversation_id text not null references public.rv_conversations(id) on delete cascade,
 sender uuid not null, envelope text not null check (envelope ~ '^ENCv1:' and octet_length(envelope)<100000)
);
create index rv_events_conversation on public.rv_events(conversation_id);
create table public.rv_calls (
 id uuid primary key, conversation_id text not null references public.rv_conversations(id) on delete cascade,
 from_uid uuid not null, to_uid uuid not null, status text not null check(status in ('ringing','accepted','ended')),
 video boolean not null, created_at bigint not null,
 offer text not null check(offer ~ '^ENCv1:' and octet_length(offer)<100000),
 answer text check(answer is null or (answer ~ '^ENCv1:' and octet_length(answer)<100000))
);
create index rv_calls_recipient on public.rv_calls(to_uid,status,created_at);
create table public.rv_candidates (
 id uuid primary key, call_id uuid not null references public.rv_calls(id) on delete cascade,
 sender uuid not null, payload text not null check(payload ~ '^ENCv1:' and octet_length(payload)<16000)
);
create index rv_candidates_call on public.rv_candidates(call_id);
create table private.phone_profiles (
 uid uuid primary key references auth.users(id) on delete cascade,
 key text not null, device_id uuid not null, discoverable boolean not null default false,
 updated_at timestamptz not null default now()
);
create table private.phone_limits (
 uid uuid not null, bucket text not null, started_at timestamptz not null, count integer not null,
 primary key(uid,bucket)
);

alter table public.rv_conversations enable row level security;
alter table public.rv_messages enable row level security;
alter table public.rv_events enable row level security;
alter table public.rv_calls enable row level security;
alter table public.rv_candidates enable row level security;
alter table private.phone_profiles enable row level security;
alter table private.phone_limits enable row level security;
revoke all on public.rv_conversations,public.rv_messages,public.rv_events,public.rv_calls,public.rv_candidates from anon,authenticated;
revoke all on private.phone_profiles,private.phone_limits from public,anon,authenticated;
grant select on public.rv_conversations to authenticated;
grant select,insert,delete on public.rv_messages,public.rv_events to authenticated;
grant select,insert on public.rv_calls,public.rv_candidates to authenticated;
grant update(status,answer) on public.rv_calls to authenticated;
grant usage on sequence public.rv_messages_seq_seq to authenticated;

create policy conversation_members on public.rv_conversations for select to authenticated using ((select auth.uid())=any(members));
create policy message_members on public.rv_messages for select to authenticated using (
 exists(select 1 from public.rv_conversations c where c.id=conversation_id) and
 (expires_at=0 or expires_at>extract(epoch from now())*1000));
create policy message_sender on public.rv_messages for insert to authenticated with check (
 sender=(select auth.uid()) and exists(select 1 from public.rv_conversations c where c.id=conversation_id) and
 created_at between extract(epoch from now())*1000-86400000 and extract(epoch from now())*1000+60000 and
 (expires_at=0 or expires_at>extract(epoch from now())*1000));
create policy message_delete_own on public.rv_messages for delete to authenticated using (sender=(select auth.uid()) and exists(select 1 from public.rv_conversations c where c.id=conversation_id));
create policy event_members on public.rv_events for select to authenticated using (exists(select 1 from public.rv_conversations c where c.id=conversation_id));
create policy event_sender on public.rv_events for insert to authenticated with check (sender=(select auth.uid()) and exists(select 1 from public.rv_conversations c where c.id=conversation_id));
create policy event_delete_members on public.rv_events for delete to authenticated using (exists(select 1 from public.rv_conversations c where c.id=conversation_id));
create policy call_members on public.rv_calls for select to authenticated using (exists(select 1 from public.rv_conversations c where c.id=conversation_id));
create policy call_sender on public.rv_calls for insert to authenticated with check (
 from_uid=(select auth.uid()) and to_uid<>from_uid and status='ringing' and answer is null and
 created_at between extract(epoch from now())*1000-60000 and extract(epoch from now())*1000+60000 and
 exists(select 1 from public.rv_conversations c where c.id=conversation_id and to_uid=any(c.members)));
create policy call_update_members on public.rv_calls for update to authenticated using (
 exists(select 1 from public.rv_conversations c where c.id=conversation_id)) with check (
 exists(select 1 from public.rv_conversations c where c.id=conversation_id) and
 (status='ended' or (status='accepted' and to_uid=(select auth.uid()) and answer is not null)));
create policy candidate_members on public.rv_candidates for select to authenticated using (exists(select 1 from public.rv_calls c where c.id=call_id));
create policy candidate_sender on public.rv_candidates for insert to authenticated with check (
 sender=(select auth.uid()) and exists(select 1 from public.rv_calls c where c.id=call_id and c.status in ('ringing','accepted')));

create function private.call_transition() returns trigger language plpgsql set search_path='' as $$
begin
 if new.status='ended' and new.answer is not distinct from old.answer then return new; end if;
 if old.status='ringing' and new.status='accepted' and auth.uid()=old.to_uid and old.answer is null and new.answer is not null then return new; end if;
 raise exception 'Invalid call transition';
end $$;
create trigger enforce_call_transition before update on public.rv_calls for each row execute function private.call_transition();

create function private.take_limit(actor uuid, category text, maximum integer) returns void language plpgsql set search_path='' as $$
declare used integer;
begin
 insert into private.phone_limits(uid,bucket,started_at,count) values(actor,category,now(),1)
 on conflict(uid,bucket) do update set
 count=case when private.phone_limits.started_at<now()-interval '1 hour' then 1 else private.phone_limits.count+1 end,
 started_at=case when private.phone_limits.started_at<now()-interval '1 hour' then now() else private.phone_limits.started_at end
 returning count into used;
 if used>maximum then raise exception 'Too many requests. Try again later.'; end if;
end $$;
create function private.valid_key(value text) returns boolean language plpgsql immutable set search_path='' as $$
declare encoded bytea;
begin
 if value is null or value !~ '^[A-Za-z0-9+/]+={0,2}$' or length(value)>512 then return false; end if;
 encoded:=decode(value,'base64');
 return length(encoded)=91 and substring(encoded from 1 for 27)=decode('3059301306072a8648ce3d020106082a8648ce3d03010703420004','hex');
exception when others then return false;
end $$;

-- Privileged lookup is kept outside exposed schemas and checks the actual verified caller.
create function private.phone_action(action text, payload jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); actor_phone text; own private.phone_profiles; peer private.phone_profiles;
 target uuid; target_phone text; cid text; existing public.rv_conversations; mine_key text:=payload->>'key'; keys jsonb; pair uuid[];
begin
 if actor is null then raise exception 'Sign in with a verified phone number first.'; end if;
 select '+'||ltrim(u.phone,'+') into actor_phone from auth.users u where u.id=actor and u.phone_confirmed_at is not null and (u.banned_until is null or u.banned_until<now());
 if actor_phone is null then raise exception 'Verify your phone number first.'; end if;
 select * into own from private.phone_profiles where uid=actor for share;
 if action='profile' then
  return jsonb_build_object('phoneNumber',actor_phone,'discoverable',coalesce(own.discoverable,false),'registered',coalesce(own.key=mine_key,false));
 elsif action='register' then
  if not exists(select 1 from jsonb_array_elements(coalesce(auth.jwt()->'amr','[]'::jsonb)) a where a->>'method'='otp' and (a->>'timestamp')::numeric between extract(epoch from now())-300 and extract(epoch from now())+60) then raise exception 'Verify your phone again before registering this device.'; end if;
  if not private.valid_key(mine_key) or jsonb_typeof(payload->'discoverable') is distinct from 'boolean' then raise exception 'Invalid phone discovery settings.'; end if;
  perform private.take_limit(actor,'profile',10);
  insert into private.phone_profiles(uid,key,device_id,discoverable) values(actor,mine_key,(payload->>'deviceId')::uuid,(payload->>'discoverable')::boolean)
  on conflict(uid) do update set key=excluded.key,device_id=excluded.device_id,discoverable=excluded.discoverable,updated_at=now();
  return jsonb_build_object('phoneNumber',actor_phone,'discoverable',(payload->>'discoverable')::boolean);
 elsif action='discovery' then
  if own.uid is null or own.key is distinct from mine_key or jsonb_typeof(payload->'discoverable') is distinct from 'boolean' then raise exception 'Verify this device first.'; end if;
  perform private.take_limit(actor,'discovery',20);
  update private.phone_profiles set discoverable=(payload->>'discoverable')::boolean,updated_at=now() where uid=actor;
  return jsonb_build_object('discoverable',(payload->>'discoverable')::boolean);
 elsif action='turn' then
  perform private.take_limit(actor,'turn',60);
  return jsonb_build_object('allowed',true);
 elsif action='chatInfo' then
  select * into existing from public.rv_conversations c where c.id=payload->>'id';
  if existing.id is null or not actor=any(existing.members) or (existing.keys->>actor::text) is distinct from mine_key then raise exception 'This device is not a member of the conversation.'; end if;
  target:=case when existing.members[1]=actor then existing.members[2] else existing.members[1] end;
  select '+'||ltrim(u.phone,'+') into target_phone from auth.users u where u.id=target;
  return jsonb_build_object('id',existing.id,'peerUid',target,'peerKey',existing.keys->>target::text,'phoneNumber',target_phone);
 elsif action in ('startChat','pair') then
  if own.uid is null or own.key is distinct from mine_key then raise exception 'Verify and register this device first.'; end if;
  perform private.take_limit(actor,'lookup',20);
  if action='startChat' then
   target_phone:=regexp_replace(payload->>'phoneNumber','[ ()-]','','g');
   if target_phone is null or target_phone !~ '^\+[1-9][0-9]{6,14}$' then raise exception 'Use a phone number including + and country code.'; end if;
   select u.id into target from auth.users u where ltrim(u.phone,'+')=ltrim(target_phone,'+') and u.phone_confirmed_at is not null and (u.banned_until is null or u.banned_until<now());
  else target:=(payload->>'peerUid')::uuid; end if;
  if target=actor then raise exception 'Use another person’s contact.'; end if;
  select * into peer from private.phone_profiles where uid=target for share;
  if peer.uid is null or (action='startChat' and not peer.discoverable) or (action='pair' and peer.key is distinct from payload->>'peerKey') then raise exception 'No discoverable RoseVault account was found.'; end if;
  if not private.valid_key(peer.key) then raise exception 'The peer device key is invalid.'; end if;
  cid:=encode(extensions.digest(case when own.key collate "C"<peer.key collate "C" then own.key||':'||peer.key else peer.key||':'||own.key end,'sha256'),'hex');
  pair:=case when actor<target then array[actor,target] else array[target,actor] end;
  keys:=jsonb_build_object(actor::text,own.key,target::text,peer.key);
  insert into public.rv_conversations(id,members,keys,kind) values(cid,pair,keys,case when action='pair' then 'code' else 'phone' end) on conflict(id) do nothing;
  select * into existing from public.rv_conversations c where c.id=cid;
  if existing.members<>pair or existing.keys<>keys then raise exception 'Pinned device identities differ. Pair again.'; end if;
  if target_phone is null then select '+'||ltrim(u.phone,'+') into target_phone from auth.users u where u.id=target; end if;
  return jsonb_build_object('id',cid,'peerUid',target,'peerKey',peer.key,'phoneNumber',target_phone);
 end if;
 raise exception 'Unknown phone action.';
end $$;
revoke all on all functions in schema private from public,anon,authenticated;
grant execute on function private.phone_action(text,jsonb) to authenticated;
create function public.phone(action text,payload jsonb default '{}'::jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.phone_action(action,payload) $$;
revoke all on function public.phone(text,jsonb) from public,anon;
grant execute on function public.phone(text,jsonb) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('vault-media','vault-media',false,31457280,array['application/octet-stream']) on conflict(id) do nothing;
create policy vault_media_read on storage.objects for select to authenticated using (
 bucket_id='vault-media' and (storage.foldername(name))[1]='media' and exists(select 1 from public.rv_conversations c where c.id=(storage.foldername(name))[2]));
create policy vault_media_insert on storage.objects for insert to authenticated with check (
 bucket_id='vault-media' and name ~ '^media/[a-f0-9]{64}/[a-f0-9-]{36}$' and exists(select 1 from public.rv_conversations c where c.id=(storage.foldername(name))[2]));
create policy vault_media_delete on storage.objects for delete to authenticated using (
 bucket_id='vault-media' and owner_id=(select auth.uid())::text and exists(select 1 from public.rv_conversations c where c.id=(storage.foldername(name))[2]));

-- No public buckets, client-side service keys, mutable membership, or public phone directory.
