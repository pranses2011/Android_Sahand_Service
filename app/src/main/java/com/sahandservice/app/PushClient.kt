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
 * PushClient — v2.11.4 — راه‌اندازی و ثبت اعلان پوش FCM (Firebase)
 *
 * درخواست کاربر: «چرا در اپ یا گوشی موبایل اعلان پوش (FCM) نیست؟ شاید
 * علت اینکه نوتیفیکیشن‌ها نشان داده نمی‌شوند همین باشد — چکش کن و
 * برطرفش کن.»
 *
 * معماری (بدون نیاز به google-services.json و بدون بازسازی APK برای
 * هر پروژهٔ Firebase):
 *  ۱) «پیکربندی Firebase» (projectId/apiKey/appId/senderId) از خودِ پنل
 *     گرفته می‌شود: GET /api/push?action=fcm-config — پنل آن را از سرور
 *     لایسنس می‌گیرد و مدیر ارشد آن را در پنل مدیریت لایسنس (تنظیمات ←
 *     اعلان پوش FCM) وارد کرده است.
 *  ۲) Firebase در زمان اجرا مقداردهی می‌شود (FirebaseApp.initializeApp
 *     با FirebaseOptions) و توکن دستگاه گرفته می‌شود.
 *  ۳) توکن با کوکی نشست کاربر به پنل ثبت می‌شود (POST /api/push)؛ پنل
 *     آن را برای خودش و برای سرور لایسنس نگه می‌دارد → پیام چت/اعلان
 *     سرویس‌کار/پاسخ انتقاد، «لحظه‌ای» روی گوشی می‌رسد (حتی با اپِ بسته).
 *  ۴) polling قبلی (NotifyService هر ۶۰ ثانیه) به‌عنوان پشتیبان زنده
 *     می‌ماند — اگر FCM تنظیم نشده بود همان کار می‌کند.
 *
 * همه‌چیز fail-soft است: نبود اینترنت/تنظیمات/مجوز هیچ خطایی در اپ
 * نمی‌سازد و بعداً دوباره تلاش می‌شود.
 */
object PushClient {

    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_FCM_TRIED = "fcm_setup_last"
    private const val KEY_FCM_OK = "fcm_ok"

    fun sp(context: Context): SharedPreferences = context.getSharedPreferences(MainActivity.PREFS, 0)

    fun token(context: Context): String = sp(context).getString(KEY_FCM_TOKEN, "") ?: ""

    /** آیا FCM با موفقیت راه‌اندازی شده؟ (برای ارسال توکن همراه تپ قلب) */
    fun isReady(context: Context): Boolean = sp(context).getBoolean(KEY_FCM_OK, false)

    /**
     * راه‌اندازی کامل: گرفتن پیکربندی ← مقداردهی Firebase ← توکن ← ثبت.
     * هر ۳۰ دقیقه حداکثر یک‌بار تلاش می‌کند (در صورت موفقیت، هر ۷ روز
     * فقط تازه‌سازی توکن).
     */
    fun ensureSetup(context: Context, force: Boolean = false) {
        try {
            val sp = sp(context)
            val serverUrl = sp.getString(MainActivity.KEY_URL, "") ?: ""
            if (serverUrl.isBlank()) return
            val last = sp.getLong(KEY_FCM_TRIED, 0L)
            val now = System.currentTimeMillis()
            val ok = sp.getBoolean(KEY_FCM_OK, false)
            /* موفق: هر ۷ روز یک‌بار تازه‌سازی؛ ناموفق: هر ۳۰ دقیقه */
            val interval = if (ok) 7 * 24 * 3600_000L else 30 * 60_000L
            if (!force && now - last < interval) return
            sp.edit().putLong(KEY_FCM_TRIED, now).apply()

            Thread {
                try {
                    /* ۱) پیکربندی از پنل (با کوکی نشست) */
                    val cfg = fetchConfig(serverUrl)
                    if (cfg == null || !cfg.optBoolean("enabled", false)) {
                        sp.edit().putBoolean(KEY_FCM_OK, false).apply()
                        return@Thread
                    }
                    /* ۲) مقداردهی Firebase در زمان اجرا */
                    if (!initFirebase(context, cfg.optJSONObject("config") ?: return@Thread)) {
                        sp.edit().putBoolean(KEY_FCM_OK, false).apply()
                        return@Thread
                    }
                    /* ۳) توکن (روی نخ اصلی Firebase لازم است) */
                    val token = com.google.android.gms.tasks.Tasks.await(
                        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    )
                    if (token.isNullOrBlank()) {
                        sp.edit().putBoolean(KEY_FCM_OK, false).apply()
                        return@Thread
                    }
                    sp.edit().putString(KEY_FCM_TOKEN, token).putBoolean(KEY_FCM_OK, true).apply()

                    /* ۴) ثبت روی پنل */
                    registerToken(context, token)
                } catch (_: Exception) {
                    /* fail-soft — تلاش بعدی */
                    sp.edit().putBoolean(KEY_FCM_OK, false).apply()
                }
            }.start()
        } catch (_: Exception) { /* fail-soft */ }
    }

    /** مقداردهی برنامهٔ Firebase با FirebaseOptions (idempotent) */
    private fun initFirebase(context: Context, cfg: JSONObject): Boolean {
        return try {
            val projectId = cfg.optString("projectId", "")
            val apiKey = cfg.optString("apiKey", "")
            val appId = cfg.optString("appId", "")
            val senderId = cfg.optString("senderId", "")
            if (projectId.isBlank() || apiKey.isBlank() || appId.isBlank()) return false

            val app = try {
                com.google.firebase.FirebaseApp.getInstance("sahand-push")
            } catch (_: IllegalStateException) {
                null
            }
            if (app != null) return true /* قبلاً مقداردهی شده */

            val options = com.google.firebase.FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setGcmSenderId(if (senderId.isBlank()) projectId else senderId)
                .build()
            com.google.firebase.FirebaseApp.initializeApp(context, options, "sahand-push")
            true
        } catch (_: Exception) {
            false
        }
    }

    /** GET /api/push?action=fcm-config — با کوکی نشست (مثل NotifyService) */
    private fun fetchConfig(serverUrl: String): JSONObject? {
        return try {
            val conn = openConn(serverUrl.trimEnd('/') + "/api/push?action=fcm-config", "GET")
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val txt = conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { it.readText() }
                }
                if (txt.isBlank()) null else JSONObject(txt)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** POST /api/push {fcmToken, deviceId} — ثبت توکن روی پنل (fail-soft) */
    fun registerToken(context: Context, token: String) {
        try {
            if (token.isBlank()) return
            val sp = sp(context)
            val serverUrl = sp.getString(MainActivity.KEY_URL, "") ?: ""
            if (serverUrl.isBlank()) return
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
            Thread {
                try {
                    val payload = JSONObject()
                        .put("fcmToken", token)
                        .put("deviceId", deviceId)
                    val conn = openConn(serverUrl.trimEnd('/') + "/api/push", "POST")
                    try {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        conn.responseCode
                    } finally {
                        conn.disconnect()
                    }
                } catch (_: Exception) { /* fail-soft */ }
            }.start()
        } catch (_: Exception) { /* fail-soft */ }
    }

    private fun openConn(urlStr: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/json")
        /* کوکی نشست پنل — مثل NotifyService (CookieManager سراسری WebView) */
        try {
            val u = URL(urlStr)
            val base = buildString { append(u.protocol).append("://").append(u.host) }
            CookieManager.getInstance().getCookie(base)?.let { conn.setRequestProperty("Cookie", it) }
        } catch (_: Exception) { /* بی‌اثر */ }
        return conn
    }
}
