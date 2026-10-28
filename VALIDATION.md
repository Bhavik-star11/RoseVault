# Validation — Supabase edition 1.5.0

## Checked in this workspace

- Native debug APK and instrumentation APK build; Android lint reports no errors.
- Eleven JVM cases pass for envelope encryption, phone input and password derivation/policy.
- Twenty-three existing Android cases pass: local password storage/unlock, vault/media/migration behavior, phone form validation/offline continuation, local buttons, navigation and Nearby protocol/prerequisite checks.
- The optional native Supabase transport test passes separately against an isolated synthetic account: token refresh, protected conversation reads, private file upload/download/idempotent retry, encrypted-envelope insert/read/delete, call create/end transition, profile lookup and honest missing-relay feedback. The first attempt ran before emulator DNS was ready; the rerun passed after network validation.
- Thirty-one transactional PostgreSQL/Storage policy checks pass in a dedicated project. They cover anonymous access denial, verified registration, hidden profiles/counters, invalid keys, opt-out, pinned identity/membership, encrypted-envelope constraints, impersonation, immutable messages/keys, private files, recipient-only call acceptance and stale-OTP rejection. The fixtures are rolled back.
- Eight Node tests pass for short-lived TURN credentials, authentication checks, unverified users, missing configuration, rate limits and secret exclusion.
- Supabase security advisors found no warnings/errors. Two informational findings (RLS without policies on private profiles/counters) are intentional direct-access denial.

The optional integration fixture uses synthetic confirmed accounts and a test-only session, not a real SMS flow. It is supplied only to the instrumentation test APK; production source has no token-injection/test-login endpoint. Synthetic accounts, rows and files are removed after verification. No real SMS or email is sent by these tests.

## Publication checks

The public repository excludes local backend properties, Firebase configuration, credentials, signing keys, test sessions, build output and caches. It includes MIT licensing, contribution/security notes, dependency manifests/lockfile, Gradle wrapper checksum, schema migration, access policies, credential function and CI workflow. CI has not passed merely because its file exists; check the repository's Actions results after publication.

## Remaining real-world verification

A real SMS provider and TURN relay credentials have not been supplied. Live phone OTP and real two-network audio/video calls are not claimed. Two physical phones are still required for radio discovery/range/large transfers and owner credential/biometric/camera behavior. There is no background push/ringing or automatic cloud object-cleanup schedule. Cloud sync uses foreground polling. See SETUP.md and SECURITY.md for release limits.
