package fi.jss.pwa

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import fi.jss.pwa.core.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shows one site fullscreen. In the launcher app each site runs in its own task (see documentLaunchMode in
 * its manifest) and is picked by the intent's pwa://site/<id> URI. In a generated per-site app it is the
 * launcher activity and the site comes from assets/site.json.
 */
class WebAppActivity : Activity() {
    private lateinit var site: Site
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var popup: View? = null
    private var popupWebView: WebView? = null
    private var splash: View? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: ((Set<String>) -> Unit)? = null
    private var iconLookupDone = false
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = Site.idFromUri(intent.data)?.let { SiteStore.get(this, it) } ?: StandaloneSite.load(this)
        if (loaded == null) {
            Toast.makeText(this, R.string.site_missing, Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }
        site = loaded

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // With edge-to-edge layout the system no longer resizes the window for the keyboard,
        // so pad the content by the keyboard height ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        webView = WebView(this)
        configure(webView)
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        showSplash()
        setContentView(root)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(site.url)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        // Settings may have been edited while this task was in the background.
        SiteStore.get(this, site.id)?.let { site = it } ?: run { finishAndRemoveTask(); return }
        webView.settings.userAgentString = userAgent(webView)
        applyWindowMode()
        updateTaskDescription()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (!::webView.isInitialized) return
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            popupWebView?.destroy()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the keyboard can bring the bars back; hide them again once we have focus.
        if (hasFocus && ::webView.isInitialized) applyWindowMode()
    }

    // ---------------------------------------------------------------- window

