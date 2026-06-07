package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.service.BranchSwitcherService
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
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Main tool window panel with toolbar + card layout.
 *
 * Layout (BorderLayout):
 * - NORTH: toolbar (action buttons + options) + current state bar
 * - CENTER: scrollable preset cards
 * - SOUTH: collapsible log panel (toggle to show/hide)
 *
 * Thread safety: uses [service.scope] for background git probes;
 * [detectCurrentState] uses generation-based stale detection.
 */
class BranchSwitcherPanel(
    private val project: Project,
    private val service: BranchSwitcherService,
) : JPanel(BorderLayout()) {

    // ── UI state ───────────────────────────────────────────────
    private val currentBranchLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = JPanel(BorderLayout()).apply {
        add(presetsInner, BorderLayout.NORTH)
    }
    private val presetsScroll = JBScrollPane(presetsContainer).apply {
        border = BorderFactory.createEmptyBorder()
    }

    private val dirtyCombo = JComboBox(arrayOf(Bundle.msg("option.dirty.stash"), Bundle.msg("option.dirty.skip"), Bundle.msg("option.dirty.force")))
    private val timeoutCombo = JComboBox(arrayOf("30s", "60s", "120s", "300s"))
    private val pullCheck = JCheckBox(Bundle.msg("option.pull.after"), service.pullAfterSwitch)
    private val fetchCheck = JCheckBox(Bundle.msg("option.fetch.before"), service.fetchFirst)

    private val progressBar = JProgressBar().apply {
        isStringPainted = true
        isVisible = false
    }

    private val log = javax.swing.JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        contentType = "text/plain"
    }
    private var logVisible = false
    private lateinit var logToggle: JLabel
    private lateinit var logScroll: JBScrollPane

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
        wireOptionsPersistence()
        wireEventSubscriptions()
    }

    // ── Top block: toolbar + options + status ─────────────────

    private fun createTopBlock(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(createToolbar())
            add(Box.createVerticalStrut(2))
            add(createStatusBar())
        }
    }

    private fun createToolbar(): JPanel {
        return JPanel(BorderLayout()).apply {
            // Left: action buttons
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            left.add(iconButton(Bundle.msg("action.reload"), AllIcons.Actions.Refresh,
                Bundle.msg("action.reload")) { presetManager.reload(presetsInner); detectCurrentState() })
            left.add(iconButton(Bundle.msg("action.open.config"), AllIcons.Actions.EditSource,
                Bundle.msg("action.open.config")) { presetManager.openConfig() })
            left.add(Box.createHorizontalStrut(6))
            left.add(iconButton(Bundle.msg("action.export"), AllIcons.Actions.MenuSaveall,
                Bundle.msg("action.export")) { presetManager.exportPresets() })
            left.add(iconButton(Bundle.msg("action.import"), AllIcons.Actions.MenuSaveall,
                Bundle.msg("action.import")) { presetManager.importPresets(presetsContainer) })
            left.add(Box.createHorizontalStrut(6))
            left.add(iconButton(Bundle.msg("action.undo"), AllIcons.Actions.Rollback,
                Bundle.msg("action.undo.tip")) { switchController.undoLastSwitch() })
            add(left, BorderLayout.WEST)

            // Right: new preset buttons
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
            right.add(JButton(Bundle.msg("action.add.preset"), AllIcons.General.Add).noFocusRing()
                .also { it.addActionListener { presetManager.addPreset(presetsInner) } })
            right.add(JButton(Bundle.msg("action.from.current"), AllIcons.Vcs.Branch).noFocusRing().also {
                it.toolTipText = Bundle.msg("action.from.current.tip")
                it.addActionListener { presetManager.addPresetFromCurrent(presetsInner) }
            })
            add(right, BorderLayout.EAST)

            // Center: options row
            val opts = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0))
            opts.add(JLabel(Bundle.msg("label.dirty.working.tree")))
            opts.add(dirtyCombo)
            opts.add(JLabel(Bundle.msg("option.timeout")))
            opts.add(timeoutCombo)
            opts.add(fetchCheck)
            opts.add(pullCheck)
            opts.add(JCheckBox(Bundle.msg("option.confirm.init")).apply {
                isSelected = service.confirmBeforeInit
                addItemListener { service.confirmBeforeInit = isSelected }
            })
            add(opts, BorderLayout.CENTER)
        }
    }

    private fun createStatusBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(2, 2, 4, 2),
            )
            add(currentBranchLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.EAST)
        }
    }

    // ── Log panel (collapsible) ────────────────────────────────

    private fun createLogPanel(): JPanel {
        logScroll = JBScrollPane(log).apply {
            preferredSize = Dimension(0, JBUI.scale(80))
            isVisible = false
        }
        logToggle = JLabel("${AllIcons.General.ArrowRight} Log").apply {
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
        logToggle.text = if (logVisible) " Log" else " Log"
        revalidate()
        repaint()
    }

    // ── Helper ─────────────────────────────────────────────────

    private fun iconButton(tip: String, icon: javax.swing.Icon, altText: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tip
            addActionListener { action() }
        }.noFocusRing()
    }


    // ── Option persistence ─────────────────────────────────────

    private fun wireOptionsPersistence() {
        dirtyCombo.selectedIndex = when (service.dirtyAction) {
            DirtyAction.Stash -> 0; DirtyAction.Skip -> 1; DirtyAction.Force -> 2
        }
        dirtyCombo.addItemListener {
            service.dirtyAction = when (dirtyCombo.selectedIndex) {
                1 -> DirtyAction.Skip; 2 -> DirtyAction.Force; else -> DirtyAction.Stash
            }
        }
        pullCheck.addItemListener { service.pullAfterSwitch = pullCheck.isSelected }
        fetchCheck.addItemListener { service.fetchFirst = fetchCheck.isSelected }
        timeoutCombo.selectedIndex = when (service.timeoutSeconds) {
            30 -> 0; 120 -> 2; 300 -> 3; else -> { service.timeoutSeconds = 60; 1 }
        }
        timeoutCombo.addItemListener {
            service.timeoutSeconds = when (timeoutCombo.selectedIndex) {
                0 -> 30; 2 -> 120; 3 -> 300; else -> 60
            }
        }
    }

    // ── Event subscriptions ────────────────────────────────────

    private fun wireEventSubscriptions() {
        val connection = project.messageBus.connect(service)
        connection.subscribe(BranchSwitchListener.TOPIC, object : BranchSwitchListener {
            override fun onBranchSwitched() {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { detectCurrentState() }
            }
        })
        addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater { detectCurrentState() }
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
            for (p in snapshot) {
                val dir = if (p == ".") root.toFile() else root.resolve(p).toFile()
                branches[p] = if (dir.exists()) service.gitClient.currentBranch(dir) else null
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (gen != service.getDetectGen()) return@invokeLater
                pinnedEditors.forEach { editor ->
                    if (editor in eds) editor.applyCurrentState(branches)
                }
                presetsInner.revalidate()
                presetsInner.repaint()
                logDetected(eds.toList(), branches)
            }
        }
    }

    private fun logDetected(eds: List<PresetEditor>, branches: Map<String, String?>) {
        val main = branches["."] ?: "(detached)"
        val matched = eds.firstOrNull { it.matchesState(branches) }?.currentPreset()?.name
        currentBranchLabel.text = "${Bundle.msg("label.main.branch")} $main"
        append("[detect] main=$main, matched=${matched ?: "<none>"}")
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
