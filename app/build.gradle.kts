plugins {
    id("com.android.application")
}

android {
    namespace = "ink.grootnibbel.launch"
    compileSdk = 36

    defaultConfig {
        applicationId = "ink.grootnibbel.launch"
        minSdk = 26
        // Staying at 26 keeps us off the package-visibility rules that would otherwise hide the
        // launchable apps behind a <queries> list.
        targetSdk = 26
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    kotlin {
        jvmToolchain(21)
    }
}
