# Google Play Internal Testing Checklist

Use this checklist only after the repository release gates pass. It prepares an internal test; it
does not authorize publication to Google Play.

## Owner-controlled prerequisites

- [ ] Register or confirm the Play Console application for `org.synapseworks.pageharbor`.
- [ ] Create and secure the upload key; configure it only through the documented environment
  variables or ignored `local.properties` values.
- [ ] Enrol in Play App Signing and retain recovery/key-rotation information securely.
- [ ] Publish [PRIVACY.md](../PRIVACY.md) at a stable, public, non-geofenced HTTPS URL.
- [ ] Add the final SynapseWorks developer identity and a monitored privacy contact to that policy.
- [ ] Enter the same privacy-policy URL in Play Console and make it reachable from the app or store
  listing.
- [ ] Review [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md), the Google ML Kit Terms, and the
  distribution channel's attribution process before uploading a binary.

## Data safety draft

This is a draft to validate in the current Play Console form, not a claim that a static answer will
remain valid after SDK or policy updates.

| Question | Draft answer / evidence |
| --- | --- |
| Does the app collect data off-device? | **Yes**, because bundled ML Kit may transmit technical diagnostics. RME PDF Scanner code does not transmit document content. |
| Personal info, photos/documents, OCR text | **No collection by RME PDF Scanner or ML Kit** for this app flow; ML Kit documents image/text input and output as on-device. |
| Diagnostics | **Yes**: ML Kit documents performance/error information for diagnostics, reliability, compatibility, and improvement. Mark encrypted in transit according to ML Kit's current disclosure. |
| Device or other IDs | **Yes** if the current ML Kit disclosure continues to describe per-installation identifiers; verify the exact Play taxonomy at submission. |
| App and device information | **Yes** if the form exposes the documented ML Kit application/device-information categories; verify the exact taxonomy at submission. |
| Shared with third parties | Verify current Play definitions and Google relationship before submitting. Do not assert “no sharing” merely because RME PDF Scanner has no backend. |
| Advertising, personalization, account management | **No**. |
| Deletion request | RME PDF Scanner has no account or hosted document record. Use the policy contact for questions; do not claim a deletion badge unless the owner supplies a qualifying mechanism for all collected SDK data. |

Google Play holds the publisher responsible for complete SDK disclosures. Recheck Google's current
ML Kit data disclosure and Play Data safety guidance immediately before submission.

## Store and policy completion

- [ ] Set application category, content rating, target audience, and app-access answers.
- [ ] Add the internal-test app name, short/full description, screenshots, 512×512 icon, and feature
  graphic that accurately depict the released UI.
- [ ] Verify the app uses the final privacy-policy URL and that it loads without sign-in, PDF viewer,
  geofencing, or download requirement.
- [ ] Complete the current Data safety form from the draft above and retain the reviewed answers.
- [ ] Confirm target SDK and all policy declarations against the current Play Console requirements.

## Upload and tester plan

- [ ] Build only from the reviewed commit with `./gradlew verifyReleaseSigning` and
  `./gradlew bundleReleaseForPlay`.
- [ ] Verify the upload-signed AAB certificate and version name/code before upload.
- [ ] Upload to the **internal testing** track only; do not promote automatically.
- [ ] Add a small, consented tester group and provide a support/feedback route that does not ask for
  document contents or screenshots containing sensitive documents.
- [ ] Ask testers to use non-sensitive documents and to check scan, PDF save/share, JPEG export,
  OCR, searchable PDF, cancellation/retry, rotation, and uninstall/reinstall behavior.
- [ ] Review crashes/ANRs and privacy feedback without collecting document data.

## v1.0.0 production-release preparation

Repository closure evidence is complete and tagged for `v0.8.0-dev`. This checklist supports the
current `1.0.0` owner-controlled production-release preparation; it is not authorization to
upload or publish, and does not indicate that internal testing has started.

- [ ] Confirm the tester sees the expected version and version code in About RME PDF Scanner.
- [ ] For local verification only, confirm the build type is `releaseVerification`; this label and
  Git revision must not appear in a production release.
- [ ] Perform the update, clean-install, lifecycle, output, OCR, and repeated-session checks in
  [BETA_SMOKE_TEST.md](BETA_SMOKE_TEST.md).
- [ ] Record device evidence in [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md), using
  **planned** rather than claiming untested compatibility.
- [ ] File failures with [BUG_REPORT_TEMPLATE.md](BUG_REPORT_TEMPLATE.md) and reject sensitive
  attachments, OCR text, full paths, and unredacted logs.
- [ ] Keep active-operation interruption evidence separate from ordinary background/foreground
  evidence. Do not claim a process-kill or cache-inventory result when the local verification
  artifact is non-debuggable or the operation completed before the interruption could occur.
- [ ] Treat the deterministic paused-operation suite as lifecycle and stale-callback evidence only;
  it uses test APK fakes and is not present or configurable in the production release.

## Before any wider rollout

- [ ] Re-run the release build, unit, lint, and connected-device gates.
- [ ] Reconfirm version-code monotonicity, Play signing, Data safety, privacy URL, notices, and store
  assets.
- [ ] Obtain explicit owner approval for a closed, open, or production rollout.
