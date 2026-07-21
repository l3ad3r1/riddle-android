plugins {
    id("com.android.application")
}

android {
    namespace = "com.riddleapp.diary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riddleapp.diary"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // ML Kit ships native handwriting libs for every ABI, which quadruples the APK. The target
        // tablet is arm64-v8a; drop this filter if the app ever needs to run on 32-bit devices.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    // On-device handwriting recognition, so remembered pages can be carried as text instead of images.
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
