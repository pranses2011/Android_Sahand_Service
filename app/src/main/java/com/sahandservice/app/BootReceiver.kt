package com.sahandservice.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — v2.11.1 — بعد از روشن شدن گوشی، سرویس اعلان‌ها دوباره
 * فعال می‌شود (اگر کاربر قبلاً سرور را تنظیم کرده و وارد شده باشد)
 * تا «هیچ نوتیفیکیشنی از دست نرود».
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.MY_PACKAGE_REPLACED"
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
