package com.submodule.branchswitcher.ui
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.switch.OperationCancelledException
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

/**
 * A submodule row's discovery inputs: the superproject root ([gitRoot], where
 * `git ls-remote` runs), the submodule [path], and its registered URL from `.gitmodules`
 * (null when unregistered or when `.gitmodules` omits a URL).
 */
internal data class SubmoduleSource(
    val gitRoot: File,
    val path: String,
    val url: String?,
)

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
    scheduleUi: ((() -> Unit) -> Unit) = edtSchedule,
    submodule: SubmoduleSource? = null,
    cache: RemoteBranchCache? = null,
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
            discoverBranchChoices(client, dir, current, discoverCurrent, loadChoices, log, submodule, cache)
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
 * Refreshes one branch combo by fetching the repository's remote branches first, then
 * relisting. Unlike [loadComboBranches], a failed fetch never blanks the combo: the
 * previous list and selection are restored. Shares the [KEY_BRANCH_LOAD] slot so a
 * refresh and a normal discovery supersede each other. All UI work and the load-lifecycle
 * callback are routed through [scheduleUi] onto the EDT, so a successful refresh cannot
 * be overwritten by a stale restore from the completion handler.
 *
 * For a submodule row ([submodule] != null) the refresh also unions fresh `ls-remote`
 * heads ([submodule.gitRoot] + [SubmoduleSource.url]), so an uninitialized submodule
 * (no local `.git`) still lists its remote branches without fetching.
 */
@Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
internal fun refreshComboBranches(
    combo: JComboBox<String>,
    dir: File,
    current: String,
    branchLoads: BranchLoadCoordinator,
    log: AppLogger,
    onLoadStart: () -> Unit,
    onLoadEnd: (succeeded: Boolean, superseded: Boolean) -> Unit,
    scheduleUi: ((() -> Unit) -> Unit) = edtSchedule,
    submodule: SubmoduleSource? = null,
    cache: RemoteBranchCache? = null,
): BranchLoadHandle {
    @Suppress("UNCHECKED_CAST")
    val previousList = (combo.getClientProperty(KEY_ALL_BRANCHES) as? List<String>) ?: emptyList()
    val previousSelected = combo.selectedItem?.toString() ?: current
    cancelComboBranchLoad(combo)
    val loadToken = Any()
    combo.putClientProperty(KEY_BRANCH_LOAD_TOKEN, loadToken)
    onLoadStart()
    combo.model = DefaultComboBoxModel(arrayOf(LOADING_BRANCH))
    combo.selectedItem = LOADING_BRANCH
    combo.isEnabled = false
    val loadEnded = AtomicBoolean(false)

    fun schedule(ui: () -> Unit) {
        try {
            scheduleUi(ui)
        } catch (e: Exception) {
            log.logFailure("refresh branch UI update failed for ${dir.name}", e)
        }
    }

    fun endLoad(succeeded: Boolean) {
        if (loadEnded.compareAndSet(false, true)) {
            val superseded = combo.getClientProperty(KEY_BRANCH_LOAD_TOKEN) !== loadToken
            schedule { onLoadEnd(succeeded, superseded) }
        }
    }

    fun applyList(branches: List<String>, selected: String) {
        if (combo.getClientProperty(KEY_BRANCH_LOAD_TOKEN) !== loadToken) return
        if (!combo.isDisplayable) return
        val list = mergeBranchChoices(selected, branches)
        combo.model = DefaultComboBoxModel(list.toTypedArray())
        combo.selectedItem = selected
        combo.putClientProperty(KEY_ALL_BRANCHES, list)
        combo.isEnabled = true
    }

    fun restorePrevious() {
        applyList(previousList, previousSelected)
    }

    val handle = if (submodule == null) {
        // Main-repo row keeps today's fetch --prune + relist behavior.
        branchLoads.refresh(dir) { result ->
            val outcome = result.getOrNull()
            val failure = result.exceptionOrNull()
            if (outcome != null && outcome.succeeded) {
                schedule { applyList(outcome.branches, current) }
                endLoad(succeeded = true)
            } else {
                if (outcome != null && !outcome.succeeded) {
                    log.warn("refresh branches failed for ${dir.name}: ${outcome.failure?.diagnostic()}")
                } else if (failure != null) {
                    log.warn("refresh branches failed for ${dir.name}", failure)
                } else {
                    log.warn("refresh branches failed for ${dir.name}")
                }
                schedule { restorePrevious() }
                endLoad(succeeded = false)
            }
        }
    } else {
        // Submodule row: fetch only when checked out, then union local refs with fresh
        // ls-remote heads (an uninitialized submodule is remote-only).
        branchLoads.launch { client ->
            val branches = try {
                refreshSubmoduleBranches(client as GitWorkflowClient, dir, submodule, cache, log)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OperationCancelledException) {
                throw CancellationException("submodule refresh cancelled").apply { initCause(e) }
            } catch (e: GitQueryException) {
                if (e.result.failureKind == GitFailureKind.CANCELLED) {
                    throw CancellationException("submodule refresh cancelled").apply { initCause(e) }
                }
                log.logFailure("refresh branches failed for ${dir.name}", e)
                schedule { restorePrevious() }
                endLoad(succeeded = false)
                return@launch
            } catch (e: Exception) {
                log.logFailure("refresh branches failed for ${dir.name}", e)
                schedule { restorePrevious() }
                endLoad(succeeded = false)
                return@launch
            }
            if (branches != null) {
                schedule { applyList(branches, current) }
                endLoad(succeeded = true)
            } else {
                schedule { restorePrevious() }
                endLoad(succeeded = false)
            }
        }
    }
    handle.invokeOnCompletion { failure ->
        if (failure != null && failure !is CancellationException) {
            log.logFailure("refresh branches failed for ${dir.name}", failure)
        }
        // Cancel / close can fire without an onResult; balance the lifecycle only.
        endLoad(succeeded = false)
    }
    combo.putClientProperty(KEY_BRANCH_LOAD, handle)
    return handle
}

