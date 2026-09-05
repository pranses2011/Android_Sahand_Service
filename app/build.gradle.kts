plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sahandservice.app"
    // v2.10.0 (درخواست کاربر ۹ — Play Protect): target/compile 35 + امضای ثابت
    // (کلید یکسان در همهٔ نسخه‌ها) اعتماد Play Protect را بالا می‌برد.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahandservice.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "2.10.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/sahand-release.jks")
            storePassword = "Sahand@2026"
            keyAlias = "sahand"
            keyPassword = "Sahand@2026"
        }
    }

    flavorDimensions += "app"
    productFlavors {
        create("agency") {
            applicationIdSuffix = ".agency"
            resValue("string", "appDisplayName", "سهند سرویس | نمایندگی")
            resValue("string", "appRole", "پنل مدیریت نمایندگی")
        }
        create("tech") {
            applicationIdSuffix = ".tech"
            resValue("string", "appDisplayName", "سهند سرویس | سرویس‌کار")
            resValue("string", "appRole", "پنل سرویس‌کار")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // خروجی با نام خوانا: SahandService-Agency-v2.4.5.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val map = mapOf("agencyRelease" to "Agency", "techRelease" to "Technician",
                            "agencyDebug" to "Agency-debug", "techDebug" to "Technician-debug")
            val pretty = map[variant.name] ?: variant.name
            outputFileName = "SahandService-${pretty}-v${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // v2.10.0 — BuildConfig برای نسخهٔ فعلی و flavor در به‌روزرسانی خودکار
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
