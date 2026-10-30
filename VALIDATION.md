# Validation — Matrix pilot 2.0.0-alpha.1

Checked locally on 30 October 2026. These are development checks, not an independent audit or a production certification.

## Native build and local regression

- Main APK and instrumentation APK build successfully. Android lint reports no errors. All 22 JVM cases pass, covering existing envelope/password/input rules plus connection-space consent, Rose safety/URL validation and Matrix input validation.
- The full Android suite exercised 27 non-optional cases. Twenty-six passed initially; the button audit stopped on an outdated Settings label after its local chat/date checks. The label assertion was corrected and the button audit passed separately from clean emulator data (77 seconds). Thus all 27 non-optional cases passed across these runs. The three optional integration cases skip without a private fixture.
- Matrix login-screen testing confirms the server/Matrix-ID form is behind the vault lock and disappears on lock. Connection-feature tests verify that private intentions do not silently become chat messages, persistence is encrypted, and Ollama server credentials can be forgotten and reject insecure origins.
- APK signing matches the previous development APK certificate (SHA-256 `2bae2505ee4cc4653845e9a7e7e91e28e4af92a0d43395736c1e8fd1b68ad8fc`). ZIP alignment passes for 16 KB pages; all arm64-v8a and x86_64 native ELF load segments have 16 KB alignment. This is not a physical 16 KB device test. Emulator integration used the universal APK on x86_64; the smaller ARM64 distribution contains the same application code and SDK, but has not been tested on a physical ARM64 phone.

## Actual Matrix SDK integration

An isolated, localhost-only Synapse v1.164.1 container and disposable accounts were used. The Android emulator trusted a short-lived local CA with normal certificate/hostname validation. Production has no certificate-bypass setting or injected test-login route.

- Two SDK accounts created/joined an encrypted private DM and decrypted each other's messages.
- Pausing/restoring the recipient fetched a missed message while preserving its account/device.
- An outgoing message was held in the SDK queue through a disabled network adapter, followed by a client pause/restart. It was delivered exactly once after restoration. This deterministic adapter test does not replace physical airplane-mode/radio tests.
- A separate fresh-account test completed mutual SAS verification, checked that both SDKs displayed matching values, sent/decrypted an encrypted message and an encrypted file, enabled encrypted key backup, and recovered the historical message on a fresh third SDK device using the recovery secret. Earlier attempts found adapter bugs and an omitted history-pagination step; those were corrected before the successful run.
- Server inspection found encrypted message events, no plaintext `m.room.message` events and no plaintext test markers in event JSON or uploaded media. A scan of 125 disposable SDK cache files found no plaintext message/file markers. These targeted sentinel checks are evidence about the exercised path, not proof against every possible leak.
- The production Compose configuration passes validation. Its generated Postgres configuration was loaded by the real Synapse configuration parser with public registration disabled. The local transport test uses SQLite; a public Postgres/HTTPS host has not been provisioned.

Each integration rerun needs fresh disposable accounts and a fresh private fixture. Never commit fixtures, passwords, tokens, recovery keys, CA private keys or server volumes. See MATRIX_SETUP.md for reproduction and cleanup.

## Not yet verified or enabled

Two physical phones are still required for Nearby radio/range, Android owner credentials/biometrics, real airplane-mode restarts, large attachments and cross-network behavior. SAS mismatch/cancel and changed-identity paths need further device testing. Real server restarts, retention/restore operations, rate controls and a full deployment security review remain operator work.

Closed-app Matrix notifications, UnifiedPush gateway/receiver registration, Doze/reboot delivery and MatrixRTC/LiveKit calls are not implemented in this pilot. No public homeserver, Ollama server, ntfy service or TURN relay has been activated. Rose AI's encrypted configuration and safety rules are tested; a live authenticated Ollama deployment has not been tested. Connection Spaces currently belong to local/Nearby chats, not Matrix rooms. No automatic Supabase-history migration is provided.

The earlier Supabase 1.5 pilot had separate Node, RLS and synthetic transport checks. Those remain legacy tests and do not validate this Matrix transport. Phone OTP, phone-number discovery and the requested additional Twilio trial recipient are not enabled by this build.

## Publication

The source keeps the MIT license and upstream dependency notices. Private backend properties, Firebase configuration, signing keys, test sessions, build output, server data and local secrets are excluded. GitHub Actions must be checked for the published commit; a workflow file alone is not a passing CI result.
