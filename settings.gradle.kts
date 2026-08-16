// 探针工程按公共合同、匹配核心和 Android 媒体实现拆分。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "m3u8-ad-audio-probe"
include(":probe-api", ":probe-core", ":probe")
