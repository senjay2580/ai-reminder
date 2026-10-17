plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号取自 git tag(CI 传 -Ptag=v1.2.3):发版只要推 tag,不用手改这里
val ver = (findProperty("tag") as String? ?: "v0.0.1").removePrefix("v")
val (vMajor, vMinor, vPatch) = ver.split(".").map(String::toInt)

android {
    namespace = "com.senjay.reminder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.senjay.reminder"
        minSdk = 26
        targetSdk = 34
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = ver
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 固定签名:覆盖安装要求新旧 APK 同一签名,CI 和本地构建都用这一个
    signingConfigs {
        create("app") {
            storeFile = file("reminder.keystore")
            storePassword = "aireminder"
            keyAlias = "reminder"
            keyPassword = "aireminder"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("app") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
    }

    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // android.jar 里的 org.json 是桩,单测要真实现
}
