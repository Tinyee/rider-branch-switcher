package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.LayoutManager
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Shared compact-transition threshold for the action bars. Single source of
 * truth so the two responsive thresholds cannot drift apart.
 */
internal val COMPACT_WIDTH: Int = JBUI.scale(340)

class CompactHeightPanel(layout: LayoutManager? = null) : JPanel(layout) {
    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}

/** Label that can yield horizontal space while retaining its natural height. */
internal class ShrinkableLabel(text: String = "") : JLabel(text) {
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
}

/** Keeps a compact trailing control visible and gives the remaining width to the leading content. */
internal class TrailingControlRowPanel(
    private val leading: JComponent,
    private val trailing: JComponent,
    private val horizontalGap: Int = JBUI.scale(8),
) : JPanel(null) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(leading)
        add(trailing)
        listOf(leading, trailing).registerMetricsRelayout {
            revalidate()
            repaint()
        }
    }

    override fun doLayout() {
        val availableWidth = availableContentWidth()
        val contentX = contentLeft()
        val trailingSize = trailing.preferredSize
        val leadingSize = leading.preferredSize
        val trailingWidth = minOf(trailingSize.width, availableWidth)
        val roomBeforeTrailing = (availableWidth - trailingWidth).coerceAtLeast(0)
        val actualGap = minOf(horizontalGap, roomBeforeTrailing)
        val leadingWidth = roomBeforeTrailing - actualGap
        val rowHeight = maxOf(leadingSize.height, trailingSize.height)

        leading.setBounds(
            contentX,
            centeredY(rowHeight, leadingSize.height),
            leadingWidth,
            leadingSize.height,
        )
        trailing.setBounds(
            contentX + leadingWidth + actualGap,
            centeredY(rowHeight, trailingSize.height),
            trailingWidth,
            trailingSize.height,
        )
    }

    override fun getPreferredSize(): Dimension {
        val leadingSize = leading.preferredSize
        val trailingSize = trailing.preferredSize
        return Dimension(
            insets.left + leadingSize.width + horizontalGap + trailingSize.width + insets.right,
            insets.top + maxOf(leadingSize.height, trailingSize.height) + insets.bottom,
        )
    }

    override fun getMinimumSize(): Dimension =
        Dimension(insets.left + insets.right, preferredSize.height)

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}

/** Conservative width hint for preferred sizing before a component receives its next bounds. */
internal fun Component.effectiveLayoutWidth(): Int? {
    return generateSequence(this) { it.parent }
        .map { it.width }
        .filter { it > 0 }
        .minOrNull()
}

/** Width available for content after the horizontal insets, never negative. */
internal fun JComponent.availableContentWidth(width: Int = this.width): Int =
    (width - insets.left - insets.right).coerceAtLeast(0)

/** Left edge of the content area, clamped into the component width. */
internal fun JComponent.contentLeft(width: Int = this.width): Int =
    insets.left.coerceIn(0, width.coerceAtLeast(0))

/** Vertical center of a [componentHeight] control within a [rowHeight] row, after the top inset. */
internal fun JComponent.centeredY(rowHeight: Int, componentHeight: Int): Int =
    insets.top + (rowHeight - componentHeight) / 2

/** Relayouts whenever a metric-affecting property changes on any of the components. */
internal fun Iterable<JComponent>.registerMetricsRelayout(onChange: () -> Unit) {
    val metricsChanged = PropertyChangeListener { onChange() }
    forEach { component ->
        component.addPropertyChangeListener("font", metricsChanged)
        component.addPropertyChangeListener("icon", metricsChanged)
        component.addPropertyChangeListener("preferredSize", metricsChanged)
        component.addPropertyChangeListener("text", metricsChanged)
        component.addPropertyChangeListener("visible", metricsChanged)
    }
}

/** Shared label/field row used by the main repository and every submodule. */
internal fun responsiveFormRow(label: JComponent, field: JComponent): JPanel {
    return ResponsiveRowPanel(
        leading = label,
        trailing = field,
        minimumLeadingWidth = JBUI.scale(112),
        minimumTrailingWidth = JBUI.scale(150),
        maximumTrailingWidth = JBUI.scale(280),
        horizontalGap = JBUI.scale(8),
        verticalGap = JBUI.scale(2),
        arrangement = ResponsiveRowArrangement.FILL_TRAILING,
    ).apply {
        border = JBUI.Borders.empty(2, 12, 2, 4)
    }
}
