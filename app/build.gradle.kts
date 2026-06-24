import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun projectProperty(name: String, defaultValue: String = ""): String {
    return localProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: defaultValue
}

fun String.asBuildConfigString(): String {
    return replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.cahdz.alexa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cahdz.alexa"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "GEMINI_MODEL", "\"gemini-3.1-flash-live-preview\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${projectProperty("SPOTIFY_CLIENT_ID").asBuildConfigString()}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${projectProperty("SPOTIFY_CLIENT_SECRET").asBuildConfigString()}\"")
        buildConfigField(
            "String",
            "SPOTIFY_REDIRECT_URI",
            "\"${projectProperty("SPOTIFY_REDIRECT_URI", "com.cahdz.alexa://spotify-auth").asBuildConfigString()}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.media)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Firebase / Gemini
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.ai)

    // Wake word
    implementation(libs.openwakeword)
    implementation(libs.onnxruntime)

    // Coroutines + Serialization
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)

    // Spotify App Remote SDK (AAR local — see libs/ folder)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Utils
    implementation(libs.gson)
}
