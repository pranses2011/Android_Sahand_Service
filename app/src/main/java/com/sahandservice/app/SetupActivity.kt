package com.sahandservice.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * SetupActivity — اتصال اپ به پنل (آدرس سرور + اعتبارسنجی)
 *
 * اعتبارسنجی (v2.10.0 منطق — حفظ شد):
 *   GET /api → JSON با message=="Hello, world!"  یا  GET /version.json → version غیرخالی
 * v2.11.0 — padding نوارهای سیستمی (هدر زیر نوار اعلان).
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var urlLayout: TextInputLayout
    private lateinit var connectBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var currentServerText: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        /* v2.11.0 — padding نوارهای سیستمی (مثل MainActivity) */
        val root = findViewById<View>(android.R.id.content)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = false

        urlInput = findViewById(R.id.urlInput)
        urlLayout = findViewById(R.id.urlLayout)
        connectBtn = findViewById(R.id.connectBtn)
        clearBtn = findViewById(R.id.clearBtn)
        statusText = findViewById(R.id.statusText)
        currentServerText = findViewById(R.id.currentServerText)

        val saved = getSharedPreferences(MainActivity.PREFS, 0).getString(MainActivity.KEY_URL, null)
        if (saved != null) {
            currentServerText.visibility = View.VISIBLE
            currentServerText.text = getString(R.string.current_server, saved)
            clearBtn.visibility = View.VISIBLE
            urlInput.setText(saved)
        }

        connectBtn.setOnClickListener { validateAndSave() }
        clearBtn.setOnClickListener {
            getSharedPreferences(MainActivity.PREFS, 0).edit().remove(MainActivity.KEY_URL).apply()
            currentServerText.visibility = View.GONE
            clearBtn.visibility = View.GONE
            urlInput.setText("")
            setStatus(R.string.cleared, busy = false)
        }
    }

    private fun validateAndSave() {
        if (busy) return
        var url = urlInput.text?.toString()?.trim() ?: ""
        if (url.isEmpty()) {
            urlLayout.error = getString(R.string.url_required)
            return
        }
        urlLayout.error = null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        url = url.trimEnd('/')
        busy = true
        setStatus(R.string.checking, busy = true)

        CoroutineScope(Dispatchers.Main).launch {
            val result = checkPanel(url)
            busy = false
            when (result) {
                is PanelOk -> {
                    setStatus(R.string.connected_ok, busy = false)
                    getSharedPreferences(MainActivity.PREFS, 0).edit()
                        .putString(MainActivity.KEY_URL, url).apply()
                    // کوکی‌های قبلی پاک شود تا نشست کهنه باعث خطا نشود
                    try {
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
                is PanelNotSahand -> setStatus(R.string.not_sahand, busy = false)
                is PanelUnreachable -> setStatus(R.string.unreachable, busy = false)
            }
        }
    }

    private fun setStatus(resId: Int, busy: Boolean) {
        statusText.text = getString(resId)
        statusText.visibility = View.VISIBLE
        connectBtn.isEnabled = !busy
        connectBtn.text = if (busy) getString(R.string.checking_btn) else getString(R.string.connect_btn)
    }

    private suspend fun checkPanel(url: String): PanelResult = withContext(Dispatchers.IO) {
        try {
            val apiBody = httpGet("$url/api", 8000)
            var ok = false
            if (apiBody != null) {
                try { ok = JSONObject(apiBody).optString("message") == "Hello, world!" } catch (_: Exception) {}
            }
            if (!ok) {
                val verBody = httpGet("$url/version.json", 8000)
                if (verBody != null) {
                    try { ok = JSONObject(verBody).optString("version").isNotEmpty() } catch (_: Exception) {}
                }
            }
            if (ok) PanelOk else PanelNotSahand
        } catch (_: Exception) {
            PanelUnreachable
        }
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                }
            } else null
            conn.disconnect()
            body
        } catch (_: Exception) { null }
    }

    private sealed class PanelResult
    private object PanelOk : PanelResult()
    private object PanelNotSahand : PanelResult()
    private object PanelUnreachable : PanelResult()
}
