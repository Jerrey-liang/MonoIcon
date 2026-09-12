// Top-level build file for MonoIcon — LSPosed module
// AGP 9.x has built-in Kotlin support, so kotlin-android plugin is not needed.
//
// The Kotlin *version* however is chosen by the KGP on the buildscript
// classpath: AGP 9.3/9.4 bundle KGP 2.2.10, which cannot read Kotlin 2.4
// metadata (MiuiX 0.9 / Haze 2 need it). Bumping AGP does not move Kotlin —
// this classpath entry is the lever.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
