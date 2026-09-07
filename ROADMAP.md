# Roadmap

Current GitHub release: **v1.2.0**, introducing the RME: PDF & Document Scanner name. Google Play rollout is handled separately. The planning milestones below retain their historical context.

RME PDF Scanner is in early development. This roadmap is public-facing and intentionally conservative; it does not claim that document scanning or export is production-ready.

## Completed

- Repository and documentation foundation.
- Android Compose foundation.
- Privacy and architecture boundaries.
- Home screen and privacy messaging.
- Branding and design system.
- ML Kit technical spike integration.
- ML Kit scanner integration, including multi-page scanning and its built-in crop, rotate, filters, deletion, and reordering experience.
- `v0.2.0-dev` export milestone:
  - Save PDF through Android Storage Access Framework.
  - Share PDF through the Android share sheet.
  - Export Pages as JPEG through Android Storage Access Framework.
  - Physical Samsung validation on Android 16.

Document scanning, PDF save, PDF share, and JPEG page export have been validated on a physical Samsung device.

## Completed milestones

### `v0.3.0-dev` — Offline OCR Foundation

- Add bundled ML Kit Text Recognition v2 Latin after dependency and privacy validation. **Implemented:** the bundled, on-device Latin engine processes active-session JPEG pages sequentially and retains in-memory page indexes and failures.
- Introduce a narrow OCR integration boundary and result model. **Implemented:** ML Kit types are confined to `MlKitOcrEngine`.
- Recognize text from scanned JPEG pages and combine it in page order. **Implemented in the scan-result flow; physical scan-flow validation remains in progress.**
- Expose a plain-text preview and allow explicit copying of recognized text. **Implemented:** text remains in memory and is copied only through explicit user action.
- Preserve page ordering, empty/partial-result handling, bounded decode, and local-only processing.
- Preserve the scan, PDF save, PDF share, and JPEG export flows.

OCR is optional: it must run only after explicit user action and must never block scanning or export. OCR results remain in memory unless the user explicitly copies or exports them. RME PDF Scanner will not introduce cloud OCR or a proprietary backend.

The current UI keeps Home focused on starting a scan. Scan Result owns export and OCR actions plus their feedback, while OCR Result owns in-memory recognized-text actions. There is no bottom navigation or internal document library.

RME PDF Scanner intentionally relies on ML Kit for scanner editing capabilities rather than duplicating crop, rotate, filters, page deletion, or reordering. Use platform capabilities where they are strong. Build only what adds distinct user value.

### `v0.4.0-dev` — Searchable PDF

- OCR geometry captured in an engine-neutral layout model.
- Local searchable-PDF generator that rebuilds pages from JPEG images.
- Invisible Unicode OCR text layer with embedded font support.
- Unicode extraction and selection validation for English, Romanian, and German fixtures.
- Local export orchestration, including OCR, generation, write stages, cancellation, and private-cache cleanup.
- Save searchable PDFs through the Android Storage Access Framework.
- User-facing Scan Result flow for saving a searchable PDF.
- Android and desktop Chrome compatibility validation, plus performance smoke measurements.

Searchable-PDF generation remains local to the active scan session. It does not add cloud OCR, a proprietary backend, an internal document library, or automatic cloud sync. Adobe Acrobat and managed Google Drive viewer validation remain pending because those viewers were unavailable or constrained in the validation environment.

### `v0.5.0-dev` — Smart Document Output

Completed:

- Deterministic local document classification for invoice, receipt, letter, form, and unknown categories.
- Unicode-aware English, German, and Romanian matching with conservative unknown fallback.
- Privacy-preserving category-only filename suggestions for searchable-PDF SAF export.
- User-editable suggested filename passed only to the system picker; no duplicate tracking, filename history, metadata, or OCR-derived filename content.
- Samsung manual validation of all five suggested filenames, user filename override, SAF cancellation, and retry behavior.
- Privacy and automated validation, including 74 passing connected tests with no failures, errors, or skips.

PDF metadata was intentionally excluded from `v0.5.0-dev`. Alternate SAF-provider behavior, duplicate-name provider behavior, spoken TalkBack verification, 200% font verification, and external Adobe/Google Drive viewer checks remain documented validation gaps, not known product defects.

### `v0.6.0-dev` — Complete

- Accessibility semantics and responsive-layout improvements, including headings, polite live feedback, and 200% font automated reachability coverage.
- Dark-theme coverage and visual hierarchy polish across Home, Scan Result, and OCR Result.
- OCR Result local document preview, page-specific selectable text, and multipage Previous/Next navigation.
- Lifecycle, cancellation, stale-result, picker-result, and provider-failure cleanup hardening.
- Rotation-safe completed Scan Result and OCR Result, including selected OCR-page retention.
- Deterministic 20-page post-scan searchable-PDF regression coverage.
- Samsung SM-S938B and API 36 emulator validation.

