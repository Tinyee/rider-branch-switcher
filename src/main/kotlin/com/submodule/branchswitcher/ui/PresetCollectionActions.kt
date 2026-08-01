package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal interface PresetCollectionHost {
    val editors: List<PresetEditor>

    fun clearEditors()
    fun addEditor(root: Path, preset: Preset)
    fun removeEditor(editor: PresetEditor)
    fun showEmptyState()
    fun refreshList()
    fun refreshParent()
    fun notifyStateChanged()
}

/** User commands that load, persist, create, import, and export preset collections. */
internal class PresetCollectionActions(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val host: PresetCollectionHost,
) {
    private val collectionOperationInProgress = AtomicBoolean(false)

    private val transferActions = PresetTransferActions(
        project = project,
        gitRoot = gitRoot,
        log = log,
        host = host,
        persist = ::persistOrReport,
    )
    private val currentStatePresetCreator = CurrentStatePresetCreator(
        project = project,
        service = service,
        gitRoot = gitRoot,
        log = log,
        host = host,
        persist = ::persistOrReport,
        nameValidator = ::newNameValidator,
    )

    /** Load presets from file and rebuild the editor list. */
    fun reload() {
        if (!beginCollectionOperation()) return
        service.scope.launch {
            val result = service.loadPresets()
            project.invokeLaterIfAlive {
                collectionOperationInProgress.set(false)
                result.onSuccess { (file, parsed) ->
                    val root = gitRoot()
                    if (root == null) {
                        log.error("git root not found")
                        Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
                        return@onSuccess
                    }
                    host.clearEditors()
                    if (parsed.presets.isEmpty()) {
                        host.showEmptyState()
                    } else {
                        parsed.presets.forEach { host.addEditor(root, it) }
                    }
                    log.debug("loaded ${parsed.presets.size} preset(s) from $file")
                    host.refreshList()
                    host.notifyStateChanged()
                }.onFailure { error ->
                    log.error("preset load failed", error)
                    Notifier.error(
                        project,
                        Bundle.msg("preset.load.failed"),
                        error.message ?: Bundle.msg("error.unknown"),
                    )
                }
            }
        }
    }

    fun saveEditor(editor: PresetEditor, pendingPreset: Preset, onComplete: (Boolean) -> Unit) {
        persistOrReport(
            host.editors.map { if (it === editor) pendingPreset else it.currentPreset() },
        ) { saved ->
            if (saved) host.notifyStateChanged()
            onComplete(saved)
        }
    }

    fun deleteEditor(editor: PresetEditor) {
        val name = editor.currentPreset().name
        val confirm = Messages.showYesNoDialog(
            project,
            Bundle.msg("label.delete.confirm.msg", name),
            Bundle.msg("label.delete.confirm.title"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        val remaining = host.editors.filter { it !== editor }.map { it.currentPreset() }
        persistOrReport(remaining) { saved ->
            if (!saved) return@persistOrReport
            host.removeEditor(editor)
            log.debug("[deleted] $name")
            host.notifyStateChanged()
        }
    }

    fun addPreset() {
        val name = Messages.showInputDialog(
            project,
            Bundle.msg("dialog.preset.name.rule"),
            Bundle.msg("dialog.add.preset"),
            null,
            "",
            newNameValidator(),
        )?.trim()
        if (name.isNullOrEmpty()) return
        val template = service.presets.firstOrNull()
        val newPreset = Preset(
            name = name,
            main = name,
            submodules = template?.submodules ?: emptyMap(),
        )
        val root = gitRoot() ?: return
        persistOrReport(host.editors.map { it.currentPreset() } + newPreset) { saved ->
            if (!saved) return@persistOrReport
            host.addEditor(root, newPreset)
            host.refreshParent()
            log.debug("[added] $name (submodule branches can be edited after expanding)")
            host.notifyStateChanged()
        }
    }

    fun addPresetFromCurrent() {
        currentStatePresetCreator.create()
    }

    fun openConfig() {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        service.scope.launch {
            val file = withContext(Dispatchers.IO) { PresetLoader.resolveFile(base) }
            project.invokeLaterIfAlive {
                if (file == null) {
                    Messages.showWarningDialog(
                        project,
                        "${Bundle.msg("preset.file.not.found")}\n$base/.idea/${PresetLoader.IDEA_FILE_NAME}",
                        Bundle.msg("plugin.title"),
                    )
                    return@invokeLaterIfAlive
                }
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(file.toString())
                if (virtualFile != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        }
    }

    fun exportPresets() {
        transferActions.exportPresets()
    }

    fun importPresets() {
        transferActions.importPresets()
    }

    private fun newNameValidator(): InputValidator = object : InputValidator {
        override fun checkInput(input: String?): Boolean {
            val name = input?.trim().orEmpty()
            return name.isNotEmpty() && host.editors.none { it.currentPreset().name == name }
        }

        override fun canClose(input: String?): Boolean = checkInput(input)
    }

    @Suppress("TooGenericExceptionCaught") // repository save may fail through IO or serialization adapters
    private fun persistOrReport(presets: List<Preset>, onComplete: (Boolean) -> Unit) {
        if (!beginCollectionOperation()) {
            onComplete(false)
            return
        }
        val snapshot = presets.toList()
        service.scope.launch {
            val failure = try {
                service.savePresets(snapshot)
                null
            } catch (e: Exception) {
                e
            }
            project.invokeLaterIfAlive {
                collectionOperationInProgress.set(false)
                if (failure == null) {
                    log.debug("[saved]")
                    onComplete(true)
                } else {
                    reportSaveFailure(failure)
                    onComplete(false)
                }
            }
        }
    }

    private fun beginCollectionOperation(): Boolean {
        if (collectionOperationInProgress.compareAndSet(false, true)) return true
        log.warn("preset collection operation ignored while another operation is running")
        Notifier.warn(
            project,
            Bundle.msg("notify.write.busy"),
            Bundle.msg("notify.write.busy.msg"),
        )
        return false
    }

    private fun reportSaveFailure(error: Exception) {
        log.error("preset save failed", error)
        Notifier.error(
            project,
            Bundle.msg("preset.save.failed"),
            error.message ?: error.javaClass.simpleName,
        )
    }

}
