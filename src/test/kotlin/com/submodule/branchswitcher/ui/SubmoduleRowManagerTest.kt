package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitClient
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
import java.nio.file.Paths
import javax.swing.JLabel
import javax.swing.JPanel

class SubmoduleRowManagerTest {

    @Test
    fun `submodule row context menu listener is installed on child label panel`() {
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            gitClient = emptyGit(),
            scope = CoroutineScope(Dispatchers.Unconfined),
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
    fun `single submodule switch delegates target to guarded owner`() {
        var requested: Pair<String, String>? = null
        val manager = SubmoduleRowManager(
            gitRoot = Paths.get("."),
            gitClient = emptyGit(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            body = JPanel(),
            log = createStringAppender {},
            onDirty = {},
            onSwitchOnly = { path, target -> requested = path to target },
        )
        manager.buildSubRow("SubA", "dev")

        manager.requestSwitchOnly("SubA")

        assertEquals("SubA" to "dev", requested)
    }

    private fun descendants(root: Component): List<Component> =
        if (root is Container) {
            listOf(root) + root.components.flatMap(::descendants)
        } else {
            listOf(root)
        }

    private fun emptyGit(): GitClient =
        Proxy.newProxyInstance(
            GitClient::class.java.classLoader,
            arrayOf(GitClient::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                List::class.java -> emptyList<String>()
                else -> null
            }
        } as GitClient
}
