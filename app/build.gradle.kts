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
            storeFile = file("C:/Users/andre/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.andre.airpodscompanion"
        minSdk = 23
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.24"
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

tasks.register<Copy>("copySamsungDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-arm64-v8a-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "AirPodsCompanion-${android.defaultConfig.versionName}-build${android.defaultConfig.versionCode}-arm64-v8a-debug.apk" }
}
