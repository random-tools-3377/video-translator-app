import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Bump both numbers for every build meant to be installed: Android only installs a
// higher versionCode over an older one.
val versionProps = Properties().apply { file("version.properties").inputStream().use { load(it) } }

// The Whisper model the release APK bundles (whisperModel in version.properties). Each model sits
// in its own folder, models/<name>/models/ggml-<name>.bin, put there by scripts/fetch-models.sh
// with its sha256 checked; the release build takes assets from that one folder only.
val whisperModel: String = versionProps.getProperty("whisperModel")
val whisperModelBytes = mapOf("medium-q5_0" to 539_212_467L, "large-v3-q5_0" to 1_081_140_203L)[whisperModel]
    ?: throw GradleException("whisperModel=$whisperModel in version.properties is not one this build knows")
val whisperModelDir = rootProject.file("models/$whisperModel")
val releaseModels = mapOf(
    File(whisperModelDir, "models/ggml-$whisperModel.bin") to whisperModelBytes,
    // whisper.cpp's speech detector, bundled in every build.
    file("src/main/assets/models/ggml-silero-v6.2.0.bin") to 885_098L,
)

// Debug builds exist for the emulator test. CI passes -Pvt.debugAbis=x86_64 to skip arm64.
val debugAbis = (providers.gradleProperty("vt.debugAbis").orNull ?: "x86_64,arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Release signing comes from repository secrets via environment variables (see .github/workflows).
val keystorePath: String? = System.getenv("VT_KEYSTORE_FILE")

android {
    namespace = "app.videotranslator"
    compileSdk = 36
    providers.gradleProperty("vt.ndkVersion").orNull?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "app.videotranslator"
        minSdk = 33
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WHISPER_MODEL_ASSET", "\"models/ggml-$whisperModel.bin\"")
        buildConfigField("String", "WHISPER_MODEL_NAME", "\"${whisperModel.substringBefore("-q")}\"")

        externalNativeBuild {
            cmake {
                // Optimised native code in every build type: an unoptimised whisper.cpp is many
                // times slower. CMakeLists.txt also forces -O3, this keeps CMake's own flags in step.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VT_STORE_PASSWORD")
                keyAlias = System.getenv("VT_KEY_ALIAS")
                keyPassword = System.getenv("VT_KEY_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            isJniDebuggable = false
            ndk { abiFilters += "arm64-v8a" }
            // Without the keystore the release APK comes out unsigned, and CI's signature check fails.
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
        }
        debug {
            ndk { abiFilters += debugAbis }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("release") { assets.srcDir(whisperModelDir) }
    }

    androidResources {
        // The model is read straight out of the APK, so it must be stored, not compressed.
        noCompress += listOf("bin", "mp4", "gguf")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val checkReleaseModel by tasks.registering {
    description = "Fails the release build if a bundled model is missing."
    val models = releaseModels
    doLast {
        for ((model, bytes) in models) {
            if (!model.isFile || model.length() != bytes) {
                throw GradleException(
                    "Missing or wrong-sized ${model.name} at $model. Run scripts/fetch-models.sh release vad first."
                )
            }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseModel) }

dependencies {
    implementation(project(":llm"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // The in-app player (plays the picked file; no network: CI checks the APK has no such permission).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
