# AI Shared Review

## Review Scope

- Date: 2026-07-15
- Target: full functionality and failure diagnosability review
- Result: `OPEN` - no obvious always-wrong branch switching path found, but rollback safety and failure diagnosis still have several actionable gaps
- Follow-up: the local-only statistics feature was fully removed; source, persisted fields, UI, i18n, tests, and active docs were checked with no new finding.

## Recommended Fix Order

1. Fix `SAFETY-01` first because it is the only P1 and affects rollback coverage.
2. Fix `FUNC-01` and `FUNC-02` next because they affect behavior and write safety.
3. Fix `LOG-01`, `LOG-02`, `LOG-03`, and `LOG-06` together as the P2 diagnosability batch.
4. Defer `LOG-04` and `LOG-05` if needed; they improve root-cause detail but do not directly change safety behavior.

## Active Findings

### SAFETY-01 - OPEN - P1 - switch checkpoint can be incomplete without blocking the switch

- Evidence: `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchExecutor.kt:150-159` silently skips any repo whose `revParseHead()` returns null, then continues with dirty handling / checkout / pull.
- Impact: If checkpoint capture fails for a repo that is later changed, rollback cannot restore that repo and the log does not explain that rollback coverage was incomplete. Derive already treats checkpoint failure as an atomic gate; normal switch is less safe.
- Suggested fix: Fail closed when an existing git repo cannot produce a checkpoint, or at minimum log `[checkpoint] <path>: missing HEAD / probe failed` and exclude that repo from mutation explicitly. Prefer blocking the whole switch unless there is a deliberate accepted reason.
- Validation: Add a `SwitchExecutorTest` where one repo returns null from `revParseHead()` and assert no checkout happens, or assert a visible checkpoint warning if the design intentionally allows partial coverage.

### FUNC-01 - OPEN - P2 - preflight failure can fall back to an empty preview and still allow switching

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/SwitchController.kt:49-57` catches preflight exceptions, logs `preflight probe failed`, then shows `SwitchPreviewDialog` with `emptyList()` and proceeds if confirmed.
- Impact: A systemic preflight failure can look like an empty/clean preview instead of a failed safety probe. The user can continue without seeing which repos were not inspected.
- Suggested fix: Fail closed on unexpected preflight failure, or show an explicit error dialog containing exception type/message and a clear `continue without preflight` warning if continuing is intentional.
- Validation: Add a controller/coordinator test around preflight exception behavior if practical; otherwise compile plus manual UI verification.

### FUNC-02 - OPEN - P2 - right-click single-submodule switch bypasses write gate and operation lifecycle

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/SubmoduleRowManager.kt:230-262` performs `checkoutExisting` / `checkoutFromRemote` directly from a context-menu coroutine, without `tryStartWrite()`, `beginOperation()`, `TaskBridge`, `onCancel`, `onFinished`, or VCS refresh.
- Impact: This hidden write path can run concurrently with full switch / derive / rollback, has weaker cancellation semantics, and failures are logged only as a first stderr line. It is inconsistent with the rest of the plugin’s guarded write model.
- Suggested fix: Either remove this niche action, route it through the same write gate + operation lifecycle, or make it a read-only/navigation action. If kept, add try/catch, diagnostics, and VCS refresh.
- Validation: Add a quickCheck rule or targeted test that all git write calls outside `SwitchRunner` / `SwitchController` are intentional and gated.

### LOG-01 - OPEN - P2 - notification rollback swallows runtime failures

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/SwitchFlowCoordinator.kt:141-164` catches `RuntimeException` from notification-triggered rollback and ignores it.
- Impact: If rollback from the failure notification crashes inside `TaskBridge.runBackground` or `executor.rollback()`, the user only sees generic rollback failure; plugin logs lose exception type/message.
- Suggested fix: Pass `AppLogger` into `rollbackSwitch(executor, log)` and log non-cancellation exceptions.
- Validation: Compile plus focused callback test if practical.

### LOG-02 - OPEN - P2 - shortcut action failures are not visible in the tool-window log

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/action/SwitchPresetAction.kt:50-71` creates an in-memory `logLines` collector and never displays or persists it; the coroutine only catches `CancellationException`.
- Impact: Switching via Ctrl+Alt+B can fail with less diagnosability than the ToolWindow path. Unexpected preflight/show-warning failures may only appear as coroutine/IDE diagnostics, not plugin logs.
- Suggested fix: Reuse `ToolWindowLogger` / service-level log sink, show a failure notification with exception type/message, or remove the unused collector and route shortcut execution through the same visible logging path.
- Validation: Add an action-path test if feasible; otherwise inspect shortcut-triggered logs manually.

