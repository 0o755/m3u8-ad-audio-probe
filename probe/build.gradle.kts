// 默认聚合 AAR 只组合运行时和官方 Media3 1.9 适配器，不承载业务实现。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.fongmi.adaudio.probe.aggregate"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

}

dependencies {
    api(project(":probe-runtime"))
    // 默认坐标在运行时带入一个官方实现，自定义适配器应直接依赖 probe-runtime。
    runtimeOnly(project(":probe-media3-1-9"))
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe", project.version.toString())
}
