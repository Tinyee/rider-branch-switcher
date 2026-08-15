package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/** Keeps overflow reachable and hides the secondary action when it belongs in the popup. */
internal class CollapsibleActionBar(
    private val primary: JComponent,
    private val secondary: JComponent,
    private val overflow: JComponent,
    private val responsiveContext: JComponent? = null,
    private val horizontalGap: Int = JBUI.scale(4),
    private val minimumContextWidthForSecondary: Int = COMPACT_WIDTH,
) : JPanel(null) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(primary)
        add(secondary)
        add(overflow)

        listOf(primary, secondary, overflow).registerMetricsRelayout { requestRelayout() }
    }

    override fun doLayout() {
        refreshSecondaryVisibility()
        val primarySize = primary.preferredSize
        val secondarySize = secondary.preferredSize
        val overflowSize = overflow.preferredSize
        val availableWidth = availableContentWidth()
        val rowHeight = maxOf(primarySize.height, secondarySize.height, overflowSize.height)
        val visibleActions = listOf(primary, secondary, overflow).filter(JComponent::isVisible)
        val allocation = allocateWidths(visibleActions, availableWidth)
        var nextX = contentLeft()

        visibleActions.forEachIndexed { index, component ->
            val preferredSize = component.preferredSize
            component.setBounds(
                nextX,
                centeredY(rowHeight, preferredSize.height),
                allocation.widths[index],
                preferredSize.height,
            )
            nextX += allocation.widths[index] + allocation.gap
        }
    }

    override fun getPreferredSize(): Dimension {
        val primarySize = primary.preferredSize
        val secondarySize = secondary.preferredSize
        val overflowSize = overflow.preferredSize
        return Dimension(
            insets.left + desiredPreferredWidth(includeSecondary = contextAllowsSecondary()) + insets.right,
            insets.top + maxOf(primarySize.height, secondarySize.height, overflowSize.height) + insets.bottom,
        )
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    fun refreshLayoutState() {
        requestRelayout()
    }

    private fun requestRelayout() {
        revalidate()
        repaint()
    }

    private fun refreshSecondaryVisibility() {
        val showSecondary = contextAllowsSecondary() && width >= requiredFullWidth()
        if (secondary.isVisible == showSecondary) return
        secondary.isVisible = showSecondary
        revalidate()
        repaint()
    }

    private fun contextAllowsSecondary(): Boolean {
        val contextWidth = (responsiveContext ?: this).width.takeIf { it > 0 }
        return contextWidth == null || contextWidth >= minimumContextWidthForSecondary
    }

    private fun requiredFullWidth(): Int =
        insets.left + insets.right +
            (if (primary.isVisible) primary.preferredSize.width + horizontalGap else 0) +
            secondary.preferredSize.width +
            horizontalGap + overflow.preferredSize.width

    private fun desiredPreferredWidth(includeSecondary: Boolean): Int {
        val desiredActions = listOfNotNull(
            primary.takeIf(JComponent::isVisible),
            secondary.takeIf { includeSecondary },
            overflow,
        )
        return desiredActions.sumOf { it.preferredSize.width } +
            horizontalGap * (desiredActions.size - 1).coerceAtLeast(0)
    }

    private fun allocateWidths(actions: List<JComponent>, availableWidth: Int): ActionAllocation {
        if (actions.isEmpty()) return ActionAllocation(IntArray(0), 0)
        val widths = IntArray(actions.size) { actions[it].preferredSize.width }
        val gapCount = (actions.size - 1).coerceAtLeast(0)
        var excess = (widths.sum() + horizontalGap * gapCount - availableWidth).coerceAtLeast(0)

        // Preserve the final overflow action until every earlier action and gap is exhausted.
        for (index in 0 until actions.lastIndex) {
            val shrinkBy = minOf(widths[index], excess)
            widths[index] -= shrinkBy
            excess -= shrinkBy
            if (excess == 0) return ActionAllocation(widths, horizontalGap)
        }
        val remainingGapBudget = (horizontalGap * gapCount - excess).coerceAtLeast(0)
        val allocatedGap = if (gapCount == 0) 0 else remainingGapBudget / gapCount
        excess = (excess - (horizontalGap - allocatedGap) * gapCount).coerceAtLeast(0)
        if (excess > 0) {
            widths[actions.lastIndex] = (widths.last() - excess).coerceAtLeast(0)
        }
        return ActionAllocation(widths, allocatedGap)
    }

    private data class ActionAllocation(val widths: IntArray, val gap: Int)
}
