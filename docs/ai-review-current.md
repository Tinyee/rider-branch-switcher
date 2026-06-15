# AI Shared Review

## Review Scope

- Date: 2026-06-16
- Target: `origin/main..HEAD` + 当前工作区修复
- Type: R5 增量复审，并检查修复产生的二阶影响
- Result: `CHANGES_REQUESTED` — 1 个 P1、1 个 P2

## Active Findings

### P1-1 迁移写回测试修复引入错误断言，并且目标测试当前失败

- Status: `OPEN`
- Evidence:
  - `PresetLoaderTest.kt:351-362` 的输入包含显式 `overrides.pull=true`；迁移合同要求显式 override 优先且写回后保留，但重新加载后却断言 `overrides.pull == null`。
  - 实际运行 `PresetLoaderTest`：29 tests，1 failed，失败位置为 `PresetLoaderTest.kt:362`。
  - `PresetLoaderTest.kt:330-361` 通过再次调用 `PresetLoader.load()` 验证写回结构。重新加载仍会执行同一迁移逻辑，因此 domain model 结果不能证明顶层 legacy `pull` 已从 JSON 删除；即使顶层字段仍存在，前两个用例也可能继续通过。
- Impact: 当前改动无法通过相关测试；同时没有真正保护“删除顶层 legacy 字段并保留嵌套 override”的迁移合同。
- Required fix:
  - 读取写回文件并用 Gson `JsonParser` 等结构化解析 JSON。
  - 断言每个 preset object 不含顶层 `pull`。
  - explicit override 用例同时断言 `overrides.pull == true`；dirty-only 用例可同时覆盖嵌套多字段，避免再次退回文本/字段顺序判断。
- Verify:
  - `PresetLoaderTest --rerun-tasks` 全部通过。
  - 临时让保存结果保留顶层 `pull` 时，结构化断言失败。
  - 临时丢弃显式 `overrides.pull=true` 时，显式 override 断言失败。

### P2-1 `CLAUDE.md` 的“自动门禁”章节仍包含人工规则

- Status: `OPEN`
- Evidence:
  - `CLAUDE.md:3` 仍声称本节“每条都有命令/门禁可验证，不过脑也能拦住”。
  - `CLAUDE.md:6` 新增文字明确承认异常规则“目前靠代码审查”；`CLAUDE.md:7-9` 的低价值测试、文档数字和接口实现完整性也没有自动门禁。
  - 本轮 `quickCheck detekt` 通过，但这些规则并未被自动验证。
- Impact: 规则分类仍会让 AI/开发者误以为人工审查项已有自动保护；修复只纠正了 `TooGenericExceptionCaught` 描述，没有解决章节分类问题。
- Required fix: 将所有只能靠代码审查的条目移到“没法自动验证”章节；自动门禁章节只保留能指出具体 task/规则实现的条目。
- Verify: 逐项为自动门禁章节中的规则指出实际 task/实现；无法指出的规则不得留在该章节。

## Verified Summary

- `PresetEditor` 的 `LongParameterList` suppress 已删除；detekt 的阈值从 12 调为 13，按 detekt 的“达到阈值即报告”语义，正确表达了允许最多 12 个构造参数。上一轮“阈值 12 时不需要 suppress”的判断不准确，现已纠正。
- 空 JSON Loader 测试已精简为单次写入、单次加载和有效断言。
- `BranchSwitcherServiceTest` 的真实 service resolver 测试、legacy migration 基础矩阵和低价值 data-class 测试删除仍保持有效。

## Residual Test Gaps

- 快捷键 Force 确认入口、PresetEditor 折叠/指示器/刷新保留、窄 Tool Window 布局仍缺自动化证据；若本轮不处理，应明确标记 `ACCEPTED` 和理由。
- 测试 helper 中仍保留较多 `executeTest(preset, options)` 旧调用形状，类型守卫对测试调用链的约束弱于生产入口。

## Validation

- `git diff --check`: PASS，仅有现存 LF/CRLF warning
- `./gradlew quickCheck detekt compileKotlin compileTestKotlin --rerun-tasks --max-workers=2 --no-parallel`: PASS
- `./gradlew test --tests "com.submodule.branchswitcher.PresetLoaderTest" --rerun-tasks --max-workers=1 --no-parallel`: FAIL，29 tests / 1 failure，`PresetLoaderTest.kt:362`
- Existing warning: Java source compatibility 17 与 Rider 2026.1.1 要求的 21 不一致；非本轮引入。
