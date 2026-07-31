package com.submodule.branchswitcher.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.switch.shortLabel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Path
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Manages submodule rows within a [PresetEditor]: build, add, remove, and state sync.
 * Extracted from PresetEditor to keep it focused.
 */
internal class SubmoduleRowManager(
    private val gitRoot: Path,
    private val gitClient: () -> PresetDiscoveryGitClient,
    private val branchLoads: BranchLoadCoordinator,
    private val body: JPanel,
    private val log: AppLogger,
    private val onDirty: () -> Unit,
    private val onSwitchOnly: (path: String, target: String) -> Unit = { _, _ -> },
    private val scheduleUi: ((() -> Unit) -> Unit) = { action ->
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(action)
    },
) {
    /** One submodule row: path, branch combo, panel, and tracking state. */
    class SubRow(
        val path: String,
        val combo: JComboBox<String>,
        val panel: JPanel,
        var deleted: Boolean = false,
        var loaded: Boolean = false,
        val statusDot: JLabel,
    )

    val subRows = LinkedHashMap<String, SubRow>()
    private var loadedOnce = false

    /** Called by [PresetEditor] when first expand occurs. */
    fun onFirstExpand() { loadedOnce = true }

    /** Creates and registers a submodule row UI + data. */
    fun buildSubRow(path: String, initialBranch: String): SubRow {
        val combo = makeBranchCombo(onDirty)
        combo.selectedItem = initialBranch
        val dot = JLabel("●").apply {
            font = font.deriveFont(8f)
            foreground = JBColor(0x9E9E9E, 0x757575)
        }
        val rowPanel = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension =
                Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
        }.apply {
            border = JBUI.Borders.empty(2, 12, 2, 4)
            alignmentX = JPanel.LEFT_ALIGNMENT
            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(dot)
                add(Box.createHorizontalStrut(4))
                add(JLabel(shortLabel(path)).apply {
                    preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
                    toolTipText = path
                })
            }
            add(labelPanel, BorderLayout.WEST)
            add(combo, BorderLayout.CENTER)

        }
        installContextMenu(rowPanel, path)
        val row = SubRow(path, combo, rowPanel, statusDot = dot)
        subRows[path] = row
        return row
    }

    private fun installContextMenu(rowPanel: JPanel, path: String) {
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(rowPanel, e, path)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(rowPanel, e, path)
            }
        }
        addContextMenuListener(rowPanel, listener)
    }

    private fun addContextMenuListener(component: Component, listener: MouseAdapter) {
        component.addMouseListener(listener)
        if (component is Container) {
            component.components.forEach { addContextMenuListener(it, listener) }
        }
    }

    private fun removeRow(path: String) {
        val row = subRows[path] ?: return
        if (cancelComboBranchLoad(row.combo)) row.loaded = false
        row.deleted = true
        row.panel.isVisible = false
        onDirty()
        body.revalidate()
        body.repaint()
    }

    /** Shows a popup to add a new submodule from .gitmodules paths not yet in the preset. */
    fun showAddSubmoduleMenu(anchor: JButton, currentPreset: Preset) {
        val all = gitClient().listSubmodulePaths(gitRoot.toFile())
        val current = subRows.values.filter { !it.deleted }.map { it.path }.toSet()
        val available = all.filter { it !in current }
        if (available.isEmpty()) {
            log.debug(Bundle.msg("log.no.submodules.available", currentPreset.name))
            return
        }
        val popup = javax.swing.JPopupMenu()
        available.forEach { path ->
            popup.add(javax.swing.JMenuItem(shortLabel(path)).apply {
                toolTipText = path
                addActionListener { addSubmoduleFromMenu(path) }
            })
        }
        popup.show(anchor, 0, anchor.height)
    }

    /** Adds a submodule row for [path], reactivating deleted rows or creating new ones. */
    fun addSubmoduleFromMenu(path: String) {
        val existing = subRows[path]
        if (existing != null && existing.deleted) {
            existing.deleted = false
            existing.panel.isVisible = true
            if (loadedOnce && !existing.loaded) {
                existing.loaded = true
                loadComboBranches(existing.combo, gitRoot.resolve(path).toFile(),
                    existing.combo.selectedItem as? String ?: "")
            }
            onDirty()
            body.revalidate()
            body.repaint()
            return
        }
        val row = buildSubRow(path, "")
        val actionsIndex = body.componentCount - 1
        body.add(row.panel, actionsIndex)
        val dir = gitRoot.resolve(path).toFile()
        row.loaded = loadedOnce
        loadComboBranches(
            combo = row.combo,
            dir = dir,
            current = "",
            discoverCurrent = dir.exists(),
            loadChoices = loadedOnce,
        )
        body.revalidate()
        body.repaint()
    }

    /** Syncs subRows to match [preset]'s submodule map. Restores rows and adds new ones. */
    fun applyPresetToUI(preset: Preset) {
        val removedPaths = mutableListOf<String>()
        subRows.values.forEach { row ->
            if (preset.submodules.containsKey(row.path)) {
                row.deleted = false
                row.panel.isVisible = true
                row.combo.selectedItem = preset.submodules[row.path]
            } else {
                removedPaths += row.path
            }
        }
        removedPaths.forEach { path ->
            val row = subRows.remove(path) ?: return@forEach
            cancelComboBranchLoad(row.combo)
            body.remove(row.panel)
        }
        body.revalidate()
        body.repaint()
    }

    /** Removes deleted rows from [body] and [subRows]. */
    fun removeDeletedRows() {
        subRows.entries.removeAll { (_, row) ->
            if (row.deleted) {
                cancelComboBranchLoad(row.combo)
                body.remove(row.panel)
                true
            } else {
                false
            }
        }
    }

    /** Loads branch combos for all unloaded visible rows. Must be guarded by loadedOnce. */
    fun loadAllBranches(preset: Preset) {
        subRows.values.forEach { row ->
            if (row.deleted || row.loaded) return@forEach
            row.loaded = true
            val dir = gitRoot.resolve(row.path).toFile()
            val branch = preset.submodules[row.path] ?: ""
            loadComboBranches(row.combo, dir, branch)
        }
    }

    var loadingCount = 0
        internal set

    /** Asynchronously loads branch names into [combo]. */
    private fun loadComboBranches(
        combo: JComboBox<String>,
        dir: File,
        current: String,
        discoverCurrent: Boolean = false,
        loadChoices: Boolean = true,
    ) {
        loadComboBranches(combo, dir, current, branchLoads, log,
            onLoadStart = { loadingCount++ },
            onLoadEnd = {
                loadingCount--
                onDirty()
            },
            discoverCurrent = discoverCurrent,
            loadChoices = loadChoices,
            scheduleUi = scheduleUi,
        )
    }

    /** Cancels branch discovery for rows that are no longer visible. */
    fun cancelBranchLoads(): Boolean {
        var cancelledAny = false
        subRows.values.forEach { row ->
            if (cancelComboBranchLoad(row.combo)) {
                row.loaded = false
                cancelledAny = true
            }
        }
        return cancelledAny
    }

    private fun showContextMenu(rowPanel: JPanel, e: MouseEvent, path: String) {
        val popup = javax.swing.JPopupMenu()
        popup.add(javax.swing.JMenuItem(Bundle.msg("action.remove.submodule")).apply {
            addActionListener { removeRow(path) }
        })
        popup.addSeparator()
        popup.add("${Bundle.msg("menu.switch.only")} ($path)").addActionListener {
            requestSwitchOnly(path)
        }
        popup.add(Bundle.msg("menu.open.explorer")).addActionListener {
            val dir = gitRoot.resolve(path).toFile()
            if (dir.exists()) java.awt.Desktop.getDesktop().open(dir)
        }
        val point = SwingUtilities.convertPoint(e.component, e.point, rowPanel)
        popup.show(rowPanel, point.x, point.y)
    }

    internal fun requestSwitchOnly(path: String) {
        val row = subRows[path] ?: return
        val target = (row.combo.selectedItem as? String)?.trim().orEmpty()
        if (target.isEmpty()) {
            log.warn("[switch] $path: target branch is empty")
            return
        }
        onSwitchOnly(path, target)
    }
}
