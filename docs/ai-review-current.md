# AI Shared Review

## Review Scope

- Date: 2026-06-17
- Target: `origin/main..HEAD` (5 unpushed commits)
- Type: shared review re-check after quick-switch fixes
- Result: `OPEN` — original QR-01..QR-06 are verified, one new lifecycle issue remains

## Active Findings

### QR-2026-06-17-07 — Quick Switch invokeLater lacks project disposed guard

- Status: `OPEN`
- Priority: `P2`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:257-264`
- Impact: Quick Switch now builds the temporary preset on `Dispatchers.IO`, then posts back to Swing with a raw `ApplicationManager.getApplication().invokeLater { ... }`. If the project/tool window is disposed between the background read and the UI callback, the callback can still clear the field and call `switchController.runSwitch(tempPreset)` on a disposed project. This is the same lifecycle class previously fixed elsewhere with disposed guards.
- Suggested fix: Add a project-alive guard before touching UI or starting the switch, for example:
  `if (project.isDisposed) return@invokeLater`
  inside the invokeLater block, or introduce/reuse a small panel-local `invokeLaterIfProjectAlive` helper.
- Verification: Structural check that the quick-switch invokeLater block guards `project.isDisposed`; then run `./gradlew test --tests "com.submodule.branchswitcher.ui.UiRulesTest" --max-workers=1 --no-parallel`.

## Verified Summary

- `VERIFIED` QR-2026-06-16-01: Main-repo-current early return was removed. `buildQuickSwitchPreset(branch, submodulePaths)` always includes all submodule targets.
- `VERIFIED` QR-2026-06-16-02: Quick Switch no longer calls `gitClient.currentBranch()` on the Swing event thread. Submodule path discovery moved into `service.scope.launch(Dispatchers.IO)`.
- `VERIFIED` QR-2026-06-16-03: Quick Switch now calls `isValidBranchName(branch)` before any background work.
- `VERIFIED` QR-2026-06-16-04: Targeted pure-builder tests were added in `UiRulesTest`.
- `VERIFIED` QR-2026-06-16-05: `plugin.xml` now says `Reorder presets with ↑↓ buttons`, no drag-and-drop overclaim.
- `VERIFIED` QR-2026-06-16-06: `CHANGELOG.md` now includes `Quick Switch Without Preset (#31)`.
- Quick switch still reuses `SwitchController.runSwitch(tempPreset)`, so preflight preview and Force dirty-strategy confirmation remain covered by the existing switch flow.

## Accepted / Residual Items

- `ACCEPTED`: README screenshot remains TODO. This belongs to Marketplace release materials and does not block the code fixes above.
- `ACCEPTED`: Full Gradle test was not rerun during this re-check. A targeted UI rules test was run; after QR-07 is fixed, run targeted tests first and relevant `--rerun-tasks` tests before claiming completion.

## Validation

- `git status --short --branch`: `## main...origin/main [ahead 5]`
- `git diff --check origin/main..HEAD`: PASS
- First targeted test attempt timed out at 120s and left a Windows file lock in `build/test-results/test/binary/output.bin`.
- `./gradlew --stop`: stopped the stale daemon/file lock.
- `./gradlew test --tests "com.submodule.branchswitcher.ui.UiRulesTest" --max-workers=1 --no-parallel --no-daemon`: PASS, BUILD SUCCESSFUL in 2m 6s.