This milestone does not add document storage, app-level navigation, permissions, metadata, document categories, cloud services, analytics, or process-death recovery.

### `v0.7.0-dev` — Complete

- Establish production release signing architecture with ignored local configuration and CI environment-variable support.
- Enable release minification and resource shrinking, then verify a debug-signed release-equivalent installation.
- Establish Android App Bundle generation and future Play-upload signing guidance without committing credentials or publishing an artifact.
- Record a reproducible Samsung startup baseline and device-targeted App Bundle split analysis;
  no Play download-size claim is made from this local analysis.
- Harden SAF and private-cache failure handling so unavailable source/destination streams never
  report success and prepared private output remains subject to cleanup.
- Complete release-readiness review of dependency licenses, bundled Liberation Sans OFL handling,
  backup/data-extraction behavior, signing boundaries, Data safety drafting, and internal-test
  prerequisites.
- Publish the source privacy-policy text, third-party notices, dependency/license audit, and a
  future Google Play internal-testing checklist. ML Kit technical diagnostics are disclosed
  separately from RME PDF Scanner's local document-content processing.

Limits carried forward: scanner acquisition remains capped at 10 pages; the 20-page regression covers post-scan searchable-PDF processing only. Process-death recovery is unsupported. No new end-user feature work is part of `v0.7.0-dev`.

The checked repository is ready for its `v0.7.0-dev` commit and tag after owner review. Google
Play upload remains an owner-controlled follow-up: it requires an upload key, a hosted public
privacy-policy URL and contact, current Play Console declarations, store assets, and acceptance of
the applicable Google terms. Those prerequisites are tracked in
[`docs/INTERNAL_TESTING_CHECKLIST.md`](docs/INTERNAL_TESTING_CHECKLIST.md).

### `v0.8.0-dev` — Complete

- Established controlled internal-beta readiness without publishing to Google Play.
- Validated a local debug-signed update path from `v0.7.0-dev` to `v0.8.0-dev`, clean install, and
  the documented no-downgrade expectation.
- Made debug and local `releaseVerification` builds identifiable without exposing build details in
  a production release.
- Added a concise beta smoke protocol, privacy-safe defect template, tester guide, and honest device
  compatibility matrix.
- Validated repeated Samsung sessions, local DocumentsUI flows, rotation, ordinary
  background/foreground behavior, and app-owned temporary-cache cleanup boundaries.
- Added deterministic paused-operation tests for OCR and searchable-PDF lifecycle invalidation,
  stale/duplicate completion rejection, and prepared-output cleanup. These are test-only fakes;
  they do not add production delays or process-death recovery.

This milestone does not add a document library, accounts, cloud sync, RME PDF Scanner analytics or crash
SDKs, advertising, OCR language downloads, advanced image editing, new AI features, automatic
document retention, new permissions, or Play publication.

`v0.8.0-dev` is complete and tagged. Its local validation evidence does not mean that Google Play
internal testing has started or that the app is available on Google Play.

### `v1.0.0` — Current production-release preparation

- Prepare version metadata and the signed AAB for the owner-controlled first internal-testing upload.
- Keep Play Console registration, Data safety declarations, policy hosting, upload, and tester
  enrollment as unchecked owner-controlled steps.
- Do not add product functionality or claim that internal testing has completed.

### `v0.9.0-dev` — Planned after internal-testing preparation

- Collect real internal-tester feedback and triage reproducible defects.
- Expand third-party SAF/provider compatibility and physical-device coverage.
- Validate a Play-delivered update only after owner-controlled Play prerequisites are complete.
- Address performance or accessibility defects discovered by testers.

No `v0.9.0-dev` work is complete or implied by this roadmap entry.

## Planned MVP

- Review one-page and multi-page scan results.
- Temporary-file cleanup.
- User-safe error handling.
- Accessibility validation.

## Later Considerations

These are non-committed possibilities:

- Document organization.
- Additional privacy-preserving features.

## Explicit Non-Goals For MVP

- Accounts.
- Proprietary backend.
- Automatic cloud sync.
- Advertising.
- Analytics.
- Subscriptions.
- Internal permanent document library.
- Smart document output, deterministic filename suggestions, or improved metadata in `v0.4.0-dev`.
- Document search or an internal library in `v0.4.0-dev`.
