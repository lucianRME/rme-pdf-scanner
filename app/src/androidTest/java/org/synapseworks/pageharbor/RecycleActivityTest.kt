package org.synapseworks.pageharbor

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.synapseworks.pageharbor.library.*
import org.synapseworks.pageharbor.security.*

class RecycleActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val dao get()=LibraryDatabase.get(app).libraryDao()
    private val vm get()=compose.activity.recycle
    private val ids=mutableListOf<String>()
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));compose.runOnIdle{unlock();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan()}}
    @After fun cleanup(){compose.runOnIdle{unlock();vm.close()};runBlocking{for(id in ids){val r=dao.documentAnyState(id);if(r?.libraryState=="TRASHED")dao.purgeTrashedDocument(id,r.trashRevision,1)else dao.deleteDocument(id)}}}
    private fun seed(trash:Boolean=true):String=runBlocking{
        val id="recycle-ui-${UUID.randomUUID()}";ids+=id
        val d=LibraryDocumentEntity(documentId=id,title="Synthetic recovery sample",createdAtMillis=2,modifiedAtMillis=Long.MAX_VALUE-100,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED",isFavorite=true)
        val p=LibraryPageEntity(pageId="$id-page",documentId=id,position=0,relativePath="synthetic",contentType="image/jpeg",sourceCategory="SCAN",width=10,height=10,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",ocrText=null,ocrError=null)
        dao.replaceDocument(d,listOf(p),"");if(trash)assertTrue(LibraryRepository(app).deleteDocument(id) is LibraryResult.Success);id
    }
    private fun ready(){compose.waitUntil(30_000){!vm.state.value.loading&&!vm.state.value.working};assertNull(vm.state.value.error)}
    private fun click(tag:String){val list=if(compose.onAllNodesWithTag("recycle-confirm-list").fetchSemanticsNodes().isNotEmpty())"recycle-confirm-list"else"recycle-list";compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun open(){compose.onNodeWithTag("open-recycle").assertIsDisplayed().performClick();compose.waitUntil{vm.state.value.open};ready()}
    private fun capture(name:String,tag:String){File(app.cacheDir,name).outputStream().use{compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun reachableRestorePurgeConfirmationAndEmptyControls(){
        val id=seed(trash=false)
        val menu=app.getString(R.string.library_more_actions,"Synthetic recovery sample")
        compose.waitUntil(30_000){runBlocking{dao.document(id)!=null}}
        // In short windows the recent-document item is not composed until its lazy grid scrolls.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(menu))
        compose.onNodeWithContentDescription(menu).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.library_delete_action),useUnmergedTree=true).performClick()
        compose.onNodeWithTag("recycle-move-list").performScrollToNode(hasTestTag("recycle-move-confirm"))
        compose.onNodeWithTag("recycle-move-confirm").assertIsDisplayed().performClick()
        compose.waitUntil(30_000){runBlocking{dao.recycleDocument(id)?.libraryState=="TRASHED"}}
        open();click("recycle-purge-$id");assertNotNull(vm.state.value.confirmation)
        click("recycle-cancel");assertNotNull(runBlocking{dao.recycleDocument(id)})
        click("recycle-restore-$id");ready();compose.waitUntil{runBlocking{dao.document(id)!=null}}
        assertTrue(runBlocking{dao.document(id)!!.isFavorite})
        runBlocking{LibraryRepository(app).deleteDocument(id)};compose.waitUntil{vm.state.value.page.items.any{it.documentId==id}}
        click("recycle-empty-bin");ready();assertTrue(vm.state.value.confirmation!!.count>=1)
        click("recycle-cancel");click("recycle-purge-$id")
        capture("m18-recycle-confirm.png","recycle-confirm-list")
        click("recycle-confirm");ready();compose.waitUntil{runBlocking{dao.documentAnyState(id)==null}}
        assertTrue(runBlocking{dao.isDeletedArchiveIdentity(id)})
        compose.waitUntil{vm.state.value.page.items.none{it.documentId==id}}
        // Empty-bin confirmation captures an episode watermark; a later trash must survive it.
        val selected=seed();compose.waitUntil{vm.state.value.page.items.any{it.documentId==selected}}
        click("recycle-empty-bin");ready();val later=seed()
        click("recycle-confirm");ready();assertNull(runBlocking{dao.documentAnyState(selected)})
        assertEquals("TRASHED",runBlocking{dao.recycleDocument(later)!!.libraryState})
        compose.waitUntil{vm.state.value.page.items.any{it.documentId==later}}
        click("recycle-purge-$later");click("recycle-confirm");ready()
        compose.waitUntil{vm.state.value.page.count==0L};compose.onNodeWithTag("recycle-list").performScrollToNode(hasTestTag("recycle-empty"));compose.onNodeWithTag("recycle-empty").assertIsDisplayed()
        capture("m18-recycle-review.png","recycle-screen");click("recycle-retry");ready();click("recycle-close")
        assertFalse(vm.state.value.open)
    }
    @Test fun recreationBackgroundLockAndLateCallbacksAreSafe(){
        val id=seed();open();compose.runOnIdle{repeat(3){vm.open()}};ready();click("recycle-purge-$id");compose.activityRule.scenario.recreate();assertEquals(id,vm.state.value.confirmation!!.id)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertFalse(vm.state.value.open);assertNull(vm.state.value.confirmation)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);open();click("recycle-purge-$id")
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{!vm.state.value.open};assertNull(vm.state.value.confirmation)
        compose.runOnIdle{vm.confirm();vm.restore(id,runBlocking{dao.recycleDocument(id)!!.trashRevision});vm.askPurge(id,1);vm.askEmpty();vm.next()}
        assertEquals("TRASHED",runBlocking{dao.recycleDocument(id)!!.libraryState});compose.runOnIdle{unlock()}
    }
    @Test fun stablePagesAndRapidRestoreDoNotSkipOrDuplicateItems(){
        repeat(65){seed()};open();val found=mutableListOf<String>()
        while(true){val page=vm.state.value.page;found+=page.items.map{it.documentId};if(page.offset+page.items.size>=page.count)break;click("recycle-next");ready()}
        assertEquals(found.size,found.distinct().size);assertTrue(found.containsAll(ids));assertTrue(vm.state.value.page.offset>=60)
        val id=vm.state.value.page.items.last().documentId;val rev=vm.state.value.page.items.last().trashRevision
        compose.runOnIdle{repeat(4){vm.restore(id,rev)}};ready();assertNotNull(runBlocking{dao.document(id)})
        click("recycle-previous");ready();click("recycle-close")
    }
}
