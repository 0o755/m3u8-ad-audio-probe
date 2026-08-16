// 匹配核心保持纯 Java，便于状态机和算法在 JVM 上完整测试。
plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(libs.gson)
    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-core", project.version.toString())
}
