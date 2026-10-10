package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel

/**
 * Pins the invalid-draft gate: a preset whose draft cannot be validated must refuse the two
 * actions that would fail on it. Switch fails in preflight and derive throws on
 * [Preset.targets] before its progress task starts, so neither may be clickable — and both
 * must say why, because the derive failure otherwise reaches only the tool-window log.
 */
class PresetEditorInvalidDraftTest {

    @Test
    fun `an invalid submodule branch disables switch and derive and explains why`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        // Adding a submodule row without picking a branch leaves it on an empty branch name,
        // which is exactly what `+ Add submodule` produces for a fresh row.
        val row = submoduleRowFor(editor, "SubA")
        row.combo.selectedItem = ""

        assertFalse("switch must be refused on an invalid draft", switchButton(editor).isEnabled)
        assertFalse("derive must be refused on an invalid draft", deriveButton(editor).isEnabled)
        assertTrue("the reason must be shown, not just the disabled state", draftErrorLabel(editor).isVisible)
        assertTrue(
            "the reason must name the offending field, got: ${draftErrorLabel(editor).text}",
            draftErrorLabel(editor).text.contains("SubA"),
        )
        assertTrue("switch must explain itself on hover", switchButton(editor).toolTipText.isNullOrEmpty().not())
    }

    @Test
    fun `an invalid main branch disables switch and derive`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        mainCombo(editor).selectedItem = "feature..broken"

        assertFalse(switchButton(editor).isEnabled)
        assertFalse(deriveButton(editor).isEnabled)
        assertTrue(draftErrorLabel(editor).isVisible)
    }

    @Test
    fun `repairing the draft re-enables switch and derive and clears the message`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        submoduleRowFor(editor, "SubA").combo.selectedItem = ""

        submoduleRowFor(editor, "SubA").combo.selectedItem = "develop"

        assertTrue("a valid draft must restore switch", switchButton(editor).isEnabled)
        assertTrue("a valid draft must restore derive", deriveButton(editor).isEnabled)
        assertFalse("the message must clear with the failure", draftErrorLabel(editor).isVisible)
        assertTrue(
            "derive must get its own tooltip back, got: ${deriveButton(editor).toolTipText}",
            deriveButton(editor).toolTipText?.contains("branch") == true,
        )
    }

    @Test
    fun `a useful tooltip on switch is the reason, not a stale one`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        val clean = switchButton(editor).toolTipText

        mainCombo(editor).selectedItem = "-force"
        val blocked = switchButton(editor).toolTipText

        assertTrue("pressing switch is blocked by the invalid draft", blocked != clean)
        mainCombo(editor).selectedItem = "main"
        assertFalse("clearing the draft error drops the reason tooltip", switchButton(editor).toolTipText == blocked)
    }

    private fun newEditor(preset: Preset): PresetEditor = PresetEditor(
        gitRoot = Paths.get("."),
        initialPreset = preset,
        log = createStringAppender {},
        onSwitch = {},
        onSave = { _, _ -> },
        onDelete = {},
        onDerive = { _, _ -> },
        nameValidator = { true },
        branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) { gitOperation() },
        onSwitchOnly = { _, _ -> },
    )

    private fun submoduleRowFor(editor: PresetEditor, path: String): SubmoduleRowManager.SubRow {
        val manager = field(editor, "submoduleManager") as SubmoduleRowManager
        return requireNotNull(manager.subRows[path]) { "no row for $path" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mainCombo(editor: PresetEditor): JComboBox<String> =
        field(editor, "mainCombo") as JComboBox<String>

    private fun switchButton(editor: PresetEditor): JButton = field(editor, "switchBtn") as JButton

    private fun deriveButton(editor: PresetEditor): JButton = field(editor, "deriveBtn") as JButton

    private fun draftErrorLabel(editor: PresetEditor): JLabel = field(editor, "draftErrorLabel") as JLabel

    private fun field(editor: PresetEditor, name: String): Any {
        val field = PresetEditor::class.java.getDeclaredField(name)
        field.isAccessible = true
        return requireNotNull(field.get(editor))
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
