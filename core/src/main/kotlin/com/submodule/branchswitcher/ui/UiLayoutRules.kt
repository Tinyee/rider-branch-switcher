package com.submodule.branchswitcher.ui

import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

fun shouldShowSecondaryAction(availableWidth: Int, requiredWidth: Int): Boolean =
    availableWidth <= 0 || availableWidth >= requiredWidth

fun <T : JComponent> T.withCompactHeight(maxWidth: Int): T {
    maximumSize = Dimension(maxWidth, preferredSize.height)
    return this
}

class CompactHeightPanel(layout: LayoutManager? = null) : JPanel(layout) {
    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
}
