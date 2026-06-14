# IntelliJ Plugin Development (Rider)

Invoke with `/intellij-plugin-dev` when writing or reviewing IntelliJ/Rider plugin code.

**Read Part 1 + Part 2 first** — these are behavioral templates that prevent the most common bugs. Part 3+ are reference material.

---

# Part 1: Constraints — do these BEFORE writing code

## State machine matrix (for any multi-phase write operation)

Fill every cell before implementing. Empty cell = design not ready:

```
Phase / Success / Blocked / Probe-error / User-cancel / Cleanup-failure
```

Then define the three contracts:

```text
Behavior contract:  Given <state>, when <action>, then <observable final state>.
Failure contract:   If <probe/command> fails, block/rollback because <reason>.
Cancel contract:    Cancel at <boundary>. Recover by <mechanism> in <fresh operation>.
```

## Scope baseline (before editing)

State before touching code:
- What files are in scope and why
- What call chain is affected (entry point → logic → side effects → cleanup)
- What is explicitly OUT of scope
- `git status --short` and `git diff --stat` output

## Self-review (after tests pass)

Before claiming "done," confirm:
- [ ] `./gradlew quickCheck` passes
- [ ] Every `TaskBridge.runBackground` has `beginOperation`/`onCancel`/`onFinished`/`endOperation`
- [ ] Every `tryStartWrite()` has `endWrite()` in finally
- [ ] New i18n keys exist in both locale files
- [ ] New interface methods have stubs in all implementations (grep for the interface name)
- [ ] Return paths all pass the same number of positional arguments

## Resource-aware test workflow

Treat test execution as a resource budget, not an all-or-nothing switch:

```bash
# Lightweight iteration
./gradlew quickCheck
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel

# Broader local verification
./gradlew test detekt --max-workers=2 --no-parallel

# Final verification when the change scope warrants the full suite
./gradlew test detekt --rerun-tasks --max-workers=2 --no-parallel

# Release/push only; warn the user before starting because it is intentionally heavy
./gradlew releaseCheck
```

- Never launch heavy Gradle commands in parallel, even when the agent tool supports parallel calls.
- Use `--max-workers=1 --no-parallel` when the user reports heat, fan noise, or constrained resources.
- Do not reduce global property-test iterations or skip coverage merely to cool local runs.
- Run `./gradlew --stop` only when releasing resources matters or the session is ending; stopping after every command makes later runs slower.

---

# Part 2: Code Templates — fill these in

## Operation lifecycle (every async write path)

```
Gate check → begin operation → cancellable task → end operation → gate release in finally
```

```kotlin
if (!service.tryStartWrite()) { warn("busy"); return }
scope.launch(Dispatchers.Default) {
    try {
        gitClient.beginOperation()
        try {
            TaskBridge.runBackground(project, "Title", true,
                block = { indicator -> /* work */ },
                onCancel = { gitClient.cancel() },
                onFinished = { gitClient.endOperation() },
            )
        } catch (_: CancellationException) { /* user cancelled */ }
    } finally { service.endWrite() }
}
```

Missing any link → cancel silently broken for that path.

## Interface method addition

When adding to any abstracted interface with multiple implementations:

```bash
# Find every implementation before adding the method
grep -rl "INTERFACE_NAME" src/ --include="*.kt"

# After adding, verify count is consistent
grep -rnc "fun NEW_METHOD" src/ --include="*.kt"
```

Touch: interface declaration, real implementation, all test fakes (counting fakes, delegation fakes, dynamic proxies).

## Structured result type (for 3+ outcome categories)

```kotlin
data class Result(
    val modified: List<ID>,           // what was changed
    val blocked: List<ID>,            // blocked before modification
    val failed: Map<ID, ErrorDetail>, // failed after partial modification
    val recoveryData: RecoveryState,  // for rollback
    val cancelled: Boolean = false,
) {
    val allClean get() = !cancelled && modified.isNotEmpty() && failed.isEmpty()
    val nothingTouched get() = !cancelled && modified.isEmpty() && failed.isEmpty()
}
```

Rules: new outcome → new `List<ID>` field; `allClean` must include `!cancelled`; every `return` in the function must pass the same argument count (verify with `grep "return Result(" | awk -F, '{print NF}' | sort -u`).

## Decision logic separated from presentation (for 3+ notification paths)

Layer 1 (pure data): sealed class with outcome types → Layer 2 (pure function): (inputs) → sealed class → Layer 3 (UI mapping): sealed class → `Bundle.msg()` calls.

Layer 1+2 never import `Bundle`, `Notifier`, `Project`, or any IntelliJ package.

## Pipeline consistency (verify with grep, not memory)

```bash
FILE=src/main/kotlin/.../Executor.kt

# Cancel checks in every phase
grep -n "cancelled" "$FILE"

# Return path argument count consistency
grep "return Result(" "$FILE" | awk -F, '{print NF}' | sort -u
# Must output exactly one number

# Cancel-before-gate order: cancel check line number must be < gate line number
grep -n "cancelled\|blocked\|isNotEmpty" "$FILE"
```

