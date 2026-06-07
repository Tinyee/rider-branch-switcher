package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
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
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Main tool window panel: preset list, switch controls, options, and log.
 *
 * Layout (BorderLayout):
 * - NORTH: title + current branch label + progress bar + scrollable preset list
 * - CENTER: colored log (JTextPane with [append])
 * - SOUTH: switch options (dirty/fetch/pull/timeout) + action buttons
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
        border = JBUI.Borders.empty(0, 0, 2, 0)
    }
    private val presetsInner = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
    }
    private val presetsContainer = JPanel(BorderLayout()).apply {
        add(presetsInner, BorderLayout.NORTH)
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

    // ── Delegates (after UI fields to resolve init order) ──────
    private val presetManager = PresetListManager(
        project, service, ::gitRoot, ::append,
        onSwitch = { preset -> switchController.runSwitch(preset) },
        onDerive = { root, preset, name -> switchController.derivePresetBranch(root, preset, name) },
    )
    @Suppress("unused")
    private lateinit var switchController: SwitchController // lateinit required: presetManager lambda captures this before init{}
    private var worktreeInfoLogged = false

    init {
        switchController = SwitchController(
            project, service, ::gitRoot, ::append,
            editors = { presetManager.editors },
            onStateChanged = ::detectCurrentState,
            progressBar = progressBar,
        )
        presetManager.onStateChanged = ::detectCurrentState
        border = JBUI.Borders.empty(8, 8, 8, 8)
        minimumSize = Dimension(JBUI.scale(280), minimumSize.height)

        add(createNorthBlock(), BorderLayout.NORTH)
        add(JBScrollPane(log).apply { preferredSize = Dimension(0, JBUI.scale(30)) }, BorderLayout.CENTER)
        add(createSouthBlock(), BorderLayout.SOUTH)

        presetManager.reload(presetsInner)
        detectCurrentState()
        wireOptionsPersistence()
        wireEventSubscriptions()
    }

    // ── UI factory methods ─────────────────────────────────────

    private fun createNorthBlock(): JPanel {
        val title = JLabel(Bundle.msg("plugin.title")).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        val presetsScroll = JBScrollPane(presetsContainer).apply {
            preferredSize = Dimension(0, JBUI.scale(300))
        }
        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton(Bundle.msg("action.add.preset"), AllIcons.General.Add).noFocusRing()
                .also { it.addActionListener { presetManager.addPreset(presetsInner) } })
            add(JButton(Bundle.msg("action.from.current"), AllIcons.Vcs.Branch).noFocusRing().also {
                it.toolTipText = Bundle.msg("action.from.current.tip")
                it.addActionListener { presetManager.addPresetFromCurrent(presetsInner) }
            })
        }
        val presetsBlock = JPanel(BorderLayout()).apply {
            add(presetsScroll, BorderLayout.CENTER)
            add(addPanel, BorderLayout.SOUTH)
        }
        val statusRow = JPanel(BorderLayout()).apply {
            add(currentBranchLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(statusRow, BorderLayout.SOUTH)
            add(presetsBlock, BorderLayout.CENTER)
        }
    }

    private fun createSouthBlock(): JPanel {
        val optsRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel(Bundle.msg("label.dirty.working.tree")))
            add(dirtyCombo)
            add(JLabel(Bundle.msg("option.timeout")))
            add(timeoutCombo)
        }
        val optsRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(fetchCheck)
            add(pullCheck)
            add(JCheckBox(Bundle.msg("option.confirm.init")).apply {
                isSelected = service.confirmBeforeInit
                addItemListener { service.confirmBeforeInit = isSelected }
            })
        }
        val opts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(4, 0, 4, 0),
            )
            optsRow1.alignmentX = LEFT_ALIGNMENT
            optsRow2.alignmentX = LEFT_ALIGNMENT
            add(optsRow1)
            add(optsRow2)
        }
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
        val btnRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { alignmentX = LEFT_ALIGNMENT }
        btnRow1.add(JButton(Bundle.msg("action.reload"), AllIcons.Actions.Refresh).noFocusRing()
            .also { it.addActionListener { presetManager.reload(presetsInner); detectCurrentState() } })
        btnRow1.add(JButton(Bundle.msg("action.open.config"), AllIcons.Actions.EditSource).noFocusRing()
            .also { it.addActionListener { presetManager.openConfig() } })
        btnRow1.add(Box.createHorizontalStrut(12))
        btnRow1.add(JButton(Bundle.msg("action.clear.log"), AllIcons.Actions.GC).noFocusRing()
            .also { it.addActionListener { log.text = "" } })
        buttons.add(btnRow1)
        val btnRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { alignmentX = LEFT_ALIGNMENT }
        btnRow2.add(JButton(Bundle.msg("action.undo"), AllIcons.Actions.Rollback).noFocusRing().also {
            it.toolTipText = Bundle.msg("action.undo.tip")
            it.addActionListener { switchController.undoLastSwitch() }
        })
        btnRow2.add(Box.createHorizontalStrut(12))
        btnRow2.add(JButton(Bundle.msg("action.export"), AllIcons.Actions.MenuSaveall).noFocusRing()
            .also { it.addActionListener { presetManager.exportPresets() } })
        btnRow2.add(JButton(Bundle.msg("action.import"), AllIcons.Actions.MenuSaveall).noFocusRing()
            .also { it.addActionListener { presetManager.importPresets(presetsContainer) } })
        buttons.add(btnRow2)
        return JPanel(BorderLayout()).apply {
            add(opts, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
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
