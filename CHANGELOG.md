# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.0]

### Added

- Added one-tap multi-file JPEG, PNG, WebP, and PDF import through Android's system picker.
- Added narrowly scoped Android `ACTION_SEND` and `ACTION_SEND_MULTIPLE` targets for supported images and PDFs.
- Added bounded, sequential, cancellable local PDF rendering through Android `PdfRenderer`.

### Changed

- Unified scanner, selected-image, inbound-share, and rendered-PDF pages in the same active document review, OCR, and export workflow.
- Added page rotation, move earlier/later, removal, and scan/import append actions to Document review.
- Modernized Home with dominant Scan, visible Import, and conditional Resume actions.
- Set the release version to `1.3.0` (version code 14) without changing `org.synapseworks.pageharbor`.

Google Play and GitHub release publication are handled separately.

## [1.2.0]

### Changed

- Renamed PageHarbor to **RME: PDF & Document Scanner**, with **RME PDF Scanner** for compact UI.
- Updated in-app branding, About/privacy presentation, documentation, and editable branding assets.
- Preserved the existing Android application identity, signing, and user data/settings compatibility.
- Included release resource-shrinking improvements.
- Set the release version to `1.2.0` (version code 13).

Google Play publication is handled separately.

## [1.0.0]

### Changed

- Synchronized the package version to `1.0.0` (version code 10).

## [0.9.0-beta01] — Internal-testing preparation

### Changed

- Synchronized the package version to `0.9.0-beta01` (version code 9) for the owner-controlled
  first Google Play Internal Testing upload preparation. This does not indicate Google Play
  availability or completed internal testing.

## [0.8.0-dev]

### Changed

- Completed and tagged `v0.8.0-dev`.
- Started `v0.8.0-dev` internal-beta readiness, update-path validation, and defect-discovery work.
- Synchronized the package version to `0.8.0-dev` (version code 8).
- Added debug and local releaseVerification build identification while keeping production release
  build details and Git revision hidden.
- Added stale, app-owned searchable-PDF cache cleanup with deterministic ownership and age tests.
- Added beta smoke, tester, defect-reporting, and device-compatibility documentation.
- Recorded partial Samsung beta-matrix evidence for releaseVerification gallery import and cancelled
  JPEG SAF export; active-operation, repeated-session, physical-device, and broader manual checks
  remain documented gaps.
- Established tablet emulator evidence on Android 12/API 31: releaseVerification Home, dialogs,
  200% font reachability, large-window resizing, and 103/103 connected tests; headless scanner
  gallery-return remains an explicit physical/manual gap.
- Added observed Samsung Android 16 releaseVerification evidence for repeated real scanner sessions,
  normal-PDF save, share-sheet cancellation, JPEG export, one- and three-page OCR, Copy Text,
  searchable-PDF save, state reset after Discard, and ordinary background/foreground retention.
  Active-operation process-loss and app-private cache inspection remain explicitly bounded gaps.
- Added deterministic Android instrumentation gates for paused fake OCR and searchable-PDF
  generation. They verify background/foreground completion, lifecycle/session invalidation, stale
  completion rejection, temporary-output cleanup, and one destination request without changing
  production operation timing or adding a runtime test toggle.
- Completed the local Samsung Session 5 cancellation/retry/share-reset sequence and final
  four-target 103/103 connected-suite evidence. Physical active-work process kill and private-cache
  inspection remain documented non-blocking validation limits.

## [0.7.0-dev]

### Changed

- Started `v0.7.0-dev` production release foundation work.
- Prepared release builds for R8 minification and resource shrinking, with a separate debug-signed release-verification artifact for local smoke testing.
- Added conditional Play upload-signing configuration using ignored local properties or environment variables; no keystore, alias, or password is stored in the repository.
- Added documented Android App Bundle and future Play-upload signing workflow.
- Synchronized the package version to `0.7.0-dev` (version code 7).
- Added a Samsung release-verification startup and App Bundle delivery-size baseline with no
  startup telemetry or eager scanner, OCR, or PDF initialization.
- Hardened normal-PDF and JPEG SAF source/destination boundary handling for unavailable or
  malformed URIs, and close an already-open normal-PDF destination when its source is missing.
- Added deterministic private-cache, source-provider, short-stream, write, flush, close,
  cancellation, and cleanup coverage for export failure paths.
- Completed the `v0.7.0-dev` release-readiness audit: dependency/license inventory, third-party
  notices, public privacy-policy source, backup/data-extraction confirmation, and future Google
  Play internal-testing checklist.
- Corrected public and in-app privacy wording to distinguish PageHarbor's local document handling
  from ML Kit's documented encrypted technical diagnostics; document images and OCR text remain
  on-device.

## [0.6.0-dev]

### Changed

