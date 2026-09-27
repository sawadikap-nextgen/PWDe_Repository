import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional Firebase config. Leave these out of local.properties and the app runs guest-only.
// All three are needed for cloud sync (Firestore needs the project id); see firebase/README.md.
//   pwde.firebase.apiKey=...
//   pwde.firebase.appId=...
//   pwde.firebase.projectId=...
// Optional GabAI button auto-detection (calibration-backend on Cloud Run):
//   pwde.detection.url=https://<service>-<hash>.<region>.run.app
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localProp(key: String): String = localProps.getProperty(key, "").replace("\"", "")

android {
    namespace = "com.pwde.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.pwde.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.3.0-prompt3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_API_KEY", "\"${localProp("pwde.firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${localProp("pwde.firebase.appId")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${localProp("pwde.firebase.projectId")}\"")
        buildConfigField("String", "DETECTION_URL", "\"${localProp("pwde.detection.url")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // The MediaPipe model is memory-mapped straight from the APK, so it must stay uncompressed. The
    // sherpa-onnx keyword spotter weights are read straight from assets for the same reason.
    androidResources {
        noCompress += "task"
        noCompress += "onnx"
    }
    // sherpa-onnx's prebuilt libraries load with System.loadLibrary, so extract them rather than
    // mmap them from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.gson)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.mediapipe.tasks.vision)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose UI tests on the JVM (Robolectric); the test manifest is already a debug dependency.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
