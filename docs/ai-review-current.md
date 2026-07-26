# AI Shared Review

## Review Scope

- Date: 2026-07-15
- Target: full functionality and failure diagnosability review
- Result: `ACCEPTED` - all P1/P2 findings are verified; the preview-diagnostic enhancement is now verified
- Follow-up: the local-only statistics feature was fully removed; source, persisted fields, UI, i18n, tests, and active docs were checked with no new finding.
- Architecture follow-up (2026-07-26): `ARCH-01` was fixed by removing an unreachable duplicate rollback path; `ARCH-02` and `ARCH-03` are accepted P3 observations in `docs/architecture-review-2026-07-26.md`.
- Second-pass follow-up (2026-07-26): `ARCH-04` blocks shortcut switching when preset loading fails; `ARCH-05` completes structured Git diagnostics on rollback, stash, and single-submodule paths.
- Test follow-up (2026-07-26): shortcut load-result branching is now a direct pure-JVM contract. The action's IntelliJ dialog and event-bus wiring remain covered indirectly by the platform suite, rather than by a heavy action fixture.

## Active Findings

None.

## Positive Notes

- `SAFETY-01` — VERIFIED: an existing repository with no readable HEAD now blocks switching before checkout; `SwitchExecutorTest` and the full `:core:test test :core:detekt detekt --rerun-tasks` suite passed.
- `FUNC-01` — VERIFIED: ToolWindow preflight exceptions notify and stop before opening a preview; the shortcut path reports unexpected failures instead of leaving them to coroutine diagnostics.
- `FUNC-02` — VERIFIED: The row component delegates single-submodule switching to `PresetListManager`, whose write uses the service gate, `TaskBridge`, Git cancellation lifecycle, notification, and VCS refresh.
- `LOG-01` — VERIFIED: notification-triggered rollback records unexpected runtime exceptions in the ToolWindow log.
- `LOG-02` — VERIFIED: the shortcut action emits structured log entries through `BranchSwitchListener`, and the ToolWindow renders them in its existing log panel.
- `LOG-03` — VERIFIED: current-state detection catches and logs non-cancellation failures per repository, then continues probing the remaining paths.
- `LOG-05` / `LOG-06` — VERIFIED: `GitResult` classifies cancellation, timeout, startup, and Git failures; bounded diagnostics include the command and exit code, and core switch/derive failure logs use them.
- `ARCH-04` / `ARCH-05` — VERIFIED: shortcut preset loading fails closed, and every remaining write-failure log uses the shared Git diagnostic format.
- `LOG-04` — VERIFIED: `PreflightRow.probeError` carries a capped exception diagnostic; the preview renders it as a warning, and `SwitchPreflightTest` covers the value and its 300-character limit.
- `TEST-01` — VERIFIED: `ShortcutSwitchRulesTest` covers failed loading with cached presets, empty successful loading, and ready selection. `SwitchPresetAction` delegates its branch decision to that pure contract.
- `TEST-02` — VERIFIED: removed one duplicate Git setup check and one checkout test that asserted no observable skip behavior; fresh full-output counting avoids stale XML inflating the documented suite size.

- Main ToolWindow switch flow logs preset name, step boundaries, repo paths, skipped/partial/fatal results, and final success/error state.
- `GitOps` captures command label, exit code, stdout, stderr, timeout, and cancellation in `GitResult`.
- Derive flow is more conservative than normal switch: it blocks on preflight/checkpoint uncertainty and logs per-repo rollback attempts.
- `ToolWindowLogger` writes warn/error entries to both tool window and IntelliJ diagnostic log while avoiding IDE fatal-error popups for expected business failures.
- The removal leaves no network, counter, install-ID, prompt, settings, or export path behind.

## Validation

- `./gradlew :core:cleanTest cleanTest :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel`: PASS (290 tests; both Detekt reports empty; count taken from clean full outputs).
- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS.
- `git diff --check`: PASS.
