package org.synapseworks.pageharbor.security

import android.app.Activity
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.synapseworks.pageharbor.MainActivity
import org.synapseworks.pageharbor.migration.workflow.MigrationOperationStatus
import org.synapseworks.pageharbor.migration.workflow.MigrationPickerRequest
import org.synapseworks.pageharbor.migration.workflow.MigrationPickerRequestId
import org.synapseworks.pageharbor.migration.workflow.MigrationSourceApp
import org.synapseworks.pageharbor.migration.workflow.MigrationSourceRoute
import org.synapseworks.pageharbor.migration.workflow.MigrationWorkflowViewModel
import org.synapseworks.pageharbor.portability.workflow.PortabilityOperationStatus
import org.synapseworks.pageharbor.portability.workflow.PortabilityPickerRequest
import org.synapseworks.pageharbor.portability.workflow.PortabilityPickerRequestId
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowKind
import org.synapseworks.pageharbor.portability.workflow.PortabilityWorkflowViewModel

class ProtectedActionQueueInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val stateStore = SharedPreferencesAppLockStateStore(context)
    private val fixtureRoot = File(context.cacheDir, "shared-pdfs")
    private val fixture = File(fixtureRoot, "$SENSITIVE_VALUE_SENTINEL.pdf")

    private lateinit var originalPersistentState: AppLockPersistentState

    @Before
    fun installIsolatedLockedState() {
        originalPersistentState = stateStore.read()
        stateStore.write(
            AppLockPersistentState(
                config = AppLockConfig(
                    enabled = true,
                    autoLockTimeout = AutoLockTimeout.FIVE_MINUTES,
                ),
            ),
        )
        fixtureRoot.mkdirs()
        fixture.delete()
    }

    @After
    fun restorePersistentState() {
        fixture.delete()
        stateStore.write(originalPersistentState)
    }

    @Test
    fun inboundShareWaitsWhileLockedSurvivesRecreationAndDispatchesOnlyAfterUnlock() {
        createOnePagePdf(fixture)
        val sharedUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fixture,
        )
        val shareIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, sharedUri)
        }
        val logToken = System.nanoTime().toString()
        val logStart = "RME_PROTECTED_QUEUE_START_$logToken"
        val logEnd = "RME_PROTECTED_QUEUE_END_$logToken"
        Log.i(LOG_TAG, logStart)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var retainedQueue: ProtectedActionQueueViewModel
            lateinit var scenarioLaunchIntent: Intent
            scenario.onActivity { activity ->
                retainedQueue = protectedQueue(activity)
                scenarioLaunchIntent = Intent(activity.intent)
                assertEquals(AppLockPhase.LOCKED, appLock(activity).state.value.phase)
                assertTrue(migration(activity).state.value.operation is MigrationOperationStatus.Idle)
                deliverNewIntent(activity, shareIntent)
                assertEquals(Intent.ACTION_SEND, activity.intent.action)
                // ActivityScenario identifies the instance by its launch Intent. The production
                // queue owns its own defensive Intent copy, so restore only the Activity's test
                // harness Intent before recreation without touching the queued payload.
                activity.intent = Intent(scenarioLaunchIntent)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertSame(retainedQueue, protectedQueue(activity))
                assertEquals(AppLockPhase.LOCKED, appLock(activity).state.value.phase)
                assertTrue(migration(activity).state.value.operation is MigrationOperationStatus.Idle)
                unlock(activity)
            }

            awaitScenario(scenario, "inbound share was not dispatched after unlock") { activity ->
                migration(activity).state.value.operation is MigrationOperationStatus.PreviewReady
            }
            scenario.onActivity { activity ->
                val preview = (
                    migration(activity).state.value.operation as MigrationOperationStatus.PreviewReady
                    ).preview
                assertEquals(MigrationSourceRoute.ANDROID_SHARE, preview.source.route)
                assertEquals(1, preview.requestedSourceCount)
                assertEquals(1, preview.documentCount)
                assertFalse(activity.intent.action == Intent.ACTION_SEND)
                // handleInboundIntentUnlocked deliberately consumes the external Intent. Restore
                // ActivityScenario's original identity so close() can observe final destruction.
                activity.intent = Intent(scenarioLaunchIntent)
            }
        }

        instrumentation.waitForIdleSync()
        Log.i(LOG_TAG, logEnd)
        SystemClock.sleep(LOG_FLUSH_MILLIS)
        val appLog = appProcessLog()
        val startIndex = appLog.lastIndexOf(logStart)
        val endIndex = appLog.indexOf(logEnd, startIndex.coerceAtLeast(0))
        assertTrue("PID-scoped log marker was not captured", startIndex >= 0 && endIndex > startIndex)
        val protectedWindow = appLog.substring(startIndex, endIndex)
        assertFalse(
            "Queued provider references must not be written to app logs",
            protectedWindow.contains(SENSITIVE_VALUE_SENTINEL),
        )
    }

    @Test
    fun activityResultPayloadsWaitAcrossRecreationAndDispatchWithExactRequestIds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var retainedQueue: ProtectedActionQueueViewModel
            lateinit var expectedMigrationRequest: MigrationPickerRequest.OpenMultipleDocuments
            lateinit var expectedPortabilityRequest: PortabilityPickerRequest.OpenRestoreDocument

            scenario.onActivity { activity ->
                assertEquals(AppLockPhase.LOCKED, appLock(activity).state.value.phase)
                retainedQueue = protectedQueue(activity)

                val migration = migration(activity)
                assertTrue(migration.selectSource(MigrationSourceApp.OTHER))
                assertTrue(migration.requestMultipleFiles())
                val migrationStatus =
                    migration.state.value.operation as MigrationOperationStatus.AwaitingMultipleFiles
                expectedMigrationRequest = MigrationPickerRequest.OpenMultipleDocuments(
                    migrationStatus.requestId,
                )

                val portability = portability(activity)
                assertTrue(portability.requestRestoreSource())
                val portabilityStatus =
                    portability.state.value.operation as PortabilityOperationStatus.AwaitingRestoreSource
                expectedPortabilityRequest = PortabilityPickerRequest.OpenRestoreDocument(
                    portabilityStatus.requestId,
                )
            }

            val launchActions = awaitPickerLaunchActions(scenario)
            val queuedMigrationRequest = launchActions
                .filterIsInstance<PendingProtectedAction.LaunchMigrationPicker>()
                .single()
                .request
            val queuedPortabilityRequest = launchActions
                .filterIsInstance<PendingProtectedAction.LaunchPortabilityPicker>()
                .single()
                .request
            assertEquals(expectedMigrationRequest, queuedMigrationRequest)
            assertEquals(expectedPortabilityRequest, queuedPortabilityRequest)

            scenario.onActivity { activity ->
                runWhenUnlocked(
                    activity,
                    PendingProtectedAction.MigrationPickerResult(
                        request = queuedMigrationRequest,
                        resultCode = Activity.RESULT_CANCELED,
                        data = Intent().putExtra("synthetic_result", "retained"),
                    ),
                )
                runWhenUnlocked(
                    activity,
                    PendingProtectedAction.PortabilityPickerResult(
                        request = queuedPortabilityRequest,
                        uri = null,
                    ),
                )
                assertTrue(
                    migration(activity).state.value.operation is
                        MigrationOperationStatus.AwaitingMultipleFiles,
                )
                assertTrue(
                    portability(activity).state.value.operation is
                        PortabilityOperationStatus.AwaitingRestoreSource,
                )
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertSame(retainedQueue, protectedQueue(activity))
                val migrationStatus =
                    migration(activity).state.value.operation as
                        MigrationOperationStatus.AwaitingMultipleFiles
                val portabilityStatus =
                    portability(activity).state.value.operation as
                        PortabilityOperationStatus.AwaitingRestoreSource
                assertEquals(expectedMigrationRequest.requestId, migrationStatus.requestId)
                assertEquals(expectedPortabilityRequest.requestId, portabilityStatus.requestId)
                assertEquals(AppLockPhase.LOCKED, appLock(activity).state.value.phase)
                unlock(activity)
            }

            awaitScenario(scenario, "activity results were not dispatched after unlock") { activity ->
                migration(activity).state.value.operation is MigrationOperationStatus.Cancelled &&
                    portability(activity).state.value.operation is PortabilityOperationStatus.Cancelled
            }
            scenario.onActivity { activity ->
                val migrationStatus =
                    migration(activity).state.value.operation as MigrationOperationStatus.Cancelled
                val portabilityStatus =
                    portability(activity).state.value.operation as PortabilityOperationStatus.Cancelled
                assertEquals(MigrationSourceRoute.MULTIPLE_FILES, migrationStatus.source.route)
                assertEquals(PortabilityWorkflowKind.RESTORE, portabilityStatus.workflow)
            }
        }
    }

    @Test
    fun launchedPickerRequestIdsRemainExactAcrossActivityRecreation() {
        val portabilityRequest = PortabilityPickerRequest.OpenRestoreDocument(
            PortabilityPickerRequestId(8_901L),
        )
        val migrationRequest = MigrationPickerRequest.OpenDocumentTree(
            MigrationPickerRequestId(9_701L),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var retainedQueue: ProtectedActionQueueViewModel
            scenario.onActivity { activity ->
                retainedQueue = protectedQueue(activity)
                retainedQueue.markPortabilityPickerLaunched(portabilityRequest)
                retainedQueue.markMigrationPickerLaunched(migrationRequest)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val recreatedQueue = protectedQueue(activity)
                assertSame(retainedQueue, recreatedQueue)
                assertEquals(portabilityRequest, recreatedQueue.consumePortabilityPickerRequest())
                assertEquals(migrationRequest, recreatedQueue.consumeMigrationPickerRequest())
                assertNull(recreatedQueue.consumePortabilityPickerRequest())
                assertNull(recreatedQueue.consumeMigrationPickerRequest())
            }
        }
    }

    private fun awaitPickerLaunchActions(
        scenario: ActivityScenario<MainActivity>,
    ): List<PendingProtectedAction> {
        val launches = mutableListOf<PendingProtectedAction>()
        awaitScenario(scenario, "typed picker launch requests were not queued") { activity ->
            while (true) {
                when (val action = protectedQueue(activity).poll() ?: break) {
                    is PendingProtectedAction.LaunchMigrationPicker,
                    is PendingProtectedAction.LaunchPortabilityPicker,
                    -> launches += action
                    is PendingProtectedAction.InboundIntent -> Unit
                    else -> throw AssertionError("Unexpected protected action: ${action::class.java.simpleName}")
                }
            }
            launches.filterIsInstance<PendingProtectedAction.LaunchMigrationPicker>().size == 1 &&
                launches.filterIsInstance<PendingProtectedAction.LaunchPortabilityPicker>().size == 1
        }
        assertEquals(2, launches.size)
        return launches
    }

    private fun runWhenUnlocked(
        activity: MainActivity,
        action: PendingProtectedAction,
    ) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "runWhenUnlocked",
            AppLockProtectedEntryPoint::class.java,
            PendingProtectedAction::class.java,
        )
        method.isAccessible = true
        method.invoke(activity, AppLockProtectedEntryPoint.ACTIVITY_RESULT_CALLBACK, action)
    }

    private fun deliverNewIntent(activity: MainActivity, intent: Intent) {
        val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        method.isAccessible = true
        method.invoke(activity, intent)
    }

    private fun unlock(activity: MainActivity) {
        val attempt = checkNotNull(
            appLock(activity).beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE),
        )
        assertTrue(
            appLock(activity).onAuthenticationResult(
                attempt.id,
                AppLockAuthenticationResult.Success,
            ),
        )
    }

    private fun awaitScenario(
        scenario: ActivityScenario<MainActivity>,
        failureMessage: String,
        condition: (MainActivity) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + CONDITION_TIMEOUT_MILLIS
        do {
            var satisfied = false
            scenario.onActivity { activity -> satisfied = condition(activity) }
            if (satisfied) return
            SystemClock.sleep(CONDITION_POLL_MILLIS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(failureMessage)
    }

    private fun appLock(activity: MainActivity): AppLockViewModel =
        ViewModelProvider(activity)[AppLockViewModel::class.java]

    private fun protectedQueue(activity: MainActivity): ProtectedActionQueueViewModel =
        ViewModelProvider(activity)[ProtectedActionQueueViewModel::class.java]

    private fun migration(activity: MainActivity): MigrationWorkflowViewModel =
        ViewModelProvider(activity)[MigrationWorkflowViewModel::class.java]

    private fun portability(activity: MainActivity): PortabilityWorkflowViewModel =
        ViewModelProvider(activity)[PortabilityWorkflowViewModel::class.java]

    private fun createOnePagePdf(destination: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, 1).create())
            document.finishPage(page)
            destination.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        assertTrue(destination.isFile)
        assertTrue(destination.length() > 0L)
    }

    private fun appProcessLog(): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "logcat --pid=${Process.myPid()} -d -v raw",
        )
        assertNotNull(descriptor)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { reader -> reader.readText() }
    }

    private companion object {
        const val SENSITIVE_VALUE_SENTINEL = "DO_NOT_LOG_PROTECTED_PAYLOAD_7C19"
        const val LOG_TAG = "RmeProtectedQueueTest"
        const val LOG_FLUSH_MILLIS = 200L
        const val CONDITION_TIMEOUT_MILLIS = 15_000L
        const val CONDITION_POLL_MILLIS = 50L
    }
}
