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
