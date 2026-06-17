# AI Shared Review

## Review Scope

- Date: 2026-06-17
- Target: `origin/main..HEAD` (2 unpushed commits: nested submodule discovery + QR-08 safety fix)
- Type: shared review re-check after QR-08 fix
- Result: `OPEN` — QR-08 is mostly fixed, but one ancestor/symlink loop edge remains

## Active Findings

### QR-2026-06-17-08 — Recursive submodule discovery still accepts paths resolving to repository root

- Status: `OPEN`
- Priority: `P2`
- Evidence: `src/main/kotlin/com/submodule/branchswitcher/git/GitOps.kt:222-225`
- Impact: The fix now rejects `.`, `..`, `../outside`, absolute Unix-style paths, and duplicate canonical directories. However, the root-boundary check explicitly allows `resolved == rootCanonical`. If `.gitmodules` contains a safe-looking path such as `link-to-root` and that directory is a symlink/junction back to the repository root, `listSubmodulePaths()` will add `link-to-root` to the result before recursion is stopped by `visited`. That avoids infinite recursion, but still accepts an ancestor/root loop as a valid submodule path. QR-08 asked to skip ancestor loop paths, not merely prevent recursion.
- Suggested fix: Seed `visited` with `rootCanonical`, or change the boundary check so recursive submodule paths must resolve strictly below the root (`resolved.startsWith(rootCanonical + File.separator)`) and not equal the root. Add a test where a child path resolves to the root (symlink/junction when supported, or a helper seam if symlink creation is unavailable on Windows).
- Verification: Add/adjust a targeted test for a path resolving to `rootCanonical`, then run `./gradlew test --tests "com.submodule.branchswitcher.git.GitOpsTest" --max-workers=1 --no-parallel`.

## Verified Summary

- Previous quick-switch review QR-01..QR-07 remains verified; no active quick-switch findings were re-opened.
- `VERIFIED` QR-08 partial fixes: recursive parsing now has a visited set, canonical root boundary check, depth limit, and rejects `.`, `..`, `../outside`, `SubA/../outside`, and Unix absolute paths.
- `GitOpsTest` now covers flat submodules, nested submodules, deep nested paths, missing nested `.gitmodules`, and several unsafe textual paths.
- `git diff --check origin/main..HEAD`: PASS.

## Accepted / Residual Items

- `ACCEPTED`: Full Gradle test was not rerun during this review. Targeted `GitOpsTest` passed; after the remaining QR-08 edge is fixed, run the targeted test again first.

## Validation

- `git status --short --branch`: `## main...origin/main [ahead 2]`
- `git diff --check origin/main..HEAD`: PASS
- `./gradlew test --tests "com.submodule.branchswitcher.git.GitOpsTest" --max-workers=1 --no-parallel`: PASS, BUILD SUCCESSFUL in 1m 57s.
