# Offline translation packs

The translation-enabled RME build can import a 614 MiB multilingual model ZIP from
**More → Resource packs → Import multilingual ZIP**. Copy the ZIP to local Downloads before
opening the picker. Android 15 or later and a 64-bit ARM process are required. Leave at least
1.75 GiB free during import. The archive and extracted data are verified against pinned hashes.
Installed model data is private and excluded from backups.

Open a document, recognize its text and choose **More → Translate text**. Select a direction
and review the resulting draft. Text recognition uses the selected OCR script. Optional OCR
models may require their own setup before a scanned document can be translated offline.

The multilingual pack supports English in both directions with French, German, Italian,
Spanish, Japanese, Korean, Chinese, Hindi, Marathi and Nepali. The existing English to Romanian
pack remains separate. TXT supports the translated scripts. The searchable reflowed PDF writer
still has its existing Latin-oriented character limits.

Machine translations require review. In the synthetic smoke sample, Nepali to English produced
a fragment for a full sentence. These checks establish local execution and script coverage.
They provide limited evidence about translation quality on real documents.

## English and Irish (Gaeilge)

The dedicated 262 MiB Irish ZIP adds **English ↔ Irish**. Choose **Import Irish ZIP** in
**Resource packs** and leave 1 GiB free during import. The ZIP contains pinned quantized
Helsinki-NLP models for both directions and their Apache-2.0 notice.
[English → Irish base model](https://huggingface.co/Helsinki-NLP/opus-mt-en-ga) · [Irish → English base model](https://huggingface.co/Helsinki-NLP/opus-mt-ga-en)

The Irish ZIP has SHA-256
`f8de4aaf0170fab68e22264a0cb968e36e833047f22c7176fa9dbe83634ee4a3`.

## Reproducible multilingual ZIP

Use Python 3.12 with `sentencepiece==0.2.1` and `tokenizers==0.21.4`. On a connected machine,
obtain the pinned inputs, then put them in a local directory. The pack builder uses local files
and verifies each input and the final ZIP.

- From the pinned Xenova conversion, obtain `tokenizer.json`, `sentencepiece.bpe.model`,
  `onnx/encoder_model_quantized.onnx` and `onnx/decoder_model_quantized.onnx`. Keep the two
  ONNX files in the same directory as the other inputs. [Pinned conversion](https://huggingface.co/Xenova/m2m100_418M/tree/9c374f0b7aca709787cea97b047bfbbd1559d177)
- Save the pinned publisher license as `fairseq-MIT-LICENSE.txt`. The base model is published
  under MIT and the RME ZIP carries the model and conversion attribution.
  [Base model](https://huggingface.co/facebook/m2m100_418M/blob/55c2e61bbf05dfb8d7abccdc3fae6fc8512fd636/README.md) · [Publisher license](https://github.com/facebookresearch/fairseq/blob/3d262bb25690e4eb2e7d3c1309b1e9c406ca4b99/LICENSE)

```sh
python tools/m37/build_multilingual_pack.py ./inputs ./rme-multilingual-v1.zip
```

Expected ZIP length `643915373` bytes and SHA-256
`d6768346c72f7d2ad9402257b6379fe0639f0a4e9d5f60ce79c6ff3cb4fe1b6f`.

Use the existing sealed `rmeModelRuntimeRoot` build input to produce a translation-enabled APK.
The language weights are delivered separately from the app. RME can import and translate with
the device network off.
