package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JLabel

class UiLayoutTest {
    @Test
    fun `compact height panel never stretches vertically`() {
        val panel = CompactHeightPanel().apply {
            add(JLabel("compact"))
            preferredSize = Dimension(240, 42)
        }

        assertEquals(42, panel.maximumSize.height)
        assertEquals(Short.MAX_VALUE.toInt(), panel.maximumSize.width)
    }

    @Test
    fun `compact height component keeps preferred height and configured max width`() {
        val combo = JComboBox(arrayOf("one", "two"))
        val preferredHeight = combo.preferredSize.height

        combo.withCompactHeight(360)

        assertEquals(360, combo.maximumSize.width)
        assertEquals(preferredHeight, combo.maximumSize.height)
    }
}
