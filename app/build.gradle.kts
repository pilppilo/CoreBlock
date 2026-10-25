plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.coreblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.coreblock"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("com.google.ai.edge.litert:litert:1.1.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.1.1")

    // D6: ML Kit genai deps (genai-prompt/summarization/image-description) were here.
    // Stripped until Phase 3 actually wires AICore — they injected INTERNET,
    // READ_PHONE_STATE, network/storage permissions and ~48 MB of APK bloat.

    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Pure-JVM unit tests for the deterministic pipeline (hasher, rules engine)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
