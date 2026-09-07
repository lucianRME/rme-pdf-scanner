# RME PDF Scanner 1.2.0 screenshots

Six direct Android captures are provided for each device category, showing the current product name and version 1.2.0 (build 13). All images use the light theme and are portrait 9:16, 24-bit RGB PNGs without alpha.

| Folder | Resolution | Android display density | Logical width |
| --- | --- | --- | --- |
| `phone` | 1080 × 1920 | 360 dpi | 480 dp |
| `tablet-7-inch` | 1440 × 2560 | 360 dpi | 640 dp |
| `tablet-10-inch` | 1800 × 3200 | 320 dpi | 900 dp |

The tablet folders represent small- and large-tablet Android emulator display profiles, not captures from physical tablets. Each profile renders the app at its own native resolution and logical width.

## Capture provenance

Source: tag `v1.2.0`, commit `60b84ccaa7956304a51c54b9c35f2333fed9a6d3`.

Captures were taken on an isolated Android 12/API 31 emulator using a temporary instrumentation build of the release source. The capture build sets `SHOW_BUILD_DETAILS` to false to match production presentation; the screenshot harness lives outside the repository. Production application source, signing, permissions, and release artifacts are unchanged.

A three-page, explicitly labelled reading sample was generated locally and loaded through the existing instrumentation session seam. The sample JPEGs and PDF contain no personal or customer information. Filtering uses the app's real image engine; OCR is run by the app's bundled ML Kit recognizer. The files are direct, unedited UI screenshots, including the Android system bars. The scan and OCR screens show representative document handling after page acquisition; no external scanner or save-success screenshot is claimed.

## Suggested upload order and alt text

Use the numbered order in each folder. Alt text suggestions are under 140 characters.

| File | Suggested alt text |
| --- | --- |
| `01-home.png` | RME PDF Scanner Home, with Scan document, privacy information and About actions. |
| `02-scanned-document.png` | Three-page reading sample in Scan Result, with document preview and page navigation. |
| `03-filters-and-export.png` | Grayscale document filter with PDF save, searchable PDF, share and page export controls. |
| `04-recognized-text.png` | Locally recognized text from a three-page reading sample, with page navigation. |
| `05-privacy.png` | Privacy dialog explaining local document handling, export choices and Google ML Kit diagnostics. |
| `06-about.png` | About RME PDF Scanner, showing version 1.2.0, build 13, SynapseWorks attribution and open-source license. |

## Verification and publication

- Capture instrumentation completed successfully for all three display profiles.
- Dimensions, RGB PNG encoding, aspect ratios, and image integrity checked for all 18 files.
- Every image visually reviewed; Home, About and privacy show the new name, and About shows version 1.2.0.
- Local text recognition checked for former-name variants and required current branding/version text.
- `manifest.json` records each file's dimensions and SHA-256 digest.

The dimensions and encoding follow [Google Play's screenshot guidance](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en). Upload these to the corresponding phone, 7-inch tablet and 10-inch tablet sections when preparing the store listing. No store upload or rollout was performed.

These sets supersede the older screenshot folders for the renamed listing. The separate feature-graphic PNG still requires export from the updated SVG.
