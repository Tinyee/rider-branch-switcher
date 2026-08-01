package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.presentation.shouldShowForceWarning
import java.awt.BorderLayout
import javax.swing.BoxLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class SwitchPreviewDialog(
    project: Project,
    private val request: ResolvedSwitchRequest,
    private val rows: List<PreflightRow>,
) : DialogWrapper(project) {

    private val warnColor get() = NamedColorUtil.getErrorForeground()
    private val mutedColor get() = NamedColorUtil.getInactiveTextColor()
    private val accentColor get() = JBUI.CurrentTheme.Link.Foreground.ENABLED

    init {
        title = Bundle.msg("dialog.switch.preset.title", request.preset.name)
        setOKButtonText(Bundle.msg("dialog.switch.title"))
        setCancelButtonText(Bundle.msg("dialog.cancel"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val table = JBTable(PreviewTableModel(rows)).apply {
            rowHeight = (rowHeight + 6).coerceAtLeast(24)
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
        }
        val widths = intArrayOf(180, 140, 140, 90, 110).map { JBUI.scale(it) }.toIntArray()
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
        parts += Bundle.msg("summary.repos", total)
        parts += Bundle.msg("summary.to.switch", toSwitch)
        if (noChange > 0) parts += Bundle.msg("summary.already", noChange)
        if (dirty > 0) parts += Bundle.msg("summary.dirty", dirty)
        if (missingBranch > 0) parts += Bundle.msg("summary.missing.branch", missingBranch)
        if (missingDir > 0) parts += Bundle.msg("summary.missing.dir", missingDir)

        val summaryText = parts.joinToString("  ·  ")
        val summaryLabel = ShrinkableLabel(summaryText).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = summaryText
            border = JBUI.Borders.empty(2, 4, 6, 4)
            if (missingBranch > 0 || missingDir > 0) foreground = warnColor
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summaryLabel)
            if (shouldShowForceWarning(request, rows)) {
                val warning = Bundle.msg("dialog.force.warn")
                add(ShrinkableLabel(warning).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    toolTipText = warning
                    foreground = warnColor
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.empty(0, 4, 6, 4)
                })
            }
        }
    }

    private inner class PreviewTableModel(val rows: List<PreflightRow>) : AbstractTableModel() {
        private val cols = arrayOf(
            Bundle.msg("column.repo"),
            Bundle.msg("column.current"),
            Bundle.msg("column.target"),
            Bundle.msg("column.dirty"),
            Bundle.msg("column.source"),
        )
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
            val preflightRow = value as PreflightRow
            val text = when (column) {
                0 -> preflightRow.label
                1 -> when {
                    preflightRow.probeError != null -> preflightRow.probeError
                    !preflightRow.exists -> Bundle.msg("status.missing.dir")
                    else -> preflightRow.current ?: Bundle.msg("status.detached")
                }
                else -> ""
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            toolTipText = when {
                column == 1 && preflightRow.probeError != null -> preflightRow.probeError
                column == 0 && !preflightRow.isMain -> preflightRow.path
                else -> text
            }
            font = if (preflightRow.isMain && column == 0) {
                font.deriveFont(Font.BOLD)
            } else {
                font.deriveFont(Font.PLAIN)
            }
            if (!isSelected) {
                foreground = when {
                    !preflightRow.exists || preflightRow.probeError != null -> warnColor
                    !preflightRow.needsSwitch -> mutedColor
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
            val preflightRow = value as PreflightRow
            super.getTableCellRendererComponent(
                table,
                preflightRow.target,
                isSelected,
                hasFocus,
                row,
                column,
            )
            toolTipText = preflightRow.target
            if (!isSelected) {
                foreground = when {
                    preflightRow.branchMissing -> warnColor
                    preflightRow.needsSwitch -> accentColor
                    else -> mutedColor
                }
            }
            font = if (preflightRow.needsSwitch) {
                font.deriveFont(Font.BOLD)
            } else {
                font.deriveFont(Font.PLAIN)
            }
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
            val preflightRow = value as PreflightRow
            val text = when {
                !preflightRow.exists -> "—"
                preflightRow.dirtyCount < 0 -> "?"
                preflightRow.dirtyCount == 0 -> Bundle.msg("status.clean")
                else -> Bundle.msg("status.file.count", preflightRow.dirtyCount)
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when {
                    !preflightRow.exists || preflightRow.dirtyCount < 0 -> mutedColor
                    preflightRow.dirtyCount == 0 -> mutedColor
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
            val preflightRow = value as PreflightRow
            val text = when {
                !preflightRow.exists -> "—"
                preflightRow.hasLocal && preflightRow.hasRemote -> Bundle.msg("status.both")
                preflightRow.hasLocal -> Bundle.msg("status.local.only")
                preflightRow.hasRemote -> Bundle.msg("status.remote.only")
                else -> Bundle.msg("status.none")
            }
            super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column)
            if (!isSelected) {
                foreground = when {
                    !preflightRow.exists -> mutedColor
                    preflightRow.branchMissing -> warnColor
                    else -> table.foreground
                }
            }
            return this
        }
    }

    companion object {
        fun showAndConfirm(project: Project, request: ResolvedSwitchRequest, rows: List<PreflightRow>): Boolean {
            val dialog = SwitchPreviewDialog(project, request, rows)
            return dialog.showAndGet()
        }
    }
}
