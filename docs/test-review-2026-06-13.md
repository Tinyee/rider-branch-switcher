# 测试审查与后续建议（2026-06-13）

## Current Cleanup Status

- 2026-06-19: Removed 11 low-value structure tests.
- Deleted `SubmoduleRowManagerTest` (5 tests: `SubRow` data-class checks plus duplicated `shortLabel` checks).
- Removed `SwitchStepTest` StepResult/SwitchContext structure checks (4 tests).
- Removed `SwitchExecutorTest` CheckpointEntry data-class checks (2 tests).
- Current documented test count after cleanup: 287 tests / 21 classes total (78 core pure JVM via `:core:test`, 209 platform/integration via `test`).
- The original findings below are historical review notes; items listed there may already be resolved.

## 当前判断

当前测试对切换流水线、步骤行为、预设 JSON 和文件读写已有较好覆盖。
下一阶段不应继续单纯追求测试数量，而应优先覆盖跨组件、异步生命周期和真实进程行为。

本次审查重点：

- 优先补充真实取消链路和异步生命周期测试。
- 将只验证 Kotlin data class、集合或简单 lambda 的测试替换为业务行为测试。
- 保留真实 Git 临时仓库集成测试，但避免增加依赖网络或固定时间等待的脆弱测试。

## 建议补充的测试

### 已完成：取消正在执行的 Git 进程

`GitOpsTest.cancel terminates running process and allows commands after operation ends` 使用可控假进程
覆盖运行中取消、强制终止、返回 `cancelled`，以及操作结束后继续执行命令。测试不依赖外网或固定 sleep。

### 已完成：真正验证 SwitchExecutor 取消流水线

`SwitchExecutorTest.cancel after one step stops remaining pipeline and signals git` 已使用两个可控
`SwitchStep` 执行真实流水线，并验证第二步不执行、结果失败、`git.cancel()` 被调用。

### ✅ P0：TaskBridge 生命周期 — 已完成 (2026-06-13)

`TaskBridgeLifecycleTest`（9 用例）已覆盖全部建议场景：
- 正常完成、block 抛异常、用户取消、父协程取消（Task 启动前/运行中）、回调幂等。
- 新增防御性覆盖：runner 同步抛异常传播、onCancel/onFinished 回调异常记录日志不传播。
- 通过可注入 `TaskRunner` 接口实现无 IntelliJ 运行时测试。

### 已完成：Preset ID 迁移边界

`PresetLoaderTest` 已覆盖缺失、空白和重复 ID 的规范化与写回、有效 ID 不触发保存，
以及迁移保存失败不阻断加载。规范化逻辑保证返回的内存对象始终使用非空且唯一的 ID。

### 已完成：BranchCombo 异步加载

`BranchComboUtilTest` 已覆盖成功、异常、组件销毁、占位符过滤、当前分支补入和重复分支。
组件销毁路径现在也保证调用 `onLoadEnd`，避免外层 loading 计数卡住。

### 已完成：真实仓库回滚失败场景

已覆盖原分支恢复失败时回退 checkpoint SHA、分支和 SHA 都失败时返回失败，
原状态为 detached HEAD 时恢复原 SHA，以及某个子模块回滚失败时其他仓库继续回滚。

### 部分完成：大仓规模调用预算与性能基准

`LargeRepoScalabilityTest` 已在 50 个子模块场景下验证 Switch pipeline 和 Preflight
对每个仓库的 Git 调用次数，防止重复调用造成线性倍增。

仍建议通过独立 Gradle task 或手工基准脚本测量：

- 真实 Git CLI 下的 Preflight 耗时。
- 多 preset 展开和状态刷新耗时。
- 不同机器与 Rider 版本下的性能变化。

普通 `test` 不包含墙钟阈值，避免因 CI 和开发机性能差异产生不稳定失败。

## 建议删除或替换的低价值测试

以下测试大多只验证 Kotlin、Swing 或集合自身行为，对业务回归保护较弱。
建议在补入对应高价值行为测试后删除或替换，而不是只为减少测试数量直接删除。

### 可优先替换

- `SubmoduleRowManagerTest.SubRow stores all fields correctly`
  - 仅验证构造参数能保存到字段。
- `SubmoduleRowManagerTest.SubRow targetBranch defaults to empty`
  - 仅验证简单默认值。
