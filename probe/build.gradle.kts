// 对外 AAR 聚合规则加载、协调状态机与 Media3 音频探针。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe"
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
    implementation(project(":probe-core"))
    // AudioSink 属于 Media3 不稳定 API，首版必须整套锁定 1.9.2，拒绝静默混版。
    implementation(libs.media3.exoplayer) {
        version { strictly(libs.versions.media3.get()) }
    }
    implementation(libs.media3.hls) {
        version { strictly(libs.versions.media3.get()) }
    }
    compileOnly(libs.annotation)

    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe", project.version.toString())
}
