plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The web app shell shared by the launcher app (:app) and the per-site app template (:site).
android {
    namespace = "fi.jss.pwa.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}
