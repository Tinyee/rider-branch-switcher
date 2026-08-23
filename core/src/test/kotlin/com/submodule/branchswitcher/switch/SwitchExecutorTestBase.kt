package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Before
import java.io.File

/**
 * Shared fixtures for the [SwitchExecutor] test classes: a clean repo fake, the
 * project root, the default preset, and real `git init` for submodule scenarios.
 */
abstract class SwitchExecutorTestBase {

    protected val log = mutableListOf<String>()

    // Default fake: clean repos, main branch exists, everything succeeds
    protected val fakeGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashOidByMessage(workDir: File, messagePrefix: String): String? =
            if (messagePrefix.startsWith(STASH_MESSAGE_PREFIX)) "stash-oid" else null
        override fun fetch(workDir: File): GitResult = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch == "main" || branch == "dev"
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev", "feature-x")
        override fun revParseHead(workDir: File): String? = "abc123"
        override fun stashApply(workDir: File, oid: String): GitResult = GitResult("pop", 0, "", "")
        override fun stashDrop(workDir: File, oid: String): GitResult = GitResult("stash drop", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
        override fun repositoryIdentity(workDir: File): RepositoryIdentity {
            val root = projectRoot.toFile().canonicalFile
            val directory = if (workDir.canonicalFile == root) {
                File(root, ".git")
            } else {
                File(root, ".git/modules/${workDir.name}")
            }
            return RepositoryIdentity(directory.absolutePath, root.takeIf { workDir.canonicalFile != root }?.path)
        }
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
            SubmoduleRegistration("SubA", "SubA", "."),
            SubmoduleRegistration("SubB", "SubB", "."),
        )
        override fun resetHard(workDir: File, revision: String): GitResult = GitResult("reset", 0, "", "")
        override fun cancel() = Unit
    }

    protected val projectRoot = java.nio.file.Files.createTempDirectory("test-executor")
    protected val preset = Preset("test", "dev", emptyMap())

    /**
     * Wraps a base git client so [collisions] are reported as untracked + matching the
     * target tree, and simulates the approved-stash lifecycle against real files: `stashPaths`
     * removes the still-present collision files and records their oid; `stashApply` restores
     * the files of exactly that oid; `stashDrop` records the drop. Message lookups only match
     * approved-discard messages, so the WIP-stash flow keeps its own fallback.
     */
    protected class ApprovedStashFake(
        private val base: GitClient,
        private val collisions: Set<String>,
    ) : GitClient by base {
        var stashPathsCalls = 0
        var dropCalls = 0
        var applyCalls = 0
        var isolated = mutableSetOf<String>()
        private val oidFiles = mutableMapOf<String, Set<String>>()

        override fun untrackedFiles(workDir: File): List<String> = collisions.toList()

        override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
            paths.filter { it in collisions }

        override fun headStructuralCollisions(workDir: File, paths: List<String>): List<String> =
            paths.filter { it in collisions }

        override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult {
            stashPathsCalls++
            val files = mutableSetOf<String>()
            for (path in paths) {
                val file = File(workDir, path)
                if (file.isFile) {
                    file.delete()
                    files += path
                    isolated += path
                }
            }
            if (files.isNotEmpty()) oidFiles["approved-oid"] = files
            return GitResult("stash paths", 0, "", "")
        }

        override fun stashOidByMessage(workDir: File, messagePrefix: String): String? = when {
            // WIP stash identity (the production client matches the unique per-operation
            // message, so the fake must serve the same message-based lookup).
            messagePrefix.startsWith(STASH_MESSAGE_PREFIX) -> "stash-oid"
            messagePrefix.startsWith(APPROVED_DISCARD_MESSAGE_PREFIX) && "approved-oid" in oidFiles -> "approved-oid"
            else -> null
        }

        override fun stashApply(workDir: File, oid: String): GitResult {
            applyCalls++
            for (path in oidFiles[oid].orEmpty()) {
                val file = File(workDir, path)
                file.parentFile.mkdirs()
                file.writeText("restored")
            }
            return GitResult("stash apply", 0, "", "")
        }

        override fun stashDrop(workDir: File, oid: String): GitResult {
            dropCalls++
            return GitResult("stash drop", 0, "", "")
        }
    }

    protected fun recovery(git: GitClient = fakeGit) =
        SwitchRecoveryExecutor(projectRoot, createStringAppender { log += it }, git)

    @Before
    fun setup() {
        log.clear()
        initGitRepo(projectRoot.toFile())
    }

    protected fun initGitRepo(dir: File) {
        dir.mkdirs()
        val proc = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("git init should succeed in ${dir.absolutePath}: $out", 0, proc.waitFor())
    }
}
