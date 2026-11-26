package org.synapseworks.pageharbor.resourcepack

import java.io.File

internal object MultilingualPack {
    private const val id = "m2m100-multilingual-v1"

    fun store(noBackupFilesDir: File): ModelPackStore = ModelPackStore(
        File(noBackupFilesDir, "binary-multilingual-packs-v1"),
        profile,
    )

    private val profile = ModelPackStore.Profile(
        id = id,
        metadata = "schema=1\nid=$id\ncompatibility=rme-m2m100-v1\nversion=1\n",
        archiveLimit = 643915373L,
        archiveSha = "d6768346c72f7d2ad9402257b6379fe0639f0a4e9d5f60ce79c6ff3cb4fe1b6f",
        freeBefore = 1792L * 1024 * 1024,
        freeAfterStaging = 256L * 1024 * 1024,
        entries = listOf(
            ModelPackStore.Entry("MODEL-NOTICES.txt", 1585L, "8cc24c7dba8bc961c68394338036e64055656712ba5f3bc028a786ed9c87b1dc"),
            ModelPackStore.Entry("decoder_model_quantized.onnx", 339181945L, "6015e31c8976659aedb06058c4dadf0f400d087a3f9830f838e68f220d79bcb6"),
            ModelPackStore.Entry("encoder_model_quantized.onnx", 287856370L, "13a94e354a9140764eb81102d77d3ec6952d796e6f113c651eeb3c3443da0386"),
            ModelPackStore.Entry("model-vocabulary.json", 1416330L, "ae25b05b00f406aaac523c6fb4cb1398510af453a58e9df0d51f6be92650146f"),
            ModelPackStore.Entry("source-to-model-ids.json", 782949L, "2811f9f452fa28c2e9653d18780f564864d1d7e48ae160ed159efe450a3b9095"),
            ModelPackStore.Entry("tokenizer-source.json", 14675257L, "f32be03d088da86ac2f3c896d62bfbc830253227f54b956d5850869e7d4dbeda"),
        ),
    )
}
