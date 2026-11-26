package org.synapseworks.pageharbor.resourcepack

/** Availability only: never loads native code or treats a data pack as an engine. */
internal enum class ModelRuntimeAvailability(val guidance: String) {
    AVAILABLE(""),
    UNSUPPORTED_DEVICE("Offline English → Romanian translation requires Android 15 or later on an ARM64 device. Use a supported device; other document tools remain available."),
    MISSING_RUNTIME("This app does not include offline English → Romanian translation. Use an app edition that includes it; importing a model pack alone cannot enable translation."),
}

internal fun modelRuntimeAvailability(
    sdk: Int,
    is64Bit: Boolean,
    first64BitAbi: String?,
    runtimePresent: () -> Boolean,
): ModelRuntimeAvailability = when {
    sdk < 35 || !is64Bit || first64BitAbi != "arm64-v8a" -> ModelRuntimeAvailability.UNSUPPORTED_DEVICE
    !runtimePresent() -> ModelRuntimeAvailability.MISSING_RUNTIME
    else -> ModelRuntimeAvailability.AVAILABLE
}
