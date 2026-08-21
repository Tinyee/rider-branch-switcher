package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.LogEntry
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** Bounded Tool Window diagnostics with operation filtering and full-log access. */
internal class ToolWindowLogPanel : JPanel(BorderLayout()) {
    private val entries = ArrayDeque<LogEntry>()
    private var latestOperationId: String? = null
    private var isExpanded = false

    private val logTextPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }
    private val logScrollPane = JBScrollPane(logTextPane).apply {
        preferredSize = Dimension(0, JBUI.scale(96))
        isVisible = false
    }
    private val toggleLabel = JLabel(
        " ${Bundle.msg("label.log")}",
        AllIcons.General.ArrowRight,
        SwingConstants.LEFT,
    ).apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 4, 0, 0)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = toggleExpanded()
        })
    }
    private val currentOperationFilter = FeedbackIconButton(AllIcons.General.Filter, selectionAware = true).apply {
        toolTipText = Bundle.msg("log.filter.current")
        addActionListener { renderAll() }
    }
    private val copyButton = iconButton(
        AllIcons.General.Copy,
        Bundle.msg("log.copy.current"),
        ::copyCurrentOperation,
    )
    private val toolbar = createToolbar().apply { isVisible = false }
    private val header = JPanel(BorderLayout()).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        add(toggleLabel, BorderLayout.CENTER)
        add(toolbar, BorderLayout.EAST)
    }

    init {
        add(header, BorderLayout.NORTH)
        add(logScrollPane, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        val parentHeight = parent?.height?.takeIf { it > 0 } ?: return preferred
        if (!isExpanded) return preferred
        val boundedHeight = maxOf(header.preferredSize.height, parentHeight / 3)
        return Dimension(preferred.width, minOf(preferred.height, boundedHeight))
    }

    fun append(entry: LogEntry) {
        ApplicationManager.getApplication().invokeLater {
            val startedOperation = operationIdFrom(entry.message)
                ?.takeIf { "operation started" in entry.message }
            val operationChanged = startedOperation != null && startedOperation != latestOperationId
            if (startedOperation != null) latestOperationId = startedOperation
            entries.addLast(entry)
            while (entries.size > MAX_LOG_ENTRIES) entries.removeFirst()
            if (operationChanged && currentOperationFilter.isSelected) {
                renderAll()
            } else if (matchesActiveFilter(entry)) {
                appendToDocument(entry)
            }
        }
    }

    private fun createToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
        isOpaque = false
        add(currentOperationFilter)
        add(copyButton)
        add(iconButton(AllIcons.General.OpenDisk, Bundle.msg("log.open.full"), ::openFullLog))
        add(iconButton(AllIcons.General.Delete, Bundle.msg("log.clear"), ::clear))
    }

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit) = FeedbackIconButton(icon).apply {
        toolTipText = tooltip
        addActionListener { action() }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        logScrollPane.isVisible = isExpanded
        toolbar.isVisible = isExpanded
        toggleLabel.icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        revalidate()
        repaint()
    }

    private fun copyCurrentOperation() {
        val text = entriesForCurrentOperation().joinToString("\n", transform = ::formatEntry)
        if (text.isNotEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            copyButton.flashIcon(AllIcons.General.InspectionsOK, replacementToolTip = Bundle.msg("log.copied"))
        }
    }

    private fun openFullLog() {
        RevealFileAction.openFile(PathManager.getLogDir().resolve("idea.log"))
    }

    private fun clear() {
        entries.clear()
        latestOperationId = null
        logTextPane.text = ""
    }

    private fun renderAll() {
        logTextPane.text = ""
        visibleEntries().forEach(::appendToDocument)
    }

    private fun appendToDocument(entry: LogEntry) {
        val document = logTextPane.styledDocument
        try {
            document.insertString(document.length, formatEntry(entry) + "\n", attributesFor(entry.level))
            trimDocument()
            logTextPane.caretPosition = document.length
        } catch (_: Exception) {
            // Logging must not break the Tool Window while Swing is disposing the document.
        }
    }

    private fun visibleEntries(): List<LogEntry> =
        if (currentOperationFilter.isSelected) entriesForCurrentOperation() else entries.toList()

    private fun entriesForCurrentOperation(): List<LogEntry> {
        val operationId = latestOperationId ?: return entries.toList()
        return entries.filter { it.message.contains("[$operationId") }
    }

    private fun matchesActiveFilter(entry: LogEntry): Boolean =
        !currentOperationFilter.isSelected || latestOperationId == null ||
            entry.message.contains("[${latestOperationId}")

    private fun formatEntry(entry: LogEntry): String =
        "[${TIME_FORMAT.format(entry.createdAt.atZone(ZoneId.systemDefault()))}] ${entry.message}"

    private fun trimDocument() {
        val document = logTextPane.styledDocument
        val rootElement = document.defaultRootElement
        if (rootElement.elementCount <= MAX_LOG_LINES) return
        try {
            val linesToRemove = rootElement.elementCount - RETAINED_LOG_LINES
            document.remove(0, rootElement.getElement(linesToRemove).endOffset)
        } catch (_: Exception) {
            // Best effort; the next append retries.
        }
    }

    private fun attributesFor(level: LogEntry.Level): SimpleAttributeSet {
        val color = when (level) {
            LogEntry.Level.ERROR -> JBColor.RED
            LogEntry.Level.WARN -> WARN_AMBER
            LogEntry.Level.DEBUG -> JBColor.GRAY
            LogEntry.Level.ACTIVITY -> JBColor(0x1565C0, 0x42A5F5)
            LogEntry.Level.INFO -> logTextPane.foreground
        }
        return SimpleAttributeSet().apply { StyleConstants.setForeground(this, color) }
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val OPERATION_ID = Regex("\\[((?:switch|derive|single-switch)-[0-9a-f]{8})(?:/[^]]+)?]")
        private const val MAX_LOG_ENTRIES = 5_000
        private const val MAX_LOG_LINES = 5_000
        private const val RETAINED_LOG_LINES = 4_000

        internal fun operationIdFrom(message: String): String? =
            OPERATION_ID.find(message)?.groupValues?.get(1)
    }
}
