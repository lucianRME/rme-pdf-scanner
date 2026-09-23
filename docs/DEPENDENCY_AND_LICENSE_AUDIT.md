# Dependency and License Audit

Status: updated for `v1.5.0` preparation, 22 September 2026.

## Method

The release runtime graph was resolved with:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Direct runtime declarations in v1.5 are listed below; the resolved graph also includes the Kotlin
standard library `2.2.20` supplied by the Kotlin Android plugin:

- AndroidX Core KTX `1.17.0`, Lifecycle Runtime KTX `2.9.4`, and Activity Compose `1.11.0`.
- Compose BOM `2025.09.01`, Compose UI/UI Graphics/UI Tooling Preview, Material 3, and the extended
  Material icon set.
- Google Play services ML Kit Document Scanner `16.0.0` and ML Kit Text Recognition `16.0.1`.
- PdfBox-Android `2.0.27.0` for local PDF parsing and searchable-PDF generation.
- Room Runtime/KTX `2.8.5`.
- Google Play In-App Review `2.0.2`.
- AndroidX Biometric `1.1.0`, added in v1.5 for Android system-authentication app-lock prompts. It
  is Apache-2.0, does not provide RME with biometric templates, and introduces no analytics,
  telemetry, cloud service, or network behavior.

KSP `2.2.20-2.0.4`, Room Compiler `2.8.5`, AndroidX test libraries, and Compose test/tooling
  artifacts are build- or test-time dependencies and are not packaged as runtime application
  features. The inspection included POM metadata, the PdfBox Android AAR, the resolved
  `releaseRuntimeClasspath`, and the minified release artifact.

## Results

- AndroidX, Compose, Kotlin, coroutines, JSpecify, javax.inject, and Apache Commons Codec are
  Apache-2.0-family dependencies.
- Room runtime/KTX are AndroidX Apache-2.0 components. Room adds no network permission or cloud SDK;
  RME uses it only for the private on-device library and FTS index.
- AndroidX Biometric is an Apache-2.0 AndroidX library. RME uses only its `BiometricPrompt` API;
  device credentials and biometric enrollment remain owned by Android, with no RME PIN or app-lock
  verifier stored locally.
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
