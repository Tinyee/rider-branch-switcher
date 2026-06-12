package com.submodule.branchswitcher.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.model.DirtyAction
import kotlinx.coroutines.CoroutineScope
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Central project-level service for the Branch Switcher plugin.
 *
 * Responsibilities:
 * - Persist switch options to [branch-switcher.xml] via [PersistentStateComponent]
 * - Manage preset loading/saving via [PresetLoader]
 * - Provide a [CoroutineScope] for async operations (background branch detection, combo loading)
 * - Track switch history for undo support (max 5 entries)
 * - Cache current branch state ([currentBranches]) with stale-detection via [detectGen]
 *
 * The [detectGen] counter is incremented on each [detectCurrentState] call.
 * Async branch probes capture the current generation; when results arrive,
 * they are discarded if a newer detection has started, preventing stale UI updates.
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

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null
    val presets: List<Preset> get() = presetFile.presets

    fun loadPresets(): Result<Pair<Path, PresetFile>> {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) }
            ?: return Result.failure(IllegalStateException("project base path is null"))
        return PresetLoader.load(base).onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
        }
    }

    fun savePresets(presets: List<Preset>) {
        val file = savedFilePath ?: run {
            val base = project.basePath?.let { java.nio.file.Paths.get(it) }
                ?: throw IllegalStateException("project base path is null — cannot save presets")
            val resolved = PresetLoader.ensureFile(base)
            savedFilePath = resolved
            resolved
        }
        presetFile = presetFile.copy(presets = presets)
        PresetLoader.save(file, presetFile)
    }

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
    companion object {
        fun getInstance(project: Project): BranchSwitcherService =
            project.service()
    }
}
