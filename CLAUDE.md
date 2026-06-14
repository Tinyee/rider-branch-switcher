# CLAUDE.md

## 不准 — 每条都来自本项目的实际过失

- **没跑 `./gradlew quickCheck` 不准说没问题。** derive 功能 7 轮审查，每轮都有 grep 能发现的低级错误。
- **在 A 处修了 bug 不准不扫 B/C/D 处。** 缺 try/catch、缺 cancel 检查、缺 operation 生命周期——同一个 bug 在 3+ 个文件里各犯了一遍。
- **多阶段操作不准先写代码后排状态矩阵。** 7 轮审查因为状态是边做边发现的，不是提前列出来的。
- **不准静默吞异常，按类型处理**：`CancellationException` 必须重新抛出。安全探针异常 → 转为 Unknown/Error 由调用方处理。best-effort UI 清理 → 可以忽略但必须注释原因。其他 → 至少 `LOG.warn`。
- **声称完成前不准用 Gradle 缓存声称验证通过。** 开发中增量 OK。声称"没问题"时至少跑一次 `--rerun-tasks`。
- **不准为了让测试绿而放松安全检查。** tri-state probe 初期设计成 fail-open 就为了让测试过。
- **不准写只验证 data class 字段或语言特性的测试。** 已清理 6 个（HistoryTest 全文件 + PresetJsonTest 3 个），生产代码坏了它们照样绿。
- **取消操作不准跳过回滚。** 取消了也得在新 operation 里把已改的仓库恢复。
- **不准只修实例不修模式。** 同一类 bug 被审查抓了 5 轮。
- **审查指出的验证缺口不准加注释承认就当修完。** 必须补一个会因该缺口而失败的测试。checkQuickCheck 被指出"无法区分规则命中与基础设施失败"，我只加了一句注释承认限制；结果 `gradlew.bat` 路径错误导致全假阳性，quickCheck 一次都没跑到。
- **不准手动改文档数字。** 先 `grep -c "@Test"` 再改。
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

## 提交前

1. `./gradlew quickCheck`（git pre-commit hook 也会自动跑）
2. 确认每个 `TaskBridge.runBackground` 有 `beginOperation`/`onCancel`/`onFinished`/`endOperation`
3. 确认每个 `tryStartWrite()` 有 `endWrite()` in finally
4. 新功能：状态矩阵已填，所有格非空

## 怎么干活

- **一次只改一件事。** 别在同一个步骤里修 bug + 重构 + 改文档。
- **先验证再动手。** 别假设文件长什么样——读它。别假设 bug 存在——先复现。
- **看不懂的代码别删。** 如果某个安全检查、模式、守卫存在，默认它是踩过坑才加的。
- **坏主意要拒绝。** 如果建议会破坏安全、跳过清理、增加未测试复杂度，直接说出来。当应声虫导致了 7 轮审查。
- **想破例先问。** 问一句的成本 < 改错的成本。

## 测试资源与低负载规则

- 开发迭代先运行 `./gradlew quickCheck`，再运行最相关的测试类或方法，不默认跑全量。
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
