plugins {
    id("com.android.application")
}

/*
 * Template for the per-site apps that the launcher app generates on the device (see SiteApkBuilder).
 * The launcher app embeds this APK unsigned and, for each site, patches:
 *   - the package name and label placeholders in AndroidManifest.xml (in place, so they must stay long enough),
 *   - res/drawable-nodpi-v4/site_icon.png (the adaptive icon foreground),
 *   - assets/site.json (the site settings),
 * then signs it on the device.
 */
android {
    namespace = "fi.jss.pwa.site"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.jss.pwa.app.t0000000000000000000000000000000"
        minSdk = 29
        targetSdk = 36
        // Must not be lower than any generated app already installed, so keep it in step with :app.
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        manifestPlaceholders["siteLabel"] = "_".repeat(64)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // No resource shrinking: it renames res/ paths, and the generator needs a known icon path.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = null
        }
    }
}

dependencies {
    implementation(project(":core"))
}
