# Smart Document Output Technical Spike

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

Status: `v0.5.0-dev` implementation and validation record. Core PageHarbor smart-output behavior is validated. External provider and device-accessibility checks remain documented validation gaps.

## Problem

PageHarbor currently saves a user-selected PDF through the Android Storage Access Framework (SAF). A generic save name is safe but not helpful when a user has just scanned an invoice, receipt, letter, or form. The next milestone may offer a suggested PDF name, basic document category, and optional PDF metadata without turning document processing into a cloud, AI, or document-library feature.

The proposal must be deterministic, work only from the active in-memory OCR result, support English, German, and Romanian, and fail safely when the text is missing, ambiguous, or low quality.

## Decision summary

Use a deterministic local rule engine as the first smart-output approach. It should classify only a small set of broad categories and suggest content-minimizing names. Do not add a local model in `v0.5.0-dev`; its expected accuracy benefit does not justify model size, evaluation data, update, and privacy-review cost for this narrow feature.

The output is a suggestion, never an automatic rename, overwrite, document index, or persistent record. The system save picker remains the user-controlled authority for the final name and destination.

## Implemented architecture

The implemented foundation uses two UI-independent components at the document-processing boundary: `DocumentClassifier` and `FilenameSuggestionEngine`. The classifier receives only active-session OCR text already held in memory. The filename engine receives only the resulting broad category; it cannot receive OCR text, a file path, destination URI, account information, device identifier, or history of prior scans.

The two foundation components return small, content-free results:

| Field | Purpose |
| --- | --- |
| Category | `invoice`, `receipt`, `letter`, `form`, or `unknown`. |
| Suggested filename | A conservative PDF name derived from the category, such as `invoice.pdf`. |
| Confidence | Rule outcome only: `none`, `low`, `medium`, or `high`; it is not a probability. |
| Reasons | Not exposed in the implemented foundation. A future reason API, if needed, may use only enums and never matched OCR snippets or document values. |
| Optional metadata suggestion | Not implemented. It remains a future option only after an explicit user-facing decision. |

