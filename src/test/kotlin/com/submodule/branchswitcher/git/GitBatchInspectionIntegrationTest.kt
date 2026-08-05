package com.submodule.branchswitcher.git

import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.workflow.RepositoryStateDetector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class GitBatchInspectionIntegrationTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("git-batch-inspection-")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `state refresh and preflight keep real cli process budgets linear`() {
        val submodulePaths = (1..4).map { "Sub$it" }
        val repositories = listOf(root.toFile()) + submodulePaths.map { root.resolve(it).toFile() }
        repositories.forEach(::initializeRepository)
        val branch = gitOutput(root.toFile(), "branch", "--show-current")
        val starts = AtomicInteger(0)
        val git = GitOps(timeoutSeconds = 10) { builder ->
            starts.incrementAndGet()
            builder.start()
        }
        val detector = RepositoryStateDetector(
            log = createStringAppender {},
            cancellationClassifier = CancellationClassifier.DEFAULT,
        )

        val snapshot = detector.detect(detector.begin(root, listOf(".") + submodulePaths), git)

        assertEquals(5, snapshot.branches.size)
        assertEquals("one status process per repository", 5, starts.get())

        starts.set(0)
        val preset = Preset("Scale", branch, submodulePaths.associateWith { branch })
        val rows = SwitchPreflight(git).probe(root, preset)

        assertEquals(5, rows.size)
        assertTrue(rows.all { it.probeError == null })
        assertTrue(rows.all { it.hasLocal })
        assertEquals("status, remote and refs per repository", 15, starts.get())
    }

    @Test
    fun `batch inspection returns status head and exact target refs`() {
        initializeRepository(root.toFile())
        val branch = gitOutput(root.toFile(), "branch", "--show-current")
        runGit(root.toFile(), "update-ref", "refs/remotes/origin/remote-only", "HEAD")
        File(root.toFile(), "tracked.txt").appendText("dirty\n")
        val starts = AtomicInteger(0)
        val git = GitOps(timeoutSeconds = 10) { builder ->
            starts.incrementAndGet()
            builder.start()
        }

        val state = git.inspectRepositoryState(root.toFile())

        assertEquals(1, starts.get())
        assertEquals(branch, state.currentBranch)
        assertNotNull(state.head)
        assertEquals(1, state.dirtyFileCount)

        starts.set(0)
        val preflight = git.inspectPreflight(root.toFile(), setOf(branch, "remote-only", "missing"))

        assertEquals(3, starts.get())
        assertEquals(setOf(branch), preflight.localBranches)
        assertEquals(setOf("remote-only"), preflight.remoteBranches)
    }

    @Test
    fun `batch preflight remains fail closed when git cannot start`() {
        initializeRepository(root.toFile())
        val git = GitOps(timeoutSeconds = 10) { throw java.io.IOException("git unavailable") }

        val row = SwitchPreflight(git).probe(root, Preset("Broken", "main")).single()

        assertTrue(row.exists)
        assertFalse(row.hasLocal)
        assertFalse(row.hasRemote)
        assertEquals(-1, row.dirtyCount)
        assertNotNull(row.probeError)
        assertTrue(row.probeError!!.contains("GitQueryException"))
    }

    @Test
    fun `tracked stash apply restores the requested oid and retains recovery backups`() {
        initializeRepository(root.toFile())
        val repository = root.toFile()
        val trackedFile = File(repository, "tracked.txt")
        val git = GitOps(timeoutSeconds = 10)

        trackedFile.writeText("first change\n")
        assertTrue(git.stash(repository, "first").ok)
        val firstOid = requireNotNull(git.stashTopOid(repository))

        trackedFile.writeText("second change\n")
        assertTrue(git.stash(repository, "second").ok)
        val secondOid = requireNotNull(git.stashTopOid(repository))
        assertNotEquals(firstOid, secondOid)

        val firstApply = git.stashApply(repository, firstOid)
        assertTrue(firstApply.diagnostic(), firstApply.ok)
        assertEquals("first change\n", trackedFile.readText())
        assertEquals(secondOid, git.stashTopOid(repository))

        runGit(repository, "reset", "--hard", "HEAD")
        val secondApply = git.stashApply(repository, secondOid)
        assertTrue(secondApply.diagnostic(), secondApply.ok)
        assertEquals("second change\n", trackedFile.readText())
        assertEquals(secondOid, git.stashTopOid(repository))
    }

    private fun initializeRepository(directory: File) {
        directory.mkdirs()
        runGit(directory, "init", "--quiet")
        runGit(directory, "config", "user.email", "tests@example.com")
        runGit(directory, "config", "user.name", "Branch Switcher Tests")
        File(directory, "tracked.txt").writeText("initial\n")
        runGit(directory, "add", "tracked.txt")
        runGit(directory, "commit", "--quiet", "-m", "initial")
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.waitFor())
    }

    private fun gitOutput(directory: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.waitFor())
        return output
    }
}
