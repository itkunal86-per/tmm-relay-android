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
        
        // Enable multi-dex if needed for large AAR files
        multiDexEnabled = true
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
        
        debug {
            isMinifyEnabled = false
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
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

repositories {
    google()
    mavenCentral()
    flatDir {
        dirs("libs")
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

    // Protocol Buffers - Required for Trimble SDK (BlueBottle classes)
    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("com.google.protobuf:protobuf-java-util:3.25.1")

    // Coroutines (background work)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Trimble Catalyst SDK - Import as module (CORRECT WAY)
    implementation(project(":CatalystFacade"))

    // Local AAR libraries from libs folder
    // These libraries are used for Trimble SDK functionality:
    // - Trimble.Licensing.Android: Required for SDK licensing (initialized via TrimbleLicensingUtil)
    // - trimble.jssi.core-release: Core JSSI library (used by CatalystFacade)
    // - trimble.jssi.android.communicators-release: Android communicators for JSSI (used by CatalystFacade)
    // - JTDDTransformation-release: Coordinate transformation library (available via CoordinateTransformUtil)
    // - empowerlib-1.2.0.26: Empower library for additional Trimble functionality (may contain BlueBottle classes)
    // Note: AAR files are included with transitive dependencies to ensure all required classes are available
    implementation(mapOf("name" to "empowerlib-1.2.0.26", "ext" to "aar")) {
        isTransitive = true
    }
    implementation(mapOf("name" to "JTDDTransformation-release", "ext" to "aar")) {
        isTransitive = true
    }
    implementation(mapOf("name" to "trimble.jssi.android.communicators-release", "ext" to "aar")) {
        isTransitive = true
    }
    implementation(mapOf("name" to "trimble.jssi.core-release", "ext" to "aar")) {
        isTransitive = true
    }
    implementation(mapOf("name" to "Trimble.Licensing.Android", "ext" to "aar")) {
        isTransitive = true
    }

    // Testing
   testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
