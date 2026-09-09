# OCR Technology Spike

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

Status: research and architecture only. This spike adds no OCR implementation, dependency, permission, scanner change, or export change. Its recommended next milestone is `v0.3.0-dev` — Offline OCR Foundation.

## Executive summary

Recommend **Google ML Kit Text Recognition v2 with the bundled Latin-script model** as PageHarbor's first OCR engine when OCR is approved for implementation. It is the best fit for the initial English, German, and Romanian requirement: those languages are explicitly supported by the Latin model; it is fast on typical Android devices; it returns structured text, geometry, and confidence; and it has a maintained Android API. The bundled form makes recognition available without a first-use model download.

This is a deliberately narrow recommendation. OCR must run only over the pages in the active local scan session; it must not change the scanner; and it must produce text as a separate local artifact. The present image-only PDF remains the source document. A later, separately approved export change can use the OCR layout to add an invisible text layer and create a searchable PDF.

ML Kit is proprietary client software, so it is not the strongest option on the "open source where practical" preference. That is an accepted trade-off for the first implementation because it avoids native/JNI ownership and offers substantially better Android integration. The bundled model avoids the dynamic Google Play services model download required by the unbundled variant. It does **not** turn the OCR stack into open source, and it should be re-evaluated if a fully open-source engine becomes a project priority.

## Scope and evaluation method

This comparison concerns recognition of already-scanned page images, not camera capture, page detection, crop, or PDF export. "Offline" means an OCR call can complete after installation with no document content sent to PageHarbor or a PageHarbor backend. It does not make claims about the behaviour of Google Play services or an app store at install/update time.

Accuracy and speed are qualitative because they depend materially on page resolution, blur, skew, layout, fonts, language mix, device CPU/ABI, and preprocessing. No vendor score is treated as a PageHarbor benchmark. Before adoption, benchmark the candidate on consented, non-sensitive representative English, German, and Romanian documents on the supported-device range.

## Comparison table

| Option | Offline support | English / German / Romanian | Accuracy and speed | APK impact | Android integration / maintenance | License | Text, confidence, searchable PDF, and extensibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **ML Kit Text Recognition v2 — bundled Latin (recommended)** | Yes after app install: model is statically linked; no model download at first use. | All three are explicitly supported by the Latin-script model. | Strong practical baseline for clean scanned documents; real-time on most devices for Latin. Validate PageHarbor documents. | About **4 MB per script per architecture**. | First-party Android API; no JNI; lowest implementation/upgrade burden of the evaluated engines. Android API 23+. | Proprietary Google SDK / applicable Google terms; not open source. | Returns text at block, line, element, and symbol levels, plus boxes, rotation, languages, and confidence. Supports copy text directly. Does not generate a searchable PDF; PageHarbor must create the invisible text layer. Good extension point for search, selection, redaction assistance, and export. |
| **ML Kit Text Recognition v2 — unbundled Latin** | Recognition is on-device once the Play-services model is present, but first use can require a download; unsuitable for a strict immediately-offline promise. | Same Latin coverage. | Same stated runtime characteristics as bundled. | About **260 KB per script per architecture** in the app; model lives in Play services. | Simple API, but adds model-availability, download, failure, and device-service behaviour to UX and testing. | Proprietary Google SDK / applicable Google terms. | Same structured result and confidence capability; searchable-PDF work remains PageHarbor-owned. Useful only if binary size outweighs the offline-first first-use requirement. |
| **ML Kit Document Scanner OCR** | The scanner flow operates on-device but its models/UI are dynamically delivered by Google Play services. | Not applicable as an OCR API. | Not applicable. It scans, enhances, and returns images/PDF; it does not expose recognized text. | Scanner SDK is documented as roughly 300 KB plus dynamically delivered resources. | Existing scanner integration is separate and should remain unchanged. | Proprietary Google SDK / applicable Google terms. | No text result, confidence, copy-text API, or OCR searchable-PDF API. Its PDF/JPEG result is a suitable input to a separate OCR engine only after lifecycle validation. |
| **Tesseract 5 + selected `tessdata_fast` models** | Yes when native libraries and selected language models ship with the app. | `eng`, `deu`, and `ron` models are available. More than 100 languages overall. | Mature and capable for document images; typically slower and more preprocessing/layout-sensitive on mobile than ML Kit. `fast` models explicitly trade some accuracy for speed. | Material and variable: native library plus one model per selected language/ABI. Must measure a release build; do not assume a fixed number. | No official modern Android SDK. Requires NDK/JNI build, ABI packaging, bitmap conversion, model/version lifecycle, native crash/security maintenance, and OCR tuning. | Apache-2.0 engine and official trained data; audit transitive native dependencies (including Leptonica). | Plain text, TSV, hOCR, ALTO, PAGE, PDF, and invisible-text-only PDF outputs; TSV has word confidence. Copy text is straightforward. Searchable PDF is possible, but PageHarbor still owns Android output and temporary-file lifecycle. Excellent future control/training, high maintenance cost. |
| **PaddleOCR / Paddle-Lite mobile deployment** | Feasible fully on-device when inference runtime, detector, recognizer, dictionaries, and models are packaged locally. | Current PP-OCR multilingual materials include German; Romanian suitability must be confirmed with the exact chosen recognition model and PageHarbor benchmark. | Potentially strong, especially for varied layouts/multilingual work, but no PageHarbor Android benchmark. Pipeline cost includes detection + recognition and can be CPU/memory intensive. | High and variable: native runtime plus detector/recognizer models and dictionaries per ABI/language. Measure, do not estimate. | Android material is a C++/Paddle-Lite deployment tutorial and demo, not a small maintained Kotlin API. Requires native build/toolchain, model conversion, ABI and performance ownership. | Apache-2.0 project; separately verify licences/provenance for every chosen model and runtime artifact. | Recognition output can provide text and boxes; confidence/result schema depends on selected pipeline. Searchable PDF and copy-text UX are PageHarbor work. High extensibility, but the highest integration and model-governance burden. |
| **Android platform OCR alternative** | No comparable public Android framework OCR API identified. | Not applicable. | Not applicable. | Not applicable. | `TextClassifier` classifies supplied text; it does not recognize text from pixels. | Platform APIs. | Not a substitute for an OCR engine. |

