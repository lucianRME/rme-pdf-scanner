# RME: PDF & Document Scanner

Privacy-first, open-source document scanning for Android, with offline OCR, searchable PDFs, and a local document library.

[Google Play](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor) · [Website](https://synapseworks.org/rme-pdf-scanner/) · [v1.4.0 release](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.4.0) · [Privacy](https://synapseworks.org/rme-pdf-scanner/privacy/) · [Apache-2.0 license](LICENSE)

**No ads. No account. No RME analytics or tracking. No RME document cloud.** Documents can stay in an app-private local library, and the RME application manifest has no `INTERNET` permission.

## What you can do

- **Scan and import:** Scan paper documents, up to 20 pages in a document workflow; import multiple JPEG, PNG, WebP, or PDF files; or receive supported files through Android sharing.
- **Manage documents:** Save and reopen documents locally. Search titles and text extracted by user-initiated OCR; organize with folders; rename, move, delete, and sort. The library adapts between list and grid layouts.
- **Edit pages:** Add, reorder, rotate, or remove pages. Apply Auto Enhance, Grayscale, B&W, or High Contrast while keeping the page preview visible in the editing workspace.
- **Use Tools:** Import files, Merge documents, Split / Extract pages, and Extract text.
- **Create and export:** Run offline OCR, copy extracted text, create searchable PDFs, export PDFs or JPEG pages, and save or share through Android system flows.

The v1.4 interface has **Home | Documents | Tools | More** navigation and a one-handed Scan button. A dedicated document workspace keeps **Add | Edit | OCR | Share | More** actions close to the page preview.

## Install

Get RME PDF Scanner from [Google Play](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor) or the APK on [GitHub Releases](https://github.com/lucianRME/rme-pdf-scanner/releases). The current stable release is [v1.4.0](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.4.0), released in September 2026. Release builds are covered by unit, instrumentation, accessibility, and device testing.

## Privacy

Saved documents, thumbnails, metadata, and optional OCR text live in RME's app-private local library. OCR and document-content processing happen on-device. RME does not operate a backend that receives or stores your documents, require an account, show ads, or run its own analytics or tracking. Exports go to the destination or receiving app you choose through Android's system save and share flows. The RME application manifest declares no `INTERNET` permission.

RME uses Google ML Kit Document Scanner and bundled ML Kit text recognition; these Google components are not part of RME's open-source code. Google Play services may obtain scanner resources, and ML Kit may send technical diagnostics under Google's practices. This is not a promise that every system component works without network access. Read the [full privacy policy](https://synapseworks.org/rme-pdf-scanner/privacy/) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Open source and development

The app is written in Kotlin with Jetpack Compose and Material 3. Its local library and search use Room/FTS; document workflows use Android system pickers and sharing, Google ML Kit, and PdfBox Android. RME's source is Apache-2.0 licensed; not every dependency is open source. See the [architecture overview](docs/ARCHITECTURE.md) for more detail. Minimum Android API level: 26; target API level: 36.

To build from source, use JDK 17 and an Android SDK with API 36. The repository includes the Gradle 8.14.3 wrapper and uses Android Gradle Plugin 8.13.0; use an Android Studio version that supports that toolchain.

```sh
git clone https://github.com/lucianRME/rme-pdf-scanner.git
cd rme-pdf-scanner
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Release signing credentials are not included in the repository; see [release-signing guidance](docs/RELEASE_SIGNING.md) for local verification and release builds.

## Planned

- **v1.5:** Import/migration of user-exported documents from CamScanner and other scanner apps.
- **v1.6:** Broader multilingual offline OCR and manual review of OCR text.

These are plans, not v1.4 features.

## Contributing and support

[Issues and feature requests](https://github.com/lucianRME/rme-pdf-scanner/issues) and code contributions are welcome. Please do not attach sensitive documents to reports or test materials.

Visit [SynapseWorks](https://synapseworks.org/) or its [support page](https://synapseworks.org/support/). For public support, email [support@synapseworks.org](mailto:support@synapseworks.org); for security issues, email [security@synapseworks.org](mailto:security@synapseworks.org).

RME was formerly **PageHarbor**. The Android application ID remains `org.synapseworks.pageharbor` for upgrade compatibility.

## License

RME PDF Scanner is licensed under the [Apache License 2.0](LICENSE).

<a href="https://alternativeto.net/software/rme-pdf-scanner/about/?utm_source=badge&amp;utm_medium=referral">
  <img
    src="https://alternativeto.net/static/badges/badge-compact-light.svg"
    alt="RME PDF Scanner on AlternativeTo"
    width="171"
    height="58"
  />
</a>
