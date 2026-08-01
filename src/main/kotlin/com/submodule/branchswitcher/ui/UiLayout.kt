package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

fun <T : JComponent> T.withCompactHeight(maxWidth: Int): T {
    maximumSize = Dimension(maxWidth, preferredSize.height)
    return this
}

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
    }

    override fun doLayout() {
        val availableWidth = (width - insets.left - insets.right).coerceAtLeast(0)
        val contentLeft = insets.left.coerceIn(0, width.coerceAtLeast(0))
        val trailingSize = trailing.preferredSize
        val leadingSize = leading.preferredSize
        val trailingWidth = minOf(trailingSize.width, availableWidth)
        val roomBeforeTrailing = (availableWidth - trailingWidth).coerceAtLeast(0)
        val actualGap = minOf(horizontalGap, roomBeforeTrailing)
        val leadingWidth = roomBeforeTrailing - actualGap
        val rowHeight = maxOf(leadingSize.height, trailingSize.height)

        leading.setBounds(
            contentLeft,
            insets.top + (rowHeight - leadingSize.height) / 2,
            leadingWidth,
            leadingSize.height,
        )
        trailing.setBounds(
            contentLeft + leadingWidth + actualGap,
            insets.top + (rowHeight - trailingSize.height) / 2,
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
