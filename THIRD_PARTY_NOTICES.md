# Third-Party Notices

RME PDF Scanner is licensed under Apache License 2.0; see [LICENSE](LICENSE). This file records the
third-party components packaged by the `0.7.0-dev` Android application. It is a repository-level
distribution notice and must accompany any source or binary release made outside Google Play.
Before a Play upload, the release owner must confirm with counsel or the distribution process that
the Play-delivered binary is accompanied by any notices required for that channel.

## License inventory

| Component | Packaged use | License / terms | Required treatment |
| --- | --- | --- | --- |
| AndroidX, Kotlin, Jetpack Compose, Material 3, Kotlin coroutines, JSpecify, javax.inject, and Apache Commons Codec | Android application/runtime libraries | Apache License 2.0 | Preserve the Apache license and applicable notices in a source/binary distribution. |
| PdfBox-Android `2.0.27.0` and Apache PDFBox code | Local searchable-PDF composition | Apache License 2.0 | Preserve the Apache license and applicable notices. |
| Bouncy Castle `bcprov`, `bcpkix`, and `bcutil` `1.72` | Transitive PdfBox cryptography/support code | Bouncy Castle licence (MIT-style) | Preserve the copyright and permission notice below. |
| Liberation Sans Regular `2.1.5` | Bundled by PdfBox-Android and embedded as a subset in searchable PDFs | SIL Open Font License 1.1 | Preserve the copyright and OFL notice; do not represent RME PDF Scanner as the font author. |
| Google ML Kit Document Scanner and Text Recognition, Google Play services / Tasks | Scanner acquisition and on-device OCR | Google ML Kit Terms of Service and applicable Google terms | Separately licensed proprietary SDKs; review and accept applicable Google terms before distribution. They are not Apache-2.0 or open source. |

The Gradle release runtime dependency graph was reviewed for this inventory. No GPL, LGPL, AGPL,
copyleft font, or native GPL-family dependency was identified. PdfBox-Android has no native ABI
library; the native libraries in the release artifact are supplied by ML Kit/Compose.

## Bouncy Castle notice

Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc.

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
licensed under the SIL Open Font License, Version 1.1. RME PDF Scanner does not modify or rename the
font. Searchable-PDF generation embeds a subset of the original font solely to render its invisible
Unicode text layer; this is permitted by OFL 1.1. No Reserved Font Name declaration was found in
the bundled font metadata.

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

`v0.7.0-dev` does not add an in-app license screen: none of the reviewed licenses expressly
requires a particular UI. This repository notice is the v0.7 source/release notice. A release owner
must ensure that any independently distributed APK/AAB is accompanied by these notices and the
Apache and OFL licence texts in the chosen delivery channel. Google Play upload readiness therefore
includes a final distribution/legal-channel check rather than an unreviewed in-app UI addition.
