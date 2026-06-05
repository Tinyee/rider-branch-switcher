package com.submodule.branchswitcher

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
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

class BranchSwitcherPanel(private val project: Project) : JPanel(BorderLayout()) {

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null

    private val editors = mutableListOf<PresetEditor>()
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = JPanel(BorderLayout()).apply {
        add(presetsInner, BorderLayout.NORTH)
    }

    private val dirtyCombo = JComboBox(arrayOf("Stash 脏改动", "脏则跳过", "强制(危险)"))
    private val pullCheck = JCheckBox("切换后 pull --ff-only", true)
    private val fetchCheck = JCheckBox("切换前 fetch", true)

    private val log = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val title = JLabel("Submodule Branch Switcher").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }

        val presetsScroll = JBScrollPane(presetsContainer).apply {
            preferredSize = Dimension(0, 600)
        }
        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("+ 新增预设").also { it.addActionListener { addPreset() } })
        }
        val presetsBlock = JPanel(BorderLayout()).apply {
            add(presetsScroll, BorderLayout.CENTER)
            add(addPanel, BorderLayout.SOUTH)
        }

        val north = JPanel(BorderLayout())
        north.add(title, BorderLayout.NORTH)
        north.add(presetsBlock, BorderLayout.CENTER)

        val logScroll = JBScrollPane(log).apply {
            preferredSize = Dimension(0, 110)
        }

        val optsRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("脏工作区:"))
            add(dirtyCombo)
        }
        val optsRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(fetchCheck)
            add(pullCheck)
        }
        val opts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(80, 80, 80)),
                BorderFactory.createEmptyBorder(4, 0, 4, 0),
            )
            optsRow1.alignmentX = LEFT_ALIGNMENT
            optsRow2.alignmentX = LEFT_ALIGNMENT
            add(optsRow1)
            add(optsRow2)
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 0, 4, 0)
        }
        buttons.add(JButton("重载预设").also { it.addActionListener { reload() } })
        buttons.add(JButton("打开预设文件").also { it.addActionListener { openConfig() } })
        buttons.add(Box.createHorizontalStrut(12))
        buttons.add(JButton("清空日志").also { it.addActionListener { log.text = "" } })

        val south = JPanel(BorderLayout())
        south.add(opts, BorderLayout.NORTH)
        south.add(buttons, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(logScroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        reload()
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
        val base = ideBase() ?: run { append("project base path is null"); return }
        PresetLoader.load(base)
            .onSuccess { (file, parsed) ->
                savedFilePath = file
                presetFile = parsed
                val root = gitRoot()
                if (root == null) {
                    append("[error] git root not found")
                    return@onSuccess
                }
                parsed.presets.forEach { addEditorRow(root, it) }
                append("loaded ${parsed.presets.size} preset(s) from $file")
                presetsInner.revalidate()
                presetsInner.repaint()
            }
            .onFailure { append("[error] ${it.message}") }
    }

    private fun addEditorRow(root: Path, preset: Preset) {
        lateinit var editor: PresetEditor
        editor = PresetEditor(
            gitRoot = root,
            initial = preset,
            log = ::append,
            onSwitch = ::runSwitch,
            onSave = { saveAll() },
            onDelete = { deleteEditor(editor) },
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
        presetsInner.revalidate()
        presetsInner.repaint()
        append("[deleted] $name")
    }

    private fun saveAll() {
        val file = savedFilePath ?: return
        val list = editors.map { it.currentPreset() }
        presetFile = presetFile.copy(presets = list)
        PresetLoader.save(file, presetFile)
        append("[saved] -> $file")
    }

    private fun addPreset() {
        val name = Messages.showInputDialog(project,
            "预设名（也作为主仓默认分支名）:", "新增预设", null)?.trim()
        if (name.isNullOrEmpty()) return
        val template = presetFile.presets.firstOrNull()
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
        val confirm = Messages.showYesNoDialog(
            project,
            "切换到预设：${preset.name}\n主仓 → ${preset.main}\n${preset.submodules.size} 个子模块\n\n确认继续？",
            "Branch Switcher",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return

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

        val task = object : Task.Backgroundable(project, "Switching branches", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val executor = SwitchExecutor(root) { msg -> append(msg) }
                executor.execute(preset, opts)
            }
            override fun onFinished() { refreshVcs(root, preset) }
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    private fun refreshVcs(root: Path, preset: Preset) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            val mgr = git4idea.repo.GitRepositoryManager.getInstance(project)
            val dirs = mutableListOf(root.toFile())
            preset.submodules.keys.forEach { dirs += root.resolve(it).toFile() }
            for (dir in dirs) {
                val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
                vf.refresh(true, true)
                mgr.getRepositoryForRoot(vf)?.update()
            }
            append("[vcs] refreshed ${dirs.size} repo(s)")
        }
    }

    private fun append(line: String) {
        SwingUtilities.invokeLater {
            log.append(line)
            log.append("\n")
            log.caretPosition = log.document.length
        }
    }
}
