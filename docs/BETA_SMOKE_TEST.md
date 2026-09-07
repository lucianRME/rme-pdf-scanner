# Internal Beta Smoke Test

Use only non-sensitive sample pages. This is a 10–15 minute manual smoke, not a request to upload
anything to Google Play.

Record completed and blocked items in [BETA_MATRIX_RESULTS.md](BETA_MATRIX_RESULTS.md); do not mark
an item passed solely because a deterministic test covers a related boundary.

## Before starting

- Record the version, build code, and build type from **About RME PDF Scanner**.
- Use a local `releaseVerification` build or the approved internal-testing build.
- Do not use IDs, invoices, medical records, personal photos, OCR text, or full file paths.

## Install and update

1. Install the build and open RME PDF Scanner.
2. If a previous internal build is available, install this build over it without uninstalling.
3. Confirm the displayed version is the expected one and Home opens normally.
4. Uninstall, reinstall, and confirm the same Home behavior. Downgrade is intentionally unsupported.

## Core flow

1. Scan one page, then scan multiple pages; try gallery import if the scanner offers it.
2. Cancel the scanner once and confirm RME PDF Scanner remains usable.
3. From Scan Result, save a PDF, share a PDF, export a JPEG page, and save a searchable PDF.
4. Run OCR with English, Romanian diacritics, and German characters if safe sample text is available.
   Open OCR Result and use Copy Text.

## Lifecycle and repeat use

1. Rotate on Scan Result and OCR Result.
2. Background and return to the app once.
3. Cancel a save picker once, retry it, then Discard.
4. Complete a second scan/OCR/export session. Confirm no prior preview, OCR text, filename category,
   progress, or success message appears.

## Physical-device recovery supplement

When using a debuggable development build and explicit owner approval, inspect only RME PDF Scanner-owned
private cache paths before and after the flow. Do not bypass Android's `run-as` boundary on a
non-debuggable releaseVerification artifact; record that limitation instead. For active OCR or
searchable-PDF interruption, use a fixture large enough for the operation to be visibly active;
otherwise record the check as unverified rather than treating a quick completion as equivalent.

The deterministic instrumentation suite separately pauses fake OCR and fake searchable-PDF
generation; it validates ownership, stale-completion rejection, and cleanup without a production
delay or a tester-accessible control. It complements, but does not replace, physical process-kill
evidence.

## Tablet and large-window supplement

When a stable tablet or resizable target is available, repeat the Home, dialog, Scan Result, and OCR
Result checks in its native large size, a portrait-equivalent size, and 200% font. Confirm that all
actions remain reachable by scrolling or semantics rather than device coordinates. Record whether
scanner/gallery and SAF system UI are actually usable in the environment; headless-camera behavior
must not be reported as a completed scanner-to-output flow.

## Report immediately

Report an app close, missing picker, stale page/text, corrupt PDF, wrong filename, OCR failure,
share failure, stuck progress, or a permission prompt. Use
[BUG_REPORT_TEMPLATE.md](BUG_REPORT_TEMPLATE.md); attach screenshots only when they contain no
sensitive content.
