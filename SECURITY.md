# Security policy and limitations

RoseVault is an experimental, unaudited reference implementation. Do not present it as a reviewed production secure messenger. Report vulnerabilities through GitHub private vulnerability reporting when available; do not publish tokens, passwords, phone numbers, private content or exploit details in ordinary issues.

## Local vault and unlocking

Local state/media use AES-256-GCM and Android Keystore keys. Atomic writes protect interrupted saves; corrupted vault data fails closed. Hardware/device key deletion loses the vault. Background and inactivity locking clear visible state and stop foreground connections.

Android device credentials/strong biometrics can unlock the vault. A separate local app password is optional. Its salted PBKDF2-HMAC-SHA256 verifier is encrypted, not sent to Supabase, and rate-limited locally after repeated failures. Setting/changing/removing it requires fresh Android owner confirmation; device credentials remain the explicit recovery route. The app password is an application-level gate, not a hardware-authentication token or a password-derived replacement for all existing vault keys. Rooted/modified systems, OS compromise and attempt-file rollback are outside that limiter's guarantees. See APP_PASSWORD.md.

## Device-to-device encryption

Device identities use P-256 ECDH, HKDF-SHA256 and authenticated message envelopes. Conversation IDs derive from sorted device public keys. Message/event/call envelopes authenticate their context; local state pins peer identity and exposes safety numbers for human comparison. Media is encrypted before upload to private Storage.

This is static ECDH. Forward secrecy, double ratcheting, post-compromise security, reviewed multi-device fan-out, encrypted key backup and account-based key recovery are not implemented. Reinstalling creates a new identity. Account/SMS verification alone cannot prove the owner of a device key; compare safety numbers through an authenticated channel.

## Supabase authorization

All exposed conversation/message/event/call/candidate tables have RLS and explicit grants. Clients cannot create or mutate membership/keys directly. Envelopes are immutable, sender IDs are constrained to the authenticated account, and only the recipient can accept a ringing call. The server checks envelope shape/size, not cryptographic authenticity; clients verify encrypted payloads before display.

The phone RPC uses a private, fixed-search-path function with an explicit auth.uid() check and verified-phone lookup. Profiles and rate counters live in a non-exposed schema with RLS and no client table policies/grants. That absence of policies intentionally denies direct access. Device registration requires recent OTP authentication. Discovery is opt-in, lookups are rate-limited, and incoming conversations are accepted only when the local device key matches the pinned membership.

Auth stores/processes verified phone numbers. Private profile rows contain public device keys and discovery settings, not a client-readable number directory. Number reassignment/SIM takeover, compromised accounts and a malicious directory/server can affect metadata and first-use identity discovery. Existing key comparisons remain essential.

Only project URLs and publishable client keys belong in Android configuration. Secret/service_role keys, relay shared secrets, SMS credentials and session/test tokens must stay out of APKs and public repositories. Access/refresh tokens and the bound account UID are encrypted locally. A second Supabase account cannot silently adopt the same bound vault.

## Network, calls and notifications

The app uses normal HTTPS certificate validation with redirects disabled for API calls. Cloud history synchronizes through foreground polling; it is not a background delivery or push system. Message synchronization advances a server sequence cursor so queued messages are not skipped because their original timestamps are old.

WebRTC signaling is encrypted. Media requires TURN relay candidates; no direct/STUN fallback is enabled. The Edge Function validates bearer tokens through Auth, requires a verified phone, checks an issuance quota, and returns ten-minute relay credentials. A public endpoint with the gateway JWT check disabled still performs these explicit handler checks. Never remove them. Relay operators see connecting IPs and timing; Supabase sees accounts, membership, ciphertext and metadata. Actual two-network audio/video requires service configuration and physical-device testing.

## Offline and retention

Nearby uses confirmed pairing codes, pinned keys, encrypted bounded frames, receipts, deduplication and durable queues. Keep both apps unlocked/in range. It is not mesh routing or long-distance offline communication. Google Play services are required.

Memory-free mode discards local bodies, including unsent messages, on lock. Expiry hides messages locally and through database read policies; physical cloud ciphertext/object removal requires an operator cleanup job. No automatic cleanup schedule is deployed in this version. Objects must be removed through the Storage API, not by directly deleting storage.objects rows. Orphan cleanup, retention, backups, account deletion and operator audit controls need an explicit deployment policy.

## Release process

Never commit local project configuration, production credentials, signing keys or personal fixtures. Review the public file list and run the included Android, Node and database policy checks. Use a maintained release-signing key outside Git and document how updates preserve Keystore data. Dependencies retain their own licenses; see THIRD_PARTY_NOTICES.md.
