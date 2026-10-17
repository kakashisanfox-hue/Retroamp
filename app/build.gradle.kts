
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

    signingConfigs {
        create("retroampRelease") {
            val keystorePath = System.getenv("RETROAMP_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RETROAMP_STORE_PASSWORD")
                keyAlias = System.getenv("RETROAMP_KEY_ALIAS")
                keyPassword = System.getenv("RETROAMP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!System.getenv("RETROAMP_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("retroampRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
