# Matrix in RoseVault

## What changes

Matrix is the new cloud-chat direction. Twilio was only the old SMS authentication provider, while Supabase carried the old app-to-app messages. Replacing that backend with Matrix is a migration, not an SMS setting. Matrix accounts use IDs such as `@alice:matrix.example.org`; they do not prove ownership of a mobile number. Phone lookup and OTP login are disabled in this build. Existing local vault and Nearby data remain readable; old Supabase accounts, ciphertext and conversations are not automatically imported into Matrix.

The application uses the Apache-2.0 Matrix Rust SDK Android bindings, pinned to `org.matrix.rustcomponents:sdk-android:26.10.23`. It powers Element X and supplies the protocol, Olm/Megolm encryption, SQLite stores, timelines, local echoes and send queues. RoseVault remains a Kotlin/Compose app with its existing Android 12 minimum. Adding the native SDK makes the APK larger. Trixnity is a viable Apache-2.0 Kotlin alternative; do not mix two crypto stores or two different Matrix SDKs in the same session. The older Matrix Android SDK is not the preferred starting point for a new integration.

## Terms in plain language

- **Homeserver:** the server holding accounts, room state and encrypted message events for your users. It is a mailbox and sync service.
- **Synapse:** a widely used homeserver implementation. It is separate server software with its own license and operational costs.
- **Room:** a conversation with membership, permissions and history rules. Each private DM is created invite-only and encrypted from its first event.
- **Event:** a message or change in a room. Encryption protects message content, but room membership, timing, traffic and some state remain visible to the server.
- **Access token:** a login secret authorizing a device. It must never appear in GitHub, screenshots, URLs, notifications or logs.
- **Device ID:** a Matrix session identity tied to that device's cryptographic keys. A new login/device is not automatically trusted.
- **Sync:** the SDK's process for fetching changes, reconciling local echoes and catching up after reconnect. It cannot deliver internet traffic to a phone with no network.

For a free same-Wi-Fi development setup, use [LOCAL_MATRIX.md](LOCAL_MATRIX.md). It has a separate app/package, private-IP HTTP guard, local Compose overlay and isolated server data. The production steps below continue to require HTTPS.

## Deploy Synapse, Postgres and HTTPS

This guide does not provision a paid VPS. Review costs and obtain a Linux host/domain you control first. The checked-in Compose setup is a private pilot with federation blocked, public signup disabled, and only HTTPS exposed.

1. Point the chosen DNS name, for example `matrix.example.org`, to your host. Choose it before creating accounts: Matrix user IDs contain this server name and it cannot casually be changed later. Allow inbound TCP 80/443 for HTTPS certificate issuance and clients. Do not publish Postgres, Synapse's internal 8008 port or the admin API directly.
2. Install Docker Engine and Compose using official vendor instructions. Copy `deploy/matrix` onto the host. Copy `.env.example` to `.env`, set `MATRIX_HOST`, and replace `POSTGRES_PASSWORD` with a freshly generated `openssl rand -hex 32` value. Keep `.env` private. Pin the three images to reviewed digests for production and track security updates; default Postgres/Caddy major tags are convenient pilot defaults, not fully reproducible pins.
3. Run `python3 render-config.py` from that directory. It creates private Synapse secrets without printing them and refuses to overwrite an existing configuration. Check file ownership: the Synapse container must be able to read/write `data`. Use the container's documented UID/GID for your deployment, not world-writable permissions.
4. Run `docker compose config --quiet` to validate, then `docker compose up -d`. Check `docker compose logs --tail=100 synapse` without publishing the logs. Caddy obtains public certificates. Never disable certificate verification to get a phone connected.
5. Check `https://matrix.example.org/_matrix/client/versions`. This setup uses native simplified sliding sync supported by the pinned Synapse. It does not need the retired external sliding-sync proxy.
6. Create the first administrator interactively: `docker compose exec synapse register_new_matrix_user -c /data/homeserver.yaml http://localhost:8008`. Enter a username, a unique password and choose administrator only for the owner. Do not put passwords in shell command arguments or use the administrator as a daily chat account.
7. Run the same command to create normal accounts for each person and answer **no** to administrator. Public registration stays disabled. Invite users by sharing their Matrix IDs through a trusted channel. Open-source clients cannot be securely restricted to a particular app merely by checking a User-Agent; account issuance and permissions are the access boundary.
8. On the phone unlock RoseVault, tap **Matrix** in the top bar, enter the HTTPS homeserver origin, full Matrix ID and account password. The password is cleared from the form and is not saved by RoseVault. OAuth-only public homeservers need a separate OAuth sign-in flow; this pilot targets the password-enabled private Synapse deployment described here.
9. On Alice's phone enter Bob's full Matrix ID and choose Start encrypted conversation. Bob reviews and accepts the invitation. An unencrypted room is blocked rather than silently falling back to plaintext. Verify both devices before sensitive conversations.
10. Protect and back up the Postgres volume, Synapse data/signing keys, proxy configuration and private environment. A server backup does not replace each user's encryption recovery secret. Test restore on an isolated host before relying on backups.

