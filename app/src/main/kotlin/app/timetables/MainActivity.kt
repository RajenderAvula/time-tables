package app.timetables

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Hosts the Time Tables web app (assets/index.html) in a WebView and gives it native abilities:
 *  - alarms, reminders, digest and Pomodoro that fire when the app is closed (Scheduler)
 *  - speech recognition through Android's SpeechRecognizer (works offline with a downloaded language pack)
 *  - saving files of any size to Downloads/Time Tables
 *  - notifications, microphone / camera / location permissions, file chooser, Back button
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permResult: ((Map<String, Boolean>) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            )
            fileCallback = null
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            permResult?.invoke(res)
            permResult = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // A WebView ignores its own padding, so the padding goes on a container around it.
        val root = FrameLayout(this)
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.page_bg))
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // keep the page clear of the status bar, navigation bar and keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val u = request.url
                if (u.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                val scheme = u.scheme ?: ""
                try {
                    when (scheme) {
                        // web pages and Google searches open inside the app
                        "http", "https" -> openInApp(u.toString())
                        "intent" -> startActivity(
                            Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                            }
                        )
                        else -> startActivity(Intent(Intent.ACTION_VIEW, u)) // tel:, sms:, mailto:, geo:, app schemes
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No app can open this link", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            // microphone / camera requests from the page (voice notes, photos)
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = mutableListOf<String>()
                    for (r in request.resources) {
                        when (r) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> need += Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> need += Manifest.permission.CAMERA
                        }
                    }
                    ask(need) { ok ->
                        if (ok.values.all { it }) request.grant(request.resources) else request.deny()
                    }
                }
            }

            // location-based alarms
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                runOnUiThread {
                    ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { ok ->
                        callback.invoke(origin, ok.values.any { it }, false)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // close an open dialog / panel first, otherwise leave the app
                web.evaluateJavascript(
                    "(function(){var o=document.querySelector('.mdlg,.srch:not([hidden]),#sheet:not([hidden]),#qpanel:not([hidden])');" +
                        "if(o){(document.activeElement||document.body).dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return 1}return 0})()"
                ) { r -> if (r != "1") moveTaskToBack(true) }
            }
        })

        Scheduler.ensureChannel(this)
        Scheduler.ensureRingChannel(this)
        showOverLock(intent)
        if (Build.VERSION.SDK_INT >= 33) ask(listOf(Manifest.permission.POST_NOTIFICATIONS)) { }

        if (savedInstanceState == null) {
            web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    private fun openInApp(url: String) {
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOverLock(intent)
    }

    /** An alarm that opens the app wakes the screen and shows over the lock screen. */
    private fun showOverLock(i: Intent?) {
        if (i?.getBooleanExtra("ring", false) == true) {
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        AppState.foreground = true
        web.onResume()
        // anything the phone was going to ring is handled by the page now
        Scheduler.stopRinging(this)
        getSystemService(NotificationManager::class.java).cancelAll()
        Scheduler.scheduleNext(this)
        Geo.register(this)
        web.evaluateJavascript("window.nativeGeoHits&&window.nativeGeoHits();window.nativeDone&&window.nativeDone()", null)
    }

    override fun onPause() {
        AppState.foreground = false
        web.evaluateJavascript("window.nativeSyncNow&&window.nativeSyncNow(true)", null)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        abortSpeech()
        web.destroy()
        super.onDestroy()
    }

    private fun ask(perms: List<String>, done: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) { done(perms.associateWith { true }); return }
        permResult = done
        permLauncher.launch(missing.toTypedArray())
    }

    private var geoAsked = false

    /** Location alarms need location, and "all the time" so they work with the app closed. */
    private fun ensureGeoPerms() {
        ask(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { ok ->
            if (!ok.values.any { it }) return@ask
            Geo.register(applicationContext)
            if (Build.VERSION.SDK_INT >= 29 && !Geo.hasBackground(this) && !geoAsked) {
                geoAsked = true
                AlertDialog.Builder(this)
                    .setTitle("Allow location all the time")
                    .setMessage("So location alarms ring when the app is closed, choose “Allow all the time” on the next screen.")
                    .setPositiveButton("Continue") { _, _ ->
                        ask(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { Geo.register(applicationContext) }
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }

    /* ---------------- speech recognition (backs window.SpeechRecognition in the page) ---------------- */

    private var rec: SpeechRecognizer? = null
    private var srActive = false
    private var srCont = false
    private var srInterim = true
    private var srLang = "en-US"
    private var srOffline = false

    private fun jsEvt(type: String, text: String) {
        val q = JSONObject.quote(text)
        web.post { web.evaluateJavascript("window.__srEvt&&window.__srEvt('$type',$q)", null) }
    }

    private fun first(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val srListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(t: Int, p: Bundle?) {}

        override fun onPartialResults(b: Bundle?) {
            if (srActive) first(b)?.let { if (it.isNotBlank()) jsEvt("partial", it) }
        }

        override fun onResults(b: Bundle?) {
            first(b)?.let { if (it.isNotBlank()) jsEvt("final", it) }
            if (srActive && srCont) main.postDelayed({ if (srActive) listen() }, 120) else finishSpeech()
        }

        override fun onError(e: Int) {
            if (!srActive) { finishSpeech(); return }
            when (e) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    if (srCont) main.postDelayed({ if (srActive) listen() }, 150)
                    else { jsEvt("error", "no-speech"); finishSpeech() }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    rec?.cancel()
                    main.postDelayed({ if (srActive) listen() }, 400)
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    jsEvt("error", "network"); finishSpeech()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    jsEvt("error", "not-allowed"); finishSpeech()
                }
                12, 13 -> { jsEvt("error", "language-not-supported"); finishSpeech() } // language not supported / unavailable
                else -> { jsEvt("error", "audio-capture"); finishSpeech() }
            }
        }
    }

    private fun listen() {
        val r = rec ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, srInterim)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, srOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { r.startListening(i) } catch (e: Exception) { jsEvt("error", "audio-capture"); finishSpeech() }
    }

    private fun beginSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jsEvt("error", "service-not-allowed"); jsEvt("end", ""); return
        }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(srListener) }
        srActive = true
        jsEvt("start", "")
        listen()
    }

    private fun finishSpeech() {
        val was = rec != null
        srActive = false
        rec?.destroy()
        rec = null
        if (was) jsEvt("end", "")
    }

    private fun abortSpeech() {
        srActive = false
        rec?.cancel()
        finishSpeech()
    }

    /* ---------------- file saving ---------------- */

    private var saveOut: FileOutputStream? = null
    private var saveTmp: File? = null
    private var saveName = ""
    private var saveMime = ""

    /** Copies the finished temp file into Downloads/Time Tables. Returns where it went, or null. */
    private fun commitFile(src: File, name: String, mime: String): String? {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        // pick the MIME type from the extension so Android does not add ".txt" to ".json"
        val type = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase())
            ?: mime.ifBlank { "application/octet-stream" }
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Time Tables")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
            val out = contentResolver.openOutputStream(uri) ?: return null
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            "Downloads/Time Tables"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            src.copyTo(File(dir, safe), overwrite = true)
            dir.absolutePath
        }
    }

    /** Functions the page can call as window.AndroidBridge.* */
    inner class Bridge {
        /** Opens the system print dialog for the page as it is shown now (the page hides everything but the table). */
        @JavascriptInterface
        fun printPage(title: String) {
            runOnUiThread {
                try {
                    val pm = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
                    val attrs = android.print.PrintAttributes.Builder()
                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4.asLandscape())
                        .build()
                    pm.print(title.ifBlank { "Timetable" }, web.createPrintDocumentAdapter(title.ifBlank { "Timetable" }), attrs)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Printing is not available on this device", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ----- saving files in chunks, so large backups do not need to fit in one string -----
        @JavascriptInterface
        fun saveBegin(name: String, mime: String): Boolean = try {
            saveOut?.close()
            val f = File(cacheDir, "save.tmp")
            saveOut = FileOutputStream(f)
            saveTmp = f
            saveName = name
            saveMime = mime
            true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun saveChunk(b64: String): Boolean = try {
            saveOut!!.write(Base64.decode(b64, Base64.DEFAULT)); true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun saveEnd(): Boolean {
            return try {
                saveOut?.close(); saveOut = null
                val f = saveTmp ?: return false
                val where = commitFile(f, saveName, saveMime)
                f.delete()
                if (where == null) return false
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Saved $saveName to $where", Toast.LENGTH_LONG).show()
                }
                true
            } catch (e: Exception) { false }
        }

        // ----- alarms that work when the app is closed -----
        @JavascriptInterface
        fun setSchedule(json: String) {
            Scheduler.replaceAll(applicationContext, json)
        }

        @JavascriptInterface
        fun notify(title: String, body: String) {
            Scheduler.show(applicationContext, "n" + System.nanoTime(), title, body, false)
        }

        // ----- location alarms that work when the app is closed -----
        @JavascriptInterface
        fun setGeofences(json: String) {
            Geo.replaceAll(applicationContext, json)
            if (json.length > 2 && AppState.foreground) runOnUiThread { ensureGeoPerms() }
        }

        @JavascriptInterface
        fun takeGeoHits(): String = Geo.takeHits(applicationContext)

        // ----- installed apps and the in-app browser -----
        @JavascriptInterface
        fun listApps(): String {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val a = JSONArray()
            pm.queryIntentActivities(i, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
                .forEach { a.put(JSONObject().put("name", it.first).put("pkg", it.second)) }
            return a.toString()
        }

        @JavascriptInterface
        fun launchPkg(pkg: String): Boolean {
            val li = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            runOnUiThread { try { startActivity(li) } catch (e: Exception) { } }
            return true
        }

        @JavascriptInterface
        fun openInApp(url: String) {
            runOnUiThread { this@MainActivity.openInApp(url) }
        }

        // ----- is the phone set up so alarms ring? -----
        @JavascriptInterface
        fun alarmStatus(): String {
            val nm = getSystemService(NotificationManager::class.java)
            val pm = getSystemService(PowerManager::class.java)
            val o = JSONObject()
            o.put("notif", NotificationManagerCompat.from(applicationContext).areNotificationsEnabled())
            o.put("fullScreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
            o.put("battery", pm.isIgnoringBatteryOptimizations(packageName))
            o.put("location", Geo.hasPermission(applicationContext))
            o.put("bgLocation", Geo.hasBackground(applicationContext))
            return o.toString()
        }

        @JavascriptInterface
        fun fixAlarm(which: String) {
            runOnUiThread {
                try {
                    val pkg = Uri.parse("package:$packageName")
                    val i = when (which) {
                        "notif" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        "fullScreen" -> if (Build.VERSION.SDK_INT >= 34)
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (x: Exception) { }
                }
            }
        }

        // ----- scheduled calls / messages / apps -----
        @JavascriptInterface
        fun runAction(json: String) {
            runOnUiThread {
                try {
                    val i = Act.intent(this@MainActivity, JSONObject(json))
                    if (i != null) startActivity(i) else Toast.makeText(this@MainActivity, "Could not open that", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No app can do that", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun takeDone(): String = Scheduler.takeDone(applicationContext)

        // ----- speech recognition -----
        @JavascriptInterface
        fun speechStart(lang: String, continuous: Boolean, interim: Boolean, offline: Boolean) {
            runOnUiThread {
                srLang = lang.ifBlank { "en-US" }
                srCont = continuous
                srInterim = interim
                srOffline = offline
                ask(listOf(Manifest.permission.RECORD_AUDIO)) { ok ->
                    if (ok.values.all { it }) beginSpeech()
                    else { jsEvt("error", "not-allowed"); jsEvt("end", "") }
                }
            }
        }

        @JavascriptInterface
        fun speechStop() {
            runOnUiThread { srActive = false; rec?.stopListening() }
        }

        @JavascriptInterface
        fun speechAbort() {
            runOnUiThread { abortSpeech() }
        }
    }
}
