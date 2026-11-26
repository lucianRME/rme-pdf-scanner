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
import org.synapseworks.pageharbor.library.organization.*
import org.synapseworks.pageharbor.security.*

class OrganizationActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val dao get()=LibraryDatabase.get(app).libraryDao()
    private val vm get()=compose.activity.organization
    private lateinit var doc:String
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val r=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(r.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){
        check(app.packageName.endsWith(".phase2test"));doc="organization-ui-${UUID.randomUUID()}"
        compose.runOnIdle{unlock();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].clearScan()}
        runBlocking{
            val d=LibraryDocumentEntity(documentId=doc,title="Synthetic organization sample",createdAtMillis=2,modifiedAtMillis=Long.MAX_VALUE-100,pageCount=1,folderId=null,thumbnailRelativePath=null,ocrStatus="NOT_INDEXED")
            val p=LibraryPageEntity(pageId="$doc-page",documentId=doc,position=0,relativePath="synthetic",contentType="image/jpeg",sourceCategory="SCAN",width=10,height=10,sourceByteCount=1,rotationDegrees=0,filterName="ORIGINAL",ocrText=null,ocrError=null)
            dao.replaceDocument(d,listOf(p),"")
        }
        compose.waitUntil(30_000){!vm.uiState.value.catalogLoading}
    }
    @After fun cleanup(){compose.runOnIdle{unlock();vm.close()};runBlocking{dao.deleteDocument(doc);dao.organizationTags().filter{it.name.startsWith("UI sample")}.forEach{dao.deleteOrganizationTag(it.tagId,1)}}}
    private fun scroll(tag:String):SemanticsNodeInteraction {
        if(compose.onAllNodesWithTag("organization-tag-dialog").fetchSemanticsNodes().isNotEmpty()) {
            return compose.onNodeWithTag(tag).performScrollTo()
        }
        // Scroll the lazy container directly; a second generic scroll can realign the item
        // after the requested descendant was already brought into view at large fonts.
        compose.onNodeWithTag("organization-results").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun click(tag:String){val n=compose.onNodeWithTag(tag);if(!n.isDisplayed())scroll(tag);n.assertIsDisplayed().assertIsEnabled().performClick()}
    private fun ready(){compose.waitUntil(30_000){!vm.uiState.value.working&&!vm.uiState.value.page.loading};assertFalse(vm.uiState.value.page.failed)}
    private fun open(){compose.onNodeWithTag("open-organization").assertIsDisplayed().performClick();compose.waitUntil{vm.uiState.value.request.open};ready()}
    private fun input(text:String){
        val viewport=compose.onNodeWithTag("organization-tag-dialog")
        val unoccluded=viewport.fetchSemanticsNode().size.height
        val n=compose.onNodeWithTag("organization-name");n.performScrollTo().performClick()
        compose.waitUntil(10_000){viewport.fetchSemanticsNode().size.height<unoccluded-32}
        n.performScrollTo().assertIsFocused().performTextReplacement(text)
        compose.waitUntil(10_000){vm.uiState.value.nameInput==text}
        val done=compose.onNodeWithTag("organization-dialog-close");if(!done.isDisplayed())done.performScrollTo();done.assertIsDisplayed()
        File(app.cacheDir,"m17-organization-dialog.png").outputStream().use{compose.onNodeWithTag("organization-tag-dialog").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        n.performImeAction()
        compose.waitUntil(10_000){viewport.fetchSemanticsNode().size.height>=unoccluded-16}
    }
    @Test fun reachableCreateAssignRenameDeleteFavoriteAndFindControls(){
        open();click("organization-filters");ready()
        click("organization-tags-$doc");compose.waitUntil{vm.uiState.value.document.isNotEmpty()}
        input("UI sample tag");click("organization-save-name");ready()
        compose.waitUntil{vm.uiState.value.tags.any{it.name=="UI sample tag"}&&vm.uiState.value.document.any{it.tagId!=null}}
        val id=vm.uiState.value.tags.single{it.name=="UI sample tag"}.tagId
        click("organization-assign-$id");ready();compose.waitUntil{vm.uiState.value.document.none{it.tagId==id}}
        click("organization-assign-$id");ready();compose.waitUntil{vm.uiState.value.document.any{it.tagId==id}}
        click("organization-dialog-close");click("organization-favorite-$doc");ready();compose.waitUntil{vm.uiState.value.page.items.any{it.id==doc&&it.isFavorite}}
        click("organization-filters");click("organization-filter-$id");click("organization-favorites-only");ready();assertEquals(listOf(doc),vm.uiState.value.page.items.map{it.id})
        click("organization-manage");click("organization-rename-$id");input("UI sample renamed");click("organization-save-name");ready();compose.waitUntil{vm.uiState.value.tags.any{it.tagId==id&&it.name=="UI sample renamed"}}
        click("organization-dialog-close")
        compose.activityRule.scenario.recreate();ready();assertTrue(vm.uiState.value.request.filter.favoritesOnly);assertEquals(setOf(id),vm.uiState.value.request.filter.tagIds)
        compose.onNodeWithTag("organization-screen").assertIsDisplayed()
        File(app.cacheDir,"m17-organization-review.png").outputStream().use{compose.onNodeWithTag("organization-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("organization-manage");click("organization-delete-$id");click("organization-delete-confirm");ready();compose.waitUntil{vm.uiState.value.tags.none{it.tagId==id}}
        click("organization-dialog-close");compose.waitUntil{vm.uiState.value.request.filter.tagIds.isEmpty()};ready()
        assertTrue(runBlocking{dao.document(doc)!!.isFavorite});assertTrue(runBlocking{dao.documentTagIds(doc).isEmpty()})
        click("organization-clear");ready();click("organization-close");assertFalse(vm.uiState.value.request.open)
    }
    @Test fun recreationBackgroundAndAppLockDiscardUnsubmittedName(){
        open();click("organization-manage");input("UI sample unsubmitted")
        compose.activityRule.scenario.recreate();compose.waitUntil{vm.uiState.value.managing};assertEquals("UI sample unsubmitted",vm.uiState.value.nameInput)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertFalse(vm.uiState.value.request.open);assertEquals("",vm.uiState.value.nameInput)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);open();click("organization-manage");input("UI sample locked")
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()}
        compose.waitUntil{!vm.uiState.value.request.open};assertEquals("",vm.uiState.value.nameInput)
        assertTrue(runBlocking{dao.organizationTags().none{it.name in setOf("UI sample locked","UI sample unsubmitted")}})
        compose.runOnIdle{unlock()}
    }
    @Test fun unchangedFiltersStayReadyAndEmptyStateCanBeCleared(){
        open();compose.runOnIdle{vm.sort(vm.uiState.value.request.sort);vm.query(vm.uiState.value.request.query)};ready()
        val viewport=compose.onNodeWithTag("organization-viewport")
        val unoccluded=viewport.fetchSemanticsNode().size.height
        val field=scroll("organization-query");field.performClick()
        compose.waitUntil(10_000){viewport.fetchSemanticsNode().size.height<unoccluded-32}
        scroll("organization-query").assertIsFocused().performTextReplacement("zzqvorganizationmissing")
        compose.waitUntil(10_000){vm.uiState.value.request.query=="zzqvorganizationmissing"}
        field.performImeAction()
        compose.waitUntil(10_000){viewport.fetchSemanticsNode().size.height>=unoccluded-16}
        compose.waitUntil(30_000){!vm.uiState.value.page.loading&&vm.uiState.value.page.items.isEmpty()}
        scroll("organization-empty").assertIsDisplayed()
        click("organization-clear");ready();assertTrue(vm.uiState.value.page.items.any{it.id==doc})
        compose.runOnIdle{vm.folder("stale-folder")};ready()
        scroll("organization-folder").assertTextContains("Folder: Unavailable folder",substring=true)
        assertTrue(vm.uiState.value.page.items.isEmpty())
        click("organization-clear");ready();assertTrue(vm.uiState.value.page.items.any{it.id==doc})
        click("organization-close")
        compose.runOnIdle{vm.favorite(doc,true);vm.manage(true);vm.editDocument(doc);vm.name("UI sample late");vm.query("late");vm.favorites(true)}
        assertFalse(vm.uiState.value.working);assertFalse(vm.uiState.value.managing);assertNull(vm.uiState.value.documentId)
        assertEquals("",vm.uiState.value.nameInput);assertEquals("",vm.uiState.value.request.query);assertFalse(vm.uiState.value.request.filter.favoritesOnly)
        assertFalse(runBlocking{dao.document(doc)!!.isFavorite})
    }
}
