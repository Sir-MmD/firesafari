plugins {
    id("com.android.application")
}

// Firefox's engine. Bump this regularly: it ships inside the APK, so security fixes
// only arrive when the app is rebuilt. Versions: https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/
val geckoviewVersion = "157.0.20261005135250"

android {
    namespace = "app.firesafari"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.firesafari"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign with this machine's debug key so updates install
            // over each other. Use a real keystore if you ever distribute it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // GeckoView bundles native code for every ABI; build one APK per ABI instead of one huge APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoviewVersion")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
