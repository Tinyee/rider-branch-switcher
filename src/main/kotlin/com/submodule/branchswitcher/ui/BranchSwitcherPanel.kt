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
import com.submodule.branchswitcher.BranchSwitchListener
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

class BranchSwitcherPanel(
    private val project: Project,
    private val service: BranchSwitcherService,
) : JPanel(BorderLayout()) {

    private val editors = mutableListOf<PresetEditor>()
    private var emptyStatePanel: JPanel? = null
    private val currentBranchLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = JPanel(BorderLayout()).apply {
        add(presetsInner, BorderLayout.NORTH)
    }

    private val dirtyCombo = JComboBox(arrayOf("Stash 脏改动", "脏则跳过", "强制(危险)"))
    private val timeoutCombo = JComboBox(arrayOf("30s", "60s", "120s", "300s"))
    private val pullCheck = JCheckBox("切换后 pull --ff-only", true)
    private val fetchCheck = JCheckBox("切换前 fetch", true)

    private val log = javax.swing.JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val title = JLabel("Submodule Branch Switcher").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }

        val presetsScroll = JBScrollPane(presetsContainer).apply {
            preferredSize = Dimension(0, 300)
        }
        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("新增预设", AllIcons.General.Add).noFocusRing()
                .also { it.addActionListener { addPreset() } })
            add(JButton("从当前状态", AllIcons.Vcs.Branch).noFocusRing().also {
                it.toolTipText = "基于主仓和已 init 子模块的当前 HEAD 分支生成预设"
                it.addActionListener { addPresetFromCurrent() }
            })
        }
        val presetsBlock = JPanel(BorderLayout()).apply {
            add(presetsScroll, BorderLayout.CENTER)
            add(addPanel, BorderLayout.SOUTH)
        }

        val north = JPanel(BorderLayout())
        north.add(title, BorderLayout.NORTH)
        north.add(currentBranchLabel, BorderLayout.SOUTH)
        north.add(presetsBlock, BorderLayout.CENTER)

        val logScroll = JBScrollPane(log).apply {
            preferredSize = Dimension(0, 30)
        }

        val optsRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("脏工作区:"))
            add(dirtyCombo)
            add(JLabel("超时:"))
            add(timeoutCombo)
        }
        val optsRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(fetchCheck)
            add(pullCheck)
            add(JCheckBox("init 前确认").apply {
                isSelected = service.confirmBeforeInit
                addItemListener { service.confirmBeforeInit = isSelected }
            })
        }
        val opts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
            )
            optsRow1.alignmentX = LEFT_ALIGNMENT
            optsRow2.alignmentX = LEFT_ALIGNMENT
            add(optsRow1)
            add(optsRow2)
        }

        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(2, 0, 4, 0)
        }
        val btnRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        btnRow1.add(JButton("重载预设", AllIcons.Actions.Refresh).noFocusRing()
            .also { it.addActionListener { reload() } })
        btnRow1.add(JButton("打开预设文件", AllIcons.Actions.EditSource).noFocusRing()
            .also { it.addActionListener { openConfig() } })
        btnRow1.add(Box.createHorizontalStrut(12))
        btnRow1.add(JButton("清空日志", AllIcons.Actions.GC).noFocusRing()
            .also { it.addActionListener { log.text = "" } })
        buttons.add(btnRow1)
        val btnRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        btnRow2.add(JButton("撤销切换", AllIcons.Actions.Rollback).noFocusRing().also {
            it.toolTipText = "撤销最近一次切换，回到切换前的分支状态"
            it.addActionListener { undoLastSwitch() }
        })
        btnRow2.add(Box.createHorizontalStrut(12))
        btnRow2.add(JButton("导出预设", AllIcons.Actions.MenuSaveall).noFocusRing()
            .also { it.addActionListener { exportPresets() } })
        btnRow2.add(JButton("导入预设", AllIcons.Actions.MenuSaveall).noFocusRing()
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
            if (java.nio.file.Files.exists(cur.resolve(".git"))) return cur
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
                pinnedEditors.forEach { it.applyCurrentState(branches) }
                presetsInner.revalidate()
                presetsInner.repaint()
                logDetected(pinnedEditors, branches)
            }
        }
    }

    private var lastMatchedPreset: Preset? = null

    private fun undoLastSwitch() {
        val allPresets = editors.map { it.currentPreset() }
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(project, "没有可撤销的切换记录", "撤销切换")
            return
        }
        // Find the preset that was active before the last switch
        val previousName = history[1].presetName
        val preset = allPresets.find { it.name == previousName }
        if (preset == null) {
            Messages.showInfoMessage(project, "找不到之前的预设「$previousName」", "撤销切换")
            return
        }
        runSwitch(preset)
    }

    private fun logDetected(eds: List<PresetEditor>, branches: Map<String, String?>) {
        val main = branches["."] ?: "(detached)"
        val matched = eds.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "主仓: $main"
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
        )
        editors.add(editor)
        presetsInner.add(editor)
        presetsInner.add(Box.createVerticalStrut(4))
    }

    private fun deleteEditor(editor: PresetEditor) {
        val name = editor.currentPreset().name
        val confirm = Messages.showYesNoDialog(
            project,
            "删除预设「$name」?\n该操作会立即写入 JSON 文件。",
            "删除预设",
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
            "预设名（也作为主仓默认分支名,不可与已有预设重名）:",
            "新增预设", null, "", newNameValidator())?.trim()
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
                        "主仓当前是 detached HEAD,先 checkout 一个分支再用此功能",
                        "Branch Switcher")
                    return
                }
                val name = Messages.showInputDialog(project,
                    "预设名（不可与已有预设重名）:",
                    "从当前状态新建预设", null, mb, newNameValidator())?.trim()
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
                "preset file 不存在。建议位置:\n$base/.idea/${PresetLoader.IDEA_FILE_NAME}",
                "Branch Switcher")
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
        )

        setSwitchInProgress(true)
        val task = object : Task.Backgroundable(project, "Switching branches", true) {
            var ok = false
            private var rollbackExecutor: SwitchExecutor? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val executor = SwitchExecutor(root, ::append, service.gitClient, indicator)
                rollbackExecutor = executor
                ok = executor.execute(preset, opts)
            }
            override fun onFinished() {
                setSwitchInProgress(false)
                if (ok) {
                    service.addHistory(preset.name)
                    Notifier.info(project, "切换完成", "已切到「${preset.name}」")
                } else {
                    val executor = rollbackExecutor
                    if (executor?.getCheckpoint() != null) {
                        Notifier.rollbackAction(project, "切换有失败项",
                            "「${preset.name}」部分仓未成功。可回滚到切换前的 HEAD。") {
                            rollbackSwitch(executor)
                        }
                    } else {
                        Notifier.error(project, "切换有失败项",
                            "「${preset.name}」部分仓未成功，详见 ToolWindow 日志")
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
                Notifier.info(project, "派生完成", "分支 $branchName 已创建，共 ${preset.targets().size} 个仓库")
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
                    Notifier.warn(project, "回滚部分失败",
                        "部分仓库未能恢复到切换前状态，详见 ToolWindow 日志")
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
        Notifier.info(project, "导出完成", "${editors.size} 个预设已复制到剪贴板")
    }

    private fun importPresets() {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, "剪贴板为空", "导入预设")
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
                Messages.showWarningDialog(project, "剪贴板内容不是有效的预设 JSON", "导入预设")
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
            Notifier.info(project, "导入完成", "从剪贴板导入了 ${imported.presets.size} 个预设")
        } catch (e: Exception) {
            append("[import] error: ${e.message}")
            Messages.showWarningDialog(project, "导入失败: ${e.message}", "导入预设")
        }
    }

    private fun setSwitchInProgress(inProgress: Boolean) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("SubmoduleBranches") ?: return
        if (inProgress) {
            tw.setIcon(AllIcons.Process.Step_4)
        } else {
            tw.setIcon(AllIcons.Vcs.Branch)
        }
    }

    private fun createEmptyState(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(40, 16, 40, 16)
            alignmentX = CENTER_ALIGNMENT
            val hint = JLabel("还没有预设").apply {
                font = font.deriveFont(Font.BOLD, 15f)
                foreground = JBColor.GRAY
                alignmentX = CENTER_ALIGNMENT
            }
            val subHint = JLabel("预设是一组分支组合，可以一键切换主仓和所有子模块").apply {
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
            cta.add(JButton("从当前状态创建", AllIcons.Vcs.Branch).noFocusRing().also {
                it.addActionListener { addPresetFromCurrent() }
            })
            cta.add(JButton("手动新增", AllIcons.General.Add).noFocusRing().also {
                it.addActionListener { addPreset() }
            })
            add(cta)
        }
    }

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
