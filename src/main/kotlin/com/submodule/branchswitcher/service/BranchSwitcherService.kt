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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path

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
class BranchSwitcherService(private val project: Project)
    : PersistentStateComponent<BranchSwitcherService.OptionsState>, Disposable {

    /** [SupervisorJob] isolates child coroutine failures so one failed probe doesn't cancel sibling probes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun dispose() {
        scope.cancel()
    }

    data class OptionsState(
        var dirtyAction: String = "Stash",
        var fetchFirst: Boolean = true,
        var pullAfterSwitch: Boolean = true,
        var timeoutSeconds: Int = 60,
        var confirmBeforeInit: Boolean = false,
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
        set(value) {
            options.timeoutSeconds = value
            gitClient = GitOps(value)
        }

    var confirmBeforeInit: Boolean
        get() = options.confirmBeforeInit
        set(value) { options.confirmBeforeInit = value }

    var gitClient: GitClient = GitOps(options.timeoutSeconds)
        private set

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
        val file = savedFilePath ?: return
        presetFile = presetFile.copy(presets = presets)
        PresetLoader.save(file, presetFile)
    }

    // -- Switch history for undo support (max 5 entries) --

    private var history = mutableListOf<SwitchHistoryEntry>()
    private val maxHistory = 5
    /** Records a completed switch: preset name + timestamp. */
    data class SwitchHistoryEntry(val presetName: String, val timestamp: Long)

    fun addHistory(name: String) {
        history.add(0, SwitchHistoryEntry(name, System.currentTimeMillis()))
        if (history.size > maxHistory) history = history.take(maxHistory).toMutableList()
    }

    fun getHistory(): List<SwitchHistoryEntry> = history.toList()
    fun getLastHistory(): SwitchHistoryEntry? = history.firstOrNull()

    // -- Stale-detection for async branch probes --

    /**
     * Monotonically increasing generation counter.
     * Each [detectCurrentState] call increments it via [nextDetectGen].
     * Async probe callbacks check [getDetectGen] — if a newer probe started,
     * the old result is discarded to avoid updating the UI with stale data.
     */
    private var currentBranches: Map<String, String?> = emptyMap()
    private var detectGen: Long = 0

    fun nextDetectGen(): Long = ++detectGen
    fun getDetectGen(): Long = detectGen
    fun setCurrentBranches(branches: Map<String, String?>) { currentBranches = branches }
    fun getCurrentBranches(): Map<String, String?> = currentBranches

    companion object {
        fun getInstance(project: Project): BranchSwitcherService =
            project.service()
    }
}
