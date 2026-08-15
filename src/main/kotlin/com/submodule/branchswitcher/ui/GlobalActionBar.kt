package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JPanel

/** Matches the design's compact top action row without centering or wrapping controls. */
internal class GlobalActionBar(
    private val primary: JButton,
    private val addPreset: JButton,
    private val compactAtWidth: Int = COMPACT_WIDTH,
    private val horizontalGap: Int = JBUI.scale(6),
) : JPanel(null) {
    private val addPresetText = addPreset.text
    private var compact = false

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(primary)
        add(addPreset)
        addPreset.toolTipText = addPresetText
        addPreset.accessibleContext.accessibleName = addPresetText
        listOf(primary, addPreset).registerMetricsRelayout {
            revalidate()
            repaint()
        }
    }

    override fun doLayout() {
        refreshMode()
        val availableWidth = availableContentWidth()
        val primarySize = primary.preferredSize
        val addSize = addPreset.preferredSize
        val rowHeight = maxOf(primarySize.height, addSize.height)
        val addWidth = if (compact) minOf(JBUI.scale(34), availableWidth) else minOf(addSize.width, availableWidth)
        val actualGap = minOf(horizontalGap, (availableWidth - addWidth).coerceAtLeast(0))
        val contentX = contentLeft()
        val primaryWidth = if (compact) {
            (availableWidth - actualGap - addWidth).coerceAtLeast(0)
        } else {
            minOf(primarySize.width, availableWidth)
        }

        primary.setBounds(contentX, centeredY(rowHeight, primarySize.height), primaryWidth, primarySize.height)
        addPreset.setBounds(
            contentX + primaryWidth + actualGap,
            centeredY(rowHeight, addSize.height),
            minOf(addWidth, (availableWidth - primaryWidth - actualGap).coerceAtLeast(0)),
            addSize.height,
        )
    }

    override fun getPreferredSize(): Dimension {
        val primarySize = primary.preferredSize
        val addSize = addPreset.preferredSize
        return Dimension(
            insets.left + primarySize.width + horizontalGap + addSize.width + insets.right,
            insets.top + maxOf(primarySize.height, addSize.height) + insets.bottom,
        )
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    private fun refreshMode() {
        if (width <= 0) return
        val shouldCompact = width <= compactAtWidth
        if (compact == shouldCompact) return
        compact = shouldCompact
        addPreset.text = if (compact) "" else addPresetText
        revalidate()
        repaint()
    }
}
