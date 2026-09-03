package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import javax.swing.DefaultComboBoxModel
import javax.swing.JLabel
import javax.swing.JPanel

class SubmoduleRowManagerTest {

    // Windows CI runners can be slow to schedule the shared IO dispatcher, so a
    // trivially-synchronous row load may take longer than a tight 5s latch. Keep a
    // generous window; a healthy load still completes in milliseconds.
    private val loadCompletionTimeoutSeconds = 30L

    @Test
    fun `submodule row context menu listener is installed on child label panel`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
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
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
        val body = JPanel().apply { add(JPanel()) }
        val finished = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
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

        assertTrue("row branch load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        requireNotNull(manager.subRows["SubA"])
        assertEquals(0, manager.loadingCount)
    }

    @Test
    fun `a failed submodule load is retried on the next loadAllBranches`() {
        // Simulates collapse + re-expand after a submodule-only failure: PresetEditor
        // resets branchesLoaded on collapse when the manager reports unloaded rows, so
        // the next expand retries the failed row even though the main repo loaded.
        val root = Files.createTempDirectory("submodule-row-retry")
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
        val body = JPanel().apply { add(JPanel()) }
        var listCalls = 0
        var uiCount = 0
        val firstLoadDone = CountDownLatch(1)
        val retryDone = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
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
        assertTrue("first load should finish", firstLoadDone.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        val row = requireNotNull(manager.subRows["SubA"])
        assertFalse("failed load must reset row.loaded so a re-expand retries", row.loaded)
        assertTrue("failed row must leave the manager with unloaded rows", manager.hasUnloadedRows())
        assertEquals(0, manager.loadingCount)

        manager.loadAllBranches(Preset("Work", "main", mapOf("SubA" to "dev")))
        assertTrue("retry load should finish", retryDone.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertFalse("a successful retry clears the unloaded rows", manager.hasUnloadedRows())
        assertEquals("failed load must be retried on the next loadAllBranches", 2, listCalls)
    }

    @Test
    fun `removing a loading submodule row cancels its git operation`() {
        val root = Files.createTempDirectory("submodule-row-cancel")
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
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
        assertTrue("row discovery should start", started.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))

        manager.applyPresetToUI(preset.copy(submodules = emptyMap()))

        assertTrue("removed row load should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertTrue("removed row Git operation should be cancelled", cancelled.get())
        assertTrue("removed row should leave the manager", "SubA" !in manager.subRows)
        assertEquals(0, manager.loadingCount)
    }

    private fun awaitZeroLoading(manager: SubmoduleRowManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(loadCompletionTimeoutSeconds)
        while (manager.loadingCount > 0 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(0, manager.loadingCount)
    }

    @Test
    fun `refreshSubmoduleRow fetches and relists that row, keeping selection`() {
        val root = Files.createTempDirectory("row-refresh")
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
        val body = JPanel().apply { add(JPanel()) }
        var fetchCalls = 0
        val done = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "fetch" -> { fetchCalls++; GitResult("fetch", 0, "", "") }
                        "listAllBranches" -> listOf("new-1")
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it(); done.countDown() },
        )
        val row = manager.buildSubRow("SubA", "dev")
        body.add(row.panel)
        body.addNotify()
        row.loaded = true
        manager.onFirstExpand()

        manager.refreshSubmoduleRow("SubA")

        assertTrue("row refresh should finish", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        awaitZeroLoading(manager)
        assertEquals(1, fetchCalls)
        @Suppress("UNCHECKED_CAST")
        val all = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue("refreshed list must include the remote branch", all.contains("new-1"))
        assertEquals("dev", requireNotNull(manager.subRows["SubA"]).combo.selectedItem)
        assertTrue("refreshed row stays loaded", requireNotNull(manager.subRows["SubA"]).loaded)
    }

    @Test
    fun `refreshSubmoduleRow on an uninitialized submodule lists via ls-remote`() {
        val root = Files.createTempDirectory("row-refresh-uninit")
        val body = JPanel().apply { add(JPanel()) }
        var fetchCalls = 0
        var lsCalls = 0
        val done = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "fetch" -> { fetchCalls++; GitResult("fetch", 0, "", "") }
                        "listRemoteHeads" -> { lsCalls++; listOf("develop", "release") }
                        "listAllBranches" -> emptyList<String>()
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it(); done.countDown() },
            cache = RemoteBranchCache(),
        )
        manager.setPathUrls(mapOf("SubA" to "https://example.com/repo.git"))
        val row = manager.buildSubRow("SubA", "")
        body.add(row.panel); body.addNotify()
        row.loaded = true
        manager.onFirstExpand()

        manager.refreshSubmoduleRow("SubA")

        assertTrue("refresh should finish", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertEquals("uninitialized refresh must not fetch", 0, fetchCalls)
        assertTrue("uninitialized refresh must ls-remote", lsCalls >= 1)
        @Suppress("UNCHECKED_CAST")
        val all = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue(all.containsAll(listOf("develop", "release")))
    }

    @Test
    fun `refreshSubmoduleRow on a checked-out submodule fetches and lists remote heads`() {
        val root = Files.createTempDirectory("row-refresh-checked")
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
        val body = JPanel().apply { add(JPanel()) }
        var fetchCalls = 0
        var lsCalls = 0
        val done = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "fetch" -> { fetchCalls++; GitResult("fetch", 0, "", "") }
                        "listRemoteHeads" -> { lsCalls++; listOf("develop", "release") }
                        "listAllBranches" -> listOf("main", "feature/x")
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it(); done.countDown() },
            cache = RemoteBranchCache(),
        )
        manager.setPathUrls(mapOf("SubA" to "https://example.com/repo.git"))
        val row = manager.buildSubRow("SubA", "main")
        body.add(row.panel)
        body.addNotify()
        row.loaded = true
        manager.onFirstExpand()

