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

private enum class KotlinLexicalMode { CODE, STRING, TRIPLE_STRING, CHARACTER, LINE_COMMENT, BLOCK_COMMENT }

/** Removes comments and literal text while preserving executable Kotlin tokens, including `${...}` expressions. */
fun kotlinCodeLines(file: File): List<String> {
    val source = file.readText()
    val code = StringBuilder(source.length)
    var mode = KotlinLexicalMode.CODE
    var blockCommentDepth = 0
    val templateStringModes = mutableListOf<KotlinLexicalMode>()
    val templateBraceDepths = mutableListOf<Int>()
    var index = 0

    fun preserveNewline() {
        if (source[index] == '\n') code.append('\n')
    }

    while (index < source.length) {
        when (mode) {
            KotlinLexicalMode.CODE -> when {
                source.startsWith("//", index) -> {
                    mode = KotlinLexicalMode.LINE_COMMENT
                    index += 2
                }
                source.startsWith("/*", index) -> {
                    mode = KotlinLexicalMode.BLOCK_COMMENT
                    blockCommentDepth = 1
                    index += 2
                }
                source.startsWith("\"\"\"", index) -> {
                    mode = KotlinLexicalMode.TRIPLE_STRING
                    index += 3
                }
                source[index] == '"' -> {
                    mode = KotlinLexicalMode.STRING
                    index++
                }
                source[index] == '\'' -> {
                    mode = KotlinLexicalMode.CHARACTER
                    index++
                }
                source[index] == '{' && templateBraceDepths.isNotEmpty() -> {
                    templateBraceDepths[templateBraceDepths.lastIndex]++
                    code.append(source[index++])
                }
                source[index] == '}' && templateBraceDepths.isNotEmpty() -> {
                    val lastIndex = templateBraceDepths.lastIndex
                    val remainingDepth = templateBraceDepths[lastIndex] - 1
                    if (remainingDepth == 0) {
                        templateBraceDepths.removeAt(lastIndex)
                        mode = templateStringModes.removeAt(templateStringModes.lastIndex)
                        index++
                    } else {
                        templateBraceDepths[lastIndex] = remainingDepth
                        code.append(source[index++])
                    }
                }
                else -> code.append(source[index++])
            }
            KotlinLexicalMode.LINE_COMMENT -> {
                preserveNewline()
                if (source[index++] == '\n') mode = KotlinLexicalMode.CODE
            }
            KotlinLexicalMode.BLOCK_COMMENT -> when {
                source.startsWith("/*", index) -> {
                    blockCommentDepth++
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    blockCommentDepth--
                    index += 2
                    if (blockCommentDepth == 0) mode = KotlinLexicalMode.CODE
                }
                else -> {
                    preserveNewline()
                    index++
                }
            }
            KotlinLexicalMode.STRING, KotlinLexicalMode.CHARACTER -> when {
                source[index] == '\\' -> {
                    preserveNewline()
                    index++
                    if (index < source.length) {
                        preserveNewline()
                        index++
                    }
                }
                mode == KotlinLexicalMode.STRING && source.startsWith("${'$'}{", index) -> {
                    templateStringModes += mode
                    templateBraceDepths += 1
                    mode = KotlinLexicalMode.CODE
                    index += 2
                }
                mode == KotlinLexicalMode.STRING && source[index] == '"' ||
                    mode == KotlinLexicalMode.CHARACTER && source[index] == '\'' -> {
                    mode = KotlinLexicalMode.CODE
                    index++
                }
                else -> {
                    preserveNewline()
                    index++
                }
            }
            KotlinLexicalMode.TRIPLE_STRING -> when {
                source.startsWith("\"\"\"", index) -> {
                    mode = KotlinLexicalMode.CODE
                    index += 3
                }
                source.startsWith("${'$'}{", index) -> {
                    templateStringModes += mode
                    templateBraceDepths += 1
                    mode = KotlinLexicalMode.CODE
                    index += 2
                }
                else -> {
                    preserveNewline()
                    index++
                }
            }
        }
    }
    return code.toString().lines()
}

