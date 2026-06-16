# AI Shared Review

## Review Scope

- Date: 2026-06-16
- Target: `origin/main..HEAD`（5 个未推送提交）
- Type: R6 增量复审，并检查修复产生的二阶影响
- Result: `CHANGES_REQUESTED` — 2 个 P2

## Active Findings

### P2-1 迁移测试仍未结构化验证顶层 legacy 字段已删除

- Status: `OPEN`
- Evidence:
  - `PresetLoaderTest.kt:330-363` 仍通过第二次调用 `PresetLoader.load(tmpDir)` 验证写回结果，没有读取并解析写回 JSON。
  - 如果保存逻辑错误地继续写入顶层 `pull`，第二次加载会再次执行同一迁移并得到相同 domain model，因此这些测试仍可能通过。
  - `rg -n "JsonParser|JsonObject" PresetLoaderTest.kt` 无匹配；提交 `4fc985a` 标题声称 “structural JSON assertions”，实际未实现。
- Impact: 测试无法保护迁移的关键持久化合同：顶层 legacy `pull` 必须从文件中消失，嵌套 `overrides.pull` 必须按规则保留。
- Required fix:
  - 读取 `result.getOrThrow().first` 指向的写回文件，使用 Gson `JsonParser` 结构化解析。
  - 断言 preset object 不含顶层 `pull`。
  - 对 false/true/explicit override/dirty-only 矩阵分别断言嵌套 `overrides` 的期望状态。
- Verify:
  - `PresetLoaderTest --rerun-tasks` 全部通过。
  - 临时让保存结果保留顶层 `pull` 时测试失败。
  - 临时丢弃 explicit `overrides.pull=true` 时测试失败。

### P2-2 `CLAUDE.md` 的自动门禁章节仍包含多条人工规则

- Status: `OPEN`
- Evidence:
  - `CLAUDE.md:3` 声称本节“每条都有命令/门禁可验证，不过脑也能拦住”。
  - 本轮仅将 `TooGenericExceptionCaught` 说明移动到人工章节；`CLAUDE.md:6-9` 的异常处理、低价值测试、文档数字、接口实现完整性仍无自动门禁。
  - `quickCheck detekt` 本轮通过，但没有验证上述规则。
- Impact: AI/开发者仍可能误信不存在的自动保护，规则分类没有兑现章节承诺。
- Required fix: 将无法指出具体自动 task/规则实现的条目移到“没法自动验证”章节；或实现真实门禁及坏 fixture 后再保留。
- Verify: 为自动门禁章节每一条列出对应 task/实现；无法列出的条目不得留在该章节。

## Verified Summary

- explicit override 的错误 `assertNull` 已纠正为 `assertEquals(true, ...)`，`PresetLoaderTest` 当前全部通过。
- `TooGenericExceptionCaught` 未启用的说明已移动到人工经验章节，但其他人工规则尚未搬迁。
- `PresetEditor` suppress 删除、detekt 构造器阈值 13、空 JSON 测试精简均保持有效。
- 真实 service resolver 测试、legacy migration 基础矩阵和低价值 data-class 测试删除仍保持有效。

## Residual Test Gaps

- 快捷键 Force 确认入口、PresetEditor 折叠/指示器/刷新保留、窄 Tool Window 布局仍缺自动化证据；若本轮不处理，应明确标记 `ACCEPTED` 和理由。
- 测试 helper 中仍保留较多 `executeTest(preset, options)` 旧调用形状，类型守卫对测试调用链的约束弱于生产入口。

## Validation

- `git diff --check origin/main..HEAD`: PASS
- `./gradlew test --tests "com.submodule.branchswitcher.PresetLoaderTest" --rerun-tasks --max-workers=1 --no-parallel`: PASS，29 tests
- `./gradlew quickCheck detekt compileKotlin compileTestKotlin --rerun-tasks --max-workers=2 --no-parallel`: PASS
- Existing warning: Java source compatibility 17 与 Rider 2026.1.1 要求的 21 不一致；非本轮引入。
