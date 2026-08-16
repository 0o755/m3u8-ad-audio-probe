// 播放门面只依赖稳定 SPI，不绑定 Media3 或具体播放器实现。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.player"
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
    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-player", project.version.toString())
}
