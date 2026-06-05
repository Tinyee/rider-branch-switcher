package com.submodule.branchswitcher

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PresetEditor(
    private val gitRoot: Path,
    initial: Preset,
    private val log: (String) -> Unit,
    private val onSwitch: (Preset) -> Unit,
    private val onSave: (Preset) -> Unit,
    private val onDelete: () -> Unit,
) : JPanel() {

    private class SubRow(
        val path: String,
        val combo: JComboBox<String>,
        val panel: JPanel,
        var deleted: Boolean = false,
        var loaded: Boolean = false,
    )

    private var original: Preset = initial

    private val mainCombo = makeCombo()
    private val subRows = LinkedHashMap<String, SubRow>()
    private val saveBtn = JButton("✓ 保存").apply { isEnabled = false }.noFocusRing()
    private val revertBtn = JButton("⟲ 丢弃").apply { isEnabled = false }.noFocusRing()
    private val addSubBtn = JButton("+ 添加子模块").noFocusRing()
    private val arrow = JLabel("▶")
    private val nameLabel = JLabel(initial.name)
    private val currentBadge = JLabel("· 当前").apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        isVisible = false
    }
    private val switchBtn = JButton("切到此预设").noFocusRing()
    private var isCurrent = false

    private val body = object : JPanel() {
        override fun getMaximumSize(): Dimension =
            Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private var loadedOnce = false
    private var loadingCount = 0
    private var initializing = true

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = makeBorder(highlighted = false)

        val header = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.source !is JButton) toggle()
                }
            })
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(arrow)
            add(nameLabel.apply { font = font.deriveFont(Font.BOLD) })
            add(currentBadge)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { toggle() }
            })
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        switchBtn.addActionListener { onSwitch(buildCurrent()) }
        right.add(switchBtn)
        right.add(JButton("✕ 删除").noFocusRing().also {
            it.foreground = NamedColorUtil.getErrorForeground()
            it.addActionListener { onDelete() }
        })

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)

        body.add(makeMainRow())
        initial.submodules.keys.forEach { path ->
            body.add(buildSubRow(path).panel)
        }
        val actions = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 8, 4, 4)
        }
        addSubBtn.addActionListener { showAddSubmoduleMenu() }
        revertBtn.addActionListener { revert() }
        saveBtn.addActionListener {
            val cur = buildCurrent()
            original = cur
            onSave(cur)
            subRows.entries.removeAll { (_, row) ->
                if (row.deleted) { body.remove(row.panel); true } else false
            }
            body.revalidate()
            body.repaint()
            updateDirty()
        }
        val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        leftActions.add(addSubBtn)
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        rightActions.add(revertBtn)
        rightActions.add(saveBtn)
        actions.add(leftActions, BorderLayout.WEST)
        actions.add(rightActions, BorderLayout.EAST)
        body.add(actions)

        add(header)
        add(body)

        applyOriginalToUI()
        initializing = false
    }

    private fun makeCombo(): JComboBox<String> {
        val combo = JComboBox<String>()
        combo.isEditable = true
        combo.prototypeDisplayValue = "x".repeat(28)
        combo.addItemListener { updateDirty() }
        val editor = combo.editor.editorComponent as? JTextField
        editor?.document?.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateDirty()
                override fun removeUpdate(e: DocumentEvent) = updateDirty()
                override fun changedUpdate(e: DocumentEvent) = updateDirty()
            }
        )
        editor?.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER,
                    KeyEvent.VK_ESCAPE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
                    KeyEvent.VK_TAB, KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL,
                    KeyEvent.VK_ALT, KeyEvent.VK_META -> return
                }
                filterPopup(combo, editor)
            }
        })
        return combo
    }

    private fun filterPopup(combo: JComboBox<String>, editor: JTextField) {
        @Suppress("UNCHECKED_CAST")
        val all = combo.getClientProperty(KEY_ALL_BRANCHES) as? List<String> ?: return
        val text = editor.text ?: ""
        val caret = editor.caretPosition
        val filtered = if (text.isBlank()) all
                       else all.filter { it.contains(text, ignoreCase = true) }
        if (filtered.isEmpty()) {
            combo.isPopupVisible = false
            return
        }
        val same = combo.itemCount == filtered.size &&
            (0 until combo.itemCount).all { combo.getItemAt(it) == filtered[it] }
        if (!same) {
            val model = DefaultComboBoxModel(filtered.toTypedArray())
            model.selectedItem = text
            combo.model = model
            editor.text = text
            editor.caretPosition = minOf(caret, text.length)
        }
        if (combo.isShowing && editor.isFocusOwner) {
            combo.isPopupVisible = true
        }
    }

    private fun makeMainRow(): JPanel {
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = BorderFactory.createEmptyBorder(2, 12, 2, 4)
            alignmentX = LEFT_ALIGNMENT
            val l = JLabel("主仓").apply {
                preferredSize = Dimension(140, preferredSize.height)
            }
            add(l, BorderLayout.WEST)
            add(mainCombo, BorderLayout.CENTER)
        }
    }

    private fun buildSubRow(path: String): SubRow {
        val combo = makeCombo()
        val rowPanel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = BorderFactory.createEmptyBorder(2, 12, 2, 4)
            alignmentX = LEFT_ALIGNMENT
            val l = JLabel(shortLabel(path)).apply {
                preferredSize = Dimension(140, preferredSize.height)
                toolTipText = path
            }
            add(l, BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)

            val delBtn = JButton("✕").apply {
                margin = Insets(0, 4, 0, 4)
                preferredSize = Dimension(32, 24)
                maximumSize = Dimension(32, 24)
                minimumSize = Dimension(32, 24)
                foreground = NamedColorUtil.getErrorForeground()
                toolTipText = "从此预设移除该子模块（保存后切换将不动它）"
                addActionListener {
                    val r = subRows[path] ?: return@addActionListener
                    r.deleted = true
                    r.panel.isVisible = false
                    updateDirty()
                    body.revalidate()
                    body.repaint()
                }
            }.noFocusRing()
            add(delBtn, BorderLayout.EAST)
        }
        val row = SubRow(path, combo, rowPanel)
        subRows[path] = row
        return row
    }

    private fun shortLabel(path: String): String {
        if (!path.contains("/")) return path
        return path.substringAfterLast('/').removeSuffix("~")
    }

    private fun applyOriginalToUI() {
        mainCombo.selectedItem = original.main
        val orphan = mutableListOf<String>()
        subRows.values.forEach { row ->
            if (original.submodules.containsKey(row.path)) {
                row.deleted = false
                row.panel.isVisible = true
                row.combo.selectedItem = original.submodules[row.path]
            } else {
                orphan += row.path
            }
        }
        orphan.forEach { path ->
            val row = subRows.remove(path) ?: return@forEach
            body.remove(row.panel)
        }
        body.revalidate()
        body.repaint()
        updateDirty()
    }

    private fun showAddSubmoduleMenu() {
        val all = GitOps.listSubmodulePaths(gitRoot.toFile())
        val current = subRows.values.filter { !it.deleted }.map { it.path }.toSet()
        val available = all.filter { it !in current }
        if (available.isEmpty()) {
            log("所有 .gitmodules 中的子模块均已在「${original.name}」中配置")
            return
        }
        val popup = javax.swing.JPopupMenu()
        available.forEach { path ->
            popup.add(javax.swing.JMenuItem(shortLabel(path)).apply {
                toolTipText = path
                addActionListener { addSubmoduleFromMenu(path) }
            })
        }
        popup.show(addSubBtn, 0, addSubBtn.height)
    }

    private fun addSubmoduleFromMenu(path: String) {
        val existing = subRows[path]
        if (existing != null && existing.deleted) {
            existing.deleted = false
            existing.panel.isVisible = true
            if (loadedOnce && !existing.loaded) {
                existing.loaded = true
                loadComboBranches(existing.combo, gitRoot.resolve(path).toFile(),
                    existing.combo.selectedItem as? String ?: "")
            }
            updateDirty()
            body.revalidate()
            body.repaint()
            return
        }
        val row = buildSubRow(path)
        val actionsIndex = body.componentCount - 1
        body.add(row.panel, actionsIndex)
        val dir = gitRoot.resolve(path).toFile()
        val seedBranch = if (dir.exists()) GitOps.currentBranch(dir) ?: "" else ""
        row.combo.selectedItem = seedBranch
        if (loadedOnce) {
            row.loaded = true
            loadComboBranches(row.combo, dir, seedBranch)
        }
        body.revalidate()
        body.repaint()
        updateDirty()
    }

    private fun toggle() {
        body.isVisible = !body.isVisible
        arrow.text = if (body.isVisible) "▼" else "▶"
        if (body.isVisible && !loadedOnce) {
            loadedOnce = true
            loadBranches()
        }
        revalidate()
        repaint()
    }

    private fun loadBranches() {
        loadComboBranches(mainCombo, gitRoot.toFile(), original.main)
        subRows.values.forEach { row ->
            if (row.deleted || row.loaded) return@forEach
            row.loaded = true
            val dir = gitRoot.resolve(row.path).toFile()
            val branch = original.submodules[row.path] ?: ""
            loadComboBranches(row.combo, dir, branch)
        }
    }

    private fun loadComboBranches(combo: JComboBox<String>, dir: File, current: String) {
        combo.model = DefaultComboBoxModel(arrayOf("loading..."))
        combo.selectedItem = "loading..."
        combo.isEnabled = false
        loadingCount++
        Thread {
            val branches = if (dir.exists()) GitOps.listAllBranches(dir) else emptyList()
            SwingUtilities.invokeLater {
                val list = if (current.isNotEmpty() && !branches.contains(current))
                    listOf(current) + branches else branches
                combo.model = DefaultComboBoxModel(list.toTypedArray())
                combo.selectedItem = current
                combo.putClientProperty(KEY_ALL_BRANCHES, list)
                combo.isEnabled = true
                loadingCount--
                updateDirty()
            }
        }.start()
    }

    companion object {
        private const val KEY_ALL_BRANCHES = "submodule.branchswitcher.allBranches"

        private fun makeBorder(highlighted: Boolean): Border {
            val divider = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            return if (highlighted) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(
                        divider,
                        BorderFactory.createMatteBorder(0, 3, 0, 0,
                            JBUI.CurrentTheme.Link.Foreground.ENABLED),
                    ),
                    BorderFactory.createEmptyBorder(4, 1, 10, 4),
                )
            } else {
                BorderFactory.createCompoundBorder(
                    divider,
                    BorderFactory.createEmptyBorder(4, 4, 10, 4),
                )
            }
        }
    }

    fun applyCurrentState(currentBranches: Map<String, String?>) {
        setHighlighted(matchesState(currentBranches))
    }

    fun matchesState(currentBranches: Map<String, String?>): Boolean {
        val mainMatches = currentBranches["."] == original.main
        val subsMatch = original.submodules.all { (path, branch) ->
            currentBranches[path] == branch
        }
        return mainMatches && subsMatch
    }

    private fun setHighlighted(highlighted: Boolean) {
        val changed = highlighted != isCurrent
        isCurrent = highlighted
        currentBadge.isVisible = highlighted
        switchBtn.isEnabled = !highlighted
        switchBtn.text = if (highlighted) "已在此预设" else "切到此预设"
        switchBtn.toolTipText = if (highlighted) "当前主仓与子模块分支已与该预设一致" else null
        border = makeBorder(highlighted)
        if (changed) {
            revalidate()
            repaint()
            parent?.revalidate()
            parent?.repaint()
        }
    }

    private fun buildCurrent(): Preset {
        val newSubs = LinkedHashMap<String, String>()
        subRows.values.forEach { row ->
            if (row.deleted) return@forEach
            newSubs[row.path] = (row.combo.selectedItem as? String)?.trim() ?: ""
        }
        return original.copy(
            main = (mainCombo.selectedItem as? String)?.trim() ?: original.main,
            submodules = newSubs,
        )
    }

    private fun revert() {
        applyOriginalToUI()
    }

    private fun updateDirty() {
        if (initializing || loadingCount > 0) {
            saveBtn.isEnabled = false
            revertBtn.isEnabled = false
            return
        }
        val cur = buildCurrent()
        val dirty = cur != original
        saveBtn.isEnabled = dirty
        revertBtn.isEnabled = dirty
    }

    fun currentPreset(): Preset = original

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
