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
import com.submodule.branchswitcher.workflow.RepositoryStateRefreshCoordinator
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.settings.BranchSwitcherConfigurable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
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
    private val currentBranchLabel = ShrinkableLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }
    private val strategyLabel = ShrinkableLabel().apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor.GRAY
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = Bundle.msg("label.strategy.tip")
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { openSettings() }
        })
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = ViewportWidthPanel(fillViewportHeight = true).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(presetsInner)
        add(Box.createVerticalGlue())
    }
    private val presetsScroll = JBScrollPane(presetsContainer).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val logPanel = ToolWindowLogPanel()
    private val stateRefreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val reflogWatchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastReflogStamp: Long = -1L

    // ── Logger ──────────────────────────────────────────────────
    private val logger: AppLogger = ToolWindowLogger(logPanel::append)
    private val stateDetector = RepositoryStateDetector(
        logger,
        platformCancellationClassifier,
    )
    private val stateRefreshes = RepositoryStateRefreshCoordinator(
        scope = service.scope,
        openOperation = service.gitClient::openOperation,
        detector = stateDetector,
        log = logger,
        deliver = project::invokeLaterIfAlive,
        cancellationClassifier = platformCancellationClassifier,
    )

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
        startReflogWatch()
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
        return TrailingControlRowPanel(currentBranchLabel, createMoreActionsButton())
    }

    private fun createActionRow(): JPanel {
        val fromCurrentButton = jButton(Bundle.msg("action.from.current"), AllIcons.Vcs.Branch) {
            toolTipText = Bundle.msg("action.from.current.tip")
            addActionListener { presetManager.addPresetFromCurrent() }
        }
        val addPresetButton = jButton(Bundle.msg("action.add.preset"), AllIcons.General.Add) {
            addActionListener { presetManager.addPreset() }
        }
        val primaryActions = GlobalActionBar(fromCurrentButton, addPresetButton)
        return CompactHeightPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(primaryActions, BorderLayout.CENTER)
            add(strategyLabel, BorderLayout.SOUTH)
        }
    }

    private fun createMoreActionsButton(): JButton {
        return jButton(icon = AllIcons.Actions.MoreHorizontal) {
            margin = JBUI.insets(0, 4, 0, 4)
            preferredSize = Dimension(JBUI.scale(32), preferredSize.height)
            maximumSize = preferredSize
            minimumSize = preferredSize
            toolTipText = Bundle.msg("action.more.tip")
            addActionListener { showMoreActions(this) }
        }
    }

    private fun showMoreActions(anchor: JButton) {
        showActionPopup(
            anchor,
            listOf(
                listOf(
                    PopupAction(Bundle.msg("action.reload"), AllIcons.Actions.Refresh) {
                        presetManager.reload()
                        detectCurrentState()
                    },
                    PopupAction(Bundle.msg("action.open.config"), AllIcons.FileTypes.Config) {
                        presetManager.openConfig()
                    },
                ),
                listOf(
                    PopupAction(Bundle.msg("action.import"), AllIcons.Actions.MenuPaste) {
                        presetManager.importPresets()
                    },
                    PopupAction(Bundle.msg("action.export"), AllIcons.Actions.Download) {
                        presetManager.exportPresets()
                    },
                ),
                listOf(
                    PopupAction(Bundle.msg("action.previous.preset"), AllIcons.Actions.Back) {
                        switchController.switchToPreviousPreset(presetManager.editors.map { it.currentPreset() })
                    },
                    PopupAction(
                        Bundle.msg("action.settings"),
                        AllIcons.General.Settings,
                        perform = ::openSettings,
                    ),
                ),
            ),
        )
    }

    // ── Strategy summary ───────────────────────────────────────

    private fun refreshStrategySummary() {
        strategyLabel.text = strategySummary(
            service.dirtyAction,
            service.fetchFirst,
            service.pullAfterSwitch,
            service.timeoutSeconds,
        )
        strategyLabel.toolTipText = "${strategyLabel.text}. ${Bundle.msg("label.strategy.tip")}"
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
                project.invokeLaterIfAlive(::detectCurrentState)
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
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) == 0L) return@addHierarchyListener
            if (isShowing) {
                startReflogWatch()
                project.invokeLaterIfAlive {
                    detectCurrentState()
                    refreshStrategySummary()
                }
            } else {
                stateRefreshAlarm.cancelAllRequests()
                reflogWatchAlarm.cancelAllRequests()
            }
        }
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                project.invokeLaterIfAlive {
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

    /**
     * Detects external git operations (terminal checkout / pull / rebase) that move HEAD
     * without going through the plugin. The IntelliJ VFS does not watch `.git`, and the
     * Git4Idea repository listener is not on this project's compile classpath, so we poll
     * the main reflog's file stamp instead — a filesystem stat with no git process. Only a
     * stamp change schedules a refresh; plain file edits never trigger detection.
     */
    private fun startReflogWatch() {
        reflogWatchAlarm.cancelAllRequests()
        if (!shouldRunReflogWatch(isShowing, project.isDisposed)) return
        reflogWatchAlarm.addRequest({ checkExternalGitSwitch() }, REFLOG_WATCH_INTERVAL_MS)
    }

    private fun checkExternalGitSwitch() {
        if (!shouldRunReflogWatch(isShowing, project.isDisposed)) {
            reflogWatchAlarm.cancelAllRequests()
            return
        }
        val reflog = mainReflogPath() ?: return
        val stamp = runCatching { reflog.lastModified() }.getOrElse { -1L }
        if (lastReflogStamp >= 0 && stamp != lastReflogStamp) {
            scheduleStateRefresh()
        }
        lastReflogStamp = stamp
        startReflogWatch()
    }

    private fun mainReflogPath(): File? {
        val root = gitRoot() ?: return null
        val git = root.resolve(".git").toFile()
        val gitDir = if (git.isDirectory) {
            git
        } else if (git.isFile) {
            // Worktree: .git is a file pointing at the real git directory.
            runCatching { git.readLines().firstOrNull()?.removePrefix("gitdir: ")?.trim() }
                .getOrNull()?.let { root.resolve(it).toFile() }
        } else {
            null
        } ?: return null
        return File(gitDir, "logs/HEAD")
    }

    override fun dispose() {
        stateRefreshes.close()
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
        val pinnedEditors = currentEditors.toList()
        stateRefreshes.refresh(root, repositoryPaths) { snapshot ->
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

    private fun logDetected(
        editors: List<PresetEditor>,
        branches: Map<String, String?>,
        dirtyRepos: Map<String, Boolean>,
    ) {
        val main = branches["."] ?: Bundle.msg("status.detached")
        val mainDirty = dirtyRepos["."] == true
        val matched = editors.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "${Bundle.msg("label.main.branch")} $main"
        if (mainDirty) {
            currentBranchLabel.text += " · ${Bundle.msg("status.tooltip.dirty")}"
            currentBranchLabel.foreground = JBColor(0xE07B00, 0xFFA726)
        } else {
            currentBranchLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        currentBranchLabel.toolTipText = currentBranchLabel.text
        logger.debug("[detect] main=$main${if (mainDirty) " (dirty)" else ""}, matched=${matched ?: "<none>"}")
    }

    private companion object {
        private const val REFLOG_WATCH_INTERVAL_MS = 2000L
    }
}

internal fun shouldRunReflogWatch(isShowing: Boolean, projectDisposed: Boolean): Boolean =
    isShowing && !projectDisposed
