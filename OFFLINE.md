# Nearby and offline use

Local spaces, history, queued messages and Nearby remain available without a Supabase account or internet. Use Continue offline after unlocking. The phone screen lock and optional RoseVault password remain local.

## Connect two nearby phones

1. Install this version of RoseVault on both Android phones. Google Play services must be installed and up to date.
2. Turn on **Bluetooth and Wi-Fi** on both phones. The Wi-Fi network does not need internet, and mobile data can remain off. Airplane mode may require you to re-enable both radios manually.
3. Open and unlock RoseVault. Tap the **Nearby chats** icon beside the lock button.
4. Choose a device nickname and tap **Find nearby phones** on both phones. Allow Nearby/Bluetooth permissions. Android 12/12L also needs the location permission used by the platform for discovery.
5. On one phone, select the other device. Compare the displayed connection code in person, then tap **Codes match on both phones**. Reject mismatching codes.
6. Tap **Back to chats** and open the newly paired nearby conversation. You can send text and encrypted attachments without internet.
7. Keep both apps open and unlocked. Locking, backgrounding, leaving range, or stopping Nearby disconnects the link. Ordinary queued messages stay encrypted on the sender's phone and retry after reconnection.

Nearby conversations have their own local space and are not automatically copied into cloud conversations. Their device identities are pinned through the verified connection. After reinstalling, device keys change and you must pair again.

## Attachments and retention

Nearby attachments use the same 20 MB import limit as the vault. Encrypted attachments are divided into bounded frames and authenticated before displaying them on the receiver. Large transfers depend on radio conditions; an interrupted transfer remains pending and can be retried. A disconnected receiver does not produce a false delivered status.

The Android file picker temporarily leaves RoseVault and triggers the privacy lock. After importing, unlock and reconnect Nearby if needed to deliver the queued attachment. A previously downloaded cloud attachment can be reopened from its encrypted cache without internet; an attachment that has never been downloaded still needs an initial connection.

Memory-free mode intentionally discards message bodies, including pending messages, when the vault locks. Leave memory-free mode off when you want durable offline queues.

Nearby is for local messaging and attachments. Voice/video calls still require the configured internet calling service. It does not provide long-distance offline delivery or background mesh relaying.

## Verification status

The app's encryption, receipt, framing, deduplication, reconnection, persistence, and UI logic are tested in the isolated Android emulator, including with Wi-Fi and cellular disabled. The protocol tests connect two independent device-key repositories through an in-memory frame bridge, not a simulated successful Bluetooth connection. A physical two-phone radio test remains required to verify discovery, real Bluetooth/Wi-Fi range, OEM permissions, and transfer behavior on your actual devices.

Implementation references: [Nearby Connections overview](https://developers.google.com/nearby/overview), [connection authentication](https://developers.google.com/nearby/connections/android/manage-connections), [Android permissions](https://developers.google.com/nearby/connections/android/get-started), and [manual radio management](https://android-developers.googleblog.com/2026/07/upcoming-changes-nearby-connections-api.html).
