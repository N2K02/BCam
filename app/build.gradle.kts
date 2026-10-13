plugins {
    id("com.android.application")
}

android {
    namespace = "com.bcam.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bcam.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // R8 removes unused code and resources for a smaller, faster APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same fixed key as debug, so it installs over earlier builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
