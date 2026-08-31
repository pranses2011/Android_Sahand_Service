package com.sahandservice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class SetupActivity : AppCompatActivity() {

    private lateinit var inputLayout: TextInputLayout
    private lateinit var input: TextInputEditText
    private lateinit var connectBtn: MaterialButton
    private lateinit var clearBtn: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var currentServerText: TextView
    private lateinit var panelInfoText: TextView

    // B-07: پرچم هشدار http — فقط بار اول متوقف می‌شود، تلاش دوم عبور می‌کند (سرور LAN)
    private var insecureWarned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        inputLayout = findViewById(R.id.urlLayout)
        input = findViewById(R.id.urlInput)
        connectBtn = findViewById(R.id.connectBtn)
        clearBtn = findViewById(R.id.clearBtn)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        currentServerText = findViewById(R.id.currentServerText)
        panelInfoText = findViewById(R.id.panelInfoText)

        val prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(MainActivity.KEY_URL, "").orEmpty()

        if (saved.isNotEmpty()) {
            currentServerText.visibility = View.VISIBLE
            currentServerText.text = getString(R.string.current_server, saved)
            clearBtn.visibility = View.VISIBLE
            input.setText(saved)
        }

        clearBtn.setOnClickListener {
            prefs.edit().remove(MainActivity.KEY_URL).apply()
            currentServerText.visibility = View.GONE
            clearBtn.visibility = View.GONE
            input.text?.clear()
            statusText.text = getString(R.string.cleared)
            panelInfoText.text = ""
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                validateAndSave()
                true
            } else false
        }

        connectBtn.setOnClickListener { validateAndSave() }
    }

    private fun validateAndSave() {
        val raw = input.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            inputLayout.error = getString(R.string.url_required)
            return
        }
        inputLayout.error = null

        val url = normalize(raw)

        // B-07: هشدار اتصال رمزنگاری‌نشده — مسدود نمی‌کنیم (سرورهای LAN) اما کاربر باید بداند
        if (url.startsWith("http://") && !insecureWarned) {
            insecureWarned = true
            setStatus(R.string.insecure_warning, false)
            inputLayout.error = getString(R.string.insecure_warning)
            return // یک بار توقف برای هشدار؛ دوباره زدن دکمه ادامه می‌دهد
        }
        inputLayout.error = null

        setStatus(R.string.checking, true)

        lifecycleScope.launch {
            val result = checkPanel(url)
            when (result) {
                is PanelResult.Ok -> {
                    getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .edit().putString(MainActivity.KEY_URL, url).apply()
                    setStatus(R.string.connected_ok, false)
                    panelInfoText.text = result.info
                    startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                    finish()
                }
                is PanelResult.NotSahand -> {
                    setStatus(R.string.not_sahand, false)
                }
                is PanelResult.Unreachable -> {
                    setStatus(R.string.unreachable, false)
                }
            }
        }
    }

    private fun setStatus(resId: Int, busy: Boolean) {
        statusText.text = getString(resId)
        statusText.visibility = View.VISIBLE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        connectBtn.isEnabled = !busy
        connectBtn.text = if (busy) getString(R.string.checking_btn) else getString(R.string.connect_btn)
    }

    private fun normalize(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    private fun httpGet(urlStr: String, timeoutMs: Int): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SahandAndroidApp/2.6.1")
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { s ->
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = s.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > 1_000_000) break
                    }
                    buf.toByteArray()
                }
                String(bytes, StandardCharsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkPanel(url: String): PanelResult = withContext(Dispatchers.IO) {
        // 1) health check: GET /api → {"message":"Hello, world!"}
        val apiBody = httpGet("$url/api", 8000)
        var isSahand = false
        if (apiBody != null) {
            try {
                val msg = JSONObject(apiBody).optString("message", "")
                if (msg == "Hello, world!") isSahand = true
            } catch (_: Exception) {
            }
        }
        if (!isSahand) {
            // 2) fallback: version.json
            val vBody = httpGet("$url/version.json", 8000)
            if (vBody != null) {
                try {
                    val v = JSONObject(vBody).optString("version", "")
                    if (v.isNotEmpty()) isSahand = true
                } catch (_: Exception) {
                }
            }
        }
        if (!isSahand) return@withContext PanelResult.Unreachable

        // gather version info for display
        var info = ""
        try {
            val vBody = httpGet("$url/version.json", 6000)
            if (vBody != null) {
                val o = JSONObject(vBody)
                val v = o.optString("version", "")
                val name = o.optString("versionName", "")
                info = if (name.isNotEmpty()) getString(R.string.panel_version_fmt, v, name)
                else getString(R.string.panel_version_short, v)
            }
        } catch (_: Exception) {
        }
        PanelResult.Ok(info)
    }

    private sealed class PanelResult {
        data class Ok(val info: String) : PanelResult()
        object NotSahand : PanelResult()
        object Unreachable : PanelResult()
    }
}
