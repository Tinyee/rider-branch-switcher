package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SwitchStepTest {

    private val log = mutableListOf<String>()
    private val projectRoot: Path = Files.createTempDirectory("test-step")

    private val fakeGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashPop(workDir: File, oid: String): GitResult = GitResult("pop", 0, "", "")
        override fun fetch(workDir: File): GitResult = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch in listOf("main", "dev")
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
        override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev")
        override fun revParseHead(workDir: File): String? = "abc123"
        override fun repositoryIdentity(workDir: File): RepositoryIdentity {
            val root = projectRoot.toFile().canonicalFile
            val sectionName = workDir.name
            val gitDirectory = if (workDir.canonicalFile == root) {
                File(root, ".git")
            } else {
                File(root, ".git/modules/$sectionName")
            }
            return RepositoryIdentity(gitDirectory.absolutePath, root.takeIf { workDir.canonicalFile != root }?.path)
        }
        override fun remoteUrl(workDir: File): String? = null
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
            SubmoduleRegistration("SubA", "SubA", "."),
        )
        override fun resetHard(workDir: File, revision: String): GitResult = GitResult("reset", 0, "", "")
        override fun cancel() = Unit
        override fun localBranchProbe(workDir: File, branch: String): Boolean = localBranchExists(workDir, branch)
        override fun dirtyProbe(workDir: File): Boolean = isDirty(workDir)
    }

    private fun context(opts: SwitchOptions = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)) =
        SwitchContext(projectRoot, Preset("test", "dev"), opts, fakeGit, createStringAppender { log += it })

    private fun SwitchStep.run(
        context: SwitchContext,
        state: SwitchState = SwitchState(),
    ): StepExecution = execute(context, state)

    @Before
    fun setup() {
        log.clear()
        initGitRepo(projectRoot.toFile())
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

    // ---- CheckoutStep ----

    @Test
    fun `checkout step skip when already on target branch`() {
        val sameGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
        }
        val c = context().copy(git = sameGit)
        val step = CheckoutStep()
        val execution = step.run(c)
        assertTrue(execution.result is StepResult.Success)
        assertTrue(log.any { it.contains("already on") })
        assertTrue(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout step fails when branch not found`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val c = context().copy(git = missingGit)
        val step = CheckoutStep()
        assertTrue(step.run(c).result is StepResult.Partial)
    }

    @Test
    fun `checkout step creates from remote when local missing`() {
        var remoteCheckoutCalls = 0
        val remoteOnlyGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
            override fun checkoutFromRemote(workDir: File, branch: String): GitResult {
                remoteCheckoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(git = remoteOnlyGit)
        val step = CheckoutStep()
        assertTrue(step.run(c).result is StepResult.Success)
        assertEquals(1, remoteCheckoutCalls)
        assertTrue(log.any { it.contains("local branch missing") })
    }

    @Test
    fun `checkout step does not touch paths skipped by dirty handling`() {
        var checkoutCalls = 0
        val trackingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(git = trackingGit)
        val state = SwitchState().withSkipped(".")

        val execution = CheckoutStep().run(c, state)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(0, checkoutCalls)
        assertFalse(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout failure is not recorded as successful`() {
        val failingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 1, "", "conflict")
        }
        val c = context().copy(git = failingGit)

        val execution = CheckoutStep().run(c)
        assertTrue(execution.result is StepResult.Partial)
        assertFalse(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `missing branch restores stash and removes its tracking entry`() {
        var popCalls = 0
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun stashPop(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context().copy(git = missingGit)
        val state = SwitchState().withTrackedStash(".", "before -> dev", "stash-oid")

        val execution = CheckoutStep().run(c, state)
        assertTrue(execution.result is StepResult.Partial)
        assertEquals(1, popCalls)
        assertFalse(execution.state.stashesSnapshot().containsKey("."))
    }

    // ---- DirtyHandlingStep ----

    @Test
    fun `dirty step skip clean repo`() {
        val c = context()
        val step = DirtyHandlingStep()
        assertTrue(step.run(c).result is StepResult.Success)
    }

    @Test
    fun `dirty step stash on dirty repo`() {
        var stashCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                return GitResult("stash", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        val execution = step.run(c)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(1, stashCalls)
        assertTrue(execution.state.hasStashes())
        assertTrue(log.any { it.contains("stash: ok") })
    }

    @Test
    fun `dirty step retains an unidentified stash but blocks further writes`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashTopOid(workDir: File): String? = null
        }
        val execution = DirtyHandlingStep().run(
            context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit),
        )

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertNull(execution.state.trackedStash(".")?.oid)
        val issue = (execution.result as StepResult.Partial).issues.single()
        assertEquals(OperationIssueCode.STASH_IDENTITY_UNAVAILABLE, issue.code)
    }

    @Test
    fun `dirty step fail with skip on dirty repo`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val c = context(SwitchOptions(DirtyAction.Skip)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        val initialState = SwitchState()
        val execution = step.run(c, initialState)
        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertFalse(initialState.isSkipped("."))
    }

    @Test
    fun `stash failure marks path skipped and does not track stash`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", 1, "", "failed")
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)
        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertFalse(execution.state.hasStashes())
    }

    // ---- FetchStep ----

    @Test
    fun `fetch step skip when option disabled`() {
        val noFetchGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult = error("fetch should not be called")
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = false)).copy(git = noFetchGit)
        val step = FetchStep()
        assertTrue(step.run(c).result is StepResult.Success)
    }

    @Test
    fun `fetch step still fetches when already on target`() {
        var fetchCalls = 0
        val alreadyGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun fetch(workDir: File): GitResult {
                fetchCalls++
                return GitResult("fetch", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = true)).copy(git = alreadyGit)
        val step = FetchStep()
        assertTrue(step.run(c).result is StepResult.Success)
        assertEquals(1, fetchCalls)
    }

    @Test
    fun `fetch failure is reported as partial`() {
        val failingGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult =
                GitResult("fetch", 1, "", "network unavailable")
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = true)).copy(git = failingGit)

        val result = FetchStep().run(c).result

        assertEquals(
            listOf("." to OperationIssueCode.FETCH_FAILED),
            (result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
    }

    // ---- PullStep ----

    @Test
    fun `pull step skip when preset pull disabled`() {
        val noPullGit = object : GitClient by fakeGit {
            override fun pullFf(workDir: File, branch: String): GitResult = error("should not be called")
        }
        val noPullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = noPullGit, preset = noPullPreset)
        val step = PullStep()
        assertTrue(step.run(c).result is StepResult.Success) // options.pull = false -> skip
    }

    @Test
    fun `pull step executes when both enabled`() {
        val calls = mutableListOf<String>()
        val pullGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult {
                calls += branch
                return GitResult("pull", 0, "", "")
            }
        }
        val pullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = pullGit, preset = pullPreset)
        val state = SwitchState().withSuccessfulCheckout(".")
        val step = PullStep()
        assertTrue(step.run(c, state).result is StepResult.Success)
        assertEquals(listOf("dev"), calls)
    }

    @Test
    fun `pull failure is partial and still restores tracked stash`() {
        var popCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult =
                GitResult("pull", 1, "", "not fast-forward")

            override fun stashPop(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = failingGit)
        val state = SwitchState()
            .withSuccessfulCheckout(".")
            .withTrackedStash(".", "main -> dev", "stash-oid")

        val execution = PullStep().run(c, state)

        assertEquals(
            listOf("." to OperationIssueCode.PULL_FAILED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(1, popCalls)
        assertFalse(execution.state.hasStashes())
    }

    @Test
    fun `pull step skips repos whose checkout did not succeed`() {
        var pullCalls = 0
        val pullGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult {
                pullCalls++
                return GitResult("pull", 0, "", "")
            }
        }
        val pullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = pullGit, preset = pullPreset)

        assertTrue(PullStep().run(c).result is StepResult.Success)
        assertEquals(0, pullCalls)
    }

    @Test
    fun `pull disabled still restores tracked stashes`() {
        var popCalls = 0
        val popGit = object : GitClient by fakeGit {
            override fun stashPop(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = popGit)
        val state = SwitchState().withTrackedStash(".", "before -> dev", "stash-oid")

        val execution = PullStep().run(c, state)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(1, popCalls)
        assertFalse(execution.state.hasStashes())
    }

    @Test
    fun `stash pop failure makes pull partial and keeps recovery state`() {
        val popGit = object : GitClient by fakeGit {
            override fun stashPop(workDir: File, oid: String): GitResult =
                GitResult("pop", 1, "", "conflict")
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = popGit)
        val state = SwitchState().withTrackedStash(".", "before -> dev", "stash-oid")

        val execution = PullStep().run(c, state)

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.hasStashes())
    }

    @Test
    fun `staged pull restores only stashes in its target scope`() {
        val popped = mutableListOf<String>()
        projectRoot.resolve("SubA").toFile().mkdirs()
        val popGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun stashPop(workDir: File, oid: String): GitResult {
                popped += if (workDir == projectRoot.toFile()) "." else workDir.name
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(
            git = popGit,
            preset = Preset("test", "dev", mapOf("SubA" to "dev")),
        )
        val initialState = SwitchState()
            .withTrackedStash(".", "before -> dev", "stash-oid")
            .withTrackedStash("SubA", "before -> dev", "stash-oid")

        val mainExecution = PullStep(SwitchTargetScope.MAIN).run(c, initialState)
        assertTrue(mainExecution.result is StepResult.Success)
        assertEquals(listOf("."), popped)
        assertEquals(setOf("SubA"), mainExecution.state.stashesSnapshot().keys)

        val submoduleExecution = PullStep(SwitchTargetScope.SUBMODULES).run(c, mainExecution.state)
        assertTrue(submoduleExecution.result is StepResult.Success)
        assertEquals(listOf(".", "SubA"), popped)
        assertFalse(submoduleExecution.state.hasStashes())
    }

    @Test
    fun `submodule target scope processes parents before nested paths`() {
        val preset = Preset(
            "nested",
            "main",
            linkedMapOf(
                "SubA/Nested" to "nested-dev",
                "SubB" to "main",
                "SubA" to "dev",
            ),
        )

        assertEquals(
            listOf("SubB", "SubA", "SubA/Nested"),
            preset.targetsFor(SwitchTargetScope.SUBMODULES).map { it.path },
        )
    }

    @Test
    fun `failed submodule initialization prevents checkout and is not tracked as initialized`() {
        var checkoutCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = workDir.name != "SubA"

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
                GitResult("init", 1, "", "clone failed")

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = failingGit,
            preset = Preset("test", "main", mapOf("SubA" to "dev")),
        )
        val state = SwitchState().withSuccessfulCheckout(".")

        val execution = SubmoduleTreeStep().run(c, state)

        assertEquals(
            listOf("SubA" to OperationIssueCode.SUBMODULE_INIT_FAILED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.initializedSubmodulesSnapshot().isEmpty())
        assertFalse(execution.state.checkoutSucceeded("SubA"))
    }

    @Test
    fun `successful init command without a usable repository fails its target locally`() {
        var checkoutCalls = 0
        val incompleteGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = workDir.name != "SubA"
            override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
                GitResult("init", 0, "", "")

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val execution = SubmoduleTreeStep().run(
            context().copy(
                git = incompleteGit,
                preset = Preset("test", "main", mapOf("SubA" to "dev")),
            ),
            SwitchState().withSuccessfulCheckout("."),
        )

        assertEquals(
            listOf("SubA" to OperationIssueCode.SUBMODULE_DIRECTORY_MISSING),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.initializedSubmodulesSnapshot().isEmpty())
    }

    @Test
    fun `obsolete preset path is skipped after main checkout`() {
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun registeredSubmodulePaths(gitRoot: File): Set<String> = setOf("NewSub")

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("moved", "main", mapOf("OldSub" to "dev")),
        )

        val execution = SubmoduleTreeStep().run(
            c,
            SwitchState().withSuccessfulCheckout("."),
        )

        assertEquals(
            listOf("OldSub" to OperationIssueCode.SUBMODULE_NOT_REGISTERED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.isSkipped("OldSub"))
        assertTrue(log.any { it.contains("obsolete worktree retained") })
    }

    @Test
    fun `submodule remote added after checkpoint blocks checkout`() {
        projectRoot.resolve("SubA").toFile().mkdirs()
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun registeredSubmodulePaths(gitRoot: File): Set<String> = setOf("SubA")
            override fun remoteUrl(workDir: File): String = "replacement-url"

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("replacement", "main", mapOf("SubA" to "dev")),
            checkpoint = mapOf("SubA" to CheckpointEntry("before", "main", remoteUrl = null)),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertEquals(
            listOf("SubA" to OperationIssueCode.REPOSITORY_REMOTE_CHANGED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
    }

    @Test
    fun `nested registration refreshes after parent checkout`() {
        projectRoot.resolve("Parent/Nested").toFile().mkdirs()
        val checkedOut = mutableListOf<String>()
        var registrationReads = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true

            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> {
                registrationReads++
                return if (registrationReads == 1) {
                    listOf(SubmoduleRegistration("Parent", "Parent", "."))
                } else {
                    listOf(
                        SubmoduleRegistration("Parent", "Parent", "."),
                        SubmoduleRegistration("Parent/Nested", "Nested", "Parent"),
                    )
                }
            }

            override fun repositoryIdentity(workDir: File): RepositoryIdentity {
                val root = projectRoot.toFile().canonicalFile
                val relative = workDir.canonicalFile.relativeTo(root).invariantSeparatorsPath
                val gitDirectory = when (relative) {
                    "" -> File(root, ".git")
                    "Parent" -> File(root, ".git/modules/Parent")
                    else -> File(root, ".git/modules/Parent/modules/Nested")
                }
                val superproject = when (relative) {
                    "" -> null
                    "Parent" -> root.path
                    else -> File(root, "Parent").path
                }
                return RepositoryIdentity(gitDirectory.absolutePath, superproject)
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkedOut += workDir.relativeTo(projectRoot.toFile()).invariantSeparatorsPath
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset(
                "nested",
                "main",
                linkedMapOf("Parent/Nested" to "dev", "Parent" to "dev"),
            ),
        )

        val execution = SubmoduleTreeStep().run(
            c,
            SwitchState().withSuccessfulCheckout("."),
        )

        assertTrue(
            "Expected nested checkout success, got ${execution.result}",
            execution.result is StepResult.Success,
        )
        assertEquals(listOf("Parent", "Parent/Nested"), checkedOut)
    }

    @Test
    fun `nested target initializes from its registered parent when parent is not in preset`() {
        val parent = projectRoot.resolve("Parent").toFile().also(File::mkdirs)
        var initRoot: File? = null
        var initPath: String? = null
        val validatingGit = object : GitClient by fakeGit {
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> =
                listOf(SubmoduleRegistration("Parent/Nested", "Nested", "Parent"))

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                initRoot = gitRoot
                initPath = path
                File(gitRoot, path).mkdirs()
                return GitResult("init", 0, "", "")
            }

            override fun isGitRepo(workDir: File): Boolean = workDir.exists() && workDir.name == "Nested"

            override fun repositoryIdentity(workDir: File): RepositoryIdentity {
                val root = projectRoot.toFile().canonicalFile
                return when (workDir.name) {
                    "Parent" -> RepositoryIdentity(File(root, ".git/modules/Parent").path, root.path)
                    "Nested" -> RepositoryIdentity(
                        File(root, ".git/modules/Parent/modules/Nested").path,
                        File(root, "Parent").path,
                    )
                    else -> RepositoryIdentity(File(root, ".git").path, null)
                }
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("nested", "main", mapOf("Parent/Nested" to "dev")),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertTrue("Expected nested init success, got ${execution.result}", execution.result is StepResult.Success)
        assertEquals(parent.canonicalFile, initRoot?.canonicalFile)
        assertEquals("Nested", initPath)
    }

}
