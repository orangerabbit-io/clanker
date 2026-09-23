import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    iosArm64 {
        binaries.framework {
            baseName = "clanker"
            isStatic = true
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "clanker"
            isStatic = true
        }
    }
    // JVM target compiles the common UI code locally (no Android SDK needed);
    // verified via :composeApp:compileKotlinJvm — the standard CMP app-module shape.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // HttpClient is part of the App/ConnectFlow/ChatViewModel signatures; shared's
            // implementation dep does not leak, so declare ktor-client-core explicitly.
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
            // ChatViewModel serializes ReasoningBlocks for persistence.
            implementation(libs.kotlinx.serialization.json)
            implementation(compose.materialIconsExtended)
            // Markdown renderer (Task 8 Step 4): multiplatform-markdown-renderer-m3
            // + code highlighting; must resolve on common/jvm targets.
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.code)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            // Production SQLite driver for the app database (Task 8 wiring).
            implementation(libs.sqldelight.android.driver)
        }
        // Darwin engine required by MainViewController.kt (iOS only).
        // iosMain shared source set is not present in this KMP config; add dependency
        // to each iOS target's main source set individually.
        val iosArm64Main by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
    }
}

android {
    namespace = "io.orangerabbit.clanker"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.orangerabbit.clanker"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // Release signing driven by environment variables (see CI release.yml).
        // Falls back to debug signing when env vars absent (local dev).
        val keystoreFile = System.getenv("CLANKER_KEYSTORE")
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("CLANKER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLANKER_KEY_ALIAS")
                keyPassword = System.getenv("CLANKER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
