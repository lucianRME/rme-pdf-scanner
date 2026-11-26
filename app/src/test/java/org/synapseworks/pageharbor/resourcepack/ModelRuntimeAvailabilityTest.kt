package org.synapseworks.pageharbor.resourcepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelRuntimeAvailabilityTest {
    @Test fun unsupportedDevicesNeverProbeTheRuntime() {
        for (profile in listOf(Triple(34, true, "arm64-v8a"), Triple(35, false, "arm64-v8a"), Triple(35, true, "x86_64"), Triple(35, true, null))) {
            assertEquals(ModelRuntimeAvailability.UNSUPPORTED_DEVICE,
                modelRuntimeAvailability(profile.first, profile.second, profile.third) { error("Must not probe") })
        }
    }

    @Test fun missingRuntimeIsNotResolvedByImportAdvice() {
        val result = modelRuntimeAvailability(35, true, "arm64-v8a") { false }
        assertEquals(ModelRuntimeAvailability.MISSING_RUNTIME, result)
        assertFalse(result.guidance.contains("import the exact", ignoreCase = true))
    }

    @Test fun supportedRuntimeCanProceedToIndependentPackValidation() {
        assertEquals(ModelRuntimeAvailability.AVAILABLE, modelRuntimeAvailability(35, true, "arm64-v8a") { true })
        assertEquals(ModelRuntimeAvailability.AVAILABLE, modelRuntimeAvailability(36, true, "arm64-v8a") { true })
    }
}
