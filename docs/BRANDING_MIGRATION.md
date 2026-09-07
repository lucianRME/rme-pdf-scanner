# RME: PDF & Document Scanner branding migration

The product formerly called PageHarbor now uses **RME: PDF & Document Scanner** as its installed Android application label and **RME PDF Scanner** for compact UI, About, privacy copy, and natural prose. This is a presentation-only change.

## Compatibility and privacy

- `applicationId` and namespace remain `org.synapseworks.pageharbor`; package directories and the launcher activity are unchanged.
- `app/build.gradle.kts` is unchanged: signing configuration, credential lookup names, keys, version name `1.1.0`, and version code `12` are preserved. Existing Google Play listing URLs still use the same application ID. A future upload follows the normal release/version-code process.
- FileProvider remains `${applicationId}.fileprovider`. Its paths, grant behavior, backup exclusions, private cache directories, file prefixes, cleanup policies, and save/share implementations are unchanged.
- No database, preference, storage, or settings migration is introduced. Existing exported files and private data/settings remain compatible because neither their Android identity nor their storage identifiers or handling changed. Active scans remain in-memory sessions under the existing lifecycle policy; this rename does not add process-death persistence.
- No permission, dependency, network behavior, document retention, or privacy guarantee changes. Existing ML Kit diagnostic disclosures and scanner-component availability caveats remain intact; the rename does not introduce stronger offline claims.
- The Gradle display name changes to `RME PDF Scanner`. Repository directory, Git remote, source URLs, package paths, and standard `app-*.apk` artifact names stay unchanged.

## Application surface review

`strings.xml` provides the full and short names. Home uses the short resource; About actions/headings, supporting copy, and privacy dialogs use the new brand. The manifest already references `@string/app_name`, and the launcher activity inherits that label. No localized app-name overrides, shortcuts, branded notification channels, or splash text exist. Export/share titles and default filenames are already neutral. Launcher/adaptive/monochrome icon artwork and system splash resources contain no product-name text and are unchanged.

Editable SVG titles, descriptions, and wordmarks now use the short name; existing mark geometry, colors, and filenames remain. Wordmark font sizes were adjusted to fit. See [store asset follow-up](../assets/play-store/README.md) for raster assets that intentionally remain historical exports.

## Remaining old-name occurrences

The case-insensitive repository review used `page[ _-]?harbou?r` to cover PageHarbor, PageHarbour, Page Harbour, page_harbor, and hyphenated variants. Tracked source and documentation were reviewed along with asset filenames and raster text. Git history, generated build/cache output, and private local configuration are not product copy and were not rewritten.

| Category | Retained occurrences | Reason |
| --- | --- | --- |
| Intentionally retained technical identifier | `org.synapseworks.pageharbor` in packages, imports, tests, application ID, namespace, provider/fixture URIs, and Play links | Preserve Android/Play identity and package structure. |
| Intentionally retained technical identifier | `PAGEHARBOR_RELEASE_*`, `pageharbor.release.*` in build configuration and signing documentation | Preserve existing credential lookup and signing workflow. |
| Intentionally retained technical identifier | `PageHarbor*` internal Kotlin symbols/tests/previews, `Theme.PageHarbor`, `pageharbor_*` color resources, and `pageharbor-cache-` test-only temporary-directory prefix | Internal implementation names are not displayed as product copy; no symbol or package migration is needed. |
| Intentionally retained technical identifier | `pageharbor-android` source/support URLs and `pageharbor-*.svg` asset filenames | Preserve repository identity and existing asset paths. SVG displayed text has changed. |
| Historical documentation | Released CHANGELOG entries; existing ADRs; OCR, scanner, smart-output, and searchable-PDF investigation records; beta/performance results; prior device observation in lifecycle validation | Preserve the name under which the original decisions, releases, or measurements were recorded. Historical record notices explain the current name. |
| Historical documentation | Former-name references in README, branding guidance, ADR-012, and this audit | Explain continuity between the former product and the renamed application. |
| Should still be changed before publication | Nine historical PNG exports listed in the store asset follow-up | Old text is baked into screenshots/feature graphic. These are not Android runtime assets; recapture screenshots and export the updated SVG before uploading. |

