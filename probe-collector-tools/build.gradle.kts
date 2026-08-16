// 采集器工具提供指纹产出与 HLS 候选扫描，不向宿主暴露 PCM 或播放器实现。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.tools"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":probe-api"))
    api(project(":probe-adapter-api"))
    implementation(project(":probe-core"))
    compileOnly(libs.annotation)

    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-collector-tools",
        project.version.toString())
}
