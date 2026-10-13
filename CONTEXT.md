# Submodule Branch Switcher

An IntelliJ Platform plugin that puts one main Git repository and its recursive
submodule graph onto a named set of branches in a single, undoable operation.

## Presets

**Preset**:
A named set of branch targets — one main branch plus a branch per submodule.
Identified by a stable id that survives renames; the name is only the
user-facing label.
_Avoid_: config, configuration, profile, branch set

**Target**:
One repository plus the branch a preset wants it on. Every preset has exactly
one main target and zero or more submodule targets, and targets are always
processed main-first.
_Avoid_: entry, pair, row, item

**Main repository**:
The Git repository the IDE project is opened on, and the one whose
`.gitmodules` registers the submodules.
_Avoid_: superproject, root repository, parent repository

**Submodule**:
A Git repository registered in the main repository's `.gitmodules`.
_Avoid_: child repo, nested repo

## Switching

**Switch**:
Putting every target's repository onto the branch its preset names, as one
operation.
_Avoid_: checkout, sync, apply, update

**Checkout**:
The single-repository Git primitive a switch performs against each target.
Reserved for that per-repository step, never for the whole operation.
_Avoid_: switch

**Derive**:
Creating one new branch with the same name across every target, starting from
the preset's branches. The way to switch onto branches that do not exist yet.
_Avoid_: create, fork, branch out

**Preflight**:
The read-only probe and confirmation shown before a switch is allowed to write
anything.
_Avoid_: dry run, validation, check

**Checkpoint**:
The pre-switch state recorded so the operation can be undone. Captured before
the first write.
_Avoid_: backup, save point, snapshot

**Rollback**:
Returning repositories to the checkpoint. One half of a recovery, not a synonym
for it, and not the editor's _discard_.
_Avoid_: undo, roll back

**Recovery**:
What runs after a switch fails or is cancelled: a rollback, plus restoration of
any stashes that switch created.
_Avoid_: cleanup, repair, revert

**Dirty**:
A repository holding uncommitted worktree changes. Its configured handling is
one of `Stash`, `Skip`, or `Force`.
_Avoid_: modified, unclean, pending

**Stash**:
Worktree changes set aside so a checkout can proceed. An _approved-discard
stash_ isolates untracked files the user agreed to remove; a _retained stash_
is one an earlier operation left behind.
_Avoid_: shelve, WIP, backup

## Preset Editor

**Draft**:
The preset editor's uncommitted edits. Distinct from _dirty_, which is always
about a Git worktree and never about editor state.
_Avoid_: pending changes, unsaved state, modifications

**Discard**:
Throwing away the editor's _draft_ and restoring the saved preset.
_Avoid_: revert, undo, reset, rollback

**Repository state snapshot**:
One reading of every repository's branch and _dirty_ flag, stamped with a
generation so superseded readings can be recognised and dropped.
_Avoid_: status, scan

**Write lease**:
Exclusive ownership of repository writes for one operation; no second write may
start while one is held.
_Avoid_: lock, mutex, semaphore

**Mutation gate**:
The editor-wide flag that is off while any write operation is running.
_Avoid_: busy flag, enabled state

**Branch load**:
Fetching the branch list a combo box offers. A load replaces the combo's
contents while it is in flight, so a branch load in progress is not a selection.
Distinct from `git fetch`, which is a separate switch option.
_Avoid_: discovery, query, branch scan
