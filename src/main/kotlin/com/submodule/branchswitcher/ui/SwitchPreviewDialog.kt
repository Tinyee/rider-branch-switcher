package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class SwitchPreviewDialog(
    project: Project,
    preset: Preset,
    private val rows: List<PreflightRow>,
) : DialogWrapper(project) {

    private val warnColor get() = NamedColorUtil.getErrorForeground()
    private val mutedColor get() = NamedColorUtil.getInactiveTextColor()
    private val accentColor get() = JBUI.CurrentTheme.Link.Foreground.ENABLED

    init {
        title = "切到「${preset.name}」"
        setOKButtonText("切换")
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val table = JBTable(PreviewTableModel(rows)).apply {
            rowHeight = (rowHeight + 6).coerceAtLeast(24)
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
        }
        val widths = intArrayOf(180, 140, 140, 90, 110)
        widths.forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = w }

        val targetRenderer = TargetCellRenderer()
        val mutedIfNoChange = MutedIfNoChangeRenderer()
        table.columnModel.getColumn(0).cellRenderer = mutedIfNoChange
        table.columnModel.getColumn(1).cellRenderer = mutedIfNoChange
        table.columnModel.getColumn(2).cellRenderer = targetRenderer
        table.columnModel.getColumn(3).cellRenderer = DirtyRenderer()
        table.columnModel.getColumn(4).cellRenderer = SourceRenderer()

        val scroll = JBScrollPane(table).apply {
            preferredSize = Dimension(JBUI.scale(720), (rows.size.coerceAtMost(8) * table.rowHeight) + JBUI.scale(56))
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4, 4, 4, 4)
        }
        panel.add(buildSummary(), BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun buildSummary(): JComponent {
        val total = rows.size
        val toSwitch = rows.count { it.exists && it.needsSwitch && !it.branchMissing }
        val noChange = rows.count { it.exists && !it.needsSwitch }
        val missingBranch = rows.count { it.branchMissing }
        val missingDir = rows.count { !it.exists }
        val dirty = rows.count { it.dirtyCount > 0 }

        val parts = mutableListOf<String>()
        parts += "$total 个仓"
        parts += "$toSwitch 待切换"
        if (noChange > 0) parts += "$noChange 已就位"
        if (dirty > 0) parts += "$dirty 有脏改动"
        if (missingBranch > 0) parts += "$missingBranch 分支缺失"
        if (missingDir > 0) parts += "$missingDir 目录缺失"

        val label = JLabel(parts.joinToString("  ·  ")).apply {
            border = JBUI.Borders.empty(2, 4, 6, 4)
            if (missingBranch > 0 || missingDir > 0) foreground = warnColor
        }
        return label
    }

    private inner class PreviewTableModel(val rows: List<PreflightRow>) : AbstractTableModel() {
        private val cols = arrayOf("仓", "当前", "目标", "脏改动", "分支位置")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private inner class MutedIfNoChangeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            val r = value as PreflightRow
            val text = when (column) {
                0 -> r.label
                1 -> if (!r.exists) "(目录缺失)" else r.current ?: "(detached)"
                else -> ""
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            toolTipText = if (column == 0 && !r.isMain) r.path else null
            font = if (r.isMain && column == 0) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
            if (!isSelected) {
                foreground = when {
                    !r.exists -> warnColor
                    !r.needsSwitch -> mutedColor
                    else -> table.foreground
                }
            }
            return this
        }
    }

    private inner class TargetCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            val r = value as PreflightRow
            super.getTableCellRendererComponent(table, r.target, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when {
                    r.branchMissing -> warnColor
                    r.needsSwitch -> accentColor
                    else -> mutedColor
                }
            }
            font = if (r.needsSwitch) font.deriveFont(Font.BOLD) else font.deriveFont(Font.PLAIN)
            return this
        }
    }

    private inner class DirtyRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.LEFT
        }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            val r = value as PreflightRow
            val text = when {
                !r.exists -> "—"
                r.dirtyCount < 0 -> "?"
                r.dirtyCount == 0 -> "干净"
                else -> "${r.dirtyCount} 个文件"
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when {
                    !r.exists || r.dirtyCount < 0 -> mutedColor
                    r.dirtyCount == 0 -> mutedColor
                    else -> warnColor
                }
            }
            return this
        }
    }

    private inner class SourceRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            val r = value as PreflightRow
            val text = when {
                !r.exists -> "—"
                r.hasLocal && r.hasRemote -> "本地 + 远端"
                r.hasLocal -> "仅本地"
                r.hasRemote -> "仅远端"
                else -> "❌ 不存在"
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when {
                    !r.exists -> mutedColor
                    r.branchMissing -> warnColor
                    else -> table.foreground
                }
            }
            return this
        }
    }

    companion object {
        fun showAndConfirm(project: Project, preset: Preset, rows: List<PreflightRow>): Boolean {
            val dialog = SwitchPreviewDialog(project, preset, rows)
            return dialog.showAndGet()
        }
    }
}
