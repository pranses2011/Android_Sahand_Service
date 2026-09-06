package com.sahandservice.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * NotifyService — v2.11.1 — سرویس پیش‌زمینهٔ اعلان‌ها
 *
 * درخواست کاربر: «هیچ نوتیفیکیشنی از دست نرود».
 * قبل از این نسخه poll اعلان فقط داخل MainActivity بود → با بسته شدن
 * (یا swipe کردن) اپ، هیچ اعلانی نمایش داده نمی‌شد. این سرویس:
 *  • foreground است (نوتیف دائمی کم‌اهمیت «هم‌گام‌سازی فعال») → اندروید
 *    آن را نمی‌کشد؛
 *  • هر ۶۰ ثانیه /api/chat?unread=mine و اعلان‌های سرویسکار/سرویس‌های
 *    در انتظار نمایندگی را می‌شمارد و با افزایش، اعلان HIGH می‌دهد؛
 *  • START_STICKY → اگر سیستم آن را کشت، دوباره بالا می‌آید؛
 *  • BootReceiver بعد از ری‌استارت گوشی آن را فعال می‌کند؛
 *  • شمارنده‌ها در NotificationHub مشترک‌اند → با poll خودِ Activity
 *    هیچ اعلان تکراری صادر نمی‌شود.
 */
class NotifyService : Service() {

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
        const val FIRST_POLL_DELAY_MS = 8_000L

        @Volatile private var started = false

        /** شروع امن (اگر سرور تنظیم شده) — از MainActivity و BootReceiver صدا زده می‌شود */
        fun start(context: Context) {
            try {
                val sp = context.getSharedPreferences(MainActivity.PREFS, 0)
                val url = sp.getString(MainActivity.KEY_URL, "") ?: ""
                if (url.isBlank()) return
                val intent = Intent(context, NotifyService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) { /* fail-soft */ }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollTask = object : Runnable {
        override fun run() {
            try { pollOnce() } catch (_: Exception) {}
            /* v2.11.4 — راه‌اندازی/تازه‌سازی پوش FCM هر ~۱۰ دقیقه (fail-soft) —
             * حتی وقتی Activity بسته است؛ سرویس همیشه زنده است. */
            try {
                pollCount++
                if (pollCount % 10 == 1) PushClient.ensureSetup(this@NotifyService)
            } catch (_: Exception) { /* بی‌اثر */ }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    private var pollCount = 0

    override fun onCreate() {
        super.onCreate()
        NotificationHub.ensureChannels(this)
        startForegroundInternal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        if (!polling) {
            polling = true
            handler.postDelayed(pollTask, FIRST_POLL_DELAY_MS)
        }
        /* START_STICKY — اگر سیستم سرویس را کشت، دوباره اجرا می‌شود */
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacks(pollTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** نوتیف دائمی کم‌اهمیت (الزام foreground service) */
    private fun startForegroundInternal() {
        try {
            val open = Intent(this, MainActivity::class.java)
            val pi = android.app.PendingIntent.getActivity(
                this, 0, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, NotificationHub.CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("سهند سرویس")
                .setContentText("هم‌گام‌سازی اعلان‌ها فعال است")
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .setShowWhen(false)
                .setContentIntent(pi)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, notif)
            }
        } catch (_: Exception) { /* fail-soft */ }
    }

    /* ═══════════ polling (همان منطق MainActivity — با hub مشترک) ═══════════ */

    private fun sp(): SharedPreferences = getSharedPreferences(MainActivity.PREFS, 0)
    private fun serverUrl(): String = sp().getString(MainActivity.KEY_URL, "") ?: ""

    private fun openConnection(urlStr: String, method: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val base = serverUrl()
        CookieManager.getInstance().getCookie(base)?.let { conn.setRequestProperty("Cookie", it) }
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun fetchBody(path: String): String? {
        return try {
            val conn = openConnection(serverUrl().trimEnd('/') + path, "GET")
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

    private fun pollOnce() {
        val base = serverUrl()
        if (base.isBlank()) return

        /* نقش کاربر از SharedPreferences (MainActivity آن را ذخیره می‌کند) */
        val panel = sp().getString("user_panel", null)
        val techId = sp().getString("user_tech_id", null)
        NotificationHub.userPanel = panel
        NotificationHub.techId = techId

        /* ۱) پیام‌های چت خوانده‌نشده */
        val chatBody = fetchBody("/api/chat?unread=mine")
        if (chatBody != null) {
            try {
                val count = JSONObject(chatBody).optInt("unreadCount", 0)
                val delta = NotificationHub.takeChatDelta(count)
                if (delta > 0) {
                    NotificationHub.post(
                        this,
                        "پیام جدید",
                        if (delta == 1) "یک پیام خوانده‌نشده دارید — برای مشاهده چت را باز کنید"
                        else "$delta پیام خوانده‌نشده دارید — برای مشاهده چت را باز کنید",
                        "chat-unread-bg", "/"
                    )
                }
            } catch (_: Exception) {}
        }

        /* ۲) اعلان سرویس‌کار / سرویس‌های در انتظار نمایندگی */
        val isTech = panel == "technician" && !techId.isNullOrEmpty()
        val path = if (isTech) "/api/notification?technicianId=$techId"
                   else "/api/entity?type=service&status=pending&_pageSize=200"
        val body = fetchBody(path)
        if (body != null) {
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
                    val delta = NotificationHub.takeNotifDelta(count)
                    if (delta > 0) {
                        if (isTech) {
                            NotificationHub.post(
                                this, "اعلان جدید",
                                if (delta == 1) "یک اعلان خوانده‌نشده جدید دارید" else "$delta اعلان خوانده‌نشده جدید دارید",
                                "notif-bg", "/"
                            )
                        } else {
                            NotificationHub.post(
                                this, "سرویس جدید",
                                if (delta == 1) "یک سرویس جدید در انتظار بررسی است" else "$delta سرویس جدید در انتظار بررسی است",
                                "notif-bg", "/"
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
