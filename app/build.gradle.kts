plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acapan.island"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.acapan.island"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
