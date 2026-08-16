// 自定义适配器消费者只依赖 runtime，验证不携带任何 Media3 实现也能完成 R8。
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fongmi.adaudio.probe.smoke.custom"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fongmi.adaudio.probe.smoke.custom"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    implementation("io.github.0o755:ad-audio-probe-runtime:${probeVersion.get()}")
    implementation("io.github.0o755:ad-audio-probe-player:${probeVersion.get()}")
    implementation("io.github.0o755:ad-audio-probe-collector-tools:${probeVersion.get()}")
}
