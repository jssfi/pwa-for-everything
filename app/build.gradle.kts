import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from keystore.properties (not committed):
//   storeFile=/abs/path/to/key.jks  storePassword=...  keyAlias=...  keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "fi.jss.pwa"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.jss.pwa"
        minSdk = 29
        targetSdk = 36
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core"))
    // Signs the generated per-site APKs on the device.
    implementation("com.android.tools.build:apksig:8.13.0")
}

/** Puts the unsigned per-site app template (:site) into this app's assets as site-template.apk. */
abstract class CopySiteTemplate : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val apk = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }!!.single()
        // SiteApkBuilder replaces these entries by path.
        ZipFile(apk).use { zip ->
            for (entry in listOf("res/drawable-nodpi-v4/site_icon.png", "assets/site.json")) {
                check(zip.getEntry(entry) != null) { "site template is missing $entry" }
            }
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        apk.copyTo(File(out, "site-template.apk"))
    }
}

val copySiteTemplate = tasks.register<CopySiteTemplate>("copySiteTemplate") {
    dependsOn(":site:assembleRelease")
    apkDir.set(project(":site").layout.buildDirectory.dir("outputs/apk/release"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySiteTemplate, CopySiteTemplate::outputDir)
    }
}
