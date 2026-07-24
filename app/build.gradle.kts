import java.util.Properties

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
        versionName = "1.0.0"

        // ML Kit ships native handwriting libs for every ABI, which quadruples the APK. The target
        // tablet is arm64-v8a; drop this filter if the app ever needs to run on 32-bit devices.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing comes from an untracked signing.properties beside the project. Without it the
    // release build is simply unsigned, so a clone still builds.
    val signingProps = rootProject.file("signing.properties").takeIf { it.exists() }?.let {
        Properties().apply { it.inputStream().use { stream -> load(stream) } }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
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
