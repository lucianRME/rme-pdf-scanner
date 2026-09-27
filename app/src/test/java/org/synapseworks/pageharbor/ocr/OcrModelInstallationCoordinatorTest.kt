package org.synapseworks.pageharbor.ocr

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OcrModelInstallationCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun latinIsAlwaysBundledAndNeverTouchesOptionalBackend() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)

        assertEquals(OcrModelState.Bundled, manager.stateFor(OcrScript.LATIN))
        assertEquals(OcrModelState.Bundled, manager.requestInstall(OcrScript.LATIN))
        assertEquals(0, backend.availabilityCalls.get())
        assertEquals(0, backend.installCalls.get())
    }

    @Test
    fun availabilityMapsInstalledMissingUnsupportedAndRetryableFailure() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)

        backend.availability = OptionalOcrModelAvailability.Installed
        assertEquals(OcrModelState.Installed, manager.stateFor(OcrScript.CHINESE))
        backend.availability = OptionalOcrModelAvailability.NotInstalled
        assertEquals(OcrModelState.NotInstalled, manager.stateFor(OcrScript.JAPANESE))
        backend.availability = OptionalOcrModelAvailability.Unsupported
        assertEquals(OcrModelState.Unsupported, manager.stateFor(OcrScript.KOREAN))
        backend.availability = OptionalOcrModelAvailability.Failed(
            OcrModelFailure.AVAILABILITY_CHECK_FAILED,
            retryable = true,
        )
        assertEquals(
            OcrModelState.RetryableFailure(OcrModelFailure.AVAILABILITY_CHECK_FAILED),
            manager.stateFor(OcrScript.DEVANAGARI),
        )
    }

    @Test
    fun installPublishesOnlyBackendProvidedProgressAndCompletesInstalled() = runBlocking {
        val backend = FakeBackend().apply {
            installAction = { onState ->
                onState(OcrModelState.Pending)
                onState(OcrModelState.Downloading(25L, 100L))
                onState(OcrModelState.Installing)
                OptionalOcrModelInstallResult.Installed
            }
        }
        val manager = manager(backend)

        assertEquals(OcrModelState.Installed, manager.requestInstall(OcrScript.CHINESE))
        assertEquals(OcrModelState.Installed, manager.states.value[OcrScript.CHINESE])
    }

    @Test
    fun concurrentDuplicateRequestsShareOneBackendInstall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply {
            installAction = {
                entered.complete(Unit)
                release.await()
                OptionalOcrModelInstallResult.Installed
            }
        }
        val manager = manager(backend)

        val first = async { manager.requestInstall(OcrScript.JAPANESE) }
        entered.await()
        val second = async { manager.requestInstall(OcrScript.JAPANESE) }
        assertEquals(OcrModelState.Installing, manager.states.value[OcrScript.JAPANESE])
        release.complete(Unit)

        assertSame(OcrModelState.Installed, first.await())
        assertSame(OcrModelState.Installed, second.await())
        assertEquals(1, backend.installCalls.get())
    }

    @Test
    fun canceledWaiterDoesNotCancelSharedInstall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = FakeBackend().apply {
            installAction = {
                entered.complete(Unit)
                release.await()
                OptionalOcrModelInstallResult.Installed
            }
        }
        val manager = manager(backend)

        val first = async { manager.requestInstall(OcrScript.KOREAN) }
        entered.await()
        first.cancel()
        val surviving = async { manager.requestInstall(OcrScript.KOREAN) }
        release.complete(Unit)

        assertEquals(OcrModelState.Installed, surviving.await())
        assertEquals(1, backend.installCalls.get())
    }

    @Test
    fun retryStartsFreshRequestAfterRetryableFailure() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)
        backend.installAction = {
            OptionalOcrModelInstallResult.Failed(
                OcrModelFailure.INSTALLATION_FAILED,
                retryable = true,
            )
        }

        assertEquals(
            OcrModelState.RetryableFailure(OcrModelFailure.INSTALLATION_FAILED),
            manager.requestInstall(OcrScript.DEVANAGARI),
        )
        backend.installAction = { OptionalOcrModelInstallResult.Installed }
        assertEquals(OcrModelState.Installed, manager.requestInstall(OcrScript.DEVANAGARI))
        assertEquals(2, backend.installCalls.get())
    }

    @Test
    fun terminalFailureCancellationAndUnsupportedRemainDistinct() = runBlocking {
        val backend = FakeBackend()
        val manager = manager(backend)

        backend.installAction = {
            OptionalOcrModelInstallResult.Failed(
                OcrModelFailure.INSTALLATION_FAILED,
                retryable = false,
            )
        }
        assertEquals(
            OcrModelState.Failed(OcrModelFailure.INSTALLATION_FAILED),
            manager.requestInstall(OcrScript.CHINESE),
        )
        backend.installAction = { OptionalOcrModelInstallResult.Canceled }
        assertEquals(OcrModelState.Canceled, manager.requestInstall(OcrScript.JAPANESE))
        backend.installAction = { OptionalOcrModelInstallResult.Unsupported }
        assertEquals(OcrModelState.Unsupported, manager.requestInstall(OcrScript.KOREAN))
        assertFalse(manager.states.value.containsValue(OcrModelState.Downloading()))
    }

    private fun manager(backend: OptionalOcrModelBackend) =
        CoalescingOcrModelInstaller(backend, scope)

    private class FakeBackend : OptionalOcrModelBackend {
        val availabilityCalls = AtomicInteger()
        val installCalls = AtomicInteger()
        var availability: OptionalOcrModelAvailability =
            OptionalOcrModelAvailability.NotInstalled
        var installAction: suspend ((OcrModelState) -> Unit) -> OptionalOcrModelInstallResult = {
            OptionalOcrModelInstallResult.Installed
        }

        override suspend fun availability(script: OcrScript): OptionalOcrModelAvailability {
            availabilityCalls.incrementAndGet()
            return availability
        }

        override suspend fun install(
            script: OcrScript,
            onState: (OcrModelState) -> Unit,
        ): OptionalOcrModelInstallResult {
            installCalls.incrementAndGet()
            return installAction(onState)
        }
    }
}
