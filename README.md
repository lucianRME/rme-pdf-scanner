# RME: PDF & Document Scanner

**Private, open-source document scanning for Android.**

Scan documents, import images and PDFs, run OCR offline, create searchable PDFs, and export your files without ads, tracking, accounts, or a proprietary cloud backend.

[![Google Play](https://img.shields.io/badge/Google%20Play-Download-414141?logo=googleplay&logoColor=white)](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor)
[![Latest GitHub release](https://img.shields.io/github/v/release/lucianRME/rme-pdf-scanner?display_name=tag&sort=semver)](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.3.0)
[![GitHub stars](https://img.shields.io/github/stars/lucianRME/rme-pdf-scanner)](https://github.com/lucianRME/rme-pdf-scanner)
[![Apache 2.0 license](https://img.shields.io/github/license/lucianRME/rme-pdf-scanner)](LICENSE)
<a href="https://alternativeto.net/software/rme-pdf-scanner/about/?utm_source=badge&amp;utm_medium=referral">
  <img
    src="https://alternativeto.net/static/badges/badge-compact-light.svg"
    alt="RME PDF Scanner on AlternativeTo"
    width="171"
    height="58"
  />
</a>

> ⭐ If you find RME useful or like the privacy-first approach, consider starring the repository.

## Why RME?

Many document scanners rely on accounts, advertising, analytics, or cloud processing.

RME takes a different approach:

- **Privacy-first**: document processing stays on the device
- Offline OCR
- Searchable PDFs
- Multi-page scanning and document sessions, currently up to 20 pages
- Multi-file JPEG, PNG, WebP, and PDF import through Android's system picker
- Android share-target import for supported images and PDFs
- User-controlled PDF/JPEG export
- No ads
- No tracking or analytics
- No account or login
- No proprietary document-processing backend

## Get the app

### Google Play

[RME: PDF & Document Scanner](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor)

### Latest GitHub release

[RME PDF Scanner v1.3.0](https://github.com/lucianRME/rme-pdf-scanner/releases/tag/v1.3.0)

## Features

### Scanning

- Multi-page document scanning
- Multi-file JPEG, PNG, WebP, and PDF import through Android's system picker
- Android share-target import for supported images and PDFs
- One unified review workflow for scanned and imported pages
- Reorder, rotate, remove, filter, or add pages
- Document filters: Original, Enhance, Grayscale, Black and white, and High Contrast
- Up to 20 pages per active document

### OCR

- Offline, on-device Latin OCR
- Selectable and copyable recognized text
- Page navigation
- No cloud OCR requirement

### Searchable PDFs

- Local searchable PDF generation
- Invisible Unicode OCR text layer
- Deterministic category-based filename suggestions
- Save searchable PDFs through the Android system file picker; share standard scan PDFs through the Android share sheet

### Export

- Android Storage Access Framework (SAF)
- Standard and searchable PDF export
- PDF sharing through the Android share sheet
- JPEG page export
- User-selected storage provider and destination

## Privacy by design

RME processes scan images, OCR text, and searchable-PDF text layers on the device. It does not operate:

- Advertising
- Tracking or analytics
- User accounts
- Proprietary cloud storage
- Cloud OCR
- An AI backend

RME does not send document images, OCR text, or generated PDF content to an RME server because it has no such server. The app does not maintain a persistent document library. Services such as Google Drive or OneDrive may appear only as destinations exposed through the Android system file picker; RME does not directly access those services or their account credentials.

Google ML Kit is separately licensed. It may send documented encrypted technical diagnostics to Google, while document images and recognized OCR text are processed on-device and are not sent by ML Kit. See [PRIVACY.md](PRIVACY.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full qualifications and applicable third-party terms.

The app declares no `INTERNET` permission. Google Play services may need network access to obtain or update scanner resources; offline OCR does not mean every Google-provided component can be acquired offline.

## Technical details

- Kotlin
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- Google ML Kit Document Scanner
- Android Storage Access Framework

The searchable-PDF engine has deterministic 20-page regression coverage. The focused Home, Document review, and OCR Result surfaces do not create a persistent document library. Scanned pages, selected images, Android shares, and locally rendered PDF pages use one active session. The completed active document and selected OCR page survive configuration changes; process-death recovery is intentionally unsupported.

Searchable-PDF filename suggestions use broad local categories: invoice, receipt, letter, form, or unknown. They never use OCR-derived names, dates, amounts, identifiers, addresses, or other sensitive document values. The user may edit a suggestion, and the selected SAF provider controls the final name and destination.

RME does not claim universal external viewer or SAF-provider compatibility, full accessibility certification, or low-end-device validation.

## Local development

```sh
./gradlew assembleDebug
./gradlew bundleRelease
./gradlew bundleReleaseVerification
./gradlew test
./gradlew lint
```

See [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) for unsigned/debug-signed local verification and Play upload signing. No signing credential belongs in this repository.

## Testing

Historical beta-testing materials are available in [docs/BETA_SMOKE_TEST.md](docs/BETA_SMOKE_TEST.md), [docs/INTERNAL_TESTER_GUIDE.md](docs/INTERNAL_TESTER_GUIDE.md), and [docs/BUG_REPORT_TEMPLATE.md](docs/BUG_REPORT_TEMPLATE.md). Do not use sensitive documents in testing.

## Project history

The application was originally released as **PageHarbor**.

The app was renamed in v1.2.0, and development continues as **RME: PDF & Document Scanner**. v1.3.0 introduced a unified scan/import workflow, multi-file and PDF import, Android share-target support, and improved document review and import handling. The Android application ID intentionally remains `org.synapseworks.pageharbor` for upgrade compatibility. Active development continues based on user feedback and privacy-first product goals.

## License

RME PDF Scanner is licensed under the [Apache License 2.0](LICENSE).

## Contributing

Issues, bug reports, technical feedback, and contributions are welcome. Please avoid including sensitive document content in issues or test materials.

If you find the project useful or want to support privacy-first Android software, a ⭐ on the repository helps others discover it.

## Attribution

Developed by Lucian Irimie and published under [SynapseWorks](https://synapseworks.org/rme-pdf-scanner/).