// Extracted scan logic shared by quickCheck (production scan) and checkQuickCheck (fixture test).
// Returns list of failure messages, empty = all clear.
fun scanQuickChecks(
    srcRoot: File,
    msgDir: File,
    enforceCoreBoundary: Boolean = false,
    checkMessages: Boolean = true,
): List<String> {
    val failures = mutableListOf<String>()

    fun fail(msg: String) { failures.add(msg) }

    // 1. Cancel symmetry - each background Git operation must own and close an isolated session.
    for (f in fileTree(srcRoot).filter { it.extension == "kt" && !it.name.contains("TaskBridge") }) {
        val lines = kotlinCodeLines(f)
        if (lines.any { "TaskBridge.runBackground" in it }) {
            if (f.name != "GitBackgroundRunner.kt")
                fail("${f.name}: direct TaskBridge.runBackground outside GitBackgroundRunner")
            if (lines.none { "openOperation()" in it || "openOperation(" in it })
                fail("${f.name}: runBackground without openOperation")
            if (lines.none { "onCancel" in it })
                fail("${f.name}: runBackground without onCancel")
            if (lines.none { "onFinished" in it })
                fail("${f.name}: runBackground without onFinished")
            if (lines.none { ".close()" in it })
                fail("${f.name}: runBackground without session close")
        }
    }

    // 2. Write gate pairing - every acquired write lease must be closed in the same file.
    for (f in fileTree(srcRoot).filter {
        it.extension == "kt" && it.name != "BranchSwitcherService.kt"
    }) {
        val lines = kotlinCodeLines(f)
        val hasAcquire = lines.any { "tryAcquireWrite()" in it }
        val hasClose = lines.any { "writeLease.close()" in it }
        if (hasAcquire && !hasClose) fail("${f.name}: tryAcquireWrite without writeLease.close")
        if (!hasAcquire && hasClose) fail("${f.name}: writeLease.close without tryAcquireWrite")
    }

    // 3. Core must remain a pure JVM module.
    if (enforceCoreBoundary) {
        val sourceLines = fileTree(srcRoot).filter { it.extension == "kt" }
            .flatMap(::kotlinCodeLines)
        val intellijReferences = sourceLines.filter { line ->
            line.contains("com.intellij.")
        }
        if (intellijReferences.isNotEmpty()) {
            fail("Core references IntelliJ API: ${intellijReferences.take(3)}")
        }
        val desktopUiImports = sourceLines.filter {
            val line = it.trimStart()
            line.startsWith("import java.awt") || line.startsWith("import javax.swing")
        }
        if (desktopUiImports.isNotEmpty()) {
            fail("Core imports desktop UI: ${desktopUiImports.take(3)}")
        }
    }

    // 4. switch/ must not import ui/
    val switchDir = file("$srcRoot/com/submodule/branchswitcher/switch")
    if (switchDir.exists()) {
        val violations = fileTree(switchDir).filter { it.extension == "kt" }
            .flatMap(::kotlinCodeLines).filter { it.contains("import") && it.contains(".ui.") }
        if (violations.isNotEmpty())
            fail("switch/ imports ui/: ${violations.take(3)}")
    }

    // 5. Platform and application layers must keep a one-way dependency direction.
    val forbiddenLayerImports = mapOf(
        "workflow" to listOf(".platform.", ".ui.", ".service."),
        "platform" to listOf(".workflow.", ".ui.", ".service."),
        "service" to listOf(".workflow.", ".platform.", ".ui."),
    )
    for ((layer, forbidden) in forbiddenLayerImports) {
        val layerDir = file("$srcRoot/com/submodule/branchswitcher/$layer")
        if (!layerDir.exists()) continue
        val violations = fileTree(layerDir).filter { it.extension == "kt" }
            .flatMap { file ->
                kotlinCodeLines(file)
                    .filter { line ->
                        forbidden.any { segment ->
                            line.contains("com.submodule.branchswitcher$segment")
                        }
                    }
                    .map { "${file.name}: $it" }
            }
        if (violations.isNotEmpty()) fail("$layer has forbidden layer imports: ${violations.take(3)}")
    }

    val workflowDir = file("$srcRoot/com/submodule/branchswitcher/workflow")
    if (workflowDir.exists()) {
        val intellijReferences = fileTree(workflowDir).filter { it.extension == "kt" }
            .flatMap(::kotlinCodeLines)
            .filter { line ->
                line.contains("com.intellij.")
            }
        if (intellijReferences.isNotEmpty()) {
            fail("workflow references IntelliJ API: ${intellijReferences.take(3)}")
        }
        val pluginImplementationReferences = fileTree(workflowDir).filter { it.extension == "kt" }
            .flatMap(::kotlinCodeLines)
            .filter { line ->
                listOf("TaskBridge", "Bundle", "Notifier", "GitOps", "GitCommandClient", "GitProcessRunner")
                    .any { type -> line.contains("com.submodule.branchswitcher.$type") ||
                        line.contains("com.submodule.branchswitcher.git.$type") }
            }
        if (pluginImplementationReferences.isNotEmpty()) {
            fail("workflow references plugin implementation: ${pluginImplementationReferences.take(3)}")
        }
    }

    // 6. Git process execution belongs to GitProcessRunner; GitOps may only probe --version.
    val rawGit = fileTree(srcRoot).filter {
        it.extension == "kt" && it.name !in setOf("GitProcessRunner.kt", "GitOps.kt")
    }.flatMap { file ->
        file.readLines().zip(kotlinCodeLines(file))
    }.filter { (rawLine, codeLine) -> codeLine.contains("ProcessBuilder") && rawLine.contains("\"git") }
    if (rawGit.isNotEmpty())
        fail("Raw git ProcessBuilder outside GitProcessRunner: ${rawGit.take(3)}")

    // 7. i18n key count symmetry
    val enFile = file("$msgDir/BranchSwitcherBundle.properties")
    val zhFile = file("$msgDir/BranchSwitcherBundle_zh.properties")
    if (checkMessages && enFile.exists() && zhFile.exists()) {
        val enKeys = enFile.readLines()
            .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
        val zhKeys = zhFile.readLines()
            .filter { it.matches(Regex("^[a-z.]+=.*")) }.map { it.substringBefore("=") }.toSet()
        val onlyEn = enKeys - zhKeys
        val onlyZh = zhKeys - enKeys
        if (onlyEn.isNotEmpty()) fail("Keys only in EN: $onlyEn")
        if (onlyZh.isNotEmpty()) fail("Keys only in ZH: $onlyZh")
    }

    // 8. Deprecated IntelliJ API patterns
    val deprecated = fileTree(srcRoot).filter { it.extension == "kt" }
        .flatMap(::kotlinCodeLines).filter {
            it.contains("project.coroutineScope") ||
            it.contains("SwingUtilities.invokeLater") ||
            it.contains("ServiceLevel.PROJECT") ||
            it.contains("beginOperation(") ||
            it.contains("endOperation(")
        }
    if (deprecated.isNotEmpty())
        fail("Deprecated API usage: ${deprecated.take(3)}")

    return failures
}

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
    // -- releaseCheck: aggregate all automated checks + metadata validation -----

    register("quickCheck") {
        group = "verification"
        description = "Lightweight structural checks (seconds, no compilation). Run before every commit."

        doLast {
            val msgDir = file("src/main/resources/messages")
            val failures = scanQuickChecks(file("src/main/kotlin"), msgDir) +
                scanQuickChecks(
                    file("core/src/main/kotlin"),
                    msgDir,
                    enforceCoreBoundary = true,
                    checkMessages = false,
                )
            failures.forEach { logger.error("  FAIL: $it") }
            if (failures.isNotEmpty()) throw GradleException("quickCheck failed - ${failures.size} violation(s), see errors above")
            logger.lifecycle("quickCheck PASSED: all rules clean")
        }
    }

    register("checkQuickCheck") {
        group = "verification"
        description = "Test quickCheck rules by injecting broken fixtures into a temp dir and verifying the scan catches them."

        doLast {
            val fixtureDir = file("gradle/quick-check-fixtures")
            val fixtures = fixtureDir.listFiles { f -> f.extension == "fixture" }?.toList() ?: emptyList()
            if (fixtures.isEmpty()) throw GradleException("No quickCheck fixtures found in $fixtureDir - commit gradle/quick-check-fixtures/?")

            // Write fixtures under build/ to avoid interfering with detekt or other tools
            // scanning src/main/kotlin concurrently.
            val tempRoot = file("build/quick-check-fixtures")
            // Must replicate the full package structure so rule 3 (switch/ui) finds
            // com/submodule/branchswitcher/switch/ under srcRoot.
            val tempSrcDir = file("$tempRoot/com/submodule/branchswitcher")
            val msgDir = file("src/main/resources/messages")

            // Package-scoped fixtures must be placed under the directory scanned by their rule.
            val fixtureToDir = mapOf(
                "violates-switch-imports-ui.kt" to "switch/_fixture_test_",
                "violates-workflow-imports-ui.kt" to "workflow/_fixture_test_",
                "violates-workflow-imports-platform.kt" to "workflow/_fixture_test_",
                "violates-workflow-intellij.kt" to "workflow/_fixture_test_",
                "violates-workflow-intellij-qualified.kt" to "workflow/_fixture_test_",
                "violates-workflow-intellij-template.kt" to "workflow/_fixture_test_",
                "violates-workflow-intellij-nested-template.kt" to "workflow/_fixture_test_",
                "violates-workflow-root-platform.kt" to "workflow/_fixture_test_",
                "violates-workflow-git-implementation.kt" to "workflow/_fixture_test_",
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
                    val violations = scanQuickChecks(tempRoot, msgDir, enforceCoreBoundary = true)

                    target.delete()

                    if (shouldBeCaught) {
                        val diagnostic = when {
                            name.contains("direct-background") -> "direct TaskBridge.runBackground"
                            name.contains("cancel") -> "runBackground without"
                            name.contains("write") -> "tryAcquireWrite without writeLease.close"
                            name.contains("switch") -> "switch/ imports ui/"
                            name.contains("workflow-intellij") -> "workflow references IntelliJ API"
                            name.contains("workflow-root-platform") || name.contains("workflow-git-implementation") ->
                                "workflow references plugin implementation"
                            name.contains("workflow") -> "workflow has forbidden layer imports"
                            name.contains("core-intellij") -> "Core references IntelliJ API"
                            name.contains("core-desktop-ui") -> "Core imports desktop UI"
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
