plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.volumemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.volumemanager"
        minSdk = 23
        // Target 33 on purpose: the handset this is built for (Realme 6, Android 10/11)
        // is unaffected by Android 14+ foreground-service-type enforcement, and keeping
        // targetSdk below 34 avoids needing the "specialUse" FGS subtype on that device.
        targetSdk = 33
        versionCode = 6
        versionName = "1.3.2"
    }

    // CI supplies these env vars; local builds simply produce an unsigned APK.
    signingConfigs {
        create("release") {
            val storePass = System.getenv("VM_STORE_PASSWORD")
            if (!storePass.isNullOrBlank()) {
                storeFile = file("release.jks")
                storePassword = storePass
                keyAlias = System.getenv("VM_KEY_ALIAS") ?: "volume"
                keyPassword = System.getenv("VM_KEY_PASSWORD") ?: storePass
            }
        }
    }
    val releaseSigning = signingConfigs.getByName("release")

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    // RMX2001 is arm64-v8a: ship only that ABI to keep the release APK small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
