package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Paths

/**
 * Pins the cold-start button state: a freshly constructed editor must expose clickable
 * switch/derive actions. Regression for the construction-order bug where the action state
 * was recomposed while [PresetEditor.isInitializing] was still true and never recomposed
 * afterwards, leaving both actions unavailable until the first expand or write.
 */
class PresetEditorActionsEnabledTest {

    @Test
    fun `switch and derive are available right after construction`() {
        val editor = newEditor()

        val state = editor.controlState()
        assertTrue("switch must be available on a cold-started editor", state.switchEnabled)
        assertTrue("derive must be available on a cold-started editor", state.deriveEnabled)
    }

    private fun newEditor(): PresetEditor = PresetEditor(
        gitRoot = Paths.get("."),
        initialPreset = Preset("Work", "main", mapOf("SubA" to "dev")),
        log = createStringAppender {},
        onSwitch = {},
        onSave = { _, _ -> },
        onDelete = {},
        onDerive = { _, _ -> },
        nameValidator = { true },
        branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) { gitOperation() },
        onSwitchOnly = { _, _ -> },
    )

    private fun gitOperation(): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> Unit
                "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession
}
