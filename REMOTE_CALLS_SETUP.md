# Calls between distant phones — Supabase

Both phones need internet, verified Supabase phone accounts, and a shared cloud conversation. Nearby is a separate local-radio mode. The repository includes a real foreground WebRTC client with encrypted offer/answer/ICE signaling; live media requires an external TURN relay.

## 1. Finish account and database setup

Follow SETUP.md. Both people verify different phone numbers, the recipient enables number discovery, and one starts a conversation by number. Compare safety numbers and test encrypted text replies first.

## 2. Provide a calling relay

A TURN relay carries encrypted call traffic between phones on different networks. It is a separate service from Supabase. Use a managed TURN provider supporting the shared-secret TURN REST credential scheme, or host coturn on an internet-facing server you control. A Supabase callable function cannot itself serve as the TURN relay.

For self-hosting, the server operator needs a public address, a domain such as `turn.YOUR_DOMAIN`, a valid TLS certificate for that domain, and bandwidth suitable for the expected calls. The included `deploy/turnserver.conf.example` is a configuration starting point, not a provisioned or audited server.

Install coturn from the server distribution's trusted package source. Replace every `REPLACE_...` value in the example, install it as the server's coturn configuration, and restrict access to the file because it contains the shared secret. Generate a strong random secret locally on the server, for example with `openssl rand -hex 32`; keep the result out of chat and Git.

Allow the listener ports used by your TURN URLs, and the configured UDP relay range, in both the server and hosting-provider firewalls. The example uses 3478 UDP/TCP, 5349 TCP for TLS, and 49160–49200 UDP for a small initial test pool. That small range needs capacity planning before broader use. If the server is behind NAT, configure coturn's public/private mapping and forward the same relay port numbers. Use correct DNS records for the addresses actually served.

Install the domain certificate and key where only the TURN service can read them. Set up renewal and service reload. Keep the relay host updated. Preserve shared-secret authentication and restrictions on access to private/internal peers.

References: [coturn configuration example](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf), [coturn TURN REST authentication](https://github.com/coturn/coturn/wiki/turnserver#turn-rest-api).

## 3. Deploy credentials and add secrets

Run `npx supabase functions deploy turn-credentials` for your linked project. In the Supabase dashboard's Edge Function secrets, set TURN_URLS to your actual relay URLs and TURN_SHARED_SECRET to the same private secret configured on coturn. Keep the shared secret out of GitHub, Android properties, screenshots and chat.

Example TURN_URLS value (replace the domain):

```
turns:turn.YOUR_DOMAIN:5349?transport=tcp,turn:turn.YOUR_DOMAIN:3478?transport=udp
```

The handler validates each bearer token through Auth, checks the confirmed phone, enforces the per-account issuance limit through the phone RPC, and mints ten-minute credentials. No service-role key is embedded in the app. The endpoint is intentionally unavailable until its relay secrets are set.

## 4. Test real networks

Keep both apps open and unlocked. Put one phone on Wi-Fi and the other on mobile data, allow microphone/camera permissions and try voice/video, accepting/declining, mute, camera toggle, ending from either side, and connection loss/retry. Verify relay candidates in device/server diagnostics.

The client requires TURN relay candidates; it does not silently expose direct peer IPs if TURN fails. The relay operator still sees connecting IPs and traffic timing. This version has no background push/ringing implementation and does not guarantee incoming calls while the app is closed. Actual two-network media has to be tested before claiming live long-distance calls work.
