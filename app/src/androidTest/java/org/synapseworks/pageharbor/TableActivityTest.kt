package org.synapseworks.pageharbor

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import org.synapseworks.pageharbor.table.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.PageHarborScreen

class TableActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val i=InstrumentationRegistry.getInstrumentation();private val app=i.targetContext.applicationContext as Application
    private val model get()=compose.activity.table
    private lateinit var file:File
    private fun unlock(){val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];if(lock.state.value.phase==AppLockPhase.LOCKED){val t=lock.beginAuthentication(AppLockAuthenticationAvailability.AVAILABLE)!!;lock.onAuthenticationResult(t.id,AppLockAuthenticationResult.Success)};lock.disableAfterAuthentication()}
    @Before fun setup(){check(app.packageName.endsWith(".phase2test"));file=File(app.cacheDir,"m22-input-${UUID.randomUUID()}.png").apply{writeBytes(TableTestImage.bytes(FrozenTables.optical))};M22DocumentsProvider.reset(app)
        compose.runOnIdle{unlock();model.close();ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java].screen=PageHarborScreen.Home}}
    @After fun cleanup(){compose.runOnIdle{unlock();model.close()};file.delete();M22DocumentsProvider.reset(app)}
    private fun click(tag:String){compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()}
    private fun cellEvidence(r:Int,c:Int){
        compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-horizontal-$r"))
        compose.onNodeWithTag("table-evidence-$r-$c").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun cell(r:Int,c:Int,value:String){compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-horizontal-$r"));compose.onNodeWithTag("table-cell-$r-$c").performScrollTo().performTextReplacement(value)
        compose.runOnIdle{compose.activity.currentFocus?.clearFocus();androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}}
    private fun open(){val tools=app.getString(R.string.navigation_tools);compose.onNode((hasText(tools)or hasContentDescription(tools))and hasClickAction()).performClick();compose.onNodeWithTag("tools-grid").performScrollToNode(hasTestTag("open-table"));compose.onNodeWithTag("open-table").assertIsDisplayed().performClick();compose.waitUntil{model.state!=null}}
    private fun image(){val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{if(intent.action==Intent.ACTION_GET_CONTENT){assertEquals("image/*",intent.type);assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))};return null}}
        i.addMonitor(monitor);try{click("table-image");compose.waitUntil(40_000){model.state?.phase==TablePhase.EDIT}}finally{i.removeMonitor(monitor)}}
    private fun review(){click("table-review");assertEquals(TablePhase.REVIEW,model.state!!.phase);click("table-confirm")}
    private fun systemNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo?{val root=i.uiAutomation.rootInActiveWindow?:return null
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root);var count=0;while(queue.isNotEmpty()&&count++<1024){val n=queue.removeFirst();if(predicate(n))return n;for(k in 0 until n.childCount)n.getChild(k)?.let(queue::addLast)};return null}
    private fun systemClick(text:String,rootRow:Boolean=false){val until=SystemClock.uptimeMillis()+20_000
        fun inRoots(n:AccessibilityNodeInfo):Boolean{var p:AccessibilityNodeInfo?=n;while(p!=null){if(p.viewIdResourceName?.endsWith("/roots_list")==true)return true;p=p.parent};return false}
        while(SystemClock.uptimeMillis()<until){var n=systemNode{it.text?.toString()?.equals(text,true)==true&&(!rootRow||inRoots(it))};while(n!=null&&!n.isClickable)n=n.parent
            if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
            if(rootRow){val roots=systemNode{it.viewIdResourceName?.endsWith("/roots_list")==true};if(roots?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==false)roots.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)};SystemClock.sleep(100)
        };throw AssertionError("Synthetic system action unavailable")}
    private fun chooseDestination(){val end=SystemClock.uptimeMillis()+15_000;while(SystemClock.uptimeMillis()<end){val n=systemNode{it.contentDescription?.toString() in listOf("Show roots","Show navigation drawer")};if(n!=null){n.performAction(AccessibilityNodeInfo.ACTION_CLICK);break};SystemClock.sleep(100)}
        systemClick("RME CSV test destination",true);systemClick("Save")}
    private fun saved(){compose.waitUntil(30_000){model.state?.problem=="CSV saved."}}
    @Test fun reachableImageCorrectionAndActualSystemSaveWriteFinalCsvWithEvidence(){
        val session=ViewModelProvider(compose.activity)[PageHarborSessionViewModel::class.java];val before=session.documentSession
        open();image();assertEquals(FrozenTables.optical,model.state!!.grid.values());val originalItem=model.state!!.grid.values()[1][1];cell(1,1,"MANUAL O0");cell(1,1,FrozenTables.optical[1][1]);assertEquals(FrozenTables.optical,model.state!!.grid.values());cell(1,0,"0099");click("table-evidence-1-0");compose.onNodeWithText("OCR row",substring=true).assertExists();clickDialogClose()
        review();click("table-save");chooseDestination();saved();val target=M22DocumentsProvider.root(app).listFiles().orEmpty().single()
        File(app.cacheDir,"m22-ui.csv").writeBytes(target.readBytes());val expected=FrozenTables.optical.map{it.toMutableList()}.toMutableList();expected[1][0]="0099"
        File(app.cacheDir,"m22-ui-expected.json").writeText(org.json.JSONArray(expected).toString(),Charsets.UTF_8)
        assertEquals("0012",model.state!!.extraction!!.grid.values()[1][0]);assertEquals(originalItem,model.state!!.extraction!!.grid.values()[1][1]);assertSame(before,session.documentSession);assertFalse(model.state!!.confirmed)
        click("table-close");assertNull(model.state)
    }
    @Test fun untouchedAutomaticGridSavesExactCsvAndConflictingReadingsRemainReachable(){
        open();image();assertEquals(FrozenTables.optical,model.state!!.grid.values());assertFalse(model.state!!.grid.rows.flatten().any{it.corrected})
        review();cellEvidence(1,1);compose.onNodeWithText("Original OCR row",substring=true).assertExists()
        compose.onNodeWithText("Source crop (48px text):",substring=true).assertExists();compose.onAllNodesWithTag("table-use-original").assertCountEquals(0);clickDialogClose()
        click("table-save");chooseDestination();saved()
        File(app.cacheDir,"m22-automatic.csv").writeBytes(M22DocumentsProvider.root(app).listFiles()!!.single().readBytes())
        File(app.cacheDir,"m22-automatic-expected.json").writeText(org.json.JSONArray(FrozenTables.optical).toString())
        assertFalse(model.state!!.grid.rows.flatten().any{it.corrected})
        click("table-correct");cellEvidence(1,1);compose.onNodeWithTag("table-use-original").performScrollTo().assertIsDisplayed().performClick();compose.waitUntil{model.state!!.grid.values()[1][1]=="BLUE B0OK"};assertEquals("BLUE B0OK",model.state!!.grid.values()[1][1])
        cellEvidence(1,1);compose.onNodeWithTag("table-use-crop-48").performScrollTo().performClick();assertEquals(FrozenTables.optical,model.state!!.grid.values())
        click("table-review");assertFalse(model.state!!.confirmed)
    }
    @Test fun genuineLiteralZeroAndLetterOCorrectionsReachActualCsvWithoutNumericRewriting(){
        file.writeBytes(TableTestImage.bytes(FrozenLiteralTables.grid));open();image()
        for(r in FrozenLiteralTables.grid.indices)for(c in FrozenLiteralTables.grid[r].indices)if(model.state!!.grid.values()[r][c]!=FrozenLiteralTables.grid[r][c])cell(r,c,FrozenLiteralTables.grid[r][c])
        assertEquals(FrozenLiteralTables.grid,model.state!!.grid.values());review();click("table-save");chooseDestination();saved()
        File(app.cacheDir,"m22-literals-corrected.csv").writeBytes(M22DocumentsProvider.root(app).listFiles()!!.single().readBytes())
        File(app.cacheDir,"m22-literals-corrected-expected.json").writeText(org.json.JSONArray(FrozenLiteralTables.grid).toString())
    }
    private fun clickDialogClose(){compose.onNodeWithTag("table-evidence-close").performClick()}
    @Test fun actualSaveDialogCancellationLeavesNoWriteAndAllowsExplicitRetry(){
        open();click("table-manual");cell(0,0,"0007");review();click("table-save")
        compose.waitUntil(20_000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("input keyevent 4")).use{it.readBytes()}
        compose.waitUntil(20_000){model.state?.phase==TablePhase.REVIEW};assertFalse(model.state!!.confirmed);assertEquals(0,M22DocumentsProvider.opens.get());assertTrue(M22DocumentsProvider.root(app).listFiles().orEmpty().isEmpty())
        click("table-confirm");click("table-save");chooseDestination();saved();assertTrue(M22DocumentsProvider.root(app).listFiles()!!.single().readText().contains("0007"))
    }
    @Test fun cellAndRowColumnCorrectionsReachReviewedGridAndShowFormulaEscaping(){
        open();click("table-manual");cell(0,0,"0012");cell(0,1,"=1+2");cell(1,0,"-12.50");cell(1,1,"Café, \"q\"\nline")
        click("table-row-insert-0");click("table-row-down-1");click("table-row-delete-2")
        click("table-add-column");click("table-column-next");click("table-delete-column");click("table-column-left");click("table-column-right")
        cell(0,0,"0099");assertEquals(listOf(listOf("0099","=1+2"),listOf("-12.50","Café, \"q\"\nline")),model.state!!.grid.values())
        review();compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-horizontal-0"));compose.onNodeWithTag("table-escaped-0-1").performScrollTo().assertTextContains("text: =1+2",substring=true)
        var launches=0;val uri=M22DocumentsProvider.create(app);val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{if(intent.action==Intent.ACTION_CREATE_DOCUMENT){launches++;assertEquals("text/csv",intent.type);assertEquals("table.csv",intent.getStringExtra(Intent.EXTRA_TITLE));return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri))};return null}}
        i.addMonitor(monitor);try{click("table-save");saved();assertEquals(1,launches)}finally{i.removeMonitor(monitor)}
        File(app.cacheDir,"m22-corrected.csv").writeBytes(app.contentResolver.openInputStream(uri)!!.use{it.readBytes()})
        File(app.cacheDir,"m22-corrected-expected.json").writeText(org.json.JSONArray(listOf(listOf("0099","text: =1+2"),listOf("-12.50","Café, \"q\"\nline"))).toString())
        compose.onNodeWithTag("table-list").performScrollToNode(hasTestTag("table-reviewed"));File(app.cacheDir,"m22-review.png").outputStream().use{compose.onNodeWithTag("table-screen").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        click("table-correct");cell(0,1,"not a formula");click("table-review");assertFalse(model.state!!.confirmed)
    }
    @Test fun recreationRetainsOnlyMemoryAndActualBackgroundLockAndSavedStateProtectCells(){
        open();click("table-manual");val secret="M22EphemeralSyntheticOnlyQ0";cell(0,0,secret)
        val bundle=Bundle();compose.runOnIdle{i.callActivityOnSaveInstanceState(compose.activity,bundle)};val parcel=Parcel.obtain();val bytes=try{bundle.writeToParcel(parcel,0);parcel.marshall()}finally{parcel.recycle()}
        for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)){val needle=secret.toByteArray(charset);assertFalse((0..(bytes.size-needle.size).coerceAtLeast(-1)).any{start->needle.indices.all{bytes[start+it]==needle[it]}})}
        compose.activityRule.scenario.recreate();assertEquals(secret,model.state!!.grid.values()[0][0])
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);assertTrue(model.state!!.grid.rows.flatten().all{it.value.isEmpty()});compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        click("table-manual");cell(0,0,"PRIVATE SYNTHETIC");compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null};compose.onAllNodesWithTag("table-screen").assertCountEquals(0)
    }
    @Test fun registeredSaveCallbackAfterRecreationAndLockNeverOpensDestination(){
        open();click("table-manual");cell(0,0,"PRIVATE SYNTHETIC");review();var code=0;val uri=M22DocumentsProvider.create(app)
        compose.runOnIdle{val s=model.state!!;assertTrue(model.beginSave(s.owner,s.epoch,s.review!!.id));val bundle=Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(bundle)
            val keys=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=bundle.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!;val key="table-csv:"+model.activityResultOwner;code=codes[keys.indexOf(key)]
            val launched=bundle.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf();launched.add(key);bundle.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched);compose.activity.activityResultRegistry.onRestoreInstanceState(bundle)}
        compose.activityRule.scenario.recreate();assertEquals(TablePhase.DESTINATION,model.state!!.phase)
        compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{model.state==null}
        compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)))};assertEquals(0,M22DocumentsProvider.opens.get())
        compose.runOnIdle{unlock();compose.activity.openTable()};assertTrue(model.state!!.grid.rows.flatten().all{it.value.isEmpty()});assertEquals(0,M22DocumentsProvider.root(app).listFiles()!!.single().length().toInt())
    }
    @Test fun registeredImageCanReturnBehindAuthenticationWithoutOcrUntilUnlock(){
        open();var code=0
        compose.runOnIdle{val s=model.state!!;assertTrue(model.beginPicker(s.owner,s.epoch));val b=Bundle();compose.activity.activityResultRegistry.onSaveInstanceState(b);val keys=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!;val codes=b.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!;val key="table-image:"+model.activityResultOwner;code=codes[keys.indexOf(key)];val launched=b.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")?:arrayListOf();launched.add(key);b.putStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS",launched);compose.activity.activityResultRegistry.onRestoreInstanceState(b)}
        compose.activityRule.scenario.recreate();compose.runOnIdle{val lock=ViewModelProvider(compose.activity)[AppLockViewModel::class.java];lock.setup(AutoLockTimeout.IMMEDIATELY,AppLockAuthenticationAvailability.AVAILABLE);lock.lockNow()};compose.waitUntil{ViewModelProvider(compose.activity)[AppLockViewModel::class.java].state.value.phase==AppLockPhase.LOCKED}
        compose.runOnIdle{assertTrue(compose.activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))))};assertNull(model.state!!.extraction);assertEquals(TablePhase.OCR,model.state!!.phase)
        compose.runOnIdle{unlock()};compose.waitUntil(40_000){model.state?.phase==TablePhase.EDIT};assertTrue(TableOpticalEvidence.allOtherCellsExact(model.state!!.grid,FrozenTables.optical))
    }
}
