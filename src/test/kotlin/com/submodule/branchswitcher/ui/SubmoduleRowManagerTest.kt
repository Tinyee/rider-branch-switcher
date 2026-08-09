package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JPanel

class SubmoduleRowManagerTest {

    @Test
    fun `submodule row context menu listener is installed on child label panel`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            gitClient = ::emptyGit,
            branchLoads = emptyBranchLoads(),
            body = JPanel(),
            log = createStringAppender {},
            onDirty = {},
        )

        val row = manager.buildSubRow("SubA", "dev")

        val pathLabel = descendants(row.panel)
            .filterIsInstance<JLabel>()
            .firstOrNull { it.text == "SubA" }

        assertNotNull(pathLabel)
        assertTrue(pathLabel!!.mouseListeners.isNotEmpty())
    }

    @Test
    fun `single submodule switch uses the currently visible target`() {
        var requested: Pair<String, String>? = null
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            gitClient = ::emptyGit,
            branchLoads = emptyBranchLoads(),
            body = JPanel(),
            log = createStringAppender {},
            onDirty = {},
            onSwitchOnly = { path, target -> requested = path to target },
        )
        val row = manager.buildSubRow("SubA", "dev")
        row.combo.selectedItem = "release"

        manager.requestSwitchOnly("SubA")

        assertEquals("SubA" to "release", requested)
    }

    @Test
    fun `failed current branch discovery always finishes row loading`() {
        val root = Files.createTempDirectory("submodule-row")
        Files.createDirectories(root.resolve("SubA"))
        val body = JPanel().apply { add(JPanel()) }
        val finished = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            gitClient = { failingCurrentBranchGit() },
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                failingCurrentBranchOperation()
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = {
                it()
                finished.countDown()
            },
        )
        manager.onFirstExpand()

        manager.addSubmoduleFromMenu("SubA")

        assertTrue("row branch load should finish", finished.await(5, TimeUnit.SECONDS))
        requireNotNull(manager.subRows["SubA"])
        assertEquals(0, manager.loadingCount)
    }

    @Test
    fun `a failed submodule load is retried on the next loadAllBranches`() {
        // Simulates collapse + re-expand after a submodule-only failure: PresetEditor
        // resets branchesLoaded on collapse when the manager reports unloaded rows, so
        // the next expand retries the failed row even though the main repo loaded.
        val root = Files.createTempDirectory("submodule-row-retry")
        Files.createDirectories(root.resolve("SubA"))
        val body = JPanel().apply { add(JPanel()) }
        var listCalls = 0
        var uiCount = 0
        val firstLoadDone = CountDownLatch(1)
        val retryDone = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            gitClient = ::emptyGit,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    if (methodName == "listAllBranches") {
                        listCalls++
                        if (listCalls == 1) error("git temporarily unavailable")
                        listOf("dev")
                    }
                    null
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = {
                it()
                if (uiCount == 0) firstLoadDone.countDown()
                uiCount++
                if (uiCount == 2) retryDone.countDown()
            },
        )
        manager.onFirstExpand()

        manager.addSubmoduleFromMenu("SubA")
        assertTrue("first load should finish", firstLoadDone.await(5, TimeUnit.SECONDS))
        val row = requireNotNull(manager.subRows["SubA"])
        assertFalse("failed load must reset row.loaded so a re-expand retries", row.loaded)
        assertTrue("failed row must leave the manager with unloaded rows", manager.hasUnloadedRows())
        assertEquals(0, manager.loadingCount)

        manager.loadAllBranches(Preset("Work", "main", mapOf("SubA" to "dev")))
        assertTrue("retry load should finish", retryDone.await(5, TimeUnit.SECONDS))
        assertFalse("a successful retry clears the unloaded rows", manager.hasUnloadedRows())
        assertEquals("failed load must be retried on the next loadAllBranches", 2, listCalls)
    }

    @Test
    fun `removing a loading submodule row cancels its git operation`() {
        val root = Files.createTempDirectory("submodule-row-cancel")
        Files.createDirectories(root.resolve("SubA"))
        val body = JPanel()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val operation = gitOperation(onCancel = { cancelled.set(true) }) { methodName ->
            if (methodName == "listAllBranches") {
                started.countDown()
                while (!cancelled.get()) Thread.sleep(10)
                throw CancellationException("row removed")
            }
            null
        }
        val manager = SubmoduleRowManager(
            gitRoot = root,
            gitClient = ::emptyGit,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) { operation },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = {
                it()
                finished.countDown()
            },
        )
        val preset = Preset("Work", "main", mapOf("SubA" to "dev"))
        val row = manager.buildSubRow("SubA", "dev")
        body.add(row.panel)
        manager.onFirstExpand()
        manager.loadAllBranches(preset)
        assertTrue("row discovery should start", started.await(5, TimeUnit.SECONDS))

        manager.applyPresetToUI(preset.copy(submodules = emptyMap()))

        assertTrue("removed row load should finish", finished.await(5, TimeUnit.SECONDS))
        assertTrue("removed row Git operation should be cancelled", cancelled.get())
        assertTrue("removed row should leave the manager", "SubA" !in manager.subRows)
        assertEquals(0, manager.loadingCount)
    }

    private fun descendants(root: Component): List<Component> =
        if (root is Container) {
            listOf(root) + root.components.flatMap(::descendants)
        } else {
            listOf(root)
        }

    private fun emptyGit(): PresetDiscoveryGitClient =
        Proxy.newProxyInstance(
            PresetDiscoveryGitClient::class.java.classLoader,
            arrayOf(PresetDiscoveryGitClient::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                List::class.java -> emptyList<String>()
                else -> null
            }
        } as PresetDiscoveryGitClient

    private fun emptyBranchLoads(): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            gitOperation()
        }

    private fun failingCurrentBranchGit(): PresetDiscoveryGitClient =
        Proxy.newProxyInstance(
            PresetDiscoveryGitClient::class.java.classLoader,
            arrayOf(PresetDiscoveryGitClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "currentBranch" -> error("cannot inspect branch")
                "listAllBranches", "listSubmodulePaths" -> emptyList<String>()
                else -> null
            }
        } as PresetDiscoveryGitClient

    private fun failingCurrentBranchOperation(): GitOperationSession =
        gitOperation { methodName ->
            when (methodName) {
                "currentBranch" -> error("cannot inspect branch")
                "listAllBranches", "listSubmodulePaths" -> emptyList<String>()
                else -> null
            }
        }

    private fun gitOperation(
        onCancel: () -> Unit = {},
        response: (String) -> Any? = { null },
    ): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> onCancel()
                "close" -> Unit
                else -> response(method.name) ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession
}
