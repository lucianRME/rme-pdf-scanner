# Architecture Decision Records

This log records lightweight product and architecture decisions for RME: PDF & Document Scanner. Existing ADRs retain the product name used when the decisions were recorded. Decisions may be revised as implementation validates platform behavior.

## ADR-001: No Proprietary Backend

Decision:
PageHarbor will not operate a backend for the MVP.

Rationale:
The MVP centers on local document processing and user-controlled save or share destinations. A backend would add privacy, security, operational, and trust costs that are not required for scanning, reviewing, exporting, saving, or sharing.

Consequences:
PageHarbor will not provide account sync, remote backup, server-side processing, or a proprietary document portal in the MVP. Features must work through local processing and Android system interfaces.

## ADR-002: User-Controlled File Destinations

Decision:
Use Android system file-selection interfaces rather than direct cloud-provider integrations.

Rationale:
The Android Storage Access Framework lets the user choose local or provider-backed destinations without PageHarbor integrating cloud SDKs or handling account credentials.

Consequences:
Cloud providers may appear as system picker destinations, but PageHarbor does not manage those accounts or storage systems. Provider-specific behavior, availability, and privacy terms remain outside PageHarbor's control.

## ADR-003: Minimal Architecture

Decision:
Introduce abstractions only when required for state management, testability, or isolation of platform integrations.

Rationale:
The MVP is small, and scanner behavior still needs validation. Premature layers would make the code harder to change without improving user value or privacy.

Consequences:
Static screens should stay simple. Flow coordination, adapters, and helper components should be added when they remove real complexity, isolate platform behavior, or make cleanup and error paths testable.

## ADR-008: Local Three-Surface Navigation

Decision:
Use local screen-state navigation for Home, Scan Result, and OCR Result rather than Navigation Compose.

Consequences:
Home owns introduction and scan launch; Scan Result owns post-scan save/share/export/OCR feedback; OCR Result owns in-memory text actions. PageHarbor intentionally has no bottom navigation, drawer, tabs, or internal document library.

## ADR-004: System Document Scanner First

Decision:
Evaluate ML Kit Document Scanner before implementing a custom CameraX scanning pipeline.

Rationale:
A system document scanner may reduce custom camera, crop, page detection, and image-processing work while aligning with a user-initiated scan flow. It may also avoid PageHarbor directly requesting camera permission, depending on validated scanner behavior.

Risks:

- Google Play Services dependency.
- Component availability.
- Initial component download.
- Emulator limitations.
- Limitations on scanner customization.
- Privacy wording.

Validation criteria:

- Successful multi-page scan.
- Cancellation handling.
- Output format and URI behavior.
- Operation without PageHarbor declaring INTERNET.
- Behavior after required components are already installed.
- Behavior on a physical Android device.
- Compatibility with minimum supported Android version.

Consequences:
Implementation should avoid public claims of absolute offline scanning until scanner behavior is tested. If the system scanner does not satisfy privacy, availability, quality, or UX requirements, PageHarbor may revisit a custom scanner approach.

## ADR-005: No Internal Document Library In MVP

Status: superseded by ADR-015 for v1.4; retained as the historical MVP decision.

Decision:
PageHarbor will export documents but will not initially maintain a permanent internal document collection.

Rationale:
The MVP focuses on scanning, review, PDF export, and user-selected save or share. An internal library would require retention rules, backup behavior, indexing, deletion UX, and additional privacy review.

Consequences:
Users control saved files through their chosen destinations. PageHarbor must avoid retaining document copies beyond the active scan and export workflow except where strictly required for retry, sharing, or cleanup.

## ADR-006: Grantable URIs For PDF Sharing

Decision:
Share ML Kit PDF results directly when they use a grantable `content` URI. When ML Kit returns a readable but non-grantable URI, copy the PDF byte-for-byte into PageHarbor's private cache and share it through a narrowly scoped `FileProvider` content URI.

Rationale:
Physical-device validation showed that ML Kit can return a PDF URI that PageHarbor can read but Android cannot safely grant to another application. Raw file locations must not be exposed, and share targets require temporary read access to a content URI.

Consequences:
The FileProvider exposes only the `shared-pdfs` cache directory. Partial copies are deleted after preparation failures. Completed copies are eligible for operating-system cache eviction and PageHarbor removes copies at least 24 hours old when the app starts. No storage permission, network access, or re-encoding is introduced.

