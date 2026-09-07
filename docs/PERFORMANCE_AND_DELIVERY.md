# Performance and Delivery Validation

> Historical record for RME: PDF & Document Scanner, formerly PageHarbor. Product names below reflect the investigation or validation at the time.

Status: `v0.7.0-dev` baseline and `v0.8.0-dev` closure inspection. The local
releaseVerification approach remains for beta regression smoke.

## Scope and method

Measurements use the minified, resource-shrunk `releaseVerification` variant on Samsung
SM-S938B, Android 16, ABI `arm64-v8a`. It is debug-signed solely for local installation; its
release code and resources match the unsigned `release` verification artifact. Android's
`am start -W` measures activity-display timing, not a full application-process trace. No
PageHarbor telemetry, tracing upload, benchmark dependency, or PageHarbor startup initializer was
added for this work. ML Kit's separately documented technical diagnostics remain an external SDK
behavior and are disclosed in `PRIVACY.md`.

For cold starts, the app was force-stopped before each launch. For warm starts, Home was sent to
the background and brought back through Android. A hot relaunch targeted the already foreground
activity. Home content was checked after the measurements.

## Samsung startup results

| Scenario | Measurements (ms) | Median | Range |
| --- | ---: | ---: | ---: |
| Cold activity display | 128, 124, 111, 100, 102 | 111 | 100–128 |
| Warm foreground return | 27, 6, 9, 7, 7 | 7 | 6–27 |
| Hot relaunch | 2 | 2 | 2–2 |

The first warm return (27 ms) is the only visible warm outlier. The sample is intentionally a
small device smoke, not a performance certification. Source review confirms that Home launches
only Compose and asynchronous stale app-owned cache cleanup; scanner client creation is
user-triggered, the app creates an ML Kit recognizer only for explicit OCR on `Dispatchers.IO`,
and PDFBox initializes only while generating a searchable PDF. The packaged ML Kit component
provider remains an existing library manifest initializer, so its isolated contribution cannot be
derived from this smoke; no PageHarbor eager work was added. No startup regression or main-thread
document-processing path was found.

## Artifact baseline and delivery analysis

The final v0.8 closure inspection recorded these exact local artifact bytes:

| Artifact | Bytes |
| --- | ---: |
| Debug APK | 64,977,385 |
| Unsigned release APK | 50,377,255 |
| Debug-signed releaseVerification APK | 50,389,751 |
| Unsigned release AAB | 28,579,553 |
| Debug-signed releaseVerification AAB | 28,612,644 |

The debug and release APKs plus the unsigned release AAB match the prior v0.8 hardening
inspection exactly. The debug-signed releaseVerification AAB is 42 bytes larger than its prior
recording; the interruption hardening adds no production dependency, resource, test fixture, or
runtime gate, so this archive-level package variation is not a meaningful delivery-size change.

Bundletool 1.18.1 built a temporary default APK set from the releaseVerification AAB for the
connected Samsung device specification. Its reported total for that device was 12,893,237 bytes.
The delivered set contained base master, `arm64_v8a`, English-language, and `xxhdpi` splits.
This is a local device-targeted APK-set calculation, not a claim about Google Play download size,
compression, serving, or install accounting.

The universal verification APK packages `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` native
libraries. On the target ABI, `libmlkit_google_ocr_pipeline.so` is 11,064,544 uncompressed bytes;
the Compose graphics native library is 10,096 bytes. ML Kit's bundled text-recognition and scanner
dependencies therefore dominate native delivery size. PDFBox-Android contributes Java/resources,
including its embedded font and Bouncy Castle transitives, but no native ABI library. Compose and
AndroidX are shared UI/runtime dependencies. No dependency was removed or added because each
remains required for the existing feature set.

R8 mapping, usage, seeds, and configuration reports were generated locally for both release
variants and inspected without being retained in source control. The narrow optional JPEG-2000
rule remains limited to PdfBox's absent optional codec types. Release inspection found the ML Kit
manifest discovery components and required PDFBox code present, with no test-only or debug-only
classes/resources in the release APK. No credentials or signing values were found in artifacts or
build output.

## Failure and cleanup matrix

| Boundary | Expected outcome | Deterministic coverage |
| --- | --- | --- |
| No source / source open failure | Safe source or preparation failure; no success | PDF writer and coordinator tests |
| Null or unavailable destination | Safe destination failure; prepared output deleted | Coordinator tests |
| Write, mid-copy, flush, or close failure | Write failure; prepared output deleted | Coordinator tests |
| Copy cancellation | Cancellation stays distinct; prepared output deleted | Coordinator tests |
| Private cache unavailable | Temporary-storage failure before generation | Coordinator test double |
| Private-cache deletion refusal | No crash or false success; cleanup remains best effort | Coordinator test double |
| Short source reads | Complete byte-for-byte copy | PDF writer test |
| Malformed SAF URI | Existing safe source/destination category | Activity boundary handling |

Real device storage exhaustion and every third-party provider remain environment-dependent manual
validation limits. The implementation deliberately avoids fabricating a success after a failed
write, avoids automatic retries, and leaves retry initiation to the user through the existing UI.
