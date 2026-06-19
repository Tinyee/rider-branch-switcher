# Architecture Review — 2026-06-20

## Current State

core/: Pure JVM — GitClient, model, switch pipeline, rules, import/export
root/: IntelliJ adapters, UI, notifications, persistence

The split is correct. Remaining issues are in the platform orchestration layer.

## P2 Items (implement now)

### 1. CancellationClassifier → core

**Problem**: core has PCE class-name checks (`e.javaClass.simpleName == "ProcessCanceledException"`)
in SwitchPreflight and DeriveBranchExecutor. Platform concept leaks into core.

**Fix**: Define `CancellationClassifier` interface in core. Platform injects it.

```kotlin
// core
interface CancellationClassifier {
    fun isCancellation(e: Throwable): Boolean
    companion object {
        val DEFAULT: CancellationClassifier = object : CancellationClassifier {
            override fun isCancellation(e: Throwable) =
                e is java.util.concurrent.CancellationException
        }
    }
}
```

Platform wires: `CancellationClassifier { e -> e is ProcessCanceledException || e is CancellationException }`

### 2. SwitchFlowCoordinator → platform

**Problem**: SwitchController and SwitchPresetAction each have their own:
- preflight + progress
- Force warning check
- missing branch/dir confirm
- notification
- VCS refresh
- telemetry/history

**Fix**: Extract shared `SwitchFlowCoordinator` that takes callbacks for UI differences.

```kotlin
// platform
class SwitchFlowCoordinator(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
)

suspend fun executeSwitch(preset: Preset, log: AppLogger, confirmation: SwitchConfirmation): SwitchResult
```

### 3. BranchSwitcherService split

**Problem**: Service holds telemetry, preset cache, write gate, history, settings.

**Fix**: Extract `TelemetryStore` and `PresetRepository`.

```kotlin
class TelemetryStore { ... }
class PresetRepository(private val project: Project) { ... }
```

## P3 Items (defer)

### 4. SwitchContext → explicit pipeline state

Replace mutable maps with immutable data class per step.

### 5. GitClient → split by concern

GitQueryClient / GitWriteClient / GitSubmoduleClient / GitOperationLifecycle.

## Implementation Order

1. ✅ CancellationClassifier (smallest, fixes P2 core leak) — done 03c0082
2. ✅ SwitchFlowCoordinator (unifies two switch entry points) — done a394c58
3. TelemetryStore + PresetRepository extract — deferred, lower priority
