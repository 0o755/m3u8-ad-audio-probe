// 烟测工程从 Maven Local 核对默认聚合与可替换适配器的真实发布依赖图。
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.util.zip.ZipFile

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
val media3Adapter110Contract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3Adapter111Contract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val playerContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val collectorToolsContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3ConflictContract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3Conflict110Contract by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val media3Conflict111Contract by configurations.creating {
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
    add(media3Adapter110Contract.name,
        "io.github.0o755:ad-audio-probe-media3-1.10.1:${probeVersion.get()}")
    add(media3Adapter111Contract.name,
        "io.github.0o755:ad-audio-probe-media3-1.11.0:${probeVersion.get()}")
    add(playerContract.name,
        "io.github.0o755:ad-audio-probe-player:${probeVersion.get()}")
    add(collectorToolsContract.name,
        "io.github.0o755:ad-audio-probe-collector-tools:${probeVersion.get()}")
    add(media3ConflictContract.name,
        "io.github.0o755:ad-audio-probe-media3-1.9.2:${probeVersion.get()}")
    add(media3ConflictContract.name, "androidx.media3:media3-common:1.10.1")
    add(media3Conflict110Contract.name,
        "io.github.0o755:ad-audio-probe-media3-1.10.1:${probeVersion.get()}")
    add(media3Conflict110Contract.name, "androidx.media3:media3-common:1.9.2")
    add(media3Conflict111Contract.name,
        "io.github.0o755:ad-audio-probe-media3-1.11.0:${probeVersion.get()}")
    add(media3Conflict111Contract.name, "androidx.media3:media3-common:1.10.1")
}

fun Configuration.moduleIds(): Set<String> = incoming.resolutionResult.allComponents
    .mapNotNull { it.id as? ModuleComponentIdentifier }
    .map { "${it.group}:${it.module}" }
    .toSet()

fun Configuration.media3Versions(): Set<String> = incoming.resolutionResult.allComponents
    .mapNotNull { it.id as? ModuleComponentIdentifier }
    .filter { it.group == "androidx.media3" }
    .map { it.version }
    .toSet()

fun verifyMedia3Adapter(
    configuration: Configuration,
    coordinate: String,
    expectedMedia3Version: String
) {
    val modules = configuration.moduleIds()
    check("io.github.0o755:ad-audio-probe-adapter-api" in modules) {
        "$coordinate 没有传递 adapter SPI"
    }
    check("io.github.0o755:ad-audio-probe-core" !in modules) {
        "$coordinate 不应反向携带匹配核心"
    }
    check("androidx.media3:media3-exoplayer" in modules
            && "androidx.media3:media3-exoplayer-hls" in modules) {
        "$coordinate 发布元数据缺少完整播放器依赖"
    }
    check(configuration.media3Versions() == setOf(expectedMedia3Version)) {
        "$coordinate 必须严格使用 Media3 $expectedMedia3Version，实际为 ${configuration.media3Versions()}"
    }
}

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
        check("io.github.0o755:ad-audio-probe-player" in defaultModules
                && "io.github.0o755:ad-audio-probe-collector-tools" in defaultModules) {
            "默认坐标没有公开播放器或采集工具"
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

        val playerModules = playerContract.moduleIds()
        check("io.github.0o755:ad-audio-probe-adapter-api" in playerModules
                && playerModules.none { it.startsWith("androidx.media3:") }) {
            "播放器门面必须只依赖稳定 SPI，不得绑定 Media3"
        }

        val collectorModules = collectorToolsContract.moduleIds()
        check("io.github.0o755:ad-audio-probe-core" in collectorModules
                && collectorModules.none { it.startsWith("androidx.media3:") }) {
            "采集工具应复用匹配核心，但不得绑定具体媒体实现"
        }

        verifyMedia3Adapter(
            media3AdapterContract,
            "ad-audio-probe-media3-1.9.2",
            "1.9.2"
        )
        verifyMedia3Adapter(
            media3Adapter110Contract,
            "ad-audio-probe-media3-1.10.1",
            "1.10.1"
        )
        verifyMedia3Adapter(
            media3Adapter111Contract,
            "ad-audio-probe-media3-1.11.0",
            "1.11.0"
        )

        listOf(
            media3ConflictContract,
            media3Conflict110Contract,
            media3Conflict111Contract
        ).forEach { configuration ->
            val conflict = runCatching { configuration.resolve() }.exceptionOrNull()
            check(conflict != null) {
                "${configuration.name} 必须在构建期拒绝 Media3 组件混用不同版本"
            }
        }
    }
}

tasks.register("verifyMinifiedServiceProviders") {
    group = "verification"
    description = "验证默认 Release/R8 APK 仍包含音频与播放两套官方服务实现。"
    dependsOn(":consumer:assembleRelease")
    doLast {
        val apk = project(":consumer").layout.buildDirectory
            .file("outputs/apk/release/consumer-release-unsigned.apk").get().asFile
        check(apk.isFile) { "找不到默认消费者 Release APK: $apk" }
        val provider = "io.github.fongmi.adaudio.probe.adapter.media3.v1_9.Media3ProbeAdapterFactory"
        ZipFile(apk).use { archive ->
            val matches = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                .filter { entry ->
                    archive.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines.map(String::trim).any { it == provider }
                    }
                }
                .map { it.name }
                .toList()
            check(matches.size == 2) {
                "Release/R8 APK 应保留音频与播放两份官方 provider，实际为 $matches"
            }
        }
    }
}
