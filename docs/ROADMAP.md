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

Defer these changes until a concrete feature or failure needs them:

- Represent recovery as an inspectable rollback plan before execution.
- Define whether a submodule initialized during a failed switch should remain
  initialized or support an explicit cleanup policy.
- Replace remaining user-visible string failure reasons with richer domain
  errors when retry or diagnostic UI needs structured data.
- Re-evaluate CLI Git versus Git4Idea only when measured compatibility,
  performance, or credential handling justifies the migration cost.

These items do not currently block feature work or the first release.

## Not Planned

- Per-preset dirty/fetch/pull controls in the main Tool Window
- Local telemetry or usage statistics
- Broad architectural refactoring without a concrete product need
- Advertising unverified IDE families
