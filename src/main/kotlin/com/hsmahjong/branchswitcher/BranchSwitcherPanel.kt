package com.hsmahjong.branchswitcher

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
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
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class BranchSwitcherPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<Preset>()
    private val presetList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = javax.swing.ListCellRenderer<Preset> { _, value, _, isSelected, _ ->
            JLabel("${value.name}  →  main: ${value.main}  (+${value.submodules.size} subs)").apply {
                isOpaque = true
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                if (isSelected) {
                    background = java.awt.Color(0x2D5AF0)
                    foreground = java.awt.Color.WHITE
                }
            }
        }
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

        val top = JPanel(BorderLayout())
        val title = JLabel("HsMahjong Branch Switcher").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }
        top.add(title, BorderLayout.NORTH)

        val listScroll = JBScrollPane(presetList).apply {
            preferredSize = Dimension(0, 140)
        }
        top.add(listScroll, BorderLayout.CENTER)

        val opts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(6, 0, 6, 0)
        }
        val dirtyRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("脏工作区:"))
            add(dirtyCombo)
        }
        opts.add(dirtyRow)
        opts.add(pullCheck)
        opts.add(fetchCheck)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val switchBtn = JButton("切换").also {
            it.addActionListener { runSwitch() }
        }
        val reloadBtn = JButton("重载预设").also {
            it.addActionListener { reload() }
        }
        val openCfgBtn = JButton("打开预设文件").also {
            it.addActionListener { openConfig() }
        }
        val clearBtn = JButton("清空日志").also {
            it.addActionListener { log.text = "" }
        }
        buttons.add(switchBtn)
        buttons.add(reloadBtn)
        buttons.add(openCfgBtn)
        buttons.add(Box.createHorizontalStrut(12))
        buttons.add(clearBtn)

        val north = JPanel(BorderLayout())
        north.add(top, BorderLayout.NORTH)
        north.add(opts, BorderLayout.CENTER)
        north.add(buttons, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(JBScrollPane(log), BorderLayout.CENTER)

        reload()
    }

    private fun projectRoot(): Path? {
        val base = project.basePath ?: return null
        return Paths.get(base)
    }

    private fun reload() {
        listModel.clear()
        val root = projectRoot()
        if (root == null) {
            append("project base path is null")
            return
        }
        val res = PresetLoader.load(root)
        res.onSuccess { file ->
            file.presets.forEach { listModel.addElement(it) }
            append("loaded ${file.presets.size} preset(s) from ${PresetLoader.FILE_NAME}")
            if (listModel.size() > 0) presetList.selectedIndex = 0
        }.onFailure {
            append("[error] ${it.message}")
        }
    }

    private fun openConfig() {
        val root = projectRoot() ?: return
        val file = root.resolve(PresetLoader.FILE_NAME)
        if (!java.nio.file.Files.exists(file)) {
            Messages.showWarningDialog(project,
                "$file 不存在，请先创建", "Branch Switcher")
            return
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString())
        if (vf != null) {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun runSwitch() {
        val preset = presetList.selectedValue
        if (preset == null) {
            Messages.showWarningDialog(project, "请先选择一个预设", "Branch Switcher")
            return
        }
        val root = projectRoot() ?: return

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
        }
        com.intellij.openapi.progress.ProgressManager.getInstance().run(task)
    }

    private fun append(line: String) {
        SwingUtilities.invokeLater {
            log.append(line)
            log.append("\n")
            log.caretPosition = log.document.length
        }
    }
}
