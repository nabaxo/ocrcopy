plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing secrets live outside the repo, in ~/.gradle/gradle.properties.
val signingKeys = listOf(
    "OCRCOPY_STORE_FILE",
    "OCRCOPY_STORE_PASSWORD",
    "OCRCOPY_KEY_ALIAS",
    "OCRCOPY_KEY_PASSWORD",
)
val signing = signingKeys.associateWith { providers.gradleProperty(it).orNull }
val missingSigningKeys = signing.filterValues { it.isNullOrBlank() }.keys

android {
    namespace = "dev.nabaxo.ocrcopy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.nabaxo.ocrcopy"
        // 26 = adaptive icons exist, so no PNG fallback icons are needed.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Modern phones only. Drops 32-bit ARM and x86 copies of the ML Kit/CameraX native libs.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (missingSigningKeys.isEmpty()) {
                storeFile = file(signing.getValue("OCRCOPY_STORE_FILE")!!)
                storePassword = signing.getValue("OCRCOPY_STORE_PASSWORD")
                keyAlias = signing.getValue("OCRCOPY_KEY_ALIAS")
                keyPassword = signing.getValue("OCRCOPY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Fail release packaging loudly instead of producing an unsigned or debug-signed APK.
// Debug builds and other tasks are unaffected.
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    doFirst {
        if (missingSigningKeys.isNotEmpty()) {
            throw GradleException(
                "Release signing not configured. Add to ~/.gradle/gradle.properties: " +
                    missingSigningKeys.joinToString(", "),
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val cameraX = "1.4.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Only ContentCopy/CameraAlt are used; R8 strips the rest.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Bundled Latin model: shipped inside the APK, no download, works offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
