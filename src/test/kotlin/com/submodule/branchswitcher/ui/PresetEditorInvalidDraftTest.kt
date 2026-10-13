package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * none of the three may be clickable, and the reason must be carried, because the derive failure
 * otherwise reaches only the tool-window log. Revert is the exception: it restores the saved
 * preset, so it stays available as the way out.
 *
 * Assertions read [PresetEditor.controlState] — the editor's own decision — rather than the
 * buttons that render it. What is pinned here is what the editor decided, not how Swing drew it.
 */
class PresetEditorInvalidDraftTest {

    @Test
    fun `an invalid submodule branch disables switch and derive and explains why`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        // Adding a submodule row without picking a branch leaves it on an empty branch name,
        // which is exactly what `+ Add submodule` produces for a fresh row.
        val combo = submoduleComboFor(editor, "SubA")
        combo.selectedItem = ""

        val state = editor.controlState()
        assertFalse("switch must be refused on an invalid draft", state.switchEnabled)
        assertFalse("derive must be refused on an invalid draft", state.deriveEnabled)
        val reason = requireNotNull(state.draftError) { "the reason must be carried, not just the refusal" }
        assertTrue("the reason must name the offending field, got: $reason", reason.contains("SubA"))
    }

    /**
     * Saving an invalid draft only ever ends in the "invalid branch for ..." error dialog, so the
     * action must be refused up front — while Revert stays available as the way out of a draft
     * that cannot be repaired by hand.
     */
    @Test
    fun `an invalid draft disables save but keeps revert as the way out`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        submoduleComboFor(editor, "SubA").selectedItem = ""

        val state = editor.controlState()
        assertFalse("save would only raise the invalid-branch error", state.saveEnabled)
        assertTrue("revert must stay enabled as the way out", state.revertEnabled)
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
        val combo = submoduleComboFor(editor, "SubA")
        assertEquals("precondition: the gate starts on the saved branch", "dev", combo.selectedItem)

        editorField(combo).text = ""

        assertEquals(
            "the combo itself is unchanged until the edit is committed",
            "dev",
            combo.selectedItem,
        )
        val state = editor.controlState()
        assertFalse("switch must be refused on the now-empty branch", state.switchEnabled)
        assertFalse("derive must be refused on the now-empty branch", state.deriveEnabled)
        assertFalse("save must be refused on the now-empty branch", state.saveEnabled)
        assertTrue("the reason must be carried", state.draftError != null)
    }

    @Test
    fun `an invalid main branch disables switch and derive`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        mainCombo(editor).selectedItem = "feature..broken"

        val state = editor.controlState()
        assertFalse(state.switchEnabled)
        assertFalse(state.deriveEnabled)
        assertTrue(state.draftError != null)
    }

    @Test
    fun `repairing the draft re-enables switch and derive and clears the message`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        submoduleComboFor(editor, "SubA").selectedItem = ""

        submoduleComboFor(editor, "SubA").selectedItem = "develop"

        val state = editor.controlState()
        assertTrue("a valid draft must restore switch", state.switchEnabled)
        assertTrue("a valid draft must restore derive", state.deriveEnabled)
        assertNull("the failure must clear with the repair", state.draftError)
    }

    @Test
    fun `the refusal reason tracks the draft rather than going stale`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        val clean = editor.controlState().draftError

        mainCombo(editor).selectedItem = "-force"
        val blocked = editor.controlState().draftError

        assertTrue("an invalid draft must supply a reason", blocked != null)
        assertTrue("the reason must be the failure, not a carried-over one", blocked != clean)
        mainCombo(editor).selectedItem = "main"
        assertNull("repairing the draft drops the reason", editor.controlState().draftError)
    }

    /**
     * The other tests here read [PresetEditor.controlState] — the decision. This one reads the
     * buttons and the label, because the decision is worthless to the user until it is rendered
     * onto them. Without this, deleting the whole of `renderControlState`'s body would leave every
     * other test green while the user saw four operable-looking buttons and no reason text.
     */
    @Test
    fun `the decision reaches the controls the user actually sees`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        val state = editor.controlState()
        assertTrue("precondition: a valid draft enables switch", state.switchEnabled)

        submoduleComboFor(editor, "SubA").selectedItem = ""

        val blocked = editor.controlState()
        assertFalse("precondition: the draft is now refused", blocked.switchEnabled)
        assertEquals("switch must be drawn from the decision", blocked.switchEnabled, switchButton(editor).isEnabled)
        assertEquals("derive must be drawn from the decision", blocked.deriveEnabled, deriveButton(editor).isEnabled)
        assertEquals("save must be drawn from the decision", blocked.saveEnabled, saveButton(editor).isEnabled)
        assertEquals("discard must be drawn from the decision", blocked.revertEnabled, revertButton(editor).isEnabled)
        assertTrue("the reason must be visible, not merely carried", draftErrorLabel(editor).isVisible)
        assertTrue(
            "the visible reason must be the one the decision carried, got: ${draftErrorLabel(editor).text}",
            !draftErrorLabel(editor).text.isNullOrEmpty() && draftErrorLabel(editor).text.contains("SubA"),
        )
        assertTrue("switch must explain itself on hover", !switchButton(editor).toolTipText.isNullOrEmpty())
    }

    @Test
    fun `repairing the draft clears the rendered reason and restores the buttons`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))
        submoduleComboFor(editor, "SubA").selectedItem = ""

        submoduleComboFor(editor, "SubA").selectedItem = "develop"

        val state = editor.controlState()
        assertTrue("precondition: a valid draft enables switch", state.switchEnabled)
        assertTrue("switch must be drawn enabled again", switchButton(editor).isEnabled)
        assertTrue("derive must be drawn enabled again", deriveButton(editor).isEnabled)
        assertFalse("the reason must be hidden again", draftErrorLabel(editor).isVisible)
        assertTrue(
            "derive must get its own tooltip back once nothing blocks it, got: ${deriveButton(editor).toolTipText}",
            deriveButton(editor).toolTipText?.contains("branch") == true,
        )
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

    private fun submoduleComboFor(editor: PresetEditor, path: String): JComboBox<String> =
        requireNotNull(branchCombo(editor, path)) { "no row for $path" }

    private fun mainCombo(editor: PresetEditor): JComboBox<String> = branchCombo(editor, null)!!

    @Suppress("UNCHECKED_CAST")
    private fun branchCombo(editor: PresetEditor, path: String?): JComboBox<String>? =
        editor.control(PresetEditorControl.BRANCHES, path) as JComboBox<String>?

    private fun switchButton(editor: PresetEditor): JButton =
        editor.control(PresetEditorControl.SWITCH) as JButton

    private fun deriveButton(editor: PresetEditor): JButton =
        editor.control(PresetEditorControl.DERIVE) as JButton

    private fun saveButton(editor: PresetEditor): JButton =
        editor.control(PresetEditorControl.SAVE) as JButton

    private fun revertButton(editor: PresetEditor): JButton =
        editor.control(PresetEditorControl.DISCARD) as JButton

    private fun draftErrorLabel(editor: PresetEditor): JLabel =
        editor.control(PresetEditorControl.DRAFT_ERROR) as JLabel

    private fun editorField(combo: JComboBox<String>): JTextField =
        combo.editor.editorComponent as JTextField

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
