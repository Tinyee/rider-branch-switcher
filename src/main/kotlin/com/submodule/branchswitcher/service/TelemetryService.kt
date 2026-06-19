package com.submodule.branchswitcher.service

import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.service.BranchSwitcherService.OptionsState
import java.util.UUID

/**
 * Anonymous usage statistics with explicit opt-in.
 * All data stays local; counters are exported to clipboard on demand.
 */
class TelemetryService(
    private val state: () -> OptionsState,
    private val project: Project,
) {
    private val options: OptionsState get() = state()

    data class Export(
        val pluginVersion: String,
        val riderVersion: String,
        val installId: String,
        val counters: Map<String, Int>,
    )

    val installId: String
        get() {
            if (!options.telemetryOptIn) return "<not opted in>"
            if (options.telemetryInstallId.isEmpty()) {
                options.telemetryInstallId = UUID.randomUUID().toString()
            }
            return options.telemetryInstallId
        }

    var promptShown: Boolean
        get() = options.telemetryPromptShown
        set(value) { options.telemetryPromptShown = value }

    var optIn: Boolean
        get() = options.telemetryOptIn
        set(value) { options.telemetryOptIn = value }

    fun incrementSwitch() { if (optIn) options.telemetrySwitchCount++ }
    fun incrementCreate() { if (optIn) options.telemetryCreateCount++ }
    fun incrementDerive() { if (optIn) options.telemetryDeriveCount++ }
    fun incrementError() { if (optIn) options.telemetryErrorCount++ }

    fun export(): String {
        val export = Export(
            pluginVersion = pluginVersion(),
            riderVersion = try {
                com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
            } catch (_: Exception) { "unknown" },
            installId = installId.take(8) + "…",
            counters = mapOf(
                "switch" to options.telemetrySwitchCount,
                "createPreset" to options.telemetryCreateCount,
                "derive" to options.telemetryDeriveCount,
                "error" to options.telemetryErrorCount,
            ),
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(export)
    }

    fun maybeShowOptIn() {
        if (promptShown) return
        promptShown = true
        val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            Bundle.msg("telemetry.dialog.body"),
            Bundle.msg("telemetry.dialog.title"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )
        optIn = result == com.intellij.openapi.ui.Messages.YES
    }

    private fun pluginVersion(): String {
        return try {
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.submodule.branchswitcher")
            )?.version
        } catch (_: Exception) { null }
            ?: PLUGIN_VERSION_FALLBACK
    }

    companion object {
        private const val PLUGIN_VERSION_FALLBACK = "0.7.0"
    }
}