## Client architecture and storage

`matrix/MatrixVault.kt` is the service/repository boundary. The Rust SDK owns Matrix SQLite persistence and the durable send queue. Compose consumes its small StateFlow view in `ui/screens/MatrixScreen.kt`. The existing encrypted local-vault repository remains separate for local notes, media and Nearby; it is not used as a competing Matrix event database. Room/SQLite wrappers that duplicate SDK keys or sync tokens would add reconciliation risks, so no extra plaintext Room cache is introduced.

Each Matrix session has a random private store directory. A high-entropy store passphrase and the serialized session are sealed by Android Keystore in `matrix-session.enc`. SQLite is configured with the SDK's encrypted-store passphrase. The app refuses to silently replace an existing Matrix account with another account. Android app backup remains disabled. Clearing app data/uninstalling removes the local Keystore/store material; use Matrix encrypted recovery to restore historical room keys on another device.

The app-lock gate remains separate from server login. Locking hides the Matrix UI and pauses/drops its active SDK objects. A notification must never act as a bypass to this gate. Main-thread UI does not perform network or crypto work. No contacts, SMS, phone-state, or location permission is required for Matrix text chat. Existing Bluetooth/Nearby permissions serve the separate Nearby transport; microphone/camera are for calls/voice features, not Matrix login.

## E2EE, verification and recovery

Olm protects device-to-device key exchange; Megolm protects encrypted room messages. The SDK handles these protocols. RoseVault does not implement its own Matrix cryptography, insert raw access tokens into URLs, or send a plaintext replacement if keys are unavailable. Do not call raw room send APIs for plaintext messages in encrypted rooms. Do not mark a device verified by comparing names or by clicking a generic checkbox.

Use Device verification and recovery to request/accept the SDK's verification exchange. Compare all emoji or decimal values using a separate trusted channel, then approve only when both people see a match. Reject/cancel a mismatch. This is a real SAS exchange; a textual "encrypted" label alone does not establish trust. Changed identities and unverified devices may require explicit verification before messages can be sent; the app does not offer an ignore-trust-and-send shortcut.

Set up encrypted key recovery and save the returned recovery key privately offline. On a new device, sign in and enter that secret to recover room keys. The server stores an encrypted key backup; possessing the account password or a database backup alone is not a guarantee of historical decryption. Never auto-reset another user's identity or overwrite an existing recovery key because a download fails. Recovery is not remote logout, emergency data recovery, or protection from an already-unlocked compromised phone.

## Offline behavior and statuses

The SDK timeline supplies local echoes immediately and persists the send queue. **Queued** means waiting to upload, **Sent** means accepted by the server, and **Received** means this client has decrypted an incoming event. Sent is not Delivered or Read. Read receipts and typing sharing are optional; they reveal activity metadata. Retry re-enables the SDK's queue after a failure; it does not create a new transaction for every retry.

If Alice is offline, messages must stay in the local encrypted SDK store until connectivity returns. If Bob is offline, the homeserver retains encrypted events and Bob catches up when his SDK sync runs. Restart restores the same account/device/session store instead of creating a new device and duplicating the send. Back-pagination loads older history through the SDK. A new device still needs verified key sharing/recovery; downloading ciphertext alone is not enough.