No accidental old product-name copy remains in application UI strings or current editable SVG text. The source/support URLs intentionally keep the repository name.

## Validation

- `./gradlew assembleDebug assembleReleaseVerification test lint assembleDebugAndroidTest --offline`: passed.
- Unit tests: 171 passed in each of debug, release, and releaseVerification (513 executions, no failures or skips).
- Lint: passed with 28 warnings and one hint; no errors. No lint rules or tests were disabled.
- Packaged debug APK inspection: label `RME: PDF & Document Scanner`, package `org.synapseworks.pageharbor`, version `1.1.0` / `12`, and no INTERNET permission.
- `ANDROID_SERIAL=emulator-5560 ./gradlew connectedDebugAndroidTest --offline`: 116 passed on a disposable Android 12 / API 31 emulator, no failures or skips. New tests verify the installed app/launcher label and existing FileProvider authority; Home/About/privacy and large-font tests also passed.
- SVG wordmarks rendered for visual inspection. All 18 checked-in PNG assets inspected with local text recognition; nine contain the former name. The text-free launcher icon remains unchanged.
- `git diff --check`: passed. Diff review confirms no dependency, signing, persisted-storage, or repository-identity changes.

A physical-device upgrade from a Play-signed installation and live Play Console/store updates were not performed. Compatibility here is supported by unchanged identity/storage/signing configuration, existing tests, and installed-package checks on the disposable emulator.

## Files changed

- `AGENTS.md`
- `CHANGELOG.md`
- `PRIVACY.md`
- `README.md`
- `ROADMAP.md`
- `THIRD_PARTY_NOTICES.md`
- `app/proguard-rules.pro`
- `app/src/androidTest/java/org/synapseworks/pageharbor/ApplicationBrandingTest.kt`
- `app/src/androidTest/java/org/synapseworks/pageharbor/ClipboardTextTest.kt`
- `app/src/androidTest/java/org/synapseworks/pageharbor/HomeScreenTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/synapseworks/pageharbor/document/searchablepdf/SearchablePdfTemporaryFileCleanup.kt`
- `app/src/main/java/org/synapseworks/pageharbor/ocr/MlKitOcrEngine.kt`
- `app/src/main/java/org/synapseworks/pageharbor/ui/home/HomeScreen.kt`
- `app/src/main/java/org/synapseworks/pageharbor/ui/theme/DesignTokens.kt`
- `app/src/main/java/org/synapseworks/pageharbor/ui/theme/Type.kt`
- `app/src/main/res/values/strings.xml`
- `assets/app-icon/pageharbor-app-icon-source.svg`
- `assets/branding/pageharbor-brand-mark.svg`
- `assets/github/pageharbor-github-social-preview.svg`
- `assets/logo/pageharbor-horizontal-lockup.svg`
- `assets/play-store/README.md`
- `assets/play-store/feature-graphic-1024x500.svg`
- `assets/play-store/pageharbor-feature-graphic-concept.svg`
- `assets/source/pageharbor-mark-monochrome.svg`
- `design/DESIGN_SYSTEM.md`
- `docs/ARCHITECTURE.md`
- `docs/BETA_MATRIX_RESULTS.md`
- `docs/BETA_SMOKE_TEST.md`
- `docs/BRANDING.md`
- `docs/BRANDING_MIGRATION.md`
- `docs/BUG_REPORT_TEMPLATE.md`
- `docs/DECISIONS.md`
- `docs/DEPENDENCY_AND_LICENSE_AUDIT.md`
- `docs/DEVICE_COMPATIBILITY.md`
- `docs/INTERNAL_TESTER_GUIDE.md`
- `docs/INTERNAL_TESTING_CHECKLIST.md`
- `docs/LIFECYCLE_AND_FAILURE_VALIDATION.md`
- `docs/MLKIT_SCANNER_SPIKE.md`
- `docs/OCR_SPIKE.md`
- `docs/PERFORMANCE_AND_DELIVERY.md`
- `docs/PRIVACY_BOUNDARIES.md`
- `docs/PRODUCT_FLOW.md`
- `docs/RELEASE_SIGNING.md`
- `docs/SEARCHABLE_PDF_SPIKE.md`
- `docs/SMART_OUTPUT_SPIKE.md`
- `settings.gradle.kts`
