// The language model (llama.cpp) as its own native library, libvtllama.so. It is a separate
// module because llama.cpp carries a different version of ggml from whisper.cpp, and each
// module's native code is one CMake project. The Kotlin side lives in the app
// (app.videotranslator.llm).
plugins {
    alias(libs.plugins.android.library)
}

// As in the app: CI passes -Pvt.debugAbis=x86_64 for the emulator.
val debugAbis = (providers.gradleProperty("vt.debugAbis").orNull ?: "x86_64,arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "app.videotranslator.llm"
    compileSdk = 36
    providers.gradleProperty("vt.ndkVersion").orNull?.let { ndkVersion = it }

    defaultConfig {
        minSdk = 33
        externalNativeBuild {
            cmake {
                // Optimised in every build type (llama-build.cmake also forces -O3).
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isJniDebuggable = false
            ndk { abiFilters += "arm64-v8a" }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
