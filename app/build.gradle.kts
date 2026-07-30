plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ moves the Compose compiler out of composeOptions and into its
    // own Gradle plugin. Applying it enables Compose for this module.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.carwalkaround"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.carwalkaround"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 16 KB page size support: .so files must be stored uncompressed so
            // the loader can mmap them directly from the APK. This is already
            // the default for minSdk >= 23, but it is pinned here because a
            // future minSdk change would silently flip it back and reintroduce
            // a Play Store compliance failure. AGP 8.5.1+ handles the matching
            // 16 KB zip alignment automatically (verify: zipalign -c -P 16 4).
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // --- Compose (managed via BOM so versions stay mutually compatible) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material3 pinned per project spec (README) rather than taken from the BOM.
    implementation("androidx.compose.material3:material3:1.2.1")

    // --- Activity / lifecycle glue for Compose ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // --- CameraX ---
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- On-device OCR for the VIN plate ---
    // The *bundled* Latin model, not the Play-Services-delivered variant: a
    // walkaround happens on a lot or in a garage, where the first-run model
    // download would fail exactly when the app is needed. Costs a few MB of APK
    // and ships native .so files — see the jniLibs packaging note above, which
    // is what keeps those 16 KB-page-size compliant.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Bridges ML Kit's Task API to suspend functions. Worth the small artifact:
    // hand-rolled Task -> continuation wrappers are a reliable way to leak a
    // continuation when the caller's scope is cancelled mid-recognition.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // --- Debug-only tooling for Compose previews / layout inspector ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
