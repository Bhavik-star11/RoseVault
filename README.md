# RoseVault

An experimental, MIT-licensed Android private vault and relationship messenger. Native Kotlin/Jetpack Compose, Android Keystore, **Matrix Rust SDK** for encrypted internet chat, and Nearby Connections for phone-to-phone offline messages. Android 12+.

![RoseVault chats](screenshots/chats.png)

## Test free on your computer

Run Synapse and PostgreSQL locally with Docker and use the separate RoseVault Local build on two phones on the same trusted Wi-Fi. No VPS, domain or SMS purchase is required. Follow [LOCAL_MATRIX.md](LOCAL_MATRIX.md). The published internet-chat build still requires HTTPS; the development build alone allows private-IP HTTP with disposable accounts.

## Matrix migration pilot

Unlock the vault and tap **Matrix** in the top bar. Sign in to your own HTTPS Synapse homeserver with a full ID such as `@alice:matrix.example.org`. Matrix replaces the previous Supabase chat direction; Twilio SMS is not needed for Matrix account login. Public signup stays disabled: the homeserver operator issues accounts.

The SDK handles Olm/Megolm, encrypted room creation, timeline local echoes, persisted send queues, reconnect sync and per-device keys. The UI offers invitation review, text chat, pagination, optional read receipts/typing, reactions, edit/redaction actions, encrypted file attachments, SDK SAS verification and encrypted recovery setup. A compiling action is not proof of a successful live security flow: see [VALIDATION.md](VALIDATION.md) for tested versus remaining checks.

This is a migration pilot, **not an independently audited or production-ready secure messenger**. Existing local vault and Nearby data remain separate and readable. Supabase-only accounts/history do not automatically become Matrix accounts/history. The old cloud phone backend is disabled, not silently reused for Matrix messages. Its source and operator documents remain as legacy reference.

Matrix protects message content; homeservers still see accounts, room state/membership, event timing and traffic metadata. Verify devices and save a private recovery secret before relying on long-term history. Clearing app data removes local hardware keys. Read [SECURITY.md](SECURITY.md) and [MATRIX_SETUP.md](MATRIX_SETUP.md).

## Local vault and Nearby

- Encrypted local notes, saved messages, media import/open/delete, calendar and a persistent outbox.
- Phone screen-lock/strong-biometric entry, optional separate RoseVault password, inactivity/background locking and screenshot protection.
- Nearby text/attachment exchange with paired safety-number comparison, acknowledgements, deduplication and reconnect queues. Both apps must be open and unlocked with Bluetooth/Wi-Fi and Google Play services available.
- Optional Connection Spaces/Our Story tools in the legacy/local chat surface; their custom consent records are not yet mapped into Matrix events. Neutral Matrix chat does not impose relationship labels.

## Optional services and limits

[SELF_HOSTED_SERVICES.md](SELF_HOSTED_SERVICES.md) explains the selected self-hosted Ollama and ntfy/UnifiedPush direction. Rose is a manually reviewed HTTPS Ollama client; only the typed context is submitted and the server operator can read it. No prompt history is saved by the app. It is general communication support, not counseling, diagnosis or relationship judgment. It requires a reachable server and cannot run on a fully disconnected phone.

Private/Stealth notification builders and a local privacy test are available. **Closed-app Matrix push, a UnifiedPush gateway/receiver, MatrixRTC/LiveKit calls, movie synchronization, on-device LLM inference, and automatic migration are not activated by this pilot.** A homeserver does not carry offline internet calls. Nearby is the independent offline radio transport.

## Phone login: trial and upgrade

The earlier Supabase phone-login pilot used Twilio Verify. Its trial could send codes only to recipients verified in the Twilio account; public SMS registration required an operator upgrade and regional setup. Recipient numbers and OTPs must never be committed. Matrix usernames avoid that SMS dependency, but hosting, storage, bandwidth and call relays still have costs. App users do not need their own Twilio subscription.

## Build and run

1. Install JDK 21 and Android SDK platform/build tools 36.
2. Run `./gradlew :app:assembleDebug`. The pinned Matrix SDK is fetched from Maven Central. For a smaller APK for modern ARM64 phones, use `./gradlew -PtargetAbi=arm64-v8a :app:assembleDebug`; omit that option for the universal emulator/phone build. No `google-services.json`, Supabase key or Twilio credential is needed for this build. Old `enableCloud` arguments do not enable the retired phone transport.
3. Install `app/build/outputs/apk/debug/app-debug.apk`. Preserve your existing vault when updating with the same signing key; uninstalling/clearing app data destroys its local keys.
4. Deploy the private homeserver using [MATRIX_SETUP.md](MATRIX_SETUP.md), create normal accounts, open Matrix in the app, and compare devices before sensitive chat.

A clone generates its own debug signing key and cannot update an APK signed by a different developer. Public distribution needs a maintained release-signing process; never commit signing keys.

## Verify

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

Native device tests are under `app/src/androidTest`. The opt-in Matrix test needs a separate local Synapse fixture with two disposable accounts and a test CA; production login has no injected-token or certificate-bypass route. Legacy Supabase database/relay tests remain in `tests` and are not Matrix encryption tests.

## Documentation

- [MATRIX_SETUP.md](MATRIX_SETUP.md): concepts, pinned SDK, Synapse/Postgres/HTTPS, Android architecture and rollout tests.
- [SELF_HOSTED_SERVICES.md](SELF_HOSTED_SERVICES.md): Ollama, ntfy, consent features and honest feature limits.
- [APP_PASSWORD.md](APP_PASSWORD.md), [OFFLINE.md](OFFLINE.md), [VALIDATION.md](VALIDATION.md).
- [SETUP.md](SETUP.md) and [REMOTE_CALLS_SETUP.md](REMOTE_CALLS_SETUP.md): legacy Supabase pilot references; do not deploy them expecting Matrix calls.
- [CONTRIBUTING.md](CONTRIBUTING.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [LICENSE](LICENSE).

The MIT license covers RoseVault's original source. Matrix Rust SDK is Apache-2.0; Synapse and other server components retain their own licenses. Nearby still requires Google Play services. Preserve upstream notices when distributing binaries.
