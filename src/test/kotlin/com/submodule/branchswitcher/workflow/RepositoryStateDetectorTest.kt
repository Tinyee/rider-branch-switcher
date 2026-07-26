package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.RepositoryStateGitClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.switch.CancellationClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class RepositoryStateDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `detect returns branch and dirty state for existing repositories`() {
        val root = temp.newFolder("root")
        val module = File(root, "module").apply { mkdirs() }
        val git = RecordingGit().apply {
            branches[root] = "main"
            branches[module] = "develop"
            dirty[module] = true
        }
        var provided: RepositoryStateGitClient = git
        val detector = RepositoryStateDetector(
            gitClient = { provided },
            log = createStringAppender {},
            cancellationClassifier = CancellationClassifier.DEFAULT,
        )

        val snapshot = detector.detect(detector.begin(root.toPath(), listOf(".", "module", "missing")))

        assertEquals(mapOf("." to "main", "module" to "develop", "missing" to null), snapshot.branches)
        assertEquals(mapOf("." to false, "module" to true, "missing" to false), snapshot.dirtyRepositories)

        val replacement = RecordingGit()
        provided = replacement
        detector.detect(detector.begin(root.toPath(), listOf(".")))
        assertEquals("provider must be resolved for each detection", 1, replacement.currentBranchCalls)
    }

    @Test
    fun `repository failure is isolated and logged`() {
        val root = temp.newFolder("root")
        val module = File(root, "module").apply { mkdirs() }
        val logs = mutableListOf<String>()
        val git = RecordingGit().apply {
            branches[root] = "main"
            failures += module
        }
        val detector = detector(git, logs)

        val snapshot = detector.detect(detector.begin(root.toPath(), listOf(".", "module")))

        assertEquals("main", snapshot.branches["."])
        assertEquals(null, snapshot.branches["module"])
        assertFalse(snapshot.dirtyRepositories.getValue("module"))
        assertTrue(logs.any { "[detect] module: IllegalStateException: unreadable" in it })
    }

    @Test
    fun `cancellation is not converted into an unreadable repository`() {
        val root = temp.newFolder("root")
        val git = RecordingGit().apply { cancellation = root }
        val detector = detector(git)
        val request = detector.begin(root.toPath(), listOf("."))

        assertThrows(CancellationException::class.java) { detector.detect(request) }
    }

    @Test
    fun `new request makes an older snapshot stale`() {
        val root = temp.newFolder("root")
        val detector = detector(RecordingGit())
        val first = detector.detect(detector.begin(root.toPath(), listOf(".")))
        val second = detector.detect(detector.begin(root.toPath(), listOf(".")))

        assertFalse(detector.isLatest(first))
        assertTrue(detector.isLatest(second))
    }

    private fun detector(
        git: RepositoryStateGitClient,
        logs: MutableList<String> = mutableListOf(),
    ) = RepositoryStateDetector(
        gitClient = { git },
        log = createStringAppender { logs += it },
        cancellationClassifier = CancellationClassifier.DEFAULT,
    )

    private class RecordingGit : RepositoryStateGitClient {
        val branches = mutableMapOf<File, String?>()
        val dirty = mutableMapOf<File, Boolean>()
        val failures = mutableSetOf<File>()
        var cancellation: File? = null
        var currentBranchCalls = 0

        override fun currentBranch(workDir: File): String? {
            currentBranchCalls++
            if (workDir == cancellation) throw CancellationException("cancelled")
            if (workDir in failures) error("unreadable")
            return branches[workDir]
        }

        override fun revParseHead(workDir: File): String? = "sha"

        override fun isDirty(workDir: File): Boolean = dirty[workDir] == true
    }
}
