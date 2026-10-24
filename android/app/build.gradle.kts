import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kemzy.liveavatar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kemzy.liveavatar"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "KEMZY_API_BASE_URL", "\"https://kemzy-liveavatar.seravellenyravalen.workers.dev\"" )
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

val faceLandmarkerModel = layout.projectDirectory.file("src/main/assets/face_landmarker.task")
val downloadFaceLandmarkerModel by tasks.registering {
    outputs.file(faceLandmarkerModel)
    doLast {
        val target = faceLandmarkerModel.asFile
        if (!target.exists() || target.length() < 1_000_000L) {
            target.parentFile.mkdirs()
            val connection = URL("https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task")
                .openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
            connection.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(target.exists() && target.length() > 1_000_000L) {
            "MediaPipe face_landmarker.task download failed or returned an incomplete file."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadFaceLandmarkerModel) }

dependencies {
    val camerax = "1.5.2"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
