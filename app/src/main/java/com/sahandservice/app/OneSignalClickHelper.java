package com.sahandservice.app;

import com.onesignal.OneSignal;
import com.onesignal.notifications.INotificationClickListener;
import com.onesignal.notifications.INotificationClickEvent;
import org.json.JSONObject;

/**
 * OneSignalClickHelper — v2.12.1 — پل جاوا برای API 5.1 OneSignal
 *
 * متدهای استاتیکِ object کاتلینیِ OneSignal (مثل getNotifications) از
 * Kotlin 2.0 با نام property قابل‌دسترسی نیستند؛ از جاوا مستقیم فراخوانی
 * می‌شوند. کلیک روی اعلان OneSignal → همان دیپ‌لینک نوتیف پنل
 * (pendingNotifUrl در MainActivity).
 */
public final class OneSignalClickHelper {

    /** فراخوانی با URL ناوبری اعلان (فقط وقتی URL معتبر داشته باشد) */
    public interface Handler {
        void onUrl(String url);
    }

    private OneSignalClickHelper() {}

    public static void install(final Handler handler) {
        try {
            OneSignal.getNotifications().addClickListener(new INotificationClickListener() {
                @Override
                public void onClick(INotificationClickEvent event) {
                    try {
                        JSONObject data = event.getNotification().getAdditionalData();
                        String url = data != null ? data.optString("url", "/") : "/";
                        if (url != null && !url.trim().isEmpty() && !"/".equals(url.trim())) {
                            handler.onUrl(url.trim());
                        }
                    } catch (Throwable ignored) {
                        /* fail-soft */
                    }
                }
            });
        } catch (Throwable ignored) {
            /* fail-soft — OneSignal مقداردهی نشده باشد */
        }
    }
}
