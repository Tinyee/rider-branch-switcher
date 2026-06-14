# CLAUDE.md

## 不准 — 每条都来自本项目的实际过失

- **没跑 `./gradlew quickCheck detekt` 不准说没问题。** derive 功能 7 轮审查，每轮都有 grep 能发现的低级错误。
- **在 A 处修了 bug 不准不扫 B/C/D 处。** 缺 try/catch、缺 cancel 检查、缺 operation 生命周期——同一个 bug 在 3+ 个文件里各犯了一遍。
- **多阶段操作不准先写代码后排状态矩阵。** 7 轮审查因为状态是边做边发现的，不是提前列出来的。
- **不准静默吞异常，按类型处理**：`CancellationException` 必须重新抛出。安全探针异常 → 转为 Unknown/Error 由调用方处理。best-effort UI 清理 → 可以忽略但必须注释原因。其他 → 至少 `LOG.warn`。
- **声称完成前不准用 Gradle 缓存声称验证通过。** 开发中增量 OK。声称"没问题"时至少跑一次 `--rerun-tasks`。
- **不准为了让测试绿而放松安全检查。** tri-state probe 初期设计成 fail-open 就为了让测试过。
- **不准写只验证 data class 字段或语言特性的测试。** 已清理 6 个（HistoryTest 全文件 + PresetJsonTest 3 个），生产代码坏了它们照样绿。
- **取消操作不准跳过回滚。** 取消了也得在新 operation 里把已改的仓库恢复。
- **不准只修实例不修模式。** 同一类 bug 被审查抓了 5 轮。
- **审查指出的验证缺口不准加注释承认就当修完。** 必须补一个会因该缺口而失败的测试。checkQuickCheck 被指出"无法区分规则命中与基础设施失败"，我只加了一句注释承认限制；结果 `gradlew.bat` 路径错误导致全假阳性，quickCheck 一次都没跑到。
- **不准凭感觉改文档数字。** 用可复现的 `rg -n`/测试输出枚举证据，并区分匹配行、调用点、机械迁移和语义重写。
- **不准顺手改和当前任务无关的代码。** "来都来了"导致 scope 失控。
- **不准不经同意删除/重写已有测试。**
- **接口加方法不准不更新所有实现。** `GitClient` 加方法漏了 test fake 发生过多次。改接口前 `grep -rl "INTERFACE_NAME" src/`。
- **新增写路径不准缺生命周期。** 每个异步写入口（action、快捷键、菜单项）必须走：门禁检查 → 开始操作 → 可取消任务 → 结束操作 → finally 释放。缺一环 = cancel 静默失效。

## 新功能开发：先设计再写代码

被要求实现新功能时，第一步不是写代码，是先回复：

1. 状态矩阵（成功/阻塞/错误/取消/清理失败，每个阶段填满）
2. 三份合同（行为/失败/取消）
3. 影响的文件和调用链

用户确认后再动手。derive 功能 7 轮审查的原因是边写边发现状态。

**设计文档写完后，交出去被审查前，先做设计者自检：**

完整清单使用 `docs/templates/design-review-checklist.md`。不能凭下面的摘要直接宣告可开工。

- **设计里提到的每个 API/函数/类名，用 `rg` + 源码读取确认实际行为再写进伪代码。** `DirtyAction.valueOf()` 会抛异常、`buildSummary()` 返回的是单个 JLabel 不是 Panel——全是凭印象写伪代码没查源码。
- **数据模型加字段后，用 `rg -n` 列全所有"读"和"写"调用点。** DTO 转换漏了 `toPreset()` 和 `parsePresetImport()`、UI 只设计了 `buildOverrides()` 忘了 `applyOverridesToUI()`——都是只想了主路径没系统枚举消费点。
- **有迁移逻辑时，先答"迁移后旧 JSON 文件长什么样"。** `pull: false` 迁移到 `overrides.pull: false` 后顶层 `pull` 字段还在不在？`pull: true` 要不要也写回以删除旧字段？没想透这个导致 needsPullMigration 条件写窄了。
- **引用项目已有基础设施时先 Read 它的实现。** quickCheck 是什么机制（Kotlin task + fixture 自测）没读过就写了 bash 方案。
- **测试计划分两层：纯逻辑层（无 IDE 运行时）+ 入口接线层（Loader/import/SwitchController 实际调用）。** 纯函数测试全绿 ≠ import 路径正确带入 overrides、≠ Loader 正确触发写回。
- **区分增量复审和最终复审。** 增量复审只确认上一轮问题，不能宣告 `PASS`；最终复审必须从零执行完整模板，检查修复产生的二阶影响。
- **交审前自己从零通读一遍设计文档。** 不能只看 diff——R5 智能引号、R9 `setItemAt` 参数顺序、R9 硬编码字符串，全是上一轮改动引入的新 bug，增量复审抓不到。
- **伪代码统一分类为 `COMPILE_SHAPED` 或 `ILLUSTRATIVE_ONLY`。** 计划直接照抄实现的代码块必须在设计中标注；最终复审证据必须分类所有用于证明正确性的代码块。前者逐项对照真实 receiver、符号、参数顺序、返回类型、可见性和 nullability；后者不能证明入口接线正确。
- **影响范围先用 `rg -n` 枚举并区分机械迁移与语义重写。** 匹配行数不等于改动量；per-preset 曾把 36 处引用和语义重写估成 ~5 行。
- **外部输入必须检查缺失、显式 null、空集合、null item、非法值和写回失败。** 只把叶子 DTO 设为 nullable 不代表顶层容器安全。

