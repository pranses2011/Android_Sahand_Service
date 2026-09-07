plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sahandservice.app"
    compileSdk = 35

    defaultConfig {
        // پکیج پایه — flavor ها پسوند می‌گذارند:
        //   agency → com.sahandservice.app.agency
        //   tech   → com.sahandservice.app.tech
        applicationId = "com.sahandservice.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 18
        versionName = "2.11.7"
    }

    /* v2.11.0 — دو نسخهٔ اپ: نمایندگی و سرویس‌کار (مطابق v2.10.0 قبلی) */
    flavorDimensions += "role"
    productFlavors {
        create("agency") {
            dimension = "role"
            applicationId = "com.sahandservice.app.agency"
            resValue("string", "appDisplayName", "سهند سرویس | نمایندگی")
            resValue("string", "appRoleName", "پنل مدیریت نمایندگی")
        }
        create("tech") {
            dimension = "role"
            applicationId = "com.sahandservice.app.tech"
            resValue("string", "appDisplayName", "سهند سرویس | سرویس‌کار")
            resValue("string", "appRoleName", "پنل سرویس‌کار")
        }
    }

    val keystoreFile = rootProject.file("keystore/SahandService-release.keystore")
    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KS_PASS") ?: "sahand-service-2026"
                keyAlias = System.getenv("KS_ALIAS") ?: "sahandservice"
                keyPassword = System.getenv("KS_KEYPASS") ?: "sahand-service-2026"
                /* v2.11.5 — Play Protect (item 1): همهٔ طرح‌های امضا روشن —
                 * بعضی دستگاه‌ها/Play Protect روی APKهای فقط-V2 هشدار می‌دهند */
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true // v2.11.1 — BuildConfig.FLAVOR/VERSION_NAME برای تپ قلب اپ
    }
}

dependencies {
    /* v2.11.4 — اعلان پوش FCM: مقداردهی در زمان اجرا (PushClient) —
     * بدون پلاگین google-services و بدون google-services.json؛
     * پیکربندی از پنل (LM) گرفته می‌شود. */
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
