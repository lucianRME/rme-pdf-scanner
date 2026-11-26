# RME: Private PDF Scanner Privacy Policy

Effective date: 17 September 2026

RME: Private PDF Scanner is an Android document scanner published by SynapseWorks. This policy describes the
`org.synapseworks.pageharbor` application, version `1.0.0` and later versions that retain the
same practices. It is written for users and supports the app's Google Play distribution.

## What RME PDF Scanner does with documents

RME PDF Scanner processes scan images, OCR text, and searchable-PDF text layers on the device. It does
not operate a document backend, cloud storage service, account system, advertising service, or its
own analytics or tracking service. RME PDF Scanner does not send document images, OCR text, or generated
PDF content to an RME PDF Scanner server because it has no such server.

Scanned pages, user-selected images, Android-shared images, locally rendered PDF pages, and OCR
results begin as active-session data. External content URIs remain user/provider owned and RME never
deletes them. An unsaved active session is not recovered after process death. PDF import uses a
bounded app-private source copy and temporary rendered page files;
the source copy is removed after preparation and rendered pages are removed on cancellation,
replacement, removal, discard, or session cleanup. Orphaned cache files from process death are
removed when stale.

When the user explicitly chooses **Save to RME**, RME creates a private persistent library copy of
each page and stores its title, timestamps, page order, rotation/filter state, folder assignment,
thumbnail, and OCR indexing status in a local Room database. If the user has explicitly run OCR,
the recognized page text and safe error category are also stored locally so title and OCR search
work after restart. This private library data is not uploaded, shared, or automatically backed up
by RME. A user may explicitly create a portable backup, optionally encrypt it with a password, and
choose its destination through Android's system file picker.

A temporary searchable PDF or share copy may be held in the app cache only while it is needed for
the requested save, share, retry, or cleanup operation. RME PDF Scanner removes prepared searchable
PDFs after success, failure, cancellation, or discard; completed share copies are cache data and are
cleaned when stale. RME PDF Scanner disables Android backup and excludes its private data from cloud
backup and device transfer.

## Saving and sharing

RME PDF Scanner uses Android's system file picker for selecting imports and save destinations, and
Android's share sheet for receiving supported user-shared files and sending exports. The user chooses
the source, destination, or receiving app. If the user selects a third-party storage provider or share
target, that provider handles the selected file under its own terms and privacy policy. RME PDF
Scanner does not receive that provider's account credentials and requests no broad storage access.

## Google ML Kit and Google Play services

RME PDF Scanner uses Google ML Kit Document Scanner, bundled Latin text recognition, and optional
Chinese, Japanese, Korean, and Devanagari OCR models delivered through Google Play services.
Document scanning and OCR content processing occur on-device after the required component is
available. According to Google's ML Kit documentation,
the SDK does not send document images, video, text input, or text-recognition output to Google.

ML Kit may send encrypted technical diagnostics to Google. Google documents these as device and app
information, feature/configuration information, performance and error data, and per-installation
identifiers used for diagnostics, reliability, compatibility, and improvement. This is SDK data
collection outside RME PDF Scanner's own code; it is not advertising and does not include document
content. ML Kit and Google Play services are governed by Google's terms and privacy practices.

The document scanner and optional OCR scripts may obtain resources through Google Play services. Consequently,
RME PDF Scanner does not promise that a device can acquire or update every Google-provided component
without network access, even though the app itself declares neither the `INTERNET` nor
`ACCESS_NETWORK_STATE` permission and does not itself send document content to a server.

## Permissions and security

RME PDF Scanner declares no `INTERNET`, `ACCESS_NETWORK_STATE`, broad-storage, account, location,
contacts, microphone, phone, or advertising permissions. The Google-provided Document scan flow owns its camera interaction and does not require RME to request camera access.

Source update, 3 November 2026 (local unreleased M10 candidate; public publication is separate): the optional **Continuous camera** flow asks for Android CAMERA permission only when the user chooses Allow camera. It uses AndroidX CameraX locally, with no account, model download, audio recording, cloud inference or network processing. The camera is released when the capture screen leaves the foreground or app lock hides it. Denial or unavailable hardware leaves Document scan and system file import available. Ready pages are private temporary files; Cancel deletes the batch, while Use transfers those pages into the existing unsaved document lifecycle. Nonidentity image orientation is normalized locally before page adoption. Unsaved pages are not recovered after process death; orphaned capture files are removed on the next process's capture-store initialization.

Files shared from RME PDF Scanner use a non-exported
FileProvider with temporary read access limited to the exact URI granted by the app. Its configured
roots cover private cache areas required by imports/exports and the private library used to reopen
saved pages; no directory is independently browsable through the provider. The exported launcher Activity accepts only supported image/PDF send
intents and validates every incoming URI before it enters the active document.

## Retention and deletion

