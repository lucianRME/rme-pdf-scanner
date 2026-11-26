# Offline OCR spelling

Open **Recognized text → Review spelling**. When an OCR script covers several languages, select
**Change spelling language**. The dialog defaults to a matching device language when available,
or the first choice for that script. Older results without a recorded script offer all choices.
For saved documents, open **Review text → Edit → Review spelling**.

The bundled choices are English, French, German, Irish, Italian, Romanian, Spanish, Simplified
Chinese, Traditional Chinese, Japanese, Korean, Hindi, Marathi and Nepali. An OCR recognizer's
script coverage can be broader than spelling review's language lists.

Word lists are loaded in memory only when spelling review opens. Fresh results offer a reviewed
copy, and saved-document corrections go into the existing editable draft. Changes require a
selection and then a separate copy or save action. Raw OCR and source images remain intact.
Spelling review has no runtime download, remote lookup, account or device spell-check service.
Existing OCR model installation requirements are unchanged.

Most languages get conservative one-edit suggestions for words that are absent from the selected
list. Latin checks prefer known accent variants and neighbouring letter swaps. Chinese and Japanese
use known words to qualify a bounded set of visually similar character substitutions. These lists
cover common words and can miss names, inflections, compounds, and unusual terms. Review is scoped
to a page, skips tab- or pipe-separated rows, and keeps numbers and mixed identifiers untouched.
Suggestions are optional and may be imperfect.

## Word-list provenance

- English retains the existing CMUdict-derived list and packaged redistribution notice.
- French, German, Italian, Romanian, Spanish, Simplified Chinese, Japanese, Korean and Hindi are
  derivatives of the `small` frequency data from [Robyn Speer's wordfreq 3.1.1](https://pypi.org/project/wordfreq/3.1.1/).
  The lists retain single words in the target script, NFC-normalize, deduplicate and order them.
  Four common form/document terms are also explicitly included in Japanese. The data is CC BY-SA
  4.0. See the upstream source attributions in the packaged WORDFREQ-DATA-NOTICE.md.
  Pinned wheel SHA-256 `4b1c6ecffc6198be3396d5cf871c4423ca71c907c231348d352dd54d62b97473`.
- Traditional Chinese retains [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)
  traditional headwords whose simplified form occurs in the compact wordfreq list. Source
  snapshot 2026-11-24 20:43:35 UTC, CC BY-SA 4.0. Packaged CEDICT-SPELLING-NOTICE.txt retains the
  upstream header and source hash.
- Irish is the unchanged Irish Word Frequency List by Michal Boleslav Měchura. The data and its
  full Open Database License 1.0 text are packaged separately. RME only uses the lemma column
  while the dialog is open. [Pinned source](https://github.com/michmech/irish-word-frequency/tree/b40d3bcc79fad304961af808db860b03d5ef413a).
- Marathi and Nepali use compact derivatives of [wmkeyboard-data's corpus lists](https://github.com/wasi-master/wmkeyboard-data/blob/663e989ac1de7505a103b2ebdbdc7aa6f4c4d9b5/data/README.md).
  Original corpora and Tatoeba attribution are preserved in the packaged INDIC-SPELLING-NOTICE.md.
  The published lists and compact derivatives retain CC BY-SA 4.0.

The CC BY-SA terms apply to the separate data assets. [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/legalcode).
Source hashes below refer to the upstream compressed members, the unpacked CC-CEDICT text, or the
unchanged Irish frequency file. Bundled derivatives are alphabetical NFC UTF-8 with LF endings and
GZIP compression. Original token frequency and glosses are omitted.

| Language | Asset SHA-256 | Source SHA-256 |
| --- | --- | --- |
| de | `6d3d6881707dad6972faadd63efa353d49a724755dd774e13f8f3af894dd47f0` | `2115b5bb4adb671a3352555a480b9c2f5b03493e9f7e4047997361d62310017a` |
| es | `d918d7369483f9565769a2e98a89e7dc148af8beb8f3290f7d3c504adb91e128` | `ff5853040f65bcc9cb3ed3721d1d09d4405389c1741ebbf529612220829ff5af` |
| fr | `91794748779aa234355c527981feb5f44590e5770258a84f529c72372dbb88c8` | `8fbbf619ff2e6ff5b3d99d41e69c105daf5795771ce8ef36529f210d571abe6e` |
| hi | `4c09d702c38b0f132ff33306f1752f99256034d110cd72488708a7dc5410db37` | `92ccbf70297d28e97af55a2a517b95c3d8b599e4cc943fb6d4612a3d44a86992` |
| it | `21be831f11b42f5508491ce1b7fb0b915b9a7910b7111c6ab6383c356479f102` | `07a4355d735d9cc864ce9fe679d94a13dee4cefa2495b6b013ecdb214b231c66` |
| ja | `c05624a9400824195af236c64351542587b0881aa0884922684c9e81e8850102` | `cb86d1b139615d650573ee66ada5f6cb61ff0825557943de9db46f5f3f0e71f9` |
| ko | `ebc72816427f6f41da66de78da02a1dd2e600fbe3734d5d151a5e284e869591f` | `01014287a9e779d232f965d054a9cb5eb10b0bc46ee464fbd42935a5df7c8319` |
| ro | `f1ecf1a083340b0296f0decc4357fdecbb81de7705c4414cf03c730e78741d3f` | `c17fe82952ed209bb45b95c56cb5c7f077ca921a84578c3807d48ddc1dd842e2` |
| zh | `547ca0e50381b6adba661ac0b30a52168b0d47f1fb9f328253078c0a0158bfe3` | `441ce2e01370185606f0e3c5da47f64887981758b0f65ae511c0a5927b1ab359` |
| zh-Hant | `e0fc18d038ddac18db34ac6752cfeefe847af5d7dcc9f80b3aad9fab388a17c2` | `3a0e7ed8bf97c5cf58a039f9a431e052171a558d8d86d551e6c896d52a2cc706` |
| mr | `e844d114d10eeb50b8550ed11a2bbda522daa799e5b687675fbe047f8a625145` | `5cdafbbca554230a52a0f1012df81a6ffd2949e3009b30c7270e5e65d289164e` |
| ne | `b3ee8900f26788ed46f902f0a554bec2f78b601548e5671dbafe8d6c9aec3630` | `0c5536407ac9c21009469fba22dc1dd304e3100a763fd52e963664679fbc6856` |
| ga | `a40741bd75abfccdf6f017cb6630dec9d52d8bb61ecb512b9fcafb33148acfb0` | `a40741bd75abfccdf6f017cb6630dec9d52d8bb61ecb512b9fcafb33148acfb0` |