## ADR-007: Local OCR Engine

Decision:
Use the bundled Latin-script variant of Google ML Kit Text Recognition v2 as the initial OCR engine. Limit the first language set to English, German, and Romanian. OCR is optional and user initiated: scanning, PDF save, PDF share, and JPEG export work without it. Treat ML Kit Document Scanner as scan acquisition and scanner editing only; it does not expose a recognized-text OCR result.

Rationale:
The bundled Latin model is available without a first-use model download, supports the target languages, returns structured text with geometry and confidence, and has a maintained Android API. This provides the lowest integration and native-maintenance burden while keeping recognition of scanned pages on the device. It is a pragmatic choice despite the project preference for open source: ML Kit is proprietary. Tesseract remains the planned reconsideration path if fully open-source engine ownership becomes more important than the added NDK/JNI, model, and tuning burden.

Consequences:
OCR output is sensitive document content: it stays local to the active session, remains in memory by default rather than being retained permanently, and is never logged or sent to a backend. Copy Text is explicit user action. PageHarbor does not recreate crop, rotate, filters, deletion, or reordering that ML Kit already provides in the scanner flow. The bundled model avoids a first-use OCR model download; this local-only design does not make claims about app-store or platform-service installation and update behavior.

ML Kit dependencies may declare non-exported component-discovery metadata required for their local initialization. PageHarbor removes their transitive CCT transport discovery and scheduling components during manifest merging and requests no network permission. This does not override ML Kit's separately documented SDK behavior: ML Kit may send encrypted technical diagnostics to Google. Public privacy wording and the Play Data safety declaration must disclose that SDK collection while continuing to distinguish it from document content, which ML Kit documents as on-device.

## ADR-009: Local Searchable PDF Composition

Decision:
Create a new local PDF from scanner JPEG pages and append an invisible embedded-font text layer from engine-neutral OCR line geometry. Use PdfBox-Android `2.0.27.0` (Apache-2.0) as the narrow production dependency. Do not mutate the scanner-produced PDF in the first implementation.

Rationale:
JPEG composition gives PageHarbor one controlled coordinate system for the background image and OCR bounds, keeps scanner-PDF parser compatibility out of the critical path, and makes page size, rotation, temporary output, and cleanup explicit. PdfBox-Android provides embedded Unicode-font support and an explicit invisible-text rendering mode that Android `PdfDocument` does not provide with verified interoperability. Its Apache-2.0 license is compatible with PageHarbor; no Bouncy Castle dependency is declared directly.

Consequences:
The completed flow invokes a UI-independent coordinator to run or consume local OCR, prepare a private-cache searchable PDF, and copy it to a user-selected SAF URI. It does not change the existing scanner-PDF save, share, or JPEG-export flows. The coordinator deletes prepared PDFs after write success, write failure, cancellation, or explicit destination-selection cancellation. All OCR geometry and prepared files remain active-session local data and must never be logged, retained as a library, or transmitted. PageHarbor performs no cloud OCR and operates no proprietary backend. Chrome desktop validation is complete; Adobe Acrobat and managed Google Drive viewer validation remain pending because those viewers were unavailable or constrained in the validation environment.

## ADR-010: Deterministic Local Smart Document Suggestions

Decision:
For `v0.5.0-dev` smart-document-output work, use a deterministic, local rule engine over the active in-memory OCR result. It may suggest only broad categories and category-based PDF filenames; it must not copy arbitrary OCR content into names or metadata. Do not add a local model, cloud service, persistence, analytics, or an internal document index.

Rationale:
The initial value is a safe save-name suggestion for a small set of categories—invoice, receipt, letter, form, or unknown—across English, German, and Romanian. Fixed keyword and structure rules are explainable, testable, dependency-free, and work with the existing offline OCR boundary. A model would add binary size, update, evaluation, performance, false-positive, and privacy-review costs without enough demonstrated value for this narrow scope.

Consequences:
The rule engine must operate only for the active user-initiated export and discard input and intermediate text when it returns. Its result uses a non-probabilistic confidence level; any future reason API must use non-content enums only. The safe fallback is `document.pdf`; the Android SAF picker remains authoritative for final naming and duplicate handling. Optional PDF metadata must be off by default, generic rather than OCR-derived, and require explicit user choice in a future product flow. Further UI, metadata, or category work requires separate scope, test, privacy, and device-validation work.

