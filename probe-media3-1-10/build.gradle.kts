// 官方 Media3 1.10 适配器隔离不稳定音频接口，并严格绑定指定的 1.10.1。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.adapter.media3.v1_10"
    compileSdk = 36

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
    api(project(":probe-adapter-api"))
    // AudioSink 属于 Media3 不稳定 API，所有组件必须使用同一已验证版本。
    implementation(libs.media3v110.exoplayer) {
        version { strictly(libs.versions.media3v110.get()) }
    }
    implementation(libs.media3v110.hls) {
        version { strictly(libs.versions.media3v110.get()) }
    }
    constraints {
        implementation(libs.media3v110.common) {
            version { strictly(libs.versions.media3v110.get()) }
        }
        implementation(libs.media3v110.container) {
            version { strictly(libs.versions.media3v110.get()) }
        }
        implementation(libs.media3v110.datasource) {
            version { strictly(libs.versions.media3v110.get()) }
        }
        implementation(libs.media3v110.decoder) {
            version { strictly(libs.versions.media3v110.get()) }
        }
        implementation(libs.media3v110.extractor) {
            version { strictly(libs.versions.media3v110.get()) }
        }
        implementation(libs.media3v110.database) {
            version { strictly(libs.versions.media3v110.get()) }
        }
    }
    compileOnly(libs.annotation)

    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-media3-1.10.1", project.version.toString())
}
