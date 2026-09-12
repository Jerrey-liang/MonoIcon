plugins {
    alias(libs.plugins.android.application)
    // kotlin-android is NOT needed with AGP 9.x — AGP has built-in Kotlin support
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jerrey.monoicon"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.jerrey.monoicon"
        // Android 15 (API 35) and newer only — the miuix-blur glass engine needs the
        // AGSL runtime-shader path (API 33+) and we no longer ship a fallback.
        minSdk = 35
        targetSdk = 36
        versionCode = 9
        versionName = "1.5.1"

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

    // Miuix — MIUI/HyperOS-style Compose UI for the settings screen
    // (0.9.x: UI in miuix-ui, icons in miuix-icons)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // miuix-blur: 液态玻璃底栏（与 LSPosed Manager 同引擎：图层录制 + 降采样金字塔模糊 + SDF 高光）
    implementation(libs.miuix.blur)

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
