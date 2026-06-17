package com.submodule.branchswitcher.benchmark

import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * Wall-clock benchmark for SwitchExecutor and SwitchPreflight on a large
 * multi-repo setup (1 main + 50 preset target repos) with real git repositories.
 *
 * This benchmark creates 51 independent git repos (no .gitmodules registration —
 * the pipeline uses [com.submodule.branchswitcher.model.Preset.targets], not
 * `git submodule` introspection). Creation uses real `git init` and real
 * `GitOps` for all switch commands.
 *
 * Intentionally slow (30–90 seconds). Lives in a dedicated `./gradlew benchmark`
 * task — never runs as part of `./gradlew test`.
 *
 * No wall-clock thresholds are enforced; results are printed for human review.
 */
class LargeRepoBenchmark {

    companion object {
        private const val targetRepoCount = 50

        private lateinit var tmpDir: Path
        private lateinit var git: GitOps

        // Timings collected during setup, printed in the test
        private var setupTimingMs: Long = -1

        @BeforeClass
        @JvmStatic
        fun setUp() {
            setupTimingMs = measureTimeMillis {
                tmpDir = Files.createTempDirectory("benchmark-")
                val root = tmpDir.toFile()
                root.mkdirs()
                git = GitOps(timeoutSeconds = 60)

                // Main repo
                createRepoWithSecondBranch(root, ".", "main", "dev")

                // Target repos (independent repos, not git-submodule registered)
                for (i in 1..targetRepoCount) {
                    val subDir = root.resolve("sub-$i")
                    createRepoWithSecondBranch(root, subDir.name, "main", "dev")
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { tmpDir.toFile().deleteRecursively() }
        }

        /** Creates a repo with an initial commit on [baseBranch] and a second branch [targetBranch]. */
        private fun createRepoWithSecondBranch(
            parent: File, name: String, baseBranch: String, targetBranch: String,
        ) {
            val dir = if (name == ".") parent else parent.resolve(name)
            dir.mkdirs()

            // Use standalone ProcessBuilder for setup — GitOps.run is private
            // and setup doesn't need cancellation/timeout lifecycle.
            gitOk(dir, "init")
            runGit(dir, "checkout", "-b", baseBranch)
            gitOk(dir, "config", "user.email", "benchmark@test.com")
            gitOk(dir, "config", "user.name", "Benchmark")
            gitOk(dir, "config", "core.autocrlf", "false")

            // Initial commit on baseBranch
            File(dir, "README.md").writeText("# $name\n")
            gitOk(dir, "add", "README.md")
            gitOk(dir, "commit", "-m", "initial $name")

            // Create targetBranch from baseBranch
            gitOk(dir, "branch", targetBranch)
        }

        private fun runGit(dir: File, vararg args: String): Pair<Int, String> {
            val proc = ProcessBuilder("git", *args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exit = proc.waitFor()
            return exit to out
        }

        private fun gitOk(dir: File, vararg args: String): String {
            val (code, out) = runGit(dir, *args)
            assertEquals(
                "git ${args.joinToString(" ")} should succeed in ${dir.name}: $out",
                0, code,
            )
            return out
        }

        private fun formatMs(ms: Long): String = when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
            else -> "${ms / 60_000}m ${"%.1f".format((ms % 60_000) / 1000.0)}s"
        }
    }

    // ── Benchmark tests ──────────────────────────────────────────────

    @Test
    fun `benchmark preflight probe on 51 repos`() {
        val submodules = (1..targetRepoCount).associate { "sub-$it" to "dev" }
        val preset = Preset("bench-pf", "dev", submodules)

        val preflight = SwitchPreflight(git)

        val elapsed = measureTimeMillis {
            val result = preflight.probe(tmpDir, preset, null)
            val repos = 1 + targetRepoCount
            assertEquals(repos, result.size)
            result.forEach { row ->
                assertTrue("${row.label}: should exist", row.exists)
                assertEquals("dev", row.target)
            }
        }

        println("╔══ Preflight benchmark ══╗")
        println("║ targets:      ${1 + targetRepoCount} (preset repos, no .gitmodules)")
        println("║ wall-clock:   ${formatMs(elapsed)}")
        println("╚══════════════════════════╝")
    }

    @Test
    fun `benchmark full switch pipeline on 51 repos`() {
        val submodules = (1..targetRepoCount).associate { "sub-$it" to "dev" }
        val preset = Preset("bench-sw", "dev", submodules)

        val log = mutableListOf<String>()
        val executor = SwitchExecutor(
            projectRoot = tmpDir,
            log = createStringAppender { log += it },
            git = git,
        )

        val elapsed = measureTimeMillis {
            val ok = executor.execute(
                com.submodule.branchswitcher.model.ResolvedSwitchRequest.resolve(
                    preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true),
                ),
            )
            assertTrue("switch to dev should succeed", ok)
        }

        // Verify the switch actually happened
        val root = tmpDir.toFile()
        assertEquals("dev", git.currentBranch(root))
        for (i in 1..targetRepoCount) {
            val subDir = root.resolve("sub-$i")
            assertEquals("sub-$i should be on dev", "dev", git.currentBranch(subDir))
        }

        println("╔══ Switch benchmark ══╗")
        println("║ targets:      ${1 + targetRepoCount} (preset repos, no .gitmodules)")
        println("║ wall-clock:   ${formatMs(elapsed)}")
        println("╚══════════════════════╝")
    }

    @Test
    fun `benchmark summary`() {
        // Prints the combined summary. Individual timing values are printed by the tests above.
        // (JUnit4 does not guarantee execution order; alphabetical happens to work here.)
        println("╔══ Large-repo benchmark summary ══╗")
        println("║ target repos:  $targetRepoCount (independent git repos, no .gitmodules)")
        println("║ git backend:    real GitOps")
        println("║ setup time:     ${formatMs(setupTimingMs)}")
        println("║ machine:        ${Runtime.getRuntime().availableProcessors()} cores")
        println("║ max memory:     ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
        println("╚══════════════════════════════════╝")
    }

    }