        manager.refreshSubmoduleRow("SubA")

        assertTrue("refresh should finish", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        awaitZeroLoading(manager)
        assertEquals("checked-out refresh must fetch", 1, fetchCalls)
        assertTrue("checked-out refresh must ls-remote", lsCalls >= 1)
        @Suppress("UNCHECKED_CAST")
        val all = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue(
            "combo must union local and remote branches",
            all.containsAll(listOf("main", "feature/x", "develop", "release")),
        )
        assertEquals("main", requireNotNull(manager.subRows["SubA"]).combo.selectedItem)
        assertTrue("refreshed row stays loaded", requireNotNull(manager.subRows["SubA"]).loaded)
    }

    @Test
    fun `refreshSubmoduleRow keeps the previous list when fetch fails on a checked-out row`() {
        val root = Files.createTempDirectory("row-refresh-fetchfail")
        Files.createDirectories(root.resolve("SubA").resolve(".git"))
        val body = JPanel().apply { add(JPanel()) }
        var fetchCalls = 0
        val done = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "fetch" -> { fetchCalls++; GitResult("fetch", 1, "", "offline") }
                        "listAllBranches" -> listOf("old-a", "old-b")
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it(); done.countDown() },
            cache = RemoteBranchCache(),
        )
        manager.setPathUrls(mapOf("SubA" to "https://example.com/repo.git"))
        val row = manager.buildSubRow("SubA", "old-a")
        body.add(row.panel)
        body.addNotify()
        row.loaded = true
        row.combo.putClientProperty(KEY_ALL_BRANCHES, listOf("old-a", "old-b"))
        manager.onFirstExpand()

        manager.refreshSubmoduleRow("SubA")

        assertTrue("refresh should finish", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        awaitZeroLoading(manager)
        assertEquals("fetch was attempted", 1, fetchCalls)
        @Suppress("UNCHECKED_CAST")
        val all = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertEquals("a failed fetch must keep the previous list", listOf("old-a", "old-b"), all)
        assertEquals("old-a", requireNotNull(manager.subRows["SubA"]).combo.selectedItem)
    }

    @Test
    fun `uninitialized submodule row lists remote heads via ls-remote`() {
        val root = Files.createTempDirectory("row-lsremote")
        val body = JPanel().apply { add(JPanel()) }
        val cache = RemoteBranchCache()
        val done = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "listRemoteHeads" -> listOf("develop", "jade/develop")
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it(); done.countDown() },
            cache = cache,
        )
        manager.setPathUrls(mapOf("SubA" to "https://example.com/repo.git"))
        Files.createDirectories(root.resolve("SubA")) // empty dir, no .git
        val row = manager.buildSubRow("SubA", "")
        body.add(row.panel)
        body.addNotify()
        manager.onFirstExpand()

        manager.loadAllBranches(Preset("Work", "main", mapOf("SubA" to "develop")))

        assertTrue("discovery should finish", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        val all = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue("remote heads must appear for an uninitialized row", all.containsAll(listOf("develop", "jade/develop")))
    }

    @Test
    fun `setPathUrls self-heals a row loaded before url resolution`() {
        val root = Files.createTempDirectory("row-self-heal")
        val body = JPanel().apply { add(JPanel()) }
        val cache = RemoteBranchCache()
        var loads = 0
        val firstLoad = CountDownLatch(1)
        val healLoad = CountDownLatch(1)
        val manager = SubmoduleRowManager(
            gitRoot = root,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                gitOperation { methodName ->
                    when (methodName) {
                        "listRemoteHeads" -> listOf("develop", "jade/develop")
                        else -> null
                    }
                }
            },
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = {
                it()
                loads++
                if (loads == 1) firstLoad.countDown() else healLoad.countDown()
            },
            cache = cache,
        )
        Files.createDirectories(root.resolve("SubA")) // empty dir, no .git
        val row = manager.buildSubRow("SubA", "")
        body.add(row.panel)
        body.addNotify()
        manager.onFirstExpand()

        // No URL yet: the uninitialized row lists only the preset branch (local-only).
        manager.loadAllBranches(Preset("Work", "main", mapOf("SubA" to "develop")))
        assertTrue("initial load should finish", firstLoad.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        val before = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertFalse("no remote heads before url resolution", before.contains("jade/develop"))

        // URL arrives late: the already-loaded row re-discovers and gains the remote heads.
        manager.setPathUrls(mapOf("SubA" to "https://example.com/repo.git"))
        assertTrue("self-heal reload should finish", healLoad.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        val after = requireNotNull(manager.subRows["SubA"]).combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue("remote heads must appear after url self-heal", after.containsAll(listOf("develop", "jade/develop")))
        assertEquals("selection must be preserved across the self-heal", "develop", requireNotNull(manager.subRows["SubA"]).combo.selectedItem)
    }

    @Test
    fun `fillRows preserves selection and skips unloaded rows`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            branchLoads = emptyBranchLoads(),
            body = JPanel(),
            log = createStringAppender {},
            onDirty = {},
        )
        val loaded = manager.buildSubRow("Loaded", "dev")
        val unloaded = manager.buildSubRow("Unloaded", "main")
        loaded.loaded = true

        val updated = manager.fillRows(
            mapOf(
                "Loaded" to listOf("main", "dev", "feature"),
                "Unloaded" to listOf("other"),
            ),
        )

        assertEquals("only the loaded row is updated", 1, updated)
        assertEquals("selection is preserved", "dev", loaded.combo.selectedItem)
        assertTrue("updated row is enabled", loaded.combo.isEnabled)
        @Suppress("UNCHECKED_CAST")
        val all = loaded.combo.getClientProperty(KEY_ALL_BRANCHES) as List<String>
        assertTrue(all.containsAll(listOf("main", "dev", "feature")))
        // The unloaded row is left completely untouched.
        assertEquals("main", unloaded.combo.selectedItem)
        assertNull(unloaded.combo.getClientProperty(KEY_ALL_BRANCHES))
    }

    @Test
    fun `fillRows leaves a mid-load row alone`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            branchLoads = emptyBranchLoads(),
            body = JPanel(),
            log = createStringAppender {},
            onDirty = {},
        )
        val row = manager.buildSubRow("SubA", "dev")
        row.loaded = true
        // Simulate a first-expand load still in flight: disabled combo on the placeholder.
        row.combo.model = DefaultComboBoxModel(arrayOf(LOADING_BRANCH))
        row.combo.selectedItem = LOADING_BRANCH
        row.combo.isEnabled = false

        val updated = manager.fillRows(mapOf("SubA" to listOf("dev", "main")))

        assertEquals("a mid-load row must not be counted as updated", 0, updated)
        assertFalse("a mid-load row must not be force-enabled", row.combo.isEnabled)
        assertEquals("a mid-load row keeps its placeholder for its own load to finish", LOADING_BRANCH, row.combo.selectedItem)
        assertEquals("a mid-load row keeps its loading model untouched", 1, row.combo.itemCount)
    }

    private fun descendants(root: Component): List<Component> =
        if (root is Container) {
            listOf(root) + root.components.flatMap(::descendants)
        } else {
            listOf(root)
        }

    private fun emptyBranchLoads(): BranchLoadCoordinator =
        BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
            gitOperation()
        }

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
