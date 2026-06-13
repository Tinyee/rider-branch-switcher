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

    register("quickCheck") {
        group = "verification"
        description = "Lightweight structural checks (seconds, no compilation). Run before every commit."

        doLast {
            val srcDir = file("src/main/kotlin")
            val testDir = file("src/test/kotlin")
            val msgDir = file("src/main/resources/messages")
            var ok = true

            fun fail(msg: String) { logger.error("  FAIL: $msg"); ok = false }
            fun pass(msg: String) { logger.lifecycle("  OK: $msg") }

            // 1. Cancel symmetry
            val bgCount = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.count { "TaskBridge.runBackground" in it }
            val cancelPairCount = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.count { "beginOperation" in it || "onCancel" in it || "endOperation" in it }
            if (bgCount > 0 && cancelPairCount < bgCount * 3)
                fail("Cancel asymmetry: $bgCount runBackground calls but only $cancelPairCount lifecycle tokens (expect 3×)")
            else pass("Cancel symmetry: $bgCount calls, $cancelPairCount tokens")

            // 2. Write gate pairing
            val startWrites = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.count { "tryStartWrite()" in it }
            val endWrites = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.count { "endWrite()" in it }
            if (startWrites != endWrites)
                fail("Write gate asymmetry: $startWrites tryStartWrite vs $endWrites endWrite")
            else pass("Write gate symmetry: $startWrites pairs")

            // 3. switch/ must not import ui/
            val switchDir = file("$srcDir/com/submodule/branchswitcher/switch")
            if (switchDir.exists()) {
                val violations = fileTree(switchDir).filter { it.extension == "kt" }
                    .flatMap { it.readLines() }.filter { it.contains("import") && it.contains(".ui.") }
                if (violations.isNotEmpty())
                    fail("switch/ imports ui/: ${violations.take(3)}")
                else pass("switch/ has no ui/ imports")
            }

            // 4. No raw git ProcessBuilder outside GitOps (known exceptions documented in isGitRepo / git PATH check)
            val rawGit = fileTree(srcDir).filter {
                it.extension == "kt" && !it.name.contains("GitOps") && !it.name.contains("ToolWindowFactory") && !it.name.contains("SwitchStep")
            }.flatMap { it.readLines() }.filter { it.contains("ProcessBuilder") && it.contains("\"git") }
            if (rawGit.isNotEmpty())
                fail("Raw git ProcessBuilder outside GitOps: ${rawGit.take(3)}")
            else pass("No raw git ProcessBuilder outside GitOps")

            // 5. i18n key count symmetry
            val enKeys = file("$msgDir/BranchSwitcherBundle.properties").readLines()
                .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
            val zhKeys = file("$msgDir/BranchSwitcherBundle_zh.properties").readLines()
                .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
            val onlyEn = enKeys - zhKeys
            val onlyZh = zhKeys - enKeys
            if (onlyEn.isNotEmpty()) fail("Keys only in EN: $onlyEn")
            if (onlyZh.isNotEmpty()) fail("Keys only in ZH: $onlyZh")
            if (onlyEn.isEmpty() && onlyZh.isEmpty())
                pass("i18n symmetry: ${enKeys.size} keys in both locales")

            // 6. allOk must include cancelled check
            val allOkDefs = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.filter { it.contains("val allOk") || it.contains("val allClean") }
            for (def in allOkDefs) {
                if (!def.contains("cancelled") && !def.contains("!cancelled")) {
                    fail("allOk/allClean missing cancelled check: $def")
                }
            }
            if (allOkDefs.isNotEmpty() && ok) pass("allOk/allClean includes cancelled check")

            // 7. Deprecated IntelliJ API patterns
            val deprecated = fileTree(srcDir).filter { it.extension == "kt" }
                .flatMap { it.readLines() }.filter {
                    it.contains("project.coroutineScope") && !it.contains("//") ||
                    it.contains("SwingUtilities.invokeLater") && !it.contains("//") ||
                    it.contains("ServiceLevel.PROJECT") && !it.contains("//")
                }
            if (deprecated.isNotEmpty())
                fail("Deprecated API usage: ${deprecated.take(3)}")
            else pass("No deprecated IntelliJ API patterns")

            // 8. Notification decision functions must be exhaustive (no missing branches)
            val decisionFiles = fileTree(srcDir).filter { it.extension == "kt" && it.name.contains("Notification") }
            for (f in decisionFiles) {
                val whenBranches = f.readLines().filter { it.contains(" is ") && it.contains(" ->") }.size
                val sealedSubclasses = fileTree(srcDir).filter { it.extension == "kt" && it.name.contains("Notification") }
                    .flatMap { it.readLines() }.filter { it.contains("class ") && it.contains(": DeriveNotification") || it.contains(": OpNotification") }.size
                if (whenBranches > 0 && sealedSubclasses > 0 && whenBranches < sealedSubclasses)
                    fail("${f.name}: when has $whenBranches branches but sealed class has $sealedSubclasses subclasses — missing branch?")
            }

            // 9. Every switch/when must handle all enum values
            for (f in fileTree(srcDir).filter { it.extension == "kt" }) {
                val lines = f.readLines()
                val enumValues = mutableSetOf<String>()
                var inEnum = false
                for (line in lines) {
                    if (line.contains("enum class")) inEnum = true
                    if (inEnum && line.contains("}")) inEnum = false
                    if (inEnum && line.trim().startsWith("//")) continue
                    if (inEnum) {
                        val m = Regex("""\s+(\w+)[,;]?""").find(line.trim())
                        if (m != null) enumValues.add(m.groupValues[1])
                    }
                }
                val whenBranches = lines.filter { it.contains(" ->") && it.contains(".") }.size
                if (enumValues.isNotEmpty() && whenBranches in 1..(enumValues.size - 1))
                    fail("${f.name}: when covers $whenBranches branches but enum has ${enumValues.size} values: $enumValues")
            }

            if (!ok) throw GradleException("quickCheck failed — see errors above")
            logger.lifecycle("quickCheck PASSED")
        }
    }

    register("releaseCheck") {
        group = "verification"
        description = "Run all automated release checks: quickCheck, test, detekt, buildPlugin, verifyPlugin, and metadata validation."

        dependsOn("quickCheck", "test", "detekt", "buildPlugin", "verifyPlugin")

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
