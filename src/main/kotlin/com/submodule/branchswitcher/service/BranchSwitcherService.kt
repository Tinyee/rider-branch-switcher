package com.submodule.branchswitcher.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.PresetLoader
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import kotlinx.coroutines.CoroutineScope
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Central project-level service for the Branch Switcher plugin.
 *
 * Responsibilities:
 * - Persist switch options to [branch-switcher.xml] via [PersistentStateComponent]
 * - Manage preset loading/saving via [PresetLoader]
 * - Provide a [CoroutineScope] for async operations (background branch detection, combo loading)
 * - Track switch history for undo support (max 5 entries)
 * - Cache current branch state ([currentBranches]) with stale-detection via [detectGen]
 *
 * The [detectGen] counter is incremented on each [detectCurrentState] call.
 * Async branch probes capture the current generation; when results arrive,
 * they are discarded if a newer detection has started, preventing stale UI updates.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "BranchSwitcherOptions",
    storages = [Storage("branch-switcher.xml")]
)
class BranchSwitcherService(
    private val project: Project,
    cs: CoroutineScope,
) : PersistentStateComponent<BranchSwitcherService.OptionsState>, Disposable {

    /** Platform-injected [CoroutineScope] with [SupervisorJob] semantics. */
    val scope = cs

    /** Prevents overlapping write operations (switch, derive, rollback). */
    private val writeGate = AtomicBoolean(false)

    /** Returns true if a write operation can start (locks the gate). */
    fun tryStartWrite(): Boolean = writeGate.compareAndSet(false, true)

    /** Releases the write gate. Must be called in finally. */
    fun endWrite() { writeGate.set(false) }

    override fun dispose() {
        // Platform manages injected [cs] lifecycle; nothing else to clean up.
    }

    data class OptionsState(
        var dirtyAction: String = "Stash",
        var fetchFirst: Boolean = true,
        var pullAfterSwitch: Boolean = true,
        var timeoutSeconds: Int = 60,
        var confirmBeforeInit: Boolean = false,
        var history: MutableList<SwitchHistoryEntry> = mutableListOf(),
        // ── Anonymous telemetry ──────────────────────────────────
        var telemetryInstallId: String = "",
        var telemetryPromptShown: Boolean = false,
        var telemetryOptIn: Boolean = false,
        var telemetrySwitchCount: Int = 0,
        var telemetryCreateCount: Int = 0,
        var telemetryDeriveCount: Int = 0,
        var telemetryQuickSwitchCount: Int = 0,
        var telemetryErrorCount: Int = 0,
    )

    private var options = OptionsState()

    override fun getState(): OptionsState = options
    override fun loadState(state: OptionsState) { options = state }

    var dirtyAction: DirtyAction
        get() = when (options.dirtyAction) {
            "Skip" -> DirtyAction.Skip
            "Force" -> DirtyAction.Force
            else -> DirtyAction.Stash
        }
        set(value) { options.dirtyAction = value.name }

    var fetchFirst: Boolean
        get() = options.fetchFirst
        set(value) { options.fetchFirst = value }

    var pullAfterSwitch: Boolean
        get() = options.pullAfterSwitch
        set(value) { options.pullAfterSwitch = value }

    var timeoutSeconds: Int
        get() = options.timeoutSeconds
        set(value) { options.timeoutSeconds = value }

    var confirmBeforeInit: Boolean
        get() = options.confirmBeforeInit
        set(value) { options.confirmBeforeInit = value }

    // ── Anonymous telemetry ──────────────────────────────────────────

    /** Whether the user has seen the opt-in dialog (avoids re-prompting). */
    var telemetryPromptShown: Boolean
        get() = options.telemetryPromptShown
        set(value) { options.telemetryPromptShown = value }

    /** Stable anonymous ID, generated only after opt-in consent. */
    val telemetryInstallId: String
        get() {
            if (!options.telemetryOptIn) return "<not opted in>"
            if (options.telemetryInstallId.isEmpty()) {
                options.telemetryInstallId = UUID.randomUUID().toString()
            }
            return options.telemetryInstallId
        }

    var telemetryOptIn: Boolean
        get() = options.telemetryOptIn
        set(value) { options.telemetryOptIn = value }

    fun incrementSwitchCount() { if (options.telemetryOptIn) options.telemetrySwitchCount++ }
    fun incrementCreateCount() { if (options.telemetryOptIn) options.telemetryCreateCount++ }
    fun incrementDeriveCount() { if (options.telemetryOptIn) options.telemetryDeriveCount++ }
    fun incrementQuickSwitchCount() { if (options.telemetryOptIn) options.telemetryQuickSwitchCount++ }
    fun incrementErrorCount() { if (options.telemetryOptIn) options.telemetryErrorCount++ }

    /** Export anonymized telemetry as a JSON string for clipboard sharing. */
    fun exportTelemetry(): String {
        val pluginVersion = "0.7.0"
        val riderVersion = try {
            com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
        } catch (_: Exception) { "unknown" }
        return buildString {
            appendLine("{")
            appendLine("  \"pluginVersion\": \"$pluginVersion\",")
            appendLine("  \"riderVersion\": \"$riderVersion\",")
            appendLine("  \"installId\": \"${options.telemetryInstallId.take(8)}…\",")
            appendLine("  \"counters\": {")
            appendLine("    \"switch\": ${options.telemetrySwitchCount},")
            appendLine("    \"createPreset\": ${options.telemetryCreateCount},")
            appendLine("    \"derive\": ${options.telemetryDeriveCount},")
            appendLine("    \"quickSwitch\": ${options.telemetryQuickSwitchCount},")
            appendLine("    \"error\": ${options.telemetryErrorCount}")
            appendLine("  }")
            appendLine("}")
        }
    }

    /** Show first-install opt-in dialog (once per install). */
    fun maybeShowTelemetryOptIn() {
        if (options.telemetryPromptShown) return // already asked
        options.telemetryPromptShown = true
        val result = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            com.submodule.branchswitcher.Bundle.msg("telemetry.dialog.body"),
            com.submodule.branchswitcher.Bundle.msg("telemetry.dialog.title"),
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )
        options.telemetryOptIn = result == com.intellij.openapi.ui.Messages.YES
    }

    /** Cached [GitOps] instance, recreated only when timeout changes. */
    private var _gitClient: GitClient? = null
    private var _gitClientTimeout: Int = -1

    val gitClient: GitClient get() {
        if (_gitClient == null || _gitClientTimeout != options.timeoutSeconds) {
            _gitClient = GitOps(options.timeoutSeconds)
            _gitClientTimeout = options.timeoutSeconds
        }
        return _gitClient!!
    }

    private var presetFile: PresetFile = PresetFile()
    private var savedFilePath: Path? = null
    val presets: List<Preset> get() = presetFile.presets

    fun loadPresets(): Result<Pair<Path, PresetFile>> {
        val base = project.basePath?.let { java.nio.file.Paths.get(it) }
            ?: return Result.failure(IllegalStateException("project base path is null"))
        return PresetLoader.load(base).onSuccess { (file, parsed) ->
            savedFilePath = file
            presetFile = parsed
        }
    }

    fun savePresets(presets: List<Preset>) {
        val file = savedFilePath ?: run {
            val base = project.basePath?.let { java.nio.file.Paths.get(it) }
                ?: throw IllegalStateException("project base path is null — cannot save presets")
            val resolved = PresetLoader.ensureFile(base)
            savedFilePath = resolved
            resolved
        }
        presetFile = presetFile.copy(presets = presets)
        PresetLoader.save(file, presetFile)
    }

    // -- Switch history for undo support (max 5 entries, persisted across restarts) --

    private val maxHistory = 5
    /** Records a completed switch: preset name, stable id (for rename survival), and timestamp. */
    data class SwitchHistoryEntry(
        val presetName: String = "",
        val presetId: String? = null,
        val timestamp: Long = 0,
    )

    @Synchronized
    fun addHistory(name: String, id: String? = null) {
        val list = options.history
        list.add(0, SwitchHistoryEntry(name, id, System.currentTimeMillis()))
        if (list.size > maxHistory) {
            options.history = list.take(maxHistory).toMutableList()
        }
    }

    fun getHistory(): List<SwitchHistoryEntry> = options.history.toList()
    fun getLastHistory(): SwitchHistoryEntry? = options.history.firstOrNull()

    // -- Stale-detection for async branch probes --

    /**
     * Monotonically increasing generation counter (atomic for thread safety).
     * Each [detectCurrentState] call increments it via [nextDetectGen].
     * Async probe callbacks check [getDetectGen] — if a newer probe started,
     * the old result is discarded to avoid updating the UI with stale data.
     */
    private val detectGen = AtomicLong(0)

    fun nextDetectGen(): Long = detectGen.incrementAndGet()
    fun getDetectGen(): Long = detectGen.get()
    fun resolveSwitchRequest(preset: Preset): ResolvedSwitchRequest =
        ResolvedSwitchRequest.resolve(
            preset,
            SwitchOptions(
                dirty = dirtyAction,
                pull = pullAfterSwitch,
                fetchFirst = fetchFirst,
                confirmBeforeInit = confirmBeforeInit,
            ),
        )

    companion object {
        fun getInstance(project: Project): BranchSwitcherService =
            project.service()
    }
}