Implementation status:
The local classifier, category-only filename suggester, and searchable-PDF SAF initial-title integration are complete without UI changes. Classification requires two distinct signals and otherwise falls back conservatively to `document.pdf`. Metadata remains intentionally unimplemented. Future metadata, categories, models, or other smart-output expansion requires a separate decision.

## ADR-011: Retain Only Stable Active-Session State Across Configuration Changes

Decision:
Use one Activity-scoped `ViewModel` to retain the current in-memory scan session across `MainActivity` configuration changes. Retain only the current product screen, scan summary, scanner-returned JPEG/PDF URIs, completed OCR result, and selected OCR page index.

Rationale:
The prior Activity-local and Compose-local state returned a user with a completed scan to Home after rotation. A standard retained ViewModel fixes that lifecycle boundary without adding persistent storage, Navigation Compose, or a document library. The retained data is the minimum needed to keep Scan Result and completed OCR Result usable after an Activity recreation.

Consequences:
No `SavedStateHandle`, database, file persistence, background job, or process-death recovery is introduced. Active OCR, PDF generation, SAF write/picker ownership, transient feedback, coroutine jobs, streams, decoded preview bitmaps, PdfBox objects, and prepared private exports remain Activity-owned. Recreation cancels or resets that transient work, cleans prepared private output, returns to a stable Scan Result when a completed scan exists, and permits retry. OCR Result decodes only its current selected scanner JPEG with bounded sampling and has no image cache. The ViewModel must never retain an Activity, Context, document copies, or logging/analytics data. Final Activity/task destruction ends the in-memory session.

## ADR-012: Product Rename Without Application Migration

Decision:
Rename the product from PageHarbor to **RME: PDF & Document Scanner**, with **RME PDF Scanner** for compact UI and natural prose.

Rationale:
The name changes; the existing Google Play application and update path must continue.

Consequences:
Keep `org.synapseworks.pageharbor` as both application ID and namespace. Preserve package directories, signing configuration and keys, version metadata, FileProvider authority, private storage paths, backup rules, and cleanup behavior. This change adds no data migration, permissions, dependencies, networking, or privacy claims. The Gradle display name is presentation metadata only; repository identity stays unchanged. See [branding migration audit](BRANDING_MIGRATION.md) for retained identifiers and external asset follow-up.

## ADR-013: One Session-Local Document Acquisition Pipeline

Decision:
Represent the active document as one in-memory `DocumentSession` containing ordered pages with stable session-only identities, source categories, rotation, and non-destructive filter state. Route scanner output, selected-image, inbound-share, and rendered-PDF-page inputs through one `DocumentAcquisitionCoordinator` with a 20-page default limit, typed errors, interruption, and explicit resource ownership.

Rationale:
Review, reorder, filter, OCR, PDF export, save, and share operate on effective ordered pages rather than on the API that acquired them. Opaque resource references keep URI parsing and stream access at Android boundaries. A single coordinator prevents future import sources from creating parallel document pipelines and centralizes the safety rules for limits, stable identity, cancellation, replacement, and cleanup.

Consequences:
The existing scanner is the first acquisition adapter. Its PDF remains an optional direct-copy optimization only while the active pages match the original order and have no app-level filter or rotation; appended, reordered, rotated, filtered, or non-scanner pages use the existing local recomposition path. This fixes the prior risk of saving a scanner PDF that covered only the pages before an add-pages operation.

Resources are classified as user/external or RME-owned temporary files. User files and external content URIs are never deleted. Acquisition staging files are cleaned on every terminal path, RME-owned page resources transfer to the active session on success, and transferred resources are cleaned on replacement, discard, or final ViewModel invalidation. Cleanup accepts only regular files canonically located below the private root declared by the creating adapter. The session is not persisted. The Stage 0 form of this decision added no picker, share receiver, manifest filter, or `PdfRenderer` workflow; ADR-014 records those v1.3 adapters.

Adapters register RME-owned staging resources with the active acquisition token as soon as those files exist, so cancellation and lifecycle invalidation do not depend on receiving a later completion payload. Canonical file identity is shared by preservation, deduplication, same-file checks, and deletion; paths that resolve to the root, outside it, through an escaping symlink, or cannot be canonicalized are preserved rather than deleted.

