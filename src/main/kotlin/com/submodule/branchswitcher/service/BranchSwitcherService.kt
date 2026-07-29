package com.submodule.branchswitcher.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import kotlinx.coroutines.CoroutineScope
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Idempotent ownership token for the project write gate.
     *
     * Every successful acquisition must be closed from `finally`.
     */
    class WriteLease internal constructor(
        private val gate: AtomicBoolean,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                gate.set(false)
            }
        }
    }

    /** Acquires the write gate, returning an idempotent scoped lease on success. */
    fun tryAcquireWrite(): WriteLease? =
        if (writeGate.compareAndSet(false, true)) WriteLease(writeGate) else null

    data class OptionsState(
        var dirtyAction: String = "Stash",
        var fetchFirst: Boolean = true,
        var pullAfterSwitch: Boolean = true,
        var timeoutSeconds: Int = 60,
        var confirmBeforeInit: Boolean = false,
        var history: MutableList<SwitchHistoryEntry> = mutableListOf(),
    )

    private var options = OptionsState()

    override fun getState(): OptionsState = options
    override fun loadState(state: OptionsState) {
        synchronized(gitClientLock) {
            options = state
            _gitClient = null
        }
    }

    // ── Delegated sub-components ─────────────────────────────────────

    val presetRepo = PresetRepository(project)

    var dirtyAction: DirtyAction
        get() = when (options.dirtyAction) {
            "Skip" -> DirtyAction.Skip
            "Force" -> DirtyAction.Force
            else -> DirtyAction.Stash
        }
        set(value) { options.dirtyAction = value.name }

    var fetchFirst: Boolean
        get() = options.fetchFirst
        set(value) { options.fetchFirst = value }

    var pullAfterSwitch: Boolean
        get() = options.pullAfterSwitch
        set(value) { options.pullAfterSwitch = value }

    var timeoutSeconds: Int
        get() = synchronized(gitClientLock) { options.timeoutSeconds }
        set(value) {
            synchronized(gitClientLock) {
                if (options.timeoutSeconds != value) {
                    options.timeoutSeconds = value
                    _gitClient = null
                }
            }
        }

    var confirmBeforeInit: Boolean
        get() = options.confirmBeforeInit
        set(value) { options.confirmBeforeInit = value }

    /** Cached [GitOps] instance, recreated only when timeout changes. */
    private val gitClientLock = Any()
    private var _gitClient: GitClient? = null

    val gitClient: GitClient
        get() = synchronized(gitClientLock) {
            _gitClient ?: GitOps(options.timeoutSeconds).also {
                _gitClient = it
            }
        }

    // ── Preset persistence (delegated) ───────────────────────────────

    val presets: List<Preset> get() = presetRepo.presets

    fun loadPresets(): Result<Pair<Path, PresetFile>> = presetRepo.load()

    fun savePresets(presets: List<Preset>) = presetRepo.save(presets)

    // -- Switch history for undo support (max 5 entries, persisted across restarts) --

    private val maxHistory = 5
    /** Records a completed switch: preset name, stable id (for rename survival), and timestamp. */
    data class SwitchHistoryEntry(
        val presetName: String = "",
        val presetId: String? = null,
        val timestamp: Long = 0,
    )

    @Synchronized
    fun addHistory(name: String, id: String? = null) {
        val list = options.history
        list.add(0, SwitchHistoryEntry(name, id, System.currentTimeMillis()))
        if (list.size > maxHistory) {
            options.history = list.take(maxHistory).toMutableList()
        }
    }

    fun getHistory(): List<SwitchHistoryEntry> = options.history.toList()

    fun resolveSwitchRequest(preset: Preset): ResolvedSwitchRequest =
        ResolvedSwitchRequest.resolve(
            preset,
            SwitchOptions(
                dirty = dirtyAction,
                pull = pullAfterSwitch,
                fetchFirst = fetchFirst,
                confirmBeforeInit = confirmBeforeInit,
            ),
        )

}
