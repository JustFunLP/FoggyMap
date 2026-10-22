import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables (GitHub Actions secrets) – never from the repository.
val signingFile = System.getenv("FOGGY_KEYSTORE_FILE")

// Address of the group sync server, written by the "Koop-Server" workflow. Not a secret.
val defaultServer: String = rootProject.file("server/deployed-server-url.txt").let { f ->
    if (f.exists()) f.readText().trim().takeIf { Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$").matches(it) } ?: "" else ""
}

android {
    namespace = "de.foggymap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.foggymap.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("FOGGY_VERSION_CODE") ?: "300").toInt()
        versionName = "1.0." + (System.getenv("FOGGY_VERSION_CODE") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
    }

    signingConfigs {
        if (signingFile != null && file(signingFile).exists()) {
            create("release") {
                storeFile = file(signingFile)
                storePassword = System.getenv("FOGGY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOGGY_KEY_ALIAS")
                keyPassword = System.getenv("FOGGY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is deliberately off: MapLibre (JNI) and JTS run unshrunk, which removes a whole class of
            // device-only crashes. The APK is larger (~35 MB) but behaves exactly like the tested debug build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") } }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.locationtech.jts:jts-core:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
