# Searchable PDF Implementation Record

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

Status: `v0.4.0-dev` completed. This record closes the searchable-PDF investigation and documents the shipped local implementation. It does not change normal PDF save, PDF share, JPEG export, permissions, network behavior, or the Home screen.

## Original problem

The scanner returns a PDF and ordered JPEG page URIs. PageHarbor's offline OCR originally produced only page-indexed text, so users could read or copy text but could not save a PDF whose visible page image was searchable and selectable. The solution had to preserve the scan appearance, keep OCR and document data local, use a user-selected Storage Access Framework (SAF) destination, avoid logging sensitive content, and clean temporary files on every outcome.

## Evaluated options

| Option | Outcome |
| --- | --- |
| Append a text layer to the scanner-produced PDF | Not selected for the first implementation. It would require robust handling of scanner-owned PDF rotation, compression, encryption, and metadata. |
| Rebuild a PDF from scanner JPEG pages and OCR geometry | **Selected.** It gives one PageHarbor-controlled coordinate system for page images and text bounds, with explicit temporary-output ownership. |
| Ask an OCR engine to generate the PDF | Rejected. It would couple output fidelity and lifecycle to the OCR vendor and add unnecessary native/model ownership. |
| Android `PdfDocument` | Insufficient: no verified invisible-text mode or embedded Unicode-font control for interoperable searchable text. |
| PdfBox-Android `2.0.27.0` | **Selected.** Apache-2.0, Android-compatible, and supports image XObjects, embedded `PDType0Font`, Unicode extraction, and `RenderingMode.NEITHER` invisible text. |
| iText 7 or OpenPDF | Not selected because of licensing or unvalidated Android/invisible-text behavior. |

## Chosen architecture

`PdfBoxSearchablePdfGenerator` rebuilds every ordered scanner JPEG as the visible PDF page background. It consumes engine-neutral OCR line geometry, maps the upright OCR bounds into the PDF coordinate system, embeds a Unicode-capable font, and writes the line in `RenderingMode.NEITHER` so the text is selectable and searchable without a visible duplicate overlay. Blank pages or pages with no usable layout receive no text commands.

`LocalSearchablePdfExportCoordinator` runs local OCR when necessary, prepares the generated PDF only in PageHarbor's private cache, and copies it to the SAF URI chosen by the user. It reports recognizing, generating, and writing stages without document data. It deletes the prepared PDF after a successful write, write failure, coroutine cancellation, generation failure, or destination-selection cancellation/discard.

The design keeps ML Kit types inside the OCR adapter. The PDF writer receives only ordered JPEG streams and engine-neutral OCR results; it receives no document URI, path, raw EXIF value, or ML Kit type. No document data is sent to cloud OCR, an AI service, or a proprietary backend.

## Implementation outcome

- Implemented the Scan Result **Save Searchable PDF** action.
- Implemented local OCR geometry, including line bounds and explicit upright-image rotation contract.
- Implemented JPEG-background PDF composition with an invisible Unicode OCR text layer.
- Implemented local OCR/generation/SAF orchestration with safe error, cancellation, and cleanup paths.
- Preserved existing PDF save, PDF share, JPEG export, and OCR Copy Text behavior.
- Added focused unit and instrumentation coverage for geometry, generation, export, cancellation, and temporary-file cleanup.

## Performance and APK impact

One warm-run physical-device smoke check on a Samsung SM-S938B running Android 16 used deterministic, non-sensitive fixtures and the bundled Latin recognizer:

| Pages | OCR | Generation | Total | Output size |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 125 ms | 34 ms | 159 ms | 82,507 bytes |
| 5 | 235 ms | 46 ms | 281 ms | 373,928 bytes |
| 10 | 484 ms | 79 ms | 563 ms | 738,204 bytes |

These smoke measurements are not release performance targets. They establish that the end-to-end flow completes locally for representative page counts; further performance work should use consented documents and a broader device range.

PdfBox-Android is the only production dependency added for the text layer. Identical-source release APKs measured 60,277,817 bytes with the dependency and 51,864,601 bytes with it `compileOnly`: a raw delta of **+8,413,216 bytes (8.02 MiB)**. Minification was disabled for that measurement, so it is not an App Bundle or download-size estimate. No native binary, direct Bouncy Castle dependency, network permission, or telemetry was added.

## Compatibility results

The Android fixture checks verified one- and three-page PDFs with image backgrounds, an embedded `Type0` font and `ToUnicode` map on every page, and exact extraction of English, Romanian (`ă â î ș ț`), and German (`ä ö ü ß`) text in reading order. Files by Google rendered the expected visible pages and exposed the Unicode text through accessibility, but did not expose in-document search or text-selection/copy controls.

Desktop Google Chrome 150.0.7871.129 on macOS validated text-fragment search/highlighting, select-all alignment, and Chrome's selected-text payload for ASCII, Romanian, and German fixture text. The highlights aligned with the visible words and showed no visible duplicate text overlay. This validates Chrome desktop behavior; it is not a claim of universal viewer compatibility.

## Known limitations

- Adobe Acrobat validation is pending because Acrobat was not installed in the validation environment.
- Managed Android Google Drive viewer validation is pending because it rendered an empty surface for the local test documents in that environment.
- A manual Chrome clipboard paste remains to be checked; Chrome's selected-text payload was verified instead.
- OCR accuracy, selection bounds, and copied text reflect OCR quality. Tables, columns, handwriting, skewed baselines, and complex scripts remain outside the validated scope.
- The generated PDF preserves the scanner JPEG pages, not scanner-PDF metadata or print sizing.

No generated fixtures, screenshots, cache files, Downloads copies, or APKs are retained as milestone evidence.

## Evidence

- [Android `PdfDocument` API](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)
- [PDFBox invisible text rendering mode](https://pdfbox.apache.org/docs/2.0.0/javadocs/org/apache/pdfbox/pdmodel/graphics/state/RenderingMode.html)
- [PdfBox-Android 2.0.27.0 Maven metadata and dependencies](https://central.sonatype.com/artifact/com.tom-roush/pdfbox-android)
- [SIL Open Font License 1.1](https://openfontlicense.org/)
