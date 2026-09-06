package com.sahandservice.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * PushMsgService — v2.11.4 — دریافت اعلان‌های پوش FCM
 *
 * سرور لایسنس (رلهٔ v1/push) پیام را از طریق FCM HTTP v1 می‌فرستد؛
 * این سرویس آن را دریافت کرده و با همان سازوکار اعلان‌های داخلیِ
 * برنامه (NotificationHub — کانال‌ها + دیپ‌لینک app://sahand/open) نمایش
 * می‌دهد؛ یعنی کلیک روی اعلان دقیقاً مثل اعلان‌های polling رفتار می‌کند.
 *
 * پیام می‌تواند notification یا data باشد؛ هر دو پشتیبانی می‌شوند
 * (data: title/body/url — notification: title/body).
 */
class PushMsgService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        try {
            val data = remoteMessage.data
            val title = (data["title"] ?: remoteMessage.notification?.title) ?: "اعلان جدید"
            val body = (data["body"] ?: remoteMessage.notification?.body) ?: ""
            val url = data["url"] ?: "/"
            val tag = data["tag"] ?: ("fcm-" + (remoteMessage.messageId ?: System.currentTimeMillis().toString()))

            NotificationHub.post(this, title, body, tag, url)
        } catch (_: Exception) { /* fail-soft */ }
    }

    /** توکن تازه (چرخش توکن/حذف و نصب مجدد) → دوباره ثبت روی پنل */
    override fun onNewToken(token: String) {
        try {
            getSharedPreferences(MainActivity.PREFS, 0).edit()
                .putString("fcm_token", token)
                .putBoolean("fcm_ok", true)
                .apply()
            PushClient.registerToken(this, token)
        } catch (_: Exception) { /* fail-soft */ }
    }
}
