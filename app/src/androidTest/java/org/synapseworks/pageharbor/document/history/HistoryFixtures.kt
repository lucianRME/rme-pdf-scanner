package org.synapseworks.pageharbor.document.history

import android.content.Context
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.PageHarborSessionViewModel
import org.synapseworks.pageharbor.document.session.*
import org.synapseworks.pageharbor.document.curvature.CurvatureFixtures

/** Real navigation through fresh geometry, then the exact supplied result for downstream workflows. */
internal object HistoryFixtures {
    class Restored(val session:DocumentSession,private val vm:PageHarborSessionViewModel) {
        suspend fun close(){withContext(Dispatchers.Main.immediate){vm.clearScan()};vm.awaitHistoryIdle();vm.disposeHistoryForTest()}
    }
    suspend fun restored(context:Context,prepared:CurvatureFixtures.Prepared):Restored {
        val vm=PageHarborSessionViewModel()
        withContext(Dispatchers.Main.immediate){vm.installDocumentSessionForTest(prepared.before);vm.configureHistory(AndroidHistorySourceAccess(context))}
        vm.awaitHistoryIdle()
        withContext(Dispatchers.Main.immediate){assertTrue(vm.applyCurvature(prepared.before,vm.documentRevision,prepared.replacement))};vm.awaitHistoryIdle()
        val result=vm.documentSession
        repeat(2){withContext(Dispatchers.Main.immediate){assertTrue(vm.undoDocument())};vm.awaitHistoryIdle();assertSame(prepared.before,vm.documentSession)
            withContext(Dispatchers.Main.immediate){assertTrue(vm.redoDocument())};vm.awaitHistoryIdle();assertSame(result,vm.documentSession)}
        // Caller owns the prepared workspace and closes it after its export/persistence assertions.
        return Restored(result,vm)
    }
}
