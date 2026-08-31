package com.sahandservice.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
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

        setupWebView(savedInstanceState ?: intent.getBundleExtra("webState"))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack() && !pageError) {
                    web.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
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
            // (پس از خود-میزبانی فونت‌ها در وب v2.6.1 هیچ وابستگی خارجی http باقی نمانده است)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_LOAD
            userAgentString = "$userAgentString SahandAndroidApp/2.6.1"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // Service Worker (PWA) با رفتار پیش‌فرض WebView کار می‌کند — نیازی به تنظیم اضافه نیست

        web.addJavascriptInterface(FileBridge(), "SahandFiles")

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
                runOnUiThread { request.grant(request.resources) }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleSystemDownload(url, contentDisposition, mimeType)
        }

        if (state != null) web.restoreState(state) else web.loadUrl(serverUrl)
    }

    // ── Downloads ─────────────────────────────────────────────

    private fun handleSystemDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("blob:") || url.startsWith("data:")) return // handled by JS bridge
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
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
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
