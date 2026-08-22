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