### LOG-03 - OPEN - P2 - current-state detection coroutine can fail without tool-window evidence

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:345-351` calls `currentBranch()` / `isDirty()` without per-path exception handling.
- Impact: UI current-state indicators may silently become stale if a repo probe throws.
- Suggested fix: Catch per-repo non-cancellation exceptions, log `[detect] <path>: <ExceptionType>: <message>`, and continue probing remaining repos.
- Validation: Compile plus extracted pure detection test if practical.

### LOG-04 - OPEN - P3 - preflight probe errors hide root cause

- Evidence: `core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchPreflight.kt:67-81` fail-closes to a `[probe error]` row but drops exception type/message.
- Impact: Preview blocks risky switching, but logs cannot say whether current branch, dirty count, local branch, remote branch, filesystem, or another probe failed.
- Suggested fix: Add `onProbeError(path, label, throwable)` or a diagnostic field on `PreflightRow`.
- Validation: Update `SwitchPreflightTest` for probe diagnostics.

### LOG-05 - OPEN - P3 - several Git failure logs omit command and exit code

- Evidence: `GitResult` has `cmd`, `exitCode`, `stdout`, and `stderr`, but call sites such as `PullStep.kt:34`, `CheckoutStep.kt:52`, `SubmoduleSyncStep.kt:19`, `DeriveBranchExecutor.kt:176`, and `SubmoduleRowManager.kt:259` often log only the first stderr line.
- Impact: Auth errors, conflicts, hook failures, and remote errors can lose the actionable line and the exact command.
- Suggested fix: Add `GitResult.diagnostic(maxLines = 5, maxChars = 1000)` and use it in failed git logs.
- Validation: Add pure tests for the diagnostic helper.

### LOG-06 - OPEN - P2 - Git timeout is not classified separately from generic Git failure

- Evidence: `src/main/kotlin/com/submodule/branchswitcher/git/GitOps.kt:100-103` returns `GitResult(exitCode = -1, stderr = "timeout after ${timeoutSeconds}s")`, but higher-level steps collapse it into generic summaries like `checkout failed`, `pull had warnings`, or `fetch had warnings`.
- Impact: A preset may fail because a git command timed out, but notifications and step summaries do not clearly distinguish timeout from branch-not-found, auth failure, conflict, dirty workspace, or other errors.
- Suggested fix: Add `GitFailureKind.Timeout / Cancelled / StartFailed / GitFailed`, or at least `GitResult.isTimeout`, and surface `[timeout] <cmd> exceeded <timeout>s` in logs and summaries.
- Validation: Add pure timeout-classification tests plus one affected step test.

## Positive Notes

- Main ToolWindow switch flow logs preset name, step boundaries, repo paths, skipped/partial/fatal results, and final success/error state.
- `GitOps` captures command label, exit code, stdout, stderr, timeout, and cancellation in `GitResult`.
- Derive flow is more conservative than normal switch: it blocks on preflight/checkpoint uncertainty and logs per-repo rollback attempts.
- `ToolWindowLogger` writes warn/error entries to both tool window and IntelliJ diagnostic log while avoiding IDE fatal-error popups for expected business failures.
- The removal leaves no network, counter, install-ID, prompt, settings, or export path behind.

## Validation

- `./gradlew quickCheck --offline --no-daemon --max-workers=1 --no-parallel`: PASS.
- Platform compilation and target tests: BLOCKED before compilation. Online dependency resolution stalled; offline mode reported uncached IntelliJ/Gson/Kotlin dependencies. No test result is claimed.
- `git diff --check`: PASS
