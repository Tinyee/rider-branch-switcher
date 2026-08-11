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

This is the complete active P3 inventory. Completed state-refresh, recovery
plan, and structured-diagnostic work is recorded in
[`review-history.md`](review-history.md).

### P3-08: Re-evaluate The Git Backend With Evidence

The plugin uses the Git CLI. Consider a Git4Idea migration only after measured
evidence identifies a compatibility, performance, or credential-handling
limitation that the CLI implementation cannot reasonably address.

This item does not currently block maintenance or the first release.

### P3-09: Automate Marketplace Publishing After The First Release

Publish 0.8.0 manually after the release checklist passes. Once that process is
proven, add a release-only workflow that validates the tagged source version,
runs `releaseCheck`, publishes with the protected Marketplace token, and keeps
ordinary pushes unable to publish.

This item starts only after the first successful manual Marketplace release.

### P3-10: Graceful Git Process Termination

`GitProcessRunner.terminateProcess` force-kills git processes (`destroyForcibly`)
on cancellation and timeout. A write process killed between creating `index.lock`
and writing the index leaves a stale 0-byte `index.lock` that silently blocks
every later git write — `git stash` in particular fails on it with exit 1 and no
stderr. Consider sending SIGTERM first, waiting briefly for cooperative exit (so
git can remove its own lock), then falling back to SIGKILL only if the process
is still alive. Validate the descendant-process shutdown paths in
`GitProcessRunner` before changing the termination order.