## Test quality checklist

```
[ ] Does it call the production function, or just construct data?
[ ] Would it still pass if the production code were broken?
[ ] Does it assert ALL side effects (not just the primary outcome)?
[ ] Is it testing behavior, or a language feature (data class, copy(), Boolean)?
```

---

# Part 3: Gotchas & Anti-patterns

## Kotlin + IntelliJ

| Wrong | Right | Why |
|-------|-------|-----|
| `JLabel("${icon} text")` | `JLabel(" text", icon, LEFT)` | Icon.toString() = "CachedImageIcon@..." |
| `SwingUtilities.invokeLater` | `Application.invokeLater` | No modality integration, no write-intent in 2025.1+ |
| `JButton("Save")` | `jButton("Save", icon)` factory | Leaves focus ring on click |
| `project.coroutineScope` | Inject `CoroutineScope` via constructor | Deprecated, classloader leaks |
| `proc.waitFor()` | `proc.waitFor(10, SECONDS)` + `destroyForcibly()` | Infinite hang on stuck filesystem |
| Gson → Kotlin data class | Gson → DTO → `.toDomain()` | Gson bypasses Kotlin default params |
| `Dimension(140, 28)` | `Dimension(JBUI.scale(140), JBUI.scale(28))` | Broken on HiDPI |
| `Color(0xE07B00)` | `JBColor(0xE07B00, 0xFFA726)` | Broken on dark theme |
| `catch (_: Exception) {}` | `catch (e: Exception) { LOG.warn(...) }` | Silently hides failures |

## IntelliJ API

| Wrong | Right | Why |
|-------|-------|-----|
| `@Service(ServiceLevel.PROJECT)` | `@Service(Service.Level.PROJECT)` | API renamed in 2024.2+ |
| ToolWindow `id` from `Bundle.msg()` | String literal | Language switch breaks `getToolWindow()` |
| `<vendor>internal</vendor>` | Real name + url + email | Marketplace rejection |
| `ReadAction.compute{}` (2026.1+) | `readAction {}` (suspending) | Not cancellable, can block writes |
| `Dispatchers.Main` for model access (2025.1+) | `Dispatchers.EDT` + explicit `ReadAction` | Write-intent removed |
| `SwingUtilities.invokeLater` for model | `Application.invokeLater` + `WriteAction.run{}` | No write-intent in 2025.1+ |

## Pipeline anti-patterns

| Wrong | Right | Why |
|-------|-------|-----|
| Preflight skips bad repos, continues | Block ALL repos if ANY preflight issue | Safety must not depend on rollback success |
| `allOk = succeeded.isNotEmpty()` | `allOk = !cancelled && succeeded.isNotEmpty() && failed.isEmpty()` | Cancelled is never OK |
| Boolean probe (`false` = "not found" OR "git error") | Tri-state probe (`null` = "can't determine" → block) | Fail-closed, not fail-open |
| Rollback inside cancelled operation | Rollback in fresh operation AFTER endOperation() | Cancelled operations reject new commands |
| Same try/catch in 3 phases, missing in 4th | Every phase has per-repo try/catch | One failure blocks remaining repos |

---

# Part 4: Domain Reference

## Rider SDK Version Mapping

| Rider | Build | Kotlin |
|-------|-------|--------|
| 2024.3 | 243 | 2.0-2.1 |
| 2025.1 | 251 | 2.1 |
| 2025.2 | 252 | 2.2 |
| 2026.1 | 261 | 2.3 |

```properties
# gradle.properties
rider.version=2026.1.1
plugin.sinceBuild=261
plugin.untilBuild=261.*
rider.path=C:/Program Files/JetBrains/JetBrains Rider 2026.1
```

Build against the earliest supported version, not the latest. Keep Kotlin/Gradle/Plugin/Rider versions as one tested matrix — upgrading just one dimension breaks.

## Threading Model (2025.1+)

| Concern | API |
|---------|-----|
| Pure Swing UI | `Dispatchers.EDT` or `Application.invokeLater()` |
| Read PSI/model | suspending `readAction {}` / `smartReadAction {}` |
| Write PSI/model | explicit write-action API, keep short |

Don't suspend inside read-action blocks. Call `ProgressManager.checkCanceled()`, don't throw `ProcessCanceledException` manually.

## CoroutineScope & Services

```kotlin
// ✅ Inject via service constructor
@Service(Service.Level.PROJECT)
class MyService(project: Project, val scope: CoroutineScope) : Disposable {
    override fun dispose() { /* platform manages scope lifecycle */ }
}
// ❌ project.coroutineScope — deprecated, leaks classloaders
```

Scope hierarchy: `Root → Application → Project → Plugin`. Auto-cancelled on project close/plugin unload.

Light services (`@Service`): no plugin.xml needed. Application-level persistent light services must disable roaming.

