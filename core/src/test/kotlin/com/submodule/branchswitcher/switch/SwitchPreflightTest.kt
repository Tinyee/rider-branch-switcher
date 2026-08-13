package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.model.Preset
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SwitchPreflightTest {

    private val fakeGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashApply(workDir: File, oid: String): GitResult = GitResult("pop", 0, "", "")
        override fun fetch(workDir: File): GitResult = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch in listOf("main", "dev")
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = branch != "local-only"
        override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
        override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = listOf("SubA", "SubB")
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev", "feature-x")
        override fun revParseHead(workDir: File): String? = "abc123"
        override fun repositoryIdentity(workDir: File): RepositoryIdentity =
            RepositoryIdentity(File(workDir, ".git").absolutePath, null)
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> =
            listSubmodulePaths(gitRoot).map { SubmoduleRegistration(it, it, ".") }
        override fun resetHard(workDir: File, revision: String) = GitResult("reset", 0, "", "")
        override fun cancel() = Unit
        override fun localBranchProbe(workDir: File, branch: String): Boolean = localBranchExists(workDir, branch)
        override fun dirtyProbe(workDir: File): Boolean = isDirty(workDir)
    }

    private val projectRoot: Path = Files.createTempDirectory("test-preflight")

    @Before
    fun setup() {
        initGitRepo(projectRoot.toFile())
        initGitRepo(projectRoot.resolve("SubA").toFile())
        initGitRepo(projectRoot.resolve("SubB").toFile())
    }

    private fun initGitRepo(dir: File) {
        dir.mkdirs()
        val proc = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("git init should succeed in ${dir.absolutePath}: $out", 0, proc.waitFor())
    }

    @Test
    fun `probe returns correct count for preset with submodules`() {
        val preflight = SwitchPreflight(fakeGit)
        val preset = Preset("test", "dev", mapOf("SubA" to "dev", "SubB" to "feature-x"))
        val rows = preflight.probe(projectRoot, preset)
        assertEquals(3, rows.size)
        assertEquals(".", rows[0].path)
        assertEquals("SubA", rows[1].path)
        assertEquals("SubB", rows[2].path)
        assertEquals(projectRoot.fileName.toString(), rows[0].label)
        assertTrue("Main repo should be marked as main", rows[0].isMain)
        assertTrue(rows[0].needsSwitch)
    }

    @Test
    fun `probe detects missing directory`() {
        val preflight = SwitchPreflight(fakeGit)
        val preset = Preset("test", "dev", mapOf("Ghost" to "dev"))
        val rows = preflight.probe(projectRoot, preset)
        val ghost = rows.find { it.path == "Ghost" }
        assertNotNull("Ghost submodule should be in rows", ghost)
        assertFalse("Ghost should not exist", ghost!!.exists)
        assertEquals(-1, ghost.dirtyCount)
    }

    @Test
    fun `probe detects dirty repos`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun dirtyFileCount(workDir: File): Int = 3
        }
        val preflight = SwitchPreflight(dirtyGit)
        val preset = Preset("test", "main")
        val rows = preflight.probe(projectRoot, preset)
        assertEquals(3, rows[0].dirtyCount)
    }

    @Test
    fun `probe detects local-only branch`() {
        val localGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = true
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val preflight = SwitchPreflight(localGit)
        val preset = Preset("test", "local-only")
        val rows = preflight.probe(projectRoot, preset)
        assertTrue("Should have local branch", rows[0].hasLocal)
        assertFalse("Should not have remote branch", rows[0].hasRemote)
        assertFalse("Should not be branchMissing", rows[0].branchMissing)
    }

    @Test
    fun `probe detects missing branch`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val preflight = SwitchPreflight(missingGit)
        val preset = Preset("test", "no-branch")
        val rows = preflight.probe(projectRoot, preset)
        assertTrue("Should be branchMissing", rows[0].branchMissing)
    }

    @Test
    fun `probe needsSwitch is false when already on target`() {
        val preflight = SwitchPreflight(fakeGit)
        val preset = Preset("test", "main")  // target is main, current is main
        val rows = preflight.probe(projectRoot, preset)
        assertFalse("needsSwitch should be false", rows[0].needsSwitch)
    }

    @Test
    fun `probe converts ordinary git exception to fail-closed row`() {
        val throwingGit = object : GitClient by fakeGit {
            override fun dirtyFileCount(workDir: File): Int = throw java.io.IOException("git failed")
        }
        val failures = mutableListOf<Pair<String, Exception>>()
        val preflight = SwitchPreflight(throwingGit, onProbeFailure = { path, error ->
            failures += path to error
        })
        val preset = Preset("test", "main")
        val rows = preflight.probe(projectRoot, preset)
        assertEquals(1, rows.size)
        assertTrue("row should exist", rows[0].exists)
        assertEquals(-1, rows[0].dirtyCount)
        assertFalse(rows[0].hasLocal)
        assertFalse("probe error is unknown state, not a missing branch", rows[0].branchMissing)
        assertEquals("IOException: git failed", rows[0].probeError)
        assertEquals(listOf("."), failures.map { it.first })
        assertTrue(failures.single().second is java.io.IOException)
    }

    @Test
    fun `repository probe failure is not reported as a missing directory`() {
        val throwingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = throw java.io.IOException("cannot inspect repo")
        }

        val row = SwitchPreflight(throwingGit).probe(projectRoot, Preset("test", "main")).single()

        assertTrue(row.exists)
        assertEquals("IOException: cannot inspect repo", row.probeError)
    }

    @Test
    fun `probe caps diagnostic text when git exception message is long`() {
        val throwingGit = object : GitClient by fakeGit {
            override fun dirtyFileCount(workDir: File): Int =
                throw IllegalStateException("x".repeat(400))
        }

        val rows = SwitchPreflight(throwingGit).probe(projectRoot, Preset("test", "main"))

        assertEquals(300, rows.single().probeError!!.length)
        assertTrue(rows.single().probeError!!.startsWith("IllegalStateException: "))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun `probe rethrows CancellationException instead of converting to row`() {
        val cancelGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? =
                throw java.util.concurrent.CancellationException("cancelled")
        }
        val preflight = SwitchPreflight(cancelGit)
        preflight.probe(projectRoot, Preset("test", "main"))
    }

    // Simulates IntelliJ ProcessCanceledException without importing the type
    class ProcessCanceledException(msg: String) : RuntimeException(msg)

    @Test(expected = ProcessCanceledException::class)
    fun `probe rethrows PCE-like exception instead of converting to row`() {
        val pceGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? =
                throw ProcessCanceledException("cancelled")
        }
        val pceClassifier = CancellationClassifier { e ->
            e is java.util.concurrent.CancellationException || e is ProcessCanceledException
        }
        val preflight = SwitchPreflight(pceGit, classifier = pceClassifier)
        preflight.probe(projectRoot, Preset("test", "main"))
    }
}
