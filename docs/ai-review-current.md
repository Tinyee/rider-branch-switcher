# AI Shared Review

## Review Scope

- Date: 2026-06-14
- Target: 未提交的 per-preset overrides 实现
- Baseline: `docs/design/per-preset-overrides.md`
- Type: 实现复审 R3
- Result: `CHANGES_REQUESTED` — 原 3 项已部分/全部修复，仍有 2 个 P2、1 个 P3

## Active Findings

### P2-1 Override 折叠与窄窗口布局仍不完整

- Status: `OPEN`
- Evidence:
  - `PresetEditor.kt:336-341` 新增了 `Dirty/Pull/Fetch` 标签，但 `toggleOverrides()` 在 `PresetEditor.kt:345-347` 只切换三个 combo，可折叠后标签仍悬空显示。
  - 标签、三个固定宽度 combo 和标题全部放在单行 `FlowLayout`；仅 combo 宽度已达 290px，加上标签、标题、齿轮和间距明显超过 `BranchSwitcherPanel` 的 280px 最小宽度，未提供换行或响应式降级。
- Impact: 折叠后的 UI 语义不完整；窄 Tool Window 下 override 行会拥挤、截断或产生不必要的横向空间。
- Required fix: 将三个“标签 + combo”组成实际可折叠的内容容器，toggle 统一控制该容器；为窄窗口提供换行、纵向布局或响应式布局，不要继续把全部字段固定在单行。
- Verify: 组件/UI 测试覆盖展开、折叠时标签与 combo 一起变化；手工验证约 280px 与常规宽度下均可辨识。

### P2-2 测试计划仍只完成一部分

- Status: `OPEN`
- Evidence:
  - 已补：`effectiveOptions`/`ResolvedSwitchRequest` 基础测试、5 个 Force helper 测试、import overrides/malformed dirty/null entry 测试。
  - 仍缺：legacy pull 的迁移优先级与 migrationSaver 写回测试、顶层 DTO null 安全测试、`BranchSwitcherService.resolveSwitchRequest()` 四个全局字段映射、快捷键 Force 确认入口测试、PresetEditor 折叠/指示器/刷新保留交互测试。
  - `TestHelpers.kt` 仍通过 34 个 `executeTest(preset, options)` 调用保留旧调用形状，没有完成设计要求的显式 request API 迁移。
- Impact: 当前二阶 UI 回归没有测试拦截；迁移写回、service 接线和快捷入口安全确认仍可能回归而目标测试通过。
- Required fix: 优先补 PresetEditor 交互纯逻辑/组件测试、Loader migrationSaver 矩阵、service resolver 和快捷入口确认；核心 API 合同测试直接使用 `ResolvedSwitchRequest`，机械 executor 测试 helper 若保留需明确理由。
- Verify: 对照设计文档 §11 逐项映射到实际测试方法；相关目标测试 `--rerun-tasks` 通过。

### P3-1 已无必要的 LongParameterList suppress

- Status: `OPEN`
- Evidence: `PresetEditor` 已使用 `GlobalOptionLabels` 将构造参数降至 detekt 阈值内，`detekt --rerun-tasks` 可通过，但 `PresetEditor.kt:61` 仍保留 `@Suppress("LongParameterList")`。
- Impact: suppress 已失去用途，会掩盖未来再次超过参数阈值的回归，并违反实现复审模板“不用 suppress 掩盖门禁”的约束。
- Required fix: 删除该 suppress，重新运行 detekt。
- Verify: `./gradlew detekt --rerun-tasks --max-workers=2 --no-parallel` PASS。

## Verified Summary

- `VERIFIED` 原 P1 detekt：未使用 `opts` 已删除，三个 callback 已聚合为 `GlobalOptionLabels`；`detekt --rerun-tasks` PASS。
- `VERIFIED` 原 P2 可辨识性：Dirty/Pull/Fetch 已有独立 Bundle 标签，中英文 key 对称。
- `PARTIAL` 原 P2 测试缺口：新增 21 个 merge/request、Force helper、import 测试均通过；剩余缺口见 P2-2。
- `VERIFIED` 既有修复：快捷入口 Force 确认、当前编辑状态指示器、Settings 刷新保留、tooltip i18n、resolver/request 接线仍正确。

## Validation

- `git diff --check`: PASS（仅现有 LF/CRLF warning）
- `./gradlew quickCheck detekt compileKotlin compileTestKotlin --max-workers=2 --no-parallel`: PASS；其中 detekt 首次为 UP-TO-DATE
- `./gradlew detekt --rerun-tasks --max-workers=2 --no-parallel`: PASS，6s
- PresetConfigTest + SwitchPreviewDialogTest + PresetImportRulesTest（`--rerun-tasks --max-workers=1`）：
  - XML 报告：21 tests / 0 failures / 0 errors / 0 skipped
  - Gradle 客户端在报告生成后仍未退出，6 分钟超时后已停止；需单独排查测试任务退出问题