### Evidence and notes

- ML Kit documents the bundled/unbundled choices, the approximate 4 MB/260 KB per-script-per-architecture size trade-off, first-run download behaviour, and its Android API requirements in its [Android text-recognition guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android). The three target languages appear in its [supported-language list](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages).
- ML Kit documents per-block/line/element/symbol text, geometry, language, rotation, and confidence in the [Text Recognition v2 overview](https://developers.google.com/ml-kit/vision/text-recognition/v2).
- The [Document Scanner Android guide](https://developers.google.com/ml-kit/vision/doc-scanner/android) specifies JPEG/PDF result URIs, not recognized-text results. Its scanner resources and UI are dynamically delivered, as also recorded in `docs/MLKIT_SCANNER_SPIKE.md`.
- Tesseract documents its Apache-2.0 engine, 100+ languages, outputs including PDF/invisible-text-only PDF, and Leptonica dependency in its [official repository](https://github.com/tesseract-ocr/tesseract). The [fast data repository](https://github.com/tesseract-ocr/tessdata_fast) describes the speed/accuracy compromise; the [TSV documentation](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html) includes word confidence.
- PaddleOCR's [mobile deployment guide](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/lite/readme.md) requires compiling/packaging Paddle-Lite and models, and its [multilingual PP-OCRv5 material](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.en.md) describes broad multilingual recognition. These are feasibility evidence, not a PageHarbor quality guarantee.

## Recommendation

Adopt this architectural direction, subject to a later implementation approval:

1. Use **ML Kit Text Recognition v2, bundled Latin model only** for the first OCR release.
2. Support English, German, and Romanian first. Do not add script-specific models until a user need and APK-size review justify them.
3. Keep the existing ML Kit Document Scanner as scan acquisition only. It is not PageHarbor's OCR engine and must not be relied on to expose text.
4. Run OCR only after the user completes scanning/review, from the active session's local page images, and process each page off the main thread with bounded concurrency.
5. Treat OCR output as sensitive document content. Keep it in memory where possible; never log it; do not transmit it; and delete any temporary OCR or derived PDF files on cancellation, failure, and normal cleanup.
6. Ship copyable text before searchable-PDF export unless both can be safely completed together. A searchable PDF is a PDF-generation feature, not a capability supplied by the selected recognizer.
7. Retain Tesseract as the explicit future replacement path if the project decides that an open-source engine is more important than Android integration cost.

## Proposed architecture for a future implementation

This is a design boundary, not an instruction to create these classes now.

- **OCR adapter:** converts a local `ScannedPage` image into engine-neutral `RecognizedPage` data (text blocks, reading order, geometry, and optional confidence). ML Kit types do not leak outside this adapter.
- **Session-scoped OCR coordinator:** invokes the adapter only for a user-initiated scan session, surfaces progress/cancellation/error categories without content, and owns any OCR temporary resources.
- **RecognizedPage data:** contains sensitive in-memory text and normalized geometry. It is neither analytics data nor a permanent library/index.
- **Copy text UI:** displays/exports selected recognized text only through explicit user action. Assess clipboard privacy and avoid automatic copying.
- **Searchable-PDF writer:** separately maps recognized text and page coordinates to an invisible PDF text layer over the original page image. It must preserve the existing user-selected SAF destination and share flow, and must be tested with accented German/Romanian text, rotation, and multi-page documents.

The adapter boundary keeps a future Tesseract migration possible without changing Compose screens, scanner launch, SAF export destination selection, or the share-sheet contract.

## Risks and trade-offs

- **Proprietary dependency:** Bundled ML Kit meets local-processing and first-use availability goals but not a pure open-source preference. Its behaviour, terms, device support, and model quality remain external dependencies.
- **Binary size:** The bundled Latin model adds about 4 MB per ABI. App Bundle delivery and a measured release artifact, not an arithmetic estimate, must determine the actual user download impact.
- **Recognition is imperfect:** OCR may misread diacritics, tables, handwriting, low-contrast text, stamps, and skewed/blurred pages. OCR text must not be represented as a legally authoritative transcription.
- **Searchable-PDF complexity:** Accurate invisible-text placement, reading order, fonts/Unicode, PDF accessibility semantics, and cleanup are independent work. The feature must not silently replace or degrade the current image PDF.
- **Sensitive data expansion:** OCR creates new text representations that are easier to search, select, leak through logs, or retain accidentally. Existing no-content/no-path logging and temporary-file rules apply equally to OCR text.
- **Memory and latency:** Multi-page high-resolution bitmaps can exhaust memory or block the UI. Decode with bounds, process one page at a time or a small bounded queue, close/release resources promptly, and make cancellation observable.
- **No unvalidated quality claim:** ML Kit's documented real-time claim is not a PageHarbor accuracy benchmark. Test on physical low/mid/high-tier Android devices and real, consented documents before product claims.

## Decision

For a future OCR feature, PageHarbor's preferred initial engine is **bundled ML Kit Text Recognition v2 (Latin)**. It is selected for offline availability after app installation, explicit English/German/Romanian support, Android integration quality, structured results and confidence, and lower long-term implementation burden.

The decision does not approve adding ML Kit Text Recognition, changing the scanner, changing export, or claiming absolute offline operation today. A future implementation must complete the validation gates below and receive dependency/privacy review first.

## Historical implementation gates

The spike recorded the following gates for the subsequent OCR implementation:

1. Review the exact dependency version, licences/terms, merged manifest, transitive dependencies, and network/Play-services implications; add no `INTERNET` permission.
2. Build a consented, non-sensitive benchmark corpus for English, German, and Romanian, including diacritics, invoices/forms, dense paragraphs, low-light scans, rotations, and multi-page documents. Define measurable accuracy, latency, memory, and APK budgets.
3. Prototype the bundled Latin recognizer behind a narrow adapter only; verify airplane-mode operation after fresh install, first OCR, cancellation, configuration change, and low-memory paths on physical devices.
4. Add focused tests for reading order, errors, cancellation, temporary-file cleanup, and the hard rule that no text, URI, path, or document metadata is logged.
5. Implement and validate explicit copy-text UX before making OCR persistent or searchable.
6. Separately spike searchable-PDF generation. Validate selectable/searchable text, visual fidelity, Unicode/diacritics, SAF save, share, cancellation, and cleanup without altering the original image-PDF fallback.
7. Reassess Tesseract against the same corpus and budgets if open-source ownership, language coverage, or ML Kit platform dependence becomes the decisive requirement.
