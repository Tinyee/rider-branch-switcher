// Structural (text-level) quick checks for the plugin + core source trees.
//
// These scans are fast tripwires, not proofs: [kotlinCodeLines] strips comments and
// literals, but text matching cannot establish control flow, scope, or that two
// symbols refer to the same object. They are deliberately paired with structural
// guarantees elsewhere (ArchUnit rules in ArchitectureRulesTest.kt for layer and
// core purity, and WriteOperationLauncher's CloseOnce + finally + invokeOnCompletion
// for write-lease pairing). Prefer strengthening the structural guarantee over
// extending a text rule here.
//
// Applied from the root build.gradle.kts via `apply(from = "gradle/quick-check.gradle.kts")`.

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

/**
 * Returns list of failure messages, empty = all clear.
 * [checkMessages] gates the EN/ZH key-symmetry scan, which needs the resource dir.
 */
fun scanQuickChecks(
    srcRoot: File,
    msgDir: File,
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
    //    Text tripwire only: it proves co-occurrence of `tryAcquireWrite()` and
    //    `writeLease.close()` in a file, not that the close sits in a finally block or
    //    targets the same lease. The real invariant is structural — WriteOperationLauncher
    //    wraps the lease in CloseOnce and closes it from both `finally` and
    //    `job.invokeOnCompletion` — and ArchUnit cannot express control flow, so this
    //    cheap scan is kept as a first-pass guard.
    for (f in fileTree(srcRoot).filter {
        it.extension == "kt" && it.name != "BranchSwitcherService.kt"
    }) {
        val lines = kotlinCodeLines(f)
        val hasAcquire = lines.any { "tryAcquireWrite()" in it }
        val hasClose = lines.any { "writeLease.close()" in it }
        if (hasAcquire && !hasClose) fail("${f.name}: tryAcquireWrite without writeLease.close")
        if (!hasAcquire && hasClose) fail("${f.name}: writeLease.close without tryAcquireWrite")
    }

    // 3. i18n key count symmetry
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

    // 4. Deprecated IntelliJ API patterns
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
    register("quickCheck") {
        group = "verification"
        description = "Lightweight structural checks (seconds, no compilation). Run before every commit."

        doLast {
            val msgDir = file("src/main/resources/messages")
            val failures = scanQuickChecks(file("src/main/kotlin"), msgDir) +
                scanQuickChecks(
                    file("core/src/main/kotlin"),
                    msgDir,
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
            val tempSrcDir = file("$tempRoot/com/submodule/branchswitcher")
            val msgDir = file("src/main/resources/messages")

            val defaultDir = "_fixture_test_"

            var passed = 0; var failed = 0
            val total = fixtures.size

            try {
                for (fixture in fixtures) {
                    val name = fixture.name.removeSuffix(".fixture")
                    val shouldBeCaught = name.startsWith("violates-")
                    val subDir = defaultDir
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
                            name.contains("direct-background") -> "direct TaskBridge.runBackground"
                            name.contains("cancel") -> "runBackground without"
                            name.contains("write") -> "tryAcquireWrite without writeLease.close"
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
}
