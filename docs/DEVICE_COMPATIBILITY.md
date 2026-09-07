# Device Compatibility Matrix

RME PDF Scanner has `minSdk 26` (Android 8) and `targetSdk 36`. This matrix records evidence; it does
not claim compatibility for devices that have not been tested.

| Device / Android version | Form factor | Status | Coverage |
| --- | --- | --- | --- |
| Samsung SM-S938B / Android 16 | Phone | Manually validated, with explicit gaps | v0.8 update/clean install, repeated releaseVerification scanner sessions including final searchable-PDF cancel/retry/save and share cancellation, normal/searchable PDF, JPEG export, one/three-page OCR, Copy Text, rotation, and ordinary background/foreground; deterministic paused-operation coverage passed, while physical active-operation process kill and private-cache inventory remain unverified |
| API 36 emulator | Pixel 7 phone emulator | Automated and partially manually validated | Recovered Android 16/API 36 Google Play ARM64 AVD; releaseVerification Home and ML Kit scanner entry; 103/103 debug connected tests |
| Android 8–9 | Phone | Blocked | No installed/attached minimum-SDK target |
| Android 10 | Phone | Planned | Manual smoke |
| Android 12 | Pixel 7 phone emulator | Automated and partially manually validated | New lower-resource Android 12/API 31 Google APIs ARM64 AVD; releaseVerification Home; 103/103 debug connected tests |
| Android 13 | Pixel or equivalent | Planned | Manual smoke |
| Android 14 | Phone | Planned | Manual smoke |
| Android 15 | Phone | Planned | Manual smoke |
| Android 16 | Pixel | Planned | Manual smoke |
| Lower-resource emulator | Pixel 7 phone emulator | Established | Android 12/API 31 Google APIs ARM64, cold booted with 2 GB RAM and two cores; not a physical low-memory-device claim |
| Android 12 | Pixel Tablet emulator | Automated and partially manually validated | API 31 Google APIs ARM64, 2560×1600/320 dpi; stable cold boot, releaseVerification Home/dialogs, 200% font and resize checks, 103/103 debug connected tests |

Statuses: **automated** means deterministic suite coverage; **manually validated** means a recorded
human smoke; **planned** has no compatibility claim; **unavailable** means no test device exists.
