import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials live OUTSIDE this public repo — never commit a
// keystore or its passwords. Default location is a keystores folder in the
// developer's home directory; override with -PreleaseKeystoreProps=<path> if
// needed (e.g. from CI, pointing at a path written from a secret).
val releaseKeystorePropsFile = File(
    (project.findProperty("releaseKeystoreProps") as String?)
        ?: "${System.getProperty("user.home")}/keystores/spooler-keystore.properties",
)
val releaseKeystoreProps = Properties().apply {
    if (releaseKeystorePropsFile.exists()) releaseKeystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = releaseKeystoreProps.containsKey("storeFile")

android {
    namespace = "com.bsbagley.spooler"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bsbagley.spooler"
        // POC targets the Pixel 8 Pro only (Android 14+).
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to unsigned if the keystore properties file isn't
            // present (e.g. a fresh checkout without the local keystore) —
            // signingConfig is only set when we actually found credentials.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
