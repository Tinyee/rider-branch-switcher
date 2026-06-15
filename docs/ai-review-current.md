# AI Shared Review

## Review Scope

- Date: 2026-06-16
- Target: `origin/main..HEAD`（3 个未推送提交）
- Commits: `e653a6b`, `3f077cd`, `165a879`
- Result: `CHANGES_REQUESTED` — 2 个 P2、2 个 P3

## Active Findings

### P2-1 `CLAUDE.md` 把人工规则误标为自动门禁

- Status: `OPEN`
- Evidence:
  - `CLAUDE.md:3` 声称本节“每条都有命令/门禁可验证，不过脑也能拦住”，但 `CLAUDE.md:7-9` 的低价值测试、文档数字、接口实现完整性都没有自动门禁，只是人工规则或建议命令。
  - `CLAUDE.md:6` 声称 detekt 的 `TooGenericExceptionCaught` 会拦截，但 `detekt-config.yml:40-41` 明确配置为 `active: false`。
  - 本轮 `quickCheck detekt` 全部通过，证明这些声明当前不能被门禁兑现。
- Impact: AI 或开发者会信任不存在的保护，跳过人工审查；规则文档自身违背“证据必须对应真实入口”的原则。
- Required fix: 将无法自动拦截的条目移到 lessons-learned；或实现对应门禁后再保留。删除或改正 `TooGenericExceptionCaught` 的错误声明。
- Verify: 对每条留在“自动门禁”章节的规则，列出实际 task/规则实现和一个能被 `checkQuickCheck` 捕获的坏 fixture。

### P2-2 迁移写回测试用文本正则判断 JSON 层级，可能误判正确结果

- Status: `OPEN`
- Evidence: `PresetLoaderTest.kt:334-360` 用 `content.contains(Regex(""",\s*"pull"\s*:"""))` 判断顶层 legacy `pull` 已删除。该正则不理解 JSON 层级；若合法的 `overrides.pull` 排在另一个 override 字段后，也会匹配并错误失败。
- Impact: 测试依赖 Gson 格式和字段顺序，既可能误报，也没有真正证明“顶层字段不存在、嵌套字段仍保留”的迁移合同。
- Required fix: 解析写回后的 JSON，逐个获取 preset object，结构化断言 preset 不含 `pull`，并断言需要保留的 `overrides.pull` 值正确。
- Verify: 使用同时包含 `overrides.dirty` 与 `overrides.pull` 的输入，写回后结构化断言通过；临时保留顶层 `pull` 时测试失败。

### P3-1 `LongParameterList` suppress 仍未移除

- Status: `OPEN`
- Evidence: `PresetEditor.kt:61` 仍有 `@Suppress("LongParameterList")`；`detekt-config.yml:27` 的 `constructorThreshold` 是 12，当前构造器正好 12 个参数，不需要 suppress。
- Impact: suppress 会掩盖未来参数数目超过阈值的回归；本次提交仅修改注释，没有解决上一轮问题。
- Required fix: 删除 suppress 和对应解释注释。
- Verify: `./gradlew detekt --rerun-tasks --max-workers=2 --no-parallel` 通过。

### P3-2 空 JSON Loader 测试重复执行且注释与实际行为矛盾

- Status: `OPEN`
- Evidence: `PresetLoaderTest.kt:385-399` 先通过 `writePresetFile("{}")` 写入并调用一次 `load`，但结果 `result` 未使用；随后又写入同一文件并第二次调用 `load`。注释声称需要绕过 `ensureFile`，但第一次写入已经保证文件存在。
- Impact: 测试包含无意义的额外迁移/写盘流程，注释会误导后续维护者理解 Loader 行为。
- Required fix: 保留一次 `writePresetFile("{}")`、一次 `load` 和对应断言，删除无用变量及错误注释。
- Verify: 精简后的目标测试通过，临时破坏 `{}` 的 null-safe 转换时测试失败。

## Residual Test Gaps

- 快捷键 Force 确认入口、PresetEditor 折叠/指示器/刷新保留、窄 Tool Window 布局仍缺自动化证据；若本轮不处理，应明确标记 `ACCEPTED` 和理由。
- 测试 helper 中仍保留较多 `executeTest(preset, options)` 旧调用形状，类型守卫对测试调用链的约束弱于生产入口。

## Verified Summary

- `BranchSwitcherServiceTest` 已通过真实 `service.resolveSwitchRequest()` 验证全局选项映射和 preset override 合并。
- legacy pull + explicit override 已验证会触发写回；legacy false/true、dirty-only 合并、null item、`{}`、`presets:null` 均已有 Loader 入口测试。
- 低价值 `PresetOverrides` data-class equality 测试已删除。
- 本轮未修改生产行为；功能相关目标测试全部通过。

## Validation

- `git diff --check origin/main..HEAD`: PASS
- `./gradlew quickCheck detekt compileKotlin compileTestKotlin --rerun-tasks --max-workers=2 --no-parallel`: PASS
- `PresetLoaderTest + BranchSwitcherServiceTest + PresetConfigTest --rerun-tasks --max-workers=1 --no-parallel`: PASS
- Existing warnings: IntelliJ Platform Gradle Plugin 版本过旧、Java source compatibility 17 与 Rider 2026.1.1 要求的 21 不一致；非本轮引入。
