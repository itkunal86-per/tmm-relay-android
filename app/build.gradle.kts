plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hirenq.tmmrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hirenq.tmmrelay"
        minSdk = 29  // Android 10
        targetSdk = 35  // Android 15 (future-proof for Android 16)
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "!q2w3e4R"
            keyAlias = "tmmrelay"
            keyPassword = "!q2w3e4R"
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    
}

repositories {
    google()
    mavenCentral()
    flatDir {
        dirs("lib")
    }
}

dependencies {

    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle (for foreground service safety)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    
    // LocalBroadcastManager (for service-activity communication)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Networking (for API relay)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines (background work)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Trimble Catalyst SDK - AAR files from app/lib folder
    // fileTree automatically includes all .aar files from the lib directory
    implementation(fileTree(mapOf("dir" to "lib", "include" to listOf("*.aar"))))

    // Testing
   testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
