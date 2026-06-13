# 测试审查与后续建议（2026-06-13）

## 当前判断

当前测试对切换流水线、步骤行为、预设 JSON 和文件读写已有较好覆盖。
下一阶段不应继续单纯追求测试数量，而应优先覆盖跨组件、异步生命周期和真实进程行为。

本次审查重点：

- 优先补充真实取消链路和异步生命周期测试。
- 将只验证 Kotlin data class、集合或简单 lambda 的测试替换为业务行为测试。
- 保留真实 Git 临时仓库集成测试，但避免增加依赖网络或固定时间等待的脆弱测试。

## 建议补充的测试

### P0：取消正在执行的真实 Git 进程

当前 `GitOpsTest` 已覆盖取消后拒绝启动后续命令，以及嵌套操作生命周期，
但尚未覆盖命令运行期间触发取消的行为。

建议验证：

- 长时间运行的进程会在取消后被终止。
- 取消结果为 `cancelled`，而不是等待到 `timeout`。
- 取消后不会残留子进程。
- 操作结束后，后续 Git 命令能够正常执行。

建议先为 `GitOps` 抽取可注入的进程启动或执行边界，再使用可控假进程测试，
避免依赖真实慢速网络和固定时间等待。

### P0：真正验证 SwitchExecutor 取消流水线

当前以下测试只直接读取 `cancelled()` lambda，并没有执行流水线：

- `SwitchExecutorTest.cancel stops pipeline`
- `SwitchIntegrationTest.cancelled context reports true when cancelled flag is set`

建议使用两个可控 `SwitchStep`：

1. 第一个 Step 执行后将 indicator 标记为取消。
2. 断言第二个 Step 没有执行。
3. 断言执行结果为失败。
4. 断言 `git.cancel()` 被调用。

### P0：TaskBridge 生命周期

`TaskBridge.runBackground` 负责连接 IntelliJ Task 和协程，也是 Git 操作生命周期清理的关键边界。

建议验证：

- 正常完成时 `onFinished` 只调用一次。
- 用户取消时调用 `onCancel`，最终仍调用 `onFinished`。
- `block` 抛异常时仍调用 `onFinished`，异常传回调用方。
- 父协程取消时，运行中的 `ProgressIndicator` 被取消。
- 即使任务在进入 `block` 前取消，`beginOperation` / `endOperation` 仍能配对。

这组测试需要 IntelliJ 测试环境或对 Task 调度边界进行小幅抽象，不建议用 sleep 模拟。

### P1：Preset ID 迁移边界

当前已覆盖旧预设生成 ID、写回，以及写回失败不影响加载。

建议继续补充：

- 新旧 preset 混合时，仅为缺少 ID 的项生成 ID。
- 所有 preset 已有 ID 时，不触发迁移保存。
- 迁移保存失败后，返回的内存对象仍包含生成的 ID。
- 多个缺少 ID 的 preset 会生成互不相同的 ID。

### P1：BranchCombo 异步加载

建议为 `loadComboBranches` 补充：

- 加载异常后，combo 恢复 enabled 状态并调用 `onLoadEnd`。
- 组件已经销毁时，不再修改 UI。
- `"loading..."` 不会成为可保存的分支值。
- 当前分支不在 Git 返回列表时，会被补入并选中。
- Git 返回重复分支时，最终列表行为明确。

如直接测试 `ApplicationManager.invokeLater` 成本较高，可先将“加载结果合并”提取为纯函数测试。

### P2：真实仓库回滚失败场景

建议补充：

- 原分支恢复失败时会回退到 checkpoint SHA。
- 分支和 SHA 都恢复失败时，rollback 返回失败。
- 原状态为 detached HEAD 时，能够恢复到原 SHA。
- 某个子模块回滚失败时，其他仓库仍继续回滚。

### P2：大仓性能基准

为 50+ 子模块建立可重复性能测试或基准脚本，关注：

- Preflight 执行的 Git 命令数量。
- 状态刷新是否重复查询相同仓库。
- 多 preset 展开和刷新耗时。

性能测试不建议加入普通 `test` 任务，可作为独立 Gradle task 或手工基准脚本运行。

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
- 两个取消测试都只读取 `cancelled()` lambda，应合并并替换为真实流水线取消测试。

## 推荐执行顺序

1. 用真实流水线取消测试替换两个无效取消测试。
2. 补 GitOps 运行中取消测试。
3. 补 TaskBridge 生命周期测试。
4. 补 Preset ID 迁移边界测试。
5. 提取 BranchCombo 纯逻辑并补异步结果测试。
6. 删除或合并低价值、重复测试。
7. 最后再评估是否需要引入 PITest 变异测试。

## 验收标准

- 取消测试真正执行流水线或进程，而不是只读取标志位。
- 每个异步入口都覆盖成功、失败、取消和清理路径。
- 关键迁移失败不会阻断正常读取。
- 删除低价值测试后，关键业务分支覆盖不下降。
- 普通测试不依赖外网、不使用长时间 sleep，且可在 Windows、Linux、macOS CI 稳定运行。
