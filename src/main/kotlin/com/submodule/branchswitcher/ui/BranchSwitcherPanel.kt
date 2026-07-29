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
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.LogEntry
import com.submodule.branchswitcher.log.ToolWindowLogger
import com.submodule.branchswitcher.platform.gitRootPath
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.workflow.RepositoryStateDetector
import com.submodule.branchswitcher.settings.BranchSwitcherConfigurable
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * Main tool window panel with header + card layout.
 *
 * Layout (BorderLayout):
 * - NORTH: header (title + current branch + more menu) + action row
 * - CENTER: scrollable preset cards
 * - SOUTH: collapsible log panel (toggle to show/hide)
 *
 * Thread safety: uses [service.scope] for background git probes and
 * [RepositoryStateDetector] for generation-based stale detection.
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
            val viewport = parent
            if (viewport is javax.swing.JViewport) {
                pref.height = maxOf(pref.height, viewport.height)
            }
            return pref
        }
    }.apply {
        add(presetsInner)
        add(Box.createVerticalGlue())
    }
    private val presetsScroll = JBScrollPane(presetsContainer).apply {
        border = BorderFactory.createEmptyBorder()
    }

    private val logPanel = ToolWindowLogPanel()
    private val stateRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    // ── Logger ──────────────────────────────────────────────────
    private val logger: AppLogger = ToolWindowLogger(logPanel::append)
    private val stateDetector = RepositoryStateDetector({ service.gitClient }, logger)

    // ── Explicit command wiring ─────────────────────────────────
    private val switchController = SwitchController(
        project, service, ::gitRoot, logger,
        onStateChanged = ::detectCurrentState,
    )
    private val presetManager = PresetListManager(
        project, service, ::gitRoot, logger, presetsInner,
        onSwitch = switchController::runSwitch,
        onDerive = switchController::derivePresetBranch,
        onStateChanged = ::detectCurrentState,
    )
    private var worktreeInfoLogged = false

    init {
        border = JBUI.Borders.empty(6, 8, 4, 8)
        minimumSize = Dimension(JBUI.scale(280), minimumSize.height)

        add(createTopBlock(), BorderLayout.NORTH)
        add(presetsScroll, BorderLayout.CENTER)
        add(logPanel, BorderLayout.SOUTH)

        presetManager.reload()
        detectCurrentState()
        refreshStrategySummary()
        wireEventSubscriptions()
    }

    // ── Top block: header + action row ────────────────

    private fun createTopBlock(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(createHeaderRow())
            add(Box.createVerticalStrut(4))
            add(createActionRow())
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
            add(jButton(Bundle.msg("action.from.current"), AllIcons.Vcs.Branch) {
                toolTipText = Bundle.msg("action.from.current.tip")
                addActionListener { presetManager.addPresetFromCurrent() }
            })
            add(Box.createHorizontalStrut(4))
            add(jButton(Bundle.msg("action.add.preset"), AllIcons.General.Add) {
                addActionListener { presetManager.addPreset() }
            })

            add(Box.createHorizontalGlue())
            add(strategyLabel)
        }
    }

    private fun createMoreActionsButton(): JButton {
        return jButton(icon = AllIcons.Actions.MoreHorizontal) {
            margin = JBUI.insets(0, 4, 0, 4)
            preferredSize = Dimension(JBUI.scale(32), JBUI.scale(24))
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = Bundle.msg("action.more.tip")
            addActionListener {
                createMoreMenu().show(this, 0, height)
            }
        }
    }

    private fun createMoreMenu(): JPopupMenu {
        return JPopupMenu().apply {
            border = JBUI.Borders.empty(4, 0)
            add(menuItem(Bundle.msg("action.reload")) {
                presetManager.reload()
                detectCurrentState()
            })
            add(menuItem(Bundle.msg("action.open.config")) { presetManager.openConfig() })
            addSeparator()
            add(menuItem(Bundle.msg("action.import")) { presetManager.importPresets() })
            add(menuItem(Bundle.msg("action.export")) { presetManager.exportPresets() })
            addSeparator()
            add(menuItem(Bundle.msg("action.undo")) {
                switchController.undoLastSwitch(presetManager.editors.map { it.currentPreset() })
            })
            add(menuItem(Bundle.msg("action.settings")) { openSettings() })
        }
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem = JMenuItem(text).apply {
        border = JBUI.Borders.empty(4, 14)
        addActionListener { action() }
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
    // ── Event subscriptions ────────────────────────────────────

    private fun wireEventSubscriptions() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(BranchSwitchListener.TOPIC, object : BranchSwitchListener {
            override fun onBranchSwitched() {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { detectCurrentState() }
            }

            override fun onLog(entry: LogEntry) {
                logPanel.append(entry)
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

    private fun gitRoot(): Path? {
        val root = project.gitRootPath() ?: return null
        val dotGit = root.resolve(".git")
        if (!java.nio.file.Files.isDirectory(dotGit) && !worktreeInfoLogged) {
            worktreeInfoLogged = true
            logger.debug("[info] detected git worktree — .git is a file, not a directory")
        }
        return root
    }

    /** Probes all editor paths in the background, then applies the latest snapshot. */
    private fun detectCurrentState() {
        val root = gitRoot() ?: return
        val currentEditors = presetManager.editors
        val repositoryPaths = LinkedHashSet<String>().apply { add(".") }
        currentEditors.forEach {
            repositoryPaths.addAll(it.currentPreset().submodules.keys)
        }
        val request = stateDetector.begin(root, repositoryPaths)
        val pinnedEditors = currentEditors.toList()
        service.scope.launch {
            val snapshot = stateDetector.detect(request)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!stateDetector.isLatest(snapshot)) return@invokeLater
                pinnedEditors.forEach { editor ->
                    if (editor in currentEditors) {
                        editor.applyCurrentState(snapshot.branches, snapshot.dirtyRepositories)
                    }
                }
                presetsInner.revalidate()
                presetsInner.repaint()
                logDetected(currentEditors.toList(), snapshot.branches, snapshot.dirtyRepositories)
            }
        }
    }

    private fun logDetected(
        editors: List<PresetEditor>,
        branches: Map<String, String?>,
        dirtyRepos: Map<String, Boolean>,
    ) {
        val main = branches["."] ?: "(detached)"
        val mainDirty = dirtyRepos["."] == true
        val matched = editors.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "${Bundle.msg("label.main.branch")} $main"
        if (mainDirty) {
            currentBranchLabel.text += " · ${Bundle.msg("status.tooltip.dirty")}"
            currentBranchLabel.foreground = JBColor(0xE07B00, 0xFFA726)
        } else {
            currentBranchLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        logger.debug("[detect] main=$main${if (mainDirty) " (dirty)" else ""}, matched=${matched ?: "<none>"}")
    }
}
