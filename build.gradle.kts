import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.submodule"
version = "0.7.0"

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

val platformType = providers.gradleProperty("platform.type")
val platformVersion = providers.gradleProperty("platform.version")
val platformLocalPath = providers.gradleProperty("platform.localPath").orNull
val verifierIdeCodes = providers.gradleProperty("plugin.verifier.ideCodes")
    .orElse(platformType)
    .get()
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

dependencies {
    intellijPlatform {
        if (!platformLocalPath.isNullOrBlank()) {
            local(platformLocalPath)
        } else {
            create(platformType, platformVersion, useInstaller = false)
        }
        bundledPlugin("Git4Idea")
    }
    implementation(project(":core"))
    // IntelliJ Platform provides Gson at runtime; compile only to avoid bundling a duplicate Gson jar.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("com.google.code.gson:gson:2.11.0")
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
            verifierIdeCodes.forEach { code ->
                ide("$code-${platformVersion.get()}")
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt-config.yml"))
}

pitest {
    // Manual diagnostic only. Keep mutation testing out of test/releaseCheck:
    // it is CPU-heavy and should be run deliberately on scoped core logic.
    // The scoped target tests are JUnit 4 tests. Avoid the JUnit 5 PIT plugin
    // here: with the IntelliJ Platform classpath it can drag in Vintage/Kotest
    // discovery and make the mutation run much heavier than the target scope.
    targetClasses.set(
        setOf(
            "com.submodule.branchswitcher.ui.UiRulesKt",
        )
    )
    excludedClasses.set(
        setOf(
            "com.submodule.branchswitcher.TaskBridge*",
            "com.submodule.branchswitcher.git.GitOps",
            "com.submodule.branchswitcher.service.*",
            "com.submodule.branchswitcher.settings.BranchSwitcherConfigurable",
            "com.submodule.branchswitcher.ui.BranchSwitcherPanel",
            "com.submodule.branchswitcher.ui.BranchSwitcherToolWindowFactory",
            "com.submodule.branchswitcher.ui.PresetEditor",
            "com.submodule.branchswitcher.ui.PresetListManager",
            "com.submodule.branchswitcher.ui.SubmoduleRowManager",
            "com.submodule.branchswitcher.ui.SwitchController",
            "com.submodule.branchswitcher.ui.SwitchPreviewDialog",
        )
    )
    targetTests.set(
        setOf(
            "com.submodule.branchswitcher.ui.UiRulesTest",
        )
    )
    avoidCallsTo.set(setOf("kotlin.jvm.internal"))
    outputFormats.set(setOf("HTML", "XML"))
    mutationThreshold.set(95)
    timestampedReports.set(false)
    threads.set(1)
}

// Extracted scan logic shared by quickCheck (production scan) and checkQuickCheck (fixture test).
// Returns list of failure messages, empty = all clear.
fun scanQuickChecks(srcRoot: File, msgDir: File): List<String> {
    val failures = mutableListOf<String>()

    fun fail(msg: String) { failures.add(msg) }

    // 1. Cancel symmetry - per-file: every file with runBackground must have beginOperation + onCancel + onFinished + endOperation
    for (f in fileTree(srcRoot).filter { it.extension == "kt" && !it.name.contains("TaskBridge") }) {
        val lines = f.readLines()
        if (lines.any { "TaskBridge.runBackground" in it }) {
            if (lines.none { "beginOperation()" in it || "beginOperation(" in it })
                fail("${f.name}: runBackground without beginOperation")
            if (lines.none { "onCancel" in it })
                fail("${f.name}: runBackground without onCancel")
            if (lines.none { "onFinished" in it })
                fail("${f.name}: runBackground without onFinished")
            if (lines.none { "endOperation()" in it || "endOperation(" in it })
                fail("${f.name}: runBackground without endOperation")
        }
    }

    // 2. Write gate pairing - per-file: every file with tryStartWrite must have endWrite
    for (f in fileTree(srcRoot).filter { it.extension == "kt" }) {
        val lines = f.readLines()
        val hasStart = lines.any { "tryStartWrite()" in it }
        val hasEnd = lines.any { "endWrite()" in it }
        if (hasStart && !hasEnd) fail("${f.name}: tryStartWrite without endWrite")
        if (!hasStart && hasEnd) fail("${f.name}: endWrite without tryStartWrite")
    }

    // 3. switch/ must not import ui/
    val switchDir = file("$srcRoot/com/submodule/branchswitcher/switch")
    if (switchDir.exists()) {
        val violations = fileTree(switchDir).filter { it.extension == "kt" }
            .flatMap { it.readLines() }.filter { it.contains("import") && it.contains(".ui.") }
        if (violations.isNotEmpty())
            fail("switch/ imports ui/: ${violations.take(3)}")
    }

    // 4. No raw git ProcessBuilder outside GitOps
    val rawGit = fileTree(srcRoot).filter {
        it.extension == "kt" && !it.name.contains("GitOps") && !it.name.contains("ToolWindowFactory")
    }.flatMap { it.readLines() }.filter { it.contains("ProcessBuilder") && it.contains("\"git") }
    if (rawGit.isNotEmpty())
        fail("Raw git ProcessBuilder outside GitOps: ${rawGit.take(3)}")

    // 5. i18n key count symmetry
    val enFile = file("$msgDir/BranchSwitcherBundle.properties")
    val zhFile = file("$msgDir/BranchSwitcherBundle_zh.properties")
    if (enFile.exists() && zhFile.exists()) {
        val enKeys = enFile.readLines()
            .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
        val zhKeys = zhFile.readLines()
            .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
        val onlyEn = enKeys - zhKeys
        val onlyZh = zhKeys - enKeys
        if (onlyEn.isNotEmpty()) fail("Keys only in EN: $onlyEn")
        if (onlyZh.isNotEmpty()) fail("Keys only in ZH: $onlyZh")
    }

    // 6. allOk must include cancelled check
    val allOkDefs = fileTree(srcRoot).filter { it.extension == "kt" }
        .flatMap { it.readLines() }.filter { it.contains("val allOk") || it.contains("val allClean") }
    for (def in allOkDefs) {
        if (!def.contains("cancelled") && !def.contains("!cancelled"))
            fail("allOk/allClean missing cancelled check: $def")
    }

    // 7. Deprecated IntelliJ API patterns
    val deprecated = fileTree(srcRoot).filter { it.extension == "kt" }
        .flatMap { it.readLines() }.filter {
            it.contains("project.coroutineScope") && !it.contains("//") ||
            it.contains("SwingUtilities.invokeLater") && !it.contains("//") ||
            it.contains("ServiceLevel.PROJECT") && !it.contains("//")
        }
    if (deprecated.isNotEmpty())
        fail("Deprecated API usage: ${deprecated.take(3)}")

    return failures
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    test {
        useJUnitPlatform()
        timeout.set(Duration.ofMinutes(15))
        // Limit parallel test forks - real-git integration tests spawn many
        // processes, so running too many test classes in parallel causes CPU
        // saturation without improving wall-clock time.
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        // Exclude benchmark from normal test runs - the benchmark creates
        // 50+ real git repos and takes 30–90 seconds.
        filter { excludeTestsMatching("com.submodule.branchswitcher.benchmark.*") }
    }

    register("pureTest") {
        group = "verification"
        description = "Run core pure JVM tests without the IntelliJ Platform runtime."
        dependsOn(":core:test")
    }
    // -- releaseCheck: aggregate all automated checks + metadata validation -----

    register("quickCheck") {
        group = "verification"
        description = "Lightweight structural checks (seconds, no compilation). Run before every commit."

        doLast {
            val failures = scanQuickChecks(file("src/main/kotlin"), file("src/main/resources/messages"))
            failures.forEach { logger.error("  FAIL: $it") }
            if (failures.isNotEmpty()) throw GradleException("quickCheck failed - ${failures.size} violation(s), see errors above")
            logger.lifecycle("quickCheck PASSED: all rules clean")
        }
    }

    register("checkQuickCheck") {
        group = "verification"
        description = "Test quickCheck rules by injecting broken fixtures into a temp dir and verifying the scan catches them."

        doLast {
            val fixtureDir = file(".claude/rules/fixtures")
            val fixtures = fixtureDir.listFiles { f -> f.extension == "fixture" }?.toList() ?: emptyList()
            if (fixtures.isEmpty()) throw GradleException("No quickCheck fixtures found in $fixtureDir - commit .claude/rules/fixtures/?")

            // Write fixtures under build/ to avoid interfering with detekt or other tools
            // scanning src/main/kotlin concurrently.
            val tempRoot = file("build/quick-check-fixtures")
            // Must replicate the full package structure so rule 3 (switch/ui) finds
            // com/submodule/branchswitcher/switch/ under srcRoot.
            val tempSrcDir = file("$tempRoot/com/submodule/branchswitcher")
            val msgDir = file("src/main/resources/messages")

            // switch-imports-ui fixture must go in switch/ directory (rule 3 only scans there)
            val fixtureToDir = mapOf(
                "violates-switch-imports-ui.kt" to "switch/_fixture_test_",
            )
            val defaultDir = "_fixture_test_"

            var passed = 0; var failed = 0
            val total = fixtures.size

            try {
                for (fixture in fixtures) {
                    val name = fixture.name.removeSuffix(".fixture")
                    val shouldBeCaught = name.startsWith("violates-")
                    val subDir = fixtureToDir[name] ?: defaultDir
                    val targetDir = file("$tempSrcDir/$subDir")
                    targetDir.mkdirs()
                    val target = file("$targetDir/$name")
                    fixture.copyTo(target, overwrite = true)

                    // Direct scan - no Gradle subprocess. Eliminates all the problems
                    // with nested processes (stderr blocking, timeouts, path resolution).
                    val violations = scanQuickChecks(tempRoot, msgDir)

                    target.delete()

                    if (shouldBeCaught) {
                        val diagnostic = when {
                            name.contains("cancel") -> "runBackground without"
                            name.contains("write") -> "tryStartWrite without endWrite"
                            name.contains("switch") -> "switch/ imports ui/"
                            name.contains("deprecated") -> "Deprecated API"
                            else -> name
                        }
                        val caught = violations.any { it.contains(diagnostic) }

                        if (caught) {
                            logger.lifecycle("  OK: $name - caught")
                            passed++
                        } else {
                            logger.error("  FAIL: $name - diagnostic not found in violations")
                            logger.error("  violations (${violations.size}): $violations")
                            failed++
                        }
                    } else {
                        // Fixture should NOT trigger any rule - false-positive check.
                        if (violations.isEmpty()) {
                            logger.lifecycle("  OK: $name - correctly ignored (0 violations)")
                            passed++
                        } else {
                            logger.error("  FAIL: $name - false positive, got ${violations.size} violation(s): $violations")
                            failed++
                        }
                    }
                }
            } finally {
                if (tempRoot.exists()) tempRoot.deleteRecursively()
            }
            if (failed > 0) throw GradleException("checkQuickCheck: $failed/$total fixture(s) not caught or false positive")
            logger.lifecycle("checkQuickCheck PASSED: $passed/$total fixtures verified")
        }
    }

    register("releaseCheck") {
        group = "verification"
        description = "Run all automated release checks: quickCheck, core test/detekt, test, detekt, buildPlugin, verifyPlugin, and metadata validation."

        dependsOn("quickCheck", "checkQuickCheck", ":core:test", "test", "detekt", ":core:detekt", "buildPlugin", "verifyPlugin")

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

            logger.lifecycle("releaseCheck PASSED for version $projVersion")
        }
    }

    register<Test>("benchmark") {
        group = "verification"
        description = "Large-repo wall-clock benchmark (51 preset target repos, real GitOps; no .gitmodules). Not part of normal test."
        useJUnitPlatform {
            excludeEngines("kotest") // kotest classpath scan OOMs with full IntelliJ classpath
        }
        timeout.set(Duration.ofMinutes(10))
        maxParallelForks = 1 // shared temp dir
        maxHeapSize = "1g" // 50+ real git repos need headroom
        filter { includeTestsMatching("com.submodule.branchswitcher.benchmark.*") }
    }

    register("pitestCore") {
        group = "verification"
        description = "Run scoped PIT mutation testing for core pure rules. Heavy; manual only, not part of releaseCheck."
        dependsOn("pitest", ":core:pitest")
    }
}
