// Android 运行时承载门面、规则缓存和生命周期，不绑定具体媒体播放器。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.runtime"
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
    coordinates(project.group.toString(), "ad-audio-probe-runtime", project.version.toString())
}
