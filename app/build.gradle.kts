plugins {
    id("com.android.application")
}

// App version with one decimal place. CI passes the next number (-PappVersion=1.1);
// local builds default to 1.0.
val appVersion = (findProperty("appVersion") as String?) ?: "1.0"
require(Regex("""\d+\.\d""").matches(appVersion)) { "appVersion must look like 1.0, got $appVersion" }
val appVersionCode = appVersion.split(".").let { (major, minor) -> major.toInt() * 10 + minor.toInt() }

android {
    namespace = "com.bcam.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bcam.app"
        minSdk = 26
        targetSdk = 34
        // One decimal place: 1.0, 1.1 … 1.9, 2.0. versionCode 1.4 -> 14 (always goes up).
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: appVersionCode
    }


    buildTypes {
        getByName("release") {
            // R8 removes unused code and resources for a smaller, faster APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned here: CI signs it with the private key (see .github/workflows/build.yml).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

 // Experimental only: private libraries are absent from production builds.
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
    implementation("dev.n2k02:updater:0.1.11")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
