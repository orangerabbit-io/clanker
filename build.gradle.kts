// All plugins declared here with `apply false` so every module shares one classpath version.
// Note: with AGP 9 built-in Kotlin, android modules do NOT apply kotlin.android; KMP libraries
// use the dedicated com.android.kotlin.multiplatform.library plugin.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.metro) apply false
}
