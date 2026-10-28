# Deploy your own RoseVault backend

## 1. Supabase project

Create a dedicated Supabase project in a suitable region. Review its current plan limits and costs. Do not reuse unrelated application databases. Install Node.js 22+ and run `npm ci` to get the pinned Supabase CLI. Sign in with `npx supabase login`, link your project with `npx supabase link --project-ref YOUR_PROJECT_REF`, and apply `npx supabase db push` after reviewing the migration.

The migration creates protected conversation/message/event/call tables, private phone profiles/rate counters, the phone RPC, and a private `vault-media` bucket with a 30 MiB encrypted-object limit. Clients cannot read a phone directory or rewrite membership/keys. Do not disable RLS or make the bucket public.

Existing projects prepared by this repository's initial deployment already have the migration. Check `npx supabase migration list` before pushing; match migration history rather than applying the same schema twice.

## 2. Phone authentication

In your project's Authentication settings, enable Phone and configure a supported SMS provider. Choose a six-digit OTP and appropriate resend/expiry/rate limits. Configure provider/regional requirements and spending controls before live use. Use fictional test numbers first. Never commit production test-code bypasses, SMS credentials, access/refresh tokens or phone lists.

The app requests `/auth/v1/otp`, verifies `/auth/v1/verify` with SMS type, and keeps access/refresh tokens in a Keystore-encrypted atomic file. Refresh tokens are renewed as needed. A separate encrypted account binding prevents silently switching the same vault to another Supabase UID. Phone registration cannot decrypt or recover another device's local keys.

Registering a discovery key requires an authenticated account with a confirmed phone and recent OTP authentication. Number discovery is opt-in and rate-limited. For a new chat, both phones sign in; the recipient enables Find me by phone number. Chats → + → Phone number starts the shared conversation. The recipient discovers it while connected and unlocked. Compare safety numbers through a trusted channel.

## 3. Android configuration

Copy `supabase.properties.example` to `supabase.properties` in the repository root. Enter your HTTPS project origin and `sb_publishable_...` key from the project dashboard. These client values are not administrator credentials, but the local file is intentionally ignored to keep deployments independent. Never use a secret/service_role key in an APK.

Build with `./gradlew -PenableCloud=true :app:assembleDebug`. Without configuration, builds default to offline; `-PenableCloud=false` explicitly disables cloud. CI uses offline mode and requires no project secrets. No google-services.json is used.

## 4. Calls

Deploy the credential endpoint: `npx supabase functions deploy turn-credentials`. The checked-in configuration disables the legacy gateway JWT check because the handler explicitly validates the bearer token through Supabase Auth before issuing credentials; it also requires a verified phone and a database issuance limit. Do not remove those handler checks.

Configure TURN_URLS and TURN_SHARED_SECRET only as server-side Edge Function secrets. See REMOTE_CALLS_SETUP.md. Without a configured relay, the endpoint returns setup-required feedback instead of fake credentials or a direct peer-IP fallback. A Supabase project cannot itself carry TURN media.

## 5. Retention and operational limits

Expired messages are hidden by database RLS and purged from local view. Physical deletion of server ciphertext and Storage objects requires an operator cleanup job. Delete objects through the Storage API, then remove corresponding expired message rows; do not delete storage.objects rows directly, which can leave underlying blobs. Remove old calls/candidates too. This version does not deploy an automatic cleanup schedule.

This client is foreground polling, not background push. It is intended for a small pilot; measure requests, database reads, file egress and relay bandwidth before scaling. Free project limits can interrupt service; SMS-provider and relay bills are separate.

No automatic Firebase-account or cloud-history migration is performed. Local encrypted vault data is preserved by an in-place same-signature update. Existing Firebase-only shared threads do not become Supabase threads automatically; resolve account/device identity migration before adopting this update for an existing production Firebase messenger.

References: [Supabase phone login](https://supabase.com/docs/guides/auth/phone-login), [private buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [CLI](https://supabase.com/docs/reference/cli/introduction).
