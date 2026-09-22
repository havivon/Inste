plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.havivon.reelslab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.havivon.reelslab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// No libraries on purpose: a plain Activity plus a WebView keeps the build
// from depending on anything that has to resolve at compile time.
dependencies {
}
