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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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

    // D7 (Phase 2): EmbeddingEngine for EmbeddingGemma 2 zero-shot classification
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // D6: ML Kit genai deps (genai-prompt/summarization/image-description) and
    // litert-gpu were stripped until the phases that use them. ML Kit injected
    // INTERNET/READ_PHONE_STATE-era permissions and ~0 MB (thin AICore client);
    // litert-gpu's legacy manifest (org.tensorflow.lite.gpu.api targetSdk < 4)
    // implied READ_PHONE_STATE + storage permissions and ~11 MB of native libs.
    // Core litert stays for the Phase 2 engine; both return with their phase.

    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Pure-JVM unit tests for the deterministic pipeline (hasher, rules engine)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
