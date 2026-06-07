package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.switch.shortLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Manages submodule rows within a [PresetEditor]: build, add, remove, and state sync.
 * Extracted from PresetEditor to keep it focused.
 */
class SubmoduleRowManager(
    private val gitRoot: Path,
    private val gitClient: GitClient,
    private val scope: CoroutineScope,
    private val body: JPanel,
    private val log: (String) -> Unit,
    private val onDirty: () -> Unit,
) {
    /** One submodule row: path, branch combo, panel, and tracking state. */
    class SubRow(
        val path: String,
        val combo: JComboBox<String>,
        var panel: JPanel,
        var deleted: Boolean = false,
        var loaded: Boolean = false,
        val statusDot: JLabel = JLabel("●").apply {
            font = font.deriveFont(8f)
            foreground = JBColor(0x9E9E9E, 0x757575)
        },
    )

    val subRows = LinkedHashMap<String, SubRow>()
    private var loadedOnce = false

    /** Called by [PresetEditor] when first expand occurs. */
    fun onFirstExpand() { loadedOnce = true }

    /** Creates and registers a submodule row UI + data. */
    fun buildSubRow(path: String, initialBranch: String): SubRow {
        val combo = makeBranchCombo(onDirty)
        val dot = JLabel("●").apply {
            font = font.deriveFont(8f)
            foreground = JBColor(0x9E9E9E, 0x757575)
        }
        val row = SubRow(path, combo, JPanel(), statusDot = dot)
        val rowPanel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty(2, 12, 2, 4)
            alignmentX = JPanel.LEFT_ALIGNMENT
            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(dot)
                add(Box.createHorizontalStrut(4))
                add(JLabel(shortLabel(path)).apply {
                    preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
                    toolTipText = path
                })
            }
            add(labelPanel, BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)

            val delBtn = JButton(AllIcons.General.Remove).apply {
                margin = JBUI.insets(0, 4, 0, 4)
                preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                maximumSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                minimumSize = Dimension(JBUI.scale(32), JBUI.scale(24))
                toolTipText = "从此预设移除该子模块（保存后切换将不动它）"
                addActionListener {
                    val r = subRows[path] ?: return@addActionListener
                    r.deleted = true
                    r.panel.isVisible = false
                    onDirty()
                    body.revalidate()
                    body.repaint()
                }
            }.noFocusRing()
            add(delBtn, BorderLayout.EAST)
        }
        // Right-click context menu
        rowPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e, path) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e, path) }
            override fun mouseClicked(e: MouseEvent) {}
        })
        row.panel = rowPanel
        subRows[path] = row
        return row
    }

    /** Shows a popup to add a new submodule from .gitmodules paths not yet in the preset. */
    fun showAddSubmoduleMenu(anchor: JButton, currentPreset: Preset) {
        val all = gitClient.listSubmodulePaths(gitRoot.toFile())
        val current = subRows.values.filter { !it.deleted }.map { it.path }.toSet()
        val available = all.filter { it !in current }
        if (available.isEmpty()) {
            log("所有 .gitmodules 中的子模块均已在「${currentPreset.name}」中配置")
            return
        }
        val popup = javax.swing.JPopupMenu()
        available.forEach { path ->
            popup.add(javax.swing.JMenuItem(shortLabel(path)).apply {
                toolTipText = path
                addActionListener { addSubmoduleFromMenu(path) }
            })
        }
        popup.show(anchor, 0, anchor.height)
    }

    /** Adds a submodule row for [path], reactivating deleted rows or creating new ones. */
    fun addSubmoduleFromMenu(path: String) {
        val existing = subRows[path]
        if (existing != null && existing.deleted) {
            existing.deleted = false
            existing.panel.isVisible = true
            if (loadedOnce && !existing.loaded) {
                existing.loaded = true
                loadComboBranches(existing.combo, gitRoot.resolve(path).toFile(),
                    existing.combo.selectedItem as? String ?: "")
            }
            onDirty()
            body.revalidate()
            body.repaint()
            return
        }
        val row = buildSubRow(path, "")
        val actionsIndex = body.componentCount - 1
        body.add(row.panel, actionsIndex)
        val dir = gitRoot.resolve(path).toFile()
        if (!dir.exists()) {
            row.combo.selectedItem = ""
            if (loadedOnce) { row.loaded = true; loadComboBranches(row.combo, dir, "") }
            onDirty()
        } else {
            row.combo.selectedItem = "loading..."
            row.combo.isEnabled = false
            loadingCount++
            scope.launch {
                val seedBranch = gitClient.currentBranch(dir) ?: ""
                SwingUtilities.invokeLater {
                    row.combo.selectedItem = seedBranch
                    row.combo.isEnabled = true
                    loadingCount--
                    if (loadedOnce) {
                        row.loaded = true
                        loadComboBranches(row.combo, dir, seedBranch)
                    }
                    onDirty()
                }
            }
        }
        body.revalidate()
        body.repaint()
    }

    /** Syncs subRows to match [preset]'s submodule map. Restores rows and adds new ones. */
    fun applyPresetToUI(preset: Preset) {
        val orphan = mutableListOf<String>()
        subRows.values.forEach { row ->
            if (preset.submodules.containsKey(row.path)) {
                row.deleted = false
                row.panel.isVisible = true
                row.combo.selectedItem = preset.submodules[row.path]
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
    }

    /** Removes deleted rows from [body] and [subRows]. */
    fun removeDeletedRows() {
        subRows.entries.removeAll { (_, row) ->
            if (row.deleted) { body.remove(row.panel); true } else false
        }
    }

    /** Loads branch combos for all unloaded visible rows. Must be guarded by loadedOnce. */
    fun loadAllBranches(preset: Preset) {
        subRows.values.forEach { row ->
            if (row.deleted || row.loaded) return@forEach
            row.loaded = true
            val dir = gitRoot.resolve(row.path).toFile()
            val branch = preset.submodules[row.path] ?: ""
            loadComboBranches(row.combo, dir, branch)
        }
    }

    var loadingCount = 0
        internal set

    /** Asynchronously loads branch names into [combo]. */
    private fun loadComboBranches(combo: JComboBox<String>, dir: File, current: String) {
        combo.model = DefaultComboBoxModel(arrayOf("loading..."))
        combo.selectedItem = "loading..."
        combo.isEnabled = false
        loadingCount++
        scope.launch {
            val branches = try {
                if (dir.exists()) gitClient.listAllBranches(dir) else emptyList()
            } catch (e: Exception) {
                log("loadBranches failed for ${dir.name}: ${e.message}")
                emptyList()
            }
            SwingUtilities.invokeLater {
                val list = if (current.isNotEmpty() && !branches.contains(current))
                    listOf(current) + branches else branches
                combo.model = DefaultComboBoxModel(list.toTypedArray())
                combo.selectedItem = current
                combo.putClientProperty(KEY_ALL_BRANCHES, list)
                combo.isEnabled = true
                loadingCount--
                onDirty()
            }
        }
    }

    private fun showContextMenu(e: MouseEvent, path: String) {
        val popup = javax.swing.JPopupMenu()
        popup.add("${Strings.switchOnlyThis} ($path)").addActionListener {
            val dir = gitRoot.resolve(path).toFile()
            if (dir.exists() && File(dir, ".git").exists()) {
                scope.launch {
                    val cur = gitClient.currentBranch(dir)
                    val target = gitClient.currentBranch(dir) // use current as target for single-switch
                        ?: return@launch
                    val result = gitClient.checkoutExisting(dir, target)
                    SwingUtilities.invokeLater { log(if (result.ok) "[switch] $path -> $target ok" else "[switch] $path fail") }
                }
            }
        }
        popup.add(Strings.openInExplorer).addActionListener {
            val dir = gitRoot.resolve(path).toFile()
            if (dir.exists()) java.awt.Desktop.getDesktop().open(dir)
        }
        popup.show(e.component, e.x, e.y)
    }
}