The searchable-PDF export coordinator classifies the active OCR result, passes only its category to the filename engine, and supplies the resulting filename to `ActivityResultContracts.CreateDocument("application/pdf")` as an initial title. Android documents that the user may change that title, and that `ACTION_CREATE_DOCUMENT` cannot overwrite an existing file; the picker/provider handles a same-name collision, commonly by appending a number. PageHarbor does not enumerate folders, inspect existing names, create its own counters, or persist collision state. [Android SAF documentation](https://developer.android.com/training/data-storage/shared/documents-files?hl=en)

## Rule engine design

### Processing stages

1. Accept only a successful active-session OCR result; if all pages failed or text is blank, return `unknown`, `none`, and `document.pdf`.
2. Normalize each line for matching with Unicode NFKC, Unicode-aware lowercasing, whitespace collapse, and a diacritic-folded comparison form. Keep the original in-memory OCR text unchanged.
3. Test immutable per-language keyword and structure rules. Count only distinct evidence kinds; repeated matches do not inflate confidence.
4. Resolve one category deterministically: highest score wins; a tie, conflicting strong categories, or insufficient evidence produces `unknown`.
5. Produce a category-only filename. PDF metadata is intentionally not produced in v0.5.0-dev; it remains a future, opt-in consideration. Discard all intermediate normalized text when the call returns.

### Category evidence

Rules should require either one high-specificity marker plus one supporting marker, or two distinct supporting markers. A single common word must never classify a document.

| Category | High-specificity marker examples | Supporting evidence examples | Suggested filename |
| --- | --- | --- | --- |
| Invoice | `invoice`, `rechnung`, `factură` / folded `factura` | invoice number, tax/VAT, amount due, payment terms | `invoice.pdf` |
| Receipt | `receipt`, `quittung`, `kassenbon`, `bon fiscal`, `chitanță` / folded `chitanta` | total, paid, transaction date, currency amount | `receipt.pdf` |
| Letter | `dear` / `sincerely`, `sehr geehrte` / `mit freundlichen grüßen`, `stimate` / `cu stimă` | greeting near the opening and closing/signature language near the end | `letter.pdf` |
| Form | `form`, `application`, `formular`, `antrag`, `cerere` | repeated label/value lines, checkboxes, signature or date fields | `form.pdf` |
| Unknown | No category wins safely. | — | `document.pdf` |

The keyword lists are fixed application resources, versioned with code, and contain no learned weights or downloaded data. German `ß` and Romanian diacritics must match both their canonical spelling and an accent-folded OCR variant. Category words can overlap; for example, a payment form containing `invoice` must not be called an invoice unless the second invoice evidence is present.

### Confidence and reasons

| Outcome | Meaning | Filename behavior |
| --- | --- | --- |
| `none` | No usable OCR text or no safe match. | `document.pdf` |
| `low` | One non-specific or conflicting signal. | `document.pdf`; do not expose a category as a fact. |
| `medium` | A category meets the minimum distinct-evidence threshold. | Category filename; present it as a suggestion. |
| `high` | A category has a high-specificity marker and independent supporting evidence without conflict. | Category filename; still editable in SAF. |

Any future reasons must use safe enums such as `INVOICE_LABEL`, `TAX_MARKER`, `TOTAL_MARKER`, `LETTER_GREETING`, `FORM_FIELD_PATTERN`, `CONFLICTING_CATEGORIES`, and `OCR_UNAVAILABLE`. They must not carry the matched word, a line, an amount, a reference, a name, or a page index. Tests may use synthetic non-sensitive fixtures only.

## Filename safety and Unicode

The initial proposal intentionally never copies arbitrary OCR text into a filename. It does not use a recognized company name, personal name, address, email address, amount, account number, reference, date, title, or document ID. Broad category names give useful organization while minimizing sensitive data placed in a potentially long-lived filename.

The initial outputs are ASCII and therefore avoid provider-specific Unicode filename issues. The sanitizer is nevertheless required for any future user-editable or localized display label:

- Normalize to NFC before output and preserve complete grapheme clusters; never split a surrogate pair or combining sequence.
- Replace `/`, `\\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, NUL, and other control characters with a single space or hyphen; collapse repeated separators and trim leading/trailing spaces and periods.
- Reject empty names and the reserved stems `.` and `..`; then fall back to `document.pdf`.
- Reserve `.pdf` as exactly one extension. Remove an existing trailing `.pdf` case-insensitively before appending it once.
- Limit the stem to 80 UTF-8 bytes, without splitting a grapheme cluster, then append `.pdf`. This conservative 84-byte maximum stays below common provider/filesystem limits while leaving room for the provider's collision suffix.

The filename supplied to SAF is an initial display name only. A provider may enforce additional restrictions or change the name, so the returned URI is authoritative. [Android `Intent.ACTION_CREATE_DOCUMENT`](https://developer.android.com/reference/android/content/Intent)

## Optional PDF metadata

PdfBox-Android is already used for local PDF generation. Its PDFBox `PDDocumentInformation` API can set conventional Info dictionary fields including title, subject, keywords, creator, author, and creation/modification dates. [PDFBox `PDDocumentInformation`](https://pdfbox.apache.org/docs/2.0.13/javadocs/org/apache/pdfbox/pdmodel/PDDocumentInformation.html)

Metadata is embedded in the exported PDF and can be visible to recipients, viewers, indexes, and document providers. It therefore must be optional, off by default, and tied to an explicit future user choice. The proposed safe metadata is deliberately generic:

| Field | Proposed value when enabled | Exclusions |
| --- | --- | --- |
| Title | `Invoice`, `Receipt`, `Letter`, `Form`, or `Document` | No OCR title or personal/company name. |
| Subject | `Scanned document` | No document summary or extracted text. |
| Keywords | Omit. | No category keyword list or OCR-derived terms. |
| Author | Omit. | Never infer an author from OCR. |
| Creator | `PageHarbor` only if existing PDF-generation policy permits it. | No account, device, or user identifier. |
| Dates | Let the PDF library/runtime behavior stand; never derive a date from OCR. | No OCR-derived issue, due, or transaction date. |

If the category confidence is below `medium`, do not create category metadata. Metadata generation must not mutate the existing non-searchable-PDF save/share flow; it belongs only to a future explicitly selected smart-output export path.

## Examples

| OCR evidence, described without retaining text | Result | Reason summary |
| --- | --- | --- |
| English invoice heading plus VAT and amount-due markers | `invoice.pdf`, `high` | `INVOICE_LABEL`, `TAX_MARKER`, `AMOUNT_DUE_MARKER` |
| German receipt heading and total/paid markers | `receipt.pdf`, `high` | `RECEIPT_LABEL`, `TOTAL_MARKER`, `PAID_MARKER` |
| Romanian letter greeting and closing | `letter.pdf`, `medium` or `high` depending on independent structure evidence | `LETTER_GREETING`, `LETTER_CLOSING` |
| A page that says only “Total” | `document.pdf`, `low` | `TOTAL_MARKER` |
| Blank, failed, mixed, or conflicting OCR | `document.pdf`, `none` or `low` | `OCR_UNAVAILABLE` or `CONFLICTING_CATEGORIES` |

These examples are rule outcomes, not claims about OCR accuracy. The suggestion must remain editable, and the final name must be selected by the user in the system picker.

## Supported languages

The first rule tables support English, German, and Romanian—the same Latin-script languages supported by the current bundled OCR engine. Matching must be Unicode-aware and accent tolerant, including `ä ö ü ß` and `ă â î ș ț`. A document may contain more than one of the three languages; evidence from any supported table can contribute, but conflicting category evidence still yields the safe fallback.

Other languages and complex scripts are out of scope. They must produce `unknown` rather than an English-only guess. A later language requires a reviewed rule table, non-sensitive fixtures, Unicode tests, and privacy review; it does not justify automatic translation or a network request.

## Privacy implications

- All classification is local, synchronous or coroutine-local work over the active in-memory OCR result.
- No network, cloud OCR, AI service, analytics, telemetry, account, persistence, document library, or OCR-text storage is permitted.
- The engine retains no history, fingerprints, hashes, duplicate index, filenames, category outcomes, metadata, or raw OCR after the export flow ends.
- Logs, exceptions, and analytics must never contain OCR text, category reasons, suggested names, metadata values, destination URIs, or file paths.
- The system picker receives only the one suggested display name for the current user-initiated save. The user may replace it, cancel, or choose any supported destination.

## Limitations and future local model assessment

Rule-based classification will miss unfamiliar layouts, mixed-language documents, handwriting, unusual terminology, and low-quality OCR. It cannot reliably infer a safe descriptive title without risking sensitive names, identifiers, or financial data in the filename. This is acceptable for a small, privacy-first category suggestion.

A future local model could improve semantic classification and title extraction, but it would require a model binary, version/update policy, representative multilingual evaluation corpus, false-positive and privacy review, performance/size validation, and an explanation policy. It would still need the same strict filename sanitizer and safe fallback. For the initial four categories, deterministic rules are explainable, testable, dependency-free, and sufficient. Reconsider a local model only if measured rule coverage on consented, non-retained test documents shows a material user benefit that generic category names cannot provide. A cloud model remains out of scope.

## Implementation outcome

- Implemented a pure Kotlin `DocumentClassifier` with the five documented categories, non-probabilistic `none`, `low`, `medium`, and `high` confidence, Unicode normalization, and immutable English, German, and Romanian rules.
- Implemented a category-only `FilenameSuggestionEngine` with fixed ASCII outputs: `invoice.pdf`, `receipt.pdf`, `letter.pdf`, `form.pdf`, and `document.pdf`.
- Integrated the suggestion only into the searchable-PDF `ACTION_CREATE_DOCUMENT` flow. Normal PDF save, share, JPEG export, OCR, and Copy Text do not use it.
- Kept the system picker and the user-edited destination authoritative. The app stores no final filename or duplicate state.
- Did not implement optional PDF metadata, title extraction, new categories, a model, cloud processing, networking, persistence, analytics, or document history.

## Validation record — 2026-07-20

Automated validation passed for the deterministic rule engine, filename safety, and the existing export-flow regression coverage. The unit fixtures cover clear English, German, and Romanian invoices; multilingual receipts, letters, and forms; mixed-language evidence; weak, repeated, conflicting, punctuation-heavy, malformed, long, and path-like OCR input; and category-only filename mappings, suffix, invalid-character, fallback, Unicode-boundary, and determinism behavior.

Physical validation on a Samsung SM-S938B running Android 16 completed the installed-app launch, Home action target, and scanner-entry checks. The system scanner opened without capture and exposed accessible close, gallery-import, capture, and mode controls. Connected instrumentation completed 74 of 74 tests with zero failures, errors, or skips on that device, including searchable-PDF category-suggestion, user-edited-destination, cleanup, cancellation, and regression tests.

Samsung validation confirmed `invoice.pdf`, `receipt.pdf`, `letter.pdf`, `form.pdf`, and `document.pdf` in independent sessions, a user-edited SAF filename override, and cancellation returning to Scan Result with retry available. No PageHarbor defect was observed.

The following are documented validation gaps, not known product defects: a searchable-PDF open/search smoke was not repeated after temporary non-sensitive output was cleaned up; spoken TalkBack and 200% font checks were unavailable; alternate SAF providers, Google Drive, and duplicate-name behavior were unavailable; and Adobe/Google Drive viewer checks remain outside this milestone's validated scope. PDF metadata remains intentionally excluded.

## Historical implementation plan

1. Add pure Kotlin rule-engine tests first, using synthetic English, German, Romanian, mixed-language, malformed-Unicode, invalid-character, overlength, tie, and empty-OCR fixtures.
2. Implement the narrow in-memory suggester and safe result/reason types with no Android storage, network, or UI dependency.
3. Add filename sanitization and property-style tests for extension, UTF-8 byte cap, invalid characters, and deterministic output.
4. Add optional generic PDF Info metadata only after an explicit product decision and export-path design; verify that it is absent by default and cannot contain OCR-derived values.
5. Integrate only after user-flow, SAF collision, cancellation, privacy, accessibility, and physical-device validation. Keep `v0.5.0-dev` planned until those gates pass.
