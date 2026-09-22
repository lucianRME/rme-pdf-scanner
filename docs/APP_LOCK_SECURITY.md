# App Lock Security

Status: v1.5 implementation reference. App lock is an optional, device-local access control for
RME: PDF & Document Scanner.

## Device authentication

- RME uses AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` where the
  platform supports the combined authenticator set.
- On older supported Android releases, RME uses the supported system device-credential prompt.
- The system can authenticate with a strong biometric, device PIN, pattern, or password.
- RME does not create, request, store, verify, throttle, or recover a separate RME PIN.
- Device credentials and biometric templates remain managed by Android; RME never reads or stores
  biometric templates.
- If no secure device lock is configured, RME does not enable app lock silently. It explains the
  requirement and offers a path to Android security settings.
- Cancellation, failed authentication, unavailable authentication, and stale callbacks leave RME
  locked.

## What app lock protects

When enabled, app lock protects access through the RME app interface. On a cold process start,
protected content remains locked until Android reports successful device authentication. Inbound
shares, deep-link-like intents, Activity results, picker results, and queued protected actions are
held until unlock; they are not previewed or dispatched behind an overlay.

App lock does **not** encrypt the local RME document library at rest. Local files remain protected
by Android device storage security. App lock is UI access control, not a portable encryption key.

## Auto-lock

The available choices are immediately, one minute, and five minutes. The default is one minute.
Elapsed-realtime timestamps are used for timeout decisions. Moving the app to the background
cancels an active system-authentication attempt, and a stale callback cannot unlock a newer
session.

## Portable backup encryption is separate

Encrypted portable backups continue to use a user-defined **backup password**. That password:

- remains required for encrypted portable backups;
- is independent of Android device credentials and app lock;
- works when restoring on another phone; and
- is never replaced by, derived from, or used as the device PIN/pattern/password.

Backup encryption protects the portable backup artifact. App lock protects UI access on the current
device. Neither feature introduces an RME account, remote recovery service, master credential,
analytics, telemetry, or network transport.
