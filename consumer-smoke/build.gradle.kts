// 烟测工程从 Maven Local 核对默认聚合与可替换适配器的真实发布依赖图。
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application") version "8.13.2" apply false
}

val probeVersion = providers.gradleProperty("probeVersion").orElse("0.1.0-SNAPSHOT")

val defaultProbeContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val runtimeProbeContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3AdapterContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3ConflictContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add(defaultProbeContract.name,
        "io.github.0o755:ad-audio-probe:${probeVersion.get()}")
    add(runtimeProbeContract.name,
        "io.github.0o755:ad-audio-probe-runtime:${probeVersion.get()}")
    add(media3AdapterContract.name,
        "io.github.0o755:ad-audio-probe-media3-1.9.2:${probeVersion.get()}")
    add(media3ConflictContract.name,
        "io.github.0o755:ad-audio-probe-media3-1.9.2:${probeVersion.get()}")
    add(media3ConflictContract.name, "androidx.media3:media3-common:1.10.1")
}

fun Configuration.moduleIds(): Set<String> = incoming.resolutionResult.allComponents
    .mapNotNull { it.id as? ModuleComponentIdentifier }
    .map { "${it.group}:${it.module}" }
    .toSet()

tasks.register("verifyPublishedModuleGraph") {
    group = "verification"
    description = "验证默认坐标、纯运行时和 Media3 适配器的 Maven 传递边界。"
    doLast {
        val defaultModules = defaultProbeContract.moduleIds()
        check("io.github.0o755:ad-audio-probe-runtime" in defaultModules) {
            "默认坐标没有传递 probe runtime"
        }
        check("io.github.0o755:ad-audio-probe-media3-1.9.2" in defaultModules) {
            "默认坐标没有传递官方 Media3 适配器"
        }

        val runtimeModules = runtimeProbeContract.moduleIds()
        check("io.github.0o755:ad-audio-probe-adapter-api" in runtimeModules) {
            "runtime 没有公开 adapter SPI"
        }
        check("io.github.0o755:ad-audio-probe-core" in runtimeModules) {
            "runtime 发布元数据缺少匹配核心"
        }
        check(runtimeModules.none { it == "io.github.0o755:ad-audio-probe-media3-1.9.2"
                || it.startsWith("androidx.media3:") }) {
            "runtime 不得传递具体 Media3 实现"
        }

        val adapterModules = media3AdapterContract.moduleIds()
        check("io.github.0o755:ad-audio-probe-adapter-api" in adapterModules) {
            "Media3 适配器没有传递 adapter SPI"
        }
        check("io.github.0o755:ad-audio-probe-core" !in adapterModules) {
            "媒体适配器不应反向携带匹配核心"
        }
        check("androidx.media3:media3-exoplayer" in adapterModules
                && "androidx.media3:media3-exoplayer-hls" in adapterModules) {
            "Media3 适配器发布元数据缺少完整播放器依赖"
        }

        val conflict = runCatching { media3ConflictContract.resolve() }.exceptionOrNull()
        check(conflict != null) {
            "官方适配器必须在构建期拒绝 Media3 组件混用不同版本"
        }
    }
}
