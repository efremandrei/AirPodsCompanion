plugins {
    id("com.android.application")
}

android {
    namespace = "com.andre.airpodscompanion"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE")
                ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.andre.airpodscompanion"
        minSdk = 23
        targetSdk = 35
        versionCode = 32
        versionName = "1.0.31"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

tasks.register<Copy>("copySamsungDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-arm64-v8a-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "AirPodsCompanion-${android.defaultConfig.versionName}-build${android.defaultConfig.versionCode}-arm64-v8a-debug.apk" }
}
