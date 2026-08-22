# Complexity-contraction specs

Pre-Phase-1 contracts committed with the Phase 0 safety-characteristic test baseline.
These two tables constrain the refactor so each phase shows a net deletion without
re-introducing the over-engineering it removes.

## Spec 2 — Git command read/ref/index/config classification

Every `SwitchGitClient` / `DeriveGitClient` / recovery write method must appear in this
table. The four write categories make the classification explicit, so the table does not
look complete while silently missing a category (e.g. `submoduleSync` is a config write).

| Git method | Read | Branch/ref/HEAD | Index/worktree | Config/admin | Index gate |
|---|---:|---:|---:|---:|---:|
| fetch | | remote-tracking refs + object db | | | 否 |
| checkoutExisting | | HEAD | ✓ | | 是 |
| checkoutFromRemote | | branch + HEAD | ✓ | | 是 |
| checkoutNewBranch | | branch + HEAD | ✓ | | 是 |
| pullFf | | branch + HEAD | ✓ | | 是 |
| stash | | refs/stash | ✓ | | 是 |
| stashPaths (new) | | refs/stash | ✓ | | 是 |
| stashApply | | | ✓ | | 是 |
| stashDrop | | refs/stash | | | 否 |
| resetHard | | branch | ✓ | | 是 |
| deleteBranch | | ref | | | 否 |
| submoduleSync | | | | config | 否 |
| submoduleInitPath | | | ✓ | config | 是 |
| Derive rollback + branch/SHA checkout; recovery checkout paths | | branch + HEAD | ✓ | | 是 (via funnel) |

- **Gate criterion is index/worktree mutation, not ref mutation.** fetch, stashDrop,
  deleteBranch, submoduleSync are ungated.
- **Layer ownership.** The `index.lock` gate lives in `runIndexMutation()` (Phase 4).
  There is no stash/quarantine gate inside `GitCommandClient` (Phase 1): approved-stash
  handling is a workflow-layer concern.

## Spec 4 — Topology constraint (main vs submodule wiring)

The most non-regressable architecture condition of this refactor, written as an
unbreakable constraint:

- **MAIN.** The fixed pre-stash step (approved-path stash) runs before the MAIN WIP
  stash. The global pipeline step never processes a submodule.
- **SUBMODULE.** Never passes through the global step. Approved-path stash runs only
  inside `SubmoduleTreeStep.updatePreparedTarget`, and only **after** the `isUnregistered`
  topology gate.
- **Unregistered/obsolete worktree.** No scan, no manifest, no fetch, no stash, no
  checkout.

Phase 0 characterizes all three; Phase 2 must keep them fixed. This is the exact
regression the discard-before-topology-gate bug introduced.
