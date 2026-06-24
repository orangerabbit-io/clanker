plugins {
    alias(libs.plugins.android.application) // AGP 9 compiles Kotlin built-in; no kotlin.android
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
}

android {
    namespace = "io.orangerabbit.clanker"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.orangerabbit.clanker"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Built-in Kotlin defaults kotlin.compilerOptions.jvmTarget to targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:agent"))
    implementation(project(":core:designsystem"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.markdown.renderer.m3)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
}
