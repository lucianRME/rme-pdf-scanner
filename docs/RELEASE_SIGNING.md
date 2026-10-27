# Release Signing and Android App Bundles

RME: Private PDF Scanner continues the existing `org.synapseworks.pageharbor` Google Play application. The `PAGEHARBOR_RELEASE_*` environment variables and `pageharbor.release.*` local properties below remain unchanged, as do the signing configuration and keys. Every upload must follow the existing version-code and release process.

`bundleRelease` always creates a local release bundle. Without credentials it is an unsigned verification artifact and must not be uploaded to Google Play.

For an installable release-equivalent local APK, use the debug-signed verification variant:

```sh
./gradlew assembleReleaseVerification
```

This variant retains release minification and resource shrinking but signs with the Android debug key. It is only for local verification and retains the production application ID. Do not install it over a Play or GitHub installation with a different certificate. The build configuration blocks non-isolated Gradle install/connected tasks; instrumentation uses `connectedPhase2TestAndroidTest` with the separate `org.synapseworks.pageharbor.phase2test` target. Install a verification APK only on a dedicated test device/emulator whose package and certificate have been checked.

## Production Play upload signing

Do not create or commit a keystore for this project as part of normal development. Keep the upload keystore and its values in the developer's secure local environment or CI secret store. Configure every value through either environment variables or the ignored root `local.properties` file:

| Environment variable | `local.properties` key |
| --- | --- |
| `PAGEHARBOR_RELEASE_STORE_FILE` | `pageharbor.release.storeFile` |
| `PAGEHARBOR_RELEASE_STORE_PASSWORD` | `pageharbor.release.storePassword` |
| `PAGEHARBOR_RELEASE_KEY_ALIAS` | `pageharbor.release.keyAlias` |
| `PAGEHARBOR_RELEASE_KEY_PASSWORD` | `pageharbor.release.keyPassword` |

For a separately authorized production build, when all four values are available, explicitly opt in and create the upload-signed bundle:

```sh
./gradlew bundleReleaseForPlay -Ppageharbor.enableProductionSigning=true
```

`bundleReleaseForPlay` and `assembleReleaseForPlay` fail before building when credentials are absent or incomplete. They do not substitute a debug key. Google Play App Signing, upload-key custody, and publishing remain owner-controlled steps outside the normal repository workflow.

## Verification checklist

- Keep keystores, `local.properties`, APKs, and AABs ignored.
- Run `./gradlew bundleRelease` for an unsigned bundle check.
- Run `./gradlew bundleReleaseVerification` and install the matching verification APK for local smoke testing.
- Run the normal unit, lint, and connected suites before any owner-controlled Play upload.
