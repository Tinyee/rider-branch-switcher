# AI Shared Review

## Review Scope

- Date: 2026-06-17
- Target: `origin/main..HEAD` (1 unpushed commit: `46ce518 feat: #30 — recursive nested submodule discovery in listSubmodulePaths`)
- Type: local unpushed change review
- Result: `OPEN` — recursive `.gitmodules` parsing needs loop/path safety before #30 is complete

## Active Findings

### QR-2026-06-17-08 — Recursive submodule discovery can loop or escape the repository

- Status: `OPEN`
- Priority: `P1`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/git/GitOps.kt:195-207`
- Impact: `collectSubmodulePaths()` recursively follows each parsed `path` with `File(baseDir, path)` but has no visited set, depth limit, or safe-path validation. A damaged or unexpected `.gitmodules` such as `path = .`, `path = SubA/..`, an absolute path, or a symlink back to an ancestor can repeatedly read the same `.gitmodules`, recurse outside the repository, or eventually overflow the stack. This is a new risk introduced by changing `listSubmodulePaths()` from flat parsing to recursive parsing.
- Suggested fix: Resolve each candidate directory with canonical/real path, keep a `visited` set, and skip unsafe paths before recursing. At minimum reject empty/`.`/`..` components, absolute paths, paths that normalize outside the root, and directories already visited. Keep returning the original relative submodule path for safe entries.
- Verification: Add tests for `path = .`, `path = ../outside`, and a duplicate/ancestor loop scenario. Then run `./gradlew test --tests "com.submodule.branchswitcher.git.GitOpsTest" --max-workers=1 --no-parallel`.

## Verified Summary

- Previous quick-switch review QR-01..QR-07 remains verified; no active quick-switch findings were re-opened.
- `listSubmodulePaths()` now has positive tests for flat submodules, one-level nested submodules, deep nested paths, and nested directories without `.gitmodules`.
- Regular `GitOpsTest` currently passes, but it does not cover recursive loop/path-escape edge cases.

## Accepted / Residual Items

- `ACCEPTED`: Full Gradle test was not rerun during this review. A targeted `GitOpsTest` run passed; after QR-08 is fixed, run the targeted test again first.

## Validation

- `git status --short --branch`: `## main...origin/main [ahead 1]`
- `git diff --check origin/main..HEAD`: PASS
- `./gradlew test --tests "com.submodule.branchswitcher.git.GitOpsTest" --max-workers=1 --no-parallel`: PASS
