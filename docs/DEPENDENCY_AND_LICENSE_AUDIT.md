# Dependency and License Audit

Status: updated for `v1.4.0` preparation, 17 September 2026.

## Method

The release runtime graph was resolved with:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Direct declarations are AndroidX/Compose/Material, Room `2.8.5`, Google ML Kit Document Scanner
`16.0.0`, Google ML Kit Text Recognition `16.0.1`, and PdfBox-Android `2.0.27.0`. KSP is a build-time
processor used for Room code generation and is not packaged in the app. The inspection included POM
metadata, the PdfBox Android AAR, and the minified release artifact.

## Results

- AndroidX, Compose, Kotlin, coroutines, JSpecify, javax.inject, and Apache Commons Codec are
  Apache-2.0-family dependencies.
- Room runtime/KTX are AndroidX Apache-2.0 components. Room adds no network permission or cloud SDK;
  RME uses it only for the private on-device library and FTS index.
- PdfBox-Android is Apache-2.0 and brings Bouncy Castle `1.72` transitively. Bouncy Castle's
  MIT-style notice is preserved in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
- PdfBox-Android bundles Liberation Sans Regular `2.1.5`, licensed in its font metadata under SIL
  OFL 1.1. RME PDF Scanner embeds an unmodified subset into searchable PDFs. No Reserved Font Name was
  found in the bundled font metadata.
- ML Kit and Google Play services are proprietary Google SDKs subject to the ML Kit Terms of
  Service. They are retained because they implement the existing scanner/OCR capability; this audit
  does not recharacterize them as open source.
- No GPL, LGPL, AGPL, or similarly reciprocal dependency was identified in the resolved release
  graph. No direct commercial fee or source-availability obligation was found, but Google terms are
  a separate acceptance and policy prerequisite for the release owner.

## Notices and future distribution

The current repository-level notice is [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). No
reviewed dependency requires a particular in-app license UI. Before an independently distributed
APK/AAB or Play submission, the owner must make sure the delivery channel accompanies the binary
with the required Apache/OFL/Bouncy notices and complete a final legal review of the proprietary
Google terms. That owner-controlled distribution decision is tracked in the internal-testing
checklist.
