# Roadmap

This file contains only future project work and explicit non-goals. Released
changes belong in [`../CHANGELOG.md`](../CHANGELOG.md), current product
capabilities in the [README](../README.md), and completed technical decisions
in [`review-history.md`](review-history.md).

## P1: Prepare The First Public Release

- Run `./gradlew releaseCheck` against the final commit.
- Perform a Rider smoke test:
  - load and save presets
  - switch between two complete presets
  - cancel an active Git command and verify recovery
  - initialize a missing submodule
  - derive and undo a feature branch
  - verify dark/light themes and Chinese/English text
- Confirm the three README screenshots match the final plugin build.
- Review Marketplace description, license, vendor details, icon, compatibility
  wording, and ZIP contents.
- Publish only after Plugin Verifier and the Rider smoke test pass.

### Pre-release UX: Shortcut Entry Consistency ✅ Done 2026-08-13

Two user-facing gaps made the "Switch to Preset" shortcut (Ctrl+Alt+B, i.e.
Ctrl+Option+B on macOS) feel disjointed from the sidebar and fail to scale with
the number of presets. Both were closed: the shortcut now picks from a
filterable chooser (type-to-filter, per-row branch summary) and confirms through
the same `SwitchPreviewDialog` as the sidebar. Recorded in the
[changelog](../CHANGELOG.md).

## P2: Expand Compatibility Evidence

