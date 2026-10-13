package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.presetValidationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.swing.JComboBox

/**
 * Pins the collapse/re-expand draft rule. A collapse cancels every in-flight branch load; the
 * re-expand then re-runs discovery, which blanks each combo to the literal [LOADING_BRANCH]
 * placeholder. Both the reload seed and the button gate read the draft through the combo, so
 * that read must recover the branch the load displaced — otherwise the placeholder is captured
 * as a branch name and the unsaved edit is destroyed without a word.
 */
class PresetEditorDraftAcrossExpandTest {

    @Test
    fun `re-expanding keeps the main branch draft instead of capturing the placeholder`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        expand(editor)
        mainCombo(editor).selectedItem = "feature/draft"

        collapse(editor)
        expand(editor)

        assertEquals(
            "the unsaved main branch edit must survive the reload",
            "feature/draft",
            editor.buildEditedPreset().main,
        )
    }

    @Test
    fun `re-expanding keeps a submodule branch draft`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        expand(editor)
        submoduleCombo(editor, "SubA").selectedItem = "develop"

        collapse(editor)
        expand(editor)

        assertEquals(
            "the unsaved submodule branch edit must survive the reload",
            "develop",
            editor.buildEditedPreset().submodules["SubA"],
        )
    }

    /**
     * The two rules interact: the placeholder must never become a draft, or an edit that was
     * valid before the collapse would come back invalid and disable switch/derive with no
     * visible cause.
     */
    @Test
    fun `re-expanding a valid draft does not turn it invalid`() {
        val editor = newEditor(Preset("Work", "main", mapOf("SubA" to "dev")))

        expand(editor)
        mainCombo(editor).selectedItem = "feature/draft"
        collapse(editor)
        expand(editor)

        val draft = editor.buildEditedPreset()
        assertNull(
            "the reload must not manufacture a validation failure for ${draft.main}",
            presetValidationError(draft),
        )
    }

    private fun newEditor(preset: Preset): PresetEditor = PresetEditor(
        gitRoot = Files.createTempDirectory("preset-editor-draft"),
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

    private fun expand(editor: PresetEditor) = toggle(editor, expectVisible = true)

    private fun collapse(editor: PresetEditor) = toggle(editor, expectVisible = false)

    private fun toggle(editor: PresetEditor, expectVisible: Boolean) {
        editor.toggle()
        assertEquals("body visibility after toggle", expectVisible, editor.isBodyVisible())
    }

    private fun mainCombo(editor: PresetEditor): JComboBox<String> = editor.mainBranchCombo()

    private fun submoduleCombo(editor: PresetEditor, path: String): JComboBox<String> =
        requireNotNull(editor.submoduleBranchCombo(path)) { "no submodule row for $path" }

    /**
     * A session that never resumes its suspension point, so every discovery stays in flight —
     * the state a collapse cancels and leaves the placeholder behind for.
     */
    private fun gitOperation(): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "cancel", "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession
}
