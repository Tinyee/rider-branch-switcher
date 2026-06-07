package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Manages the preset list: loading, saving, CRUD, import/export.
 * Owns the [editors] list and the [presetsInner] container.
 * Extracted from [BranchSwitcherPanel].
 */
class PresetListManager(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: (String) -> Unit,
    private val onSwitch: (Preset) -> Unit,
    private val onDerive: (Path, Preset, String) -> Unit,
) {
    val editors = mutableListOf<PresetEditor>()
    private var emptyStatePanel: JPanel? = null
    var onStateChanged: (() -> Unit)? = null

    /** Load presets from file and rebuild the editor list. */
    fun reload(presetsInner: JPanel) {
        editors.clear()
        presetsInner.removeAll()
        service.loadPresets()
            .onSuccess { (file, parsed) ->
                val root = gitRoot()
                if (root == null) {
                    log("[error] git root not found")
                    Notifier.error(project, "Branch Switcher", "未找到 git 根目录")
                    return@onSuccess
                }
                emptyStatePanel = null
                if (parsed.presets.isEmpty()) {
                    val panel = createEmptyState(presetsInner)
                    emptyStatePanel = panel
                    presetsInner.add(panel)
                } else {
                    parsed.presets.forEach { addEditorRow(root, it, presetsInner) }
                }
                log("loaded ${parsed.presets.size} preset(s) from $file")
                presetsInner.revalidate()
                presetsInner.repaint()
                onStateChanged?.invoke()
            }
            .onFailure {
                log("[error] ${it.message}")
                Notifier.error(project, Strings.presetLoadFailed, it.message ?: "unknown error")
            }
    }

    fun addEditorRow(root: Path, preset: Preset, presetsInner: JPanel) {
        emptyStatePanel?.let { presetsInner.remove(it); emptyStatePanel = null }
        lateinit var editor: PresetEditor
        editor = PresetEditor(
            gitRoot = root,
            initial = preset,
            log = log,
            onSwitch = { onSwitch(it) },
            onSave = { saveAll() },
            onDelete = { deleteEditor(editor, presetsInner) },
            onDerive = { branchName -> onDerive(root, preset, branchName) },
            gitClient = service.gitClient,
            scope = service.scope,
        )
        editors.add(editor)
        presetsInner.add(editor)
        presetsInner.add(Box.createVerticalStrut(4))
    }

    fun deleteEditor(editor: PresetEditor, presetsInner: JPanel) {
        val name = editor.currentPreset().name
        val confirm = Messages.showYesNoDialog(
            project,
            Strings.deletePresetMsg.format(name),
            Strings.deleteConfirm,
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        editors.remove(editor)
        presetsInner.remove(editor)
        saveAll()
        if (editors.isEmpty()) {
            val panel = createEmptyState(presetsInner)
            emptyStatePanel = panel
            presetsInner.add(panel)
        }
        presetsInner.revalidate()
        presetsInner.repaint()
        log("[deleted] $name")
    }

    fun saveAll() {
        service.savePresets(editors.map { it.currentPreset() })
        log("[saved]")
        onStateChanged?.invoke()
    }

    fun addPreset(presetsInner: JPanel) {
        val name = Messages.showInputDialog(project,
            Strings.presetNameRule,
            Strings.addPresetDialog, null, "", newNameValidator())?.trim()
        if (name.isNullOrEmpty()) return
        val template = service.presets.firstOrNull()
        val newPreset = Preset(
            name = name,
            main = name,
            pull = true,
            submodules = template?.submodules ?: emptyMap(),
        )
        val root = gitRoot() ?: return
        addEditorRow(root, newPreset, presetsInner)
        presetsInner.parent?.revalidate()
        presetsInner.parent?.repaint()
        saveAll()
        log("[added] $name (展开后可编辑各子模块分支)")
    }

    fun addPresetFromCurrent(presetsInner: JPanel) {
        val root = gitRoot() ?: return
        service.scope.launch(Dispatchers.Default) {
            val rootFile = root.toFile()
            data class ProbeResult(
                val mainBranch: String?,
                val submodules: LinkedHashMap<String, String>,
                val skipped: List<String>,
            )
            val result = com.submodule.branchswitcher.TaskBridge.runModal(
                project, "Reading current branches", false
            ) { indicator ->
                indicator.isIndeterminate = true
                indicator.text = "main repo"
                val mb = service.gitClient.currentBranch(rootFile)
                val subs = LinkedHashMap<String, String>()
                val skipped = mutableListOf<String>()
                service.gitClient.listSubmodulePaths(rootFile).forEach { path ->
                    indicator.text = path
                    val dir = root.resolve(path).toFile()
                    if (!dir.exists() || (!dir.resolve(".git").exists() && !File(dir, ".git").isFile)) {
                        skipped += "$path (未 init)"
                        return@forEach
                    }
                    val br = service.gitClient.currentBranch(dir)
                    if (br.isNullOrEmpty()) {
                        skipped += "$path (detached)"
                        return@forEach
                    }
                    subs[path] = br
                }
                ProbeResult(mb, subs, skipped)
            }
            // Resumed after modal closes
            SwingUtilities.invokeLater {
                val mb = result.mainBranch
                if (mb.isNullOrEmpty()) {
                    Messages.showWarningDialog(project,
                        Strings.detachedHeadWarn, Strings.pluginTitle)
                    return@invokeLater
                }
                val name = Messages.showInputDialog(project,
                    Strings.presetNameRule,
                    Strings.fromCurrentDialog, null, mb, newNameValidator())?.trim()
                if (name.isNullOrEmpty()) return@invokeLater
                val newPreset = Preset(
                    name = name,
                    main = mb,
                    pull = true,
                    submodules = result.submodules,
                )
                addEditorRow(root, newPreset, presetsInner)
                presetsInner.parent?.revalidate()
                presetsInner.parent?.repaint()
                saveAll()
                log("[added from current] $name -> 主仓=$mb, ${result.submodules.size} 个子模块")
                if (result.skipped.isNotEmpty()) {
                    log("[skipped] ${result.skipped.joinToString(", ")}")
                }
                onStateChanged?.invoke()
            }
        }
    }

    fun openConfig() {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        val file = PresetLoader.resolveFile(base)
        if (file == null) {
            Messages.showWarningDialog(project,
                "${Strings.noPresetFile}\n$base/.idea/${PresetLoader.IDEA_FILE_NAME}",
                Strings.pluginTitle)
            return
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString())
        if (vf != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    fun exportPresets() {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(com.submodule.branchswitcher.model.PresetFile(editors.map { it.currentPreset() }))
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(json), null)
        log("[exported] ${editors.size} preset(s) 已复制到剪贴板")
        Notifier.info(project, Strings.exportComplete, "${editors.size} 个预设已复制到剪贴板")
    }

    fun importPresets(presetsContainer: JPanel) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, Strings.importEmpty, Strings.importDialog)
                return
            }
            val trimmed = text.trim()
            val gson = com.google.gson.Gson()
            val imported = if (trimmed.startsWith("[")) {
                val presets = gson.fromJson(trimmed, Array<Preset>::class.java)
                com.submodule.branchswitcher.model.PresetFile(presets.toList())
            } else {
                gson.fromJson(trimmed, com.submodule.branchswitcher.model.PresetFile::class.java)
            }
            if (imported == null || imported.presets.isEmpty()) {
                Messages.showWarningDialog(project, Strings.importInvalid, Strings.importDialog)
                return
            }
            val root = gitRoot() ?: return
            val presetsInner = (presetsContainer.getComponent(0) as? JPanel) ?: return
            imported.presets.forEach { preset ->
                if (editors.any { it.currentPreset().name == preset.name }) {
                    log("[import] skip ${preset.name} — 名字冲突")
                    return@forEach
                }
                addEditorRow(root, preset, presetsInner)
            }
            presetsContainer.revalidate()
            presetsContainer.repaint()
            saveAll()
            log("[imported] ${imported.presets.size} preset(s) from clipboard")
            Notifier.info(project, Strings.importComplete, "从剪贴板导入了 ${imported.presets.size} 个预设")
        } catch (e: Exception) {
            log("[import] error: ${e.message}")
            Messages.showWarningDialog(project, "${Strings.importFailed}: ${e.message}", Strings.importDialog)
        }
    }

    private fun newNameValidator(): InputValidator = object : InputValidator {
        override fun checkInput(input: String?): Boolean {
            val n = input?.trim().orEmpty()
            if (n.isEmpty()) return false
            return editors.none { it.currentPreset().name == n }
        }
        override fun canClose(input: String?): Boolean = checkInput(input)
    }

    private fun createEmptyState(parent: JPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40, 16, 40, 16)
            alignmentX = JPanel.CENTER_ALIGNMENT
            val hint = JLabel(Strings.noPresets).apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            val subHint = JLabel(Strings.noPresetsHint).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            add(hint)
            add(Box.createVerticalStrut(12))
            add(subHint)
            add(Box.createVerticalStrut(20))
            val cta = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
            cta.alignmentX = JPanel.CENTER_ALIGNMENT
            cta.add(JButton(Strings.fromCurrentCreate, AllIcons.Vcs.Branch).noFocusRing().also {
                it.addActionListener { addPresetFromCurrent(parent) }
            })
            cta.add(JButton(Strings.manualCreate, AllIcons.General.Add).noFocusRing().also {
                it.addActionListener { addPreset(parent) }
            })
            add(cta)
        }
    }
}
