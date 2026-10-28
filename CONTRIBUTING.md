# Contributing

RoseVault is an early, unaudited Android secure-chat reference implementation. Contributions should keep the existing vault format, device identities and offline outbox compatible unless a migration is explicitly included.

1. Fork and clone this repository.
2. Install JDK 21 and Android SDK platform/build tools 36. The checked-in wrapper uses Gradle 8.13 with distribution checksum verification.
3. Run `./gradlew -PenableCloud=false :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
4. Install Node.js 22+, run `npm ci`, then `npm test`.
5. To test backend changes, start Docker, run `npm run backend:start`, then `npm run test:rls`. The policy suite creates fictional fixtures inside a transaction and rolls back; its runner only targets the local backend.
6. For cloud development, deploy your own Supabase project using SETUP.md. Never commit supabase.properties, credentials, user data, test sessions, signing keys, or a real SMS test-code bypass.

Use issue/PR descriptions that explain the behavior changed, relevant tests, and compatibility implications. Discuss changes to encryption, account binding, key rotation, authentication, expiry or access policies before merging. Avoid logging phone numbers, plaintext content, OTPs or session tokens.

Native instrumentation tests live in app/src/androidTest and require an Android test device. The optional Supabase transport test is skipped unless a test operator supplies its isolated, test-only fixture in the test app's private files. Production source has no fixture login endpoint.

Your clone generates a different debug signing key. Do not uninstall another build's vault to work around a certificate mismatch: that destroys the Android Keystore identity. Use separate test devices or a distinct development application ID for your own experiments.

Contributions to this project are under the MIT license. Dependencies retain their original licenses.