Pending registration validates the complete batch before retaining any entry and returns distinct typed outcomes for accepted metadata, invalid ownership metadata, and stale tokens. Every terminal path receives the active session, so cancel, failure, interruption, and stale callbacks preserve any pending path that canonically aliases an active page while still deleting genuinely new RME-owned files. Uncertain ownership never expands cleanup authority.

Activity-owned normal-PDF save, PDF-share, and page-export operations use independent generation tokens bound to a monotonically changing effective-document revision. Successful acquisition, filter, rotation, reorder, replacement, and clear mutations advance the revision; rejected and no-op edits do not. Every picker, chooser, state update, and continuation verifies both values, while a revision observer cancels only work captured from an older document. Discard, replacement, and Activity destruction invalidate callbacks before cancelling jobs. Short-lived session leases delay deletion of RME-owned page sources until every normal export, OCR, or searchable-PDF reader that captured that session has left its terminal path; completed user-selected destination writes remain user-owned. Replacement cleanup is deferred when an old session is leased. Private preparation files that were not handed to a share target are released through idempotent ownership independently of input-page leases.

Scan-result and OCR-result previews share one Activity-composition ownership boundary. A remembered commit-aware slot owns both the currently committed bitmap and any published bitmap awaiting its Compose commit, and its disposal callback exists before asynchronous decoding begins. Decode and transformation run off the main thread; stale, cancelled, failed, replaced, pending-uncommitted, and removed previews release their decoded or transformed bitmap exactly once, while identity transforms retain the same owner and the bitmap currently displayed remains live until replacement commits or the composable is disposed.

Page inputs use a shared pre-allocation policy: at most 64 MiB when the provider exposes source size, at most 6,000 pixels on either edge, and at most 12.5 megapixels. The pixel limit admits a common 4032-by-3024 camera page and typical A4 scans near 300 dpi while bounding ARGB bitmap and filter working memory. Preview and OCR retain their smaller sampled decode policies, but reject sources outside the shared input bounds; transformed JPEG/PDF export rejects an oversized page with a typed failure instead of silently reducing output resolution.

Android acquisition adapters attach a known provider or file-descriptor byte length before decode; an unavailable length remains unknown rather than becoming a rejection. The full-resolution transform combines EXIF orientation with session rotation, recycles the replaced bitmap immediately, and filters through one scanline plus a 256-entry histogram. It therefore holds no full-page pixel arrays and at most two full-resolution ARGB bitmaps under its control (about 97.6 MB for 4032 by 3024), while keeping the existing 12.5-megapixel quality ceiling. Physical-device heap behavior remains a release check.

## ADR-014: Standards-Based Import Into the Active Document

Decision:
Use Android's multi-document Storage Access Framework picker and narrowly scoped `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filters for JPEG, PNG, WebP, and PDF. Route all accepted inputs through the existing acquisition coordinator. Append to a useful active session; replace only an empty session.

For PDF input, copy at most 128 MiB to a seekable app-private file and use Android `PdfRenderer` sequentially. Render each page as a temporary JPEG at 144 dpi where possible, bounded to 4,096 pixels per edge and 12.5 megapixels, and retain no source-PDF modification or persistent private copy.

Rationale:
System pickers and Android sharing make exported scans from other apps available without broad storage permission, provider SDKs, accounts, or proprietary migration formats. Converging before review preserves one OCR/export pipeline and makes active-session behavior predictable.

Consequences:
RME validates signatures and actual readability rather than trusting names or declared provider types. A document remains limited to 20 pages. Invalid items may be skipped when another selected item succeeds; a page-limit violation rejects the operation so the active document is never partially overfilled. Encrypted, damaged, or unsupported PDFs share one user-safe unreadable-PDF result because `PdfRenderer` does not expose a stable cross-version encryption classification.

Picker cancellation and explicit processing cancellation preserve an existing session. Activity recreation or destruction interrupts active preparation, registered owned resources are cleaned, and stale completion is rejected. External URIs are never deleted. Rendered pages live below a dedicated private import root and are removed on page removal, replacement, discard, cancellation, or final session cleanup; process-death orphans are removed when stale.

This adds no dependency, `INTERNET` permission, broad storage permission, persistable URI grant, document library, analytics, account, backend, or cloud-provider integration. Process-death session recovery remains unsupported.

## ADR-015: Explicit Private Local Document Library

