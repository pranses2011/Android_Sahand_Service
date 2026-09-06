# اپ سهند سرویس — قوانین R8
-keepclassmembers class com.sahandservice.app.MainActivity$NativeBridge { *; }
-keepclassmembers class com.sahandservice.app.MainActivity$FileBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.sahandservice.app.** { *; }
-dontwarn org.json.**
