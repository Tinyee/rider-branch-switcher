# Changelog

## [0.6.0] — 2026-06-13

### Architecture
- Structured logging: `AppLogger` interface (info/warn/error/debug/activity) replaces `(String) -> Unit`
- `ToolWindowLogger` routes to both IntelliJ diagnostic log and tool window panel
- `LogEntry.Level` enum replaces string-prefix color matching
- `Activity` level restores blue color for switch/rollback/derive operations
- `jButton()` factory in `UiUtil.kt` eliminates scattered `.noFocusRing()` calls

### Features
- **Stable Preset ID**: `Preset.id` (UUID) survives renames — undo history works even after renaming
- **Cancellable Git commands**: user cancel terminates the running `git` process within ~100ms
- Old JSON auto-generates IDs and writes back immediately on load
- `onCancel` callback in `TaskBridge.runBackground` for reliable cancel wiring

### Quality
- `error()` uses `ideaLogger.warn()` to avoid triggering Rider Fatal Errors for business failures
- Log levels assigned by semantics: failures → warn, diagnostics → debug, activities → activity
- `AppLoggerTest` (13 cases): level contract tests for fetch/checkout/stash/pull failures, Fatal, Partial, Activity
- `GitOps.run()` uses operation-scoped cancellation that blocks commands reached after cancellation
- Review fixes: 3 rounds of log-level migration corrections

### Docs
- README, ROADMAP, CHANGELOG synchronized to 0.6.0
- `plugin.xml` vendor updated to match GitHub remote (Tinyee)
- Historical review docs (`code-review-2026-06-08.md`, `ui-redesign-plan-2026-06-09.md`) marked archived

## [0.5.0] — 2026-06-07

### Architecture
- Split God Class `BranchSwitcherPanel` (793→362 lines) into `SwitchController`, `PresetListManager`, `SubmoduleRowManager`
- Unified async model to 100% coroutines with `TaskBridge` suspend wrappers
- 6-package structure: `ui/`, `switch/`, `service/`, `git/`, `model/`, `action/`
- Dependency injection via constructors throughout

### Features
- **Settings Configurable** — File → Settings → VCS → Submodule Branch Switcher
- **History persistence** — switch history survives IDE restarts (undo still works)
- **Dynamic remote name** — detects actual remote instead of hardcoding `origin`
- **Preflight warnings** — Ctrl+Alt+B shortcut shows missing dirs/branches before switching
- **Preset rename validation** — prevents duplicate names
- **Import count fix** — shows actual imported count, not JSON total

### Quality
- **28 bug fixes** across all layers (double-resume crash, DirtyAction ignored in shortcut, toolWindow ID i18n leak, etc.)
- **131 tests** (JUnit 4 + Kotest property-based)
- Kotest 5.9.1 property-based testing (6 generators)
- Gradle wrapper 8.13
- GitHub Actions CI with test artifacts on ubuntu/macOS/Windows
- `@PropertyKey` compile-time i18n key validation
- `@SerializedName("pull")` backward-compatible field rename
- HiDPI-aware column widths

### Docs
- ROADMAP with 50-item review findings, v1.0 Marketplace prep, testing strategy

## [0.4.0] — 2026-06-06

### Features
- **Stash auto-pop**: switch back to original branch → `git stash pop` automatically
- **Progress visualization**: progress bar with step name, repo name, and fraction
- **Keyboard shortcut** `Ctrl+Alt+B`: pop-up preset picker from anywhere
- **Derive feature branch**: `checkout -b` on main + all submodules from a preset
- **UI polish**: `JBUI.scale` layouts, `JBUI.Borders`, `JProgressBar` for switch progress
- **Context menu**: right-click submodule row → switch only this / open in Explorer
- **Drag-to-reorder**: ↑↓ buttons for preset ordering
- **Undo switch**: rollback to previous preset from history

### Architecture
- `BranchSwitchListener` + `MessageBus` for cross-component events
- `CoroutineScope` platform injection in `BranchSwitcherService`

### Tests
- 93 tests (BundleTest, SubmoduleRowManagerTest, SwitchStepTest, SwitchIntegrationTest, etc.)

## [0.3.0] — 2026-06-05

### Features
- **Per-preset main diff label**: shows `current → preset.main` in orange in the header
- **Partial failure rollback**: checkpoint before switch → rollback action in failure notification
- **Configurable timeout**: 30/60/120/300s options in the tool window
- **Cancellable switch**: pipeline steps check `indicator.isCanceled`
- **Persistent switch options**: dirty/fetch/pull/timeout stored in `branch-switcher.xml`

## [0.2.0] — 2026-06-04

### Features
- **Dry-run preview table**: per-repo `current → target`, dirty count, branch source
- **Submodule auto-sync**: `git submodule sync` after main checkout
- **Auto-init missing dirs**: `git submodule update --init` for missing submodules
- **"From Current State" preset creation**: one-click preset from live HEAD branches
- **IDE notifications**: errors, partial failures with rollback action
- **Theme-aware colors**: `JBColor` throughout, no hardcoded hex
- **AllIcons**: replaced ▶/▼/✕ characters with IntelliJ native icons

## [0.1.0] — 2026-06-02

### Initial release
- Multi-preset persistence (JSON)
- One-click switch main + submodules
- Branch combo with type-to-filter
- Current preset highlighting + switch button auto-disable
- Basic dirty/fetch/pull options
