plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every CI build installs as an upgrade over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.photoframe.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.photoframe.tv"
        minSdk = 22 // Fire OS 5 and newer
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed key so new builds can be installed over old ones without uninstalling.
    // Fine for a personal sideloaded app; keep the repository private if that matters to you.
    signingConfigs {
        create("frame") {
            storeFile = file("photoframe.jks")
            storePassword = "photoframe"
            keyAlias = "photoframe"
            keyPassword = "photoframe"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("frame")
        }
        debug {
            signingConfig = signingConfigs.getByName("frame")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.3.3") // 3.3.x still runs on pre-Android-7 Fire OS

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
