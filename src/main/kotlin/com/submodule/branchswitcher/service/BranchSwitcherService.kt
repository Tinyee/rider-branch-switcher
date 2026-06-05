package com.submodule.branchswitcher.service

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
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(
    name = "BranchSwitcherOptions",
    storages = [Storage("branch-switcher.xml")]
)
class BranchSwitcherService(private val project: Project)
    : PersistentStateComponent<BranchSwitcherService.OptionsState> {

    data class OptionsState(
        var dirtyAction: String = "Stash",
        var fetchFirst: Boolean = true,
        var pullAfterSwitch: Boolean = true,
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

    val gitClient: GitClient = GitOps()

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
