export async function mint(secret, urls, uid, now = Math.floor(Date.now()/1000)) {
 if(!secret || !uid || !urls.length || urls.some(x=>!/^turns?:[^\s]+$/.test(x)))throw new Error('Calling relay is not configured.');
 const username=`${now+600}:${uid}`;
 const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(secret),{name:'HMAC',hash:'SHA-1'},false,['sign']);
 const bytes=new Uint8Array(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(username)));
 return {username,credential:btoa(String.fromCharCode(...bytes)),urls,expiresAt:now+600};
}
export async function handle(request,{env,fetcher=fetch}) {
 const json=(status,value)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
 if(request.method!=='POST')return json(405,{message:'Use POST.'});
 const authorization=request.headers.get('authorization')??'';
 if(!authorization.startsWith('Bearer '))return json(401,{message:'Sign in first.'});
 try {
  const url=env('SUPABASE_URL'),key=env('SUPABASE_ANON_KEY');
  const headers={apikey:key,authorization,'Content-Type':'application/json'};
  const response=await fetcher(`${url}/auth/v1/user`,{headers});
  if(!response.ok)return json(401,{message:'Verify your phone again.'});
  const user=await response.json();
  if(!user.id || !user.phone || !user.phone_confirmed_at)return json(403,{message:'Verify your phone number first.'});
  const secret=env('TURN_SHARED_SECRET');
  const urls=(env('TURN_URLS')??'').split(',').map(x=>x.trim()).filter(Boolean);
  if(!secret || !urls.length)return json(503,{message:'Calling relay is not configured. Messaging and Nearby remain available.'});
  const limited=await fetcher(`${url}/rest/v1/rpc/phone`,{method:'POST',headers,body:JSON.stringify({action:'turn',payload:{}})});
  if(!limited.ok)return json(429,{message:'Too many calling requests. Try again later.'});
  return json(200,await mint(secret,urls,user.id));
 }catch{return json(503,{message:'Calling service is unavailable. Try again later.'});}
}
