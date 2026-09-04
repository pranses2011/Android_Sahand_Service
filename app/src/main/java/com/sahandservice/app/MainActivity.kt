package com.sahandservice.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "sahand_prefs"
        const val KEY_URL = "server_url"
        const val NOTIF_CHANNEL = "sahand_notifications" // v2.9.1
    }

    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private var serverUrl: String = ""
    private var pageError = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // ── Activity result launchers ─────────────────────────────

    private val openFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult
        var result: Array<Uri>? = null
        if (res.resultCode == Activity.RESULT_OK) {
            val dataUri = res.data?.data
            if (dataUri != null) {
                result = arrayOf(dataUri)
            } else {
                val cam = cameraPhotoUri
                if (cam != null) {
                    try {
                        contentResolver.openInputStream(cam)?.close()
                        result = arrayOf(cam)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        cameraPhotoUri = null
        cb.onReceiveValue(result)
    }

    private val geoPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        cb?.invoke(origin ?: "", granted, false)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) saveDirectFile(pending.first, pending.second, pending.third)
        else if (!granted) Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_SHORT).show()
    }

    // v2.9.1 — نتیجهٔ درخواست مجوز نوتیفیکیشن (Android 13+)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(
            this,
            if (granted) R.string.notif_granted else R.string.notif_denied,
            Toast.LENGTH_SHORT
        ).show()
    }

    // v2.9.1 — نتیجهٔ درخواست مجوز میکروفون (ضبط پیام صوتی چت)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingAudioRequest
        pendingAudioRequest = null
        runOnUiThread {
            if (granted) req?.grant(req.resources)
            else req?.deny()
        }
    }

    private var pendingAudioRequest: PermissionRequest? = null

    private var pendingDownload: Triple<String, String, ByteArray>? = null

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_URL, "").orEmpty()

        if (serverUrl.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_Sahand)
        web = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress)

        // v2.9.1 — کانال اعلان + درخواست مجوز نوتیفیکیشن (Android 13+)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        setupWebView(savedInstanceState ?: intent.getBundleExtra("webState"))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageError) {
                    showExitDialog()
                    return
                }
                // v2.9.1 (درخواست کاربر) — منطق بازگشت به وب سپرده می‌شود:
                //   window.__sahandBack() → "exit" (روی داشبورد است → کادر تأیید خروج)
                //                        → "handled" (به داشبورد برگشت)
                //   اگر پل وجود نداشت (خطا/صفحه قدیمی) → رفتار قبلی (goBack/خروج)
                web.evaluateJavascript(
                    "(typeof window.__sahandBack === 'function') ? window.__sahandBack() : 'default'"
                ) { result ->
                    // v2.9.2 — رفع خطای کامپایل: زنجیرهٔ nullable-safe (?.trim()?.trim())
                    val r = result?.trim()?.trim('"') ?: "default"
                    when (r) {
                        "exit" -> runOnUiThread { showExitDialog() }
                        "handled" -> { /* وب خودش به داشبورد برگشت */ }
                        else -> runOnUiThread {
                            // fallback — رفتار قدیمی
                            if (web.canGoBack()) web.goBack() else showExitDialog()
                        }
                    }
                }
            }
        })
    }

    // ── v2.8.7 — تحویل تضمینی آپدیت‌های سرور به اپ ──
    // WebView کش HTTP خودش را نگه می‌دارد؛ اگر سرور بروزرسانی شده باشد
    // (version.json عوض شده) کش HTTP WebView خالی می‌شود تا فایل‌های استاتیک
    // جدید (چانک‌ها/پچ‌ها) حتماً از سرور بیایند.
    // v2.9.7 (گزارش کاربر: «پنل رو آپدیت کردم ولی هنوز نسخهٔ قدیمی را نشان
    // می‌دهد»): (۱) علاوه بر پاک‌کردن کش، صفحه هم بارگذاری مجدد می‌شود —
    // قبلاً فقط کش خالی می‌شد و صفحهٔ قدیمیِ در حافظه می‌ماند تا restart
    // دستی؛ (۲) بررسی دوره‌ای هر ۵ دقیقه (نه فقط هنگام ساخت) — اپ‌های
    // همیشه‌باز هرگز آپدیت سرور را نمی‌دیدند. یک‌بار در هر تغییر نسخه.
    /** v2.9.10 — بارگذاری مجدد «بدون کش» (گزارش کاربر: «موارد جدیدی که به منو
     * اضافه شده مثل گزارش‌گیری سفارشی و مابقی موارد جدید در اپلیکیشن وجود نداره»).
     * ریشه: کش HTTP وب‌ویو (و نه SW) نسخهٔ کهنهٔ index.html/چانک‌ها را نگه می‌داشت —
     * clearCache(true) async است و ممکن است قبل از reload تمام نشود. اکنون:
     *   ۱) cacheMode = LOAD_NO_CACHE (فعال تا پایان بارگذاری)
     *   ۲) clearCache(true)
     *   ۳) reload بعد از ۳۰۰ms (فرصت پاک‌سازی)
     *   ۴) پس از onPageFinished → بازگشت به LOAD_DEFAULT */
    private fun reloadBypassingCache() {
        try { web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE } catch (_: Exception) {}
        try { web.clearCache(true) } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { web.reload() } catch (_: Exception) {}
        }, 300)
    }

    private fun ensureFreshContent(periodic: Boolean = false) {
        try {
            val verUrl = serverUrl.trimEnd('/') + "/version.json"
            Thread {
                try {
                    val conn = URL(verUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", web.settings.userAgentString)
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val serverVersion = org.json.JSONObject(body).optString("version", "")
                    if (serverVersion.isEmpty()) return@Thread
                    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val lastSeen = prefs.getString("last_server_version", null)
                    if (lastSeen != null && lastSeen != serverVersion) {
                        runOnUiThread {
                            // v2.9.10 — بارگذاری مجدد بدون کش (منوهای جدید فوراً ظاهر می‌شوند)
                            reloadBypassingCache()
                        }
                    }
                    prefs.edit().putString("last_server_version", serverVersion).apply()
                } catch (_: Exception) {
                    // آفلاین/خطا — بی‌صدا
                }
            }.start()
        } catch (_: Exception) {}
    }

    /** v2.9.7 — بررسی دوره‌ای نسخهٔ سرور (اپ‌های همیشه‌باز) */
    private val versionCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val versionCheckTask = object : Runnable {
        override fun run() {
            try { ensureFreshContent(periodic = true) } catch (_: Exception) {}
            versionCheckHandler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(state: Bundle?) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            // B-07: منع بارگذاری محتوای مخلوط — روی سرور https هیچ منبع http بارگذاری نمی‌شود
            // (پس از خود-میزبانی فونت‌ها در وب v2.6.1+ هیچ وابستگی خارجی http باقی نمانده است)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString SahandAndroidApp/2.9.10"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // Service Worker (PWA) با رفتار پیش‌فرض WebView کار می‌کند — نیازی به تنظیم اضافه نیست

        web.addJavascriptInterface(FileBridge(), "SahandFiles")
        // v2.9.1 — پل اعلان‌های سیستمی: وب با SahandNative.showNotification اعلان
        // سیستمی اندروید می‌فرستد (WebView از Notification API وب پشتیبانی نمی‌کند)
        web.addJavascriptInterface(NativeBridge(), "SahandNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "intent" || scheme == "whatsapp") {
                    openExternal(url)
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    val host = url.host ?: return false
                    if (host != Uri.parse(serverUrl).host) {
                        openExternal(url)
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                // v2.9.10 — بازگشت حالت کش به پیش‌فرض پس از بارگذاری بدون کش
                try { web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT } catch (_: Exception) {}
                if (!pageError) injectDownloadHook()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    pageError = true
                    progressBar.visibility = View.GONE
                    web.loadUrl("file:///android_asset/error.html?u=${Uri.encode(serverUrl)}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                Toast.makeText(this@MainActivity, R.string.ssl_blocked, Toast.LENGTH_LONG).show()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val gallery = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val types = params.acceptTypes?.filterNotNull()?.filter { it.isNotBlank() }?.toTypedArray()
                    if (!types.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
                }

                val camera = createCameraIntent()

                val chooser = Intent.createChooser(gallery, getString(R.string.choose_file)).apply {
                    if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
                }
                return try {
                    openFile.launch(chooser)
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    geoPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val res = request.resources ?: return@runOnUiThread
                    // v2.9.1 — درخواست میکروفون از وب (ضبط پیام صوتی چت) →
                    // مجوز اندرویدی RECORD_AUDIO هم تضمین می‌شود
                    val needsAudio = res.any { it == "android.webkit.resource.AUDIO_CAPTURE" }
                    if (needsAudio &&
                        Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingAudioRequest = request
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        request.grant(res)
                    }
                }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleSystemDownload(url, contentDisposition, mimeType)
        }

        // v2.8.7 — قبل از بارگذاری: اگر سرور بروزرسانی شده، کش HTTP خالی شود
        ensureFreshContent()
        if (state != null) web.restoreState(state) else web.loadUrl(serverUrl)
    }

    // ── Downloads ─────────────────────────────────────────────

    private fun handleSystemDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            // v2.9.6 — blob:/data: از مسیر JS hook می‌آیند؛ اگر تا اینجا رسیده
            // یعنی هوک نداد (مثلاً window.open) → مستقیم از صفحه/رشته بخوان و ذخیره کن
            if (url.startsWith("blob:")) {
                runOnUiThread { saveBlobFromWebView(url, contentDisposition, mimeType) }
                return
            }
            if (url.startsWith("data:")) {
                saveDataUrlDirect(url)
                return
            }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null) addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", web.settings.userAgentString)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val name = URLDecoder.decode(
                    try {
                        URLUtilGuessFileName(url, contentDisposition, mimeType)
                    } catch (_: Exception) {
                        "sahand-download"
                    }, "UTF-8"
                )
                setTitle(name)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed) + ": " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun URLUtilGuessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
    }

    /** v2.9.6 — ذخیرهٔ blob: با خواندن از Blob ثبت‌شده در صفحه (Promise → evaluateJavascript) */
    private fun saveBlobFromWebView(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fallbackName = URLDecoder.decode(
                try {
                    URLUtilGuessFileName(blobUrl, contentDisposition, mimeType)
                } catch (_: Exception) {
                    "sahand-download"
                }, "UTF-8"
            )
            val js = "(function(){try{var b=window.__sahandBlobs&&window.__sahandBlobs['" + blobUrl +
                "'];if(!b)return null;return new Promise(function(res){var r=new FileReader;" +
                "r.onloadend=function(){res(JSON.stringify({mime:b.type||'application/octet-stream',b64:String(r.result).split(',')[1]||''}))};" +
                "r.onerror=function(){res(null)};r.readAsDataURL(b)})}catch(e){return null}})()"
            web.evaluateJavascript(js) { result ->
                runOnUiThread {
                    try {
                        if (result == null || result == "null" || result.length < 10) {
                            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        val obj = org.json.JSONObject(result)
                        val mime = obj.optString("mime", "application/octet-stream").ifBlank { "application/octet-stream" }
                        val b64 = obj.optString("b64", "")
                        if (b64.isBlank()) {
                            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        saveDirectFile(fallbackName, mime, Base64.decode(b64, Base64.DEFAULT))
                    } catch (_: Exception) {
                        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed) + ": " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /** v2.9.6 — ذخیرهٔ مستقیم data: URL (بدون جاوااسکریپت) */
    private fun saveDataUrlDirect(dataUrl: String) {
        try {
            val commaIdx = dataUrl.indexOf(',')
            if (commaIdx < 0) return
            val header = dataUrl.substring(5, commaIdx) // after "data:"
            val mime = if (header.endsWith(";base64")) header.removeSuffix(";base64")
                .ifEmpty { "application/octet-stream" } else "text/plain"
            val b64 = dataUrl.substring(commaIdx + 1)
            val bytes = if (header.endsWith(";base64"))
                Base64.decode(b64, Base64.DEFAULT)
            else b64.toByteArray(Charsets.UTF_8)
            val name = "sahand-" + System.currentTimeMillis() + guessExtFromMime(mime)
            runOnUiThread { saveDirectFile(name, mime, bytes) }
        } catch (_: Exception) {
            runOnUiThread { Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun guessExtFromMime(mime: String): String {
        return when {
            mime.startsWith("image/png") -> ".png"
            mime.startsWith("image/jpeg") -> ".jpg"
            mime.startsWith("image/webp") -> ".webp"
            mime.startsWith("image/gif") -> ".gif"
            mime.startsWith("text/csv") || mime == "text/comma-separated-values" -> ".csv"
            mime.contains("json") -> ".json"
            mime.startsWith("text/") -> ".txt"
            mime.startsWith("application/zip") -> ".zip"
            mime.contains("pdf") -> ".pdf"
            mime.startsWith("audio/") -> ".webm"
            else -> ".bin"
        }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(getExternalFilesDir(null), "camera")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraPhotoUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openConnection(urlStr: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val cookies = CookieManager.getInstance().getCookie(serverUrl)
        if (cookies != null) conn.setRequestProperty("Cookie", cookies)
        conn.setRequestProperty("User-Agent", web.settings.userAgentString)
        return conn
    }

    // ── JS download hook (blob: + data: exports) ─────────────

    private fun injectDownloadHook() {
        val js = """
        (function(){
          if (window.__sahandDlHooked) return; window.__sahandDlHooked = true;
          try {
            var origCreate = URL.createObjectURL.bind(URL);
            window.__sahandBlobs = {};
            URL.createObjectURL = function(blob){
              var u = origCreate(blob);
              try { window.__sahandBlobs[u] = blob; } catch(e){}
              return u;
            };
            document.addEventListener('click', function(e){
              var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
              if (!a) return;
              var href = a.href || '';
              try {
                if (href.indexOf('blob:') === 0) {
                  var blob = window.__sahandBlobs[href];
                  if (blob) {
                    e.preventDefault(); e.stopPropagation();
                    var name = a.getAttribute('download') || 'sahand-export.bin';
                    var reader = new FileReader();
                    reader.onloadend = function(){
                      var b64 = String(reader.result).split(',')[1] || '';
                      SahandFiles.saveBase64(name, blob.type || 'application/octet-stream', b64);
                    };
                    reader.readAsDataURL(blob);
                  }
                } else if (href.indexOf('data:') === 0) {
                  e.preventDefault(); e.stopPropagation();
                  var dn = a.getAttribute('download') || 'sahand-export.bin';
                  SahandFiles.saveDataUrl(dn, href);
                }
              } catch(err){}
            }, true);
          } catch(e){}
        })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    inner class FileBridge {
        @JavascriptInterface
        fun saveBase64(name: String, mime: String, base64: String) {
            try {
                val clean = base64.replace("\n", "").replace(" ", "")
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                runOnUiThread { saveDirectFile(name, mime, bytes) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveDataUrl(name: String, dataUrl: String) {
            try {
                val commaIdx = dataUrl.indexOf(',')
                if (commaIdx < 0) return
                val header = dataUrl.substring(5, commaIdx) // after "data:"
                val mime = if (header.endsWith(";base64")) header.removeSuffix(";base64")
                           .ifEmpty { "application/octet-stream" } else "text/plain"
                val b64 = dataUrl.substring(commaIdx + 1)
                val bytes = if (header.endsWith(";base64"))
                    Base64.decode(b64, Base64.DEFAULT)
                else b64.toByteArray(Charsets.UTF_8)
                runOnUiThread { saveDirectFile(name, mime, bytes) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread {
                pageError = false
                web.loadUrl(serverUrl)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveDirectFile(name: String, mime: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sanitize(name))
                    put(MediaStore.Downloads.MIME_TYPE, if (mime.isBlank()) "application/octet-stream" else mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDownload = Triple(name, mime, bytes)
                    storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return
                }
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, sanitize(name))).use { it.write(bytes) }
            }
            Toast.makeText(this, getString(R.string.saved_to_downloads, sanitize(name)), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifEmpty { "sahand-file" }
    }

    // ── State ─────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) {
            web.onPause()
            CookieManager.getInstance().flush()
        }
        // v2.9.7 — بررسی دوره‌ای نسخهٔ سرور متوقف (اپ در پس‌زمینه)
        versionCheckHandler.removeCallbacks(versionCheckTask)
        // v2.9.6 — اعلان‌ها در پس‌زمینه: polling سبک تا وقتی اپ در پس‌زمینه است
        startBackgroundNotifyPolling()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        // v2.9.7 — بازگشت به اپ: فوراً نسخه چک کن + هر ۵ دقیقه (تحویل
        // تضمینی آپدیت سرور برای اپ‌های همیشه‌باز)
        try { ensureFreshContent(periodic = true) } catch (_: Exception) {}
        versionCheckHandler.removeCallbacks(versionCheckTask)
        versionCheckHandler.postDelayed(versionCheckTask, 5 * 60 * 1000L)
        // v2.9.6 — وب‌اپ خودش polling را برمی‌گرداند
        stopBackgroundNotifyPolling()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    // ── v2.9.1 — Notification channel + System notifications bridge ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
        val ch = NotificationChannel(
            NOTIF_CHANNEL,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_desc)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    /** درخواست مجوز اعلان روی Android 13+ (یک‌بار در نخستین اجرا) */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** نمایش اعلان سیستمی — id عددی از hash تگ برای به‌روزرسانی هم‌نوع‌ها */
    private fun postSystemNotification(title: String, body: String, tag: String, url: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("notif_url", url)
            }
            val pending = PendingIntent.getActivity(
                this, tag.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setVibrate(longArrayOf(200, 100, 200))
                .setContentIntent(pending)
                .build()
            nm.notify(tag.hashCode(), notif)
        } catch (_: Exception) {
            // fail-soft
        }
    }

    /**
     * v2.9.1 — SahandNative: پل JS ← اندروید
     * وب‌اپ (system-notify.ts) این متدها را صدا می‌زند تا اعلان سیستمی نمایش
     * داده شود؛ در مرورگر همین منطق با Service Worker انجام می‌شود.
     */
    inner class NativeBridge {
        @JavascriptInterface
        fun showNotification(title: String, body: String, tag: String, url: String) {
            runOnUiThread { postSystemNotification(title, body, tag, url) }
        }

        @JavascriptInterface
        fun isNotificationEnabled(): Boolean {
            return if (Build.VERSION.SDK_INT >= 33)
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            else true
        }

        @JavascriptInterface
        fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread { notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }

    // ── v2.9.6 — اعلان‌های پس‌زمینه (polling سبک وقتی اپ pause شده) ──
    // WebView در pause تایمرهای JS را متوقف می‌کند؛ برای اینکه اعلان‌ها
    // «وقتی اپ در پس‌زمینه است» هم برسند، اپ خودش هر ۶۰ ثانیه شمارِ
    // خوانده‌نشده‌ها را از سرور می‌خواند (با کوکی نشست وب) و در صورت
    // افزایش، اعلان سیستمی می‌فرستد.

    private var bgPollHandler: android.os.Handler? = null
    private var bgPollRunnable: Runnable? = null
    private var bgChatUnread = -1
    private var bgNotifUnread = -1
    private var bgUserPanel: String? = null
    private var bgTechId: String? = null

    private fun startBackgroundNotifyPolling() {
        if (bgPollHandler != null) return
        if (!::web.isInitialized) return
        try {
            // هویت کاربر از localStorage (asm-auth) — برای endpoint اعلان‌ها
            web.evaluateJavascript(
                "(function(){try{var a=JSON.parse(localStorage.getItem('asm-auth')||'null');" +
                    "return a?JSON.stringify({panel:(a.state&&a.state.panel)||'',techId:(a.state&&a.state.technicianId)||''}):null}" +
                    "}catch(e){return null}})()"
            ) { res ->
                try {
                    if (res != null && res != "null" && res.length > 4) {
                        val o = org.json.JSONObject(res)
                        bgUserPanel = o.optString("panel", "").ifBlank { null }
                        bgTechId = o.optString("techId", "").ifBlank { null }
                    }
                } catch (_: Exception) {}
            }
            bgChatUnread = -1
            bgNotifUnread = -1
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            val r = object : Runnable {
                override fun run() {
                    pollUnreadInBackground()
                    h.postDelayed(this, 60_000)
                }
            }
            h.postDelayed(r, 20_000)
            bgPollHandler = h
            bgPollRunnable = r
        } catch (_: Exception) {}
    }

    private fun stopBackgroundNotifyPolling() {
        try {
            bgPollRunnable?.let { r -> bgPollHandler?.removeCallbacks(r) }
        } catch (_: Exception) {}
        bgPollHandler = null
        bgPollRunnable = null
    }

    /** خواندن متن پاسخ GET با کوکی نشست وب (روی نخ جدا) */
    private fun fetchBodyWithSession(path: String): String? {
        return try {
            val conn = openConnection(serverUrl.trimEnd('/') + path, "GET")
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pollUnreadInBackground() {
        Thread {
            try {
                // ۱) پیام‌های چت خوانده‌نشدهٔ «من» (همهٔ گفتگوها)
                val chatBody = fetchBodyWithSession("/api/chat?unread=mine")
                if (chatBody != null) {
                    val cu = try {
                        org.json.JSONObject(chatBody).optInt("unreadCount", 0)
                    } catch (_: Exception) { -1 }
                    if (cu >= 0) {
                        val first = bgChatUnread < 0
                        if (!first && cu > bgChatUnread) {
                            postSystemNotification(
                                "پیام جدید",
                                "${cu - bgChatUnread} پیام خوانده‌نشده دارید — برای مشاهده چت را باز کنید",
                                "chat-unread-bg", "/"
                            )
                        }
                        bgChatUnread = cu
                    }
                }
                // ۲) اعلان‌های سرویس‌کار / سرویس‌های در انتظار نمایندگی
                val isTech = bgUserPanel == "technician" && !bgTechId.isNullOrEmpty()
                val path = if (isTech) "/api/notification?technicianId=$bgTechId" else "/api/entity?type=service&status=pending"
                val body = fetchBodyWithSession(path)
                if (body != null) {
                    val n = try {
                        val arr = org.json.JSONArray(body)
                        if (isTech) {
                            var c = 0
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                if (!o.optBoolean("isRead", false)) c++
                            }
                            c
                        } else {
                            arr.length()
                        }
                    } catch (_: Exception) { -1 }
                    if (n >= 0) {
                        val first = bgNotifUnread < 0
                        if (!first && n > bgNotifUnread) {
                            if (isTech) {
                                postSystemNotification("اعلان جدید", "${n - bgNotifUnread} اعلان خوانده‌نشده جدید دارید", "notif-bg", "/")
                            } else {
                                postSystemNotification("سرویس جدید", "${n - bgNotifUnread} سرویس جدید در انتظار بررسی", "notif-bg", "/")
                            }
                        }
                        bgNotifUnread = n
                    }
                }
            } catch (_: Exception) {
                // fail-soft
            }
        }.start()
    }

    // Exit dialog with settings shortcut
    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_exit_title)
            .setMessage(R.string.app_exit_msg)
            .setPositiveButton(R.string.exit) { _, _ -> finish() }
            .setNegativeButton(R.string.settings_server) { _, _ ->
                startActivity(Intent(this, SetupActivity::class.java))
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }
}
