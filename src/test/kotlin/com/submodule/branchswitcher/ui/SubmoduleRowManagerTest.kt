package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JLabel
import javax.swing.JPanel

class SubmoduleRowManagerTest {

    @Test
    fun `submodule row context menu listener is installed on child label panel`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            gitClient = ::emptyGit,
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)),
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
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)),
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
        val manager = SubmoduleRowManager(
            gitRoot = root,
            gitClient = { failingCurrentBranchGit() },
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)),
            body = body,
            log = createStringAppender {},
            onDirty = {},
            scheduleUi = { it() },
        )
        manager.onFirstExpand()

        manager.addSubmoduleFromMenu("SubA")

        requireNotNull(manager.subRows["SubA"])
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
}
