plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val vCode: Int = (project.findProperty("vCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.mytv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.barukonair.app"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = "1.0.$vCode"
        buildConfigField("String", "REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: ""}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        resourceConfigurations += listOf("en")
    }

    // Fixed key so every new build installs OVER the old one (keeps your playlists).
    signingConfigs {
        create("own") {
            storeFile = file("keystore.jks")
            storePassword = "mytv123456"
            keyAlias = "mytv"
            keyPassword = "mytv123456"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("own")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/*.version",
            "DebugProbesKt.bin",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
