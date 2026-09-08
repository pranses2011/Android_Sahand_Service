package com.sahandservice.app

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OneSignalClient — v2.12.1 — اعلان پوش OneSignal (درخواست کاربر)
 *
 * «ببین از سرویس OneSignal میشه براش استفاده کرد؟ اگه میشه همانند FCM
 * تنظیماتش رو قرار بده تا اعمال کنم.»
 *
 * معماری (قرینهٔ PushClient):
 *  ۱) App ID از پنل گرفته می‌شود: GET /api/push?action=onesignal-config
 *     (پنل آن را از سرور لایسنس می‌گیرد — مدیر ارشد در «پنل مدیریت لایسنس ←
 *      تنظیمات ← اعلان پوش OneSignal» وارد کرده است).
 *  ۲) OneSignal SDK در زمان اجرا مقداردهی می‌شود (OneSignal.initWithContext).
 *  ۳) external_id = deviceId گوشی (OneSignal.login) — سرور با همین شناسه
 *     اعلان هدفمند به دستگاه این کاربر می‌فرستد.
 *  ۴) نمایش اعلانها خودکار توسط خود OneSignal است (کانال پیش‌فرض HIGH)؛
 *     polling قبلی (NotifyService) و FCM (PushClient) پشتیبان می‌مانند.
 *
 * همه‌چیز fail-soft: نبود اینترنت/تنظیمات هیچ خطایی نمی‌سازد و بعداً
 * دوباره تلاش می‌شود (حداکثر هر ۳۰ دقیقه؛ پس از موفقیت هر ۷ روز).
 */
object OneSignalClient {

    private const val KEY_OS_TRIED = "onesignal_setup_last"
    private const val KEY_OS_OK = "onesignal_ok"
    private const val KEY_OS_APPID = "onesignal_app_id"

    fun sp(context: Context): SharedPreferences = context.getSharedPreferences(MainActivity.PREFS, 0)

    /** آیا OneSignal با موفقیت مقداردهی شده؟ */
    fun isReady(context: Context): Boolean = sp(context).getBoolean(KEY_OS_OK, false)

