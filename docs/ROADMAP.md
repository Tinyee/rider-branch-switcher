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

This is the complete active P3 inventory. Apply an item only when a feature,
bug, or measured limitation makes the change worthwhile. Historical P3
observations that were resolved by architecture work remain in
[`review-history.md`](review-history.md).

### P3-04: Isolate Tool Window State Refresh

`BranchSwitcherPanel` owns repository-state subscriptions, debounce timing,
stale-result filtering, and snapshot delivery. Extract a coordinator only when
that lifecycle grows further; do not split visual panels merely to reduce line
counts.

### P3-05: Model Recovery As A Plan

Recovery executes directly from checkpoints and tracked stashes. Introduce an
inspectable rollback plan before execution only if recovery needs preview,
retry selection, audit output, or additional recovery strategies.

### P3-07: Use Structured Recoverable Diagnostics

Some recoverable failures still cross layer boundaries as user-visible strings.
Replace them with richer domain errors when retry actions, diagnostics, or
localized presentation need structured data.

### P3-08: Re-evaluate The Git Backend With Evidence

The plugin uses the Git CLI. Consider a Git4Idea migration only after measured
evidence identifies a compatibility, performance, or credential-handling
limitation that the CLI implementation cannot reasonably address.

These items do not currently block feature work or the first release.
