# RME: PDF & Document Scanner

Privacy-first, open-source Android document scanning with multilingual on-device OCR, full-library search, searchable PDFs, migration, portable backup, and no RME account or cloud backend.

[Google Play](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor) · [Website](https://synapseworks.org/rme-pdf-scanner/) · [v1.6.0 release](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.6.0) · [Privacy](https://synapseworks.org/rme-pdf-scanner/privacy/) · [Apache-2.0 license](LICENSE)

**No ads. No account. No RME analytics or tracking. No RME document cloud.** Documents can stay in an app-private local library, and RME requests no `INTERNET` permission.

## What you can do

- **Scan and import:** Scan multi-page paper documents, up to 20 pages in a document workflow; import multiple JPEG, PNG, WebP, or PDF files; or receive supported files through Android sharing.
- **Manage documents:** Keep a persistent local library with folders and subfolders. Rename, move, delete, sort, and reopen documents without an RME account or cloud service.
- **Edit pages:** Add, reorder, rotate, or remove pages. Apply Auto Enhance, Grayscale, B&W, or High Contrast while keeping the page preview visible in the editing workspace.
- **Recognize and review text:** Run multilingual on-device OCR for Latin, Chinese, Japanese, Korean, and Devanagari scripts. Latin recognition is built in; optional script models may be downloaded by Google Play services. Review and correct recognized text, re-run OCR for a page or document, or use batch OCR.
- **Search the library:** Search titles and recognized text across the full local library, see matching snippets, and jump directly to matching pages.
- **Create and export:** Create multilingual searchable PDFs, use local smart document naming, export PDFs or JPEG pages, and save or share through Android system flows.
- **Use document tools:** Merge documents, split documents, extract pages, and extract text.
- **Move existing scans:** Import from CamScanner, Adobe Scan / Acrobat, or Genius Scan, with PDF migration guidance for Microsoft Lens.
- **Back up and transfer:** Create and restore portable backups, optionally encrypt backup files, and move an RME library to a new phone.
- **Protect app access:** Optionally require Android device authentication when opening RME.

## Install

Get RME PDF Scanner from [Google Play](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor) or the APK on [GitHub Releases](https://github.com/lucianRME/rme-pdf-scanner/releases). The current stable release is [v1.6.0](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.6.0), released in October 2026. Release builds are covered by unit, instrumentation, accessibility, and device testing.

## Privacy

Saved documents, thumbnails, metadata, and optional OCR text live in RME's app-private local library. Recognition and document-content processing run on-device. RME does not operate a backend that receives or stores your documents, require an account, show ads, or run its own analytics or tracking. Exports go to the destination or receiving app you choose through Android's system save and share flows. RME requests no `INTERNET` permission.

RME uses Google ML Kit Document Scanner and ML Kit text recognition; these Google components are not part of RME's open-source code. Latin recognition is bundled with the app. Optional Chinese, Japanese, Korean, and Devanagari OCR models, as well as scanner resources, may be downloaded or updated by Google Play services. Recognition runs on-device after the required model is available. ML Kit may send technical diagnostics under Google's practices. This is not a promise that every system component works without network access. Read the [full privacy policy](https://synapseworks.org/rme-pdf-scanner/privacy/) and [third-party notices](THIRD_PARTY_NOTICES.md).

## Open source and development

The app is written in Kotlin with Jetpack Compose and Material 3. Its local library and search use Room/FTS; document workflows use Android system pickers and sharing, Google ML Kit, and PdfBox Android. RME's source is Apache-2.0 licensed; not every dependency is open source. See the [architecture overview](docs/ARCHITECTURE.md) for more detail. Minimum Android API level: 26; target API level: 36.

To build from source, use JDK 17 and an Android SDK with API 36. The repository includes the Gradle 8.14.3 wrapper and uses Android Gradle Plugin 8.13.0; use an Android Studio version that supports that toolchain.

```sh
git clone https://github.com/lucianRME/rme-pdf-scanner.git
cd rme-pdf-scanner
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Release signing credentials are not included in the repository; see [release-signing guidance](docs/RELEASE_SIGNING.md) for local verification and release builds.

## ⭐ Show your support

If RME is useful to you, please consider supporting the project:

- **⭐ Star this repository** — it helps more people discover RME
- **🐛 Report issues** — bug reports and real-world feedback help improve the app
- **💡 Suggest features** — ideas are welcome through GitHub Issues
- **📣 Share RME** — especially with people looking for a privacy-first Android scanner
- **🔧 Contribute** — code contributions and improvements are welcome

[![GitHub stars](https://img.shields.io/github/stars/lucianRME/rme-pdf-scanner?style=social)](https://github.com/lucianRME/rme-pdf-scanner/stargazers)

## Contributing and support

[Issues and feature requests](https://github.com/lucianRME/rme-pdf-scanner/issues) and code contributions are welcome. Please do not attach sensitive documents to reports or test materials.

Visit [SynapseWorks](https://synapseworks.org/) or its [support page](https://synapseworks.org/support/). For public support, email [support@synapseworks.org](mailto:support@synapseworks.org); for security issues, email [security@synapseworks.org](mailto:security@synapseworks.org).

RME was formerly **PageHarbor**. The Android application ID remains `org.synapseworks.pageharbor` for upgrade compatibility.

## License

RME PDF Scanner is licensed under the [Apache License 2.0](LICENSE).

The Apache License 2.0 applies to the RME source code. The RME, RME PDF Scanner, and SynapseWorks names and associated logos, icons, and brand identity are not granted for use as branding for derivative applications under that license. Forks may accurately describe their origin but should use their own product identity. See [TRADEMARKS.md](TRADEMARKS.md).

<a href="https://alternativeto.net/software/rme-pdf-scanner/about/?utm_source=badge&amp;utm_medium=referral">
  <img
    src="https://alternativeto.net/static/badges/badge-compact-light.svg"
    alt="RME PDF Scanner on AlternativeTo"
    width="171"
    height="58"
  />
</a>
