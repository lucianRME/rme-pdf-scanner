package org.synapseworks.pageharbor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageHarborBuildInfoTest {
    @Test
    fun applicationIdMatchesConfiguredValue() {
        assertEquals("org.synapseworks.pageharbor", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun versionMetadataMatchesConfiguredValue() {
        assertEquals("1.5.0", BuildConfig.VERSION_NAME)
        assertEquals(16, BuildConfig.VERSION_CODE)
    }

    @Test
    fun gitRevisionMetadataIsPresentAndDoesNotExposeLocalPath() {
        assertFalse(BuildConfig.GIT_REVISION.isBlank())
        assertFalse(BuildConfig.GIT_REVISION.contains("/"))
        assertFalse(BuildConfig.GIT_REVISION.contains("\\"))
    }

    @Test
    fun productionBuildIdentityDoesNotExposeBuildDetails() {
        if (BuildConfig.SHOW_BUILD_DETAILS) {
            assertFalse(BuildConfig.BUILD_TYPE_LABEL == "release")
        } else {
            assertEquals("release", BuildConfig.BUILD_TYPE_LABEL)
            assertEquals("unknown", BuildConfig.GIT_REVISION)
        }
    }

    @Test
    fun buildConfigHasNoRuntimeTestGateSwitch() {
        assertFalse(
            BuildConfig::class.java.declaredFields.any { field ->
                field.name.contains("gate", ignoreCase = true) ||
                    field.name.contains("testControl", ignoreCase = true)
            },
        )
    }
}
