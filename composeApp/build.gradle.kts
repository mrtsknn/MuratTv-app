import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

// Liste adresi koda yazılmaz (depo public): MURATTV_LIST_URL ortam değişkeninden (CI'da GitHub secret) ya da
// local.properties'teki murattv.listUrl'den okunup DEFAULT_URL olarak derlemeye eklenir. Boşsa uygulama adres sorar.
val listUrl = System.getenv("MURATTV_LIST_URL")?.takeIf { it.isNotBlank() } ?: rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("murattv.listUrl") }
    .orEmpty()
val generatedDir = layout.buildDirectory.dir("generated/murattv/kotlin")
generatedDir.get().asFile.resolve("com/murat/yakalatv").apply { mkdirs() }.resolve("BuildSecrets.kt").writeText(
    "package com.murat.yakalatv\n\n/** Derlemede üretilir (composeApp/build.gradle.kts); elle düzenleme. */\n" +
        "const val DEFAULT_URL = \"${listUrl.replace("\"", "")}\"\n",
)

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(generatedDir) }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            // Kanal logoları
            implementation("io.coil-kt.coil3:coil-compose:3.3.0")
        }
        iosMain.dependencies {
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
            implementation("io.ktor:ktor-client-darwin:3.2.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
            implementation("androidx.media3:media3-exoplayer:1.9.0")
            implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
            implementation("androidx.media3:media3-ui:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            // HttpURLConnection PATCH desteklemiyor; Gist API için gerekli
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
        }
    }
}

android {
    namespace = "com.murat.yakalatv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.murat.yakalatv"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Şimdilik debug anahtarıyla imzalanır; mağazaya çıkarken kendi anahtarın gerekir
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

compose.resources {
    packageOfResClass = "com.murat.yakalatv.res"
}
