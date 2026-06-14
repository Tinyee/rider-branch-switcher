# AI Shared Review

## Review Scope

- Date: 2026-06-14
- Target: 当前本地未提交修改（PresetEditor 注释 + Loader/Model 测试）
- Type: 实现审查 R4
- Result: `CHANGES_REQUESTED` — 2 个 P2、2 个 P3

## Active Findings

### P2-1 新增测试没有覆盖其声称的入口合同

- Status: `OPEN`
- Evidence:
  - `PresetConfigTest.kt:87-103` 名称和注释声称验证 “Service resolver mapping”，但只调用 `ResolvedSwitchRequest.resolve(preset, global)`；没有调用 `BranchSwitcherService.resolveSwitchRequest()`。如果 service 漏传或错传任一全局字段，这些测试仍会通过。
  - `PresetLoaderTest.kt:350-355` 验证 explicit `overrides.pull` 优先，但没有验证存在 legacy `pull` 时仍触发 migrationSaver/写回并删除顶层字段。如果 `needsPullMigration` 对显式 override 错误返回 false，该测试仍会通过。
- Impact: 测试名称制造了入口接线与迁移写回已覆盖的假象，但对应生产路径回归不会被拦截。
- Required fix:
  - 将 service 映射测试移到 `BranchSwitcherServiceTest`，设置四个 service 属性后调用 `service.resolveSwitchRequest(preset)`。
  - 对 legacy pull + explicit override 使用注入的 `migrationSaver` 断言调用次数和保存后的 domain model，或读取写回 JSON 并结构化断言顶层 `pull` 已删除。
- Verify: 临时破坏 service 字段映射和 `needsPullMigration` 条件时，对应测试应失败；恢复后相关目标测试通过。

### P2-2 设计测试计划仍未完成

- Status: `OPEN`
- Evidence: 本轮补了 legacy pull 基础写回和 null item，但仍缺：
  - legacy `pull=false` + 仅 `overrides.dirty/fetchFirst` 时合并并保留字段；
  - 顶层 `{}`、`presets:null` 的 Loader 入口测试；
  - 快捷键 Force 确认入口、PresetEditor 折叠/指示器/刷新保留交互测试；
  - 34 个 `executeTest(preset, options)` 仍保留旧调用形状。
- Impact: 迁移字段保留、UI 二阶回归和入口安全确认仍可能回归。
- Required fix: 按设计文档 §11 和实现复审模板逐项核销；无法自动化的项目必须明确 `ACCEPTED` 和理由。
- Verify: 测试计划每项映射到实际测试方法或明确接受风险。

### P3-1 新增 equality 测试没有回归保护价值

- Status: `OPEN`
- Evidence: `PresetConfigTest.kt:105-110` 只验证 `PresetOverrides` data class 的结构相等性，没有调用迁移或任何生产逻辑；生产迁移代码完全损坏时仍会通过。
- Impact: 增加维护成本并让测试数量产生虚假的覆盖感，违反项目“不测试 data class/语言行为”的规则。
- Required fix: 删除该测试，替换为 `PresetDto.toPreset()` 的迁移优先级/字段保留测试。
- Verify: 替换后的测试在破坏迁移逻辑时失败。

### P3-2 LongParameterList suppress 仍未移除

- Status: `OPEN`
- Evidence: `PresetEditor.kt:61` 本轮只修改了 suppress 注释，`@Suppress("LongParameterList")` 仍存在；当前构造器为 12 参数且 detekt 在强制重跑时通过。
- Impact: suppress 会掩盖未来参数数量再次超过阈值的回归；注释修改没有解决上一轮问题。
- Required fix: 删除 suppress 和对应解释注释，重新运行 detekt。
- Verify: `./gradlew detekt --rerun-tasks --max-workers=2 --no-parallel` PASS。

## Residual Existing Finding

- Override 标签与 combo 已一起折叠，但 override 行仍使用单行 `FlowLayout`；约 280px 窄 Tool Window 的可用性尚未获得组件测试或手工验证证据。

## Verified Summary

- `PresetLoaderTest` 新增的 legacy `pull=false`、legacy `pull=true` 写回测试会经过真实 Loader 并验证写回文件。
- null preset item 的 Loader 入口不再仅停留在 DTO 纯逻辑层。
- 当前本地改动未修改生产行为。

## Validation

- `git diff --check`: PASS（仅现有 LF/CRLF warning）
- `./gradlew quickCheck detekt compileKotlin compileTestKotlin --rerun-tasks --max-workers=2 --no-parallel`: PASS
- `PresetLoaderTest + PresetConfigTest --rerun-tasks --max-workers=1 --no-parallel`: PASS，38 tests / 0 failures / 0 errors / 0 skipped，4m 55s
