package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.log.LogEntry
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Collapsible log view used by the Tool Window.
 *
 * This component owns log rendering, document trimming, and expanded state so
 * [BranchSwitcherPanel] only coordinates project and preset state.
 */
internal class ToolWindowLogPanel : JPanel(BorderLayout()) {

    private val logTextPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }
    private val logScrollPane = JBScrollPane(logTextPane).apply {
        preferredSize = Dimension(0, JBUI.scale(80))
        isVisible = false
    }
    private val toggleLabel = JLabel(" Log", AllIcons.General.ArrowRight, SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            JBUI.Borders.empty(2, 4, 0, 0),
        )
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                toggleExpanded()
            }
        })
    }
    private var isExpanded = false

    init {
        add(toggleLabel, BorderLayout.NORTH)
        add(logScrollPane, BorderLayout.CENTER)
    }

    fun append(entry: LogEntry) {
        ApplicationManager.getApplication().invokeLater {
            trimDocument()
            val document = logTextPane.styledDocument
            val attributes = attributesFor(entry.level)
            runCatching {
                document.insertString(document.length, entry.message + "\n", attributes)
                logTextPane.caretPosition = document.length
            }
        }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        logScrollPane.isVisible = isExpanded
        toggleLabel.icon = if (isExpanded) {
            AllIcons.General.ArrowDown
        } else {
            AllIcons.General.ArrowRight
        }
        revalidate()
        repaint()
    }

    private fun trimDocument() {
        val document = logTextPane.styledDocument
        val rootElement = document.defaultRootElement
        if (rootElement.elementCount <= MAX_LOG_LINES) return

        runCatching {
            val linesToRemove = rootElement.elementCount - RETAINED_LOG_LINES
            val endOffset = rootElement.getElement(linesToRemove).endOffset
            document.remove(0, endOffset)
        }
    }

    private fun attributesFor(level: LogEntry.Level): SimpleAttributeSet {
        val color = when (level) {
            LogEntry.Level.ERROR -> JBColor.RED
            LogEntry.Level.WARN -> JBColor(0xE07B00, 0xFFA726)
            LogEntry.Level.DEBUG -> JBColor.GRAY
            LogEntry.Level.ACTIVITY -> JBColor(0x1565C0, 0x42A5F5)
            LogEntry.Level.INFO -> logTextPane.foreground
        }
        return SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, color)
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 5_000
        private const val RETAINED_LOG_LINES = 4_000
    }
}
