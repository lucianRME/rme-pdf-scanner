package org.synapseworks.pageharbor.resourcepack

import org.synapseworks.pageharbor.ui.theme.RmePrimaryButton
import org.synapseworks.pageharbor.ui.theme.RmeSecondaryButton
import org.synapseworks.pageharbor.ui.theme.RmeTextButton
import org.synapseworks.pageharbor.ui.theme.RmeTextField

import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.synapseworks.pageharbor.security.*
import org.synapseworks.pageharbor.ui.security.AppLockScreen
import org.synapseworks.pageharbor.ui.theme.setPageHarborContent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** No URI, metadata or draft in saved state, diagnostics or portable backups. */
class ResourcePackActivity : FragmentActivity() {
    private val lock: AppLockViewModel by viewModels()
    private val authentication by lazy { AndroidXAppLockAuthenticationController(this,"Unlock resource packs") }
    private val store by lazy { ResourcePackStore(File(noBackupFilesDir,"resource-packs-v1")) }
    private val models by lazy { ModelPackStore(File(noBackupFilesDir,"binary-model-packs-v1")) }
    private var modelInstalled by mutableStateOf(false)
    private val multilingualModels by lazy { MultilingualPack.store(noBackupFilesDir) }
    private var multilingualInstalled by mutableStateOf(false)
    private val irishModels by lazy { IrishTranslationPack.store(noBackupFilesDir) }
    private var irishInstalled by mutableStateOf(false)
    private enum class PackKind { TEXT, ROMANIAN, MULTILINGUAL, IRISH }
    private val delivery by lazy { TranslationPackDelivery.create(this) }
    private var downloading by mutableStateOf<TranslationPack?>(null)
    private var confirmationNeeded by mutableStateOf(false)
    private var delivered: Pair<TranslationPack, File>? = null
    private fun modelStore(pack: TranslationPack) = when (pack) {
        TranslationPack.ROMANIAN -> models
        TranslationPack.MULTILINGUAL -> multilingualModels
        TranslationPack.IRISH -> irishModels
    }
    private fun installed(pack: TranslationPack) = when (pack) {
        TranslationPack.ROMANIAN -> modelInstalled
        TranslationPack.MULTILINGUAL -> multilingualInstalled
        TranslationPack.IRISH -> irishInstalled
    }
    private fun cancelDownload() {
        if (downloading != null) {
            downloading = null; delivery?.cancel(); confirmationNeeded = false; busy = false
            status = "Download cancelled."
        }
        delivered = null
    }
    private fun download(pack: TranslationPack) {
        if (!available() || busy || !runtimeAvailable()) return
        val transport = delivery ?: return
        downloading = pack; busy = true; confirmationNeeded = false; status = "Requesting language download…"
        transport.request(pack, { message, confirm ->
            if (downloading == pack) { status = message; confirmationNeeded = confirm }
        }, { archive ->
            if (downloading == pack) {
                downloading = null; busy = false; confirmationNeeded = false
                delivered = pack to archive
                consumeDelivered()
            }
        }, {
            if (downloading == pack) {
                downloading = null; busy = false; confirmationNeeded = false
                status = "Google Play download unavailable for this installation. Retry later or use Advanced local import."
            }
        })
    }
    private fun consumeDelivered() {
        if (!available() || busy) return
        val (pack, archive) = delivered ?: return
        delivered = null; busy = true; cancelled = AtomicBoolean(false)
        val flag = cancelled
        val timeout = Runnable { if (cancelled === flag) cancelImport() }
        handler.postDelayed(timeout, 120_000)
        status = "Verifying and installing language…"
        lifecycleScope.launch {
            try {
                val context = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    archive.inputStream().use { stream ->
                        input.set(stream)
                        try {
                            modelStore(pack).import(stream) {
                                context.ensureActive(); if (flag.get()) throw CancellationException()
                            }
                        } finally { input.compareAndSet(stream, null) }
                    }
                }
                status = "Language ready · available offline."
                delivery?.remove(pack) // The validated private copy is authoritative; release the delivery archive.
            } catch (_:CancellationException) {
                status = "Installation cancelled. Check installed languages before retrying."
            } catch (_:Exception) {
                status = "Language installation failed. Check free storage and retry."
            } finally { handler.removeCallbacks(timeout); busy = false; refresh() }
        }
    }
    private fun removeLanguage(pack: TranslationPack) {
        lifecycleScope.launch {
            val removed = withContext(Dispatchers.IO) { runCatching { modelStore(pack).remove() }.getOrDefault(false) }
            status = if (removed) "Language files removed." else "Close translation before removing this language."
            if (removed) delivery?.remove(pack)
            refresh()
        }
    }
    private var modelActive by mutableStateOf(false)
    private var modelMessage by mutableStateOf("")
    private var modelCancel=AtomicBoolean(false)
    private var modelEnd=java.util.concurrent.CountDownLatch(0)
    companion object { private val nativeRunning=LocalModelUse.running }
    private fun closeModel(){modelCancel.set(true);modelEnd.countDown();if(modelActive)modelMessage="Close requested. Waiting for native work to finish; the pack remains leased until then."}
    private fun runtimeAvailability()=modelRuntimeAvailability(android.os.Build.VERSION.SDK_INT,android.os.Process.is64Bit(),android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull()) { runCatching{Class.forName("org.synapseworks.pageharbor.resourcepack.KnownModelRuntime",false,classLoader)}.isSuccess }
    private fun runtimeAvailable()=runtimeAvailability()==ModelRuntimeAvailability.AVAILABLE
    private fun openModel(){
        if(!available() || !runtimeAvailable() || lookupActive)return
        if(!nativeRunning.compareAndSet(false,true)){modelMessage="A prior model check is finishing. Wait, then retry.";return}
        val flag=AtomicBoolean(false);val end=java.util.concurrent.CountDownLatch(1);modelCancel=flag;modelEnd=end;modelActive=true;modelMessage="Validating and loading…"
        Thread({
            try{
                models.usePack({if(flag.get())throw java.util.concurrent.CancellationException()}){dir->
                    val type=Class.forName("org.synapseworks.pageharbor.resourcepack.KnownModelRuntime")
                    val client=type.getConstructor(File::class.java).newInstance(dir) as AutoCloseable
                    try{
                        val message=type.getMethod("check",AtomicBoolean::class.java).invoke(client,flag) as String
                        runOnUiThread{if(available() && modelCancel===flag && !flag.get())modelMessage=message}
                        while(!end.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)){if(flag.get())break}
                    }finally{client.close()}
                }
            }catch(_:Throwable){runOnUiThread{if(modelCancel===flag)modelMessage=if(flag.get())"Model check cancelled after native return." else "Model check unavailable or failed. Reimport the exact supported pack; no remote fallback."}}
            finally{nativeRunning.set(false);runOnUiThread{if(modelCancel===flag){modelActive=false;if(flag.get())modelMessage="Check worker finished; pack lease released. Native libraries may remain mapped.";refresh()}}}
        },"local-model-check").start()
    }
    private var packs by mutableStateOf<List<ResourcePackStore.Pack>>(emptyList())
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var lexicon by mutableStateOf<CmuLexicon?>(null)
    private var lookupActive by mutableStateOf(false)
    private var word by mutableStateOf("")
    private var pronunciations by mutableStateOf<List<String>>(emptyList())
    private var lookupMessage by mutableStateOf("")
    private var lookupFlag:AtomicBoolean?=null
    private var lookupEnd:java.util.concurrent.CountDownLatch?=null
    private fun closeLookup(){lookupFlag?.set(true);lookupEnd?.countDown();lexicon?.close();lexicon=null;lookupActive=false;word="";pronunciations=emptyList();lookupMessage=""}
    private fun openLookup(){
        if(!available() || lookupActive)return
        val flag=AtomicBoolean(false);val end=java.util.concurrent.CountDownLatch(1);lookupFlag=flag;lookupEnd=end;lookupActive=true;lookupMessage="Loading English pronunciation lexicon…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val context=currentCoroutineContext();val deadline=SystemClock.elapsedRealtime()+10_000
                fun checkLoad(){context.ensureActive();if(flag.get() || SystemClock.elapsedRealtime()>deadline)throw CancellationException()}
                checkLoad()
                store.usePack(CmuLexicon.PACK_ID){pack,dir->
                    val loaded=CmuLexicon.load(pack,dir,::checkLoad)
                    try {
                        runOnUiThread { if(available() && lookupFlag===flag && !flag.get()){lexicon=loaded;lookupMessage="English pronunciations only; not definitions or translation. CMUdict may contain errors or omissions."}else{flag.set(true);end.countDown()} }
                        while(!end.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)){context.ensureActive();if(flag.get())break}
                    } finally { loaded.close() }
                }
            } catch(_:CancellationException){runOnUiThread{if(lookupFlag===flag){closeLookup();status="Pronunciation loading cancelled or exceeded10s."}}}
            catch(_:IllegalStateException){runOnUiThread{if(lookupFlag===flag){closeLookup();status="Pronunciation pack missing. Import the pinned CMUdict local pack."}}}
            catch(_:Exception){runOnUiThread{if(lookupFlag===flag){closeLookup();status="Pronunciation resource corrupt or incompatible. Close consumers, remove it and reimport the pinned pack."}}}
        }
    }
    private var foreground=false
    private var pending: Pair<android.net.Uri, PackKind>? = null
    private var cancelled=AtomicBoolean(false)
    private val input=java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>(null)
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private fun cancelImport(){cancelled.set(true);runCatching{input.getAndSet(null)?.close()}}
    private val textPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected(it, PackKind.TEXT) }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected(it, PackKind.ROMANIAN) }
    private val multilingualPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected(it, PackKind.MULTILINGUAL) }

    private val irishPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { selected(it, PackKind.IRISH) }

    private fun selected(uri: android.net.Uri?, kind: PackKind) {
        if (lock.state.value.phase == AppLockPhase.LOCKED) {
            pending = null
            status = "Unlock and choose the file again."
            return
        }
        pending = uri?.let { it to kind }
        if (foreground) consume()
    }
    private fun available()=foreground && lock.state.value.phase!=AppLockPhase.LOCKED
    private fun refresh() { lifecycleScope.launch { try { packs=withContext(Dispatchers.IO){store.list()};modelInstalled=withContext(Dispatchers.IO){models.installed()};multilingualInstalled=withContext(Dispatchers.IO){multilingualModels.installed()};irishInstalled=withContext(Dispatchers.IO){irishModels.installed()} } catch(_:Exception){status="Installed pack metadata unavailable. No resource will be loaded."} } }
    private fun consume() {
        if(!available() || busy)return
        val (uri, kind) = pending ?: return
        pending = null
        val provider=uri.authority?.let { packageManager.resolveContentProvider(it,0) }
        val systemProvider=provider?.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if(uri.scheme!="content" || uri.authority !in setOf("com.android.externalstorage.documents","com.android.providers.downloads.documents") || provider==null || !systemProvider) {
            status="Choose a local ZIP from Android Internal storage or Downloads; other providers are unavailable in this profile.";return
        }
        busy=true; cancelled=AtomicBoolean(false); val flag=cancelled
        val timeout=Runnable { if(cancelled===flag)cancelImport() }; handler.postDelayed(timeout,60_000)
        lifecycleScope.launch {
            try {
                val context=currentCoroutineContext()
                withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { stream -> input.set(stream); try { if(flag.get())throw CancellationException(); val check = { context.ensureActive(); if (flag.get()) throw CancellationException() }
                    when (kind) {
                        PackKind.TEXT -> store.import(stream, check)
                        PackKind.ROMANIAN -> models.import(stream, check)
                        PackKind.MULTILINGUAL -> multilingualModels.import(stream, check)
                        PackKind.IRISH -> irishModels.import(stream, check)
                    } } finally { input.compareAndSet(stream,null) } } ?: error("Source unavailable") }
                status="Pack installed. Hashes establish integrity only, not publisher authenticity."
            } catch(_:CancellationException){status="Import cancelled. Check the installed list if cancellation met publication."}
            catch(_:Exception){status="Import declined or interrupted. Check installed packs; select a supported local pack and retry."}
            finally { handler.removeCallbacks(timeout); busy=false; refresh() }
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setPageHarborContent { val state by lock.state.collectAsState()
            LaunchedEffect(state.phase){if(state.phase==AppLockPhase.LOCKED){cancelImport();closeLookup();closeModel();pending=null;packs=emptyList()}else consumeDelivered()}
            if(state.phase==AppLockPhase.LOCKED) AppLockScreen(state,authentication.availability(),{lock.authenticate(authentication)},{startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))},{finish()})
            else Column(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val runtime = runtimeAvailability()
                var importKind by remember { mutableStateOf<PackKind?>(null) }
                var showDetails by remember { mutableStateOf(false) }
                var downloadPack by remember { mutableStateOf<TranslationPack?>(null) }
                var removalPack by remember { mutableStateOf<TranslationPack?>(null) }
                var downloadBytes by remember { mutableStateOf<Long?>(null) }
                var downloadUnavailable by remember { mutableStateOf(false) }
                var selectedLanguage by remember { mutableStateOf(translationPackLanguages.first()) }
                var showLanguages by remember { mutableStateOf(false) }
                var languageChosen by remember { mutableStateOf(false) }
                LaunchedEffect(modelInstalled, multilingualInstalled, irishInstalled) {
                    if (!languageChosen) translationPackLanguages.firstOrNull { installed(it.pack) }?.let { selectedLanguage = it }
                }
                RmeTextButton(onClick = { cancelImport(); cancelDownload(); closeLookup(); closeModel(); finish() }) { Text("Close") }
                Text("Offline languages", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a language. Once installed, translation works offline.")
                when (runtime) {
                    ModelRuntimeAvailability.MISSING_RUNTIME -> Text("Translation is unavailable in this app build.")
                    ModelRuntimeAvailability.UNSUPPORTED_DEVICE -> Text("Translation requires Android 15 or later on a 64-bit ARM device.")
                    ModelRuntimeAvailability.AVAILABLE -> if (delivery == null) Text("Language downloads are unavailable in this app build. Advanced local import is available below.")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    RmeTextButton(onClick = { cancelImport(); cancelDownload() }) { Text("Cancel") }
                }
                if (status.isNotEmpty()) Text(status)
                if (confirmationNeeded) RmeTextButton(onClick = { delivery?.confirm() }) { Text("Continue with Google Play") }
                Box {
                    RmeSecondaryButton(onClick = { showLanguages = true }, enabled = !busy) {
                        Text("Language: ${selectedLanguage.name}")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = showLanguages, onDismissRequest = { showLanguages = false }) {
                        translationPackLanguages.forEach { language ->
                            DropdownMenuItem(text = { Text(language.name + if (installed(language.pack)) " · Ready offline" else "") }, onClick = {
                                selectedLanguage = language; languageChosen = true; showLanguages = false
                            })
                        }
                    }
                }
                val language = selectedLanguage
                Text(language.directions)
                if (language.pack == TranslationPack.MULTILINGUAL) Text("Uses shared files for 10 languages. Installing one makes all 10 available.")
                if (installed(language.pack)) {
                    Text(if (runtime == ModelRuntimeAvailability.AVAILABLE) "Ready offline" else "Files installed · translation unavailable on this device or build")
                    RmeTextButton(onClick = { removalPack = language.pack }, enabled = !busy) {
                        Text(if (language.pack == TranslationPack.MULTILINGUAL) "Remove shared languages (10)" else "Remove ${language.name}")
                    }
                } else if (runtime == ModelRuntimeAvailability.AVAILABLE && delivery != null) {
                    RmePrimaryButton(onClick = {
                        downloadPack = language.pack; downloadBytes = null; downloadUnavailable = false
                        delivery?.downloadSize(language.pack) { bytes ->
                            if (downloadPack == language.pack) { downloadBytes = bytes; downloadUnavailable = bytes == null }
                        }
                    }, enabled = !busy) { Text("Download ${language.name}") }
                } else Text("Not installed")
                val installedNames = TranslationPack.entries.filter(::installed).map { pack ->
                    when (pack) { TranslationPack.ROMANIAN -> "Romanian"; TranslationPack.IRISH -> "Irish"; TranslationPack.MULTILINGUAL -> "10 shared languages" }
                }
                if (installedNames.isNotEmpty()) Text("Installed: ${installedNames.joinToString(", ")}. Choose a language above to manage it.")
                removalPack?.let { selected ->
                    val affected = translationPackLanguages.filter { it.pack == selected }.joinToString(", ") { it.name }
                    AlertDialog(
                        onDismissRequest = { removalPack = null },
                        title = { Text(if (selected == TranslationPack.MULTILINGUAL) "Remove all 10 shared languages?" else "Remove language?") },
                        text = { Text("This removes offline translation files for $affected. Download or import the files again to restore them.") },
                        confirmButton = { RmeTextButton(onClick = { removalPack = null; removeLanguage(selected) }) { Text(if (selected == TranslationPack.MULTILINGUAL) "Remove all 10" else "Remove") } },
                        dismissButton = { RmeTextButton(onClick = { removalPack = null }) { Text("Cancel") } },
                    )
                }
                downloadPack?.let { selected ->
                    AlertDialog(
                        onDismissRequest = { downloadPack = null },
                        title = { Text("Install language?") },
                        text = {
                            val bytes = downloadBytes
                            Text(when {
                                downloadUnavailable -> "Google Play cannot provide this language for this installation. Try again later or use Advanced local import."
                                bytes == null -> "Checking download size with Google Play…"
                                bytes == 0L -> "Language files are already on this device. Install them for offline translation."
                                else -> "Download size: ${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MiB. Google Play handles the download. Translation stays on this device. After download, installation needs ${when (selected) { TranslationPack.MULTILINGUAL -> "1.75 GiB"; TranslationPack.IRISH -> "1 GiB"; else -> "768 MiB" }} of free space."
                            })
                        },
                        confirmButton = {
                            RmeTextButton(onClick = { downloadPack = null; download(selected) }, enabled = downloadBytes != null && !downloadUnavailable) { Text("Install") }
                        },
                        dismissButton = { RmeTextButton(onClick = { downloadPack = null }) { Text("Cancel") } },
                    )
                }
                RmeTextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide advanced options" else "Advanced: local files and licenses")
                }
                if (showDetails) {
                    Text("Import a local translation file", style = MaterialTheme.typography.titleMedium)
                    Text("Use the exact compatible RME language file. Files are verified and stored outside backups.")
                    if (runtime == ModelRuntimeAvailability.AVAILABLE) {
                        if (!modelInstalled) RmeTextButton(onClick = { importKind = PackKind.ROMANIAN }, enabled = !busy) { Text("Import Romanian ZIP") }
                        if (!irishInstalled) RmeTextButton(onClick = { importKind = PackKind.IRISH }, enabled = !busy) { Text("Import Irish ZIP") }
                        if (!multilingualInstalled) RmeTextButton(onClick = { importKind = PackKind.MULTILINGUAL }, enabled = !busy) { Text("Import multilingual ZIP") }
                    }
                    Text("Other text resources", style = MaterialTheme.typography.titleMedium)
                    Text("Compatible UTF-8 text packs support separate lookup tools, not translation.")
                    RmeTextButton(onClick = { importKind = PackKind.TEXT }, enabled = !busy) { Text("Import text ZIP") }
                    if (packs.isEmpty()) Text("No text packs installed")
                }
                importKind?.let { selected ->
                    AlertDialog(
                        onDismissRequest = { importKind = null },
                        title = { Text("Import pack?") },
                        text = {
                            Text(
                                when (selected) {
                                    PackKind.MULTILINGUAL -> "Transfer the RME multilingual ZIP to this device. Keep at least 1.75 GiB free for import. The pack stays on this device, outside backups."
                                    PackKind.IRISH -> "Choose the RME Irish ZIP from this device. Keep at least 1 GiB free. The pack stays on this device, outside backups."
                                    else -> "Use a pack you trust and have permission to use. Integrity checks cannot verify the publisher. Packs stay on this device, outside backups."
                                },
                            )
                        },
                        confirmButton = {
                            RmeTextButton(onClick = {
                                importKind = null
                                val picker = when (selected) {
                                    PackKind.TEXT -> textPicker
                                    PackKind.ROMANIAN -> modelPicker
                                    PackKind.MULTILINGUAL -> multilingualPicker
                                    PackKind.IRISH -> irishPicker
                                }
                                picker.launch(arrayOf("application/zip", "application/octet-stream"))
                            }) { Text("Choose ZIP") }
                        },
                        dismissButton = { RmeTextButton(onClick = { importKind = null }) { Text("Cancel") } },
                    )
                }
                if(showDetails && modelInstalled){
                    Text("Translation pack installed")
                    if (runtime == ModelRuntimeAvailability.AVAILABLE) {
                        Text("Open a document and choose Translate text to use it.")
                    }
                    if (runtimeAvailable()) {
                        RmeTextButton({openModel()},enabled=!busy && !modelActive && !lookupActive){Text("Run local model check")}
                    }
                    Text(modelMessage)
                    if (modelActive) {
                        RmeTextButton({closeModel()}){Text("Close model check")}
                    }
                    RmeTextButton({lifecycleScope.launch{status=withContext(Dispatchers.IO){if(models.remove())"Model pack removed." else "Model pack still in use. Wait for native completion, then retry."};refresh()}},enabled=!busy){Text("Remove EN→RO model pack")}
                }
                if (showDetails) {
                    Text("Text recognition (OCR)", style = MaterialTheme.typography.titleMedium)
                    Text("Latin, Chinese, Japanese, Korean and Devanagari. Open More → OCR language to manage recognition.")
                    Text("Text packs use compatible UTF-8 files. ZIP limit: 8 MiB. Unpacked limit: 16 MiB.")
                    Text("Translation runs offline after installation. The multilingual file includes its model license.")
                    Text("A cancelled model check can finish native work before releasing the pack.")
                    if (runtime == ModelRuntimeAvailability.AVAILABLE || modelInstalled) ModelNotices()
                }
                if(showDetails && lookupActive){
                    Text("English pronunciation lookup",style=MaterialTheme.typography.titleMedium)
                    Text(lookupMessage)
                    RmeTextField(word,{if(it.length<=64){word=it;pronunciations=emptyList()}else lookupMessage="Maximum64 ASCII characters."},label={Text("English word (64 ASCII characters maximum)")},enabled=lexicon!=null,singleLine=true)
                    RmePrimaryButton({try{pronunciations=lexicon?.lookup(word).orEmpty();lookupMessage=if(pronunciations.isEmpty())"Word not found in this English lexicon." else "All matching pronunciations, including variants:"}catch(_:Exception){pronunciations=emptyList();lookupMessage="Enter one ASCII word, at most64 characters."}},enabled=lexicon!=null){Text("Look up word")}
                    pronunciations.forEach{Text(it)}
                    RmeTextButton({closeLookup()}){Text("Cancel / close pronunciation lookup")}
                    Text("The pack is leased while loading and while this lookup remains open. Closing releases resources; heap reclamation is not guaranteed.")
                }
                if (showDetails) packs.forEach { p ->
                    Text("${p.id} · v${p.version}", style = MaterialTheme.typography.titleMedium)
                    Text("${p.entries.size} verified files")
                    var expanded by key(p.id) { remember { mutableStateOf(false) } }
                    RmeTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Pack details") }
                    if (expanded) Text("Declared source: ${p.provenance}\nDeclared license: ${p.license}\nDeclared hardware: ${p.hardware}\nManifest SHA-256: ${p.manifestHash}")
                    if(p.id==CmuLexicon.PACK_ID)RmeTextButton({openLookup()},enabled=!busy && !lookupActive && !modelActive){Text("Open English pronunciation lookup")}
                    RmeTextButton({ lifecycleScope.launch { try { val removed=withContext(Dispatchers.IO){store.remove(p.id)}; status=if(removed)"Pack removed." else "Pack is in use or unavailable. Close its consumer and retry." }catch(_:Exception){status="Removal interrupted. Refresh before retrying."};refresh() } },enabled=!busy){Text("Remove ${p.id}")}
                }
                RmeTextButton({refresh()},enabled=!busy){Text("Refresh installed packs")}
            }
        }
    }

    @Composable
    private fun ModelNotices() {
        var showing by remember { mutableStateOf(false) }
        RmeTextButton(onClick = { showing = !showing }) {
            Text(if (showing) "Close licenses" else "Read model licenses")
        }
        if (showing) {
            val notices by produceState<List<String>?>(null) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        assets.open("m36-model-NOTICES.txt").bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            val body = StringBuilder()
                            while (body.length < 4 * 1024 * 1024) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 - body.length))
                                if (count <= 0) break
                                body.append(buffer, 0, count)
                            }
                            body.lineSequence().flatMap { it.chunked(2000).asSequence() }.toList()
                        }
                    }.getOrDefault(listOf("Licenses unavailable in this build."))
                }
            }
            val lines = notices
            if (lines == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                items(lines.size) { index -> Text(lines[index]) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        lock.onAppForegrounded(SystemClock.elapsedRealtime())
        if (available()) {
            refresh()
            consume()
            consumeDelivered()
        }
    }
    override fun onStop() {
        foreground = false
        cancelImport()
        closeLookup()
        closeModel()
        lock.onAppBackgrounded(SystemClock.elapsedRealtime())
        super.onStop()
    }
    override fun onDestroy() {
        cancelImport()
        cancelDownload()
        delivery?.close()
        closeLookup()
        closeModel()
        authentication.cancel()
        super.onDestroy()
    }
}
