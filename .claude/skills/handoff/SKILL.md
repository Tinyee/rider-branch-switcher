---
name: handoff
description: Claude-Codex handoff protocol - validation tiers, commit recording, avoid redundant rebuilds
---

# Claude <-> Codex Handoff Protocol

Invoke with `/handoff` at the start of a review session, or when switching
between coding (Claude) and reviewing (Codex). Both sides share this protocol.

## Validation Tiers

Pick the lightest tier that covers the change. Record which tier ran
in the commit message.

| Tier | Command | Time | When |
|------|---------|------|------|
| L1 | `./gradlew testClasses quickCheck` | ~20s | Only tests/comments/docs, no production code |
| L2 | `./gradlew testClasses quickCheck detekt` | ~30s | Refactored production code, no logic change |
| L3 | L2 + `./gradlew test --tests "<affected>"` | ~2min | Changed business logic in one module |
| L4 | `./gradlew test detekt --rerun-tasks` | ~7min | Changed GitOps/switch pipeline, or pre-push final |

## Handoff Commit Convention

Every commit that hands off to review SHOULD include:

```
Verify: L<N> PASS
Changed: <what production code or test paths are affected>
```

Example:
```
fix: PropertyTest Windows canonical-path collision

Changed: PropertyTest.kt (test input filter), docs/ (test count sync)
Verify: L3 PASS (testClasses + quickCheck + detekt + GitOpsTest + PropertyTest)
```

**Verify is a hint, not a substitute.** Codex uses it to decide what to re-run,
but follows the existing rule: review must re-check code and test evidence,
not trust status text. Re-run when:

- The diff touches `GitOps.kt`, `SwitchExecutor.kt`, or `SwitchStep.kt`
- The `Changed:` list looks incomplete vs the diff
- A previous review round found verification errors
- The tier is below what the diff warrants (e.g., L1 for production code)

## Commit Batching

- **Default: append review-fix commits.** Do not amend or rebase unless the user
  explicitly asks. This matches the existing rule.
- **Accumulate before push:** gather 2-5 commits before `git push` to avoid
  repeated `releaseCheck` (2 min each).
- **If user asks:** squash via `rebase -i` or `commit --amend` before pushing.

## Quick Rules

```
tests/comments/docs only   -> L1 -> commit
production code refactor   -> L2 -> commit
GitOps/SwitchExecutor      -> L3 -> commit
push / major milestone     -> L4 -> push
```

## Codex-Specific

- On receiving a handoff: read `Verify:`, use as a starting point for what
  to re-check. Still examine the diff independently.
- If re-running: use `--max-workers=1 --no-parallel` (low-load rules).
- Mark findings as `ACCEPTED` if they are pre-existing warnings, not
  introduced by this change.

## Claude-Specific

- Never claim PASS without running at least L1.
- Record the actual command output in the commit body if the tier is L3+.
- If a change was amended after verification, note it:
  `Verify: L2 before amend, re-verified L1 after amend`
