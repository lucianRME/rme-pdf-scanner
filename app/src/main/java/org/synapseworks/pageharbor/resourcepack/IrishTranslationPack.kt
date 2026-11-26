package org.synapseworks.pageharbor.resourcepack

import java.io.File

internal object IrishTranslationPack {
    fun store(noBackupFilesDir: File): ModelPackStore = ModelPackStore(
        File(noBackupFilesDir, "binary-irish-pack-v1"),
        profile,
    )

    private val profile = ModelPackStore.Profile(
        id = "opus-irish-v1",
        metadata = "schema=1\nid=opus-irish-v1\ncompatibility=rme-marian-irish-v1\nversion=1\n",
        archiveLimit = 274587047L,
        archiveSha = "f8de4aaf0170fab68e22264a0cb968e36e833047f22c7176fa9dbe83634ee4a3",
        freeBefore = 1024L * 1024 * 1024,
        freeAfterStaging = 256L * 1024 * 1024,
        entries = listOf(
            ModelPackStore.Entry("MODEL-NOTICES.txt", 12130L, "9fd0b47e200a3fb424e459b72802d42b5137b59f1b6dadf7eb55bd9f5a4a8bb7"),
            ModelPackStore.Entry("en-ga-decoder_model_quantized.onnx", 84287889L, "e75b5d27eca7c16c15166469b5c2758d0329a2bd0aa6a03a9f10d4605ba3dbca"),
            ModelPackStore.Entry("en-ga-encoder_model_quantized.onnx", 48462597L, "47a373f30bbb6fddd2504081b6ed0236506c9ded1277d955cc9371b19e8ab135"),
            ModelPackStore.Entry("en-ga-source-to-model-ids.json", 185078L, "a28a829feb7145b578cf783195f6825f6e36755fbb84762cab55d21531edc701"),
            ModelPackStore.Entry("en-ga-target-decoder-data.json", 473516L, "93853e60e0c756e397527064c1bba3e4d181595a6237172d78dc1a279949dc98"),
            ModelPackStore.Entry("en-ga-tokenizer-source-adapter.json", 2408480L, "7bf1ecb1566ec3284dcddcbb735b7635fc309c4ba3eb209f6fe4f93734e98693"),
            ModelPackStore.Entry("en-ga-vocab.json", 1469246L, "f93c98c14b5d6358fcf4d16a699b1ebb6b1779f4fb1abba0b00757847b9deebe"),
            ModelPackStore.Entry("ga-en-decoder_model_quantized.onnx", 84287875L, "087212fe2498b463f1645886311e463de186f4db2614f69afcbd4b3a1634c54f"),
            ModelPackStore.Entry("ga-en-encoder_model_quantized.onnx", 48462598L, "dd71850d739cf36aab385362ae67ddd7445d7d891652f3e4b75b5e9783f8bce6"),
            ModelPackStore.Entry("ga-en-source-to-model-ids.json", 184324L, "5e35ba2222d6184aa6d3c72e966d455f9bb025d341094c25c66d3d0566707a45"),
            ModelPackStore.Entry("ga-en-target-decoder-data.json", 437802L, "de80696b8815b57c403a5878081c8117f14c399b6570216d2b19634b447e398e"),
            ModelPackStore.Entry("ga-en-tokenizer-source-adapter.json", 2444328L, "090e8ca8acacd030672aaf49608b9e34c71eb9c9c1e34a553e31adb12ba204e3"),
            ModelPackStore.Entry("ga-en-vocab.json", 1469246L, "f93c98c14b5d6358fcf4d16a699b1ebb6b1779f4fb1abba0b00757847b9deebe"),
        ),
    )
}
