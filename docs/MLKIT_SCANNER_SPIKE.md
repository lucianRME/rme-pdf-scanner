# ML Kit Document Scanner Spike

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

This document tracks a technical validation spike. The integration is not the production MVP scanner flow and should not be described as complete.

## Configuration Tested

- Dependency version: `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0`.
- Scanner mode: `SCANNER_MODE_BASE_WITH_FILTER`.
- Result formats: `RESULT_FORMAT_JPEG` and `RESULT_FORMAT_PDF`.
- Gallery import: enabled.
- Page limit: 10.
- Manifest permissions: merged debug manifest declares no `INTERNET`, camera, broad storage, or advertising ID permissions after transitive network permissions are removed at manifest merge.
- Emulator or device used: `PageHarbor_API_36` AVD. Scanner launch reached a Google Play Services "Something went wrong / Try again later" screen, so camera scanning was not validated on this emulator.

## Validation Checklist

- [ ] Scanner opens.
- [ ] First-run module download behavior observed.
- [ ] Single-page scan succeeds.
- [ ] Multi-page scan succeeds.
- [ ] Page reorder succeeds.
- [ ] Page removal succeeds.
- [ ] Filters work.
- [ ] Cancellation returns safely.
- [ ] JPEG results are returned.
- [ ] PDF result is returned.
- [x] PageHarbor declares no INTERNET permission.
- [x] PageHarbor declares no camera permission.
- [ ] Subsequent scan works after scanner components are installed.
- [ ] Airplane-mode behavior after component installation.
- [ ] Physical-device behavior.
- [ ] Minimum supported Android behavior.

## Known Constraints

- Scanner logic, models, and UI are provided dynamically by Google Play Services.
- First use may require component download.
- A Google Play-enabled emulator or physical device is preferable for validation.
- Devices below the ML Kit RAM requirement may return `UNSUPPORTED`.
- The absence of PageHarbor's own `INTERNET` permission does not mean Google Play Services never uses the network.
- Public wording such as 100% offline remains unapproved until validation is complete.

## Spike Safety Notes

- The spike displays only counts and neutral availability statements.
- The spike must not display raw content URIs, file paths, scanned images, document names, or metadata that may contain personal information.
- Scanner-owned result files are not deleted by this spike because ownership and lifecycle behavior still need validation.
