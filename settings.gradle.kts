// 探针工程按公共合同、匹配核心、运行时和可替换媒体适配器拆分。
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
include(
    ":probe-api",
    ":probe-adapter-api",
    ":probe-core",
    ":probe-runtime",
    ":probe-player",
    ":probe-collector-tools",
    ":probe-media3-1-9",
    ":probe"
)
