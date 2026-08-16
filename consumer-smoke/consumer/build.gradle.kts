// 故意不声明 Media3、Gson、API 或 core，Release APK 验证完整传递依赖与 R8 合同。
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fongmi.adaudio.probe.smoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fongmi.adaudio.probe.smoke"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    val probeVersion = providers.gradleProperty("probeVersion")
        .orElse("0.1.0-SNAPSHOT")
    implementation("io.github.0o755:ad-audio-probe:${probeVersion.get()}")
}
