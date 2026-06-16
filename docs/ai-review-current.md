# AI Shared Review

## Review Scope

- Date: 2026-06-16
- Target: `origin/main..HEAD` (3 unpushed commits)
- Type: local unpushed change review
- Result: `OPEN` — quick switch has behavior/UI-thread issues to fix before treating v0.7 as ready

## Active Findings

### QR-2026-06-16-01 — Quick Switch skips submodules when main repo is already on target branch

- Status: `OPEN`
- Priority: `P1`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:252-256`
- Impact: If the main repository is already on the typed branch, `doQuickSwitch()` returns before building the temporary preset. Submodules that are still on other branches will not be switched, even though README and tooltip describe quick switch as switching all repos.
- Suggested fix: Remove the `mainBranch == branch` early return. Quick switch should always build a preset with `main = branch` and every submodule target set to `branch`; existing preflight/checkout logic can decide which repos are already current.
- Verification: Add/adjust a test for the pure quick-switch preset builder covering `main already target + submodule differs`, then run the targeted test with `./gradlew test --tests "<TestClass>" --max-workers=2 --no-parallel`.

### QR-2026-06-16-02 — Quick Switch runs Git reads on the Swing event thread

- Status: `OPEN`
- Priority: `P1`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:247-262`; `GitOps.currentBranch()` uses `ProcessBuilder("git", ...)` in `src/main/kotlin/com/submodule/branchswitcher/git/GitOps.kt:101-103`.
- Impact: Clicking Quick Switch or pressing Enter can block the Tool Window while Git CLI commands run. Large repos, slow disks, or hung Git processes can freeze the UI before the existing background switch/preflight flow starts.
- Suggested fix: Keep only input validation on the EDT. Move submodule discovery / preset construction into a modal or background task, similar to `PresetListManager.addPresetFromCurrent()`, then return to EDT to call `switchController.runSwitch(tempPreset)`.
- Verification: Add a small test around extracted pure builder logic, and manually/structurally verify no `service.gitClient.currentBranch()` call remains in `doQuickSwitch()` before launching a background/modal task.

### QR-2026-06-16-03 — Quick Switch does not validate branch names

- Status: `OPEN`
- Priority: `P2`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:248-249`; existing validator is `src/main/kotlin/com/submodule/branchswitcher/switch/BranchNameRules.kt:7-21`; derive flow already uses it in `PresetEditor.kt:575-579`.
- Impact: Invalid input such as whitespace-containing names, `..`, `@{`, or leading `-` can enter preflight/switch flow. That creates noisy Git failures and inconsistent behavior versus derive branch.
- Suggested fix: Import and call `isValidBranchName(branch)` in quick switch before any Git work. Show an error dialog or log warning using an i18n message, mirroring derive branch behavior.
- Verification: Add tests for empty/invalid/valid quick-switch branch handling, or at minimum extend existing `BranchNameRules` usage coverage for the new quick-switch path.

### QR-2026-06-16-04 — Quick Switch has no targeted tests

- Status: `OPEN`
- Priority: `P2`
- Evidence: `rg "doQuickSwitch|quick\\.switch|Quick Switch" src/test` finds no targeted tests.
- Impact: The new entry point can regress without test signal, especially around main-already-current behavior, invalid input, and generated submodule targets.
- Suggested fix: Extract a pure helper such as `buildQuickSwitchPreset(branch, submodulePaths)` or `QuickSwitch.buildPreset(...)`, then test no-submodule, multiple-submodule, invalid branch rejection, and main-already-current scenarios.
- Verification: Run the new targeted test class with `./gradlew test --tests "<QuickSwitchTestClass>" --max-workers=2 --no-parallel`.

### QR-2026-06-16-05 — Marketplace/plugin description overclaims drag-and-drop reorder

- Status: `OPEN`
- Priority: `P3`
- Evidence: `src/main/resources/META-INF/plugin.xml:29` says `Drag-and-drop reorder with ↑↓ buttons`.
- Impact: Current docs describe reorder as implemented by arrow buttons, not drag-and-drop. Marketplace text would overpromise functionality.
- Suggested fix: Change the line to `Reorder presets with ↑↓ buttons` or similar.
- Verification: `git diff --check origin/main..HEAD`; optionally run `./gradlew quickCheck`.

### QR-2026-06-16-06 — CHANGELOG omits Quick Switch from v0.7.0

- Status: `OPEN`
- Priority: `P3`
- Evidence: `CHANGELOG.md:3-22` documents per-preset overrides/process updates, while README, ROADMAP, and plugin.xml already advertise quick switch as v0.7 functionality.
- Impact: Version documentation is inconsistent; release readers may not know quick switch is part of v0.7.
- Suggested fix: Add a short `Quick Switch (#31)` entry under `0.7.0`, describing the Tool Window branch-name input and no-preset flow.
- Verification: `rg "Quick Switch|quick switch|#31" CHANGELOG.md README.md docs/ROADMAP.md src/main/resources/META-INF/plugin.xml`.

## Verified Summary

- `origin/main..HEAD` currently contains 3 unpushed commits.
- Quick switch reuses `SwitchController.runSwitch(tempPreset)`, so preflight preview and Force dirty-strategy confirmation are already covered by the existing switch flow.
- `git diff --check origin/main..HEAD`: PASS.

## Accepted / Residual Items

- `ACCEPTED`: README screenshot remains TODO. This belongs to Marketplace release materials and does not block the code fixes above.
- `ACCEPTED`: Full Gradle test was not rerun during this review. After fixing code, run targeted tests first; before claiming completion, run relevant tests with `--rerun-tasks` per project rules.

## Validation

- `git status --short --branch`: `## main...origin/main [ahead 3]`
- `git diff --check origin/main..HEAD`: PASS
