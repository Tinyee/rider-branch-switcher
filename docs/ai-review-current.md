# AI Shared Review

## Review Scope

- Date: 2026-06-16
- Target: `origin/main..HEAD`（3 个未推送提交）
- Type: 本地改动审查 + 全量测试数量核实
- Result: `CHANGES_REQUESTED` — 1 个 P1

## Active Findings

### P1-1 版本号元数据不一致，会阻塞 releaseCheck

- Status: `OPEN`
- Evidence:
  - `CHANGELOG.md` 最新 heading 是 `## [0.7.0] — 2026-06-16`。
  - `build.gradle.kts` 仍是 `version = "0.6.0"`。
  - `README.md` badge 仍是 `version-0.6.0`。
  - `releaseCheck` 会校验 CHANGELOG 第一版号必须等于 Gradle project version。
- Impact: 运行 `./gradlew releaseCheck` 时会因为版本不一致失败；发布包版本、README 和 changelog 会互相矛盾。
- Required fix: 选择一个版本策略并统一：
  - 若 per-preset overrides 准备作为 0.7.0 发布，则同步 `build.gradle.kts` 和 README badge 到 `0.7.0`。
  - 若暂不升版本，则把 CHANGELOG 顶部 `0.7.0` 改为未发布草稿或并入当前版本说明，避免触发 releaseCheck 校验。
- Verify: `./gradlew releaseCheck` 至少通过版本一致性阶段；或运行相同校验逻辑确认 README badge 与 CHANGELOG heading 匹配 Gradle version。

## Verified Summary

- 全量测试已实际运行：270 tests / 21 classes / 0 failures / 0 errors / 0 skipped。
- `docs/ROADMAP.md` 的 `271 测试` 已更正为 `270 测试 / 21 个测试类`。
- `TestHelpers.executeTest(...)` 内部走 `ResolvedSwitchRequest.resolve(...)`，方向正确。
- `pluginIcon.svg` 已存在，next-steps 中不再标为缺失。

## Accepted / Residual Items

- `ACCEPTED`: README 截图仍是 TODO，属于 Marketplace 发布物料，不阻塞当前文档/测试数量同步。
- `ACCEPTED`: Java source compatibility 17 与 Rider 2026.1.1 要求 21 的警告仍存在，测试通过；后续可单独评估 JDK 21 toolchain。

## Validation

- `./gradlew test --rerun-tasks --max-workers=1 --no-parallel`: PASS，270 tests / 21 classes / 0 failures / 0 errors / 0 skipped。
- `git diff --check origin/main..HEAD`: PASS。