- Improved OCR Result with a bounded local preview for the selected scanned page, page-specific readable/selectable text, and retained multipage Previous/Next navigation.
- Refined Home and Scan Result hierarchy with a full-width primary scan action and grouped save/share versus secondary scan actions.
- Validated the OCR Result preview/text flow, multipage navigation, rotation, dark mode, large text, and TalkBack on Samsung Android 16.
- Retained stable active Scan Result and completed OCR state across `MainActivity` configuration changes, while cancelling/resetting active work, picker ownership, progress, and transient feedback without automatic restart.
- Hardened Activity-owned searchable-PDF operations against stale progress and duplicate or superseded preparation completion, and ensured prepared private output is discarded when Activity ownership ends.
- Hardened searchable-PDF SAF destination failures with safe result categories and private-output cleanup for unavailable, failing, or cancelled streams.
- Added deterministic 20-page searchable-PDF processing regression coverage using reusable in-memory fixtures; this does not change the 10-page scanner acquisition limit.
- Added semantic headings, polite live announcements for Scan Result progress and feedback, responsive stacked OCR actions, concise destructive-action copy, and 200% font-scale reachability coverage.
- Synchronized the package version to `0.6.0-dev` (version code 6).
- Removed transitive CCT transport discovery and scheduling manifest components; PageHarbor does not use telemetry transport.

## [0.5.0-dev]

### Added

- Added deterministic local English, German, and Romanian classification for invoice, receipt, letter, form, and unknown categories, with conservative unknown fallback.
- Added privacy-safe category-only searchable-PDF filename suggestions and user-editable SAF initial titles.

### Changed

- Integrated suggestions only with searchable-PDF SAF export; normal PDF save, sharing, JPEG export, OCR, and Copy Text remain unchanged.
- Validated all category suggestions, user filename override, cancellation/retry, privacy boundaries, and 74 passing connected tests on Samsung Android 16.

### Known limitations

- Alternate provider and duplicate-name behavior, spoken TalkBack, 200% font, and external Adobe/Google Drive viewer checks remain validation gaps. They are not known product defects.

## [0.4.0-dev]

### Changed

- Added local searchable-PDF generation from scanned JPEG pages and an invisible Unicode OCR text layer.
- Added export orchestration that performs local OCR as needed, generates a private temporary PDF, saves it through SAF, and cleans it up after success, failure, cancellation, or destination-selection cancellation.
- Added the user-facing Scan Result flow for saving a searchable PDF through the Android system file picker.
- Validated PDF structure, Unicode extraction, local Android rendering, desktop Chrome search and selection, and smoke performance measurements.

### Known limitations

- Adobe Acrobat and managed Google Drive viewer validation remain pending because those viewers were unavailable or constrained in the validation environment. This release does not claim universal viewer compatibility.

## [0.3.0-dev]

### Changed

- Began the `0.3.0-dev` Offline OCR Foundation cycle.
- Completed OCR technology research and selected bundled ML Kit Text Recognition v2 Latin for the initial OCR implementation.
- Retained Tesseract as a documented future OCR alternative.
- Clarified that scanner editing remains provided by ML Kit.
- Added a dependency-free OCR engine boundary and in-memory result model.
- Bounded OCR JPEG decoding to a 2,800 px long edge and 7 MP per-page bitmap cap.
- Added user-initiated offline OCR with an in-memory recognized-text preview and safe partial or empty-result handling.
- Added a dedicated in-memory OCR result surface with page-aware formatting and explicit Copy Text.
- Refined the app into Home, Scan Result, and OCR Result surfaces with user-facing scan summaries and local operation feedback.

## [0.2.0-dev]

### Added

- Initial repository setup
- Added project documentation
- Defined privacy and MVP principles
- Added the initial Android application foundation
- Added the initial Compose placeholder screen
- Added baseline build and test configuration
- Refined the Home screen for the early-development app state
- Added temporary scan action feedback while scanning is not yet implemented
- Added an in-app privacy information dialog
- Documented the MVP product flow
- Defined initial architecture boundaries
- Documented privacy and data lifecycle boundaries
- Added initial architecture decision records
- Added a clearly labelled ML Kit Document Scanner technical spike
- Added the initial branding guide
- Added the initial PageHarbor visual identity
- Added adaptive and monochrome launcher icon resources
- Added branded light and dark themes
- Added Android system splash configuration
- Added initial GitHub and Play Store visual assets
- Added a public roadmap
- Added debug version and build metadata
- Added an About dialog with project attribution
- Added PDF saving through Android's Storage Access Framework for ML Kit scan results
- Added PDF sharing through Android's native share sheet for ML Kit scan results
- Added individual scanned-page export through Android's Storage Access Framework
- Completed physical-device validation on Samsung Android 16

### Fixed

- Restored PDF sharing for scanner results that are readable but not directly grantable to share targets
