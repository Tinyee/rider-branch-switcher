package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField

/**
 * Pins the invalid-draft gate: a preset whose draft cannot be validated must refuse every action
 * that would fail on it. Switch fails in preflight, derive throws on [Preset.targets] before its
 * progress task starts, and save throws inside `PresetLoader.save`'s `requireValidPreset` — so
 * none of the three may be clickable, and each must say why, because the derive failure otherwise
 * reaches only the tool-window log. Revert is the exception: it restores the saved preset, so it
 * stays available as the way out.
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

    /**
     * Saving an invalid draft only ever ends in the "invalid branch for ..." error dialog, so the
     * button must be refused up front — while Revert stays available as the way out of a draft
     * that cannot be repaired by hand.
     */
    @Test
    fun `an invalid draft disables save but keeps revert as the way out`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        submoduleRowFor(editor, "SubA").combo.selectedItem = ""

        assertFalse("save would only raise the invalid-branch error", saveButton(editor).isEnabled)
        assertTrue("revert must stay enabled as the way out", revertButton(editor).isEnabled)
    }

    /**
     * The user clears the branch and clicks Save/collapses without pressing Enter. A JComboBox
     * does not commit the field to `selectedItem` until Enter or focus loss, so a gate that read
     * only `selectedItem` saw the stale branch and enabled Save on a draft that was already
     * empty — the click then failed with "invalid branch for '<path>': ''".
     */
    @Test
    fun `clearing the field without pressing enter is seen by the gate`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        val combo = submoduleRowFor(editor, "SubA").combo
        assertEquals("precondition: the gate starts on the saved branch", "dev", combo.selectedItem)

        editorField(combo).text = ""

        assertEquals(
            "the combo itself is unchanged until the edit is committed",
            "dev",
            combo.selectedItem,
        )
        assertFalse("switch must be refused on the now-empty branch", switchButton(editor).isEnabled)
        assertFalse("derive must be refused on the now-empty branch", deriveButton(editor).isEnabled)
        assertFalse("save must be refused on the now-empty branch", saveButton(editor).isEnabled)
        assertTrue("the reason must be shown", draftErrorLabel(editor).isVisible)
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

    private fun saveButton(editor: PresetEditor): JButton = field(editor, "saveBtn") as JButton

    private fun revertButton(editor: PresetEditor): JButton = field(editor, "revertBtn") as JButton

    private fun editorField(combo: JComboBox<String>): JTextField =
        combo.editor.editorComponent as JTextField

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
