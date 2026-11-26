# Release shrinking is enabled. AndroidX, ML Kit, and PdfBox ship their own consumer rules.
# Keep this file narrow: do not disable optimization or add broad keep rules without a verified
# runtime need, because that would undermine reproducible release-size checks.

# PdfBox references an optional JPEG-2000 codec that is not packaged by RME PDF Scanner. Scanned JPEG
# pages do not use this codec; suppress only the absent optional types so R8 can complete.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# AGP 9 strict keep semantics no longer infer constructors from Firebase Components' class-only
# consumer rule. ML Kit discovers exactly these three manifest registrars by reflection.
# Keep only their public no-argument constructors; retain full optimization elsewhere.
-keepclassmembers class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keep,allowoptimization class org.libreoffice.kit.LibreOfficeKit { *; }
-keep,allowoptimization class org.synapseworks.m35.workerprobe.Engine { *; }

-keepclassmembers class com.google.mlkit.vision.text.internal.TextRegistrar {
    public <init>();
}

# Optional local model JNI entry points and reflection-only public sample adapter.
-keep class ai.djl.huggingface.tokenizers.jni.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.synapseworks.pageharbor.resourcepack.KnownModelRuntime { public *; }
-keep class org.synapseworks.pageharbor.resourcepack.MultilingualModelRuntime { public *; }

-keep class org.synapseworks.pageharbor.validation.ModelNativeChecks { *; }

-keep class org.synapseworks.pageharbor.validation.TranslationCorpusChecks { *; }

-keep class org.synapseworks.pageharbor.validation.TranslationWorkflowChecks { *; }

-keep class org.synapseworks.pageharbor.validation.TranslationControlProvider { *; }

-keep class org.synapseworks.pageharbor.validation.TranslationPdfChecks { *; }

-keep class org.synapseworks.pageharbor.validation.AssistanceChecks { *; }

# M40 private BC1.72 provider discovers these three mappings and implementations
# by class-name strings. Frozen RSA/SHA-256 signatures fail in R8 without them.
# Preserve only the qualified digest/RSA/X.509 families, not every BC algorithm.
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }
# These mappings derive service prefixes from the enclosing class.getName().
# Keeping only $Mappings/$Digest leaves a renamed prefix and ClassNotFoundException.
-keep class org.bouncycastle.jcajce.provider.digest.SHA256 { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509 { *; }
-keep class org.synapseworks.pageharbor.resourcepack.PlayTranslationPackDelivery { public <init>(android.app.Activity); }
