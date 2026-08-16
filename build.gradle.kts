// 根构建统一版本、Java 编码和 Maven 发布元数据。
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.0o755"
    version = providers.gradleProperty("probeVersion").orElse("0.1.0-SNAPSHOT").get()

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            charSet("UTF-8")
            docEncoding("UTF-8")
            addStringOption("Xdoclint:none", "-quiet")
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("M3U8 Ad Audio Probe - ${project.name}")
                    description.set("面向普通 HLS/MP4 点播的无头广告音频探针")
                    url.set("https://github.com/0o755/m3u8-ad-audio-probe")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("0o755")
                            name.set("0o755")
                            url.set("https://github.com/0o755")
                        }
                    }
                    scm {
                        url.set("https://github.com/0o755/m3u8-ad-audio-probe")
                        connection.set("scm:git:https://github.com/0o755/m3u8-ad-audio-probe.git")
                        developerConnection.set("scm:git:ssh://git@github.com/0o755/m3u8-ad-audio-probe.git")
                    }
                }
            }

            val repositoryUrl = providers.gradleProperty("publishRepositoryUrl").orNull
                ?: System.getenv("GITHUB_REPOSITORY")?.let { "https://maven.pkg.github.com/$it" }
            if (!repositoryUrl.isNullOrBlank()) {
                repositories.maven {
                    name = "release"
                    url = uri(repositoryUrl)
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: ""
                        password = System.getenv("GITHUB_TOKEN") ?: ""
                    }
                }
            }
        }
    }

}