## 提交前

1. `./gradlew quickCheck detekt`（git pre-commit hook 也会自动跑）
2. 确认每个 `TaskBridge.runBackground` 有 `beginOperation`/`onCancel`/`onFinished`/`endOperation`
3. 确认每个 `tryStartWrite()` 有 `endWrite()` in finally
4. 新功能：状态矩阵已填，所有格非空

## 怎么干活

- **一次只改一件事。** 别在同一个步骤里修 bug + 重构 + 改文档。
- **先验证再动手。** 别假设文件长什么样——读它。别假设 bug 存在——先复现。
- **看不懂的代码别删。** 如果某个安全检查、模式、守卫存在，默认它是踩过坑才加的。
- **坏主意要拒绝。** 如果建议会破坏安全、跳过清理、增加未测试复杂度，直接说出来。当应声虫导致了 7 轮审查。
- **想破例先问。** 问一句的成本 < 改错的成本。
- **审查修完一轮再修下一轮前，重读涉及的源代码。** 不能因为 R1 时读过就凭记忆在 R2 接着改。两轮之间文件已经被自己改过，记忆里的调用链、返回类型、已有基础设施都可能是过期的。per-preset 设计 R2 犯了 4 个这类错误——quickCheck 框架没读过就写 bash、`buildSummary()` 实际结构没重读就写伪代码、`needsMigration` 触发条件没追踪调用路径就写死 `pull==false`、漏 import/Loader 端到端测试因为只看纯函数层。

## 共享审查流程

统一使用 `docs/ai-review-current.md` 在 Codex、Claude 和用户之间传递审查问题，避免复制粘贴。

- 收到“审查并写共享文档”时：审查当前改动，按模板覆盖写入本轮问题。每项必须包含状态、优先级、证据、影响、建议修复和验证方式。
- 收到“处理共享审查问题”时：先读取共享文档，逐项修复 `OPEN` / `IN_PROGRESS` 项。修复后记录实际修改和验证命令，标记为 `FIXED_PENDING_REVIEW`。
- 收到“复审共享文档”时：重新核对代码与测试，不得仅相信状态文字。确认修复后标记 `VERIFIED`；未修复则改回 `OPEN` 并说明原因。
- 收到“最终复审”或询问“可以开工了吗”时：从零执行 `docs/templates/design-review-checklist.md`，不得只核对共享文档里的活跃问题。
- 只有最终复审完成且不存在 `OPEN`、`IN_PROGRESS`、`FIXED_PENDING_REVIEW` 时，才能写 `Result: PASS — ready to implement`。
- `PASS` 必须在共享文档记录 `git hash-object <design-file>`；设计文档之后有任何修改，旧 `PASS` 自动失效，必须重新最终复审。
- 正式开工前重新读取设计列出的受影响入口；如果最终复审后相关源码发生变化，先复核变化对设计的影响再编码。
- 最终复审每项必须有源码/命令证据或明确的 `N/A` 理由；不要把完整模板复制到共享文档，避免超过 100 行。
- 不修复的非阻塞建议标记为 `ACCEPTED`，必须写清接受风险的理由。
- 不得只改文档状态而不改代码或不运行验证。
- 共享文档只详细保留 `OPEN`、`IN_PROGRESS`、`FIXED_PENDING_REVIEW`、`ACCEPTED`。复审结束后将 `VERIFIED` 详情压缩为一行摘要；超过 100 行时必须立即压缩。
- 仅 P0/P1、跨模块设计决策或用户明确要求时，才归档完整内容到 `docs/reviews/ai-review-YYYY-MM-DD.md`；普通 UI/P3 问题不归档。
- 新的独立审查轮次覆盖当前文档的摘要和活跃问题。

## 测试资源与低负载规则

- 开发迭代先运行 `./gradlew quickCheck detekt`，再运行最相关的测试类或方法，不默认跑全量。
- 完整 `test`、真实 Git 集成测试、`buildPlugin`、`verifyPlugin`、`releaseCheck` 属于重型任务，禁止并行启动，即使工具支持并行调用。
- 广泛本地验证默认加 `--max-workers=2 --no-parallel`；用户反馈发热、风扇噪音或机器受限时改为 `--max-workers=1 --no-parallel`。
- 不得为了降温而减少 Kotest 全局迭代次数或跳过测试；应选择目标测试或限流。
- 声称完成前按改动范围运行相关测试的 `--rerun-tasks`；发布/推送前才运行 `releaseCheck`，并在启动前告知用户其高负载。
- 仅在用户希望释放资源或会话结束时运行 `./gradlew --stop`。

```bash
# 轻量
./gradlew quickCheck
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel

# 广泛 / 最终 / 发布
./gradlew test detekt --max-workers=2 --no-parallel
./gradlew test detekt --rerun-tasks --max-workers=2 --no-parallel
./gradlew releaseCheck
```

## Skill

IntelliJ SDK 模式、Gradle 配置、线程模型、代码模板：`/intellij-plugin-dev`
