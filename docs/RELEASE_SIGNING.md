# Release Signing and Android App Bundles

RME: PDF & Document Scanner continues the existing `org.synapseworks.pageharbor` Google Play application. The `PAGEHARBOR_RELEASE_*` environment variables and `pageharbor.release.*` local properties below remain unchanged, as do the signing configuration and keys. The product rename does not change version metadata; a future upload must follow the existing version-code and release process.

`bundleRelease` always creates a local release bundle. Without credentials it is an unsigned verification artifact and must not be uploaded to Google Play.

For an installable release-equivalent local APK, use the debug-signed verification variant:

```sh
./gradlew assembleReleaseVerification
./gradlew installReleaseVerification
```

This variant retains release minification and resource shrinking but signs with the Android debug key. It is only for local verification.

## Production Play upload signing

Do not create or commit a keystore for this project as part of normal development. Keep the upload keystore and its values in the developer's secure local environment or CI secret store. Configure every value through either environment variables or the ignored root `local.properties` file:

| Environment variable | `local.properties` key |
| --- | --- |
| `PAGEHARBOR_RELEASE_STORE_FILE` | `pageharbor.release.storeFile` |
| `PAGEHARBOR_RELEASE_STORE_PASSWORD` | `pageharbor.release.storePassword` |
| `PAGEHARBOR_RELEASE_KEY_ALIAS` | `pageharbor.release.keyAlias` |
| `PAGEHARBOR_RELEASE_KEY_PASSWORD` | `pageharbor.release.keyPassword` |

When all four values are available, verify them and create the upload-signed bundle:

```sh
./gradlew verifyReleaseSigning
./gradlew bundleReleaseForPlay
```

`bundleReleaseForPlay` and `assembleReleaseForPlay` fail before building when credentials are absent or incomplete. They do not substitute a debug key. Google Play App Signing enrollment, upload-key creation, and publishing are future owner-controlled steps and are outside this repository workflow.

## Verification checklist

- Keep keystores, `local.properties`, APKs, and AABs ignored.
- Run `./gradlew bundleRelease` for an unsigned bundle check.
- Run `./gradlew bundleReleaseVerification` and install the matching verification APK for local smoke testing.
- Run the normal unit, lint, and connected suites before any owner-controlled Play upload.
