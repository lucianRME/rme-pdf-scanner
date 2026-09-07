# Privacy Boundaries

RME PDF Scanner is intended to keep document handling local and user-controlled. This document defines intended boundaries for future implementation and highlights behavior that must be validated before public claims are strengthened.

## Document Data Lifecycle

- Captured pages originate from the system scanner.
- Scan results are handled locally by RME PDF Scanner.
- Temporary files exist only as long as required for review, PDF preparation, save, share, retry, or cleanup.
- A prepared searchable PDF is created only in RME PDF Scanner's private cache. It is deleted after a successful SAF write, a write failure or cancellation, generator failure or cancellation, or an explicit discard when destination selection is cancelled.
- PDF sharing uses the scanner URI directly when Android can grant it safely. Otherwise, RME PDF Scanner creates a byte-for-byte copy in its private `shared-pdfs` cache and exposes only that file through a temporary-read FileProvider URI.
- Failed share preparation deletes partial cache copies immediately. Completed share copies remain cache data, may be evicted by Android, and are removed by RME PDF Scanner when they are at least 24 hours old on a later app start.
- Exported files are written only to a destination chosen by the user.
- A normal-PDF destination stream opened before a source failure is closed immediately; RME PDF Scanner
  never reports that failed export as saved. Unavailable or malformed SAF source/destination
  boundaries are reduced to safe error categories without logging the URI or document details.
- RME PDF Scanner does not retain its own cloud copy.
- Document contents must not be logged.

Temporary file ownership and cleanup must be explicit in implementation, including error and cancellation paths.

## External Components

The scanner and OCR stack are provided through Google ML Kit and Google Play services.

Assumptions requiring implementation validation:

- Document scanning and OCR content processing occur on-device; document images and recognized text
  are not sent to Google by ML Kit.
- Required scanner components may need to be downloaded or updated by Google Play Services.
- RME PDF Scanner itself should not require the INTERNET permission for this flow.
- ML Kit may transmit encrypted technical diagnostics to Google, including device/app information,
  feature configuration, performance/error data, and per-installation identifiers. It is a
  separately licensed external SDK, not RME PDF Scanner-operated analytics.
- Google Play Services is an external platform dependency with its own behavior and privacy terms.
- Public privacy wording and Play Data safety declarations must reflect this SDK collection and the
  real first-run and ongoing scanner behavior observed during implementation.

Do not claim absolute offline behavior until implementation testing confirms the real behavior on physical devices.

## User-Selected Cloud Providers

Google Drive, OneDrive, Dropbox, or other providers may appear in Android's system file picker.

- RME PDF Scanner should not integrate cloud-provider SDKs for the MVP.
- RME PDF Scanner should not receive provider account credentials.
- RME PDF Scanner should write only to the destination selected through the Android system interface.
- When the user chooses a provider, that provider handles storage under its own terms.

## Backup

Android automatic backup is disabled with `android:allowBackup="false"`. The backup and data
extraction rules additionally exclude all private root, files, database, shared-preferences, and
external domains from cloud backup and device transfer. The exclusions are defense in depth for
older/platform-specific backup behavior; no document, OCR, cache, or private app data is intended
to be backed up or transferred by RME PDF Scanner.

## Logging And Debugging

Do not log:

- Document images.
- OCR text.
- File names.
- File paths containing personal information.
- Document metadata.
- Content URIs.
- Share destinations.

Allowed diagnostics should be limited to technical error categories that contain no document content, no user-selected destination details, and no sensitive metadata.

## Permissions

Intended permission approach:

- No INTERNET permission.
- No broad storage permission.
- No account permission.
- No location, contacts, microphone, phone, or advertising permissions.
- Camera permission should not be added if the selected system scanner does not require RME PDF Scanner to request it directly.

Any future permission must be justified by a user-initiated core feature and reviewed against a platform alternative.

## Release Signing

- Release upload signing credentials are developer- or CI-local configuration, never repository content.
- Local release verification may use an unsigned bundle or a debug-signed release-equivalent artifact, but neither is a Play upload artifact.
- Production signing may be enabled only from documented environment variables or ignored `local.properties` values.
