# RoseVault password and phone screen lock

Open Security → Set RoseVault password. Confirm phone ownership with Android's credential/strong-biometric prompt, then enter and confirm a password of 12–128 characters. After locking, choose Use RoseVault password, or continue using Verify identity for the phone's screen lock/strong biometric.

Private-space and attachment re-verification can also use the app password; the dialog offers Use phone screen lock instead. Phone-number registration is separate: an SMS code never unlocks the vault or recovers hardware keys.

Change or remove an app password through Security → Manage RoseVault password, after fresh Android identity confirmation. The phone's owner can therefore recover from a forgotten app password without uninstalling the app. This is the intended recovery path. There is no remote password reset or cloud password copy.

The app stores a random 32-byte salt and PBKDF2-HMAC-SHA256 verifier with 600,000 iterations inside a Keystore-encrypted atomic local record. Password derivation runs off the UI thread. Plaintext passwords are not saved, logged, or sent to Supabase. Inputs are masked and not saved across recreation. Canceled/backgrounded verification cannot subsequently unlock the app.

Wrong passwords do not open the vault. Five failures trigger a persisted 30-second delay; further failures increase the delay up to five minutes. Successful verification resets the failure counter. The phone screen lock remains available during this delay. Corrupt password settings fail closed and can be repaired through the owner-confirmed screen-lock path.

The password is an application-level access gate; it does not re-encrypt existing message history with the password or turn an app password into a hardware-enforced Android authentication token. Existing local vault keys remain protected through Android Keystore and app isolation. Rooted/modified operating systems and offline rollback of the attempt record are outside this limiter's guarantees. Local file and key deletion still loses the vault; signing in by phone on a new device does not recover it.

Android references: [Keystore](https://developer.android.com/privacy-and-security/keystore), [cryptography](https://developer.android.com/privacy-and-security/cryptography).
