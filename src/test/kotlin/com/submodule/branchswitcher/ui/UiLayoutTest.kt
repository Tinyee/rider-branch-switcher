package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.border.EmptyBorder

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
    fun `scroll content adopts viewport width instead of clipping its preferred width`() {
        val content = ViewportWidthPanel().apply {
            preferredSize = Dimension(640, 200)
        }
        val viewport = JViewport().apply {
            view = content
            size = Dimension(280, 120)
            extentSize = size
        }

        viewport.doLayout()

        assertTrue(content.scrollableTracksViewportWidth)
        assertEquals(280, content.width)
    }

    @Test
    fun `responsive row stacks only when its rendered width cannot fit both regions`() {
        val label = JLabel("A long repository label").apply { preferredSize = Dimension(180, 24) }
        val field = JComboBox(arrayOf("main")).apply { preferredSize = Dimension(180, 28) }
        val row = ResponsiveRowPanel(
            label,
            field,
            minimumLeadingWidth = 120,
            minimumTrailingWidth = 150,
            maximumTrailingWidth = 200,
        )

        row.setSize(250, 80)
        row.doLayout()

        assertEquals(0, row.minimumSize.width)
        assertTrue(field.y > label.y)
        assertEquals(180, label.width)
        assertTrue(field.x + field.width <= row.width)

        row.setSize(420, 40)
        row.doLayout()

        assertEquals(label.y + label.height / 2, field.y + field.height / 2)
        assertEquals(200, field.width)
        assertEquals(row.width, field.x + field.width)
    }

    @Test
    fun `responsive row ignores hidden regions and contains children at extreme widths`() {
        val identity = JLabel("preset").apply { preferredSize = Dimension(100, 24) }
        val subtitle = JLabel("Main repo changed").apply {
            preferredSize = Dimension(160, 20)
            isVisible = false
        }
        val row = ResponsiveRowPanel(
            leading = identity,
            trailing = subtitle,
            stackedTrailingIndent = 20,
            arrangement = ResponsiveRowArrangement.PACKED,
        ).apply {
            border = EmptyBorder(0, 12, 0, 4)
        }

        assertEquals(24, row.preferredSize.height)

        listOf(0, 1, 5, 12, 16, 20, 80).forEach { extremeWidth ->
            row.setSize(extremeWidth, 80)
            row.doLayout()

            assertTrue(identity.x >= 0)
            assertTrue(identity.x + identity.width <= row.width)
        }

        subtitle.isVisible = true
        listOf(0, 1, 5, 16).forEach { extremeWidth ->
            row.setSize(extremeWidth, 80)
            row.doLayout()

            assertTrue(subtitle.y > identity.y)
            assertTrue(identity.x + identity.width <= row.width)
            assertTrue(subtitle.x >= 0)
            assertTrue(subtitle.x + subtitle.width <= row.width)
        }
    }

    @Test
    fun `responsive footer keeps stacked height while hidden width is stale`() {
        val add = JButton("Add submodule").apply { preferredSize = Dimension(140, 30) }
        val discard = JButton("Discard").apply { preferredSize = Dimension(110, 30) }
        val save = JButton("Save").apply { preferredSize = Dimension(90, 30) }
        val saveActions = ResponsiveRowPanel(
            leading = discard,
            trailing = save,
            horizontalGap = 4,
            verticalGap = 4,
            arrangement = ResponsiveRowArrangement.PACKED,
        )
        val footer = ResponsiveRowPanel(
            leading = add,
            trailing = saveActions,
            horizontalGap = 8,
            verticalGap = 4,
            arrangement = ResponsiveRowArrangement.SPACE_BETWEEN,
        )
        val body = CompactHeightPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(footer)
        }
        val editor = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(body)
            size = Dimension(250, 100)
        }

        footer.setSize(250, 80)
        footer.doLayout()
        val stackedHeight = footer.preferredSize.height

        body.isVisible = false
        editor.setSize(400, 100)
        body.setSize(400, 30)
        footer.setSize(400, 30)
        body.isVisible = true

        assertEquals(stackedHeight, footer.preferredSize.height)

        editor.setSize(250, 100)
        editor.doLayout()
        body.doLayout()
        footer.doLayout()

        assertTrue(saveActions.y > add.y)
        assertTrue(saveActions.y + saveActions.height <= footer.height)

        editor.setSize(400, 100)
        body.setSize(400, 30)
        footer.setSize(400, 30)
        footer.doLayout()

        assertEquals(add.y + add.height / 2, saveActions.y + saveActions.height / 2)
        assertEquals(30, footer.preferredSize.height)

        listOf(80, 120, 160).forEach { narrowWidth ->
            editor.setSize(narrowWidth, 200)
            body.setSize(narrowWidth, 200)
            footer.setSize(narrowWidth, 200)
            footer.doLayout()
            saveActions.doLayout()
            footer.setSize(narrowWidth, footer.preferredSize.height)
            footer.doLayout()
            saveActions.doLayout()

            assertTrue(discard.y + discard.height <= saveActions.height)
            assertTrue(save.y + save.height <= saveActions.height)
            assertTrue(discard.x + discard.width <= saveActions.width)
            assertTrue(save.x + save.width <= saveActions.width)
            assertTrue(saveActions.y + saveActions.height <= footer.height)
            assertEquals(add.x, saveActions.x + discard.x)
            assertEquals(discard.x, save.x)
        }

        editor.setSize(400, 100)
        body.setSize(400, 100)
        footer.setSize(400, 100)
        footer.doLayout()
        saveActions.doLayout()

        assertEquals(discard.y + discard.height / 2, save.y + save.height / 2)
        assertEquals(add.y + add.height / 2, saveActions.y + saveActions.height / 2)
    }

    @Test
    fun `action bar keeps overflow visible and collapses only the secondary action`() {
        val primary = JButton("Switch").apply { preferredSize = Dimension(180, 30) }
        val secondary = JButton("Derive").apply { preferredSize = Dimension(120, 30) }
        val overflow = JButton("...").apply { preferredSize = Dimension(32, 30) }
        val context = JLabel().apply { size = Dimension(300, 30) }
        val actions = CollapsibleActionBar(primary, secondary, overflow, responsiveContext = context)

        actions.setSize(250, 30)
        actions.doLayout()

        assertFalse(secondary.isVisible)
        assertTrue(primary.isVisible)
        assertTrue(overflow.isVisible)
        assertEquals(180, primary.width)
        assertEquals(216, actions.preferredSize.width)
        assertTrue(actions.components.all { it.x + it.width <= actions.width })

        actions.setSize(100, 30)
        actions.doLayout()

        assertEquals(64, primary.width)
        assertEquals(32, overflow.width)
        assertEquals(68, overflow.x)

        context.size = Dimension(350, 30)
        actions.setSize(156, 30)
        actions.doLayout()

        assertFalse(secondary.isVisible)
        primary.isVisible = false
        actions.refreshLayoutState()
        actions.doLayout()

        assertTrue(secondary.isVisible)
        assertEquals(120, secondary.width)
        assertEquals(124, overflow.x)

        primary.isVisible = true
        context.size = Dimension(400, 30)
        actions.setSize(340, 30)
        actions.doLayout()

        assertTrue(secondary.isVisible)
        assertEquals(180, primary.width)
        assertEquals(120, secondary.width)

        assertEquals(340, actions.preferredSize.width)
    }

    @Test
    fun `global actions stay left aligned and use an icon-only add action when narrow`() {
        val primary = JButton("From current state").apply { preferredSize = Dimension(150, 30) }
        val add = JButton("Add preset").apply { preferredSize = Dimension(100, 30) }
        val actions = GlobalActionBar(primary, add, compactAtWidth = 340, horizontalGap = 6)

        actions.setSize(400, 30)
        actions.doLayout()

        assertEquals(0, primary.x)
        assertEquals(150, primary.width)
        assertEquals(156, add.x)
        assertEquals("Add preset", add.text)

        actions.setSize(300, 30)
        actions.doLayout()

        assertEquals(0, primary.x)
        assertEquals(260, primary.width)
        assertEquals(266, add.x)
        assertEquals(34, add.width)
        assertEquals("", add.text)
        assertEquals("Add preset", add.accessibleContext.accessibleName)

        listOf(0, 1, 5, 20, 39, 40, 80).forEach { extremeWidth ->
            actions.setSize(extremeWidth, 30)
            actions.doLayout()

            assertTrue(primary.x >= 0)
            assertTrue(add.x >= 0)
            assertTrue(primary.x + primary.width <= actions.width)
            assertTrue(add.x + add.width <= actions.width)
        }
    }

    @Test
    fun `trailing control remains reachable when leading text is wider than the row`() {
        val label = ShrinkableLabel("Main: feature/a-very-long-branch-name").apply {
            preferredSize = Dimension(280, 24)
        }
        val more = JButton("...").apply { preferredSize = Dimension(32, 24) }
        val row = TrailingControlRowPanel(label, more, horizontalGap = 8)

        listOf(24, 40, 80, 180, 400).forEach { rowWidth ->
            row.setSize(rowWidth, 24)
            row.doLayout()

            assertEquals(0, label.x)
            assertTrue(label.width >= 0)
            assertTrue(more.x >= 0)
            assertTrue(more.x + more.width <= row.width)
            assertTrue(label.x + label.width <= more.x)
        }

        assertEquals(0, label.minimumSize.width)
    }

    @Test
    fun `action bars register metric relayout listeners on their children`() {
        val primary = JButton("Switch")
        val add = JButton("Add")
        val before = metricListenerCounts(primary, add)

        GlobalActionBar(primary, add, compactAtWidth = 340, horizontalGap = 6)

        assertMetricListenersAdded(before, primary, add)
    }

    @Test
    fun `trailing control registers metric relayout listeners on its children`() {
        val label = ShrinkableLabel("Main: branch")
        val more = JButton("...")
        val before = metricListenerCounts(label, more)

        TrailingControlRowPanel(label, more, horizontalGap = 8)

        assertMetricListenersAdded(before, label, more)
    }

    private fun metricListenerCounts(
        vararg components: javax.swing.JComponent,
    ): Map<javax.swing.JComponent, Map<String, Int>> =
        components.associateWith { component ->
            METRIC_PROPERTIES.associateWith { property ->
                component.getPropertyChangeListeners(property).size
            }
        }

    private fun assertMetricListenersAdded(
        before: Map<javax.swing.JComponent, Map<String, Int>>,
        vararg components: javax.swing.JComponent,
    ) {
        components.forEach { component ->
            METRIC_PROPERTIES.forEach { property ->
                val after = component.getPropertyChangeListeners(property).size
                assertEquals(
                    "listener count for '$property' should increase by 1 per child",
                    before.getValue(component).getValue(property) + 1,
                    after,
                )
            }
        }
    }

    companion object {
        private val METRIC_PROPERTIES = listOf("font", "icon", "preferredSize", "text", "visible")
    }
}
