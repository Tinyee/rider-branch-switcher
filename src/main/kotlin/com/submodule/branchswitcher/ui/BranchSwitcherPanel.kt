package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatusListener
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.settings.BranchSwitcherConfigurable
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JProgressBar
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Main tool window panel with header + card layout.
 *
 * Layout (BorderLayout):
 * - NORTH: header (title + current branch + more menu) + action row + status bar
 * - CENTER: scrollable preset cards
 * - SOUTH: collapsible log panel (toggle to show/hide)
 *
 * Thread safety: uses [service.scope] for background git probes;
 * [detectCurrentState] uses generation-based stale detection.
 */
class BranchSwitcherPanel(
    private val project: Project,
    private val service: BranchSwitcherService,
) : JPanel(BorderLayout()), Disposable {

    // ── UI state ───────────────────────────────────────────────
    private val currentBranchLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }
    private val strategyLabel = JLabel().apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = Bundle.msg("label.strategy.tip")
        // Allow BoxLayout to shrink this label in narrow windows
        minimumSize = Dimension(0, preferredSize.height)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { openSettings() }
        })
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = object : JPanel() {
        init { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        override fun getPreferredSize(): Dimension {
            val pref = super.getPreferredSize()
            val p = parent
            if (p is javax.swing.JViewport) pref.height = maxOf(pref.height, p.height)
            return pref
        }
    }.apply {
        add(presetsInner)
        add(Box.createVerticalGlue())
    }
    private val presetsScroll = JBScrollPane(presetsContainer).apply {
        border = BorderFactory.createEmptyBorder()
    }

    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        isVisible = false
        preferredSize = Dimension(JBUI.scale(200), JBUI.scale(16))
    }

    private val log = javax.swing.JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }
    private var logVisible = false
    private lateinit var logToggle: JLabel
    private lateinit var logScroll: JBScrollPane
    private val stateRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    // ── Delegates (after UI fields to resolve init order) ──────
    private val presetManager = PresetListManager(
        project, service, ::gitRoot, ::append,
        onSwitch = { preset -> switchController.runSwitch(preset) },
        onDerive = { root, preset, name -> switchController.derivePresetBranch(root, preset, name) },
    )
    @Suppress("unused")
    private lateinit var switchController: SwitchController // lateinit: presetManager captures before init{}
    private var worktreeInfoLogged = false

    init {
        switchController = SwitchController(
            project, service, ::gitRoot, ::append,
            editors = { presetManager.editors },
            onStateChanged = ::detectCurrentState,
            progressBar = progressBar,
        )
        presetManager.onStateChanged = ::detectCurrentState
        border = JBUI.Borders.empty(6, 8, 4, 8)
        minimumSize = Dimension(JBUI.scale(280), minimumSize.height)

        add(createTopBlock(), BorderLayout.NORTH)
        add(presetsScroll, BorderLayout.CENTER)
        add(createLogPanel(), BorderLayout.SOUTH)

        presetManager.reload(presetsInner)
        detectCurrentState()
        refreshStrategySummary()
        wireEventSubscriptions()
    }

    // ── Top block: header + action row + status ────────────────

    private fun createTopBlock(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(createHeaderRow())
            add(Box.createVerticalStrut(4))
            add(createActionRow())
            add(Box.createVerticalStrut(2))
            add(createStatusBar())
        }
    }

    private fun createHeaderRow(): JPanel {
        return CompactHeightPanel(BorderLayout()).apply {
            isOpaque = false
            add(currentBranchLabel, BorderLayout.WEST)
            add(createMoreActionsButton(), BorderLayout.EAST)
        }
    }

    private fun createActionRow(): JPanel {
        return CompactHeightPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JButton(Bundle.msg("action.from.current"), AllIcons.Vcs.Branch).noFocusRing().also {
                it.toolTipText = Bundle.msg("action.from.current.tip")
                it.addActionListener { presetManager.addPresetFromCurrent(presetsInner) }
            })
            add(Box.createHorizontalStrut(4))
            add(JButton(Bundle.msg("action.add.preset"), AllIcons.General.Add).noFocusRing()
                .also { it.addActionListener { presetManager.addPreset(presetsInner) } })
            add(Box.createHorizontalGlue())
            add(strategyLabel)
        }
    }

    private fun createMoreActionsButton(): JButton {
        return JButton(AllIcons.Actions.MoreHorizontal).apply {
            margin = JBUI.insets(0, 4, 0, 4)
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = Bundle.msg("action.more.tip")
            addActionListener {
                createMoreMenu().show(this, 0, height)
            }
        }.noFocusRing()
    }

    private fun createMoreMenu(): JPopupMenu {
        return JPopupMenu().apply {
            add(JMenuItem(Bundle.msg("action.reload"), AllIcons.Actions.Refresh).apply {
                addActionListener { presetManager.reload(presetsInner); detectCurrentState() }
            })
            add(JMenuItem(Bundle.msg("action.open.config"), AllIcons.Actions.EditSource).apply {
                addActionListener { presetManager.openConfig() }
            })
            addSeparator()
            add(JMenuItem(Bundle.msg("action.import"), AllIcons.Actions.MenuSaveall).apply {
                addActionListener { presetManager.importPresets(presetsContainer) }
            })
            add(JMenuItem(Bundle.msg("action.export"), AllIcons.Actions.MenuSaveall).apply {
                addActionListener { presetManager.exportPresets() }
            })
            addSeparator()
            add(JMenuItem(Bundle.msg("action.undo"), AllIcons.Actions.Rollback).apply {
                addActionListener { switchController.undoLastSwitch() }
            })
            addSeparator()
            add(JMenuItem(Bundle.msg("action.settings"), AllIcons.General.Gear).apply {
                addActionListener { openSettings() }
            })
        }
    }

    // ── Strategy summary ───────────────────────────────────────

    private fun refreshStrategySummary() {
        strategyLabel.text = strategySummary(
            service.dirtyAction,
            service.fetchFirst,
            service.pullAfterSwitch,
            service.timeoutSeconds,
        )
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, BranchSwitcherConfigurable::class.java)
        refreshStrategySummary()
    }

    // ── Status bar: progress + border ──────────────────────────

    private fun createStatusBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(2, 2, 4, 2),
            )
            add(progressBar, BorderLayout.EAST)
        }
    }

    // ── Log panel (collapsible) ────────────────────────────────

    private fun createLogPanel(): JPanel {
        logScroll = JBScrollPane(log).apply {
            preferredSize = Dimension(0, JBUI.scale(80))
            isVisible = false
        }
        logToggle = JLabel(" Log", AllIcons.General.ArrowRight, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(2, 4, 0, 0),
            )
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { toggleLog() }
            })
        }
        return JPanel(BorderLayout()).apply {
            add(logToggle, BorderLayout.NORTH)
            add(logScroll, BorderLayout.CENTER)
        }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        logScroll.isVisible = logVisible
        logToggle.icon = if (logVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        revalidate()
        repaint()
    }

    // ── Event subscriptions ────────────────────────────────────

    private fun wireEventSubscriptions() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(BranchSwitchListener.TOPIC, object : BranchSwitchListener {
            override fun onBranchSwitched() {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { detectCurrentState() }
            }
        })
        FileStatusManager.getInstance(project).addFileStatusListener(object : FileStatusListener {
            override fun fileStatusesChanged() {
                scheduleStateRefresh()
            }

            override fun fileStatusChanged(virtualFile: VirtualFile) {
                val root = gitRoot()?.toString()?.replace('\\', '/') ?: return
                val path = virtualFile.path.replace('\\', '/')
                if (path == root || path.startsWith("$root/")) {
                    scheduleStateRefresh()
                }
            }
        }, this)
        addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    detectCurrentState()
                    refreshStrategySummary()
                }
            }
        }
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    val owner = kfm.focusOwner
                    if (owner != null && SwingUtilities.isDescendingFrom(owner, this@BranchSwitcherPanel)) {
                        kfm.clearGlobalFocusOwner()
                    }
                }
            }
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {}
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }

    private fun scheduleStateRefresh() {
        if (!isShowing || project.isDisposed) return
        stateRefreshAlarm.cancelAllRequests()
        stateRefreshAlarm.addRequest({ detectCurrentState() }, 750)
    }

    override fun dispose() {
        // Alarm and listeners are registered as children of this panel.
    }

    private fun ideBase(): Path? = project.basePath?.let { Paths.get(it) }

    private fun gitRoot(): Path? {
        val base = ideBase() ?: return null
        var cur: Path? = base
        while (cur != null) {
            val dotGit = cur.resolve(".git")
            if (java.nio.file.Files.exists(dotGit)) {
                if (!java.nio.file.Files.isDirectory(dotGit) && !worktreeInfoLogged) {
                    worktreeInfoLogged = true
                    append("[info] detected git worktree — .git is a file, not a directory")
                }
                return cur
            }
            cur = cur.parent
        }
        return base
    }

    /**
     * Probes all editor paths (main + submodules) in the background, then updates UI.
     * Uses generation-based stale detection: if a newer [detectCurrentState] call starts
     * before this one finishes, the stale result is discarded via [service.getDetectGen].
     */
    private fun detectCurrentState() {
        val root = gitRoot() ?: return
        val eds = presetManager.editors
        val paths = LinkedHashSet<String>().apply { add(".") }
        eds.forEach { paths.addAll(it.currentPreset().submodules.keys) }
        val snapshot = paths.toList()
        val pinnedEditors = eds.toList()
        val gen = service.nextDetectGen()
        service.scope.launch {
            val branches = HashMap<String, String?>(snapshot.size)
            val dirty = HashMap<String, Boolean>(snapshot.size)
            for (p in snapshot) {
                val dir = if (p == ".") root.toFile() else root.resolve(p).toFile()
                branches[p] = if (dir.exists()) service.gitClient.currentBranch(dir) else null
                dirty[p] = if (dir.exists()) service.gitClient.isDirty(dir) else false
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (gen != service.getDetectGen()) return@invokeLater
                pinnedEditors.forEach { editor ->
                    if (editor in eds) editor.applyCurrentState(branches, dirty)
                }
                presetsInner.revalidate()
                presetsInner.repaint()
                logDetected(eds.toList(), branches, dirty)
            }
        }
    }

    private fun logDetected(eds: List<PresetEditor>, branches: Map<String, String?>, dirtyRepos: Map<String, Boolean>) {
        val main = branches["."] ?: "(detached)"
        val mainDirty = dirtyRepos["."] == true
        val matched = eds.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "${Bundle.msg("label.main.branch")} $main"
        if (mainDirty) {
            currentBranchLabel.text += " · ${Bundle.msg("status.tooltip.dirty")}"
            currentBranchLabel.foreground = JBColor(0xE07B00, 0xFFA726)
        } else {
            currentBranchLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        append("[detect] main=$main${if (mainDirty) " (dirty)" else ""}, matched=${matched ?: "<none>"}")
    }

    /**
     * Appends a line to the log with color coding:
     * - ERROR/FAIL/FATAL → red
     * - WARN → orange
     * - DETECT/SAVED/ADDED/EXPORTED/IMPORTED → gray
     * - DERIVE/ROLLBACK → blue
     * - default → theme foreground
     */
    private fun append(line: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val doc = log.styledDocument
            // Cap log at ~5000 lines to prevent unbounded memory growth
            val root = doc.defaultRootElement
            if (root.elementCount > 5000) {
                try {
                    val removeCount = root.elementCount - 4000
                    val endOffset = root.getElement(removeCount).endOffset
                    doc.remove(0, endOffset)
                } catch (_: Exception) {}
            }
            val color = when {
                line.contains("[error]") || line.contains("[fail]") || line.contains("[fatal]") || line.startsWith("ERROR") -> JBColor.RED
                line.contains("[warn]") || line.startsWith("WARN") -> JBColor(0xE07B00, 0xFFA726)
                line.contains("[detect]") || line.contains("[saved]") || line.contains("[added]") || line.contains("[exported]") || line.contains("[imported]") -> JBColor.GRAY
                line.contains("[derive]") || line.contains("[rollback]") -> JBColor(0x1565C0, 0x42A5F5)
                else -> log.foreground
            }
            val attrs = javax.swing.text.SimpleAttributeSet()
            javax.swing.text.StyleConstants.setForeground(attrs, color)
            try { doc.insertString(doc.length, line + "\n", attrs) } catch (_: Exception) {}
            log.caretPosition = doc.length
        }
    }
}
