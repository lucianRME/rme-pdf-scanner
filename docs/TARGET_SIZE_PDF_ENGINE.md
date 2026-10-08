# Target-size PDF engine

Milestone 4 adds an internal, local-only engine for preparing a new PDF below an exact decimal byte
limit when that limit is reachable without crossing a conservative quality floor. It does not add
the Milestone 5 preset/custom-size UI, Save/Share actions, or persistent settings.

## Existing pipeline mapping

| Requirement | Existing component | M4 treatment | Reason |
| --- | --- | --- | --- |
| Ordered effective pages and original preservation | `DocumentSession` | Reused through `targetSizePdfRequest` | Keeps page order, filters, rotation, stable IDs, and PDF provenance without modifying sources. |
| Image bounds and allocation limits | `FilteredJpegPageWriter` and `DocumentInputLimits` | Extended with bounded sampled decode, downscale, and explicit JPEG quality | Retains the validated 64 MiB / 6,000-edge / 12.5 MP source guards and two-bitmap ownership model. |
| Rotation and filters | `FilteredJpegPageWriter` | Reused | Uses the existing EXIF plus session-rotation transform and local filter engine. |
| PDF composition | `PdfBoxSearchablePdfGenerator` | Reused with an 8 MiB main-memory spool and private scratch storage; completed files are fsynced | Preserves page order and direct JPEG embedding without another PDF dependency or all-page encoded heap retention. |
| Searchable Unicode text | `OcrPageResult`, effective OCR providers, Type 3/ToUnicode generator | Reused only for trustworthy positioned effective text | Manual corrections remain authoritative; missing placement is reported and never fabricated. |
| Imported-PDF provenance | `DocumentSession.originalPdfSources` | Inspected locally before reconstruction | Rejects encrypted, malformed, interactive, signed, annotated, embedded-file, and non-image/vector-only PDFs. |
| Destination safety | Existing private-prepare then SAF flow | M4 returns only a validated private prepared result | M5 can invoke the existing Save/Share UI only after exact-size and reopen/render validation. |
| Temporary ownership and cancellation | Existing private-cache cleanup pattern | Dedicated per-run directory, typed explicit cancellation, stale cleanup | A failed/cancelled run leaves no prepared destination or library mutation. |
| Storage accounting | Corrected migration semantics | Separate source, estimated output, workspace, and safety-reserve fields | The reserve is never presented as output or persistent library size. |

## Deterministic planning and bounded search

- `200 KB`, `500 KB`, `1 MB`, and `2 MB` mean exactly `200,000`, `500,000`, `1,000,000`, and
  `2,000,000` bytes.
- Page budgets use only pixel count and an explicit deterministic content profile. Stable
  largest-remainder allocation gives every input/target pair the same plan.
- The planner never upscales. The ordinary short-edge floor is 960 pixels at JPEG quality 55;
  tiny-text pages use 1,200 pixels at quality 65. A smaller source keeps its original dimensions.
  These are engineering guardrails, not a claim of objectively measured human readability.
- Calibration selects at most three representative pages and performs at most three local JPEG
  trials per representative. It does not rewrite the whole document.
- The engine performs one full candidate encode. If that file exceeds the target, the single
  allowed refinement encodes the explicit quality-floor candidate. Therefore whole-document
  encode passes are at most two (three stages including calibration).
- A successful result requires `finalBytes <= targetBytes` with no tolerance. The closed file is
  reopened with `PdfRenderer`, its page count is checked, and every page is rendered to a bounded
  validation bitmap.
- If the floor candidate is still too large, the engine returns
  `TARGET_NOT_REACHABLE_AT_ACCEPTABLE_QUALITY` semantics with the measured smallest acceptable
  file, exact bytes, and settings. Clearly unrealistic page-count/target combinations fail before
  encoding with a deterministic minimum estimate.

## Supported and rejected inputs

Supported classifications are image documents, image-based imported PDFs, and mixed image/text or
image/vector PDFs whose complete current page visuals already exist as reconstructable RME page
assets. The engine does not copy hidden original objects into the new PDF.

The classifier rejects encrypted or malformed PDFs and conservatively rejects interactive forms,
signatures, annotations, embedded files, pages without a raster image, and incomplete page
reconstruction. This is intentional: M4 does not silently rasterize arbitrary vector-heavy,
interactive, or security-sensitive PDFs.

## Privacy, storage, and persistence boundaries

All inspection, calibration, JPEG encoding, PDF composition, validation, and cleanup run locally.
The implementation adds no dependency, permission, network call, remote model/font, analytics,
telemetry, account, backend, or cloud compressor. Source page images, original PDFs, OCR artifacts,
corrections, library records, Room schema, and backup format are read-only to this engine.

Room remains version 4 and the portable backup format remains version 3. M4 stores no preference or
database state. The caller owns a successful private prepared result and must explicitly discard it
if M5's future destination picker is cancelled.
