import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
    .getOrElse("false")
    .toBoolean()

group = "com.submodule"
version = providers.gradleProperty("localPluginVersion").getOrElse("0.8.0")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    if (useChinaMirrors) {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

val platformType = providers.gradleProperty("platform.type")
val platformVersion = providers.gradleProperty("platform.version")
val platformLocalPath = providers.gradleProperty("platform.localPath").orNull
val verifierIdeTargets = providers.gradleProperty("plugin.verifier.ideTargets")
    .orElse("RD:${platformVersion.get()}")
    .get()
    .split(',')
    .mapNotNull { target ->
        val value = target.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val parts = value.split(':', limit = 2).map(String::trim)
        require(parts.size == 2 && parts.all(String::isNotEmpty)) {
            "Invalid plugin.verifier.ideTargets entry '$value'; expected PRODUCT:VERSION"
        }
        parts[0] to parts[1]
    }

dependencies {
    intellijPlatform {
        if (!platformLocalPath.isNullOrBlank()) {
            local(platformLocalPath)
        } else {
            create(platformType, platformVersion)
        }
        bundledPlugin("Git4Idea")
    }
    implementation(project(":core"))
    // IntelliJ Platform provides Gson at runtime; compile only to avoid bundling a duplicate Gson jar.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Architecture dependency-direction rules run on compiled main classes as a test.
    // Test-only: never ships in the plugin artifact.
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    buildSearchableOptions.set(false)

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("plugin.sinceBuild").get()
            providers.gradleProperty("plugin.untilBuild").orNull?.let { untilBuild.set(it) }
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("publishToken"))
            .orElse("")
    }
    pluginVerification {
        ides {
            verifierIdeTargets.forEach { (productCode, version) ->
                create(productCode, version) {
                    // Rider installers are not supported by IntelliJ Platform Gradle Plugin 2.11.
                    useInstaller.set(productCode != "RD")
                }
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt-config.yml"))
}

apply(from = "gradle/quick-check.gradle.kts")

tasks {
    test {
        useJUnitPlatform()
        timeout.set(Duration.ofMinutes(15))
        // Limit parallel test forks - real-git integration tests spawn many
        // processes, so running too many test classes in parallel causes CPU
        // saturation without improving wall-clock time.
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }

    register("pureTest") {
        group = "verification"
        description = "Run core pure JVM tests without the IntelliJ Platform runtime."
        dependsOn(":core:test")
    }

    named("check") {
        dependsOn("quickCheck", "checkQuickCheck")
    }

    register("validateReleaseMetadata") {
        group = "verification"
        description = "Validate release version metadata and required Marketplace artifacts."

        dependsOn("buildPlugin")

        doLast {
            val projVersion = version.toString()
            val expectedZip = layout.buildDirectory.file("distributions/submodule-branch-switcher-$projVersion.zip").get().asFile

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
                throw GradleException("LICENSE file is missing - required for Marketplace publication")
            }
            logger.lifecycle("  LICENSE: present")

            // --- pre-flight warnings (non-fatal) ---------------------------------------
            val readme = file("README.md").readText()
            if (Regex("screenshot.*TODO|TODO.*screenshot", RegexOption.IGNORE_CASE).containsMatchIn(readme)) {
                logger.warn("  [WARN] README still contains screenshot TODO - replace before Marketplace publish")
            }

            val iconFile = file("src/main/resources/META-INF/pluginIcon.svg")
            if (!iconFile.exists()) {
                logger.warn("  [WARN] pluginIcon.svg not found - required for Marketplace publication")
            } else {
                logger.lifecycle("  pluginIcon.svg: present")
            }

            logger.lifecycle("validateReleaseMetadata PASSED for version $projVersion")
        }
    }

    register("releaseCheck") {
        group = "verification"
        description = "Run all automated release checks: tests, static analysis, plugin verification, and metadata validation."

        dependsOn(
            "quickCheck",
            "checkQuickCheck",
            ":core:test",
            "test",
            "detekt",
            ":core:detekt",
            "verifyPlugin",
            "validateReleaseMetadata",
        )

        doLast {
            logger.lifecycle("releaseCheck PASSED for version $version")
        }
    }

    register("pitestCore") {
        group = "verification"
        description = "Run scoped PIT mutation testing for core pure rules. Heavy; manual only, not part of releaseCheck."
        dependsOn(":core:pitest")
    }
}
