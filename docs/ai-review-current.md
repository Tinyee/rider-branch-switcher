# AI Shared Review

## Review Scope

- Date: 2026-07-15
- Target: full functionality and failure diagnosability review
- Result: `ACCEPTED` - all P1/P2 findings are verified; one P3 preview-diagnostic enhancement is deferred
- Follow-up: the local-only statistics feature was fully removed; source, persisted fields, UI, i18n, tests, and active docs were checked with no new finding.
- Architecture follow-up (2026-07-26): `ARCH-01` was fixed by removing an unreachable duplicate rollback path; `ARCH-02` and `ARCH-03` are accepted P3 observations in `docs/architecture-review-2026-07-26.md`.
- Second-pass follow-up (2026-07-26): `ARCH-04` blocks shortcut switching when preset loading fails; `ARCH-05` completes structured Git diagnostics on rollback, stash, and single-submodule paths.

## Active Findings

### LOG-04 - ACCEPTED - P3 - preflight probe errors hide root cause

- Evidence: `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchPreflight.kt:67-81` fail-closes to a `[probe error]` row but drops exception type/message.
- Impact: Preview blocks risky switching, but logs cannot say whether current branch, dirty count, local branch, remote branch, filesystem, or another probe failed.
- Deferred rationale: the preflight already fail-closes and labels the affected repository. Surfacing sanitized exception detail in the preview requires a model/UI contract change; keep it as a targeted future diagnosability improvement rather than expanding this safety-focused refactor.
- Future validation: update `SwitchPreflightTest` and the preview rendering test when the diagnostic field is introduced.

## Positive Notes

- `SAFETY-01` — VERIFIED: an existing repository with no readable HEAD now blocks switching before checkout; `SwitchExecutorTest` and the full `:core:test test :core:detekt detekt --rerun-tasks` suite passed.
- `FUNC-01` — VERIFIED: ToolWindow preflight exceptions notify and stop before opening a preview; the shortcut path reports unexpected failures instead of leaving them to coroutine diagnostics.
- `FUNC-02` — VERIFIED: The row component delegates single-submodule switching to `PresetListManager`, whose write uses the service gate, `TaskBridge`, Git cancellation lifecycle, notification, and VCS refresh.
- `LOG-01` — VERIFIED: notification-triggered rollback records unexpected runtime exceptions in the ToolWindow log.
- `LOG-02` — VERIFIED: the shortcut action emits structured log entries through `BranchSwitchListener`, and the ToolWindow renders them in its existing log panel.
- `LOG-03` — VERIFIED: current-state detection catches and logs non-cancellation failures per repository, then continues probing the remaining paths.
- `LOG-05` / `LOG-06` — VERIFIED: `GitResult` classifies cancellation, timeout, startup, and Git failures; bounded diagnostics include the command and exit code, and core switch/derive failure logs use them.
- `ARCH-04` / `ARCH-05` — VERIFIED: shortcut preset loading fails closed, and every remaining write-failure log uses the shared Git diagnostic format.

- Main ToolWindow switch flow logs preset name, step boundaries, repo paths, skipped/partial/fatal results, and final success/error state.
- `GitOps` captures command label, exit code, stdout, stderr, timeout, and cancellation in `GitResult`.
- Derive flow is more conservative than normal switch: it blocks on preflight/checkpoint uncertainty and logs per-repo rollback attempts.
- `ToolWindowLogger` writes warn/error entries to both tool window and IntelliJ diagnostic log while avoiding IDE fatal-error popups for expected business failures.
- The removal leaves no network, counter, install-ID, prompt, settings, or export path behind.

## Validation

- `./gradlew :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel`: PASS (288 tests; both Detekt reports empty).
- `./gradlew quickCheck --max-workers=1 --no-parallel`: PASS.
- `git diff --check`: PASS.
