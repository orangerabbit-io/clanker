// Vendored from the standalone `ui-cyberpunk` library (AGP 8.7 / Kotlin 2.1) into clanker's
// AGP-9 single-classpath build. The public surface is preserved verbatim under `io.orangerabbit.ui.*`
// (the design-system swap contract), so a later switch back to the published coordinate is a
// dependency change, not a source change. AGP 9 compiles Kotlin built-in; no kotlin.android.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.orangerabbit.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The upstream library targets minSdk 33 for the AGSL CRT shader; clanker's floor is 28,
        // so CrtScreen guards the RuntimeShader path at runtime (Build.VERSION >= TIRAMISU).
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx) // WindowCompat in AppTheme
    debugImplementation(libs.compose.ui.tooling)
}
