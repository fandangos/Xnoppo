plugins {
    id("com.android.application")
}

// Plain Java + framework widgets only (no AndroidX/Kotlin): tiny APK, no dependency churn.
android {
    namespace = "com.fandangos.xnoppocast"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fandangos.xnoppocast"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
