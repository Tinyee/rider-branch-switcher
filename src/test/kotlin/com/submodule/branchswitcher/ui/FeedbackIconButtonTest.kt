package com.submodule.branchswitcher.ui

import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.Icon

class FeedbackIconButtonTest {

    @Test
    fun `action buttons are styled for toolbar use`() {
        val button = FeedbackIconButton(stubIcon())

        assertFalse(button.isFocusable)
        assertFalse(button.isOpaque)
        assertTrue("rollover tracking must be enabled for hover feedback", button.isRolloverEnabled)
        assertEquals(Dimension(JBUI.scale(26), JBUI.scale(24)), button.preferredSize)
    }

    @Test
    fun `flashIcon swaps icon and tooltip immediately and restoreFlash reverts both`() {
        val button = FeedbackIconButton(stubIcon("copy"))
        button.toolTipText = "original tooltip"

        button.flashIcon(stubIcon("check"), replacementToolTip = "copied")

        assertEquals("check", (button.icon as StubIcon).label)
        assertEquals("copied", button.toolTipText)

        button.restoreFlash()

        assertEquals("copy", (button.icon as StubIcon).label)
        assertEquals("original tooltip", button.toolTipText)
    }

    @Test
    fun `repeated flash restores the icon and tooltip from before the first flash`() {
        val button = FeedbackIconButton(stubIcon("copy"))
        button.toolTipText = "copy"

        button.flashIcon(stubIcon("check"), replacementToolTip = "copied")
        button.flashIcon(stubIcon("second"), replacementToolTip = "second")

        button.restoreFlash()

        assertEquals("copy", (button.icon as StubIcon).label)
        assertEquals("copy", button.toolTipText)
    }

    private fun stubIcon(label: String = "icon"): Icon = StubIcon(label)

    private class StubIcon(val label: String) : Icon {
        override fun getIconWidth(): Int = 16
        override fun getIconHeight(): Int = 16
        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) = Unit
    }
}
