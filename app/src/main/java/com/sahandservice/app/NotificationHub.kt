package com.sahandservice.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * NotificationHub — v2.11.1 — مرکز مشترک اعلان‌های سیستمی
 *
 * درخواست کاربر: «هنوز هم نوتیفیکیشن نمایش داده نمیشه. ارتباط بین برنامه
 * و اپلیکیشن و نوتیفیکیشن رو کامل و دقیق بررسی بکن و کامل اصلاحش بکن.»
 *
 * مشکل قبلی: شمارنده‌های unread فقط در حافظهٔ MainActivity بودند →
 * ۱) با بستن اپ، poll متوقف می‌شد (سرویسی وجود نداشت)؛
 * ۲) اولین poll فقط baseline می‌گذاشت و پیام‌های موجودِ خوانده‌نشده
 *    هرگز اعلان نمی‌شدند؛
 * ۳) MainActivity و سرویس جدید اگر هر دو poll می‌کردند اعلان تکراری
 *    می‌دادند.
 *
 * راه‌حل: وضعیت مشترک (شمارنده‌ها) + پست اعلان + کانال‌ها همه اینجا؛
 * هم Activity و هم NotifyService از همین یک مرکز استفاده می‌کنند →
 * رفتار یکسان و بدون تکرار (synchronized).
 */
object NotificationHub {

    const val CHANNEL_ALERTS = "sahand_notifications"        // HIGH — پیام/اعلان
    const val CHANNEL_STATUS = "sahand_service_status"       // LOW — نوتیف دائمی سرویس

    /* ── وضعیت مشترک (بین Activity و Service) ── */
    var chatUnread: Int = -1
    var notifUnread: Int = -1
    var fbUnread: Int = -1   /* v2.11.5 — پاسخ‌های خوانده‌نشدهٔ مدیر سامانه */
    var userPanel: String? = null   // "agency" | "technician"
    var techId: String? = null

    @Synchronized
    fun takeChatDelta(count: Int): Int {
        val prev = chatUnread
        val delta = when {
            count <= 0 -> 0
            prev < 0 -> count          /* نخستین poll: پیام‌های موجود هم اعلان می‌شوند */
            count > prev -> count - prev
            else -> 0
        }
        chatUnread = count
        return delta
    }

    @Synchronized
    fun takeNotifDelta(count: Int): Int {
        val prev = notifUnread
        val delta = when {
            count <= 0 -> 0
            prev < 0 -> count          /* نخستین poll: اعلان‌های موجود هم می‌آیند */
            count > prev -> count - prev
            else -> 0
        }
        notifUnread = count
        return delta
    }

    @Synchronized
    fun takeFbDelta(count: Int): Int {
        val prev = fbUnread
        val delta = when {
            count <= 0 -> 0
            prev < 0 -> 0   /* نخستین poll فقط خط پایه — اعلان تکراری نمی‌دهد */
            count > prev -> count - prev
            else -> 0
        }
        fbUnread = count
        return delta
    }

    @Synchronized
    fun reset() {
        chatUnread = -1
        notifUnread = -1
        fbUnread = -1
    }

    /* ── کانال‌ها ── */
    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ALERTS) == null) {
            val ch = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.description = context.getString(R.string.notif_channel_desc)
            ch.enableVibration(true)
            mgr.createNotificationChannel(ch)
        }
        if (mgr.getNotificationChannel(CHANNEL_STATUS) == null) {
            val ch = NotificationChannel(
                CHANNEL_STATUS,
                "وضعیت هم‌گام‌سازی",
                NotificationManager.IMPORTANCE_MIN
            )
            ch.description = "نوتیف دائمی برای فعال ماندن دریافت اعلان‌ها در پس‌زمینه"
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
        }
    }

    /** آیا اعلان‌ها واقعاً قابل نمایشند؟ (مجوز + کانال) */
    fun reallyEnabled(context: Context): Boolean {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!mgr.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = mgr.getNotificationChannel(CHANNEL_ALERTS) ?: return true
            if (ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** آیا مجوز POST_NOTIFICATIONS (اندروید ۱۳+) داده شده؟ */
    fun permissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** پست اعلان با کانال HIGH — برمی‌گرداند نمایش داده شد یا نه */
    fun post(context: Context, title: String, body: String, tag: String, url: String = "/"): Boolean {
        try {
            if (!reallyEnabled(context)) return false
            ensureChannels(context)
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = Uri.parse("app://sahand/open?tag=" + Uri.encode(tag) + "&url=" + Uri.encode(url))
            }
            val pi = PendingIntent.getActivity(
                context, tag.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setColor(0xFF2563EB.toInt())

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false

            mgr.notify(tag, (tag.hashCode() and 0x3FFFFFFF), builder.build())
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