/**
 * Refreshes one submodule row's branches without ever blanking on failure. A checked-out
 * submodule (`.git` present) is fetched first; a failed fetch returns null so the caller
 * restores the previous list. When [SubmoduleSource.url] is registered, the remote heads are
 * re-listed fresh via `ls-remote` (always invalidating the cached value first). Returns the
 * deduplicated union of relisted local branches and remote heads, or null when neither
 * listing succeeded; cancellation propagates as [CancellationException].
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // ls-remote / listAllBranches failures degrade to empty, never blank
private suspend fun refreshSubmoduleBranches(
    client: GitWorkflowClient,
    dir: File,
    submodule: SubmoduleSource,
    cache: RemoteBranchCache?,
    log: AppLogger,
): List<String>? {
    val git = File(dir, ".git").exists()

    if (git) {
        val fetched = try {
            client.fetch(dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            log.warn("refresh branches failed for ${dir.name}", e)
            return null
        }
        if (!fetched.ok) {
            log.warn("refresh branches failed for ${dir.name}: ${fetched.diagnostic()}")
            return null
        }
    }

    val url = submodule.url
    var remote = emptyList<String>()
    var remoteOk = false
    if (url != null) {
        cache?.invalidate(url)
        try {
            remote = client.listRemoteHeads(submodule.gitRoot, url)
            remoteOk = true
            cache?.put(url, remote)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: GitQueryException) {
            if (e.result.failureKind == GitFailureKind.CANCELLED) throw e
            log.warn("ls-remote failed for ${submodule.path}", e)
        } catch (e: Exception) {
            log.warn("ls-remote failed for ${submodule.path}", e)
        }
    }

    var local = emptyList<String>()
    var localOk = false
    if (git) {
        try {
            local = client.listAllBranches(dir)
            localOk = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: GitQueryException) {
            if (e.result.failureKind == GitFailureKind.CANCELLED) throw e
            log.warn("list branches failed for ${dir.name}", e)
        } catch (e: Exception) {
            log.warn("list branches failed for ${dir.name}", e)
        }
    }

    return if (localOk || remoteOk) dedupSortedUnion(local, remote) else null
}

/**
 * Reads the current branch and branch list for one combo in the background.
 * A [CancellationException] (coroutine cancel or a CANCELLED Git query) propagates so
 * the caller ends the lifecycle without applying any UI; any other query failure
 * degrades to a non-succeeded result the caller may use to reset retry state.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // Git query adapters vary; each cancellation type propagates via its own explicit catch
private suspend fun discoverBranchChoices(
    client: PresetDiscoveryGitClient,
    dir: File,
    current: String,
    discoverCurrent: Boolean,
    loadChoices: Boolean,
    log: AppLogger,
    submodule: SubmoduleSource? = null,
    cache: RemoteBranchCache? = null,
): BranchComboLoadResult {
    return try {
        // A submodule row is "git" only when it is checked out (a `.git` entry exists); an
        // uninitialized submodule still has remote heads via its registered URL. The main-repo
        // row keeps the original `dir.exists()` gate and never reaches the remote path.
        val git = if (submodule == null) dir.exists() else File(dir, ".git").exists()
        val selectedBranch = if (discoverCurrent && git) {
            client.currentBranch(dir).orEmpty()
        } else {
            current
        }
        currentCoroutineContext().ensureActive()
        val local = if (loadChoices && git) {
            client.listAllBranches(dir)
        } else {
            emptyList()
        }
        currentCoroutineContext().ensureActive()
        val remote = discoverRemoteHeads(client, submodule, cache, log)
        // The main-repo row keeps its raw (already sorted) local list unchanged; only a
        // submodule row unions local refs with ls-remote heads and re-sorts the union.
        val branches = if (submodule == null) local else dedupSortedUnion(local, remote)
        BranchComboLoadResult(selectedBranch, branches)
    } catch (e: CancellationException) {
        throw e
    } catch (e: OperationCancelledException) {
        // A cancelled Git read surfaces as OperationCancelledException (not the JDK type);
        // the enclosing launch only treats a coroutine CancellationException as a cancel, so
        // convert here (carrying the cause) rather than letting a custom exception be
        // recorded as a load failure by the completion handler.
        throw CancellationException("branch discovery cancelled").apply { initCause(e) }
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

/**
 * Resolves a submodule row's remote heads via its registered URL. A cached hit returns
 * without running `git`; on a miss the result is cached. Any non-cancellation failure
 * degrades to an empty list (local branches survive; the row never blanks) and logs a WARN,
 * while cancellation propagates so the caller ends the lifecycle without applying UI.
 */
@Suppress("TooGenericExceptionCaught") // remote-heads failures degrade to local-only, never propagate
private suspend fun discoverRemoteHeads(
    client: PresetDiscoveryGitClient,
    submodule: SubmoduleSource?,
    cache: RemoteBranchCache?,
    log: AppLogger,
): List<String> {
    if (submodule == null) return emptyList()
    val url = submodule.url ?: return emptyList()
    cache?.get(url)?.let { return it }
    return try {
        client.listRemoteHeads(submodule.gitRoot, url).also { cache?.put(url, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OperationCancelledException) {
        throw e
    } catch (e: GitQueryException) {
        if (e.result.failureKind == GitFailureKind.CANCELLED) throw e
        log.warn("ls-remote failed for ${submodule.path}", e)
        emptyList()
    } catch (e: Exception) {
        log.warn("ls-remote failed for ${submodule.path}", e)
        emptyList()
    }
}

/** Deduplicates and sorts the union of local and remote branch names. */
private fun dedupSortedUnion(local: List<String>, remote: List<String>): List<String> =
    (local + remote).distinct().sorted()

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
