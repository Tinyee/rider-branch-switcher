package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JProgressBar
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Main tool window panel: preset list, switch controls, options, and log.
 *
 * Layout (BorderLayout):
 * - NORTH: title + current branch label + progress bar + scrollable preset list
 * - CENTER: colored log (JTextPane with [append])
 * - SOUTH: switch options (dirty/fetch/pull/timeout) + action buttons
 *
 * Thread safety: uses [service.scope] for background git probes;
 * [detectCurrentState] uses generation-based stale detection.
 */
class BranchSwitcherPanel(
    private val project: Project,
    private val service: BranchSwitcherService,
) : JPanel(BorderLayout()) {

    private val editors = mutableListOf<PresetEditor>()
    private var emptyStatePanel: JPanel? = null
    private val currentBranchLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        border = JBUI.Borders.empty(0, 0, 2, 0)
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = JPanel(BorderLayout()).apply {
        add(presetsInner, BorderLayout.NORTH)
    }

    private val dirtyCombo = JComboBox(arrayOf(Strings.dirtyStash, Strings.dirtySkip, Strings.dirtyForce))
    private val timeoutCombo = JComboBox(arrayOf("30s", "60s", "120s", "300s"))
    private val pullCheck = JCheckBox(Strings.pullAfter, true)
    private val fetchCheck = JCheckBox(Strings.fetchBefore, true)

    private val progressBar = JProgressBar().apply {
        isStringPainted = true
        isVisible = false
    }

    private val log = javax.swing.JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }

    init {
        border = JBUI.Borders.empty(8, 8, 8, 8)
        minimumSize = Dimension(JBUI.scale(280), minimumSize.height)

        val title = JLabel(Strings.pluginTitle).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }

        val presetsScroll = JBScrollPane(presetsContainer).apply {
            preferredSize = Dimension(0, JBUI.scale(300))
        }
        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton(Strings.addPreset, AllIcons.General.Add).noFocusRing()
                .also { it.addActionListener { addPreset() } })
            add(JButton(Strings.fromCurrent, AllIcons.Vcs.Branch).noFocusRing().also {
                it.toolTipText = Strings.fromCurrentTip
                it.addActionListener { addPresetFromCurrent() }
            })
        }
        val presetsBlock = JPanel(BorderLayout()).apply {
            add(presetsScroll, BorderLayout.CENTER)
            add(addPanel, BorderLayout.SOUTH)
        }

        val statusRow = JPanel(BorderLayout()).apply {
            add(currentBranchLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.SOUTH)
        }
        val north = JPanel(BorderLayout())
        north.add(title, BorderLayout.NORTH)
        north.add(statusRow, BorderLayout.SOUTH)
        north.add(presetsBlock, BorderLayout.CENTER)

        val logScroll = JBScrollPane(log).apply {
            preferredSize = Dimension(0, JBUI.scale(30))
        }

        val optsRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel(Strings.dirtyWorkingTree))
            add(dirtyCombo)
            add(JLabel(Strings.timeoutSeconds))
            add(timeoutCombo)
        }
        val optsRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(fetchCheck)
            add(pullCheck)
            add(JCheckBox(Strings.confirmInit).apply {
                isSelected = service.confirmBeforeInit
                addItemListener { service.confirmBeforeInit = isSelected }
            })
        }
        val opts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(4, 0, 4, 0),
            )
            optsRow1.alignmentX = LEFT_ALIGNMENT
            optsRow2.alignmentX = LEFT_ALIGNMENT
            add(optsRow1)
            add(optsRow2)
        }

        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
        val btnRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        btnRow1.add(JButton(Strings.reloadPresets, AllIcons.Actions.Refresh).noFocusRing()
            .also { it.addActionListener { reload() } })
        btnRow1.add(JButton(Strings.openPresetFile, AllIcons.Actions.EditSource).noFocusRing()
            .also { it.addActionListener { openConfig() } })
        btnRow1.add(Box.createHorizontalStrut(12))
        btnRow1.add(JButton(Strings.clearLog, AllIcons.Actions.GC).noFocusRing()
            .also { it.addActionListener { log.text = "" } })
        buttons.add(btnRow1)
        val btnRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        btnRow2.add(JButton(Strings.undoSwitch, AllIcons.Actions.Rollback).noFocusRing().also {
            it.toolTipText = Strings.undoTip
            it.addActionListener { undoLastSwitch() }
        })
        btnRow2.add(Box.createHorizontalStrut(12))
        btnRow2.add(JButton(Strings.exportPresets, AllIcons.Actions.MenuSaveall).noFocusRing()
            .also { it.addActionListener { exportPresets() } })
        btnRow2.add(JButton(Strings.importPresets, AllIcons.Actions.MenuSaveall).noFocusRing()
            .also { it.addActionListener { importPresets() } })
        buttons.add(btnRow2)

        val south = JPanel(BorderLayout())
        south.add(opts, BorderLayout.NORTH)
        south.add(buttons, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(logScroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        reload()

        // Restore persisted switch options
        dirtyCombo.selectedIndex = when (service.dirtyAction) {
            DirtyAction.Stash -> 0
            DirtyAction.Skip -> 1
            DirtyAction.Force -> 2
        }
        pullCheck.isSelected = service.pullAfterSwitch
        fetchCheck.isSelected = service.fetchFirst
        // Persist options on change
        dirtyCombo.addItemListener {
            service.dirtyAction = when (dirtyCombo.selectedIndex) {
                1 -> DirtyAction.Skip
                2 -> DirtyAction.Force
                else -> DirtyAction.Stash
            }
        }
        pullCheck.addItemListener { service.pullAfterSwitch = pullCheck.isSelected }
        fetchCheck.addItemListener { service.fetchFirst = fetchCheck.isSelected }
        // Restore timeout
        timeoutCombo.selectedIndex = when (service.timeoutSeconds) {
            30 -> 0
            120 -> 2
            300 -> 3
            else -> {
                service.timeoutSeconds = 60
                1
            }
        }
        timeoutCombo.addItemListener {
            service.timeoutSeconds = when (timeoutCombo.selectedIndex) {
                0 -> 30
                2 -> 120
                3 -> 300
                else -> 60
            }
        }

        // Subscribe to switch events (e.g., from shortcut action)
        val connection = project.messageBus.connect(service)
        connection.subscribe(BranchSwitchListener.TOPIC, object : BranchSwitchListener {
            override fun onBranchSwitched() {
                SwingUtilities.invokeLater { detectCurrentState() }
            }
        })

        // Re-detect current state only when tool window transitions from hidden→visible
        addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
                SwingUtilities.invokeLater { detectCurrentState() }
            }
        }

        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                SwingUtilities.invokeLater {
                    val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    val owner = kfm.focusOwner
                    if (owner != null && SwingUtilities.isDescendingFrom(owner, this@BranchSwitcherPanel)) {
                        kfm.clearGlobalFocusOwner()
                    }
                }
            }
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {}
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }

    private fun ideBase(): Path? = project.basePath?.let { Paths.get(it) }

    private fun gitRoot(): Path? {
        val base = ideBase() ?: return null
        var cur: Path? = base
        while (cur != null) {
            val dotGit = cur.resolve(".git")
            if (java.nio.file.Files.exists(dotGit)) {
                if (!java.nio.file.Files.isDirectory(dotGit) && !worktreeInfoLogged) {
                    worktreeInfoLogged = true
                    append("[info] detected git worktree — .git is a file, not a directory")
                }
                return cur
            }
            cur = cur.parent
        }
        return base
    }

    private fun reload() {
        editors.clear()
        presetsInner.removeAll()
        service.loadPresets()
            .onSuccess { (file, parsed) ->
                val root = gitRoot()
                if (root == null) {
                    append("[error] git root not found")
                    Notifier.error(project, "Branch Switcher", "未找到 git 根目录")
                    return@onSuccess
                }
                emptyStatePanel = null
                if (parsed.presets.isEmpty()) {
                    val panel = createEmptyState()
                    emptyStatePanel = panel
                    presetsInner.add(panel)
                } else {
                    parsed.presets.forEach { addEditorRow(root, it) }
                }
                append("loaded ${parsed.presets.size} preset(s) from $file")
                presetsInner.revalidate()
                presetsInner.repaint()
                detectCurrentState()
            }
            .onFailure {
                append("[error] ${it.message}")
                Notifier.error(project, "预设加载失败", it.message ?: "unknown error")
            }
    }

    /**
     * Probes all editor paths (main + submodules) in the background, then updates UI.
     * Uses generation-based stale detection: if a newer [detectCurrentState] call starts
     * before this one finishes, the stale result is discarded via [service.getDetectGen].
     */
    private fun detectCurrentState() {
        val root = gitRoot() ?: return
        val paths = LinkedHashSet<String>().apply { add(".") }
        editors.forEach { paths.addAll(it.currentPreset().submodules.keys) }
        val snapshot = paths.toList()
        val pinnedEditors = editors.toList()
        val gen = service.nextDetectGen()
        service.scope.launch {
            val branches = HashMap<String, String?>(snapshot.size)
            for (p in snapshot) {
                val dir = if (p == ".") root.toFile() else root.resolve(p).toFile()
                branches[p] = if (dir.exists()) service.gitClient.currentBranch(dir) else null
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (gen != service.getDetectGen()) return@invokeLater
                pinnedEditors.forEach { editor ->
                    if (editor in editors) editor.applyCurrentState(branches)
                }
                presetsInner.revalidate()
                presetsInner.repaint()
                logDetected(editors.toList(), branches)
            }
        }
    }

    private var lastMatchedPreset: Preset? = null
    private var worktreeInfoLogged = false

    private fun undoLastSwitch() {
        val allPresets = editors.map { it.currentPreset() }
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(project, Strings.noUndoHistory, Strings.undoDialog)
            return
        }
        // Find the preset that was active before the last switch
        val previousName = history[1].presetName
        val preset = allPresets.find { it.name == previousName }
        if (preset == null) {
            Messages.showInfoMessage(project, "${Strings.undoNotFound}「$previousName」", Strings.undoDialog)
            return
        }
        runSwitch(preset)
    }

    private fun logDetected(eds: List<PresetEditor>, branches: Map<String, String?>) {
        val main = branches["."] ?: "(detached)"
        val matched = eds.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "${Strings.mainLabel} $main"
        append("[detect] main=$main, matched=${matched ?: "<none>"}")
    }

    private fun addEditorRow(root: Path, preset: Preset) {
        emptyStatePanel?.let { presetsInner.remove(it); emptyStatePanel = null }
        lateinit var editor: PresetEditor
        editor = PresetEditor(
            gitRoot = root,
            initial = preset,
            log = ::append,
            onSwitch = ::runSwitch,
            onSave = { saveAll() },
            onDelete = { deleteEditor(editor) },
            onDerive = { branchName -> derivePresetBranch(root, preset, branchName) },
            gitClient = service.gitClient,
            scope = service.scope,
        )
        editors.add(editor)
        presetsInner.add(editor)
        presetsInner.add(Box.createVerticalStrut(4))
    }

    private fun deleteEditor(editor: PresetEditor) {
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
            val panel = createEmptyState()
            emptyStatePanel = panel
            presetsInner.add(panel)
        }
        presetsInner.revalidate()
        presetsInner.repaint()
        append("[deleted] $name")
    }

    private fun saveAll() {
        service.savePresets(editors.map { it.currentPreset() })
        append("[saved]")
        detectCurrentState()
    }

    private fun newNameValidator(): InputValidator = object : InputValidator {
        override fun checkInput(input: String?): Boolean {
            val n = input?.trim().orEmpty()
            if (n.isEmpty()) return false
            return editors.none { it.currentPreset().name == n }
        }
        override fun canClose(input: String?): Boolean = checkInput(input)
    }

    private fun addPreset() {
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
        addEditorRow(root, newPreset)
        presetsContainer.revalidate()
        presetsContainer.repaint()
        saveAll()
        append("[added] $name (展开后可编辑各子模块分支)")
    }

    private fun addPresetFromCurrent() {
        val root = gitRoot() ?: return
        val rootFile = root.toFile()
        val task = object : Task.Modal(project, "Reading current branches", false) {
            var mainBranch: String? = null
            val submodules = LinkedHashMap<String, String>()
            val skipped = mutableListOf<String>()
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "main repo"
                mainBranch = service.gitClient.currentBranch(rootFile)
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
                    submodules[path] = br
                }
            }
            override fun onSuccess() {
                val mb = mainBranch
                if (mb.isNullOrEmpty()) {
                    Messages.showWarningDialog(project,
                        Strings.detachedHeadWarn,
                        Strings.pluginTitle)
                    return
                }
                val name = Messages.showInputDialog(project,
                    Strings.presetNameRule,
                    Strings.fromCurrentDialog, null, mb, newNameValidator())?.trim()
                if (name.isNullOrEmpty()) return
                val newPreset = Preset(
                    name = name,
                    main = mb,
                    pull = true,
                    submodules = submodules,
                )
                addEditorRow(root, newPreset)
                presetsContainer.revalidate()
                presetsContainer.repaint()
                saveAll()
                append("[added from current] $name -> 主仓=$mb, ${submodules.size} 个子模块")
                if (skipped.isNotEmpty()) {
                    append("[skipped] ${skipped.joinToString(", ")}")
                }
                detectCurrentState()
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    private fun openConfig() {
        val base = ideBase() ?: return
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

    private fun runSwitch(preset: Preset) {
        val root = gitRoot() ?: return

        val preflightTask = object : Task.Modal(project, "Inspecting branches", true) {
            var result: List<PreflightRow> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                result = SwitchPreflight(service.gitClient).probe(root, preset, indicator)
            }
            override fun onSuccess() {
                if (!SwitchPreviewDialog.showAndConfirm(project, preset, result)) return
                executeSwitch(root, preset)
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(preflightTask)
    }

    /**
     * Runs the full switch pipeline in a [Task.Backgroundable].
     * Wraps the [ProgressIndicator] to sync fraction/text to the panel [progressBar].
     * On failure, shows a rollback-action notification if a checkpoint was recorded.
     */
    private fun executeSwitch(root: Path, preset: Preset) {
        val dirty = when (dirtyCombo.selectedIndex) {
            1 -> DirtyAction.Skip
            2 -> DirtyAction.Force
            else -> DirtyAction.Stash
        }
        val opts = SwitchOptions(
            dirty = dirty,
            pull = pullCheck.isSelected,
            fetchFirst = fetchCheck.isSelected,
            confirmBeforeInit = service.confirmBeforeInit,
        )

        setSwitchInProgress(true)
        val task = object : Task.Backgroundable(project, "Switching branches", true) {
            var ok = false
            private var rollbackExecutor: SwitchExecutor? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val wrapped = object : ProgressIndicator by indicator {
                    override fun setFraction(fraction: Double) {
                        indicator.fraction = fraction
                        SwingUtilities.invokeLater {
                            progressBar.isIndeterminate = false
                            progressBar.value = (fraction * 100).toInt()
                        }
                    }
                    override fun setText2(text: String?) {
                        indicator.text2 = text
                        SwingUtilities.invokeLater { progressBar.string = text ?: "Switching..." }
                    }
                }
                val executor = SwitchExecutor(root, ::append, service.gitClient, wrapped)
                rollbackExecutor = executor
                ok = executor.execute(preset, opts)
            }
            override fun onFinished() {
                setSwitchInProgress(false)
                service.addHistory(preset.name)
                if (ok) {
                    Notifier.info(project, Strings.switchComplete, Strings.switchCompleteMsg.format(preset.name))
                } else {
                    val executor = rollbackExecutor
                    if (executor?.getCheckpoint() != null) {
                        Notifier.rollbackAction(project, Strings.switchFailed,
                            Strings.switchPartialMsg.format(preset.name) + "。可回滚到切换前的 HEAD。") {
                            rollbackSwitch(executor)
                        }
                    } else {
                        Notifier.error(project, Strings.switchFailed,
                            Strings.switchPartialMsg.format(preset.name))
                    }
                }
                refreshVcs(root, preset)
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    private fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        val task = object : Task.Backgroundable(project, "Creating branch $branchName", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Creating branch on all repos..."
                val targets = preset.targets()
                for ((idx, target) in targets.withIndex()) {
                    indicator.fraction = idx.toDouble() / targets.size
                    indicator.text2 = if (target.path == ".") root.fileName.toString() else target.path
                    val dir = if (target.path == ".") root.toFile() else root.resolve(target.path).toFile()
                    if (!dir.exists() || !java.io.File(dir, ".git").exists()) {
                        append("[derive] skip ${target.path} — not a git repo")
                        continue
                    }
                    val r = service.gitClient.checkoutNewBranch(dir, branchName)
                    if (r.ok) {
                        append("[derive] ${target.path}: created branch $branchName")
                    } else {
                        append("[derive] ${target.path}: FAILED — ${r.stderr.lines().firstOrNull() ?: ""}")
                    }
                }
            }
            override fun onFinished() {
                detectCurrentState()
                Notifier.info(project, Strings.deriveComplete, "分支 $branchName 已创建，共 ${preset.targets().size} 个仓库")
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun rollbackSwitch(executor: SwitchExecutor) {
        val task = object : Task.Backgroundable(project, "Rolling back", true) {
            var rollbackOk = false
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                rollbackOk = executor.rollback()
            }
            override fun onFinished() {
                val root = gitRoot() ?: return
                val submodulePaths = executor.getCheckpoint()?.keys?.filter { it != "." } ?: emptyList()
                refreshVcs(root, com.submodule.branchswitcher.model.Preset("_rollback", "", submodulePaths.associateWith { "" }))
                detectCurrentState()
                if (!rollbackOk) {
                    Notifier.warn(project, Strings.rollbackPartial,
                        Strings.rollbackPartialMsg)
                }
            }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    private fun refreshVcs(root: Path, preset: Preset) {
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val dirs = mutableListOf(root.toFile())
            preset.submodules.keys.forEach { dirs += root.resolve(it).toFile() }
            try {
                val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                val mgr = git4idea.repo.GitRepositoryManager.getInstance(project)
                for (dir in dirs) {
                    val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
                    vf.refresh(false, true)
                    mgr.getRepositoryForRoot(vf)?.update()
                }
                app.invokeLater {
                    append("[vcs] refreshed ${dirs.size} repo(s)")
                    detectCurrentState()
                }
            } catch (t: Throwable) {
                app.invokeLater {
                    append("[error] refreshVcs failed: ${t.javaClass.simpleName}: ${t.message}")
                    Notifier.warn(project, "VCS 刷新失败",
                        "${t.javaClass.simpleName}: ${t.message}")
                    detectCurrentState()
                }
            }
        }
    }

    private fun exportPresets() {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(com.submodule.branchswitcher.model.PresetFile(editors.map { it.currentPreset() }))
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(json), null)
        append("[exported] ${editors.size} preset(s) 已复制到剪贴板")
        Notifier.info(project, Strings.exportComplete, "${editors.size} 个预设已复制到剪贴板")
    }

    private fun importPresets() {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, Strings.importEmpty, Strings.importDialog)
                return
            }
            val trimmed = text.trim()
            val gson = com.google.gson.Gson()
            // Accept both {"presets":[...]} and plain [...]
            val imported = if (trimmed.startsWith("[")) {
                val presets = gson.fromJson(trimmed, Array<com.submodule.branchswitcher.model.Preset>::class.java)
                com.submodule.branchswitcher.model.PresetFile(presets.toList())
            } else {
                gson.fromJson(trimmed, com.submodule.branchswitcher.model.PresetFile::class.java)
            }
            if (imported == null || imported.presets.isEmpty()) {
                Messages.showWarningDialog(project, Strings.importInvalid, Strings.importDialog)
                return
            }
            val root = gitRoot() ?: return
            imported.presets.forEach { preset ->
                // Check for name conflict
                if (editors.any { it.currentPreset().name == preset.name }) {
                    append("[import] skip ${preset.name} — 名字冲突")
                    return@forEach
                }
                addEditorRow(root, preset)
            }
            presetsContainer.revalidate()
            presetsContainer.repaint()
            saveAll()
            append("[imported] ${imported.presets.size} preset(s) from clipboard")
            Notifier.info(project, Strings.importComplete, "从剪贴板导入了 ${imported.presets.size} 个预设")
        } catch (e: Exception) {
            append("[import] error: ${e.message}")
            Messages.showWarningDialog(project, "${Strings.importFailed}: ${e.message}", Strings.importDialog)
        }
    }

    private fun setSwitchInProgress(inProgress: Boolean) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("SubmoduleBranches") ?: return
        if (inProgress) {
            tw.setIcon(AllIcons.Process.Step_4)
            progressBar.isVisible = true
            progressBar.isIndeterminate = true
            progressBar.string = "Switching..."
        } else {
            tw.setIcon(AllIcons.Vcs.Branch)
            progressBar.isVisible = false
        }
    }

    private fun createEmptyState(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40, 16, 40, 16)
            alignmentX = CENTER_ALIGNMENT
            val hint = JLabel(Strings.noPresets).apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = CENTER_ALIGNMENT
            }
            val subHint = JLabel(Strings.noPresetsHint).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.GRAY
                alignmentX = CENTER_ALIGNMENT
            }
            add(hint)
            add(Box.createVerticalStrut(12))
            add(subHint)
            add(Box.createVerticalStrut(20))
            val cta = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
            cta.alignmentX = CENTER_ALIGNMENT
            cta.add(JButton(Strings.fromCurrentCreate, AllIcons.Vcs.Branch).noFocusRing().also {
                it.addActionListener { addPresetFromCurrent() }
            })
            cta.add(JButton(Strings.manualCreate, AllIcons.General.Add).noFocusRing().also {
                it.addActionListener { addPreset() }
            })
            add(cta)
        }
    }

    /**
     * Appends a line to the log with color coding:
     * - ERROR/FAIL/FATAL → red
     * - WARN → orange
     * - DETECT/SAVED/ADDED/EXPORTED/IMPORTED → gray
     * - DERIVE/ROLLBACK → blue
     * - default → theme foreground
     */
    private fun append(line: String) {
        SwingUtilities.invokeLater {
            val doc = log.styledDocument
            val color = when {
                line.contains("[error]") || line.contains("[fail]") || line.contains("[fatal]") || line.startsWith("ERROR") -> JBColor.RED
                line.contains("[warn]") || line.startsWith("WARN") -> JBColor(0xE07B00, 0xFFA726)
                line.contains("[detect]") || line.contains("[saved]") || line.contains("[added]") || line.contains("[exported]") || line.contains("[imported]") -> JBColor.GRAY
                line.contains("[derive]") || line.contains("[rollback]") -> JBColor(0x1565C0, 0x42A5F5)
                else -> log.foreground
            }
            val attrs = javax.swing.text.SimpleAttributeSet()
            javax.swing.text.StyleConstants.setForeground(attrs, color)
            try { doc.insertString(doc.length, line + "\n", attrs) } catch (_: Exception) {}
            log.caretPosition = doc.length
        }
    }
}
