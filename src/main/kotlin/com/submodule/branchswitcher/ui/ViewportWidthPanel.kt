package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable

/** Scroll content that always adopts the Tool Window viewport width. */
internal class ViewportWidthPanel(
    layout: LayoutManager? = null,
    private val fillViewportHeight: Boolean = false,
) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = maxOf(JBUI.scale(16), visibleRect.height - JBUI.scale(16))

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val viewport = parent as? JViewport
        return if (fillViewportHeight && viewport != null) {
            Dimension(size.width, maxOf(size.height, viewport.height))
        } else {
            size
        }
    }
}
