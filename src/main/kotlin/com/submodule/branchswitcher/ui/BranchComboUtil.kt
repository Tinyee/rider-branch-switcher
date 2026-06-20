package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.submodule.branchswitcher.git.GitQueryClient
import com.submodule.branchswitcher.log.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** JComboBox client-property key storing the unfiltered branch list for popup filtering. */
const val KEY_ALL_BRANCHES = "submodule.branchswitcher.allBranches"
const val LOADING_BRANCH = "loading..."

/** Normalizes loaded branches and ensures the current branch remains selectable. */
fun mergeBranchChoices(current: String, branches: List<String>): List<String> {
    val normalized = branches
        .filter { it.isNotBlank() && it != LOADING_BRANCH }
        .distinct()
    return if (current.isNotBlank() && current != LOADING_BRANCH && current !in normalized) {
        listOf(current) + normalized
    } else {
        normalized
    }
}

/**
 * Creates an editable branch-name combo with real-time filtering.
 * The full branch list is stored as a client property ([KEY_ALL_BRANCHES]);
 * typing filters the popup case-insensitively while preserving caret position.
 */
fun makeBranchCombo(onDirty: () -> Unit): JComboBox<String> {
    val combo = JComboBox<String>()
    combo.isEditable = true
    combo.prototypeDisplayValue = "x".repeat(28)
    combo.addItemListener { onDirty() }
    val editor = combo.editor.editorComponent as? JTextField
    editor?.document?.addDocumentListener(
        object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onDirty()
            override fun removeUpdate(e: DocumentEvent) = onDirty()
            override fun changedUpdate(e: DocumentEvent) = onDirty()
        }
    )
    editor?.addKeyListener(object : KeyAdapter() {
        override fun keyReleased(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER,
                KeyEvent.VK_ESCAPE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
                KeyEvent.VK_TAB, KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL,
                KeyEvent.VK_ALT, KeyEvent.VK_META -> return
            }
            filterBranchPopup(combo, editor)
        }
    })
    return combo
}

/**
 * Filters the combo popup to branches containing [editor.text] (case-insensitive).
 * Skips rebuild if the filtered list is identical to the current model.
 * Restores caret position after model swap.
 */
fun filterBranchPopup(combo: JComboBox<String>, editor: JTextField) {
    @Suppress("UNCHECKED_CAST")
    val all = combo.getClientProperty(KEY_ALL_BRANCHES) as? List<String> ?: return
    val text = editor.text ?: ""
    val caret = editor.caretPosition
    val filtered = if (text.isBlank()) all
                   else all.filter { it.contains(text, ignoreCase = true) }
    if (filtered.isEmpty()) {
        combo.isPopupVisible = false
        return
    }
    val same = combo.itemCount == filtered.size &&
        (0 until combo.itemCount).all { combo.getItemAt(it) == filtered[it] }
    if (!same) {
        val model = DefaultComboBoxModel(filtered.toTypedArray())
        model.selectedItem = text
        combo.model = model
        editor.text = text
        editor.caretPosition = minOf(caret, text.length)
    }
    if (combo.isShowing && editor.isFocusOwner) {
        combo.isPopupVisible = true
    }
}

/**
 * Shared async branch loader used by both [PresetEditor] and [SubmoduleRowManager].
 * Sets combo to "loading..." state, launches a [scope] coroutine to fetch branches,
 * then restores the combo on EDT with the full list + current selection.
 */
fun loadComboBranches(
    combo: JComboBox<String>,
    dir: File,
    current: String,
    gitClient: GitQueryClient,
    scope: CoroutineScope,
    log: AppLogger,
    onLoadStart: () -> Unit,
    onLoadEnd: () -> Unit,
    scheduleUi: ((() -> Unit) -> Unit) = { action ->
        ApplicationManager.getApplication().invokeLater(action)
    },
) {
    onLoadStart()
    combo.model = DefaultComboBoxModel(arrayOf(LOADING_BRANCH))
    combo.selectedItem = LOADING_BRANCH
    combo.isEnabled = false
    scope.launch {
        val branches = try {
            if (dir.exists()) gitClient.listAllBranches(dir) else emptyList()
        } catch (e: Exception) {
            log.warn("loadBranches failed for ${dir.name}: ${e.message}")
            emptyList()
        }
        scheduleUi {
            try {
                if (!combo.isDisplayable) return@scheduleUi
                val list = mergeBranchChoices(current, branches)
                combo.model = DefaultComboBoxModel(list.toTypedArray())
                combo.selectedItem = current
                combo.putClientProperty(KEY_ALL_BRANCHES, list)
                combo.isEnabled = true
            } finally {
                onLoadEnd()
            }
        }
    }
}
