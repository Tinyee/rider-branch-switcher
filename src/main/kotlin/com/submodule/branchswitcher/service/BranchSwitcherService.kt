package com.submodule.branchswitcher.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Central project-level service for the Branch Switcher plugin.
 *
 * Composition root that owns persistent state and delegates to sub-components:
 * - [PresetRepository] for preset loading/saving/caching
 *
 * Also manages: write gate, switch history, GitClient cache, detect-gen counter,
 * and persistent switch options via [PersistentStateComponent].
 */
@Service(Service.Level.PROJECT)
@State(
    name = "BranchSwitcherOptions",
    storages = [Storage("branch-switcher.xml")]
)
class BranchSwitcherService(
    private val project: Project,
    cs: CoroutineScope,
) : PersistentStateComponent<BranchSwitcherService.OptionsState>, Disposable {

    /** Platform-injected [CoroutineScope] with [SupervisorJob] semantics. */
    val scope = cs

    /** Prevents overlapping write operations (switch, derive, rollback). */
    private val writeGate = AtomicBoolean(false)

    /** Returns true if a write operation can start (locks the gate). */
    fun tryStartWrite(): Boolean = writeGate.compareAndSet(false, true)

    /** Releases the write gate. Must be called in finally. */
    fun endWrite() { writeGate.set(false) }

    override fun dispose() {
        // Platform manages injected [cs] lifecycle; nothing else to clean up.
    }

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
    override fun loadState(state: OptionsState) { options = state }

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
        get() = options.timeoutSeconds
        set(value) { options.timeoutSeconds = value }

    var confirmBeforeInit: Boolean
        get() = options.confirmBeforeInit
        set(value) { options.confirmBeforeInit = value }

    /** Cached [GitOps] instance, recreated only when timeout changes. */
    private var _gitClient: GitClient? = null
    private var _gitClientTimeout: Int = -1

    val gitClient: GitClient get() {
        if (_gitClient == null || _gitClientTimeout != options.timeoutSeconds) {
            _gitClient = GitOps(options.timeoutSeconds)
            _gitClientTimeout = options.timeoutSeconds
        }
        return _gitClient!!
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
    fun getLastHistory(): SwitchHistoryEntry? = options.history.firstOrNull()

    // -- Stale-detection for async branch probes --

    /**
     * Monotonically increasing generation counter (atomic for thread safety).
     * Each [detectCurrentState] call increments it via [nextDetectGen].
     * Async probe callbacks check [getDetectGen] — if a newer probe started,
     * the old result is discarded to avoid updating the UI with stale data.
     */
    private val detectGen = AtomicLong(0)

    fun nextDetectGen(): Long = detectGen.incrementAndGet()
    fun getDetectGen(): Long = detectGen.get()
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

    companion object {
        fun getInstance(project: Project): BranchSwitcherService =
            project.service()
    }
}
