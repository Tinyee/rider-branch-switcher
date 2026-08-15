package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.EventQueue
import javax.swing.JComponent
import javax.swing.JPanel

internal enum class ResponsiveRowArrangement {
    PACKED,
    PACKED_CENTER,
    PACKED_END,
    SPACE_BETWEEN,
    FILL_TRAILING,
}

/**
 * Places two regions side by side while they fit, then stacks them at their
 * rendered width. Packed modes preserve natural control sizes; fill mode is
 * reserved for form fields and can cap the trailing region. Preferred height
 * keeps the last rendered layout mode while hidden, so an ancestor cannot
 * allocate a one-line height before the row receives its current width.
 * Invisible regions do not reserve a gap or a second line.
 */
internal class ResponsiveRowPanel(
    private val leading: JComponent,
    private val trailing: JComponent,
    private val minimumLeadingWidth: Int? = null,
    private val minimumTrailingWidth: Int? = null,
    private val maximumTrailingWidth: Int? = null,
    private val horizontalGap: Int = JBUI.scale(8),
    private val verticalGap: Int = JBUI.scale(4),
    private val stackedTrailingIndent: Int = 0,
    private val arrangement: ResponsiveRowArrangement = ResponsiveRowArrangement.FILL_TRAILING,
) : JPanel(null) {
    private var stacked = false
    private var ancestorRelayoutScheduled = false

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(leading)
        add(trailing)

        listOf(leading, trailing).registerMetricsRelayout { requestRelayout() }
    }

    override fun doLayout() {
        refreshLayoutMode()
        when {
            leading.isVisible && trailing.isVisible -> {
                if (stacked) layoutStacked() else layoutHorizontal()
            }
            leading.isVisible -> layoutSingle(leading, isTrailing = false)
            trailing.isVisible -> layoutSingle(trailing, isTrailing = true)
        }
    }

    override fun getPreferredSize(): Dimension {
        val availableWidthRequiresStacking = effectiveLayoutWidth()?.let(::shouldStackAt) == true
        return contentSize(stacked || availableWidthRequiresStacking)
    }

    override fun getMinimumSize(): Dimension {
        val availableWidthRequiresStacking = effectiveLayoutWidth()?.let(::shouldStackAt) == true
        val effectiveStacked = stacked || availableWidthRequiresStacking
        return Dimension(
            insets.left + insets.right,
            contentSize(effectiveStacked).height,
        )
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    private fun refreshLayoutMode() {
        val shouldStack = shouldStackAt(width)
        if (stacked == shouldStack) return
        stacked = shouldStack
        requestRelayout()
    }

    private fun requestRelayout() {
        revalidate()
        repaint()
        scheduleAncestorRelayout()
    }

    /**
     * A row can discover that it must stack only after its parent has already
     * allocated heights for the current validation pass. Revalidate once on
     * the next EDT turn so BoxLayout recalculates every ancestor with the new
     * preferred height instead of clipping the trailing row.
     */
    private fun scheduleAncestorRelayout() {
        if (ancestorRelayoutScheduled) return
        ancestorRelayoutScheduled = true
        EventQueue.invokeLater {
            ancestorRelayoutScheduled = false
            generateSequence<JComponent>(this) { it.parent as? JComponent }
                .forEach(JComponent::revalidate)
            repaint()
        }
    }

    private fun shouldStackAt(componentWidth: Int): Boolean =
        leading.isVisible && trailing.isVisible &&
            availableContentWidth(componentWidth) < requiredHorizontalWidth()

    private fun requiredHorizontalWidth(): Int {
        val requestedWidth = minimumTrailingWidth ?: trailing.preferredSize.width
        val trailingWidth = maximumTrailingWidth?.let { minOf(requestedWidth, it) } ?: requestedWidth
        return horizontalLeadingWidth() + horizontalGap + trailingWidth
    }

    private fun layoutHorizontal() {
        val availableWidth = availableContentWidth()
        val leadingSize = leading.preferredSize
        val trailingSize = trailing.preferredSize
        val leadingWidth = minOf(horizontalLeadingWidth(), availableWidth)
        val remainingWidth = (availableWidth - leadingWidth - horizontalGap).coerceAtLeast(0)
        val trailingWidth = trailingWidth(remainingWidth)
        val rowHeight = maxOf(leadingSize.height, trailingSize.height)
        val leadingY = centeredY(rowHeight, leadingSize.height)
        val trailingY = centeredY(rowHeight, trailingSize.height)
        val packedWidth = leadingWidth + horizontalGap + trailingWidth
        val leadingX = when (arrangement) {
            ResponsiveRowArrangement.PACKED_CENTER ->
                contentLeft() + ((availableWidth - packedWidth) / 2).coerceAtLeast(0)
            ResponsiveRowArrangement.PACKED_END ->
                contentLeft() + (availableWidth - packedWidth).coerceAtLeast(0)
            ResponsiveRowArrangement.PACKED,
            ResponsiveRowArrangement.SPACE_BETWEEN,
            ResponsiveRowArrangement.FILL_TRAILING,
            -> contentLeft()
        }
        val trailingX = when (arrangement) {
            ResponsiveRowArrangement.SPACE_BETWEEN,
            ResponsiveRowArrangement.FILL_TRAILING,
            ->
                contentLeft() + availableWidth - trailingWidth
            ResponsiveRowArrangement.PACKED,
            ResponsiveRowArrangement.PACKED_CENTER,
            ResponsiveRowArrangement.PACKED_END,
            -> leadingX + leadingWidth + horizontalGap
        }

        leading.setBounds(leadingX, leadingY, leadingWidth, leadingSize.height)
        trailing.setBounds(trailingX, trailingY, trailingWidth, trailingSize.height)
    }

    private fun layoutStacked() {
        val availableWidth = availableContentWidth()
        val contentLeft = contentLeft()
        val leadingSize = leading.preferredSize
        val trailingSize = trailing.preferredSize
        val leadingWidth = minOf(leadingSize.width, availableWidth)
        val actualIndent = stackedTrailingIndent.coerceIn(0, availableWidth)
        val trailingAvailableWidth = availableWidth - actualIndent
        val trailingWidth = trailingWidth(trailingAvailableWidth)
        val leadingX: Int
        val trailingX: Int
        when (arrangement) {
            ResponsiveRowArrangement.PACKED_CENTER -> {
                leadingX = contentLeft + ((availableWidth - leadingWidth) / 2).coerceAtLeast(0)
                trailingX = contentLeft + ((availableWidth - trailingWidth) / 2).coerceAtLeast(0)
            }
            ResponsiveRowArrangement.PACKED_END -> {
                leadingX = contentLeft + (availableWidth - leadingWidth).coerceAtLeast(0)
                trailingX = contentLeft + (availableWidth - trailingWidth).coerceAtLeast(0)
            }
            ResponsiveRowArrangement.SPACE_BETWEEN -> {
                leadingX = contentLeft
                trailingX = contentLeft + actualIndent
            }
            ResponsiveRowArrangement.PACKED,
            ResponsiveRowArrangement.FILL_TRAILING,
            -> {
                leadingX = contentLeft
                trailingX = contentLeft + actualIndent
            }
        }

        leading.setBounds(leadingX, insets.top, leadingWidth, leadingSize.height)
        trailing.setBounds(
            trailingX,
            insets.top + leadingSize.height + verticalGap,
            trailingWidth,
            trailingSize.height,
        )
    }

    private fun layoutSingle(component: JComponent, isTrailing: Boolean) {
        val availableWidth = availableContentWidth()
        val componentSize = component.preferredSize
        val fillsRow = isTrailing && arrangement == ResponsiveRowArrangement.FILL_TRAILING
        val componentWidth = if (fillsRow) {
            availableWidth
        } else {
            minOf(componentSize.width, availableWidth)
        }
        val componentX = when (arrangement) {
            ResponsiveRowArrangement.PACKED_CENTER ->
                contentLeft() + (availableWidth - componentWidth) / 2
            ResponsiveRowArrangement.PACKED_END ->
                contentLeft() + availableWidth - componentWidth
            ResponsiveRowArrangement.SPACE_BETWEEN ->
                if (isTrailing) contentLeft() + availableWidth - componentWidth else contentLeft()
            ResponsiveRowArrangement.PACKED,
            ResponsiveRowArrangement.FILL_TRAILING,
            -> contentLeft()
        }
        component.setBounds(componentX, insets.top, componentWidth, componentSize.height)
    }

    private fun trailingWidth(availableWidth: Int): Int {
        val preferredWidth = when (arrangement) {
            ResponsiveRowArrangement.FILL_TRAILING -> availableWidth
            ResponsiveRowArrangement.PACKED,
            ResponsiveRowArrangement.PACKED_CENTER,
            ResponsiveRowArrangement.PACKED_END,
            ResponsiveRowArrangement.SPACE_BETWEEN,
            -> trailing.preferredSize.width
        }
        return minOf(preferredWidth, maximumTrailingWidth ?: preferredWidth, availableWidth)
            .coerceAtLeast(0)
    }

    private fun contentSize(stacked: Boolean): Dimension {
        val leadingSize = leading.preferredSize.takeIf { leading.isVisible }
        val trailingSize = trailing.preferredSize.takeIf { trailing.isVisible }
        if (leadingSize == null && trailingSize == null) {
            return Dimension(insets.left + insets.right, insets.top + insets.bottom)
        }
        if (leadingSize == null || trailingSize == null) {
            val visibleSize = leadingSize ?: trailingSize!!
            return Dimension(
                insets.left + visibleSize.width + insets.right,
                insets.top + visibleSize.height + insets.bottom,
            )
        }
        val trailingWidth = minOf(
            trailingSize.width,
            maximumTrailingWidth ?: trailingSize.width,
        )
        val contentWidth: Int
        val contentHeight: Int
        if (stacked) {
            // Keep requesting the width needed to return to one row. Nested
            // responsive rows otherwise remain permanently stacked because
            // their parent sees only the narrower stacked footprint.
            contentWidth = horizontalLeadingWidth() + horizontalGap + trailingWidth
            contentHeight = leadingSize.height + verticalGap + trailingSize.height
        } else {
            contentWidth = horizontalLeadingWidth() + horizontalGap + trailingWidth
            contentHeight = maxOf(leadingSize.height, trailingSize.height)
        }
        return Dimension(
            insets.left + contentWidth + insets.right,
            insets.top + contentHeight + insets.bottom,
        )
    }

    private fun horizontalLeadingWidth(): Int =
        maxOf(leading.preferredSize.width, minimumLeadingWidth ?: 0)
}
