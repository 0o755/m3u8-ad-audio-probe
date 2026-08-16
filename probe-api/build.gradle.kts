// 公共合同是纯 Java 模块，不向宿主暴露 Android 或 Media3 类型。
plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    testImplementation(libs.junit)
}

mavenPublishing {
    coordinates(project.group.toString(), "ad-audio-probe-api", project.version.toString())
}