## Gradle build.gradle.kts

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}
// IntelliJ Platform Gradle Plugin 2.10+ incompatible with Rider local SDK.
// Gradle plugins block: no provider laziness — hardcode versions.
// buildSearchableOptions: disabled for Rider plugins.
```

## i18n

```kotlin
object Bundle : DynamicBundle("messages.Bundle") {
    fun msg(key: String, vararg args: Any): String = getMessage(key, *args)
}
```

Every key in BOTH `.properties` files. Self-audit: `grep -c "^[a-z.]+=" src/main/resources/messages/*.properties`.

## PersistentStateComponent

Legacy: `PersistentStateComponent<State>` with data class. Modern: `SimplePersistentStateComponent<State>(State())` with `BaseState`. JAXB can't serialize `MutableList`/`MutableMap`. Gson is separate from PSC.

## plugin.xml

```xml
<idea-plugin>
    <id>com.example.plugin</id>
    <vendor url="https://..." email="...">Name</vendor>
    <depends>com.intellij.modules.platform</depends>
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="MyToolWindow" ... />
        <notificationGroup id="My Group" displayType="BALLOON" />
    </extensions>
    <actions>
        <action id="My.Action" class="..." text="resource.key">
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt B"/>
        </action>
    </actions>
</idea-plugin>
```

ToolWindow `id`: string literal (not `Bundle.msg()`). `vendor`: real (not `internal`). `<errorHandler>`: enables crash reporting.

## Notification API

```kotlin
NotificationGroupManager.getInstance()
    .getNotificationGroup("My Group")
    .createNotification(title, content, NotificationType.INFORMATION)
    .notify(project)
// With action button:
    .addAction(NotificationAction.createSimple("Rollback") { onRollback() })
```

## Testing

Three layers: Pure logic (JUnit, no runtime) → Mocked interface (JUnit + delegation) → Real CLI (JUnit + temp dirs, `git init`).

When adding to an interface, touch ALL test fakes. Submodule repos need `user.email`/`user.name` configured in checkout dir. Don't use wall-clock timeouts in assertions — assert call budgets.

## UI Patterns

- Focus ring: use `jButton()` factory, not `JButton()` directly
- HiDPI: all sizes through `JBUI.scale()`, borders through `JBUI.Borders`
- Theme: colors through `JBColor(light, dark)`, never hex
- Layout: `ComponentListener` for responsive narrow/wide tool windows
- Disposal: `content.setDisposer(panel)`, message bus connections with `busConnection.disconnect()`

## ToolWindow & Dialogs

- `ToolWindowFactory.createToolWindowContent`: `content.setDisposer(panel)` for cleanup
- `DialogWrapper`: call `init()` at end of init block, `JBTable` for theme-aware tables
- `AnAction`: `update()` must be cheap (called per tick), `actionPerformed` runs on EDT → launch background work

## ComboBox Lifecycle

Async branch loading: always call `onLoadEnd` in BOTH success and failure paths. Check `isShowing` before updating — disposed combo crashes. Use `Dispatchers.IO` for git calls, `invokeLater` to return to EDT.

## File I/O & Atomic Writes

```kotlin
val tmp = Files.createTempFile(parent, name + ".", ".tmp")
try {
    Files.writeString(tmp, content)
    try { Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, file, REPLACE_EXISTING) }
} finally { try { Files.deleteIfExists(tmp) } catch (_: Throwable) {} }
```

## VFS Refresh

After git mutates files: `VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(dir)`. Refresh AFTER final stable state, not between phases. Use `Dispatchers.IO`.

## Plugin Lifecycle & Disposal

- `ProjectManagerListener` for project open/close events
- Light services: implement `Disposable`, platform calls `dispose()` on project close
- Message bus: every `connect()` must have a matching `disconnect()`
- Tool window panels implementing `Disposable`: register with `content.setDisposer(panel)`

## Compatibility & API Safety

- Build against `sinceBuild`, not latest IDE
- Run `verifyPlugin` to catch internal API usage
- Notable removals: `project.coroutineScope`, `SwingUtilities.invokeLater` for model, `Dispatchers.Main` write-intent, `ReadAction.compute{}` (2026.1)

## CI Matrix (from JetBrains official template)

Multi-OS × multi-IDE-version matrix. Qodana for static analysis. Dependabot for deps. `gradle-changelog-plugin` for automated changelog.

---

# Part 5: Project-Specific Architecture

## Dependency direction

```
Swing / Action / Configurable
        ↓
Controller / Service orchestration
        ↓
Pure state machines + notification decisions
        ↓
GitClient interface
        ↓
GitOps process implementation
```

Rules:
- `switch/` must not import from `ui/` — enforced by `quickCheck`
- All git through `GitClient`; no raw `ProcessBuilder("git")` outside `GitOps` — enforced by `quickCheck`
- Every multi-repo write: Preflight → Checkpoint → Execute → Rollback
- Safety probes tri-state or structured. Unknown = block, never "assume clean."

## References

- [IntelliJ Platform SDK Docs](https://plugins.jetbrains.com/docs/intellij)
- [Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- [SDK Code Samples](https://github.com/JetBrains/intellij-sdk-code-samples)
- [IntelliJ Platform Explorer](https://plugins.jetbrains.com/intellij-platform-explorer)