    /**
     * راه‌اندازی کامل: گرفتن App ID ← init ← login(deviceId).
     * ناموفق: هر ۳۰ دقیقه تلاش دوباره؛ موفق: هر ۷ روز تازه‌سازی.
     */
    fun ensureSetup(context: Context, force: Boolean = false) {
        try {
            val sp = sp(context)
            val serverUrl = sp.getString(MainActivity.KEY_URL, "") ?: ""
            if (serverUrl.isBlank()) return
            val last = sp.getLong(KEY_OS_TRIED, 0L)
            val now = System.currentTimeMillis()
            val ok = sp.getBoolean(KEY_OS_OK, false)
            val interval = if (ok) 7 * 24 * 3600_000L else 30 * 60_000L
            if (!force && now - last < interval) return
            sp.edit().putLong(KEY_OS_TRIED, now).apply()

            Thread {
                try {
                    /* ۱) پیکربندی از پنل (با کوکی نشست — مثل NotifyService) */
                    val cfg = fetchConfig(serverUrl)
                    if (cfg == null || !cfg.optBoolean("enabled", false)) {
                        sp.edit().putBoolean(KEY_OS_OK, false).apply()
                        return@Thread
                    }
                    val appId = cfg.optJSONObject("config")?.optString("appId", "") ?: ""
                    if (appId.isBlank()) {
                        sp.edit().putBoolean(KEY_OS_OK, false).apply()
                        return@Thread
                    }

                    /* ۲) مقداردهی SDK — فقط اگر App ID عوض شده یا هنوز init نشده */
                    val knownAppId = sp.getString(KEY_OS_APPID, "") ?: ""
                    if (knownAppId != appId) {
                        initOnSignalThread(context, appId)
                        sp.edit().putString(KEY_OS_APPID, appId).apply()
                    } else if (!sp.getBoolean(KEY_OS_OK, false)) {
                        initOnSignalThread(context, appId)
                    }

                    /* ۳) external_id = deviceId — سرور با همین شناسه پوش می‌فرستد */
                    val deviceId = android.provider.Settings.Secure.getString(
                        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                    if (deviceId.isNotBlank()) {
                        try {
                            com.onesignal.OneSignal.login(deviceId)
                        } catch (_: Exception) { /* fail-soft */ }
                    }
                    sp.edit().putBoolean(KEY_OS_OK, true).apply()
                } catch (_: Exception) {
                    sp.edit().putBoolean(KEY_OS_OK, false).apply()
                }
            }.start()
        } catch (_: Exception) { /* fail-soft */ }
    }

    /** OneSignal باید روی نخ اصلی مقداردهی شود — initWithContext(context, appId) (API 5.1) */
    private fun initOnSignalThread(context: Context, appId: String) {
        try {
            (context as? android.app.Activity)?.runOnUiThread {
                try {
                    com.onesignal.OneSignal.initWithContext(context, appId)
                } catch (_: Exception) { /* fail-soft */ }
            } ?: run {
                android.os.Handler(context.mainLooper).post {
                    try {
                        com.onesignal.OneSignal.initWithContext(context, appId)
                    } catch (_: Exception) { /* fail-soft */ }
                }
            }
        } catch (_: Exception) { /* fail-soft */ }
    }

    /** GET /api/push?action=onesignal-config — با کوکی نشست */
    private fun fetchConfig(serverUrl: String): JSONObject? {
        return try {
            val conn = openConn(serverUrl.trimEnd('/') + "/api/push?action=onesignal-config", "GET")
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                /* v2.12.1 — نشست لغزیده: Set-Cookie پاسخ را در CookieManager
                 * WebView ذخیره می‌کنیم تا سرویس پس‌زمینه هرگز از نشست نیفتد
                 * (ریشهٔ خاموشی بی‌صداِ اعلانها پس از چند روز). */
                syncCookies(conn, serverUrl)
                val txt = conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                }
                if (txt.isBlank()) null else JSONObject(txt)
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    /* ═══ v2.12.1 — ریشهٔ واقعی «نوتیفیکیشن فعال است ولی نشان نمی‌دهد» ═══
     * سرور برای نشست لغزنده (v2.12.12) در پاسخهای poll کوکی تازه
     * (Set-Cookie) می‌فرستد؛ اما HttpURLConnection آن را نادیده می‌گیرد
     * (ذخیرهٔ کوکی کار خود WebView/مرورگر است). نتیجه: کوکی ذخیره‌شدهٔ
     * اپ بعد از انقضای عمر نشست (۷ روز) می‌میرد؛ همهٔ pollهای سرویس
     * اعلان ۴۰۱ می‌خورند و اعلانها «بی‌صدا» خاموش می‌شوند — بدون هیچ خطا.
     * راه‌حل: هر پاسخ موفق، Set-Cookieهای آن در CookieManager مشترک
     * ذخیره می‌شود؛ مثل خود WebView. (عمومی — PushClient هم استفاده می‌کند) */
    fun syncCookies(conn: HttpURLConnection, pageUrl: String) {
        try {
            val setCookies: List<String> = conn.headerFields?.get("Set-Cookie") ?: return
            if (setCookies.isEmpty()) return
            val u = URL(pageUrl)
            val base = buildString {
                append(u.protocol).append("://").append(u.host)
                if (u.port > 0 && u.port != 80 && u.port != 443) append(":").append(u.port)
            }
            for (c in setCookies) {
                val pair = c.substringBefore(';').trim()
                if (pair.isEmpty()) continue
                android.webkit.CookieManager.getInstance().setCookie(base, "$pair; Path=/")
            }
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) { /* fail-soft */ }
    }

    private fun openConn(urlStr: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val u = URL(urlStr)
            val base = buildString { append(u.protocol).append("://").append(u.host) }
            CookieManager.getInstance().getCookie(base)?.let { conn.setRequestProperty("Cookie", it) }
        } catch (_: Exception) { /* بی‌اثر */ }
        return conn
    }
}
