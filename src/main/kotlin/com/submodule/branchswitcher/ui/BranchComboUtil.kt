package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
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
private const val KEY_BRANCH_LOAD = "submodule.branchswitcher.branchLoad"
private const val KEY_BRANCH_LOAD_TOKEN = "submodule.branchswitcher.branchLoadToken"
val LOADING_BRANCH: String = Bundle.msg("status.loading")

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
    combo.addItemListener {
        val branch = combo.selectedItem?.toString()
        combo.toolTipText = branch
        onDirty()
    }
    val editor = combo.editor.editorComponent as? JTextField
    editor?.document?.addDocumentListener(
        object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = editorChanged()
            override fun removeUpdate(e: DocumentEvent) = editorChanged()
            override fun changedUpdate(e: DocumentEvent) = editorChanged()

            private fun editorChanged() {
                editor.toolTipText = editor.text
                combo.toolTipText = editor.text
                onDirty()
            }
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
 * Sets the combo to its loading state, runs discovery through [branchLoads], then
 * restores the full list and current selection on the UI thread.
 */
@Suppress("TooGenericExceptionCaught") // Git, coroutine, and UI scheduler failures converge at this async boundary
internal fun loadComboBranches(
    combo: JComboBox<String>,
    dir: File,
    current: String,
    branchLoads: BranchLoadCoordinator,
    log: AppLogger,
    onLoadStart: () -> Unit,
    onLoadEnd: (succeeded: Boolean, superseded: Boolean) -> Unit,
    discoverCurrent: Boolean = false,
    loadChoices: Boolean = true,
    scheduleUi: ((() -> Unit) -> Unit) = { action ->
        ApplicationManager.getApplication().invokeLater(action)
    },
): BranchLoadHandle {
    val previousLoad = combo.getClientProperty(KEY_BRANCH_LOAD) as? BranchLoadHandle
    val loadToken = Any()
    combo.putClientProperty(KEY_BRANCH_LOAD_TOKEN, loadToken)
    previousLoad?.cancel()
    onLoadStart()
    combo.model = DefaultComboBoxModel(arrayOf(LOADING_BRANCH))
    combo.selectedItem = LOADING_BRANCH
    combo.isEnabled = false
    val completionScheduled = AtomicBoolean(false)
    val loadEnded = AtomicBoolean(false)

    fun endLoad(succeeded: Boolean) {
        if (loadEnded.compareAndSet(false, true)) {
            // Always signal the lifecycle end (balances onLoadStart for the caller's
            // in-flight counter). The caller separately decides whether a superseded
            // load (a newer load replaced this combo's token) may touch retry state.
            val superseded = combo.getClientProperty(KEY_BRANCH_LOAD_TOKEN) !== loadToken
            onLoadEnd(succeeded, superseded)
        }
    }

    fun finish(loadResult: BranchComboLoadResult?) {
        if (!completionScheduled.compareAndSet(false, true)) return
        val updateUi: () -> Unit = updateUi@{
            try {
                if (combo.getClientProperty(KEY_BRANCH_LOAD_TOKEN) !== loadToken) return@updateUi
                if (!combo.isDisplayable) return@updateUi
                val result = loadResult ?: BranchComboLoadResult(current, emptyList())
                val list = mergeBranchChoices(result.selectedBranch, result.branches)
                combo.model = DefaultComboBoxModel(list.toTypedArray())
                combo.selectedItem = result.selectedBranch
                combo.putClientProperty(KEY_ALL_BRANCHES, list)
                combo.isEnabled = true
            } finally {
                endLoad(loadResult?.succeeded ?: false)
            }
        }
        try {
            scheduleUi(updateUi)
        } catch (e: Exception) {
            endLoad(false)
            log.logFailure("loadBranches UI update failed for ${dir.name}", e)
        }
    }

    val handle = branchLoads.launch { client ->
        val loadResult = try {
            discoverBranchChoices(client, dir, current, discoverCurrent, loadChoices, log)
        } catch (e: CancellationException) {
            // A cancelled discovery leaves no UI to apply; end the lifecycle without
            // touching the combo. A superseded token still balances the load counter.
            finish(null)
            throw e
        }
        finish(loadResult)
    }
    handle.invokeOnCompletion { failure ->
        if (failure != null && failure !is CancellationException) {
            log.logFailure("loadBranches failed for ${dir.name}", failure)
        }
        finish(null)
    }
    combo.putClientProperty(KEY_BRANCH_LOAD, handle)
    return handle
}

/**
 * Reads the current branch and branch list for one combo in the background.
 * A [CancellationException] (coroutine cancel or a CANCELLED Git query) propagates so
 * the caller ends the lifecycle without applying any UI; any other query failure
 * degrades to a non-succeeded result the caller may use to reset retry state.
 */
@Suppress("TooGenericExceptionCaught") // Git query adapters vary; cancellation propagates via the explicit catch
private suspend fun discoverBranchChoices(
    client: PresetDiscoveryGitClient,
    dir: File,
    current: String,
    discoverCurrent: Boolean,
    loadChoices: Boolean,
    log: AppLogger,
): BranchComboLoadResult {
    return try {
        val selectedBranch = if (discoverCurrent && dir.exists()) {
            client.currentBranch(dir).orEmpty()
        } else {
            current
        }
        currentCoroutineContext().ensureActive()
        val branches = if (loadChoices && dir.exists()) {
            client.listAllBranches(dir)
        } else {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        BranchComboLoadResult(selectedBranch, branches)
    } catch (e: CancellationException) {
        throw e
    } catch (e: GitQueryException) {
        if (e.result.failureKind == GitFailureKind.CANCELLED) {
            throw CancellationException("branch discovery cancelled").apply { initCause(e) }
        }
        log.warn("loadBranches failed for ${dir.name}", e)
        BranchComboLoadResult(current, emptyList(), succeeded = false)
    } catch (e: Exception) {
        log.logFailure("loadBranches failed for ${dir.name}", e)
        BranchComboLoadResult(current, emptyList(), succeeded = false)
    }
}

/** Cancels the active branch discovery associated with [combo], if any. */
internal fun cancelComboBranchLoad(combo: JComboBox<String>): Boolean {
    val handle = combo.getClientProperty(KEY_BRANCH_LOAD) as? BranchLoadHandle ?: return false
    if (!handle.isActive) return false
    handle.cancel()
    return true
}

private data class BranchComboLoadResult(
    val selectedBranch: String,
    val branches: List<String>,
    /** False when discovery failed or was superseded; callers may reset retry state. */
    val succeeded: Boolean = true,
)
