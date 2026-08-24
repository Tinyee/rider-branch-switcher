package com.submodule.branchswitcher.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.impl.GitOps
import com.submodule.branchswitcher.git.impl.GitProcessShutdown
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.settings.dirtyActionFromName
import com.submodule.branchswitcher.settings.indexToTimeout
import com.submodule.branchswitcher.settings.timeoutToIndex
import kotlinx.coroutines.CoroutineScope
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.model.Preset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central project-level service for the Branch Switcher plugin.
 *
 * Composition root that owns persistent state and delegates to sub-components:
 * - [PresetRepository] for preset loading/saving/caching
 *
 * Also manages: write gate, switch history, GitClient cache, and persistent
 * switch options via [PersistentStateComponent].
 */
@Service(Service.Level.PROJECT)
@State(
    name = "BranchSwitcherOptions",
    storages = [Storage("branch-switcher.xml")]
)
class BranchSwitcherService(
    private val project: Project,
    cs: CoroutineScope,
) : PersistentStateComponent<BranchSwitcherService.OptionsState> {

    /** Platform-injected [CoroutineScope] with [SupervisorJob] semantics. */
    val scope = cs

    /** Prevents overlapping write operations (switch, derive, rollback). */
    private val writeGate = AtomicBoolean(false)
    /** Diagnostic: the operation id currently owning the write gate, so busy rejections can name the holder. */
    private val writeHolder = AtomicReference<String?>(null)

    /**
     * Idempotent ownership token for the project write gate.
     *
     * Every successful acquisition must be closed from `finally`.
     */
    class WriteLease internal constructor(
        private val gate: AtomicBoolean,
        private val holder: AtomicReference<String?>,
        private val acquiredBy: String,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                gate.set(false)
                holder.compareAndSet(acquiredBy, null)
            }
        }
    }

    /** Acquires the write gate, returning an idempotent scoped lease on success. */
    fun tryAcquireWrite(): WriteLease? {
        if (!writeGate.compareAndSet(false, true)) return null
        val acquiredBy = newOperationId("write")
        writeHolder.set(acquiredBy)
        return WriteLease(writeGate, writeHolder, acquiredBy)
    }

    /** Diagnostic: the operation id currently holding the write gate, or null when free. */
    val currentWriteHolder: String? get() = writeHolder.get()

    data class OptionsState(
        var dirtyAction: String = "Stash",
        var fetchFirst: Boolean = true,
        var pullAfterSwitch: Boolean = true,
        var timeoutSeconds: Int = 60,
        var confirmBeforeInit: Boolean = false,
        var autoDiscardMeta: Boolean = false,
        var history: MutableList<SwitchHistoryEntry> = mutableListOf(),
    )

    private var options = OptionsState()
    private val stateLock = Any()

    /**
     * IntelliJ persistence contract. [getState] returns a snapshot (history copied)
     * and [loadState] replaces the whole state under the same lock, so a concurrent
     * read never observes a partially swapped state. [loadState] also drops the cached
     * GitOps so the recreated runner picks up the newly persisted timeout, and
     * normalizes corrupted scalar values. Both run on the serialization thread; all
     * other option access goes through [stateLock] too.
     */
    override fun getState(): OptionsState = synchronized(stateLock) { options.snapshot() }
    override fun loadState(state: OptionsState) {
        synchronized(stateLock) {
            options = state.snapshot().normalized()
            _gitClient = null
        }
    }

    // ── Delegated sub-components ─────────────────────────────────────

    val presetRepo = PresetRepository(project)

    var dirtyAction: DirtyAction
        get() = synchronized(stateLock) { dirtyActionFromName(options.dirtyAction) }
        set(value) { synchronized(stateLock) { options.dirtyAction = value.name } }

    var fetchFirst: Boolean
        get() = synchronized(stateLock) { options.fetchFirst }
        set(value) { synchronized(stateLock) { options.fetchFirst = value } }

    var pullAfterSwitch: Boolean
        get() = synchronized(stateLock) { options.pullAfterSwitch }
        set(value) { synchronized(stateLock) { options.pullAfterSwitch = value } }

    var timeoutSeconds: Int
        get() = synchronized(stateLock) { options.timeoutSeconds }
        set(value) {
            synchronized(stateLock) {
                // The settings UI only offers supported values, but a programmatic or
                // legacy caller could persist an arbitrary number; normalize so the
                // getter, the exported state, and the git client never disagree.
                val normalized = indexToTimeout(timeoutToIndex(value))
                if (options.timeoutSeconds != normalized) {
                    options.timeoutSeconds = normalized
                    _gitClient = null
                }
            }
        }

    var confirmBeforeInit: Boolean
        get() = synchronized(stateLock) { options.confirmBeforeInit }
        set(value) { synchronized(stateLock) { options.confirmBeforeInit = value } }

    /** When true, untracked .meta collisions are discarded automatically without confirmation. */
    var autoDiscardMeta: Boolean
        get() = synchronized(stateLock) { options.autoDiscardMeta }
        set(value) { synchronized(stateLock) { options.autoDiscardMeta = value } }

    private var _gitClient: GitClient? = null

    /**
     * The project's single [GitOps]: its process runner applies the configured
     * timeout against the shared git-process pool, and direct calls through it share
     * one cancellation scope. Cached here and recreated only when the persisted
     * timeout changes ([loadState] resets the cache); callers never build their own.
     */
    private val shutdownServiceEnsured = AtomicBoolean(false)

    val gitClient: GitClient
        get() {
            // Eagerly instantiate the application-level shutdown service so the git thread
            // pools are disposed on plugin unload whenever git work runs (the pools are
            // created lazily on first Git process). Only the test environment (no
            // application) degrades gracefully; inside the IDE a failure to instantiate
            // the service must surface rather than silently dropping the unload cleanup.
            // Resolved outside the lock: a service lookup must never run while holding
            // stateLock.
            if (shutdownServiceEnsured.compareAndSet(false, true) &&
                com.intellij.openapi.application.ApplicationManager.getApplication() != null
            ) {
                service<GitProcessShutdown>()
            }
            return synchronized(stateLock) {
                _gitClient ?: GitOps(options.timeoutSeconds).also { _gitClient = it }
            }
        }

    // ── Preset persistence (delegated) ───────────────────────────────

    val presets: List<Preset> get() = presetRepo.presets

    suspend fun loadPresets(): Result<PresetLoadOutcome> = presetRepo.load()

    suspend fun savePresets(presets: List<Preset>) = presetRepo.save(presets)

    // -- Switch history for returning to a previous preset (max 5 entries, persisted across restarts) --

    private val maxHistory = 5
    /** Records a completed switch: preset name, stable id (for rename survival), and timestamp. */
    data class SwitchHistoryEntry(
        val presetName: String = "",
        val presetId: String? = null,
        val timestamp: Long = 0,
    )

    fun addHistory(name: String, id: String? = null) {
        synchronized(stateLock) {
            val list = options.history
            val next = SwitchHistoryEntry(name, id, System.currentTimeMillis())
            // Re-selecting the preset already at the top of the history is not a new
            // switch to record, so it must not add a consecutive duplicate (which would
            // crowd out a real return-to-previous entry at maxHistory).
            if (list.firstOrNull()?.samePreset(next) == true) return
            list.add(0, next)
            if (list.size > maxHistory) {
                options.history = list.take(maxHistory).toMutableList()
            }
        }
    }

    /** Two history entries are the same switch target when their stable ids agree, else their names. */
    private fun SwitchHistoryEntry.samePreset(other: SwitchHistoryEntry): Boolean {
        val a = presetId
        val b = other.presetId
        if (a != null && b != null) return a == b
        return presetName == other.presetName
    }

    fun getHistory(): List<SwitchHistoryEntry> = synchronized(stateLock) { options.history.toList() }

    fun resolveSwitchRequest(preset: Preset): ResolvedSwitchRequest {
        val switchOptions = synchronized(stateLock) {
            SwitchOptions(
                dirty = dirtyActionFromName(options.dirtyAction),
                pull = options.pullAfterSwitch,
                fetchFirst = options.fetchFirst,
                confirmBeforeInit = options.confirmBeforeInit,
                autoDiscardMeta = options.autoDiscardMeta,
            )
        }
        return ResolvedSwitchRequest.from(preset, switchOptions)
    }

    private fun OptionsState.snapshot(): OptionsState = copy(history = history.toMutableList())

    /**
     * Clamps externally-persisted scalar settings to canonical values at the load
     * boundary, so a hand-edited or corrupted state file cannot leave the settings
     * UI and the Git runner disagreeing (e.g. the UI showing 60s while the client
     * keeps a 999s timeout). The booleans and history pass through unchanged.
     */
    private fun OptionsState.normalized(): OptionsState = copy(
        dirtyAction = dirtyActionFromName(dirtyAction).name,
        timeoutSeconds = indexToTimeout(timeoutToIndex(timeoutSeconds)),
    )
}
