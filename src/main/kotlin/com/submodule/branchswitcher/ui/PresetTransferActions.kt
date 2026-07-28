package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.file.Path

/** Clipboard import and export commands for a preset collection. */
internal class PresetTransferActions(
    private val project: Project,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val host: PresetCollectionHost,
    private val persist: (List<Preset>) -> Boolean,
) {
    @Suppress("TooGenericExceptionCaught") // Gson and the system clipboard expose unrelated failure types
    fun exportPresets() {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val json = gson.toJson(PresetFile(host.editors.map { it.currentPreset() }))
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(json), null)
            log.debug("[exported] ${host.editors.size} preset(s) 已复制到剪贴板")
            Notifier.info(
                project,
                Bundle.msg("notify.export.complete"),
                Bundle.msg("notify.exported", host.editors.size),
            )
        } catch (e: Exception) {
            log.error("[export] failed: ${e.message}")
            Notifier.error(
                project,
                Bundle.msg("notify.export.complete"),
                "${Bundle.msg("dialog.import.failed")}: ${e.message}",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // clipboard, parsing, persistence, and UI adapters share this boundary
    fun importPresets() {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = clipboardTextOrNull(clipboard)
            if (text.isNullOrBlank()) {
                Messages.showInfoMessage(project, Bundle.msg("dialog.import.empty"), Bundle.msg("dialog.import"))
                return
            }
            val result = parsePresetImport(text, host.editors.map { it.currentPreset().name }.toSet())
            if (result.presets.isEmpty()) {
                val message = if (result.hasRecognizedEntries) {
                    Bundle.msg("dialog.import.none", result.conflictingNames.size, result.invalidNames.size)
                } else {
                    Bundle.msg("dialog.import.invalid")
                }
                Messages.showWarningDialog(project, message, Bundle.msg("dialog.import"))
                return
            }
            if (result.invalidNames.isNotEmpty()) {
                log.debug(
                    "[import] skipped ${result.invalidNames.size} invalid: " +
                        result.invalidNames.joinToString(", "),
                )
            }
            if (result.conflictingNames.isNotEmpty()) {
                log.debug(
                    "[import] skipped ${result.conflictingNames.size} conflicts: " +
                        result.conflictingNames.joinToString(", "),
                )
            }
            val root = gitRoot() ?: return
            val combined = host.editors.map { it.currentPreset() } + result.presets
            if (!persist(combined)) return
            result.presets.forEach { host.addEditor(root, it) }
            host.refreshParent()
            log.debug("[imported] ${result.presets.size} preset(s) from clipboard")
            host.notifyStateChanged()
            Notifier.info(
                project,
                Bundle.msg("notify.import.complete"),
                Bundle.msg("notify.imported", result.presets.size),
            )
        } catch (e: Exception) {
            log.error("[import] error: ${e.message}")
            Messages.showWarningDialog(
                project,
                "${Bundle.msg("dialog.import.failed")}: ${e.message}",
                Bundle.msg("dialog.import"),
            )
        }
    }
}

internal fun clipboardTextOrNull(clipboard: Clipboard): String? {
    if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
    return try {
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: UnsupportedFlavorException) {
        // Clipboard contents can change between the availability check and the read.
        null
    }
}