    @Suppress("DEPRECATION")
    private fun applyWindowMode() {
        if (site.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val hide = site.fullscreen || customView != null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!hide)
            val c = window.insetsController ?: return
            if (hide) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (hide) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            } else 0
        }
    }

    @Suppress("DEPRECATION")
    private fun updateTaskDescription() {
        setTaskDescription(ActivityManager.TaskDescription(site.name, SiteIcons.taskIcon(this, site)))
    }

    /**
     * Covers the page with the site icon until it first paints, instead of a blank white WebView. The launcher
     * app's system splash has no icon (see values-v31/themes.xml), so this also acts as the site's splash.
     */
    private fun showSplash() {
        val size = (96 * resources.displayMetrics.density).toInt()
        val view = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            isClickable = true
            addView(ImageView(context).apply { setImageBitmap(SiteIcons.display(context, site, size)) },
                FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = view
        main.postDelayed(::hideSplash, 8000) // never hide the page for long if it is slow or fails
    }

    private fun hideSplash() {
        val view = splash ?: return
        splash = null
        view.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(view) }
    }

    // ---------------------------------------------------------------- web view setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent(wv)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.setBackgroundColor(Color.WHITE)
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.addJavascriptInterface(DownloadBridge(), "PwaDownload")
        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(wv, url, ua, disposition, mime) }
    }

    /**
     * WebView's default user agent marks itself as a WebView ("; wv", "Version/4.0"), which makes
     * Google sign-in and some sites refuse to work. Present a regular Chrome user agent instead.
     */
    private fun userAgent(wv: WebView): String {
        var ua = WebSettings.getDefaultUserAgent(wv.context)
            .replace("; wv)", ")")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
        if (site.desktopMode) {
            ua = ua.replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
        }
        return ua
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (site.openExternalInBrowser && request.isForMainFrame && !isSameSite(uri)) {
                        openExternal(uri); true
                    } else false
                }
                "about", "blob", "data", "javascript" -> false
                "intent" -> { openIntentUri(view, uri.toString()); true }
                else -> { openExternal(uri); true }
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (view === webView) hideSplash()
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            if (view === webView && !iconLookupDone && isSameSite(Uri.parse(url))) {
                iconLookupDone = true
                lookupIcon(view)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed to free memory: start over with a fresh WebView.
            if (view === webView) recreate() else closePopup()
            return true
        }
    }

    private fun isSameSite(uri: Uri): Boolean {
        fun base(h: String) = h.lowercase().removePrefix("www.")
        val home = base(site.host)
        val host = base(uri.host ?: return false)
        return host == home || host.endsWith(".$home") || home.endsWith(".$host")
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openIntentUri(view: WebView, uri: String) {
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } catch (e: Exception) {
            return
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) view.loadUrl(fallback)
            else Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            if (view === webView && SiteIcons.saveIfBetter(this@WebAppActivity, site.id, icon)) iconChanged()
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val intent = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                startActivityForResult(intent, REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            openPopup(resultMsg)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popupWebView) closePopup()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post { handlePermissionRequest(request) }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            requestAndroidPermissions(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                callback.invoke(origin, granted.isNotEmpty(), false)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            hideCustomView()
            customView = view
            customViewCallback = callback
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            applyWindowMode()
        }

        override fun onHideCustomView() = hideCustomView()
    }

    private fun hideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        applyWindowMode()
    }

    // ---------------------------------------------------------------- popups (OAuth windows etc.)

    private fun openPopup(resultMsg: Message) {
        closePopup()
        val pwv = WebView(this)
        configure(pwv)
        pwv.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
            override fun onReceivedTitle(view: WebView, title: String?) {
                (popup?.findViewWithTag<TextView>("title"))?.text = title
            }
        }
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(32, 33, 36))
            val pad = (12 * density).toInt()
            setPadding(pad, 0, 0, 0)
            addView(TextView(context).apply {
                tag = "title"
                setTextColor(Color.WHITE)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "✕"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = getString(R.string.close)
                setOnClickListener { closePopup() }
            }, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pwv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popup = container
        popupWebView = pwv
        (resultMsg.obj as WebView.WebViewTransport).webView = pwv
        resultMsg.sendToTarget()
    }

    private fun closePopup() {
        popup?.let { root.removeView(it) }
        popupWebView?.destroy()
        popup = null
        popupWebView = null
    }

    // ---------------------------------------------------------------- back

    @Deprecated("Used below Android 13; newer versions use OnBackInvokedCallback")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        val pwv = popupWebView
        when {
            customView != null -> hideCustomView()
            pwv != null -> if (pwv.canGoBack()) pwv.goBack() else closePopup()
            webView.canGoBack() -> webView.goBack()
            else -> showMenu()
        }
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.menu_reload), getString(R.string.menu_home),
            getString(R.string.menu_settings), getString(R.string.menu_exit),
        )
        AlertDialog.Builder(this)
            .setTitle(site.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> webView.loadUrl(site.url)
                    2 -> SiteEditor.show(this, site) { saved ->
                        val urlChanged = saved.url != site.url
                        site = saved
                        webView.settings.userAgentString = userAgent(webView)
                        applyWindowMode()
                        updateTaskDescription()
                        if (urlChanged) webView.loadUrl(site.url)
                    }
                    3 -> finishAndRemoveTask()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- files, permissions

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) return super.onActivityResult(requestCode, resultCode, data)
        val cb = fileCallback ?: return
        fileCallback = null
        if (resultCode != RESULT_OK || data == null) return cb.onReceiveValue(null)
        val clip = data.clipData
        val uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        cb.onReceiveValue(uris)
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val needed = mutableSetOf<String>()
        request.resources.forEach {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed += Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed += Manifest.permission.RECORD_AUDIO
            }
        }
        requestAndroidPermissions(needed) { granted ->
            val allowed = request.resources.filter {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA in granted
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO in granted
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                    else -> false
                }
            }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }
    }

    /** Requests Android runtime permissions and calls [done] with the ones that are granted. */
    private fun requestAndroidPermissions(perms: Set<String>, done: (Set<String>) -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return done(perms)
        pendingPermission = { granted -> done(perms.filter { it in granted || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()) }
        requestPermissions(missing.toTypedArray(), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMISSION) return
        val granted = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED }.toSet()
        pendingPermission?.invoke(granted)
        pendingPermission = null
    }

    // ---------------------------------------------------------------- downloads

    private fun download(wv: WebView, url: String, ua: String?, disposition: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> {
                // Normally caught by DOWNLOAD_HOOK_JS before it gets here; this covers synthetic click events.
                wv.evaluateJavascript("window.__pwaSave && window.__pwaSave(${JSONObject.quote(url)}, '')", null)
            }
            url.startsWith("data:") -> DownloadBridge().save(url, "", mime.orEmpty())
            else -> {
                val name = URLUtil.guessFileName(url, disposition, mime)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", ua ?: wv.settings.userAgentString)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                try {
                    getSystemService(DownloadManager::class.java).enqueue(req)
                    Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Receives blob:/data: downloads from page JavaScript as data URLs and writes them to Downloads. */
    inner class DownloadBridge {
        @JavascriptInterface
        fun save(dataUrl: String, suggestedName: String, blobMime: String) {
            thread {
                val ok = try {
                    val comma = dataUrl.indexOf(',')
                    val header = dataUrl.substring(5, comma) // after "data:"
                    val isBase64 = header.endsWith(";base64")
                    val mime = header.substringBefore(';').ifEmpty { blobMime }.ifEmpty { "application/octet-stream" }
                    val payload = dataUrl.substring(comma + 1)
                    val bytes = if (isBase64) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                    val name = fileName(suggestedName, mime)
                    writeToDownloads(name, mime, bytes)
                    main.post { Toast.makeText(this@WebAppActivity, getString(R.string.download_saved, name), Toast.LENGTH_SHORT).show() }
                    true
                } catch (e: Exception) {
                    false
                }
                if (!ok) main.post { Toast.makeText(this@WebAppActivity, R.string.download_failed, Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface
        fun failed(message: String) {
            main.post { Toast.makeText(this@WebAppActivity, R.string.download_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun fileName(suggested: String, mime: String): String {
        val clean = suggested.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        if (clean.isNotEmpty()) return clean
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext != null) "download.$ext" else "download"
    }

    private fun writeToDownloads(name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    // ---------------------------------------------------------------- icons

    /** Finds the largest icon the site declares (link tags and web app manifest) and caches it. */
    private fun lookupIcon(view: WebView) {
        val existing = SiteIcons.load(this, site.id)
        if (existing != null && existing.width >= 192) return
        val ua = view.settings.userAgentString
        view.evaluateJavascript(ICON_FINDER_JS) { result ->
            val json = try { JSONObject(JSONArray("[$result]").getString(0)) } catch (e: Exception) { return@evaluateJavascript }
            val siteId = site.id
            thread {
                val candidates = mutableListOf<Pair<String, Int>>()
                json.optJSONArray("links")?.let { arr ->
                    for (i in 0 until arr.length()) arr.getJSONObject(i).let { candidates += it.getString("href") to it.getInt("size") }
                }
                json.optString("manifest").takeIf { it.isNotEmpty() }?.let { candidates += manifestIcons(it, ua) }
                for ((href, _) in candidates.sortedByDescending { it.second }) {
                    val bmp = SiteIcons.download(href, ua, CookieManager.getInstance().getCookie(href)) ?: continue
                    if (SiteIcons.saveIfBetter(this, siteId, bmp)) main.post { iconChanged() }
                    break
                }
            }
        }
    }

    private fun manifestIcons(manifestUrl: String, ua: String): List<Pair<String, Int>> = try {
        val conn = URL(manifestUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", ua)
        CookieManager.getInstance().getCookie(manifestUrl)?.let { conn.setRequestProperty("Cookie", it) }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val icons = JSONObject(text).optJSONArray("icons") ?: JSONArray()
        (0 until icons.length()).mapNotNull { i ->
            val icon = icons.getJSONObject(i)
            val src = icon.optString("src").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (icon.optString("type") == "image/svg+xml" || src.substringBefore('?').endsWith(".svg")) return@mapNotNull null
            val size = Regex("(\\d+)x\\d+").findAll(icon.optString("sizes")).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
            URL(URL(manifestUrl), src).toString() to size
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun iconChanged() {
        updateTaskDescription()
        Shortcuts.updatePinned(this, site)
    }

    companion object {
        private const val REQ_FILE = 1
        private const val REQ_PERMISSION = 2

        /**
         * Pages save files by clicking an <a download href="blob:..."> link. WebView cannot download blob:
         * URLs itself, so read the blob in the page while it is still alive and hand it to [DownloadBridge].
         */
        private val DOWNLOAD_HOOK_JS = """
            (function () {
              if (window.__pwaSave) return;
              function save(href, name) {
                fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                  var fr = new FileReader();
                  fr.onload = function () { PwaDownload.save(fr.result, name || '', b.type || ''); };
                  fr.readAsDataURL(b);
                }).catch(function (e) { PwaDownload.failed(String(e)); });
              }
              function handle(a) {
                if (!a || !a.href || !a.hasAttribute('download')) return false;
                if (a.href.indexOf('blob:') !== 0 && a.href.indexOf('data:') !== 0) return false;
                save(a.href, a.getAttribute('download'));
                return true;
              }
              var click = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (handle(this)) return;
                return click.apply(this, arguments);
              };
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest && e.target.closest('a');
                if (handle(a)) { e.preventDefault(); e.stopImmediatePropagation(); }
              }, true);
              window.__pwaSave = save;
            })();
        """.trimIndent()

        /** Returns JSON: {links: [{href, size}], manifest: url}. SVG icons are skipped (BitmapFactory can't decode them). */
        private val ICON_FINDER_JS = """
            (function () {
              var links = [];
              document.querySelectorAll('link[rel~="icon"], link[rel~="apple-touch-icon"], link[rel~="apple-touch-icon-precomposed"]').forEach(function (l) {
                if (l.type === 'image/svg+xml' || /\.svg(\?|$)/i.test(l.href)) return;
                var m = (l.getAttribute('sizes') || '').match(/(\d+)x\d+/);
                links.push({ href: l.href, size: m ? +m[1] : (/apple-touch/.test(l.rel) ? 180 : 32) });
              });
              links.push({ href: location.origin + '/apple-touch-icon.png', size: 179 });
              var mf = document.querySelector('link[rel="manifest"]');
              return JSON.stringify({ links: links, manifest: mf ? mf.href : '' });
            })();
        """.trimIndent()
    }
}
