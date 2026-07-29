plugins {
    id("com.android.application")
}

android {
    namespace = "com.andre.airpodscompanion"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.andre.airpodscompanion"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"
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
    rename { "AirPodsCompanion-1.0.4-build5-arm64-v8a-debug.apk" }
}
