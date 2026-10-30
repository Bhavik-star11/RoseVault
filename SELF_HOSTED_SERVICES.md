# Self-hosted Rose and notifications

These are operator setup instructions, not evidence of an active deployment. No server has been purchased or configured for the hosted pilot. Matrix is the new messaging direction; old Supabase accounts/history are not migrated automatically. See MATRIX_SETUP.md for the homeserver.

## Rose AI: Ollama

The Android client is in Security → Open optional Rose AI. It submits only text explicitly reviewed and approved in that dialog to the configured HTTPS origin's `/api/chat`. The server operator can read that text. TLS is transport protection, not end-to-end encryption against the AI server. There is no automatic chat retrieval, cloud-provider fallback, prompt history, automatic sending to a partner, or AI access to vault files/tools.

A reachable server is required. A server on the user's local network can run without an external AI service, but a completely disconnected phone cannot use it. Model weights must be downloaded to the server before offline/local-network use. A future on-device edition would be a separate implementation.

1. Obtain a Linux host you control and a domain. Review hosting charges first; this repository does not provision or pay for a VPS. Start with a small instruct model and measure RAM, CPU, latency, and concurrent requests before choosing capacity. Running an LLM and a messaging server on an undersized machine is unreliable.
2. Install Ollama using its [official instructions](https://docs.ollama.com/). Keep it bound to `127.0.0.1:11434`; do not expose this port publicly. Disable Ollama cloud features following its current server settings. Do not enable tools, browsing, or cloud model routing.
3. Review the chosen model's license and download it on the server, for example `ollama pull qwen3:4b`. Run a non-sensitive local test first. Model weights are not included in RoseVault's MIT license.
4. Install a TLS reverse proxy such as Caddy. Generate a unique access token with `openssl rand -hex 32`; set it as `ROSE_AI_TOKEN` in the proxy service's private environment. Never commit it. Use [deploy/rose-ai.Caddyfile.example](deploy/rose-ai.Caddyfile.example), replacing the domain. Only `/api/chat` is exposed; all other Ollama APIs stay blocked. Restrict firewall access to HTTPS, configure connection/request limits, and avoid access logs containing prompts or Authorization headers.
5. Validate the proxy configuration before reloading. Verify that unauthenticated `/api/chat` returns 401 and `/api/tags`, `/api/pull`, and other paths are unavailable. Test a harmless authenticated request following [Ollama's API](https://docs.ollama.com/api/chat). Do not put the token in a URL or browser address bar.
6. In RoseVault's Rose settings enter the HTTPS origin, access token, and installed model name; save. The connection file is Keystore-encrypted. Every request has a second review showing the destination and exact context. Verify the response with a harmless draft before submitting personal text.
7. Configure server retention and logging. The client saves no prompt history, but cannot enforce server-side deletion. `keep_alive: 0` requests unloading after a response; it does not prove secure RAM erasure. Clear drafts in the app; use Forget server connection to remove its saved connection/token. Stop waiting cannot recall an already submitted request.

Rose provides general communication suggestions, not counseling, diagnosis, cheating detection, relationship verdicts, or compatibility scoring. Rule-based input/output checks are only a limited English-language safeguard; they miss paraphrases and other languages, can over-trigger, and are not an emergency detector. Test the selected model independently. In danger, use qualified local support rather than the model.

## ntfy / UnifiedPush

This is the selected background-push direction. **The current APK still uses foreground sync; it does not yet include a completed UnifiedPush receiver, device registration backend, or notification dispatch bridge.** Private/Stealth settings and the notification test work locally. Installing ntfy alone does not connect it to RoseVault.

Prepare the host first:

1. Install ntfy from its [official server instructions](https://docs.ntfy.sh/install/) and expose it through HTTPS on a separate hostname, for example `push.example.org`. Review its current [UnifiedPush configuration](https://docs.ntfy.sh/config/#unifiedpush). Do not enable the Firebase upstream if the deployment must avoid Google push.
2. Use authenticated subscriptions, private administration, limited retention and attachment limits, and least-privilege publishing. Never use phone numbers, names, conversation IDs, or a public shared topic as a device endpoint. Endpoint URLs are capabilities and must not enter logs or GitHub.
3. Install the official ntfy Android distributor and select the self-hosted server. Verify its own background delivery after screen-off, Doze, reboot and reconnect on the intended phones. Fully offline phones cannot receive internet push. Force-stopping an app can prevent delivery until it is opened again.
4. Before enabling RoseVault dispatch, implement the official UnifiedPush connector with distributor selection, endpoint rotation/unregister handling, per-device ownership-protected registration, and bounded generic wake-up payloads. The server bridge must authenticate message events, restrict endpoint hosts to this ntfy deployment, throttle/deduplicate dispatch, and never accept arbitrary URLs for server requests.
5. The receiver should show only a generic private alert without opening/decrypting the vault while locked. Tapping must authenticate before fetching/displaying content. Queue outgoing encrypted work durably and retry after reconnect; server acceptance is **Sent**, not proof that the partner device received it.
6. Test closed app, locked phone, offline recipient, duplicate/replayed wake-ups, distributor removal, denied notification permission, disabled channels, endpoint rotation and expired messages before claiming background delivery. This deployment does not yet pass those live checks.

FCM is not required for this selected direction. Use the Matrix homeserver and a compatible Matrix push gateway for the new messenger. For distant voice/video media, follow [REMOTE_CALLS_SETUP.md](REMOTE_CALLS_SETUP.md); neither SMS nor ntfy carries call media.

## Connection Spaces and Our Story

Each private intention is saved inside the local encrypted vault and is never attached to an ordinary message. The Review invitation step explicitly shares a label with the paired device. Invitations/acceptances/declines/withdrawals use the same encrypted message outbox as chat. Only an acceptance by the other authenticated identity can establish a shared label. Either person can withdraw; nothing is ranked and there are no relationship streaks or compatibility scores.

A neutral chat remains available without choosing or accepting a label. Our Story shares optional journal entries, mood notes and moments as encrypted messages. It is a basic shared feed, not mood analytics, automatic memory extraction or a separate photo backup system. Clearing history can reset consent-derived space state. Invitations are disabled with disappearing or memory-free chat modes; existing history must remain to reconstruct consent. A modified peer client can display anything locally; no label grants access to another vault or weakens message authorization.

## Features from the brainstorming brief still outside this release

Movie playback synchronization, streaming-service integration, scheduled letters, automated anniversaries, shared counter/date consent, group chat, edits/reactions/delete-for-everyone, block/report, key recovery, device revocation, and on-device LLM inference are not implemented by this update. Netflix/Prime downloads must not be transferred or decrypted. A future Movie Space can share an explicit countdown/playback cue and each person's own licensed player; it cannot promise control over DRM apps without an authorized integration.