The unified IntelliJ IDEA distribution is the primary build target and Rider
is the current compatibility target. Follow the evidence requirements in the
[support matrix](SETUP.md#support-matrix-policy) before advertising another IDE
family.

Candidate verification order:

1. PyCharm Professional
2. WebStorm
3. CLion

## P3: Targeted Maintainability

This is the complete active P3 inventory. Completed state-refresh, recovery
plan, and structured-diagnostic work is recorded in
[`review-history.md`](review-history.md).

### P3-08: Re-evaluate The Git Backend With Evidence ✅ Done 2026-08-15

Evaluated against measured evidence and the CLI implementation is retained.
Process budgets are already near-minimal (one porcelain-v2 status process per
repository, at most three first-time preflight processes, four-process global
cap), CI passes on three OSes, and the hardened process lifecycle (graceful
tree termination, output caps, structured failures, per-operation
cancellation) has no known compatibility, performance, or credential gap.
Git4Idea would not remove process spawning for writes and would replace the
pinned process-layer tests and index-lock-safe termination with an internal
API that still lacks the distinctive commands (porcelain-v2 batch status,
`git config --null` .gitmodules reading, stash-by-oid apply).

Re-evaluate only when one of these appears: a user reports a submodule fetch
failing because credentials live only in the IDE credential store, a
compatibility report where `git` is unavailable on PATH but configured in the
IDE (or the reverse), or Git4Idea API usage in the plugin grows beyond the
single VCS-refresh call.

### P3-09: Automate Marketplace Publishing After The First Release

Publish 0.8.0 manually after the release checklist passes. Once that process is
proven, add a release-only workflow that validates the tagged source version,
runs `releaseCheck`, publishes with the protected Marketplace token, and keeps
ordinary pushes unable to publish.

This item starts only after the first successful manual Marketplace release.

### P3-10: Graceful Git Process Termination ✅ Done 2026-08-11

`GitProcessRunner.terminateProcess` now sends SIGTERM to the process tree first
and waits briefly for a cooperative exit (so git can remove its own `index.lock`),
then falls back to SIGKILL only for processes still alive after the grace window.
The descendant shutdown paths were validated and are covered by tests. Recorded
with the surrounding index-lock hardening in `review-history.md`.

### P3-11: Derive Feature Scope ✅ Keep 2026-08-14

Keep the derive feature rather than cutting it or gating it behind a setting.
Derive is one unobtrusive entry point (a button plus input dialog in the preset
editor) that atomically creates a feature branch across the main repository and
all submodules — a multi-repository operation stock git cannot do in one step.
It is covered by focused tests (executor, runner, notification). It is a
self-contained parallel implementation rather than shared switch logic, so any
future reduction is a convergence toward switch's OperationIssue/notification
machinery, not a deletion.

### P3-12: Recovery/Rollback Scope ✅ Keep 2026-08-14

Keep the full rollback surface rather than surfacing only the stash.
Auto-rollback to the checkpoint HEAD plus stash restore is the tool's core
safety property: a failed or interrupted switch otherwise strands the user on a
new branch with a half-updated submodule tree. Each guard (identity recheck,
clean-worktree requirement, at-most-once stash apply, retained-submodule
tracking) maps to a concrete documented failure mode rather than speculative
complexity. Reducing to "surface the stash" would remove the self-healing
behavior recovery exists to provide.

### P3-13: Responsive Layout — Consolidate Duplicated Helpers ✅ Done 2026-08-15

Kept the hand-rolled responsive layout (the adaptive stacking, 340 px compact
transition, and grouped overflow menu are the product's visual identity and
cannot be reproduced by stock Swing/IntelliJ layout). The tidy-up is complete
with no visual change:

- Shared one `COMPACT_WIDTH = JBUI.scale(340)` constant between `GlobalActionBar`
  and `CollapsibleActionBar` so the two compact thresholds cannot drift.
- Extracted the repeated `centeredY`, `availableContentWidth`, and `contentLeft`
  calculations into shared `JComponent` helpers next to `effectiveLayoutWidth()`.
- Unified the metric property-listener registration into one
  `registerMetricsRelayout` helper, closing the gap where `GlobalActionBar` and
  `TrailingControlRowPanel` did not relayout when a button's text or icon
  changed.

`UiLayoutTest` (10 tests), the full suites (189 + 166), Detekt, and `quickCheck`
all pass.

### P3-14: Git Interface Hierarchy ✅ Keep 2026-08-15

Keep the capability-oriented hierarchy rather than collapsing to a concrete
client. Both collapse premises fail on evidence: the `as?` batch fallbacks are
not dead code — narrow test doubles (10 sites; e.g. an `IndexLockBlockTest`
fake implementing only `indexLockFile`) rely on them — and `core` cannot depend
on the concrete `GitCommandClient` (it lives in the plugin module and imports
an IntelliJ Logger), so the interfaces are the dependency-direction carrier and
the actual minimal test-double boundary. Every remaining interface has its own
production consumer (switch steps, derive, preflight, preset discovery,
repository state) and narrow fakes.

The cleanup is done: `GitCancellation` (zero direct references, only inherited
by `SwitchGitClient`) is merged into `SwitchGitClient`, reducing the count from
14 to 13 with no behavior change; tests, Detekt, and `quickCheck` pass. The
`WriteGuardGitClient` wrapper this section flagged is gone — the
complexity-contraction refactor deleted it and moved the index.lock
check-then-act gate into `GitCommandClient.runIndexMutation`, and
`DirtyHandlingStep` no longer inspects any wrapper.

### P3-15: Deferred Maintainability Backlog ✅ Closed 2026-08-24

Quality and style findings deliberately deferred from the 2026-08-21 fix
rounds. Every item is now classified — done, or cancelled with a recorded
reason. The broader Phase 3 structural refactor was cancelled at its evidence
gate (no real duplication outside the frozen area); these residuals were the
part worth doing.

**Done:**
- Dead `changed` in `PresetLoader.normalizePresetIds` — `2a13386` (Phase 1 C1).
- Timeout list single source (`timeoutOptionsSeconds()`) and
  `DEFAULT_TIMEOUT_SECONDS` — `8e5fb46` (Phase 1 C2).
- Magic indices in `BranchSwitcherConfigurable` → `indexToDirtyAction` /
  semantic mappings — `8e5fb46` (Phase 1 C2).
- Consecutive `addHistory` dedup — `96c6131` (Phase 1 C3).
- Normalize unknown persisted strings at the `loadState` boundary (dirtyAction
  through the enum, timeout to a supported value, `getState` exports only
  canonical) — `0a4ca5b`.
- Drop the 2 s reflog-watcher no-op log — `41bc057`.
- Reword the misleading "rollback-skipped" warning — `41bc057`.
- Collapse the duplicate single-repository checkout/branch-missing WARN —
  `41bc057`.

**Cancelled (insufficient benefit, 2026-08-24):**
- Bound the `remoteName` / `checkedProjects` caches with eviction.
  `checkedProjects` still exists but holds at most one key per project per IDE
  session (the git-availability check runs once when a project's tool window
  opens), so there is nothing worth evicting; operation-session remote caches
  are naturally bounded by one operation and the target-repository count; the
  long-lived direct client never calls `remoteName()`.
- Split the nine types out of `core/model/PresetConfig.kt`.
- Replace `AppLoggerTest`'s ~20-method anonymous git fake with a shared fake.
- Return a defensive copy from `PresetRepository.presets` (no mutable-alias
  evidence in the production call chains; see `review-history.md`).

### P3-16: Native Searchable Branch Chooser For Branch Fields

The editable branch combos' type-to-filter popup does not open on the Rider LAF
in the field: typing never reaches the editor key listener, and programmatic
`setPopupVisible` is unreliable (verified on both a pre-feature `origin/main`
build and the current one, so this is an environment/LAF limitation, not a
regression). Targets are still picked from the drop-down arrow (full list) or
by typing the exact branch name.

Revisit when the preset-switch chooser — which already ships a proven
filterable chooser (search + list) — can be reused: replace the per-field
editable-combo type-ahead with a native IntelliJ searchable chooser, opened
from the main-repository and submodule branch fields.

### P3-17: Shallow-Module Candidates Reviewed And Retained ✅ Keep 2026-10-13

A deepening review of the branch-combo cluster and its neighbours proposed
turning shallow modules into deep ones. Three candidates were examined and every
one was either implemented or rejected on evidence; the seven modules below were
checked against the deletion test and **retained**. Recorded so a later review
does not re-propose the split.

**Retained — deleting each would concentrate complexity, not remove it:**

- `WriteOperationLauncher` — three sites construct their own, but `tryAcquireWrite`
  is one shared gate (`BranchSwitcherService.writeGate`), so separate instances
  still exclude each other. It also owns when the lease is released and that
  `afterRelease` runs only on success.
- `BranchLoadHandle` — three members look like pure delegation, but `cancel()` is
  narrower than `Job.cancel()` in a load-bearing way: it is the only thing that
  guarantees the Git session is cancelled with the coroutine. A caller holding a
  raw `Job` would cancel the coroutine and leak the process.
- `BranchLoadCoordinator` — removing it spreads session lifecycle, resource
  release, and the concurrency bound across five call sites.
- `RemoteBranchCache` — removing it spreads a map, its TTL, and the expiry
  arithmetic across four call sites, three of them non-trivial.
- `core/git` interface hierarchy — many narrow test doubles depend on it, and
  `core` cannot import the concrete client. Consistent with P3-14.
- `core/switch`'s seven steps — they share one `SwitchStep` interface; each is
  thin but does real work, so none is a pass-through. Part of the frozen pipeline
  ADR-0001 describes.
- `PresetCollectionHost` — already a separated view seam, not a leak.

**Candidates not taken, and why:**

- Merging the four branch-load client properties into one `BranchComboState` was
  rejected: ADR-0001's bar was not met (no second consumer), and the cited
  "17 `as?` casts" was really 17 accesses of which five are casts. The test-seam
  half of it was implemented instead.
- A shared composer for the preset-load fallback text was rejected — see below.

**Open findings from the same review, none urgent:**

- The `onLoadStart` / `onLoadEnd` protocol is hand-written three times
  (`PresetEditor`, `SubmoduleRowManager` twice), differing only by `row?.` and
  `row.`.
- `loadComboBranches` and `refreshComboBranches` are two operations sharing about
  40% of one protocol; `ComboBranchTarget` is used by the first and bypassed by
  the second, which is an unfinished refactor rather than a design.
- `BranchLoadCoordinator.launch` and `launchWorkflow` have identical bodies; the
  split exists only to avoid a cast, by their own KDoc.
- `cache: RemoteBranchCache?` is threaded through seven signatures and is nullable
  only for tests — production always passes a real one.
- "Is this row loaded?" is re-derived by callers from `cancelComboBranchLoad`'s
  `Boolean` — corrected 2026-10-13 from "at three sites" to **two**, and both are
  inside `SubmoduleRowManager` (`:117 removeRow`, `:329 cancelBranchLoads`), so
  neither is a second consumer under ADR-0001. Re-reading all four call sites
  shows the other two discard the result. What the `Boolean` actually schedules is
  the `loaded` flag clear, which is the same dual-meaning flag as the finding
  below — so this is a facet of that one, not a separate item.
- `SubRow.loaded` carries two meanings at once — "a load has been *started*" and
  "the list is *ready*" — across ten writes and five reads, all in
  `SubmoduleRowManager`. Every start-of-load path (`:170`, `:183`, `:237`, `:312`,
  `:371`) sets it true before the Git request goes out, so both gates that read it
  as *ready* — `loadAllBranches` (`:236`), `fillRows` (`:255`) — can in principle
  pass on a row that has not answered yet. **Traced 2026-10-13: this is latent,
  not an active bug.** `loadAllBranches` is reached only from
  `PresetEditor.loadBranches:378`, which is guarded by `!branchesLoaded:358`, and
  every path that resets `branchesLoaded` also clears the flag on the affected
  rows — the collapse path via `cancelBranchLoads:330`, the failure path via
  `:296`/`:381`. So at every reachable call the rows with `loaded == true` have
  completed. The gate is correct by that chain of three clear-paths, not because
  `loaded` means *ready* — which is exactly the fragility worth naming.
- The Swing read at `:260` (`!row.combo.isEnabled || row.combo.selectedItem ==
  LOADING_BRANCH`) is the reason the `isEnabled`/`LOADING_BRANCH` pair exists in
  `SubmoduleRowManager` at all: `fillRows` cannot tell a mid-load row from a
  failed one using `loaded`, so it asks Swing instead. Exactly one test reaches it
  — `fillRows leaves a mid-load row alone` — by hand-forging the three combo
  fields (`SubmoduleRowManagerTest:495-497`) that `loadComboBranches` itself
  writes. Mutation-checked 2026-10-13: deleting `:260` fails that test, so the
  guard's *own* logic is covered; deleting the two `combo.isEnabled = false`
  writes in `BranchComboUtil` (`:211`, `:313`) is caught by a different file
  entirely (`BranchComboUtilTest :: disposed combo is not modified but completes
  loading`). No test spans both, so the invariant `:260` relies on — *disabled
  combo implies a load is in flight* — is asserted only by a fixture, never
  exercised across the seam. That is the contract a `loaded` split would have to
  make explicit rather than assume. `hasUnloadedRows` (`:323`) is unaffected: its
  `any { !deleted && !loaded }` keeps the same meaning under either reading, since
  the entry is cleared on start and on failure.
- Verified 2026-10-13 against ADR-0001: the effective consumer count for the
  branch-load lifecycle is **one**, not two. `:260` is the only place outside the
  `BranchComboUtil.kt` lifecycle owner that reads load state, and it is inside
  `SubmoduleRowManager`. The bar is not met; do not reopen on this ground.
  `ui/BranchComboUtilTest.kt:151-155` reads `itemCount` and `getItemAt` while a
  load is pending, but only as the precondition for the ended state it asserts on
  — a test double's plumbing, not a second module learning the state machine.

**Three counts in the list above were wrong; the rest was re-verified against the
tree on 2026-10-13 and holds** — the three hand-written `onLoadStart`/`onLoadEnd`
sites; `launch`/`launchWorkflow` with byte-identical bodies
(`BranchLoadCoordinator.kt:56-65`); and `cache: RemoteBranchCache?` nullable at
seven signatures against a non-null `refreshRemoteSubmodules` (`:103`), with only
`SubmoduleRowManager:36` defaulting to null so a test can omit it.

- The `BranchComboUtil.kt` declaration count is **22**, not 20 — a `grep` that
  missed the four `suspend fun`s and undercounted. The four-concept split the
  finding describes is real: widget construction (`makeBranchCombo`,
  `filterBranchPopup`), selection semantics (`comboOfferedBranches`,
  `setComboOfferedBranches`, `comboBranchSelection`, the seed pair), load
  lifecycle (`loadComboBranches`, `refreshComboBranches`,
  `cancelComboBranchLoad`, the four keys), and Git discovery
  (`refreshSubmoduleUnion`, `listSubmoduleUnion`, `discoverBranchChoices`,
  `discoverRemoteHeads`, `dedupSortedUnion`).
- `GitOperationSession` (`GitWorkflowClient.kt:17`) resolves to **25** methods via
  its four leaf interfaces (`SwitchGitClient` 14, `DeriveGitClient` 3,
  `PresetDiscoveryGitClient` 3, `SwitchPreflightGitClient` 5), not ~40. The
  consequence the finding draws stands at 25: **12** test files build a
  `GitOperationSession` fake through `java.lang.reflect.Proxy`, each taking a
  `(String) -> Any?` responder rather than a typed double. (Two further files use
  `Proxy` against `Project` and `ProcessHandle`, which are not this finding.)
- `RemoteBranchCache`'s expiry seam is real but narrower than "negative-TTL
  arithmetic" suggests: `ttlMillis` is injected, so the test does not manipulate
  the clock — it sets `ttlMillis = -1` and every stored entry is expired at once.
  That cannot express "not yet expired" against a real elapsed time, which is the
  part an `isExpired(storedAt, now)` seam would fix. The test's own comment
  (`RemoteBranchCacheTest:17`) misdescribes the mechanism as `ttlMillis = 0`.
- The preset-load fallback text was inconsistent between the two entry points —
  the shortcut called a failed load "Import failed". Fixed 2026-10-13: both now
  fall back to `error.unknown`. No shared composer was added; they differ by one
  character, so an abstraction would remove an already-consistent line at the cost
  of a new core function and its test class. Revisit only if a third instance of
  the shape appears.

### P3-18: Write-Path Orchestration Has No Test Seam

Verified 2026-10-13 by mutation testing, not by reading. The classes that
orchestrate every repository mutation had **zero** test references:

| class | lines | test references before |
| --- | --- | --- |
| `ui/SwitchFlowCoordinator` | 329 | none |
| `ui/SwitchController` | — | none |
| `action/SwitchPresetAction` | — | none |
| `workflow/WriteOperationLauncher` | 63 | none (no test class ever) |

Checked `git log --all` for `SwitchFlowCoordinatorTest`, `SwitchControllerTest`,
`SwitchPresetActionTest`, `WriteOperationLauncherTest`: **none of the four has
ever existed** in this repository's history. This is accumulated absence, not a
regression from any one change.

**Why the mutation sweep, and not coverage.** The plugin module runs 343 tests
(600 with `core`), and every one of them passed with the write path gutted. Each
mutation below was applied to `src/main`, the full module suite re-run, and the
source restored:

| mutation | verdict | caught by |
| --- | --- | --- |
| `SwitchUiCompletion` loses its once-only guard | detected | `SwitchUiCompletionTest` |
| launcher: lease release removed | detected | `SingleRepositorySwitcherTest` |
| launcher: `afterRelease` also runs on the failure path | detected | `WriteOperationLauncherTest` (added) |
| launcher: `CloseOnce` guard removed | detected | `WriteOperationLauncherTest` (added) |
| launcher: `onBusy()` call removed | **undetected** before | `WriteOperationLauncherTest` (added) |
| launcher: completion safety-net close removed | **undetected** before | `WriteOperationLauncherTest` (added) |
| shortcut action body reduced to an immediate `return` | **undetected** | — |
| `SwitchFlowCoordinator` switch-failure logging removed | **undetected** | — |
| shortcut resolves the main repository silently (`resolve()` not `resolveOrReport()`) | **undetected** before | `ArchitectureRulesTest` (added) |
| busy indicator never claimed (switch) | **undetected** | — |
| busy indicator never claimed (rollback) | **undetected** | — |
| busy indicator never claimed (derive) | **undetected** | — |
| busy claim moved **above** the `job == null` early return (switch) | **undetected** before | `ArchitectureRulesTest` (added) |

Two mutants were discarded as invalid rather than counted: one was an
equivalent mutant (Kotlin's language semantics, not a guard, delivered the
behaviour), and one was written as byte-identical text. Both were re-run in
corrected form; the table records only the corrected results.

**The dividing line is a dependency, not a size.** The two classes the suite does
cover — `SingleRepositorySwitcher` and `SwitchUiCompletion` — are exactly the two
that take no `Project`. Every uncovered class takes one.

Why no platform fixture was ever available, checked rather than assumed: the
plugin module's test dependencies are `gson`, `junit`, `kotlin-test`, and
`archunit` — `intellijPlatform` is configured with no `testFramework(...)`
entry. A `BasePlatformTestCase` subclass fails to compile against that classpath
(verified by adding one and reading the unresolved-reference errors). Adding a
fixture means a new test dependency and probably an IDE runtime in CI, which is
a decision in its own right, not a step.

**Done 2026-10-13, first step:** `WriteOperationLauncherTest` (7 tests, no
`Project`) closes the launcher's three holes. Each of its assertions was
confirmed to fail against the mutation it exists to catch; the M11 mutant in
particular still escaped after the first draft, because no draft test let a job
finish before entering its body — the only path where that safety net fires.

**Done 2026-10-13, second step:** `ArchitectureRulesTest` gained an enumerated
rule pinning which call sites may use the silent `resolve()`. This closed two
rows above at once — the shortcut's silent resolution, and the reorder hazard —
because both are the same defect shape: a call placed so it takes effect where it
should not. The rule is bytecode-level, so it sees method references, and it
caught a site the survey had missed: `BranchSwitcherPanel`'s reflog watcher
passes `mainRepository::resolve` on a timer, which is correct (a missing root is
silence, not a notification per tick) but invisible to a name-based search. Both
directions are pinned by mutation: flipping any of the seven command sites fails
the rule, and removing a declared silent site fails the stale-entry half.

**Open:** the three busy-indicator claims (switch, rollback, derive) and the
shortcut's failure logging. All four want a platform fixture, and they are the
only items left in this entry.

The obvious route — lift the busy *decision* out of the `Project`-taking class
the way `presetEditorControlState` was lifted into `core` — **was checked against
the code and does not apply.** That precedent worked because it is a pure
function of its inputs (`localBusy` and `draftUsable` decide four booleans). The
three busy claims are not decisions but effects, and each is a different effect:
the switch claim is a callback with no paired release site, the rollback claim
pairs with an immediate `job.invokeOnCompletion`, and the derive claim calls
`setSwitchInProgress`, which mutates a field, notifies a listener, and reaches
`ToolWindowManager` for the tool-window icon — that one cannot be lifted at all.
A shared helper would have to carry three different release semantics as
parameters, which is demotion, not extraction. `ARCHITECTURE.md` already forbids
introducing an abstraction only to reduce line count.

**Also found, and it misled the first pass:** two of the three claim sites carry
a KDoc sentence explaining that claiming late keeps the busy-rejection path from
touching state. Neither comment is redundant — moving the claim *above* the early
return is a real bug, and the moved form compiles with every test still green
(checked: the claim was relocated above `if (job == null) return` and all 343
plugin tests passed). So the comments describe a genuine hazard that no test
covers, which is the *sharpest* form of this finding rather than a stale comment:
a documented, one-line-reorder bug with nothing standing in its way.
