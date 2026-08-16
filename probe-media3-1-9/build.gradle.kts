// 官方 Media3 1.9 适配器隔离不稳定音频接口，并严格绑定已验证的 1.9.2。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.adapter.media3.v1_9"
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
    api(project(":probe-adapter-api"))
    // AudioSink 属于 Media3 不稳定 API，所有组件必须使用同一已验证版本。
    implementation(libs.media3.exoplayer) {
        version { strictly(libs.versions.media3.get()) }
    }
    implementation(libs.media3.hls) {
        version { strictly(libs.versions.media3.get()) }
    }
    constraints {
        implementation(libs.media3.common) {
            version { strictly(libs.versions.media3.get()) }
        }
        implementation(libs.media3.container) {
            version { strictly(libs.versions.media3.get()) }
        }
        implementation(libs.media3.datasource) {
            version { strictly(libs.versions.media3.get()) }
        }
        implementation(libs.media3.decoder) {
            version { strictly(libs.versions.media3.get()) }
        }
        implementation(libs.media3.extractor) {
            version { strictly(libs.versions.media3.get()) }
        }
        implementation(libs.media3.database) {
            version { strictly(libs.versions.media3.get()) }
        }
    }
    compileOnly(libs.annotation)

    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-media3-1.9.2", project.version.toString())
}
