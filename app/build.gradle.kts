plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retroamp.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retroamp.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.0.0-neon"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
