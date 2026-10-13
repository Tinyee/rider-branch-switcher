# Structural refactor stops at the evidence gate

The `ui` package draws the eye: `BranchComboUtil.kt` alone is 573 lines, and the
branch-combo cluster (`BranchComboUtil`, `BranchLoadCoordinator`,
`SubmoduleRowManager`, `RemoteBranchCache`) absorbs the largest share of commits.
The structural refactor that would have broken it up was cancelled at its
evidence gate, because the duplication it was meant to remove does not exist
outside the frozen area. What looks like an unexplored opportunity is a decided
one — the maintenance work that survived the gate was completed and closed as
ROADMAP P3-15 on 2026-08-24.

## Context

A Phase 0–4 complexity-contraction refactor was planned with contracts written
up front in `docs/complexity-contraction-specs.md`: each phase had to show a net
deletion without re-introducing the over-engineering it removed. Phase 3 was the
broad structural pass over the remaining hotspots.

It did not clear that bar. Outside the frozen area — the switch pipeline, whose
complexity is inherent to the problem rather than accidental — no real
duplication was found for it to contract. Proceeding would have been motion
without a measurable gain, so it was cancelled.

## What was decided instead

Nine residual findings were carried forward as P3-15 and each classified. Eight
landed as small behavior-neutral fixes across `2a13386`, `8e5fb46`, `96c6131`,
`0a4ca5b`, and `41bc057`. Four were then cancelled on their own merits:

- **Bound the `remoteName` / `checkedProjects` caches with eviction.** Nothing to
  evict: `checkedProjects` holds at most one key per project per IDE session
  (the git-availability check runs once when a tool window opens), operation-session
  remote caches are bounded by one operation and the target count, and the
  long-lived direct client never calls `remoteName()`.
- **Split the nine types out of `core/model/PresetConfig.kt`.** File size alone.
- **Replace `AppLoggerTest`'s ~20-method anonymous git fake with a shared fake.**
- **Return a defensive copy from `PresetRepository.presets`.** No mutable-alias
  evidence in the production call chains.

Three earlier structural questions have their own recorded answers in the ROADMAP
and should not be re-opened without new evidence: P3-14 keeps the
capability-oriented Git interface hierarchy (the narrow test doubles depend on
it, and `core` cannot import the concrete client), and P3-11 and P3-12 keep
derive and the rollback surface. From the 2026-07-26 review, ARCH-02 accepted the
specialized write-operation scaffolding on the condition that it is reconsidered
only if a fourth substantial write workflow appears or lifecycle changes start
repeating across two existing paths.

## Consequences

Read a hotspot dashboard as a *where*, never a *whether*. High commit counts on
`ui` reflect active feature work on branch discovery and the preset editor, not
accumulated structural debt.

Two things would legitimately reopen this. The ARCH-02 trigger — a fourth write
workflow, or lifecycle churn repeating across two paths — is a genuine signal,
because it is evidence of duplication rather than a guess at it. And a new
consumer of the combo state would re-raise the shallow-module question: that
state currently lives in four untyped `JComboBox` client properties
(`KEY_BRANCH_LOAD`, `KEY_BRANCH_LOAD_TOKEN`, `KEY_BRANCH_LOAD_SEED`,
`KEY_ALL_BRANCHES`), read and written through `as?` casts at 17 sites inside
`BranchComboUtil.kt`. One file is not yet a problem; a second reader outside it
would be the evidence the refactor lacked.

The bar for both is the same one Phase 3 failed: name the duplication, or the
second consumer, before proposing the split.