Decision:
Add an opt-in persistent document library backed by Room `2.8.5` and app-private revision files.
Keep `DocumentSession` as the only review, OCR, edit, and export model: opening a saved document maps
its normalized database/page records back into that same session. Save only after the user chooses
**Save to RME** or **Save changes**; do not silently persist an unsaved active session.

Use normalized document, page, and one-level folder tables plus an FTS4 table containing title and
explicitly recognized OCR text. Store bounded thumbnails and full page copies below
`files/document-library`, which is excluded from Android backup and device transfer. Keep the shared
20-page limit. Deleting a folder moves its documents to no folder; deleting a document permanently
removes only RME-owned database and file records.

Rationale:
Persistent reopen/edit and local title/OCR search require durable identifiers, transactional metadata,
and explicit retention. Room provides maintained SQLite/FTS integration without networking. Full
immutable file revisions let RME prepare every page before switching the database, so a failed or
cancelled save cannot replace the last complete document. A distinct `RME_OWNED_LIBRARY` ownership
class prevents temporary-session cleanup from deleting persistent pages and prevents library cleanup
from expanding to user-selected sources.

Consequences:
Library saves run off the main thread and hold a session lease. A new revision is copied first, Room
atomically replaces document/page/FTS records, the revision is reopened as the active session, and old
revisions are removed only after the lease is released. Merge creates a new document in the selected
order. Extract creates a copy; split creates a new document and rewrites the non-empty original.
Removing the final page is rejected.

OCR text is sensitive persistent data only for an explicitly saved document after user-initiated OCR
or save. It is never logged or transmitted. External URIs and source files are never deleted. The
FileProvider remains non-exported, backup remains disabled, and no `INTERNET`, broad-storage, account,
analytics, advertising, telemetry, backend, or cloud-provider capability is added.

Room schema version 1 is the first persistent-library schema, so upgrading from v1.3 requires no data
migration. Its schema is exported and opened in instrumentation coverage; every later schema version
must ship an explicit migration and migration test. Unsaved process-death recovery remains unsupported.

## ADR-016: Portable, Versioned Library Backup And Recoverable Restore

Decision:
Define RME Backup as a logical, versioned library contract rather than a copy of Room or app-private
paths. An unencrypted backup is an inspectable ZIP containing JSON metadata, original standard assets,
editable page assets, and SHA-256 checksums. The public format preserves stable folder, document, and
page identities, relationships, timestamps, page order, edits, and saved OCR state. Room row IDs, FTS
rows, relative paths, thumbnails, caches, preferences, and other derived or device-local state are not
part of the contract.

The portable format is independent of current editor limits and can represent nested folders, source
PDFs, and documents with more than 20 pages. Page assets and their metadata are authoritative for the
current editable state; an optional source PDF is preserved as source provenance and recoverable user
content. A reader must validate capabilities and report an incompatibility before mutation rather than
silently truncate, flatten, or discard valid backup data.

Backup and restore use user-selected Android Storage Access Framework locations and bounded streaming
I/O. Restore authenticates and verifies the complete input in a private staging area, presents a
preview, resolves conflicts explicitly, regenerates thumbnails and FTS, and performs one final
transactional activation. A persistent operation journal makes interrupted staging removable and
post-commit cleanup recoverable. The existing library remains authoritative until activation succeeds.

Optional password encryption wraps the complete ordinary ZIP, including its manifest and checksums,
in the separately versioned `RMEENC01` chunked authenticated-encryption envelope specified in
[RME Backup Format Version 1](RME_BACKUP_FORMAT_V1.md). No title, folder name, OCR text, count, or other
sensitive metadata appears outside the encrypted payload. The portable key derives only from the
user's password, never from Android Keystore, so another device can restore the backup.

Rationale:
A schema dump would couple long-term recovery to Room internals and would be unsafe to merge or migrate.
Standard assets plus documented JSON remain recoverable without RME, while explicit checksums and an
authenticated encrypted envelope detect damage. Staging and one final activation prevent a cancelled,
corrupt, unsupported, or incorrectly decrypted backup from changing the live library.

Consequences:
Writers must reopen the SAF output and complete verification before reporting success. Readers reject
unsupported versions, duplicate or unsafe paths, missing or extra entries, invalid relationships,
checksum mismatches, truncated encrypted streams, and trailing data. Backup operations must coordinate
with library mutation or hold revision leases so cleanup cannot delete a page revision being streamed.
Android automatic backup remains disabled; this explicit export is the only RME library-backup path.
