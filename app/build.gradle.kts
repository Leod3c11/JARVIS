plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_VULKAN=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF"
                )
            }
        }
    }

    buildFeatures { compose = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
