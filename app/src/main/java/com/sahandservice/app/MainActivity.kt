package com.sahandservice.app

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * MainActivity — اپ اندروید سهند سرویس (WebView پنل + قابلیت‌های بومی)
 *
 * v2.11.0 — بازسازی کامل سورس (نسخه‌های قبل فقط APK بودند) + اصلاحات:
 *  • رفع «هیدر زیر نوار اعلان» (edge-to-edge اندروید ۱۵ → padding نوارها)
 *  • اعلان‌های سیستمی مقاوم (درخواست مجوز پس از نخستین تعامل + هشدار + کانال HIGH)
 *  • دیالوگ بروزرسانی با فهرست تغییرات اسکرول‌شونده (حداکثر ۶۰٪ صفحه)
 *  • نصب خودکار بروزرسانی (android-app.json + DownloadManager + نصب‌کننده)
 *  • ذخیرهٔ فایل‌های خروجی (blob / data-url / دانلود سیستمی / MediaStore)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "sahand_prefs"
        const val KEY_URL = "server_url"
        const val NOTIF_CHANNEL = "sahand_notifications"
        const val APK_MIME = "application/vnd.android.package-archive"
    }

    private var serverUrl: String = ""
    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private var pageError = false

    // ── انتخاب فایل / دوربین ──
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    // ═══ v2.11.0 ═══
    private var bgPollHandler: Handler? = null
    private var bgPollRunnable: Runnable? = null
    private var bgUserPanel: String? = null
    private var bgTechId: String? = null

    // ── v2.11.3 — callback جغرافیای WebView (پس از دادن مجوز) ──
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    // ── v2.11.3 — گیت دسترسی‌ها + دیپ‌لینک نوتیف ──
    private var gateView: ScrollView? = null
    private var pendingNotifUrl: String? = null

    // ── v2.11.5 — تکه‌های انتقال فایل (item 4) + شروع سرد → داشبورد (item 22) ──
    private val saveChunks = sortedMapOf<Int, String>()
    private var coldStartNavDone = false

    // ── بروزرسانی خودکار ──
    private val versionCheckHandler = Handler(Looper.getMainLooper())
    private val versionCheckTask = object : Runnable {
        override fun run() {
            try { checkAppUpdate(false) } catch (_: Exception) {}
            versionCheckHandler.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }
    private var updateDialogShownFor = ""
    private var pendingApkDownloadId = -1L
    private val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == pendingApkDownloadId && pendingApkDownloadId > 0) installDownloadedApk(id)
        }
    }

    // ═════════════════════ onCreate ═════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverUrl = getSharedPreferences(PREFS, 0).getString(KEY_URL, "") ?: ""
        if (serverUrl.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_Sahand)

        web = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress)

        /* ═══ v2.11.0 — رفع باگ «هیدر زیر نوار اعلان» ═══
         * روی targetSdk 35 (اندروید ۱۵) edge-to-edge اجباری است و رنگ
         * statusBarColor نادیده گرفته می‌شود → محتوای WebView زیر نوار
         * وضعیت/ناوبری می‌رود. padding نوارهای سیستمی + بریدگی نمایشگر
         * روی نمای ریشه اعمال می‌شود تا هیدر پنل همیشه زیر نوار دیده شود. */
        applySystemBarInsets()

        createNotificationChannel()
        /* v2.11.0 — درخواست مجوز اعلان دیگر در onCreate (قبل از آماده شدن
         * رابط) نیست؛ پس از ۲ ثانیه و در نخستین تعامل کاربر پرسیده می‌شود
         * (روی برخی دستگاه‌ها دیالوگِ زودهنگام خودکار بسته می‌شد و کاربر
         * بدون اطلاع رد کرده بود → «اعلان‌ها از کار افتاده بودند»). */
        Handler(Looper.getMainLooper()).postDelayed({
            requestNotificationPermissionIfNeeded(askIfNeverDenied = true)
        }, 2000L)

        // v2.11.0 — استیت WebView از intent (دیپ‌لینک نوتیف) هم قابل بازیابی است
        val savedState = savedInstanceState ?: intent.getBundleExtra("webState")
        setupWebView(savedState)

        /* v2.11.3 (درخواست ۳ کاربر) — پیش از باز شدن برنامه، همهٔ دسترسی‌های
         * لازم بررسی/فعال می‌شوند: میکروفون (چت صوتی)، موقعیت مکانی (نقشه)،
         * اعلان‌ها، دوربین (عکس دستگاه/چت) و ذخیرهٔ فایل (اندروید قدیمی).
         * اگر همه داده شده باشند، گیت نمایش داده نمی‌شود (شروع سریع). */
        maybeShowPermissionGate()

        /* v2.11.3 (درخواست ۲ کاربر) — دیپ‌لینک نوتیف: URL در intent (کلیک
         * روی اعلان هنگام شروع سرد) ذخیره و پس از بارگذاری صفحه اعمال می‌شود */
        readNotifNavIntent(intent)

        ContextCompat.registerReceiver(
            this, apkDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try { checkAppUpdate(false) } catch (_: Exception) {}
        versionCheckHandler.postDelayed(versionCheckTask, 60 * 1000L)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageError) { showExitDialog(); return }
                web.evaluateJavascript(
                    "(typeof window.__sahandBack === 'function') ? window.__sahandBack() : 'default'"
                ) { raw ->
                    val v = raw?.trim()?.trim('"') ?: "default"
                    runOnUiThread {
                        when (v) {
                            "exit" -> showExitDialog()
                            "handled" -> Unit
                            else -> if (web.canGoBack()) web.goBack() else showExitDialog()
                        }
                    }
                }
            }
        })

        // نخستین تعامل کاربر → درخواست مجوز اعلان (اگر هنوز داده نشده)
        window.decorView.setOnTouchListener { _, _ ->
            window.decorView.setOnTouchListener(null)
            requestNotificationPermissionIfNeeded(askIfNeverDenied = true)
            false
        }
    }

    /** v2.11.0 — padding نوارهای سیستمی (رفع فول‌اسکرین/هیدر زیر نوتیفیکیشن) */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root) ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // آیکون‌های روشن نوار وضعیت روی زمینهٔ تیره
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, root).isAppearanceLightNavigationBars = false
        window.statusBarColor = Color.parseColor("#0f172a")
        window.navigationBarColor = Color.parseColor("#0f172a")
    }

    // ═════════════════════ WebView ═════════════════════
    private fun setupWebView(state: Bundle?) {
        val settings: WebSettings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = settings.userAgentString + " SahandAndroidApp/2.11.1"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(FileBridge(), "SahandFiles")
        web.addJavascriptInterface(NativeBridge(), "SahandNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme in listOf("whatsapp", "tel", "sms", "mailto", "intent")) {
                    openExternal(uri)
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host ?: return false
                    val serverHost = Uri.parse(serverUrl).host ?: return false
                    if (host != serverHost) {
                        openExternal(uri)
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                try { web.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (_: Exception) {}
                if (!pageError) {
                    injectDownloadHook()
                    /* v2.11.5 (item 22) — شروع سرد اپ همیشه با داشبورد باز می‌شود
                     * («در هنگام ورود به پنل همیشه داشبورد را باز کند») — فقط یک
                     * بار در هر اجرای اپ؛ رفرش‌های درون‌صفحه تأثیر نمی‌گیرند. */
                    if (!coldStartNavDone) {
                        coldStartNavDone = true
                        try {
                            web.evaluateJavascript(
                                "(function(){try{var a=JSON.parse(localStorage.getItem('asm-auth')||'null');" +
                                "if(a&&a.state&&a.state.userId&&window.__sahandSetPageRaw){" +
                                "window.__sahandSetPageRaw('dashboard',{});}}catch(e){}})()", null)
                        } catch (_: Exception) {}
                    }
                    // v2.11.0 — poll اعلان‌ها با هر بارگذاری صفحه تازه می‌شود
                    startBackgroundNotifyPolling()
                    // v2.11.1 — ثبت دستگاه در سرور لایسنس (مدیریت لایسنس ← دستگاه‌ها)
                    sendAppHeartbeatIfNeeded()
        PushClient.ensureSetup(this@MainActivity) /* v2.11.4 — راه‌اندازی پوش FCM */
                    // v2.11.3 — ناوبری دیپ‌لینک نوتیف پس از آماده شدن صفحه
                    if (gateView == null) applyPendingNotifUrl()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    pageError = true
                    progressBar.visibility = View.GONE
                    web.loadUrl("file:///android_asset/error.html?u=" + Uri.encode(serverUrl))
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                Toast.makeText(this@MainActivity, R.string.ssl_blocked, Toast.LENGTH_LONG).show()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                progressBar.progress = newProgress
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                /* v2.11.3 — پیش از این بدون توجه به مجوزِ خودِ اپ، به صفحه
                 * grant داده می‌شد → WebView داخلی خطا می‌خورد و موقعیت هرگز
                 * نمی‌آمد. حالا اگر مجوز مکانی اپ داده نشده باشد درخواست می‌شود
                 * و نتیجهٔ واقعی به callback داده می‌شود. */
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    try { geoPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) } catch (_: Exception) {
                        callback.invoke(origin, false, false)
                    }
                } else {
                    callback.invoke(origin, true, false)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val res = request.resources
                if (res.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    runOnUiThread {
                        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(res)
                        } else {
                            pendingAudioRequest = request
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                } else {
                    request.deny()
                }
            }

            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                openFileChooser()
                return true
            }
        }

        /* v2.11.3 (درخواست ۱ کاربر — «برنامه‌ای برای باز کردن این آدرس یافت نشد»):
         * مسیرهای blob: و data: هرگز نباید به DownloadManager یا openExternal
         * بروند (دانلود سیستمی آن‌ها را نمی‌فهمد → fallback قبلی openExternal
         * → «برنامه‌ای برای باز کردن این آدرس یافت نشد»). این‌ها را از خودِ
         * صفحهٔ وب (رجیستری blob هوک دانلود یا fetch) استخراج و با
         * MediaStore ذخیره می‌کنیم. */
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("data:") -> saveDataUrlDirect(url)
                url.startsWith("blob:") -> saveBlobViaJs(url, contentDisposition, mimeType)
                else -> handleSystemDownload(url, contentDisposition, mimeType)
            }
        }

        ensureFreshContent(periodic = false)

        if (state != null) web.restoreState(state)
        else web.loadUrl(serverUrl)
    }

    // ═════════════════════ فایل انتخابی / دوربین ═════════════════════
    private fun openFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        // دوربین
        val camera = createCameraIntent()
        val chooser = Intent.createChooser(intent, getString(R.string.choose_file))
        if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        try {
            fileChooserLauncher.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = filePathCallback
        filePathCallback = null
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.data != null) uris = arrayOf(data.data!!)
            else if (data?.clipData != null) {
                val n = data.clipData!!.itemCount
                uris = Array(n) { data.clipData!!.getItemAt(it).uri }
            }
        }
        cb?.onReceiveValue(uris ?: arrayOf())
    }

    private var pendingAudioRequest: PermissionRequest? = null

    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera")
            if (!dir.exists()) dir.mkdirs()
            val photo = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
            cameraPhotoUri = uri
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) { null }
    }

    // ═════════════════════ ذخیرهٔ فایل (blob / data-url / دانلود سیستمی) ═════════════════════
    /** JS inject — لینک‌های a[download] با blob:/data: را به FileBridge می‌فرستد
     * v2.11.3 — الگوی رایج خروجی‌های پنل: ساخت <a download> بدون append به DOM
     * و click() و revoke فوری — چنین کلیکی در document event نهایی نمی‌شود
     * (propagation فقط خودِ گره است) → HTMLAnchorElement.click هم پچ می‌شود
     * تا نام فایل صحیح و مسیر ذخیرهٔ بومی برای همهٔ خروجی‌ها کار کند. */
    private fun injectDownloadHook() {
        web.evaluateJavascript("""(function(){
  if (window.__sahandDlHooked) return; window.__sahandDlHooked = true;
  function __sahandSaveBlob(blob, name){
    try {
      var fr = new FileReader();
      fr.onloadend = function(){
        var b64 = String(fr.result).split(',')[1] || '';
        var nm = name || 'sahand-export.bin';
        var mm = blob.type || 'application/octet-stream';
        /* v2.11.5 — انتقال تکه‌ای (item 4): پل JS روی رشته‌های چند-مگابایتی
         * بی‌صدا می‌میرد → «هیچ پیام، هیچ فایل». تکه‌های ۳۰۰KB. */
        if (b64.length <= 300000) {
          try { SahandFiles.saveBase64(nm, mm, b64); } catch (eD) {
            try { SahandFiles.saveFailed(); } catch (eD2) {}
          }
        } else {
          var total = Math.ceil(b64.length / 300000);
          for (var ci = 0; ci < total; ci++) {
            (function (idx) {
              setTimeout(function () {
                try { SahandFiles.saveChunk(nm, mm, idx, total, b64.substr(idx * 300000, 300000)); }
                catch (eC) { try { SahandFiles.saveFailed(); } catch (eC2) {} }
              }, idx * 40);
            })(ci);
          }
        }
      };
      fr.onerror = function(){ try { SahandFiles.saveFailed(); } catch (eR) {} };
      fr.readAsDataURL(blob);
    } catch(e){ try { SahandFiles.saveFailed(); } catch (eS) {} }
  }
  function __sahandRouteAnchor(a){
    try {
      if (!a || !a.getAttribute) return false;
      var href = a.href || '';
      var dn = a.getAttribute('download');
      if (dn == null) return false;
      if (href.indexOf('blob:') === 0) {
        var blob = window.__sahandBlobs ? window.__sahandBlobs[href] : null;
        if (blob) { __sahandSaveBlob(blob, dn); return true; }
        /* blob در رجیستری نیست (مثلاً قبل از هوک ساخته شده) — fetch همان URL */
        fetch(href).then(function(r){ return r.blob(); }).then(function(b){
          __sahandSaveBlob(b, dn);
        }).catch(function(){});
        return true; /* نگذاریم مسیر پیش‌فرض (خطای بازکردن) اجرا شود */
      }
      if (href.indexOf('data:') === 0) {
        SahandFiles.saveDataUrl(dn || 'sahand-export.bin', href);
        return true;
      }
    } catch(e){}
    return false;
  }
  try {
    var origCreate = URL.createObjectURL.bind(URL);
    window.__sahandBlobs = {};
    URL.createObjectURL = function(blob){
      var u = origCreate(blob);
      try { window.__sahandBlobs[u] = blob; } catch(e){}
      return u;
    };
    /* ۱) کلیک‌های داخل document (a[download] متصل به DOM) */
    document.addEventListener('click', function(e){
      var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if (!a) return;
      if (__sahandRouteAnchor(a)) { e.preventDefault(); e.stopPropagation(); }
    }, true);
    /* ۲) v2.11.3 — a.click() روی <a> «متصل‌نشده» (خروجی‌های گزارش/فاکتور)
     * document listener نمی‌گیرد → خودِ HTMLAnchorElement.click پچ می‌شود.
     * اگر مسیر ذخیرهٔ بومی چیدیم، click اصلی اجرا نمی‌شود (مسیر خطادار). */
    try {
      var origClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function(){
        if (__sahandRouteAnchor(this)) return;
        return origClick.apply(this, arguments);
      };
    } catch(e2){}
  } catch(e){}
})();""", null)
    }

    /**
     * v2.11.3 — استخراج blob: از خودِ صفحهٔ وب و ذخیرهٔ بومی.
     * مسیر fallback است برای وقتی کلیکِ a[download] به هوک نرسیده باشد
     * (مثلاً خروجی با anchor متصل‌نشده یا window.open). ابتدا رجیستری
     * __sahandBlobs (مرجع مستقیم به Blob — حتی پس از revokeObjectURL
     * خوانا است)، سپس fetch(url). اگر هر دو نشد → پیام خطای دقیق.
     */
    private fun saveBlobViaJs(url: String, contentDisposition: String, mimeType: String) {
        val guess = URLUtil.guessFileName(url, contentDisposition, cleanMime(mimeType))
        val name = sanitize(guess)
        val js = """(async function(){
  try {
    var u = __URL__, nm = __NAME__;
    var b = (window.__sahandBlobs && window.__sahandBlobs[u]) || null;
    if (!b) { try { b = await fetch(u).then(function(r){ return r.blob(); }); } catch (eF) {} }
    if (!b || !b.size) { try { SahandFiles.saveFailed(); } catch (eS) {} return; }
    var fr = new FileReader();
    fr.onloadend = function(){
      var b64 = String(fr.result).split(',')[1] || '';
      SahandFiles.saveBase64(nm, b.type || '__MIME__', b64);
    };
    fr.onerror = function(){ try { SahandFiles.saveFailed(); } catch (eS2) {} };
    fr.readAsDataURL(b);
  } catch (e) { try { SahandFiles.saveFailed(); } catch (eS3) {} }
})()"""
            .replace("__URL__", escapeJsString(url))
            .replace("__NAME__", escapeJsString(name))
            .replace("__MIME__", escapeJsString(cleanMime(mimeType)))
        web.evaluateJavascript(js, null)
    }

    /** رشته امن برای درج در JS (کوتیشن و بک‌اسلش) */
    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun saveBlobFromWebView(blobUrl: String, contentDisposition: String, mimeType: String) {
        web.evaluateJavascript(
            "(function(){var b=window.__sahandBlobs&&window.__sahandBlobs['$blobUrl'];if(!b)return null;var r=new FileReader();r.onloadend=function(){SahandFiles.saveBase64('${blobUrl.substringAfterLast("/")}',b.type||'application/octet-stream',String(r.result).split(',')[1]||'')};r.readAsDataURL(b);return 'ok'})()"
        ) { }
        // fallback: اگر blob register نشده بود، از path سیستمی
        handleSystemDownload(blobUrl, contentDisposition, mimeType)
    }

    private fun saveDataUrlDirect(dataUrl: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            var mime = dataUrl.substring(5, comma)
            val isBase64 = mime.endsWith(";base64")
            if (isBase64) mime = mime.removeSuffix(";base64")
            if (mime.isEmpty()) mime = "application/octet-stream"
            val data = dataUrl.substring(comma + 1)
            val bytes = if (isBase64) android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            else data.toByteArray(Charsets.UTF_8)
            saveDirectFile("sahand-export-${System.currentTimeMillis()}", mime, bytes)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessExtFromMime(mime: String): String = when {
        mime.contains("pdf") -> "pdf"
        mime.contains("csv") || mime.contains("excel") || mime.contains("spreadsheet") -> "csv"
        mime.contains("json") -> "json"
        mime.contains("zip") -> "zip"
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("svg") -> "svg"
        mime.contains("html") -> "html"
        else -> "bin"
    }

    private fun cleanMime(mime: String): String {
        val m = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return if (!Regex("^[-\\w.+]+/[-\\w.+]+$").matches(m) || m.length > 127) "application/octet-stream" else m
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (clean.isEmpty() || clean == "." || clean == "..") "sahand-export.bin" else clean.take(80)
    }

    /** ذخیرهٔ مستقیم بایت‌ها — MediaStore (اندروید ۱۰+) یا پوشهٔ Downloads */
    fun saveDirectFile(name: String, mime: String, bytes: ByteArray) {
        try {
            val nm = sanitize(name)
            val m = cleanMime(mime)
            // نام فایل با پسوند درست
            val ext = guessExtFromMime(m)
            val fileName = if (nm.contains('.')) nm else "$nm.$ext"
            if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStore(fileName, m, bytes)
            } else {
                saveToAppDownloads(fileName, bytes)
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveViaMediaStore(name: String, mime: String, bytes: ByteArray) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("stream failed")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, getString(R.string.saved_to_downloads, name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            saveToAppDownloads(name, bytes)
        }
    }

    private fun saveToAppDownloads(name: String, bytes: ByteArray) {
        try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "exports").apply { if (!exists()) mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            Toast.makeText(this, getString(R.string.saved_to_downloads, "$name (اپ)"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** دانلود سیستمی (لینک‌های http/https غیر-blob) */
    private fun handleSystemDownload(url: String, contentDisposition: String, mimeType: String) {
        Thread {
            try {
                val guess = URLUtil.guessFileName(url, contentDisposition, cleanMime(mimeType))
                runOnUiThread {
                    try {
                        val req = DownloadManager.Request(Uri.parse(url))
                        req.setMimeType(cleanMime(mimeType))
                        req.setTitle(guess)
                        req.setDescription(getString(R.string.download_started))
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitize(guess))
                        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("cookie", it) }
                        req.addRequestHeader("User-Agent", web.settings.userAgentString)
                        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(req)
                        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        /* v2.11.3 — blob:/data: هرگز با intent باز نمی‌شوند
                         * (خطای «برنامه‌ای برای باز کردن این آدرس یافت نشد») */
                        if (url.startsWith("blob:")) saveBlobViaJs(url, contentDisposition, mimeType)
                        else if (url.startsWith("data:")) saveDataUrlDirect(url)
                        else openExternal(Uri.parse(url))
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ═════════════════════ v2.11.3 — گیت دسترسی‌ها (درخواست ۳) ═════════════════════
    /** دسترسی‌های لازم برنامه — همه قبل از باز شدن صفحه بررسی/درخواست می‌شوند */
    private data class PermRow(val key: String, val label: String, val desc: String, val perms: List<String>)

    private fun permRows(): List<PermRow> {
        val rows = ArrayList<PermRow>()
        rows.add(PermRow("notif", "اعلان‌ها", "اطلاع‌رسانی سرویس‌ها و پیام‌های چت", if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()))
        rows.add(PermRow("mic", "میکروفون", "ارسال پیام صوتی در چت", listOf(Manifest.permission.RECORD_AUDIO)))
        rows.add(PermRow("loc", "موقعیت مکانی", "نمایش موقعیت روی نقشه", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        rows.add(PermRow("cam", "دوربین", "ثبت عکس دستگاه و ارسال در چت", listOf(Manifest.permission.CAMERA)))
        if (Build.VERSION.SDK_INT <= 28) {
            rows.add(PermRow("storage", "ذخیرهٔ فایل", "ذخیرهٔ خروجی‌ها و گزارش‌ها", listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)))
        }
        /* v2.11.5 — معافیت از بهینه‌سازی باتری (item 5/6): قاتل شمارهٔ یکِ
         * اعلان‌های پس‌زمینه روی گوشی‌های ایرانی (شیائومی/هواوی/سامسونگ)
         * این است که سرویس اعلان را می‌کشند. */
        rows.add(PermRow("battery", "فعال‌ماندن در پس‌زمینه", "دریافت اعلان‌ها و پیام‌ها وقتی برنامه بسته است", emptyList()))
        return rows
    }

    private fun rowGranted(row: PermRow): Boolean {
        /* v2.11.5 — ردیف باتری: مجوز runtime نیست؛ وضعیت PowerManager چک می‌شود */
        if (row.key == "battery") {
            return try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } catch (_: Exception) { true }
        }
        if (row.perms.isEmpty()) return true
        return row.perms.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** اگر دسترسیِ لازمِ فعال‌نشده باشد → صفحهٔ گیت روی WebView می‌نشیند */
    private fun maybeShowPermissionGate() {
        try {
            val missing = permRows().filter { !rowGranted(it) }
            if (missing.isEmpty()) return
            val root = findViewById<android.widget.FrameLayout>(R.id.root) ?: return
            val dp = resources.displayMetrics.density
            fun dpx(v: Int) = (v * dp).roundToInt()

            val scroll = ScrollView(this)
            scroll.id = View.generateViewId()
            scroll.setBackgroundColor(Color.parseColor("#0f172a"))
            scroll.isFillViewport = true
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dpx(20), dpx(28), dpx(20), dpx(28))
            scroll.addView(card)

            val title = TextView(this)
            title.text = "بررسی دسترسی‌های برنامه"
            title.textSize = 19f
            title.typeface = android.graphics.Typeface.DEFAULT_BOLD
            title.setTextColor(0xffe2e8f0.toInt())
            card.addView(title)
            val sub = TextView(this)
            sub.text = "برای کارکرد کامل (اعلان‌ها، چت صوتی، نقشه و ذخیرهٔ فایل) این دسترسی‌ها را فعال کنید. پس از فعال‌سازی، برنامه باز می‌شود."
            sub.textSize = 13.5f
            sub.setTextColor(0xff94a3b8.toInt())
            sub.setPadding(0, dpx(6), 0, dpx(16))
            card.addView(sub)

            val statusViews = HashMap<String, TextView>()
            for (row in permRows()) {
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                line.setPadding(0, dpx(10), 0, dpx(10))
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val name = TextView(this)
                name.text = row.label
                name.textSize = 15f
                name.setTextColor(0xffe2e8f0.toInt())
                col.addView(name)
                val desc = TextView(this)
                desc.text = row.desc
                desc.textSize = 12f
                desc.setTextColor(0xff94a3b8.toInt())
                col.addView(desc)
                line.addView(col, lp)
                val st = TextView(this)
                st.textSize = 13f
                st.typeface = android.graphics.Typeface.DEFAULT_BOLD
                statusViews[row.key] = st
                line.addView(st)
                card.addView(line)
            }

            fun refreshStatuses() {
                for (row in permRows()) {
                    val tv = statusViews[row.key] ?: continue
                    if (rowGranted(row)) {
                        tv.text = "فعال ✓"
                        tv.setTextColor(0xff34d399.toInt())
                    } else {
                        tv.text = "غیرفعال"
                        tv.setTextColor(0xfffbbf24.toInt())
                    }
                }
            }
            refreshStatuses()

            val btnEnable = Button(this)
            btnEnable.text = "فعال‌سازی همه دسترسی‌ها"
            btnEnable.setTextColor(0xffffffff.toInt())
            btnEnable.background?.setTint(0xFF2563EB.toInt())
            btnEnable.setPadding(0, dpx(12), 0, dpx(12))
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            blp.topMargin = dpx(18)
            card.addView(btnEnable, blp)
            btnEnable.setOnClickListener {
                /* v2.11.5 — ابتدا معافیت باتری (intent سیستمی، همراه بقیه) */
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        startActivity(Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        ))
                    }
                } catch (_: Exception) { /* برخی ROMها این intent را ندارند */ }
                val need = ArrayList<String>()
                for (row in permRows()) if (!rowGranted(row)) need.addAll(row.perms)
                if (need.isEmpty()) { dismissGate() ; return@setOnClickListener }
                try { gatePermLauncher.launch(need.toTypedArray()) } catch (_: Exception) {}
            }

            val btnSettings = Button(this)
            btnSettings.text = "فعال‌سازی از تنظیمات برنامه"
            btnSettings.setTextColor(0xff93c5fd.toInt())
            btnSettings.setPadding(0, dpx(10), 0, dpx(10))
            card.addView(btnSettings, blp)
            btnSettings.setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
                } catch (_: Exception) {}
            }

            /* v2.11.5 — تست اعلان (item 5): کاربر همین‌جا می‌تواند مطمئن شود
             * مجوز و کانال اعلان واقعاً کار می‌کنند — بدون انتظار برای رویداد واقعی */
            val btnTestNotif = Button(this)
            btnTestNotif.text = "تست اعلان (بررسی نمایش پیام‌ها)"
            btnTestNotif.setTextColor(0xff93c5fd.toInt())
            btnTestNotif.setPadding(0, dpx(10), 0, dpx(10))
            card.addView(btnTestNotif, blp)
            btnTestNotif.setOnClickListener {
                val shown = try {
                    NotificationHub.post(
                        this, "تست اعلان سهند سرویس",
                        "اگر این پیام را می‌بینید، اعلان‌ها فعال و سالم هستند ✓",
                        "test-notif", "/"
                    )
                } catch (_: Exception) { false }
                Toast.makeText(
                    this,
                    if (shown) "اعلان تست ارسال شد — نوار بالای گوشی را ببینید"
                    else "اعلان نمایش داده نشد — مجوز «اعلان‌ها» را از تنظیمات فعال کنید",
                    Toast.LENGTH_LONG
                ).show()
            }

            val btnSkip = Button(this)
            btnSkip.text = "ادامه بدون فعال‌سازی"
            btnSkip.setTextColor(0xff94a3b8.toInt())
            btnSkip.setPadding(0, dpx(8), 0, dpx(8))
            card.addView(btnSkip, blp)
            btnSkip.setOnClickListener { dismissGate() }

            root.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            gateView = scroll
        } catch (_: Exception) { /* fail-soft — برنامه بدون گیت هم باز می‌شود */ }
    }

    private fun dismissGate() {
        try {
            gateView?.let { (it.parent as? android.widget.FrameLayout)?.removeView(it) }
        } catch (_: Exception) {}
        gateView = null
        /* اگر نوتیفی منتظر ناوبری باشد، حالا که گیت بسته شد اعمال می‌شود */
        applyPendingNotifUrl()
    }

    private val gatePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        /* پس از پاسخ سیستم، وضعیت‌ها تازه و اگر همه فعال شد خودکار بسته می‌شود */
        try {
            val missing = permRows().filter { !rowGranted(it) }
            if (missing.isEmpty()) {
                android.os.Handler(Looper.getMainLooper()).postDelayed({ dismissGate() }, 400)
            } else if (gateView != null) {
                val anyHardDenied = missing.any { row ->
                    row.perms.any { p -> !shouldShowRequestPermissionRationale(p) }
                }
                if (anyHardDenied) {
                    Toast.makeText(this, "برخی دسترسی‌ها رد شده‌اند — از «فعال‌سازی از تنظیمات برنامه» آن‌ها را روشن کنید", Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {}
    }

    // ═════════════════════ v2.11.3 — دیپ‌لینک نوتیف (درخواست ۲) ═════════════════════
    /** URL ناوبری از intent اعلان (extra یا data app://sahand/open) خوانده می‌شود */
    private fun readNotifNavIntent(intent: Intent?) {
        try {
            if (intent == null) return
            var url = intent.getStringExtra("notif_url")
            if (url.isNullOrBlank() && intent.data != null) {
                val d = intent.data
                if (d?.scheme == "app" && d.host == "sahand") url = d.getQueryParameter("url")
            }
            if (!url.isNullOrBlank() && url != "/") pendingNotifUrl = url
        } catch (_: Exception) {}
    }

    /** ناوبری به URL اعلان — پس از آماده شدن صفحه اعمال می‌شود */
    private fun applyPendingNotifUrl() {
        val url = pendingNotifUrl ?: return
        pendingNotifUrl = null
        try {
            val full = if (url.startsWith("http")) url else serverUrl.trimEnd('/') + url
            /* اگر همین صفحه است فقط تمرکز؛ در غیر این صورت آدرس کامل با پارامتر
             * page= بارگذاری می‌شود (روتر پنل در boot دیپ‌لینک را می‌فهمد) */
            if (web.url != full) web.loadUrl(full)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotifNavIntent(intent)
        /* اپ از قبل باز است → بلافاصله ناوبری کن (بدون گیت) */
        if (gateView == null) applyPendingNotifUrl()
    }

    // ═════════════════════ بروزرسانی خودکار ═════════════════════
    private fun checkAppUpdate(forceDialog: Boolean) {
        Thread {
            try {
                val updateUrl = serverUrl.trimEnd('/') + "/android-app.json"
                val conn = URL(updateUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Cache-Control", "no-cache")
                val body = conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                }
                conn.disconnect()
                val obj = JSONObject(body)
                val newCode = obj.optInt("versionCode", 0)
                val currentCode = packageManager.getPackageInfo(packageName, 0).let {
                    if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                }
                if (newCode > currentCode) {
                    val version = obj.optString("version", "")
                    val notes = ArrayList<String>()
                    obj.optJSONArray("notes")?.let { arr: JSONArray ->
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i, "")
                            if (n.isNotBlank()) notes.add(n)
                        }
                    }
                    // APK مناسب همین flavor: agencyApk / techApk
                    val isTech = packageName.endsWith(".tech")
                    val apkUrl = obj.optString(if (isTech) "techApk" else "agencyApk", "")
                    runOnUiThread {
                        if (forceDialog || updateDialogShownFor != version) {
                            updateDialogShownFor = version
                            showUpdateDialog(version, newCode, notes, apkUrl)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** v2.11.0 — دیالوگ بروزرسانی با فهرست تغییرات اسکرول‌شونده (رفع باگ «غیرقابل اسکرول») */
    private fun showUpdateDialog(version: String, @Suppress("UNUSED_PARAMETER") code: Int, notes: ArrayList<String>, apkUrl: String) {
        val dp16 = (16 * resources.displayMetrics.density).roundToInt()
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = true
        val tv = TextView(this)
        val sb = StringBuilder()
        sb.append(getString(R.string.update_current, packageManager.getPackageInfo(packageName, 0).versionName)).append("\n")
        sb.append(getString(R.string.update_new, version)).append("\n\n")
        if (notes.isEmpty()) sb.append("—")
        else notes.filter { it.isNotBlank() }.forEach { sb.append("• ").append(it).append("\n") }
        tv.text = sb.toString()
        tv.setPadding(dp16, dp16 / 2, dp16, dp16 / 2)
        tv.textSize = 14f
        tv.setTextColor(0xffe2e8f0.toInt())
        // v2.11.0 — ارتفاع حداکثر ۶۰٪ صفحه → همیشه اسکرول‌شونده
        scroll.addView(tv)
        scroll.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scroll.isScrollbarFadingEnabled = false
        /* v2.11.5 — ارتفاع ماکزیمم فوری (item 18): قبلاً فقط بعد از layout
         * محدود می‌شد و در بعضی دستگاه‌ها اسکرول کار نمی‌کرد؛ حالا در لحظهٔ
         * show اعمال می‌شود + پدینگ داخلی برای راحتی لمس. */
        val maxH0 = (resources.displayMetrics.heightPixels * 0.6).toInt()
        tv.setPadding(dp16, dp16 / 2, dp16, dp16 * 2)
        scroll.setPadding(0, 0, 0, dp16 / 2)
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val maxH = (resources.displayMetrics.heightPixels * 0.6).toInt()
            if (scroll.height > maxH) {
                scroll.layoutParams.height = maxH
                scroll.requestLayout()
            }
        }
        scroll.post { if (scroll.height > maxH0) { scroll.layoutParams.height = maxH0; scroll.requestLayout() } }
        AlertDialog.Builder(this, R.style.Theme_Sahand_Dialog)
            .setTitle(getString(R.string.update_title))
            .setView(scroll)
            .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstallUpdate(apkUrl, version) }
            .setNeutralButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstallUpdate(apkUrl: String, version: String) {
        try {
            if (apkUrl.isEmpty()) {
                Toast.makeText(this, R.string.update_no_url, Toast.LENGTH_LONG).show()
                return
            }
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                }
                return
            }
            // آدرس نسبی → کامل با سرور
            val fullUrl = if (apkUrl.startsWith("http")) apkUrl else serverUrl.trimEnd('/') + apkUrl
            val req = DownloadManager.Request(Uri.parse(fullUrl))
            req.setTitle(getString(R.string.update_dl_title, version))
            req.setDescription(getString(R.string.update_dl_desc))
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setMimeType(APK_MIME)
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "SahandService-update-v$version.apk")
            CookieManager.getInstance().getCookie(fullUrl)?.let { req.addRequestHeader("cookie", it) }
            req.addRequestHeader("User-Agent", web.settings.userAgentString)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            pendingApkDownloadId = dm.enqueue(req)
            Toast.makeText(this, R.string.update_dl_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    fun installDownloadedApk(id: Long) {
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null || !cursor.moveToFirst()) { cursor?.close(); return }
            if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                cursor.close(); return
            }
            var uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
            cursor.close()
            var file: File? = null
            if (uri.scheme == "file") {
                uri.path?.let { file = File(it) }
            } else if (uri.scheme == "content") {
                // v2.11.0 — external-files در اندروید ۱۰+ content URI برمی‌گرداند
                try {
                    val rel = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    if (rel != null) {
                        val f = File(rel, uri.lastPathSegment?.substringAfterLast('/') ?: "")
                        if (f.exists()) file = f
                    }
                } catch (_: Exception) {}
            }
            val apkUri = if (file != null) FileProvider.getUriForFile(this, "$packageName.fileprovider", file!!) else uri
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(apkUri, APK_MIME)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.update_install_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    // ═════════════════════ اعلان‌های سیستمی ═════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(NOTIF_CHANNEL) != null) return
        val ch = NotificationChannel(
            NOTIF_CHANNEL,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH // v2.11.0 — HIGH: اعلان سربرگ‌دار و سریع
        )
        ch.description = getString(R.string.notif_channel_desc)
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(200, 100, 200)
        ch.enableLights(true)
        ch.lightColor = 0xFF0ea5e9.toInt()
        mgr.createNotificationChannel(ch)
    }

    /**
     * v2.11.0 — درخواست مجوز اعلان:
     * • askIfNeverDenied=true فقط وقتی قبلاً هرگز درخواست نشده بپرسد
     * • سیستم بعد از دو رد، خودش می‌بندد؛ در آن حالت باید کاربر به تنظیمات هدایت شود.
     */
    private fun requestNotificationPermissionIfNeeded(askIfNeverDenied: Boolean = false) {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val sp = getSharedPreferences(PREFS, 0)
        val neverAsked = !sp.getBoolean("notif_asked_once", false)
        if (askIfNeverDenied && !neverAsked) return
        sp.edit().putBoolean("notif_asked_once", true).apply()
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationsReallyEnabled(): Boolean {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!mgr.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = mgr.getNotificationChannel(NOTIF_CHANNEL) ?: return true
            if (ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    /** روزی یک‌بار هشدار داخلی وقتی اعلان خاموش است */
    private fun showNotificationWarningIfNeeded() {
        if (notificationsReallyEnabled()) return
        val sp = getSharedPreferences(PREFS, 0)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        if (sp.getString("notif_warn_day", "") == today) return
        sp.edit().putString("notif_warn_day", today).apply()
        runOnUiThread {
            AlertDialog.Builder(this, R.style.Theme_Sahand_Dialog)
                .setTitle(R.string.notif_off_title)
                .setMessage(R.string.notif_off_msg)
                .setPositiveButton(R.string.notif_open_settings) { _, _ -> openNotificationSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** ارسال اعلان سیستمی — مقاوم‌سازی‌شده (v2.11.0) */
    fun postSystemNotification(title: String, body: String, tag: String, url: String) {
        try {
            if (!notificationsReallyEnabled()) {
                showNotificationWarningIfNeeded()
                return
            }
            createNotificationChannel()
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            intent.putExtra("notif_url", url)
            val pi = android.app.PendingIntent.getActivity(
                this, tag.hashCode(), intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVibrate(longArrayOf(200, 100, 200))
                .setContentIntent(pi)
                .build()
            mgr.notify(tag.hashCode(), notif)
        } catch (_: Exception) {}
    }

    /** پل JS → اعلان سیستمی */
    inner class NativeBridge {
        @android.webkit.JavascriptInterface
        fun showNotification(title: String, body: String, tag: String, url: String) {
            runOnUiThread { postSystemNotification(title, body, tag, url) }
        }

        @android.webkit.JavascriptInterface
        fun isNotificationEnabled(): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        }

        @android.webkit.JavascriptInterface
        fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT < 33) return
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
            runOnUiThread { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /** پل JS → ذخیرهٔ فایل */
    inner class FileBridge {
        @android.webkit.JavascriptInterface
        fun saveBase64(name: String, mime: String, base64: String) {
            try {
                val bytes = android.util.Base64.decode(base64.replace("\n", "").replace(" ", ""), android.util.Base64.DEFAULT)
                runOnUiThread { saveDirectFile(name, mime, bytes) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show() }
            }
        }

        /* v2.11.5 — انتقال تکه‌ای (item 4): رشته‌های چند-مگابایتی از پل JS
         * به‌صورت بی‌صدا شکست می‌خوردند (هیچ پیام و هیچ فایل). حالا JS
         * base64 را در تکه‌های ۳۰۰KB می‌فرستد و اینجا مونتاژ می‌شود. */
        @android.webkit.JavascriptInterface
        fun saveChunk(name: String, mime: String, index: Int, total: Int, chunk: String) {
            try {
                if (index == 0) saveChunks.clear()
                saveChunks[index] = chunk
                if (index + 1 >= total) {
                    val joined = saveChunks.values.joinToString("")
                    saveChunks.clear()
                    saveBase64(name, mime, joined)
                }
            } catch (e: Exception) {
                saveChunks.clear()
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_LONG).show() }
            }
        }

        @android.webkit.JavascriptInterface
        fun saveDataUrl(name: String, dataUrl: String) {
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) return
                var mime = dataUrl.substring(5, comma)
                val isBase64 = mime.endsWith(";base64")
                if (isBase64) mime = mime.removeSuffix(";base64")
                if (mime.isEmpty()) mime = "application/octet-stream"
                val data = dataUrl.substring(comma + 1)
                val bytes = if (isBase64) android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                else data.toByteArray(Charsets.UTF_8)
                runOnUiThread { saveDirectFile(name, mime, bytes) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show() }
            }
        }

        /** v2.11.3 — پیام خطای بومی وقتی استخراج blob ممکن نیست */
        @android.webkit.JavascriptInterface
        fun saveFailed() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_LONG).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun retry() {
            runOnUiThread {
                pageError = false
                web.loadUrl(serverUrl)
            }
        }
    }

    // ═════════════════════ polling اعلان پس‌زمینه ═════════════════════
    private fun startBackgroundNotifyPolling() {
        // پنل کاربر از localStorage خوانده می‌شود (agency / technician)
        web.evaluateJavascript(
            """(function(){try{var a=JSON.parse(localStorage.getItem('asm-auth')||'null');
return a?JSON.stringify({panel:(a.state&&a.state.panel)||'',techId:(a.state&&a.state.technicianId)||''}):null}}catch(e){return null}})()"""
        ) { raw ->
            if (raw != null && raw != "null" && raw.length > 4) {
                try {
                    val o = JSONObject(raw)
                    bgUserPanel = o.optString("panel", "").ifBlank { null }
                    bgTechId = o.optString("techId", "").ifBlank { null }
                    /* v2.11.1 — نقش/ورود در SharedPreferences ذخیره می‌شود تا
                     * NotifyService (پس‌زمینه/بوت) بدون WebView هم بتواند poll کند */
                    val sp = getSharedPreferences(PREFS, 0)
                    sp.edit()
                        .putString("user_panel", bgUserPanel ?: "")
                        .putString("user_tech_id", bgTechId ?: "")
                        .putBoolean("user_logged_in", true)
                        .apply()
                    NotificationHub.userPanel = bgUserPanel
                    NotificationHub.techId = bgTechId
                    /* v2.11.1 — سرویس پیش‌زمینهٔ اعلان‌ها (حتی با بستن اپ فعال می‌ماند) */
                    NotifyService.start(this@MainActivity)
                } catch (_: Exception) {}
            }
        }
        /* v2.11.1 — شمارنده‌ها مشترک با سرویس (NotificationHub) — نه متغیر محلی */
        val handler = bgPollHandler ?: Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                pollUnreadInBackground()
                handler.postDelayed(this, 60_000L)
            }
        }
        if (bgPollRunnable != null) handler.removeCallbacks(bgPollRunnable!!)
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, 15_000L)
        bgPollHandler = handler
        bgPollRunnable = runnable
    }

    private fun stopBackgroundNotifyPolling() {
        bgPollRunnable?.let { bgPollHandler?.removeCallbacks(it) }
        bgPollHandler = null
        bgPollRunnable = null
    }

    private fun fetchBodyWithSession(path: String): String? {
        return try {
            val conn = openConnection(serverUrl.trimEnd('/') + path, "GET")
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    conn.inputStream.use { ins ->
                        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                    }
                } else null
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun openConnection(urlStr: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        CookieManager.getInstance().getCookie(serverUrl)?.let { conn.setRequestProperty("Cookie", it) }
        conn.setRequestProperty("User-Agent", web.settings.userAgentString)
        return conn
    }

    fun pollUnreadInBackground() {
        Thread {
            try {
                // ۱) پیام‌های چت خوانده‌نشده
                fetchBodyWithSession("/api/chat?unread=mine")?.let { body ->
                    try {
                        val count = JSONObject(body).optInt("unreadCount", 0)
                        /* v2.11.1 — شمارندهٔ مشترک با سرویس؛ نخستین poll هم
                         * پیام‌های موجود را اعلان می‌کند (هیچ نوتیفی از دست نرود) */
                        val delta = NotificationHub.takeChatDelta(count)
                        if (delta > 0) {
                            postSystemNotification(
                                "پیام جدید",
                                (if (delta == 1) "یک پیام" else "$delta پیام") + " خوانده‌نشده دارید — برای مشاهده چت را باز کنید",
                                "chat-unread-bg", "/"
                            )
                        }
                    } catch (_: Exception) {}
                }
                // ۲) اعلان‌های سرویس‌کار / سرویس‌های در انتظار نمایندگی
                val isTech = bgUserPanel == "technician" && !bgTechId.isNullOrEmpty()
                val path = if (isTech) "/api/notification?technicianId=$bgTechId"
                           else "/api/entity?type=service&status=pending&_pageSize=200"
                fetchBodyWithSession(path)?.let { body ->
                    try {
                        val arr = JSONArray(body)
                        var count = -1
                        if (isTech) {
                            var c = 0
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i)
                                if (o != null && !o.optBoolean("isRead", false)) c++
                            }
                            count = c
                        } else {
                            count = arr.length()
                        }
                        if (count >= 0) {
                            /* v2.11.1 — نخستین poll هم اعلان می‌دهد + شمارندهٔ مشترک سرویس */
                            val delta = NotificationHub.takeNotifDelta(count)
                            if (delta > 0) {
                                if (isTech) {
                                    postSystemNotification(
                                        "اعلان جدید",
                                        (if (delta == 1) "یک اعلان" else "$delta اعلان") + " خوانده‌نشده جدید دارید",
                                        "notif-bg", "/"
                                    )
                                } else {
                                    postSystemNotification(
                                        "سرویس جدید",
                                        (if (delta == 1) "یک سرویس" else "$delta سرویس") + " جدید در انتظار بررسی",
                                        "notif-bg", "/"
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ═════════════════════ بروزرسانی محتوا در پس‌زمینه ═════════════════════
    /* v2.11.2 (درخواست ۶ — «صفحه چندین بار رفرش می‌شود») — نسخهٔ سرور فقط
     * ذخیره می‌شود و خودِ صفحه (گارد __SAH_BUILD در admin-ext.js) در صورت
     * کشِ قدیمی، حداکثر «یک‌بار» و بدون حلقه reload می‌کند. قبلاً اینجا
     * یک reload دوم از خود اپ هم اضافه می‌شد → کاربر چند رفرش می‌دید. */
    private fun ensureFreshContent(periodic: Boolean) {
        try {
            val verUrl = serverUrl.trimEnd('/') + "/version.json"
            Thread {
                try {
                    val conn = URL(verUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", web.settings.userAgentString)
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    val text = conn.inputStream.use { ins ->
                        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                    }
                    conn.disconnect()
                    val v = JSONObject(text).optString("version", "")
                    if (v.isNotEmpty()) {
                        getSharedPreferences(PREFS, 0).edit().putString("last_server_version", v).apply()
                    }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    private fun reloadBypassingCache() {
        try { web.settings.cacheMode = WebSettings.LOAD_NO_CACHE } catch (_: Exception) {}
        try { web.clearCache(true) } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { web.reload() } catch (_: Exception) {}
        }, 300)
    }

    // ═════════════════════ متفرقه ═════════════════════
    fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this, R.style.Theme_Sahand_Dialog)
            .setTitle(R.string.app_exit_title)
            .setMessage(R.string.app_exit_msg)
            .setPositiveButton(R.string.exit) { _, _ -> finish() }
            .setNegativeButton(R.string.settings_server) { _, _ ->
                startActivity(Intent(this, SetupActivity::class.java))
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    // ═════════════════════ v2.11.2 — تپ قلب اپ (ثبت دستگاه در لایسنس) ═════════════════════
    /**
     * درخواست کاربر: «در مدیریت لایسنس دستگاه‌هایی که اپ اندروید نصب
     * کرده‌اند نمایش داده نشود (چه سرویسکار چه مدیر)». ریشه: اپ هیچ‌وقت
     * خودش را به سرور لایسنس معرفی نمی‌کرد. حالا هر ۶ ساعت + با هر
     * ورود، POST /api/app-heartbeat به «پنل» می‌فرستد؛ پنل licenseKey را
     * اضافه کرده و به v1/heartbeat سرور لایسنس فوروارد می‌کند → دستگاه
     * در جدول devices با platform=main_app ثبت و در پنل مدیریت لایسنس
     * (داشبورد ← دستگاه‌ها + تب دستگاه‌های هر لایسنس) دیده می‌شود.
     *
     * v2.11.2 (درخواست ۱۰) — meta: اطلاعات جامع گوشی/برنامه (برند، مدل،
     * SDK، صفحه‌نمایش، زبان، رم، نصب اولیه…) که پنل LM در دکمهٔ «جزئیات
     * دستگاه» نشان می‌دهد.
     */
    private fun sendAppHeartbeatIfNeeded() {
        if (serverUrl.isBlank()) return
        val sp = getSharedPreferences(PREFS, 0)
        val last = sp.getLong("last_app_heartbeat", 0L)
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return
        sp.edit().putLong("last_app_heartbeat", System.currentTimeMillis()).apply() /* جلوگیری از انباشت */
        Thread {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                ) ?: ""
                if (deviceId.isBlank()) return@Thread
                val role = try { BuildConfig.FLAVOR } catch (_: Exception) { "agency" } /* agency / tech */
                val payload = JSONObject()
                    .put("deviceId", deviceId)
                    .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("role", role)
                /* v2.11.4 — توکن FCM (اگر راه‌اندازی شده) همراه تپ قلب به
                 * سرور لایسنس هم می‌رود تا دستگاه در LM هم پوش بگیرد. */
                try {
                    val fcmTok = PushClient.token(this@MainActivity)
                    if (fcmTok.isNotBlank()) payload.put("fcmToken", fcmTok)
                } catch (_: Exception) { /* اختیاری */ }
                /* v2.11.2 — مشخصات جامع گوشی/برنامه برای «جزئیات دستگاه» در پنل لایسنس */
                try {
                    val dm = resources.displayMetrics
                    val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(memInfo)
                    val pm = packageManager
                    val pkgInfo = pm.getPackageInfo(packageName, 0)
                    val meta = JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("device", Build.DEVICE)
                        .put("product", Build.PRODUCT)
                        .put("android", Build.VERSION.RELEASE)
                        .put("sdk", Build.VERSION.SDK_INT.toString())
                        .put("security", android.os.Build.VERSION.SECURITY_PATCH ?: "")
                        .put("app", BuildConfig.VERSION_NAME)
                        .put("role", if (role == "tech") "سرویس‌کار" else "مدیر (نمایندگی)")
                        .put("screen", "${dm.widthPixels}x${dm.heightPixels}")
                        .put("density", "${dm.densityDpi}dpi (${String.format("%.1f", dm.density)}x)")
                        .put("locale", java.util.Locale.getDefault().toLanguageTag())
                        .put("memory", String.format("%.1f GB", memInfo.totalMem / 1073741824.0))
                        .put("package", packageName)
                        .put("firstInstall", pkgInfo.firstInstallTime.toString())
                        .put("lastUpdate", pkgInfo.lastUpdateTime.toString())
                        .put("timezone", java.util.TimeZone.getDefault().id)
                    payload.put("meta", meta)
                } catch (_: Exception) { /* meta اختیاری است */ }
                val conn = URL(serverUrl.trimEnd('/') + "/api/app-heartbeat").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                CookieManager.getInstance().getCookie(serverUrl)?.let { conn.setRequestProperty("Cookie", it) }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.inputStream.close()
                conn.disconnect()
                if (code !in 200..299) {
                    /* fail-soft: تلاش بعدی در فرصت بعدی */
                    getSharedPreferences(PREFS, 0).edit().putLong("last_app_heartbeat", last).apply()
                }
            } catch (_: Exception) {
                getSharedPreferences(PREFS, 0).edit().putLong("last_app_heartbeat", 0L).apply()
            }
        }.start()
    }

    // ═════════════════════ چرخهٔ عمر ═════════════════════
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { web.saveState(outState) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        // v2.11.0 — با بازگشت به برنامه، poll فوری + بررسی بروزرسانی
        startBackgroundNotifyPolling()
        sendAppHeartbeatIfNeeded() // v2.11.1 — تپ قلب دستگاه
        showNotificationWarningIfNeeded()
        ensureFreshContent(periodic = true)
    }

    override fun onPause() {
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        versionCheckHandler.removeCallbacks(versionCheckTask)
        stopBackgroundNotifyPolling()
        try { unregisterReceiver(apkDownloadReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ═════════════════════ launchers مجوزها ═════════════════════
    private val geoPermission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (cb != null && origin != null) {
            cb.invoke(origin, granted, false)
        }
    }
    private val storagePermission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_LONG).show()
    }
    private val notifPermission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            postSystemNotification(
                "اعلان‌های سهند سرویس فعال شد",
                "از این پس اعلان‌ها به‌صورت سیستمی نمایش داده می‌شوند",
                "notify-welcome", "/"
            )
        } else {
            getSharedPreferences(PREFS, 0).edit().putBoolean("notif_denied_once", true).apply()
        }
    }
    private val micPermission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingAudioRequest
        pendingAudioRequest = null
        if (granted) req?.grant(req.resources) else req?.deny()
    }
}