- `SubmoduleRowManagerTest.SubRow deleted flag can be toggled`
  - 仅验证 `var` 可以赋值。
- `HistoryTest.history entry stores name id and timestamp`
  - 仅验证 data class 字段。
- `HistoryTest.history entry with null id is backward compatible`
  - 没有经过真实持久化或历史查找逻辑。
- `HistoryTest.history ordering is newest first`
  - 仅验证手工构造的 List 顺序，没有调用 `addHistory`。
- `SwitchExecutorTest.checkpoint entry stores sha and branch`
  - 仅验证 data class 字段。
- `SwitchExecutorTest.checkpoint entry with null branch`
  - 仅验证可空字段。
- `PropertyTest.GitResult.ok matches exitCode == 0`
  - 使用属性测试验证一个简单布尔表达式，收益很低。
- `PresetJsonTest.rename preset preserves other fields`
  - 主要验证 Kotlin data class `copy()`。
- `PresetJsonTest.imported preset gets new id`
  - 测试中自行生成新 UUID，再断言两个 UUID 不同，没有覆盖导入实现。
- `PresetJsonTest.preset copy is immutable on original`
  - 主要验证 data class `copy()`。
- `SwitchStepTest.Fatal result should abort pipeline`
  - 当前只验证类型关系，没有实际运行 pipeline。
- `SwitchStepTest.Partial result marks non-success but continues`
  - 当前只验证 `Partial` 能保存 map，没有验证 pipeline 继续执行。
- `SwitchStepTest.Partial with empty failures is still Partial`
  - 仅验证类型构造。
- `SwitchStepTest.switch context stores all fields`
  - 主要验证 data class 字段。
- `SwitchStepTest.switch context stashed paths works`
  - 主要验证 MutableMap 行为。

### 应合并的重复测试

- `GitOpsTest` 中 `GitResult ok is true/false` 与
  `PropertyTest.GitResult.ok matches exitCode == 0` 重复。
- `PresetJsonTest`、`PropertyTest` 和 `PresetLoaderTest` 都包含 JSON round-trip。
  建议保留：
  - 一个属性测试验证序列化不变量。
  - `PresetLoaderTest` 验证真实文件与迁移行为。
  - 删除只覆盖简单示例、且没有额外契约的重复 round-trip。
- `SubmoduleRowManagerTest` 与 `SwitchExecutorTest` 都测试了 `shortLabel`。
  建议集中到单一 utility 测试类。
- `SwitchIntegrationTest.cancelled context reports true when cancelled flag is set` 仍只验证 lambda，
  可在补充更完整的跨组件取消测试后删除。

## 推荐执行顺序

1. ~~补 TaskBridge 生命周期测试。~~ ✅ 已完成
2. 删除或合并低价值、重复测试。
3. ~~最后再评估是否需要引入 PITest 变异测试。~~ ✅ 已完成

## 验收标准

- 取消测试真正执行流水线或进程，而不是只读取标志位。
- 每个异步入口都覆盖成功、失败、取消和清理路径。
- 关键迁移失败不会阻断正常读取。
- 删除低价值测试后，关键业务分支覆盖不下降。
- 普通测试不依赖外网、不使用长时间 sleep，且可在 Windows、Linux、macOS CI 稳定运行。

## PITest 评估结果（2026-06-19）

已接入 `./gradlew pitestCore` 作为手动诊断任务，不进入普通 `test`、`releaseCheck` 或 git hooks。

- 插件：`info.solidsoft.pitest` 1.19.0。
- 目标：SettingsRules、BranchNameRules、DeriveNotification、PresetImportRules、UiRules。
- 运行策略：单线程、JUnit 4 原生路径；不启用 JUnit 5 PIT 插件，避免 IntelliJ Platform classpath 下的 Vintage/Kotest 扫描开销。
- 首次宽范围尝试会超过 5 分钟，并误扫 Swing `BranchSwitcherConfigurable`，因此已收窄为纯规则/决策逻辑。
- 当前结果：80 mutations / 79 killed / 99%，0 no-coverage；剩余 1 个 `BranchNameRules` Kotlin lambda 等价噪音。
- 实际收益：发现并推动补强 branch name 边界样例、DeriveNotification 字段断言，并简化了 `BranchNameRules` 冗余组件检查。
