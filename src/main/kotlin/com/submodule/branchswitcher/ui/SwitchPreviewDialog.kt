package com.submodule.branchswitcher.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.presentation.shouldShowForceWarning
import com.submodule.branchswitcher.service.BranchSwitcherService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

class SwitchPreviewDialog(
    private val project: Project,
    private val request: ResolvedSwitchRequest,
    private val rows: List<PreflightRow>,
) : DialogWrapper(project) {

    private val warnColor get() = NamedColorUtil.getErrorForeground()
    private val mutedColor get() = NamedColorUtil.getInactiveTextColor()
    private val accentColor get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    private val safeColor get() = JBColor(0x2E7D32, 0x73C78B)
    private val borderColor get() = JBColor.border()
    private val panelBg get() = JBColor(0xF6F7F8, 0x3F4448)
    private val autoColor get() = JBColor(0xB45309, 0xF2A42A)

    /**
     * Whether this confirmation discards only .meta files rather than every collision.
     * Read by the caller after [showAndGet] returns true.
     */
    var onlyMetaDiscard: Boolean = false
        private set

    /** The dialog's preferred width; see [tableFillWidth]. */
    private val dialogWidth get() = JBUI.scale(720)

    /**
     * Cap on the preview table's total width: columns fit their content, and the dialog
     * widens to show them, but never past this bound — wider content scrolls horizontally
     * instead of stretching the window across the screen.
     */
    private val maxTableWidth get() = JBUI.scale(1080)

    /**
     * Total width the table is asked to fill when its packed columns are narrower than the
     * dialog: the leftover space is distributed proportionally so a sparse row still spans
     * the dialog instead of leaving a blank tail. A few px under [dialogWidth] so the
     * exact-fit case never shows a horizontal scrollbar.
     */
    private val tableFillWidth get() = dialogWidth - JBUI.scale(4)

    /** True when a collision that is not auto-approved exists, so OK must confirm a discard. */
    private val needsDiscardConfirm: Boolean
        get() = collisionDecision(
            rows.flatMap { it.untrackedCollisions },
            onlyMeta = false,
            autoMeta = request.options.autoDiscardMeta,
        ).needsConfirm

    init {
        title = Bundle.msg("dialog.switch.preset.title", request.preset.name)
        setOKButtonText(
            if (needsDiscardConfirm) Bundle.msg("dialog.collision.discard.ok") else Bundle.msg("dialog.switch.title"),
        )
        setCancelButtonText(Bundle.msg("dialog.cancel"))
        init()
    }

    // ── Preview table: pack, expand, center ─────────────────
    override fun createCenterPanel(): JComponent {
        val table = JBTable(PreviewTableModel(rows)).apply {
            rowHeight = (rowHeight + 6).coerceAtLeast(24)
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setShowGrid(false)
            tableHeader.reorderingAllowed = false
        }
        centerTableHeader(table)
        installCellRenderers(table)

        // Pack only after the renderers are installed: packing measures each cell through
        // its renderer, and the default renderer would otherwise render the whole
        // PreflightRow.toString() (including the full collision set) and blow up the width.
        val packedWidth = packColumns(table)
        if (packedWidth < tableFillWidth) {
            expandColumnsProportionally(table, packedWidth, tableFillWidth)
        }

        val scroll = JBScrollPane(table).apply {
            preferredSize = Dimension(
                maxOf(dialogWidth, minOf(packedWidth + JBUI.scale(8), maxTableWidth)),
                (rows.size.coerceAtMost(8) * table.rowHeight) + JBUI.scale(56),
            )
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(4, 10, 4, 10)
        }
        panel.add(createSummary(), BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        createCollisionSection()?.let { panel.add(it, BorderLayout.SOUTH) }
        return panel
    }

    private fun installCellRenderers(table: JBTable) {
        val mutedIfNoChange = MutedIfNoChangeRenderer()
        val targetRenderer = TargetCellRenderer()
        table.columnModel.getColumn(0).cellRenderer = mutedIfNoChange
        table.columnModel.getColumn(1).cellRenderer = mutedIfNoChange
        table.columnModel.getColumn(2).cellRenderer = targetRenderer
        table.columnModel.getColumn(3).cellRenderer = DirtyRenderer()
        table.columnModel.getColumn(4).cellRenderer = SourceRenderer()
    }

    /**
     * Forces the table header to center its labels. The header's default renderer class is
     * not guaranteed to be a DefaultTableCellRenderer across IntelliJ versions, so instead
     * of casting to a specific type the centering is applied to the component the original
     * renderer returns, preserving whatever styling IntelliJ gives the header.
     */
    private fun centerTableHeader(table: JBTable) {
        val original = table.tableHeader.defaultRenderer
        table.tableHeader.defaultRenderer = TableCellRenderer { tbl, value, isSelected, hasFocus, row, column ->
            val component = original.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
            (component as? JLabel)?.horizontalAlignment = SwingConstants.CENTER
            component
        }
    }

    /**
     * Fits each column to its widest cell (including the localized header), so rows with
     * long repo names or dirty counts don't clip. The table uses AUTO_RESIZE_OFF, so the
     * set preferred widths are the actual laid-out widths. Must run after the column
     * renderers are installed (see [installCellRenderers]). Returns the packed total so the
     * caller can either fill a sparse row or size the scroll pane to wide content.
     */
    private fun packColumns(table: JBTable): Int {
        val headerRenderer = table.tableHeader.defaultRenderer
        var total = 0
        for (columnIndex in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(columnIndex)
            var width = headerRenderer.getTableCellRendererComponent(
                table, column.headerValue, false, false, 0, columnIndex,
            ).preferredSize.width
            for (row in 0 until table.rowCount) {
                val renderer = table.getCellRenderer(row, columnIndex)
                width = maxOf(width, table.prepareRenderer(renderer, row, columnIndex).preferredSize.width)
            }
            width = maxOf(width, JBUI.scale(60)) + table.intercellSpacing.width
            column.preferredWidth = width
            total += width
        }
        return total
    }

    /**
     * Distributes the [packedWidth]-to-[targetWidth] slack across the columns in proportion
     * to their packed widths, so a sparse table still fills the dialog row. The last column
     * absorbs rounding so the new total is exactly [targetWidth].
     */
    private fun expandColumnsProportionally(table: JBTable, packedWidth: Int, targetWidth: Int) {
        val slack = targetWidth - packedWidth
        val model = table.columnModel
        val widths = (0 until model.columnCount).map { model.getColumn(it).preferredWidth }
        var distributed = 0
        for (index in widths.indices) {
            val share = if (index == widths.lastIndex) {
                slack - distributed
            } else {
                slack * widths[index] / packedWidth
            }
            model.getColumn(index).preferredWidth = widths[index] + share
            distributed += share
        }
    }

    // ── Collision card + pinned decision row ────────────────
    /**
     * Lists the untracked files the switch would overwrite, grouped per repo, inside a
     * bounded-scroll card with a breakdown header. The discard decision — the two options
     * and the live discard count — lives in a separate pinned row below the card (see
     * [createDecisionRow]) so it stays reachable no matter how many files are listed.
     * Returns null when there is no collision.
     */
    private fun createCollisionSection(): JPanel? {
        val collisionRows = rows.filter { it.untrackedCollisions.isNotEmpty() }
        if (collisionRows.isEmpty()) return null

        // One decision object drives the header counts, the summary, and every file-row note,
        // so a single recompute per checkbox toggle keeps them all on the same options. (The
        // init-time confirm gate is computed separately, see [needsDiscardConfirm].)
        val collisions = collisionRows.flatMap { it.untrackedCollisions }
        var decision = collisionDecision(collisions, onlyMeta = false, autoMeta = request.options.autoDiscardMeta)

        // The file rows keep their labels so the decision row can restyle them live as the
        // user toggles the discard options (see refreshCollisionFileRows).
        val fileRows = mutableListOf<CollisionFileRow>()
        val fileList = createCollisionFileList(collisionRows, fileRows)
        refreshCollisionFileRows(fileRows, decision)

        val card = createCollisionCard(
            createCollisionHeader(decision.total, decision.metaCount, decision.total - decision.metaCount),
            fileList,
        )

        return JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(card, BorderLayout.NORTH)
            add(
                createDecisionRow(collisions, decision) { newDecision ->
                    decision = newDecision
                    refreshCollisionFileRows(fileRows, newDecision)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    /**
     * The collision card: a left accent bar over a tinted background with no enclosing box —
     * the v1 "current card" look, where the severity is carried by the bar alone.
     */
    private fun createCollisionCard(header: JPanel, fileList: JBScrollPane): JPanel =
        JPanel(BorderLayout(0, 0)).apply {
            isOpaque = true
            background = panelBg
            border = BorderFactory.createMatteBorder(0, 3, 0, 0, warnColor)
            add(header, BorderLayout.NORTH)
            add(fileList, BorderLayout.CENTER)
        }

    /** "切换将覆盖 N 个未跟踪文件" plus a one-line split of safe .meta vs. deleted files. */
    private fun createCollisionHeader(total: Int, metaCount: Int, deletedCount: Int): JPanel {
        val title = ShrinkableLabel(Bundle.msg("dialog.collision.discard.message", total)).apply {
            font = font.deriveFont(Font.BOLD)
        }
        val breakdown = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false }
        if (metaCount > 0) {
            breakdown.add(JLabel(Bundle.msg("dialog.collision.discard.meta.count", metaCount)).apply {
                foreground = safeColor
            })
        }
        if (metaCount > 0 && deletedCount > 0) {
            breakdown.add(JLabel(" · ").apply { foreground = mutedColor })
        }
        if (deletedCount > 0) {
            breakdown.add(JLabel(Bundle.msg("dialog.collision.discard.deleted.count", deletedCount)).apply {
                foreground = warnColor
                font = font.deriveFont(Font.BOLD)
            })
        }
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = true
            background = panelBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                JBUI.Borders.empty(6, 10, 6, 10),
            )
            add(title, BorderLayout.WEST)
            add(breakdown, BorderLayout.EAST)
        }
    }

    /** The grouped, bounded-scroll list of colliding files; rows are collected for live updates. */
    private fun createCollisionFileList(
        collisionRows: List<PreflightRow>,
        fileRows: MutableList<CollisionFileRow>,
    ): JBScrollPane {
        val list = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = panelBg
        }
        for (row in collisionRows) {
            list.add(JLabel("▸ ${row.label}").apply {
                font = font.deriveFont(Font.BOLD)
                toolTipText = row.path
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(6, 8, 2, 8)
                background = panelBg
            })
            for (file in row.untrackedCollisions.sorted()) {
                val fileRow = createCollisionFileRow(file)
                fileRows += fileRow
                list.add(fileRow.rowPanel)
            }
        }
        return JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewport.background = panelBg
            preferredSize = Dimension(
                JBUI.scale(640),
                minOf(collisionRows.size * 40 + JBUI.scale(48), JBUI.scale(180)),
            )
        }
    }

    /**
     * One colliding file: the path at the left, the note at the right. [TrailingControlRowPanel]
     * gives the path the remaining row width (a long path truncates instead of widening the
     * card) and keeps the note compact on the right; the tooltip restores the full path on
     * hover. Paths stay in the default foreground for a flat, legible list — only the note
     * carries the color, and its text is (re)set by [refreshCollisionFileRows].
     */
    private fun createCollisionFileRow(file: String): CollisionFileRow {
        val pathLabel = JLabel(file).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            foreground = JBColor.foreground()
            toolTipText = file
        }
        val noteLabel = JLabel()
        val rowPanel = TrailingControlRowPanel(pathLabel, noteLabel, JBUI.scale(8)).apply {
            border = JBUI.Borders.empty(1, 8, 1, 8)
        }
        return CollisionFileRow(file, rowPanel, pathLabel, noteLabel)
    }

    /**
     * Recomputes a file row's note and colors for the current discard decision. A meta file is
     * always discarded; only its confirmation level changes (safe vs. auto). A non-meta file
     * is kept when only-meta is chosen — then it is dimmed and the note warns that this repo's
     * checkout will fail.
     */
    private fun refreshCollisionFileRows(rows: List<CollisionFileRow>, decision: CollisionDecision) {
        rows.forEach { applyDiscardState(it, decision) }
    }

    private fun applyDiscardState(row: CollisionFileRow, decision: CollisionDecision) {
        // The note text is a pure decision (UiRules); the dialog only maps it to colors.
        row.noteLabel.text = decision.noteFor(row.file)
        if (isCollisionFileMeta(row.file)) {
            row.noteLabel.foreground = if (decision.autoMeta) autoColor else safeColor
            row.noteLabel.font = row.noteLabel.font.deriveFont(Font.PLAIN)
            row.pathLabel.foreground = JBColor.foreground()
        } else {
            val discarded = !decision.onlyMeta
            row.noteLabel.foreground = if (discarded) warnColor else mutedColor
            row.noteLabel.font = row.noteLabel.font.deriveFont(if (discarded) Font.BOLD else Font.PLAIN)
            row.pathLabel.foreground = if (discarded) JBColor.foreground() else mutedColor
        }
    }

    private class CollisionFileRow(
        val file: String,
        val rowPanel: JPanel,
        val pathLabel: JLabel,
        val noteLabel: JLabel,
    )

    /**
     * The pinned discard options — "仅丢弃 .meta" (this switch) and "始终自动丢弃 .meta"
     * (persisted) — plus a live count of what would be discarded. Sits outside the file
     * list's scroll area so it never goes below the fold.
     */
    private fun createDecisionRow(
        collisions: List<String>,
        initialDecision: CollisionDecision,
        onDecisionChange: (CollisionDecision) -> Unit,
    ): JPanel {
        val onlyMetaCheck = JCheckBox(Bundle.msg("dialog.collision.discard.only.meta"))
        val autoMetaCheck = JCheckBox(Bundle.msg("dialog.collision.discard.remember")).apply {
            isSelected = initialDecision.autoMeta
        }
        val summaryLabel = JLabel().apply { foreground = mutedColor }

        // Each checkbox persists its own state; both then recompute one CollisionDecision from
        // the live state of both checkboxes, and the summary plus the file list read it.
        fun applyChange() {
            val decision = collisionDecision(collisions, onlyMetaCheck.isSelected, autoMetaCheck.isSelected)
            summaryLabel.text = decision.summary
            onDecisionChange(decision)
        }
        onlyMetaCheck.addActionListener {
            onlyMetaDiscard = onlyMetaCheck.isSelected
            applyChange()
        }
        autoMetaCheck.addActionListener {
            project.service<BranchSwitcherService>().autoDiscardMeta = autoMetaCheck.isSelected
            applyChange()
        }
        summaryLabel.text = initialDecision.summary

        // BoxLayout, not FlowLayout: FlowLayout hardcodes a 5px left inset that would push
        // the first checkbox off the left edge; BoxLayout has no insets, so the row is flush.
        // Both checkboxes are always shown: "仅丢弃 .meta" still governs whether non-.meta
        // collisions are approved, so it stays reachable even when auto-discard is on.
        val options = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        options.add(onlyMetaCheck)
        options.add(Box.createHorizontalStrut(JBUI.scale(18)))
        options.add(autoMetaCheck)

        return JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderColor),
                JBUI.Borders.empty(8, 2, 0, 2),
            )
            add(options, BorderLayout.WEST)
            add(summaryLabel, BorderLayout.EAST)
        }
    }

    // ── Summary line ────────────────────────────────────────
    private fun createSummary(): JPanel {
        val total = rows.size
        // Probe errors are unknown state: neither a repo we know needs switching nor a
        // missing branch. Exclude them from both counts so the summary stays truthful.
        val toSwitch = rows.count { it.exists && it.needsSwitch && !it.branchMissing && it.probeError == null }
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

    // ── Table model & cell renderers ────────────────────────
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

    /** Table cells are centered to match the centered column headers. */
    private abstract inner class CenteredCellRenderer : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    private inner class MutedIfNoChangeRenderer : CenteredCellRenderer() {
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

    private inner class TargetCellRenderer : CenteredCellRenderer() {
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

    private inner class DirtyRenderer : CenteredCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            val preflightRow = value as PreflightRow
            val text = when {
                !preflightRow.exists -> "—"
                preflightRow.dirtyCount < 0 -> "?"
                preflightRow.dirtyCount == 0 -> Bundle.msg("status.clean")
                preflightRow.untrackedCollisions.isNotEmpty() -> Bundle.msg(
                    "status.file.count.collision",
                    preflightRow.dirtyCount,
                    preflightRow.untrackedCollisions.size,
                )
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

    private inner class SourceRenderer : CenteredCellRenderer() {
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

}
