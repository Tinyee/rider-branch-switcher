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
import javax.swing.JComboBox
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
        requireNotNull(editor.submoduleBranchCombo(path)) { "no row for $path" }

    private fun mainCombo(editor: PresetEditor): JComboBox<String> = editor.mainBranchCombo()

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