Saved library documents remain in app-private storage until the user deletes the document, clears
the app's data, or uninstalls the app. Deleting a saved document permanently removes RME's page copies,
thumbnail, metadata, and indexed OCR text; it never deletes the external files originally imported.
Deleting a folder keeps its documents and moves them out of that folder. This version has no trash or
restore layer. Users separately control files they save or send through Android and can delete those
files from the chosen destination. Android or third-party destination providers may apply their own
retention policies. For ML Kit diagnostic data, consult Google's applicable privacy documentation and
request mechanisms.

## Contact

For source and product questions, use the project's issue tracker:
<https://github.com/lucianRME/rme-pdf-scanner/issues>.

The public policy is available at <https://synapseworks.org/rme-pdf-scanner/privacy/>. For privacy
or security concerns, contact [security@synapseworks.org](mailto:security@synapseworks.org).

## Changes

If RME PDF Scanner changes its permissions, data handling, dependencies, storage, or sharing behavior,
SynapseWorks will update this policy and its Google Play Data safety declaration before distributing
the changed build.

### Local M11 book-splitting source update (unreleased)

Book split analyzes a bounded image copy locally after an explicit user action. It adds no SDK, model download, network processing or permission. Proposals are reviewed before Use; uncertain pages can remain unchanged. Original sources are never opened for writing. Accepted halves are separate upright JPEG sources, with fresh per-half OCR and the existing editable visual settings. This is straight-line splitting, not curvature correction or redaction/sanitization. Original PDF provenance can remain in the document even when its raster page is split.

Private `cache/book-splits` workspaces hold bounded snapshots, previews and derived outputs. Cancel, backgrounding, recreation and app lock close review; owned workers settle and remove unpublished files. Use transfers completed halves to the existing session/library/backup lifecycle. Replaced app-temporary sources are released only through existing source leases; external/library source bytes are not rewritten by splitting. Unsaved book review is not recovered after process death; orphan workspaces are removed when the book store next initializes in a new process. Saved library pages have their existing durable lifetime. Public policy/release publication remains separate.


### Unreleased M12 page-curvature review

The local page-curvature workflow analyzes a bounded upright image and proposes nonlinear vertical text-line correction for supported mild curves. It uses original Kotlin geometry and platform bitmap sampling, with no new dependency, model download, permission, network or document upload. Ambiguous inputs can remain unchanged or use explicit manual review. It does not restore obscured detail, recover horizontal foreshortening or promise physical-book/OCR quality.

Before acceptance, private `page-curvature` cache files belong to an opaque review owner and a document-session lease. Draft changes always resample the same unfiltered source snapshot. Explicit Use publishes a new upright JPEG source with fresh page/OCR identity; inherited visual adjustments apply once downstream. Original bytes are not rewritten. Cancel/failure/background/lock/recreation release staged files after workers settle. New-process store initialization cleans abandoned workspaces; unsaved review is not recovered after process death. Accepted files transfer to normal session/library ownership and cleanup. External originals and original PDF provenance remain separate; this is not source-PDF sanitization.

## Handwritten signatures (M15 local implementation)

Handwriting is an on-device visual mark, not certificate-backed signing, identity verification or document-integrity certification. The pad records spatial points only, with no handwriting timing/pressure profile. Unsaved drawings remain in memory and are discarded on Cancel, lock, backgrounding or document replacement; configuration recreation retains the draft. Reusable templates are saved only by explicit Save/Replace, under app-private `noBackupFilesDir/signatures`. They are excluded from both portable document backup and automatic cloud/system backup. Rename and Delete manage the reusable asset; deletion does not erase frozen copies already placed in documents, history frames, export captures or previously exported/shared files. Uninstall/clear app data removes the private template library; no physical secure-erasure claim is made.

A saved library file is bounded and atomically replaced on an IO worker. Failed new writes preserve the previous library. Pending writes are cleaned after failure/cancellation and on the next library open; a process killed during a write may retain a pending file until that cleanup. Corrupt libraries fail closed and are not overwritten by a new Save. Explicitly saved assets can survive subsequent placement cancellation: template management is separate from document Undo/Redo.

Placed signatures contain immutable grouped vectors/styles in the editable document layer. Library reopen and explicit plain/encrypted document backups retain these copies, independently of template names or deletion. PDF/JPEG exports flatten final appearance; source bytes and original-source OCR policy stay unchanged. Secure redaction operates on the final composed appearance, with the existing image-only verifier; a decorative signature does not securely redact content. Existing M11/M12 geometry changes reject annotated pages rather than moving unchanged coordinates to new geometry. Room7 adds a no-DDL migration from Room6 to guard the new grouped layer encoding; backup6 requires reader6 and still reads supported older formats. No dependency, permission, telemetry, network processing, proprietary asset or model download is introduced. Public release/publication remains separate.
