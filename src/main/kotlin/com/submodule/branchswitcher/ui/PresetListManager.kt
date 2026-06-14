package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Manages the preset list: loading, saving, CRUD, import/export.
 * Owns the [editors] list and the [presetsInner] container.
 * Extracted from [BranchSwitcherPanel].
 */
class PresetListManager(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val onSwitch: (Preset) -> Unit,
    private val onDerive: (Path, Preset, String) -> Unit,
) {
    val editors = mutableListOf<PresetEditor>()
    private var emptyStatePanel: JPanel? = null
    var onStateChanged: (() -> Unit)? = null

    // ── Search / filter ────────────────────────────────────────────
    private val searchField = JBTextField().apply {
        putClientProperty("JTextField.placeholderText", Bundle.msg("label.search.presets"))
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { applyFilter() }
            override fun removeUpdate(e: DocumentEvent) { applyFilter() }
            override fun changedUpdate(e: DocumentEvent) { applyFilter() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) { text = "" }
            }
        })
    }

    private val clearButton = jButton(icon = AllIcons.Actions.Close) {
        toolTipText = Bundle.msg("label.clear")
        preferredSize = java.awt.Dimension(JBUI.scale(22), JBUI.scale(22))
        isVisible = false
        addActionListener {
            searchField.text = ""
        }
    }

    fun createSearchRow(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchField, BorderLayout.CENTER)
            add(clearButton, BorderLayout.EAST)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, searchField.preferredSize.height + JBUI.scale(4))
        }
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        clearButton.isVisible = query.isNotEmpty()
        for (editor in editors) {
            val match = query.isEmpty() || editor.currentPreset().name.lowercase().contains(query)
            val wrapper = editor.parent
            if (wrapper != null) {
                wrapper.isVisible = match
            }
        }
        searchField.rootPane?.contentPane?.revalidate()
        searchField.rootPane?.contentPane?.repaint()
    }

    /** Re-applies the current filter (call after presets are added/removed). */
    private fun reapplyFilter() {
        if (searchField.text.isNotEmpty()) applyFilter()
    }

    /** Load presets from file and rebuild the editor list. */
    fun reload(presetsInner: JPanel) {
        editors.clear()
        presetsInner.removeAll()
        service.loadPresets()
            .onSuccess { (file, parsed) ->
                val root = gitRoot()
                if (root == null) {
                    log.error("git root not found")
                    Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
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
                log.debug("loaded ${parsed.presets.size} preset(s) from $file")
                presetsInner.revalidate()
                presetsInner.repaint()
                reapplyFilter()
                onStateChanged?.invoke()
            }
            .onFailure {
                log.error("${it.message}")
                Notifier.error(project, Bundle.msg("preset.load.failed"), it.message ?: "unknown error")
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
            onSave = { updated -> saveAll(editor, updated) },
            onDelete = { deleteEditor(editor, presetsInner) },
            onDerive = { branchName -> onDerive(root, editor.currentPreset(), branchName) },
            nameValidator = { newName -> editors.none { it !== editor && it.currentPreset().name == newName } },
            onNameChanged = { reapplyFilter() },
            gitClient = service.gitClient,
            scope = service.scope,
            globalLabels = GlobalOptionLabels(
                dirty = {
                    when (service.dirtyAction) {
                        com.submodule.branchswitcher.model.DirtyAction.Stash -> Bundle.msg("option.dirty.stash")
                        com.submodule.branchswitcher.model.DirtyAction.Skip -> Bundle.msg("option.dirty.skip")
                        com.submodule.branchswitcher.model.DirtyAction.Force -> Bundle.msg("option.dirty.force")
                    }
                },
                pull = { if (service.pullAfterSwitch) Bundle.msg("option.override.on") else Bundle.msg("option.override.off") },
                fetch = { if (service.fetchFirst) Bundle.msg("option.override.on") else Bundle.msg("option.override.off") },
            ),
        )
        editors.add(editor)
        val wrapper = CompactHeightPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JPanel.LEFT_ALIGNMENT
            add(editor, BorderLayout.CENTER)
            add(Box.createVerticalStrut(4), BorderLayout.SOUTH)
        }
        presetsInner.add(wrapper)
    }

    fun deleteEditor(editor: PresetEditor, presetsInner: JPanel) {
        val name = editor.currentPreset().name
        val confirm = Messages.showYesNoDialog(
            project,
            Bundle.msg("label.delete.confirm.msg", name),
            Bundle.msg("label.delete.confirm.title"),
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        editors.remove(editor)
        // Remove wrapper panel (editor + strut) to avoid orphaned struts
        val wrapper = editor.parent
        if (wrapper != null && wrapper !== presetsInner) {
            presetsInner.remove(wrapper)
        } else {
            presetsInner.remove(editor)
        }
        saveAll()
        if (editors.isEmpty()) {
            val panel = createEmptyState(presetsInner)
            emptyStatePanel = panel
            presetsInner.add(panel)
        }
        presetsInner.revalidate()
        presetsInner.repaint()
        reapplyFilter()
        log.debug("[deleted] $name")
    }

    fun saveAll(pendingEditor: PresetEditor? = null, pendingPreset: Preset? = null) {
        val presets = editors.map {
            if (it === pendingEditor && pendingPreset != null) pendingPreset else it.currentPreset()
        }
        service.savePresets(presets)
        log.debug("[saved]")
        onStateChanged?.invoke()
    }

    fun addPreset(presetsInner: JPanel) {
        val name = Messages.showInputDialog(project,
            Bundle.msg("dialog.preset.name.rule"),
            Bundle.msg("dialog.add.preset"), null, "", newNameValidator())?.trim()
        if (name.isNullOrEmpty()) return
        // Guard: ensure presets are loaded before using one as template
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
        addEditorRow(root, newPreset, presetsInner)
        presetsInner.parent?.revalidate()
        presetsInner.parent?.repaint()
        saveAll()
        reapplyFilter()
        log.debug("[added] $name (展开后可编辑各子模块分支)")
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
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val mb = result.mainBranch
                if (mb.isNullOrEmpty()) {
                    Messages.showWarningDialog(project,
                        Bundle.msg("dialog.detached.head"), Bundle.msg("plugin.title"))
                    return@invokeLater
                }
                val name = Messages.showInputDialog(project,
                    Bundle.msg("dialog.preset.name.rule"),
                    Bundle.msg("dialog.from.current"), null, mb, newNameValidator())?.trim()
                if (name.isNullOrEmpty()) return@invokeLater
                val newPreset = Preset(
                    name = name,
                    main = mb,
                    submodules = result.submodules,
                )
                addEditorRow(root, newPreset, presetsInner)
                presetsInner.parent?.revalidate()
                presetsInner.parent?.repaint()
                saveAll()
                reapplyFilter()
                log.debug("[added from current] $name -> 主仓=$mb, ${result.submodules.size} 个子模块")
                if (result.skipped.isNotEmpty()) {
                    log.debug("[skipped] ${result.skipped.joinToString(", ")}")
                }
                onStateChanged?.invoke()
            })
        }
    }

    fun refreshAllGlobalLabels() {
        editors.forEach { it.refreshGlobalLabels() }
    }

    fun openConfig() {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        val file = PresetLoader.resolveFile(base)
        if (file == null) {
            Messages.showWarningDialog(project,
                "${Bundle.msg("preset.file.not.found")}\n$base/.idea/${PresetLoader.IDEA_FILE_NAME}",
                Bundle.msg("plugin.title"))
            return
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString())
        if (vf != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    fun exportPresets() {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(com.submodule.branchswitcher.model.PresetFile(editors.map { it.currentPreset() }))
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(json), null)
            log.debug("[exported] ${editors.size} preset(s) 已复制到剪贴板")
            Notifier.info(project, Bundle.msg("notify.export.complete"), Bundle.msg("notify.exported", editors.size))
        } catch (e: Exception) {
            log.error("[export] failed: ${e.message}")
            Notifier.error(project, Bundle.msg("notify.export.complete"), "${Bundle.msg("dialog.import.failed")}: ${e.message}")
        }
    }

    fun importPresets(presetsContainer: JPanel) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, Bundle.msg("dialog.import.empty"), Bundle.msg("dialog.import"))
                return
            }
            val result = parsePresetImport(text, editors.map { it.currentPreset().name }.toSet())
            if (result.presets.isEmpty()) {
                Messages.showWarningDialog(project, Bundle.msg("dialog.import.invalid"), Bundle.msg("dialog.import"))
                return
            }
            if (result.invalidNames.isNotEmpty()) {
                log.debug("[import] skipped ${result.invalidNames.size} invalid: ${result.invalidNames.joinToString(", ")}")
            }
            if (result.conflictingNames.isNotEmpty()) {
                log.debug("[import] skipped ${result.conflictingNames.size} conflicts: ${result.conflictingNames.joinToString(", ")}")
            }
            val root = gitRoot() ?: return
            val presetsInner = (presetsContainer.getComponent(0) as? JPanel) ?: return
            result.presets.forEach { preset ->
                addEditorRow(root, preset, presetsInner)
            }
            presetsContainer.revalidate()
            presetsContainer.repaint()
            saveAll()
            reapplyFilter()
            log.debug("[imported] ${result.presets.size} preset(s) from clipboard")
            Notifier.info(project, Bundle.msg("notify.import.complete"), Bundle.msg("notify.imported", result.presets.size))
        } catch (e: Exception) {
            log.error("[import] error: ${e.message}")
            Messages.showWarningDialog(project, "${Bundle.msg("dialog.import.failed")}: ${e.message}", Bundle.msg("dialog.import"))
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
            border = JBUI.Borders.empty(32, 16, 32, 16)
            alignmentX = JPanel.CENTER_ALIGNMENT
            // Large icon as visual anchor
            val iconLabel = JLabel(AllIcons.Vcs.Branch).apply {
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            add(iconLabel)
            add(Box.createVerticalStrut(16))
            val hint = JLabel(Bundle.msg("empty.no.presets")).apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            val subHint = JLabel(Bundle.msg("empty.hint")).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            add(hint)
            add(Box.createVerticalStrut(8))
            add(subHint)
            add(Box.createVerticalStrut(20))
            val cta = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
            cta.alignmentX = JPanel.CENTER_ALIGNMENT
            cta.add(jButton(Bundle.msg("empty.from.current"), AllIcons.Vcs.Branch) {
                addActionListener { addPresetFromCurrent(parent) }
            })
            cta.add(jButton(Bundle.msg("empty.manual"), AllIcons.General.Add) {
                addActionListener { addPreset(parent) }
            })
            add(cta)
            add(Box.createVerticalStrut(24))
            // First-run quick guide
            val guide = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = JPanel.CENTER_ALIGNMENT
                border = JBUI.Borders.compound(
                    javax.swing.BorderFactory.createLineBorder(JBColor.border()),
                    JBUI.Borders.empty(12, 16, 12, 16),
                )
            }
            val guideTitle = JLabel(Bundle.msg("empty.guide.title")).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            guide.add(guideTitle)
            guide.add(Box.createVerticalStrut(8))
            listOf(
                "1" to Bundle.msg("empty.guide.step1"),
                "2" to Bundle.msg("empty.guide.step2"),
                "3" to Bundle.msg("empty.guide.step3"),
            ).forEach { (num, text) ->
                val step = JLabel("$num. $text").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor.GRAY
                    alignmentX = JPanel.CENTER_ALIGNMENT
                }
                guide.add(step)
                guide.add(Box.createVerticalStrut(2))
            }
            guide.add(Box.createVerticalStrut(4))
            val tip = JLabel(Bundle.msg("empty.guide.tip")).apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBColor.GRAY
                alignmentX = JPanel.CENTER_ALIGNMENT
            }
            guide.add(tip)
            add(guide)
        }
    }
}
