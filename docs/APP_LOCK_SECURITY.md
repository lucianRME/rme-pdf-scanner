# App Lock Security

Status: v1.5 implementation reference. App lock is an optional, device-local access control for
the RME app interface. It is not a replacement for encrypted storage or a portable-backup
password.

## What app lock protects

When enabled, app lock protects access through the RME app interface. On a cold process start,
RME composes only the lock screen until the user authenticates. Protected activity results,
deep-link-like entry points, and inbound shares are held until the lock is cleared; they are not
delivered to document UI while the app is locked.

App lock does **not** encrypt the local RME document library at rest. Files in the local library
remain protected by Android's device storage security, not by the RME app-lock PIN. RME cannot
recover a lost PIN, and there is no SynapseWorks account, remote recovery service, master PIN, or
master recovery key. A user who loses every configured authentication path may be unable to open
the app interface; app lock does not delete documents after failed attempts.

## PIN credential

- PINs contain at least six digits.
- RME never stores the PIN characters or a plaintext PIN.
- Enrollment derives a 32-byte value with PBKDF2-HMAC-SHA256 using a random salt and 310,000
  iterations.
- The verifier is protected with a non-exportable HMAC-SHA256 pepper held in Android Keystore.
- Failed attempts use persisted retry throttling. Throttling state survives process restarts.
- PIN replacement and app-lock settings changes require an authenticated process-local session.

PIN characters and transient password input are cleared on the best-effort paths provided by the
implementation. No PIN, document content, OCR text, or unlocked-session proof is written to the
app-lock preferences.

## Biometric unlock

RME uses AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG` only. RME never reads, stores, or
transmits biometric templates. Android owns biometric enrollment and matching.

The biometric path uses a device-local, non-exportable Android Keystore AES key to bind the prompt
to a cryptographic proof. Biometric cancellation or failure leaves the app locked. If the Keystore
key is invalidated, RME disables biometric unlock and leaves PIN unlock available; it does not
silently enroll a replacement key during unlock. A replacement biometric key requires an
authenticated PIN session.

## Auto-lock and lifecycle

The user can choose:

- immediately when the app backgrounds;
- after one minute (the default); or
- after five minutes.

The timer uses Android elapsed-realtime values. A cold process start is locked whenever app lock is
enabled. Moving the app to the background cancels an active biometric attempt, and a stale prompt
callback cannot unlock a newer session. The user can also lock the app immediately from settings.

## Protected entry points

RME gates normal app content, activity-result callbacks, inbound `ACTION_SEND`/`ACTION_SEND_MULTIPLE`
shares, and protected navigation work. A locked inbound share is queued in memory and is processed
only after successful unlock; it does not preview or import content behind a fake overlay. Picker
results are likewise held until the app is unlocked.

## Portable backups

App lock and portable-backup encryption solve different problems:

- App lock protects access through this app on this device and does not encrypt local files at rest.
- An encrypted RME portable backup encrypts the complete backup ZIP, including titles, folder names,
  OCR text, metadata, and checksums, with the user-supplied backup password.
- The backup password is independent of the app-lock PIN and is not derived from Android Keystore
  material, so an encrypted backup can be restored on another device.

Keep a verified encrypted backup and its password separately from the locked device. RME provides
no remote PIN recovery.
