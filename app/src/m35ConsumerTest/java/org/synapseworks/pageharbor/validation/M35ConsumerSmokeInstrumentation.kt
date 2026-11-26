package org.synapseworks.pageharbor.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.synapseworks.pageharbor.BuildConfig
import org.synapseworks.pageharbor.office.previewOffice
import org.synapseworks.pageharbor.office.OfficeSource
import org.synapseworks.pageharbor.workflow.officeRecipeChecks
import java.io.File
import java.security.MessageDigest

/** Synthetic-package-only Android SDK instrumentation. No external AndroidX runner
 * or broad keep rules: actual consumer code is shrunk/optimized normally.
 * Inputs come from the separately installed test APK's frozen assets, not
 * production assets or user data. Only hashes/counts/fixed codes are reported.
 */
class M35ConsumerSmokeInstrumentation : Instrumentation() {
    private lateinit var parameters: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); parameters = arguments ?: Bundle(); start() }
    private val app get() = targetContext
    private val fixtures by lazy { app.createPackageContext(app.packageName + ".test", 0).assets }
    private val report = JSONObject()
    private val results = JSONArray()
    private var lastControl = "START"
    private val lifecycle = java.util.concurrent.atomic.AtomicReference("START")
    private val recipeLifecycle = JSONArray()
    private var recipeOffice: java.lang.ref.WeakReference<Activity>? = null
    // Ordinary diagnostic runner only. Read getters/flags, never URI/text values.
    private fun recipeState(activity: Activity, event: String) {
        if (!BuildConfig.DEBUG || !::parameters.isInitialized || parameters.getString("m35RecipeTrace") != "true" || !activity.javaClass.name.endsWith("OfficeImportActivity")) return
        recipeOffice = java.lang.ref.WeakReference(activity)
        val row = JSONObject().put("event", event).put("elapsedMs", SystemClock.elapsedRealtime())
        fun field(name: String): Any? = runCatching { activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) }.getOrNull()
        fun getter(name: String): Any? = runCatching { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }.getOrNull()
        for (name in listOf("foreground", "external", "ownsSession")) row.put(name, field(name) as? Boolean ?: JSONObject.NULL)
        for ((name, method) in listOf("sourceReady" to "getSourceReady", "workspaceReady" to "getWorkspaceReady", "busy" to "getBusy")) row.put(name, getter(method) as? Boolean ?: JSONObject.NULL)
        row.put("epoch", field("epoch") as? Long ?: -1).put("sourcePresent", field("source") != null).put("pendingInputPresent", field("pendingInput") != null).put("pickerKindPresent", field("pickerKind") != null)
        val message = getter("getMessage") as? String
        val code = message?.let { Regex("^Source declined: ([A-Z_]{1,48})\\.").find(it)?.groupValues?.get(1) }
            ?: when { message == null -> "NONE"; message.startsWith("The previous review ended on recreation") -> "RECREATION_EXPIRED"; message.startsWith("Previous Office cleanup is not confirmed") -> "CLEANUP_UNCONFIRMED"; else -> "OTHER_FIXED_MESSAGE_PRESENT" }
        row.put("messageCode", code).put("finishing", activity.isFinishing).put("destroyed", activity.isDestroyed)
        synchronized(recipeLifecycle) { if (recipeLifecycle.length() < 64) recipeLifecycle.put(row) }
    }
    override fun callActivityOnResume(activity: Activity) {
        recipeState(activity, "BEFORE_RESUME"); super.callActivityOnResume(activity); recipeState(activity, "AFTER_RESUME")
    }
    override fun callActivityOnPause(activity: Activity) {
        recipeState(activity, "BEFORE_PAUSE"); super.callActivityOnPause(activity); recipeState(activity, "AFTER_PAUSE")
    }
    override fun callActivityOnStop(activity: Activity) {
        if (activity.javaClass.name.endsWith("OfficeImportActivity")) lifecycle.set("OFFICE_STOP")
        recipeState(activity, "BEFORE_STOP"); super.callActivityOnStop(activity); recipeState(activity, "AFTER_STOP")
    }
    override fun callActivityOnDestroy(activity: Activity) {
        if (activity.javaClass.name.endsWith("OfficeImportActivity")) lifecycle.set(if (activity.isFinishing) "OFFICE_FINISH" else "OFFICE_DESTROY")
        super.callActivityOnDestroy(activity)
    }
    private fun component(command:String,marker:String):String {
        val text=ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input->
            val bytes=ByteArray(1024*1024);var used=0
            while(used<bytes.size){val n=input.read(bytes,used,bytes.size-used);if(n<0)break;used+=n}
            String(bytes,0,used,Charsets.UTF_8)
        }
        val line=text.lineSequence().firstOrNull{marker in it || marker=="mResumedActivity" && ("topResumedActivity=" in it || "ResumedActivity:" in it)}.orEmpty()
        return Regex("(?:android|com\\.android\\.[a-z0-9_.]+|com\\.google\\.android\\.documentsui|org\\.synapseworks\\.pageharbor\\.m04r8smoke)/[a-zA-Z0-9_.$]+").find(line)?.value?:"UNAVAILABLE"
    }
    private fun resumedComponent()=component("dumpsys activity activities","mResumedActivity")
    private fun foregroundWindow()=component("dumpsys window","mCurrentFocus")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkpoint(code: String) { sendStatus(17, Bundle().apply { putString("m35Step", code) }) }
    private fun waitFor(limit: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + limit
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(50) }
        throw IllegalStateException("CONTROL_TIMEOUT")
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if(Build.VERSION.SDK_INT>=34)uiAutomation.clearCache()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let(pending::add); var count = 0
        while (pending.isNotEmpty() && count++ < 4096) {
            val item = pending.removeFirst(); if (predicate(item)) return item
            for (index in 0 until item.childCount) item.getChild(index)?.let(pending::addLast)
        }
        return null
    }
    private fun tag(value: String) = node { it.viewIdResourceName?.let { name -> name == value || name.endsWith("/$value") } == true }
    private fun clickNode(initial: AccessibilityNodeInfo):Boolean {
        var item: AccessibilityNodeInfo? = initial
        while (item != null && !item.isClickable) item = item.parent
        if(item==null || !item.refresh() || !item.isVisibleToUser || !item.isEnabled)return false
        val accepted=item.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        SystemClock.sleep(75)
        return accepted
    }
    private fun reveal(value: String): AccessibilityNodeInfo {
        lastControl = value
        repeat(32) { attempt ->
            val item = tag(value)
            if (item?.isVisibleToUser == true) return item
            val scroll = node { it.isScrollable && it.packageName?.toString() == app.packageName }
            val bounds = Rect(); item?.getBoundsInScreen(bounds)
            val backwards = item != null && bounds.top < 0 || item == null && attempt >= 16
            scroll?.performAction(if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        throw IllegalStateException("CONTROL_NOT_REACHABLE")
    }
    private fun click(value: String) {
        val until=SystemClock.elapsedRealtime()+5000
        var attempted=false
        do {
            if(attempted && value=="office-save" && uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true) {
                report.put("falseActionObservedPicker",report.optInt("falseActionObservedPicker")+1);return
            }
            attempted=true
            if(clickNode(reveal(value)))return
            report.put("accessibilityFalseClickRetries",report.optInt("accessibilityFalseClickRetries")+1)
            SystemClock.sleep(50) // Refresh false actions; an observed picker is acknowledged before another dispatch.
        } while(SystemClock.elapsedRealtime()<until)
        throw IllegalStateException("CONTROL_CLICK_FAILED")
    }
    private fun messageContains(value: String): Boolean {
        val item = tag("office-message")
        if (item?.isVisibleToUser == true) return item.text?.contains(value) == true
        node { it.isScrollable && it.packageName?.toString() == app.packageName }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return false
    }
    private fun back() { ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }; SystemClock.sleep(500) }
    private fun waitOpen() = waitFor { tag("office-open")?.isEnabled == true }
    private fun open(uri: Uri, kind: String): Boolean {
        report.put("expectedOpenKind",kind)
        waitOpen(); click("office-kind-$kind")
        report.put("selectedKindMarkerVisible",tag("office-kind-$kind")?.text?.contains("✓")==true)
        var observed = 0
        val monitor = object : ActivityMonitor() {
            override fun onStartActivity(intent: Intent): ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                observed++; return ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        addMonitor(monitor)
        try {
            click("office-open")
            // Returning the picker result precedes the asynchronous source read.
            // An enabled Open button can still be the old frame, not a refusal.
            // The source header can scroll out of the accessibility viewport as
            // the review grows. These controls exist together only after the
            // source read completes (sourceReady && output == null). Observe
            // that real state, then reach/activate them through review().
            fun reviewed()=node { it.text?.toString()?.startsWith("Source: ${kind.uppercase()} · captured byte identity.")==true }!=null ||
                tag("office-accept")!=null && tag("office-convert")!=null
            var scans=0
            waitFor(30000) { uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName &&
                (if(reviewed() || tag("office-message")?.text?.toString()?.startsWith("Source declined:")==true) true else {
                    // Platform accessibility exposes the visible viewport, unlike
                    // the Compose test semantics tree. Search both directions
                    // for the actual review/refusal; do not infer a decision.
                    if(scans++ % 2 == 0) node { it.isScrollable && it.packageName?.toString()==app.packageName }
                        ?.performAction(if(scans % 60 < 20) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }) }
            check(observed == 1) { "OPEN_MONITOR_FAILED" }
            return reviewed()
        }
        finally { removeMonitor(monitor) }
    }
    private fun review() {
        click("office-accept"); click("office-convert"); var count = 0
        while (true) {
            waitFor(75000) { tag("office-preview") != null }
            click("office-reviewed"); count++; check(count <= 10)
            if (tag("office-next")?.isEnabled != true) break
            click("office-next")
        }
        waitFor { tag("office-save")?.isEnabled == true }
    }
    private fun systemClick(text: String, rootsOnly: Boolean = false) {
        lastControl=if(rootsOnly)"SYSTEM_LOCAL_ROOT" else "SYSTEM_SAVE"
        waitFor(20000) {
            val found = node { item ->
                if (!item.isVisibleToUser || !item.isEnabled || !item.text?.toString().equals(text, true)) false else if (!rootsOnly) true else {
                    var parent: AccessibilityNodeInfo? = item
                    while (parent != null && parent.viewIdResourceName?.endsWith("/roots_list") != true) parent = parent.parent
                    parent != null
                }
            }
            if (found != null && clickNode(found))true
            else if(rootsOnly && node{it.viewIdResourceName?.endsWith("/roots_list")==true && it.isVisibleToUser}==null)true
            else if(!rootsOnly && uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName)true
            else { if (rootsOnly) node { it.viewIdResourceName?.endsWith("/roots_list") == true }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
        }
    }
    private fun save(name: String) {
        click("office-save")
        waitFor { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        waitFor {
            if(node{it.viewIdResourceName?.endsWith("/roots_list")==true && it.isVisibleToUser}!=null)true
            else { node { it.contentDescription?.toString() in listOf("Show roots", "Show navigation drawer") }?.let(::clickNode);false }
        }
        systemClick("Downloads", true)
        val edit = node { it.className?.toString() == "android.widget.EditText" } ?: error("SAVE_NAME_CONTROL_MISSING")
        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }))
        systemClick("Save")
        waitFor(30000) { messageContains("Separate PDF saved.") }
    }
    private fun pixels(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256"); val row = IntArray(bitmap.width); val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { index, value -> for (component in 0..3) bytes[index * 4 + component] = (value ushr (24 - component * 8)).toByte() }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun previewControls(root: File) = runBlocking {
        val cases = JSONObject(fixtures.open("m35-preview/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        val rows = JSONArray()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index); val file = File(root, "reference.pdf")
            file.writeBytes(fixtures.open("m35-preview/" + case.getString("file")).use { it.readBytes() })
            check(digest(file.readBytes()) == case.getString("sha256"))
            // Independent known-fixture oracle only; its bitmap is test memory,
            // separate from the consumer's sequential8MiB pixel allocation.
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                for (page in 0 until pdf.pageCount) {
                    val actual = previewOffice(app, file, page, pdf.pageCount)
                    try { pdf.openPage(page).use { source ->
                        val scale = 1280.0 / maxOf(source.width, source.height)
                        val expected = Bitmap.createBitmap(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        try {
                            expected.eraseColor(Color.WHITE); source.render(expected, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val want = pixels(expected); val got = pixels(actual)
                            check(expected.width == actual.width && expected.height == actual.height && want == got) { "PIXEL_GATE_FAILED" }
                            rows.put(JSONObject().put("id", case.getString("id")).put("page", page).put("sha256", got).put("exact", true))
                        } finally { expected.recycle() }
                    } } finally { actual.recycle() }
                }
            }
            file.delete()
        }
        report.put("preview", rows)
    }
    override fun onStart() {
        super.onStart()
        val root = File(app.cacheDir, "shared-pdfs/m35-smoke-${java.util.UUID.randomUUID()}")
        var activity: Activity? = null
        try {
            check(BuildConfig.M35_OFFICE_ENABLED && app.packageName in setOf("org.synapseworks.pageharbor.m04r8smoke", "org.synapseworks.pageharbor.phase2test")) { "SYNTHETIC_PROFILE_REQUIRED" }
            if(parameters.getString("m35RecipeOnly")=="true") {
                val results=try { officeRecipeChecks(checkNotNull(parameters.getString("m35RecipeSource"))) } finally {
                    if (BuildConfig.DEBUG && parameters.getString("m35RecipeTrace") == "true") {
                        recipeOffice?.get()?.let { recipeState(it, "RECIPE_FINALLY") }
                        val folder=File(app.getExternalFilesDir(null),"m35-recipe-diagnostic").apply { mkdirs() }
                        File(folder,"lifecycle.json").writeText(synchronized(recipeLifecycle) { recipeLifecycle.toString(2) })
                    }
                }
                finish(Activity.RESULT_OK,Bundle().apply{putString("status","PASS_RECIPE_ONLY");putString("m35Results",results.toString())})
                return
            }
            val run = parameters.getString("m35ArtifactRun") ?: error("RUN_REQUIRED")
            check(run.matches(Regex("[a-f0-9]{7}-(ordinary|optimized)-(api26|api35|api36|api37)-[a-f0-9]{8}")))
            val shareOnly = parameters.getString("m35ShareOnly") == "true"
            if (shareOnly) report.put("scope", "DIAGNOSTIC_SHARE_ONLY")
            check(root.mkdirs()); if (!shareOnly) { previewControls(root); checkpoint("PIXELS_PASS") }
            activity = startActivitySync(Intent().setClassName(app, "org.synapseworks.pageharbor.office.OfficeImportActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                val lock = androidx.lifecycle.ViewModelProvider(activity as androidx.lifecycle.ViewModelStoreOwner)[org.synapseworks.pageharbor.security.AppLockViewModel::class.java]
                if (lock.state.value.phase == org.synapseworks.pageharbor.security.AppLockPhase.LOCKED) {
                    lock.beginAuthentication(org.synapseworks.pageharbor.security.AppLockAuthenticationAvailability.AVAILABLE)?.let {
                        lock.onAuthenticationResult(it.id, org.synapseworks.pageharbor.security.AppLockAuthenticationResult.Success)
                    }
                }
                lock.disableAfterAuthentication()
            }
            if (parameters.getString("m35ChooserControl") == "true") {
                runOnMainSync { activity!!.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "M35 synthetic chooser control"), "Synthetic control")) }
                waitFor { uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver") }
                report.put("scope", "DIAGNOSTIC_PLAIN_TEXT_CHOOSER").put("windowComponent", foregroundWindow())
                back()
                finish(Activity.RESULT_OK, Bundle().apply { putString("status", "PASS_DIAGNOSTIC_ONLY"); putString("m35Results", report.toString()) })
                return
            }
            val cases = JSONObject(fixtures.open("m35/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
            var aliases = 0; var cancelled = 0; var shared = 0
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index); val id = case.getString("id"); val kind = case.getString("kind")
                check(id.matches(Regex("[a-z0-9-]{1,64}")) && kind in setOf("docx", "xlsx", "pptx"))
                val source = File(root, "source.$kind").apply { writeBytes(fixtures.open("m35/$id.$kind").use { it.readBytes() }) }
                check(digest(source.readBytes()) == case.getString("sha256"))
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", source)
                val admitted = open(uri, kind)
                val expected = case.getString("expectedProduct")
                if (expected != "ADMITTED") {
                    check(!admitted && reveal("office-message").text?.contains("Source declined: $expected.") == true) { "REFUSAL_GATE_FAILED" }
                    results.put(JSONObject().put("id", id).put("decision", expected).put("sourceUnchanged", true))
                } else {
                    check(admitted) { "ADMISSION_GATE_FAILED" }
                    review()
                    if (aliases == 0 && !shareOnly) {
                        val alias = object : ActivityMonitor() { override fun onStartActivity(intent: Intent): ActivityResult? =
                            if (intent.action == Intent.ACTION_CREATE_DOCUMENT) ActivityResult(Activity.RESULT_OK, Intent().setData(uri)) else null }
                        addMonitor(alias)
                        try { click("office-save"); waitFor { messageContains("DESTINATION_REFUSED") } }
                        finally { removeMonitor(alias) }
                        check(digest(source.readBytes()) == case.getString("sha256")); aliases++
                        waitFor { tag("office-save")?.isEnabled == true }
                    }
                    if (cancelled == 0 && !shareOnly) {
                        click("office-save"); waitFor { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
                        repeat(3) { if (uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) back() }
                        waitFor { tag("office-save")?.isEnabled == true }; cancelled++
                    }
                    if (!shareOnly) save("M35-$run-$id.pdf")
                    results.put(JSONObject().put("id", id).put("decision", "ADMITTED").put("saved", !shareOnly).put("sourceUnchanged", true))
                    if (shared == 0) {
                        waitFor { tag("office-share")?.isEnabled == true }
                        val launched = java.util.concurrent.atomic.AtomicReference<Intent>()
                        val launchFailure = java.util.concurrent.atomic.AtomicReference<String>()
                        val office = activity as org.synapseworks.pageharbor.office.OfficeImportActivity
                        runOnMainSync {
                            office.onShareStartedForTest = { launched.set(it) }
                            office.onShareFailureForTest = { launchFailure.set(it) }
                        }
                        try {
                            click("office-share"); waitFor {
                                launchFailure.get()?.let { report.put("shareFailure", it); error("SHARE_LAUNCH_DECLINED") }
                                launched.get() != null
                            }
                            val request = checkNotNull(launched.get()); val sharedUri = request.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: error("SHARE_URI_MISSING")
                            check(request.type == "application/pdf" && request.clipData?.getItemAt(0)?.uri == sharedUri)
                            check(request.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            val bytes = app.contentResolver.openInputStream(sharedUri)!!.use { it.readBytes() }; check(bytes.size in 1..32 * 1024 * 1024)
                            report.put("shareSha256", digest(bytes)).put("shareSourceId", id)
                            report.put("shareWindowBeforeWait", resumedComponent())
                            lastControl = "SHARE_CHOOSER_FOREGROUND"
                            waitFor {
                                val message = tag("office-message")?.text?.toString().orEmpty()
                                if (message.startsWith("Sharing declined")) {
                                    report.put("shareDeclined", true)
                                    report.put("shareFailure", Regex("\\((SHARE_[A-Z_]+)\\)").find(message)?.groupValues?.get(1) ?: "SOURCE_OR_STORAGE")
                                    error("SHARE_LAUNCH_DECLINED")
                                }
                                uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver")
                            }; back()
                            // A translucent chooser pauses Office without
                            // stopping it. Dismissing it can preserve the review;
                            // Open is only required after explicit cancellation.
                            lastControl = "SHARE_RETURN_TO_OFFICE"
                            waitFor { uiAutomation.rootInActiveWindow?.packageName?.toString() == app.packageName }
                            click("office-clear-shares")
                            lastControl = "SHARE_COPY_CLEANUP"
                            waitFor { messageContains("Previous Office share grants") && tag("office-clear-shares")?.isEnabled == true }
                            shared++
                            if (shareOnly) {
                                report.put("cases", results).put("actualShares", shared)
                                finish(Activity.RESULT_OK, Bundle().apply { putString("status", "PASS_DIAGNOSTIC_ONLY"); putString("m35Results", report.toString()) })
                                return
                            }
                        } finally { runOnMainSync { office.onShareStartedForTest = null; office.onShareFailureForTest = null } }
                    }
                }
                check(digest(source.readBytes()) == case.getString("sha256")); source.delete()
                click("office-cancel"); waitOpen(); checkpoint("CASE_${index + 1}_PASS")
            }
            check(aliases == 1 && cancelled == 1 && shared == 1)
            report.put("cases", results).put("sourceAliasControls", aliases).put("actualSaveCancellations", cancelled).put("actualShares", shared).put("api", Build.VERSION.SDK_INT)
            finish(Activity.RESULT_OK, Bundle().apply { putString("m35Results", report.toString()); putString("status", "PASS") })
        } catch (failure: Throwable) {
            // Synthetic-driver diagnostics only: fixed kinds/codes, never source
            // text, font names, URIs, paths or arbitrary state values/field names.
            runCatching {
                runOnMainSync {
                    activity?.let { current ->
                        report.put("appLockPhase",androidx.lifecycle.ViewModelProvider(current as androidx.lifecycle.ViewModelStoreOwner)[org.synapseworks.pageharbor.security.AppLockViewModel::class.java].state.value.phase.name)
                        for(field in current.javaClass.declaredFields)runCatching {
                            field.isAccessible=true
                            val raw=field.get(current)
                            val value=if(raw is androidx.compose.runtime.State<*>)raw.value else raw
                            if(value is String && value in setOf("docx","xlsx","pptx"))report.put("activityChoiceKind",value)
                            if(value is OfficeSource)report.put("boundSourceKind",value.kind)
                            if(value is String)Regex("(?:Source declined:|Conversion declined:) ([A-Z_]{1,32})").find(value)?.let{report.put("fixedDecisionCode",it.groupValues[1])}
                        }
                    }
                }
            }
            report.put("openVisible",tag("office-open")?.isVisibleToUser==true).put("convertVisible",tag("office-convert")?.isVisibleToUser==true).put("previewVisible",tag("office-preview")?.isVisibleToUser==true)
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("status", "FAILED"); putString("failureClass", failure.javaClass.simpleName)
                putString("failureControl", lastControl)
                putString("lifecycle", lifecycle.get()); putString("resumedComponent", resumedComponent())
                putString("windowComponent", foregroundWindow())
                // Code identities only; no locals, exception messages, document
                // data or provider identifiers. Test-only, finite diagnosis.
                putString("mainCodeStack", Looper.getMainLooper().thread.stackTrace.take(24).joinToString(";") { it.className + "." + it.methodName })
                putString("window", uiAutomation.rootInActiveWindow?.packageName?.toString()?.takeIf {
                    it == app.packageName || it in setOf("android", "com.android.documentsui", "com.google.android.documentsui", "com.android.intentresolver")
                } ?: "OTHER")
                putString("failureCode", failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "CONTROL_FAILURE")
                putString("m35Results", report.put("cases", results).toString())
            })
        } finally { activity?.let { runOnMainSync { it.finish() } }; root.deleteRecursively() }
    }
}
