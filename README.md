# RME: PDF & Document Scanner

RME: PDF & Document Scanner is an open-source, privacy-first Android document scanner for scanning multi-page documents, importing pages from the gallery, recognizing Latin text on-device, and exporting PDFs or JPEGs. Version 1.0.0 was released as PageHarbor on Google Play. Development continues under the Apache 2.0 license.

[Get RME PDF Scanner on Google Play](https://play.google.com/store/apps/details?id=org.synapseworks.pageharbor) (`org.synapseworks.pageharbor`)

## Highlights

- Multi-page document scanning with review and reordering
- Gallery import
- Offline, on-device Latin OCR with selectable and copyable recognized text
- Searchable PDF generation with a local invisible OCR text layer
- PDF save and share, plus individual JPEG page export
- Deterministic category-based filename suggestions for searchable PDFs
- No ads, account, login, or RME PDF Scanner-operated cloud backend

## Current capabilities

- Scan one or more pages; scanner acquisition is currently limited to 10 pages
- Review and reorder scanned pages
- Import pages from the device gallery
- Export scans as a PDF through the Android Storage Access Framework (SAF), or share PDFs through the Android share sheet
- Export scanned pages individually as JPEGs through SAF
- Run Latin OCR locally, review the recognized text in memory, select or copy it explicitly, and move between pages
- Generate searchable PDFs locally from scanned JPEG pages with an invisible Unicode OCR text layer, then save them through SAF
- Suggest searchable-PDF filenames from broad local categories: invoice, receipt, letter, form, or unknown
- Keep the completed active scan and selected OCR page available across configuration changes; process-death recovery is intentionally unsupported

Searchable-PDF filename suggestions contain no OCR-derived names, dates, amounts, identifiers, addresses, or other document values. Users can edit a suggested name; the selected SAF provider remains authoritative for the final name and destination. The focused Home, Scan Result, and OCR Result surfaces do not create a persistent document library.

For previous planning context, see the [historical roadmap](ROADMAP.md).

## Privacy and architecture

RME PDF Scanner uses Kotlin, Jetpack Compose, Material 3, Gradle Kotlin DSL, ML Kit Document Scanner, and the Android Storage Access Framework. Its privacy-first architecture keeps document and OCR content processing on-device and lets users choose where files are saved or shared. RME PDF Scanner has no ads, RME PDF Scanner-operated tracking or analytics, account or login requirement, proprietary cloud storage, cloud OCR, or AI backend. Cloud providers such as Google Drive or OneDrive may appear only as destinations selected through the Android system file picker; RME PDF Scanner does not directly access them.

Google ML Kit is a separately licensed Google SDK. Its documented technical diagnostics can be
encrypted and sent to Google, but document images and recognized text are processed on-device and
are not sent by ML Kit. See [PRIVACY.md](PRIVACY.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before distribution.

The post-scan searchable-PDF engine has deterministic 20-page regression coverage, but RME PDF Scanner does not claim universal external viewer or SAF-provider compatibility, full accessibility certification, or low-end-device validation.

## Local development

```sh
./gradlew assembleDebug
./gradlew bundleRelease
./gradlew bundleReleaseVerification
./gradlew test
./gradlew lint
```

See [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) for unsigned/debug-signed local verification and Play upload signing. No signing credential belongs in this repository.

Historical beta-testing materials are available in [docs/BETA_SMOKE_TEST.md](docs/BETA_SMOKE_TEST.md),
[docs/INTERNAL_TESTER_GUIDE.md](docs/INTERNAL_TESTER_GUIDE.md), and
[docs/BUG_REPORT_TEMPLATE.md](docs/BUG_REPORT_TEMPLATE.md). Do not use sensitive documents in testing.

## License

RME PDF Scanner is licensed under the [Apache License 2.0](LICENSE).

## Attribution

Developed by Lucian Irimie and published under SynapseWorks.
