import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.submodule"
version = "0.6.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    // Chinese mirrors as fallback (local dev), after official sources for CI reliability
    maven("https://maven.aliyun.com/repository/public")
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    maven("https://repo.huaweicloud.com/repository/maven/")
}

val riderVersion = providers.gradleProperty("rider.version").get()
val riderPath = providers.gradleProperty("rider.path").orNull
val verifierRiderNotation = providers.gradleProperty("rider.version").map { "RD-$it" }

dependencies {
    intellijPlatform {
        if (!riderPath.isNullOrBlank()) {
            local(riderPath)
        } else {
            rider(riderVersion)
        }
        bundledPlugin("Git4Idea")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("plugin.sinceBuild").get()
            untilBuild = providers.gradleProperty("plugin.untilBuild").get()
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("publishToken"))
            .orElse("")
    }
    pluginVerification {
        ides {
            ide(verifierRiderNotation)
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt-config.yml"))
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    test {
        useJUnitPlatform()
        timeout.set(Duration.ofMinutes(15))
    }

    // -- releaseCheck: aggregate all automated checks + metadata validation -----

    register("releaseCheck") {
        group = "verification"
        description = "Run all automated release checks: test, detekt, buildPlugin, verifyPlugin, and metadata validation."

        dependsOn("test", "detekt", "buildPlugin", "verifyPlugin")

        doLast {
            val projVersion = version.toString()
            val expectedZip = layout.buildDirectory.file("distributions/rider-branch-switcher-$projVersion.zip").get().asFile

            // --- version consistency -------------------------------------------------
            val readmeText = file("README.md").readText()
            // Badge must contain the exact version tag
            if (!readmeText.contains("version-$projVersion-")) {
                throw GradleException("README version badge missing or wrong version (expected version-$projVersion-*)")
            }
            logger.lifecycle("  README badge: version-$projVersion")

            val changelogText = file("CHANGELOG.md").readText()
            // First ## [...] heading must match current version
            val firstVersionHeading = Regex("""^##\s*\[([^\]]+)\]""", RegexOption.MULTILINE).find(changelogText)
            if (firstVersionHeading == null || firstVersionHeading.groupValues[1] != projVersion) {
                throw GradleException("CHANGELOG first version heading must be ## [$projVersion]")
            }
            logger.lifecycle("  CHANGELOG latest: ## [$projVersion]")

            // --- required artifacts ---------------------------------------------------
            if (!expectedZip.exists()) {
                throw GradleException("Plugin ZIP not found: ${expectedZip.absolutePath}")
            }
            logger.lifecycle("  ZIP: ${expectedZip.name}")

            val licenseFile = file("LICENSE")
            if (!licenseFile.exists()) {
                throw GradleException("LICENSE file is missing — required for Marketplace publication")
            }
            logger.lifecycle("  LICENSE: present")

            // --- pre-flight warnings (non-fatal) ---------------------------------------
            val readme = file("README.md").readText()
            if (Regex("screenshot.*TODO|TODO.*screenshot", RegexOption.IGNORE_CASE).containsMatchIn(readme)) {
                logger.warn("  [WARN] README still contains screenshot TODO — replace before Marketplace publish")
            }

            val iconFile = file("src/main/resources/META-INF/pluginIcon.svg")
            if (!iconFile.exists()) {
                logger.warn("  [WARN] pluginIcon.svg not found — required for Marketplace publication")
            } else {
                logger.lifecycle("  pluginIcon.svg: present")
            }

            logger.lifecycle("releaseCheck PASSED for version $projVersion")
        }
    }
}
