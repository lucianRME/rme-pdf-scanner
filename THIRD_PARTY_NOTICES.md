# Third-Party Notices

RME PDF Scanner is licensed under Apache License 2.0. This repository-level notice accompanies
source and direct binary releases. The sections below retain the original `1.3.0` inventory and
document later additions. For Play, verify that the delivered APKs carry the required materials.
[Project license](LICENSE)

## License inventory

| Component | Packaged use | License / terms | Required treatment |
| --- | --- | --- | --- |
| AndroidX, Kotlin, Jetpack Compose, Material 3, Kotlin coroutines, JSpecify, javax.inject, and Apache Commons Codec | Android application/runtime libraries | Apache License 2.0 | Preserve the Apache license and applicable notices in a source/binary distribution. |
| PdfBox-Android `2.0.27.0` and Apache PDFBox code | Local searchable-PDF composition | Apache License 2.0 | Preserve the Apache license and applicable notices. |
| Bouncy Castle `bcprov`, `bcpkix`, and `bcutil` `1.86` | PDF cryptography and detached signature verification | Bouncy Castle licence (MIT-style) | Preserve the copyright and permission notice below. |
| Liberation Sans Regular `2.1.5` | Bundled by PdfBox-Android and embedded as a subset in searchable PDFs | SIL Open Font License 1.1 | Preserve the copyright and OFL notice; do not represent RME PDF Scanner as the font author. |
| Google ML Kit Document Scanner and Text Recognition, Google Play services / Tasks | Scanner acquisition and on-device OCR | Google ML Kit Terms of Service and applicable Google terms | Separately licensed proprietary SDKs; review and accept applicable Google terms before distribution. They are not Apache-2.0 or open source. |
| Carnegie Mellon Pronouncing Dictionary (CMUdict) | Bundled English word list derived from pinned headwords for local OCR spelling review | CMUdict redistribution terms (BSD-style) | Retain the [complete notice and disclaimer](docs/licenses/CMUDICT-LICENSE.txt). Provenance is recorded in [Privacy Boundaries](docs/PRIVACY_BOUNDARIES.md#ocr-lighting-and-spelling-review). |
| wordfreq, CC-CEDICT, Irish Word Frequency List, and Marathi/Nepali corpus lists | Bundled offline OCR spelling data | CC BY-SA 4.0 and ODbL 1.0 as identified per asset | Preserve the bundled notices and the [data provenance](docs/OFFLINE_OCR_SPELLING.md). |

The Gradle release runtime dependency graph was reviewed for this inventory. No GPL, LGPL, AGPL,
copyleft font, or native GPL-family dependency was identified. PdfBox-Android has no native ABI
library; the native libraries in the release artifact are supplied by ML Kit/Compose.

## Bouncy Castle notice

The Android app bundles the provider’s complete 1.86 notice in
`assets/licenses/Bouncy-Castle-1.86-LICENSE.txt`. The historical 1.72 notice follows.

Copyright (c) 2000-2022 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Source: <https://www.bouncycastle.org/license.html>

## Liberation Sans notice

Liberation Sans Regular, version `2.1.5`, is present in PdfBox-Android as
`assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf`. Its embedded metadata
identifies Google (2010), Red Hat (2012), Ascender Corp., and Steve Matteson, and states that it is
licensed under the SIL Open Font License, Version 1.1. RME PDF Scanner ships the original font
bytes. The matched upstream release license declares Arimo, Tinos, Cousine and Liberation as
Reserved Font Names. The default preparation bundle includes the complete copyright and OFL text
in `assets/licenses/Liberation-Sans-2.1.5-OFL-1.1.txt`.

The SIL Open Font License 1.1 permits use, study, modification, and redistribution of covered fonts
provided that the font is not sold by itself, copyright and license notices are included, and any
modified version is not distributed under a Reserved Font Name. The authoritative full license is
available from SIL: <https://openfontlicense.org/open-font-license-official-text/>.

## Google ML Kit notice

Google ML Kit is proprietary client software governed by the [ML Kit Terms of
Service](https://developers.google.com/ml-kit/terms), not an open-source dependency. ML Kit
documents that document images and text-recognition input/output are processed on-device, while
technical diagnostics may be encrypted and sent to Google. See [PRIVACY.md](PRIVACY.md) for the
user-facing disclosure and [docs/INTERNAL_TESTING_CHECKLIST.md](docs/INTERNAL_TESTING_CHECKLIST.md)
for the future Play Data safety draft.

## Distribution decision

`v1.3.0` does not add an in-app license screen: none of the reviewed licenses expressly
requires a particular UI. This repository notice is the v1.3 source/release notice. A release owner
must ensure that any independently distributed APK/AAB is accompanied by these notices and the
Apache and OFL licence texts in the chosen delivery channel. Google Play upload readiness therefore
includes a final distribution/legal-channel check rather than an unreviewed in-app UI addition.

## M35 local opt-in Office candidate

The unreleased, explicitly provisioned ARM64 Office build uses LibreOffice26.8.2.2,
source pin `9dea406ab7695df6325b11b0e19fdca8db4b7276`, with the candidate15 patches
and selected per-file/third-party terms. Its20 font faces include Liberation2.1.5,
Carlito and original Linux Libertine G20120116. Libertine G uses the actual offered
**OFL1.0**, not OFL1.1; individual faces have the versions/hashes in the qualified
inventory. Unicode16 data retains its own notice. Do not infer a single license
for every linked component from LibreOffice's project-level license.

The candidate APK carries the exact engine/font/resource notices under
`assets/notices/`. The [candidate-bound distribution record](docs/milestones/M35-CANDIDATE15-QUALIFICATION.md#distribution)
and final machine ledger reconcile actual compiled members, copied code/data,
NDK/auxiliary inputs, fonts, ordered patches, matching sources and build recipes.
The consumer APK verifier proves unchanged qualified payload bytes separately
from newly compiled RME integration code. No new dependency, network behavior,
permission or remote service is added. This is local candidate preparation;
publication, recipient delivery and release signing are separate, unperformed
actions. A future distributor must carry the matching notices and applicable
source/material obligations; this paragraph does not claim that delivery occurred.

## Default Android preparation bundle

The default bundle retains Java bridge and worker support code. The adapted LibreOfficeKit
Android JNI bridge uses MPL 2.0. The exact Java source compiled by Gradle, its attribution
and the full MPL text ship together in `assets/licenses/`. The upstream wrapper is pinned at
`9dea406ab7695df6325b11b0e19fdca8db4b7276`. RME-authored worker code uses the project license,
and generated Unicode 16.0 policy data uses the adjacent Unicode V3 notice. The optional
Office engine's separate distribution materials apply to the engine-enabled candidate above.
[Upstream bridge](https://github.com/LibreOffice/core/blob/9dea406ab7695df6325b11b0e19fdca8db4b7276/android/Bootstrap/src/org/libreoffice/kit/LibreOfficeKit.java)

The binary also carries the license and notice from the PdfBox-Android `v2.0.27.0` tag
(`45da92629dad5b3c9887eceefecc89a1423f5457`), including the applicable FontBox and Adobe
resource terms, and the license from the Bouncy Castle 1.72 runtime JAR. The full font license
comes from the byte-matched upstream Liberation 2.1.5 archive. The original legal wording
is preserved with line endings and trailing whitespace normalized.
[PdfBox-Android tag](https://github.com/TomRoush/PdfBox-Android/tree/45da92629dad5b3c9887eceefecc89a1423f5457) · [Liberation 2.1.5 source pin](https://github.com/liberationfonts/liberation-fonts/tree/4b0192046158094654e865245832c66d2104219e)

## M05 PDF password character data

The generated PDF R6 password repertoire uses Unicode 3.2 character data from
<https://www.unicode.org/Public/3.2-Update/UnicodeData-3.2.0.txt>, SHA-256
`5e444028b6e76d96f9dc509609c5e3222bf609056f35e5fcde7e6fb8a58cd446`.
`tools/generate_pdf_password_unicode_profile.py` reproduces the assigned-code-point ranges.
Copyright © 1991-2026 Unicode, Inc.; Unicode License V3 (SPDX `Unicode-3.0`),
compatible with this Apache-2.0 distribution. The full copyright/permission notice is bundled in
[app/src/main/assets/licenses/Unicode-3.0.txt](app/src/main/assets/licenses/Unicode-3.0.txt).
This is static character data, with no executable dependency, permission, network or data collection.
RFC4013 fixes SASLprep to Unicode 3.2; the app rejects later characters rather than accepting
a password that standards-strict PDF readers can reject. No Unicode source code is copied.

## M10 local candidate — CameraX 1.6.2 (3 November 2026)

The historical release inventory above is retained. The unreleased M10 candidate adds AndroidX CameraX core/camera2/lifecycle/view1.6.2 for optional direct continuous still capture. CameraX and its Camera2 pipe/viewfinder/video support classes, Dagger, Guava/failureaccess, atomicfu, AndroidX EXIF/tracing/concurrent and annotations are Apache-2.0-family runtime dependencies; camera-core additionally declares BSD-3-Clause native image-helper content. No video/audio/analysis/model use case is bound. This does not relicense the existing Google ML Kit SDK.

The AndroidX camera-core1.6.2 source-artifact Apache license and upstream LibYuv BSD notice/IP grant are reproduced in `app/src/main/assets/licenses/CameraX-*` and packaged with the APK. LibYuv copyright is 2011 The LibYuv Project Authors; its full notice/disclaimer and patent grant remain unchanged. Upstream notice source: `https://chromium.googlesource.com/libyuv/libyuv/+/refs/heads/main/`. The POM/AAR/source-artifact identities and runtime graph are recorded in the M10 evidence root. CameraX supplies additional native image/surface helpers; the historical statement that only ML Kit/Compose supply native libraries applies to that older release inventory.

CameraX's additional annotation/runtime support includes Checker Framework checker-qual3.43.0 (MIT, full notice in `assets/licenses/checker-qual-notice-0.txt`) and Jakarta Inject2.0.1 (Apache-2.0, license and notice in the adjacent assets). AndroidX Media3 common/container/muxer1.9.0 is transitive via camera-view/video; no media recording or network data source is bound. Full graph is retained; these are not additional application features.


## M19 local candidate — ZXing core 3.5.4

Bundled `com.google.zxing:core:3.5.4` supplies on-device QR decoding. This uses only the core
Java library, not the historical ZXing Android Barcode Scanner application or any service.
The pinned core POM declares no runtime dependencies; JUnit is test scope only. Upstream
is maintenance-only. No native code, decoder model, model download, telemetry, network
permission, backend or cloud SDK is introduced. Existing ML Kit terms/qualifications remain.

Tagged sources: [core POM](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/core/pom.xml),
[license](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/LICENSE),
[NOTICE](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.4/NOTICE).
Unmodified Apache-2.0 license and applicable upstream notices are packaged at
`assets/licenses/ZXing-3.5.4-Apache-2.0.txt` and `assets/licenses/ZXing-3.5.4-NOTICE.txt`.
Resolved graph, artifact hashes, source review and package impact are recorded in the M19
milestone evidence. Host-only Segno 1.6.6 (BSD-3-Clause) generates independent synthetic QR
fixtures in a temporary tool directory; it is not an application or Gradle dependency.


## Optional Google Play language-pack delivery profile

Only builds explicitly configured with `rmeTranslationPlayDelivery=true` include
`com.google.android.play:asset-delivery:2.3.0`. Google Play Asset Delivery is
provided by Google under the [Play Core SDK Terms of Service](https://developer.android.com/guide/playcore#play-core-software-development-kit-terms-of-service)
and the referenced [Google APIs Terms of Service](https://developers.google.com/terms),
not under this repository's Apache-2.0 license. The SDK remains unmodified.
Google and its licensors retain the SDK rights. The SDK may be redistributed
only within an API client and subject to those terms.

The default build omits this delivery dependency and its on-demand asset modules.
Language model notices remain with their pinned archives and existing runtime
notice material; Play delivery does not replace the model licenses. See
[Offline language delivery](docs/OFFLINE_LANGUAGE_DELIVERY.md) for the separate
profile, exact pack pins, local testing scope, and publication limits.
