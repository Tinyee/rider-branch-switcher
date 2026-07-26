package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Path

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
    /** Load presets from file and rebuild the editor list. */
    fun reload() {
        host.clearEditors()
        service.loadPresets()
            .onSuccess { (file, parsed) ->
                val root = gitRoot()
                if (root == null) {
                    log.error("git root not found")
                    Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
                    return@onSuccess
                }
                if (parsed.presets.isEmpty()) {
                    host.showEmptyState()
                } else {
                    parsed.presets.forEach { host.addEditor(root, it) }
                }
                log.debug("loaded ${parsed.presets.size} preset(s) from $file")
                host.refreshList()
                host.notifyStateChanged()
            }
            .onFailure {
                log.error("${it.message}")
                Notifier.error(project, Bundle.msg("preset.load.failed"), it.message ?: "unknown error")
            }
    }

    @Suppress("TooGenericExceptionCaught") // persistence adapters may surface IO or serialization failures
    fun saveEditor(editor: PresetEditor, pendingPreset: Preset) {
        try {
            service.savePresets(host.editors.map {
                if (it === editor) pendingPreset else it.currentPreset()
            })
            log.debug("[saved]")
            host.notifyStateChanged()
        } catch (e: Exception) {
            reportSaveFailure(e)
            throw e
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
        if (!persistOrReport(remaining)) return
        host.removeEditor(editor)
        log.debug("[deleted] $name")
        host.notifyStateChanged()
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
        if (service.presets.isEmpty() && service.loadPresets().isFailure) {
            log.warn("cannot add preset — failed to load existing presets")
            return
        }
        val template = service.presets.firstOrNull()
        val newPreset = Preset(
            name = name,
            main = name,
            submodules = template?.submodules ?: emptyMap(),
        )
        val root = gitRoot() ?: return
        if (!persistOrReport(host.editors.map { it.currentPreset() } + newPreset)) return
        host.addEditor(root, newPreset)
        host.refreshParent()
        log.debug("[added] $name (展开后可编辑各子模块分支)")
        host.notifyStateChanged()
    }

    fun addPresetFromCurrent() {
        val root = gitRoot() ?: return
        service.scope.launch(Dispatchers.Default) {
            val result = probeCurrentState(root) ?: return@launch
            project.invokeLaterIfAlive {
                val mainBranch = result.mainBranch
                if (mainBranch.isNullOrEmpty()) {
                    Messages.showWarningDialog(
                        project,
                        Bundle.msg("dialog.detached.head"),
                        Bundle.msg("plugin.title"),
                    )
                    return@invokeLaterIfAlive
                }
                if (result.skipped.isNotEmpty()) {
                    val details = result.skipped.joinToString("\n") { "- $it" }
                    log.warn("[from current] incomplete preset blocked: ${result.skipped.joinToString(", ")}")
                    Messages.showWarningDialog(
                        project,
                        Bundle.msg("dialog.from.current.incomplete", details),
                        Bundle.msg("dialog.from.current"),
                    )
                    return@invokeLaterIfAlive
                }
                val name = Messages.showInputDialog(
                    project,
                    Bundle.msg("dialog.preset.name.rule"),
                    Bundle.msg("dialog.from.current"),
                    null,
                    mainBranch,
                    newNameValidator(),
                )?.trim()
                if (name.isNullOrEmpty()) return@invokeLaterIfAlive
                val newPreset = Preset(
                    name = name,
                    main = mainBranch,
                    submodules = result.submodules,
                )
                if (!persistOrReport(host.editors.map { it.currentPreset() } + newPreset)) {
                    return@invokeLaterIfAlive
                }
                host.addEditor(root, newPreset)
                host.refreshParent()
                log.debug(
                    "[added from current] $name -> 主仓=$mainBranch, ${result.submodules.size} 个子模块",
                )
                host.notifyStateChanged()
            }
        }
    }

    fun openConfig() {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        val file = PresetLoader.resolveFile(base)
        if (file == null) {
            Messages.showWarningDialog(
                project,
                "${Bundle.msg("preset.file.not.found")}\n$base/.idea/${PresetLoader.IDEA_FILE_NAME}",
                Bundle.msg("plugin.title"),
            )
            return
        }
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(file.toString())
        if (virtualFile != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Gson and the system clipboard expose unrelated failure types
    fun exportPresets() {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(PresetFile(host.editors.map { it.currentPreset() }))
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(json), null)
            log.debug("[exported] ${host.editors.size} preset(s) 已复制到剪贴板")
            Notifier.info(
                project,
                Bundle.msg("notify.export.complete"),
                Bundle.msg("notify.exported", host.editors.size),
            )
        } catch (e: Exception) {
            log.error("[export] failed: ${e.message}")
            Notifier.error(
                project,
                Bundle.msg("notify.export.complete"),
                "${Bundle.msg("dialog.import.failed")}: ${e.message}",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // clipboard, parsing, persistence, and UI adapters share this boundary
    fun importPresets() {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboard.getData(DataFlavor.stringFlavor) as? String
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, Bundle.msg("dialog.import.empty"), Bundle.msg("dialog.import"))
                return
            }
            val result = parsePresetImport(text, host.editors.map { it.currentPreset().name }.toSet())
            if (result.presets.isEmpty()) {
                val message = if (result.hasRecognizedEntries) {
                    Bundle.msg("dialog.import.none", result.conflictingNames.size, result.invalidNames.size)
                } else {
                    Bundle.msg("dialog.import.invalid")
                }
                Messages.showWarningDialog(project, message, Bundle.msg("dialog.import"))
                return
            }
            if (result.invalidNames.isNotEmpty()) {
                log.debug("[import] skipped ${result.invalidNames.size} invalid: ${result.invalidNames.joinToString(", ")}")
            }
            if (result.conflictingNames.isNotEmpty()) {
                log.debug(
                    "[import] skipped ${result.conflictingNames.size} conflicts: " +
                        result.conflictingNames.joinToString(", "),
                )
            }
            val root = gitRoot() ?: return
            val combined = host.editors.map { it.currentPreset() } + result.presets
            if (!persistOrReport(combined)) return
            result.presets.forEach { host.addEditor(root, it) }
            host.refreshParent()
            log.debug("[imported] ${result.presets.size} preset(s) from clipboard")
            host.notifyStateChanged()
            Notifier.info(
                project,
                Bundle.msg("notify.import.complete"),
                Bundle.msg("notify.imported", result.presets.size),
            )
        } catch (e: Exception) {
            log.error("[import] error: ${e.message}")
            Messages.showWarningDialog(
                project,
                "${Bundle.msg("dialog.import.failed")}: ${e.message}",
                Bundle.msg("dialog.import"),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // modal and Git adapters must report non-cancellation failures uniformly
    private suspend fun probeCurrentState(root: Path): CurrentStateProbeResult? {
        return try {
            com.submodule.branchswitcher.TaskBridge.runModal(
                project,
                Bundle.msg("progress.read.current"),
                false,
            ) { indicator ->
                indicator.isIndeterminate = true
                indicator.text = Bundle.msg("progress.main.repo")
                val rootFile = root.toFile()
                val mainBranch = service.gitClient.currentBranch(rootFile)
                val submodules = LinkedHashMap<String, String>()
                val skipped = mutableListOf<String>()
                service.gitClient.listSubmodulePaths(rootFile).forEach { path ->
                    indicator.text = path
                    val dir = root.resolve(path).toFile()
                    if (!dir.exists() || (!dir.resolve(".git").exists() && !File(dir, ".git").isFile)) {
                        skipped += "$path (${Bundle.msg("status.tooltip.not.init")})"
                        return@forEach
                    }
                    val branch = service.gitClient.currentBranch(dir)
                    if (branch.isNullOrEmpty()) {
                        skipped += "$path (detached)"
                        return@forEach
                    }
                    submodules[path] = branch
                }
                CurrentStateProbeResult(mainBranch, submodules, skipped)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            null
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            null
        } catch (e: Exception) {
            log.error("probe current state failed: ${e.javaClass.simpleName}: ${e.message}")
            Notifier.warn(project, Bundle.msg("plugin.title"), "${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun newNameValidator(): InputValidator = object : InputValidator {
        override fun checkInput(input: String?): Boolean {
            val name = input?.trim().orEmpty()
            return name.isNotEmpty() && host.editors.none { it.currentPreset().name == name }
        }

        override fun canClose(input: String?): Boolean = checkInput(input)
    }

    @Suppress("TooGenericExceptionCaught") // repository save may fail through IO or serialization adapters
    private fun persistOrReport(presets: List<Preset>): Boolean = try {
        service.savePresets(presets)
        log.debug("[saved]")
        true
    } catch (e: Exception) {
        reportSaveFailure(e)
        false
    }

    private fun reportSaveFailure(error: Exception) {
        log.error("preset save failed: ${error.javaClass.simpleName}: ${error.message}")
        Notifier.error(
            project,
            Bundle.msg("preset.save.failed"),
            error.message ?: error.javaClass.simpleName,
        )
    }

    private data class CurrentStateProbeResult(
        val mainBranch: String?,
        val submodules: LinkedHashMap<String, String>,
        val skipped: List<String>,
    )
}
