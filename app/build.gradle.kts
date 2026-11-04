plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.termovigia.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.termovigia.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Chave fixa: cada APK novo instala por cima do anterior sem perder o histórico.
    signingConfigs {
        create("termovigia") {
            storeFile = file("termovigia.keystore")
            storePassword = "termovigia123"
            keyAlias = "termovigia"
            keyPassword = "termovigia123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("termovigia")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("termovigia")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
