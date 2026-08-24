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
import javax.swing.JButton

/**
 * Pins the cold-start button state: a freshly constructed editor must expose clickable
 * switch/derive buttons. Regression for the construction-order bug where the button state
 * was recomposed while [PresetEditor.isInitializing] was still true and never recomposed
 * afterwards, leaving both actions disabled until the first expand or write.
 */
class PresetEditorActionsEnabledTest {

    @Test
    fun `switch and derive buttons are clickable right after construction`() {
        val editor = newEditor()

        assertTrue("switch button must be enabled on a cold-started editor", switchButton(editor).isEnabled)
        assertTrue("derive button must be enabled on a cold-started editor", deriveButton(editor).isEnabled)
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

    private fun switchButton(editor: PresetEditor): JButton = buttonField(editor, "switchBtn")

    private fun deriveButton(editor: PresetEditor): JButton = buttonField(editor, "deriveBtn")

    private fun buttonField(editor: PresetEditor, name: String): JButton {
        val field = PresetEditor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(editor) as JButton
    }

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
