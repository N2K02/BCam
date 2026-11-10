buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has Kotlin built in; this picks the Kotlin version it uses.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
