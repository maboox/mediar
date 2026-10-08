plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// کلید امضا به‌صورت متن base64 در ریپو نگه داشته می‌شود و موقع بیلد به فایل تبدیل می‌شود
val keystoreFile = keystoreFile
if (!keystoreFile.exists()) {
    val b64 = file("mediar-debug.keystore.b64")
    if (b64.exists()) keystoreFile.writeBytes(java.util.Base64.getMimeDecoder().decode(b64.readText()))
}

android {
    namespace = "com.mediar.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mediar.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // کلید امضای ثابت: هر بیلد گیت‌هاب با همین کلید امضا می‌شود تا نسخه جدید
    // بدون پاک کردن اپ (و از دست رفتن دیتا) روی نسخه قبلی نصب شود.
    signingConfigs {
        create("fixed") {
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    // org.json واقعی برای تست‌های JVM (نسخه اندروید در تست فقط stub است)
    testImplementation("org.json:json:20240303")
}
