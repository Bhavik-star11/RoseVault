# Security policy and limitations

RoseVault 2.0 is an experimental, unaudited Matrix migration pilot. Do not describe it as an independently reviewed production secure messenger. Report vulnerabilities through GitHub private vulnerability reporting when available; never publish tokens, passwords, recovery secrets, phone numbers or private content in ordinary issues.

## Local vault and app lock

Local vault state and media use AES-256-GCM with Android Keystore keys and atomic writes. Hardware key deletion, uninstalling or clearing app data loses this local vault. Android backup is disabled. Device credentials/strong biometrics unlock the app; an optional separate RoseVault password uses a salted, encrypted PBKDF2-HMAC-SHA256 verifier with a local attempt limiter. Owner confirmation is required to change it. Device credentials remain its explicit recovery route. The app password is an application gate, not a hardware authentication token or a replacement encryption key. See APP_PASSWORD.md.

Locking hides the UI and pauses the active Matrix SDK objects and Nearby connection. Rooted/modified systems, memory extraction, attempt-file rollback and an already-unlocked compromised phone are outside these guarantees. OS screenshots are blocked by default. Users can change the screen privacy setting.

## Matrix internet messaging

The pinned Apache-2.0 Matrix Rust SDK owns Olm/Megolm encryption, key distribution, encrypted room messages, timelines, SQLite persistence, local echoes, durable queues, device verification and encrypted key backup. RoseVault does not implement a second Matrix crypto protocol or a custom plaintext send fallback. New DMs are invite-only and encrypted; the client refuses to open unencrypted rooms or send through this DM surface when more than two members are active.

The SDK store has a random high-entropy passphrase. That passphrase and session/access/refresh tokens are sealed with Android Keystore in app-private storage. Session refresh is bound to the saved user ID. Production uses normal HTTPS certificate validation; there is no UI certificate-bypass switch. Instrumentation can supply a disposable local test CA, which still verifies the certificate chain and hostname. No passwords or recovery keys belong in notifications, URLs, issue reports or Git.

A Matrix password authenticates the account; it does not prove the identity of every device. Compare the SDK's SAS values through a trusted channel before approving verification. Do not approve mismatches. Changed identities require review. Save the encrypted recovery secret privately and test restoration before relying on historical access. Account password and server backups alone do not restore lost client encryption keys. Forward-secrecy and compromise-recovery properties are those of the SDK/protocol and key-sharing policy, not a guarantee that every historic message becomes inaccessible after compromise.

Homeserver operators can see accounts, IP addresses, membership, room state, timing, sizes and other metadata. Encryption protects message content, not all metadata. Matrix login uses usernames; it does not verify ownership of a phone number. Public homeservers requiring OAuth need a separate sign-in flow; this pilot targets a private password-enabled Synapse deployment. The server template disables public signup and federation and exposes client routes through HTTPS. Operators remain responsible for updates, abuse controls, backups, retention and account deletion.

Attachments use the SDK's encrypted media upload/download. RoseVault rejects media without an encrypted source and limits the UI to 20 MB. Opening an attachment gives an external viewer access to a private temporary plaintext copy; that viewer may retain it. Temporary shared copies are removed on the next vault unlock/cache cleanup, not guaranteed immediately on lock. Files copied outside the app cannot be recalled. Device storage compromise remains out of scope.

## Offline, Nearby and legacy data

Matrix queues outgoing events locally and catches up while the Matrix screen is open/unlocked. Server acceptance is Sent, not proof of recipient delivery or reading. A fully disconnected phone cannot receive internet traffic. The current build has no closed-app Matrix push receiver or gateway. Private/Stealth generic notifications and a test notification are available, but they do not establish live background delivery.

Nearby remains a separate Google Play services transport using confirmed pairing, pinned P-256 ECDH keys, HKDF-SHA256, authenticated envelopes, acknowledgements, bounded frames and durable queues. Both apps must be unlocked and in range. This legacy static-ECDH protocol has no double ratchet, forward secrecy, multi-device fan-out or account recovery. Its safety numbers are separate from Matrix SAS. It is not long-distance mesh networking.

The old Supabase/Twilio backend and encrypted WebRTC signaling are disabled in this build. Their source, schema and tests remain as legacy reference; deploying them does not enable Matrix phone login or calls. Existing local/Nearby history is preserved separately; old cloud history is not silently migrated. Local memory-free/expiry controls belong to the legacy vault and do not promise Matrix server deletion, key erasure or room retention.

## Consent tools and optional Rose AI

Private connection intentions stay in the encrypted local vault until a user explicitly shares an offer. Mutual consent is required for shared-space changes. These records currently belong to local/Nearby chats, not Matrix rooms. They do not score compatibility or pressure changes.

Rose submits only manually typed, reviewed context to an operator-configured HTTPS Ollama service. The service operator can read it; it is not end-to-end encrypted past that service and is not offline inference. The app saves encrypted connection settings but no prompt history. General communication guidance is not diagnosis, counseling or a relationship verdict. Limited keyword checks are not comprehensive crisis detection. The deployment example restricts the API behind a token; operators must add suitable access/rate controls and protect logs, backups and the host.

## Release process

Never commit backend secrets, signing keys, personal fixtures, test sessions, private CA keys or database volumes. Review the public file list, run unit/lint/device checks, and read VALIDATION.md for actual results and remaining gaps. A debug build is not a reviewed store release. Updates require the same signing certificate; preserve a maintained release-signing key outside Git. Dependencies and server components retain their own licenses; see THIRD_PARTY_NOTICES.md.
