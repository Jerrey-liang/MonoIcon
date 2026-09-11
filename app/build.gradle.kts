plugins {
    alias(libs.plugins.android.application)
    // kotlin-android is NOT needed with AGP 9.x — AGP has built-in Kotlin support
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jerrey.monoicon"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jerrey.monoicon"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "keepRules/rules.keep"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // Phase 3.18-A: BuildConfig.DEBUG gates verbose hook logging
        // (debug builds → logd enabled for verification; release → off)
        buildConfig = true
    }

    // LSPosed META-INF/xposed files are placed in src/main/resources/
    // and automatically packaged into the APK by AGP.
}

dependencies {
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // LSPosed — libxposed API 101 (compileOnly: framework 运行时提供)
    compileOnly(libs.libxposed.api)
    // libxposed service (XposedProvider) 必须打包进 APK — Android 系统直接实例化 provider
    implementation(libs.libxposed.service)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)

    // Android Test
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