This pilot syncs while its Matrix screen is open/unlocked. Closed-app wake-ups require the selected ntfy/UnifiedPush gateway and client receiver; those are not activated merely by installing Synapse. Synapse must register a per-device pusher to a compatible Matrix push gateway, and the gateway must dispatch only opaque event/room hints with no sender names or message content. The local notification builder already supports Private and Stealth visibility. Calls, MatrixRTC/LiveKit and background push are separate rollout steps, not part of a successful text-sync test.

## Implementation and verification checklist

1. Establish the SDK, explicit HTTPS server/password login, encrypted session/store, and same-device restoration.
2. Create invite-only encrypted DMs, review invitations, display SDK timelines and send through its durable queue.
3. Test with two actual SDK accounts: decrypt the partner's message; inspect server events to ensure `m.room.encrypted` and no plaintext body; disconnect/reconnect; pause/restart and verify keys/session survive.
4. Exercise local pending sends, duplicate transaction reconciliation, pagination, recoverable/permanent send failures, server logout, and read/typing opt-in.
5. Add SDK media send/download with size limits and authenticated encrypted media, never a public Storage URL. Verify attachments are encrypted on the server. External viewing creates a private temporary plaintext copy cleared on the next vault unlock/cache cleanup; external viewers may retain their own copies.
6. Verify SAS match/mismatch/cancel and changed-device trust with two phones; test encrypted key backup/recovery on a fresh third device. Do not label these tested until those flows pass.
7. Configure closed-app ntfy/UnifiedPush and test Doze, denied permission, offline push, duplicate hints, gateway retries, reboot, distributor removal and force-stop behavior. A completely offline phone cannot receive internet push.
8. Add MatrixRTC/LiveKit later with TURN and a separate media threat model. Matrix solves queued text delivery, not offline internet calls. Nearby remains independent Bluetooth/local-Wi-Fi messaging.

The exact checks completed for a build belong in VALIDATION.md. An example or a compiling screen does not prove a live homeserver, working recovery, or push delivery.

References: [Matrix SDK catalog](https://matrix.org/ecosystem/sdks/), [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk), [Android bindings](https://github.com/matrix-org/matrix-rust-components-kotlin), [Synapse administration](https://element-hq.github.io/synapse/latest/), [Matrix client-server specification](https://spec.matrix.org/latest/client-server-api/).

### Reproduce the opt-in device test

Use an isolated local Synapse instance and a proper short-lived test CA plus server certificate with SANs for `localhost`, `127.0.0.1` and the Android emulator host gateway `10.0.2.2`. Its advertised `public_baseurl` must use `https://10.0.2.2:8448/` for that emulator test. Bind the host listener to `127.0.0.1:8448`. Do not use a CA certificate as the server leaf and do not disable TLS verification.

Run `python3 scripts/matrix-test-fixture.py --config /path/to/test/homeserver.yaml --ca /path/to/test/ca.crt --output /private/path/matrix-fixture.json`. This helper refuses non-localhost servers, uses fresh disposable accounts, and writes their credentials only to the private fixture. Copy the fixture to the **test emulator's** app-private `files/matrix-fixture.json` with `adb -s emulator-5554 exec-in run-as com.aistudio.rosevault.kxpqzm sh -c 'cat > files/matrix-fixture.json' < /private/path/matrix-fixture.json`. Create its `files` directory first if needed.

Then run the compiled instrumentation class `com.example.MatrixDeviceTest` on emulator-5554. The test trusts only that fixture's CA as DER through the SDK; production uses ordinary system certificate trust and has no certificate-disable setting. The test queues an outgoing message with the same network adapter used by the app, restarts the SDK client and checks one-time delivery. This is a deterministic queue/restart check, not a substitute for testing airplane mode on two physical phones.

Inspect the test homeserver's events afterwards: message events must be `m.room.encrypted` with no plaintext test markers. Remove the fixture from the emulator and your computer afterwards, stop the local test container, and discard its disposable server data deliberately. Never commit credentials, private CA/signing keys, app/session files or database volumes.
