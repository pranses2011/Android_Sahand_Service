package com.sahandservice.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — v2.11.1 — بعد از روشن شدن گوشی، سرویس اعلان‌ها دوباره
 * فعال می‌شود (اگر کاربر قبلاً سرور را تنظیم کرده و وارد شده باشد)
 * تا «هیچ نوتیفیکیشنی از دست نرود».
 *
 * v2.11.8 (درخواست ۱۵) — اکشن داخلی SAHAND_RESTART_NOTIFY هم پذیرفته
 * می‌شود: زنگ ساعتِ NotifyService.onTimeout (پس از پایان سهمیهٔ ۶ ساعتهٔ
 * dataSync در اندروید ۱۴+) همین اکشن را می‌فرستد تا سرویس اعلان دوباره
 * بالا بیاید و اعلان‌ها قطع نمانند.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: Intent.ACTION_BOOT_COMPLETED
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "com.sahandservice.app.RESTART_NOTIFY"
        ) return
        try {
            val sp = context.getSharedPreferences(MainActivity.PREFS, 0)
            val url = sp.getString(MainActivity.KEY_URL, "") ?: ""
            val loggedIn = sp.getBoolean("user_logged_in", false)
            if (url.isNotBlank() && loggedIn) {
                NotifyService.start(context)
            }
        } catch (_: Exception) { /* fail-soft */ }
    }
}
