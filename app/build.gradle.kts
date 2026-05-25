import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun localProp(key: String): String = localProperties.getProperty(key)?.trim()?.trim('"') ?: ""

android {
    namespace = "com.impairedvision.guideglass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.impairedvision.guideglass"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val geminiApiKey = localProp("GEMINI_API_KEY")
        val googleMapsKey = localProp("GOOGLE_MAPS_KEY")
        val googleDirectionsKey = localProp("GOOGLE_DIRECTIONS_KEY")

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GOOGLE_MAPS_KEY", "\"$googleMapsKey\"")
        buildConfigField("String", "GOOGLE_DIRECTIONS_KEY", "\"$googleDirectionsKey\"")

        resValue("string", "google_maps_key", googleMapsKey.ifEmpty { "MISSING_GOOGLE_MAPS_KEY" })
        resValue(
                "string",
                "google_directions_key",
                googleDirectionsKey.ifEmpty { "MISSING_GOOGLE_DIRECTIONS_KEY" }
        )
        resValue("string", "gemini_api_key", geminiApiKey.ifEmpty { "MISSING_GEMINI_API_KEY" })

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // This ensures the native MediaPipe libraries for both
            // real phones (arm) and emulators (x86) are included.
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        packaging {
            jniLibs {
                // This is CRITICAL. It tells Android NOT to compress the libs,
                // which often helps emulators find them.
                useLegacyPackaging = true

                // This prevents Gradle from "picking" only one and stripping others
                pickFirsts.add("**/*.so")
            }
        }
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
        viewBinding = true
        mlModelBinding = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Lifecycle — pinned explicitly to prevent transitive version conflicts
    val lifecycle_version = "2.7.0"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-viewmodel:$lifecycle_version")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("com.google.android.libraries.places:places:3.3.0")

    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // MediaPipe & TensorFlow Lite (keeping for future proofing or remove if unused, but adding ML Kit)
    implementation("com.google.mediapipe:tasks-vision:0.10.32")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // ML Kit Object Detection for Obstacle Avoidance
    implementation("com.google.mlkit:object-detection:17.0.2")
    // Google AI / Gemini
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")

    // Retrofit + Moshi for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}