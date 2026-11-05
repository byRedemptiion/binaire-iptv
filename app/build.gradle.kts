plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fournis par GitHub Actions ; valeurs par défaut pour une compilation locale.
val appVersion: String = System.getenv("APP_VERSION")?.removePrefix("v") ?: "1.0.0"
val appVersionCode: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val updateRepo: String = System.getenv("GITHUB_REPOSITORY") ?: ""
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.binaire.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.binaire.iptv"
        minSdk = 21
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Clé fixe (obligatoire pour les mises à jour) quand elle est fournie, sinon clé de debug.
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}
