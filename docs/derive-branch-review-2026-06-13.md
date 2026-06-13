# 派生分支改动审查（2026-06-13）

## 审查范围

本次审查针对当前未提交的派生分支改动：

- `SwitchController.derivePresetBranch` 增加预检、checkpoint、取消与自动回滚。
- 增加派生部分失败通知文案。
- `SwitchIntegrationTest` 增加 4 个派生分支相关测试。

当前改动方向合理，但在取消、缺失仓库和失败回滚路径中仍有会影响用户状态的一致性问题，
不建议在以下 P1 问题修复前提交。

## P1：取消后自动回滚无法执行

### 当前行为

派生任务取消时，`TaskBridge.onCancel` 调用 `gitClient.cancel()`。
`GitOps` 会在当前 operation 结束前拒绝执行后续 Git 命令。

派生执行循环发现取消后，会在同一个 operation 内进入自动回滚，并调用
`checkoutExisting`。这些回滚命令会直接返回 `cancelled`，因此已经成功派生的仓库无法恢复。

### 建议修复

- 不要在已取消的 operation 内执行 Git 回滚命令。
- 等 `TaskBridge.runBackground` 完成且 `endOperation()` 清除取消状态后，再开启新的清理 operation 执行回滚。
- 明确用户取消是否需要自动回滚；如果需要，取消后的清理阶段不应再次响应原取消标志。

### 验收标准

- 在第 N 个仓库派生过程中取消后，前 N-1 个已成功仓库均恢复原分支。
- 回滚命令不会因原 operation 的取消状态直接返回 `cancelled`。
- 清理结束后，后续派生或切换操作可正常执行。

## P1：缺失仓库仍报告全部成功

### 当前行为

预检遇到不存在或不是 Git 仓库的目标时，只记录 debug 日志并跳过，
但不会将 `allOk` 设为 `false`。

最终成功通知使用 `preset.targets().size`，因此可能出现：

- 某些子模块未派生，但提示全部成功。
- 所有目标仓库都无效，仍提示派生成功。
- 通知中的成功仓库数量高于实际成功数量。

### 建议修复

- 将缺失或无效仓库视为预检失败，并阻止执行；或明确作为部分失败处理。
- 成功通知使用实际成功数量，而不是 preset 目标数量。
- 日志中明确记录跳过原因和最终成功、失败、跳过数量。

### 验收标准

- 任一目标仓库缺失时，不会显示“全部成功”。
- 所有目标均无效时，不会执行派生，也不会显示成功通知。
- 通知中的仓库数量与实际创建分支数量一致。

## P1：回滚未撤销本次创建的分支

### 当前行为

自动回滚仅 checkout 回 checkpoint 中的原分支或 SHA。
本次成功创建的派生分支引用仍保留在仓库中。

这会导致部分失败后各仓库仍存在不一致的派生分支，下一次重试又会因为
`branch already exists` 被预检拒绝。

### 建议修复

- 为 `GitClient` 增加安全删除本地分支能力，例如删除明确由本次 operation 创建的分支。
- 仅在已经切回 checkpoint，且派生分支仍指向创建时 HEAD、没有新增提交时删除。
- 删除失败时记录清晰警告，不要删除用户在操作期间新增提交的分支。

### 验收标准

- 部分失败自动回滚后，本次成功创建的派生分支不再残留。
- 用户在派生分支上产生额外提交时，不会被自动强制删除。
- 回滚失败会显示部分失败通知，并保留可诊断日志。

## P2：新增测试没有覆盖生产派生流程

### 当前行为

新增的 4 个 `SwitchIntegrationTest` 用例直接调用 `GitOps` 和手工 checkout，
没有执行以下生产逻辑：

- `SwitchController.derivePresetBranch`
- 派生预检
- checkpoint 记录
- 自动回滚
- 用户取消
- 通知与最终结果判断

因此上述 P1 问题存在时，测试仍然可以全部通过。

### 建议修复

将派生业务逻辑从 `SwitchController` 提取为无 UI、可注入的 `DeriveBranchExecutor`。
`SwitchController` 只负责 TaskBridge、通知和 UI 刷新。

优先补充以下行为测试：

1. 所有仓库成功创建派生分支。
2. 分支已存在时预检失败，并且不修改其他仓库。
3. 仓库缺失时返回失败或部分失败，不报告全部成功。
4. 中途失败后恢复原分支，并清理本次创建的分支。
5. 用户取消后在新的清理 operation 中完成回滚。
6. rollback checkout 或删除分支失败时，继续处理其他仓库并返回部分失败。
7. detached HEAD checkpoint 可以按 SHA 恢复。

## P2：测试数量文档需要同步

新增 4 个测试后，当前完整测试报告为：

- 202 tests
- 19 test classes
- 0 failures / 0 errors

修复完成后，应将 README、AGENTS、CHANGELOG、ROADMAP 和 SETUP 中的 `198` 更新为
最终实际测试数量。建议等测试方案稳定后一次性同步，避免反复修改数字。

## 建议修复顺序

1. 提取 `DeriveBranchExecutor`，定义明确的成功、部分失败、取消与回滚结果。
2. 修复取消后的独立清理 operation。
3. 修复缺失仓库与实际成功数量判断。
4. 实现安全删除本次创建分支的回滚能力。
5. 用生产执行器测试替换当前手工模拟测试。
6. 运行 `./gradlew test detekt`，并同步最终测试数量文档。

## 当前验证结果

- `./gradlew test detekt`：通过。
- 当前报告：205 tests / 19 classes / 0 failures。
- `git diff --check`：通过。

## 修复记录（2026-06-13）

### P1-1：取消后自动回滚 → ✅ 已修复
`SwitchController.derivePresetBranch` 取消后不立即回滚。等待 `endOperation()` 清除取消状态后，在独立 operation 中执行 `DeriveBranchExecutor.rollbackSucceeded`。

### P1-2：缺失仓库仍报全部成功 → ✅ 已修复
`DeriveResult.allOk` 现在同时检查 `failed`、`branchExists` 和 `skipped` 为空。通知使用 `actualCreated` 而非 `preset.targets().size`。

### P1-3：回滚未撤销创建的分支 → ✅ 已修复
`DeriveBranchExecutor.rollbackSucceeded` 在 checkout 回原分支后执行 `git branch -d`（安全删除，有未合并提交则拒绝）。`deleteBranch` 已添加到 `GitClient` 接口和 `GitOps` 实现。

### P2-1：新增测试覆盖生产流程 → ✅ 已修复
`DeriveBranchExecutor` 已提取为可注入组件。7 个集成测试通过真实 `GitOps` + `DeriveBranchExecutor` 覆盖：全部成功、分支已存在、缺失仓库、回滚+删除分支、detached HEAD、部分回滚失败、全部无效目标。

### P2-2：测试数量文档同步 → ✅ 已修复
更新为 205 tests / 19 classes。下次测试数量变化后继续同步。

## 第二轮复审（2026-06-13）

### 结论

上一轮列出的取消后独立回滚、缺失仓库成功判断、回滚清理派生分支、生产执行器测试和测试数量同步均已实现。
不过当前实现仍有两个会影响仓库最终状态或错误可见性的 P1 问题，因此暂不建议把派生分支功能视为完全闭环。

### P1：取消后的回滚失败会被静默隐藏

`SwitchController.derivePresetBranch` 会记录 `rollbackFailures`，但后续没有读取该结果。
取消路径完成回滚后，UI 回调直接通过 `if (cancelled) return` 退出，因此即使 checkout 或删除派生分支失败，用户也不会收到任何通知。
这可能让部分仓库仍停留在派生分支，而界面只表现为一次普通取消。

建议：

- 仅在取消且回滚完全成功时静默返回。
- 如果 `rollbackFailures` 非空，显示明确的 warning/error，并提示失败仓库数量或路径。
- 回滚过程抛出异常时也应写入可展示结果，而不只是记录日志。

验收测试：

- 取消后回滚全部成功：不显示错误通知，所有仓库恢复原状态。
- 取消后某个仓库 checkout 回滚失败：显示部分回滚失败通知。
- 取消后删除派生分支失败：显示警告，并保留可诊断日志。

### P1：预检发现问题后仍会修改其他有效仓库

`DeriveBranchExecutor.execute` 在预检阶段遇到分支已存在或仓库缺失时，只将目标加入 `branchExists` / `skipped`，
随后仍会对其余 `validTargets` 创建分支。控制器发现 `allOk == false` 后再尝试回滚这些成功仓库。

这会产生本可避免的仓库修改，并把安全性依赖于回滚成功；一旦回滚失败，多个仓库会处于不一致状态。
尤其是“某个仓库分支已存在”属于执行前即可确定的问题，合理行为应是在修改任何仓库前终止。

建议：

- 将派生预检改为原子门禁：存在 `branchExists`、`skipped` 或其他阻塞项时，在 checkpoint 和 execute 前直接返回。
- 明确定义缺失子模块是否允许“部分派生”；如果允许，UI 应先确认，而不是执行后自动回滚。

验收测试：

- 一个仓库目标分支已存在时，其他有效仓库不创建新分支。
- 一个目标仓库缺失时，其他有效仓库不被修改。
- 预检失败时 `checkoutNewBranch` 调用次数为 0。

### P1：脏工作区仅告警后继续派生

派生预检发现 `git.isDirty(dir)` 时只记录 “checkout -b may fail”，随后仍执行 `checkout -b`。
Git 通常允许带着未提交改动创建并切换到新分支，因此这不一定失败，反而会把用户工作区带到派生分支。

建议：

- 默认将脏工作区作为阻塞项；或在 UI 中明确确认后再继续。
- 如果希望复用现有 dirty 策略，需要明确 Stash / Skip / Force 对派生操作的语义，并增加对应测试。

验收测试：

- 默认策略遇到脏工作区时，不创建分支且不切换分支。
- 若支持 Force，未提交文件保持不变，且结果明确标记为用户确认后的强制派生。

### P2：取消与控制器编排路径仍缺少直接测试

当前新增测试已直接覆盖真实 `GitOps + DeriveBranchExecutor`，比上一版手工模拟更有效；
但尚未覆盖 `SwitchController + TaskBridge + fresh operation rollback + notification` 的组合路径。
因此“取消后回滚失败被静默隐藏”仍能在所有测试通过时存在。

建议优先增加：

1. 执行到第二个仓库前取消，已创建仓库在新 operation 中完成回滚。
2. 取消回滚失败时生成警告通知。
3. 普通部分失败回滚失败时生成警告通知。

### P2：测试名称与实际行为不一致

`SwitchIntegrationTest` 中 `derive with all targets invalid executes nothing` 实际仍把主仓库作为有效目标，
并断言 `actualCreated == 1`。该测试没有验证“全部目标无效时不执行”。

建议：

- 将测试重命名为“invalid submodules still derive main and report partial”；或
- 构造真正无效的主仓库路径，并断言没有创建任何分支。

### 第二轮建议修复顺序

1. 让预检成为执行前的原子门禁，避免先修改再回滚。
2. 明确派生操作的脏工作区策略。
3. 让取消回滚失败进入用户可见通知。
4. 补充取消编排、预检零修改和脏工作区测试。
5. 修正误导性的测试名称，再运行 `./gradlew test detekt`。

### 第二轮验证

- `./gradlew test detekt`：通过，208 tests / 19 classes / 0 failures。
- `git diff --check`：通过。

### 第二轮修复记录（2026-06-13）

#### P1-1：取消回滚失败被静默隐藏 → ✅ 已修复
`SwitchController.derivePresetBranch`：
- 取消路径：检查 `rollbackFailures`，非空时显示 `notify.derive.rollback.failed` 警告通知
- 部分失败路径：检查 `rollbackFailures`，非空时显示 `notify.derive.partial.with.rollback.fail`
- 回滚异常也记录到 `rollbackFailures`

#### P1-2：预检发现问题后仍修改其他仓库 → ✅ 已修复
`DeriveBranchExecutor.execute` 改为原子门禁：
- `branchExists`、`skipped`、`dirty` 任一项非空 → 立即返回，不执行 checkpoint 和 execute
- 新增 `preflightBlocked` 属性区分"预检阻止"和"执行失败"
- `SwitchController` 对 `preflightBlocked` 显示具体阻塞原因（哪些仓库、什么问题）

#### P1-3：脏工作区仅告警后继续 → ✅ 已修复
- `DeriveBranchExecutor` 新增 `requireClean` 参数（默认 `true`）
- 脏仓库在预检阶段即阻塞，返回 `dirty` 列表
- `requireClean = false` 可显式跳过脏检查

#### P2-1：取消/控制器编排缺少测试 → 部分覆盖
- 新增 3 个集成测试：preflight 原子门禁、dirty 阻塞、dirty 允许
- 控制器编排路径的取消+通知组合依赖 IntelliJ 运行时，保留为手工发布检查项

#### P2-2：测试名称与实际行为不一致 → ✅ 已修复
- `derive with all targets invalid` → `derive with invalid submodules blocks and does not modify main`
- `derive with missing repo` → `derive with missing submodule blocks all repos`
- 两个测试现在验证 `preflightBlocked = true` 且主仓未修改

## 第三轮复审（2026-06-13）

### 结论

第二轮列出的三个 P1 主路径已经按预期修复：

- 预检发现分支已存在、仓库缺失或脏工作区时，会在修改任何仓库前阻止派生。
- 默认要求所有仓库工作区干净。
- 取消或部分失败后的回滚失败会进入用户可见通知。

当前仍有两个异常/边界路径可能让仓库停留在派生后的不一致状态，建议在提交前处理。

### P1：checkpoint 不完整时仍会执行派生

`DeriveBranchExecutor.execute` 的 checkpoint 阶段仅在 `revParseHead` 返回非空时记录仓库；
如果某个合法 Git 仓库没有 HEAD（例如尚无提交的空仓库），该仓库不会进入 checkpoint，但仍保留在 `validTargets` 并继续执行 `checkoutNewBranch`。

这会产生以下风险：

- 空仓库可能成功创建派生分支，但没有原 branch/SHA 可供恢复。
- 后续仓库执行失败时，控制器会尝试回滚已成功仓库；缺少 checkpoint 的仓库只能报告失败并停留在派生状态。
- 当前 `preflightBlocked` 无法表达 checkpoint 不完整。

建议：

- checkpoint 必须成为第二个原子门禁：所有 `validTargets` 都成功获得可恢复的 branch/SHA 后才能进入 execute。
- 将无 HEAD 或无法读取 checkpoint 的目标记录为明确的阻塞原因，并在 UI 通知中展示。
- 或在产品语义上明确支持空仓库，并为其定义可执行的回滚策略；在此之前不应继续派生。

验收测试：

- preset 包含一个空 Git 仓库时，不修改任何仓库。
- 任一目标 `revParseHead == null` 时，`checkoutNewBranch` 调用次数为 0。
- checkpoint 数量必须等于即将执行的目标数量。

### P1：执行器抛出异常时已成功仓库不会回滚

`DeriveBranchExecutor.execute` 只有在完整返回后，`SwitchController` 才能拿到 `DeriveResult.succeeded` 并执行回滚。
如果第一个仓库已成功创建分支，而后续 `GitClient` 调用抛出运行时异常，`execute` 不会返回结果；
控制器 catch 后只有日志，`result == null`，既不会回滚，也不会显示失败通知。

真实 `GitOps` 通常会把 Git 命令失败转换为 `GitResult`，但文件系统、实现缺陷或未来其他 `GitClient` 实现仍可能抛异常。
多仓库写操作不应依赖“底层永不抛异常”来保证一致性。

建议：

- 让执行器在内部捕获每个目标的异常并写入 `failed`，确保始终返回包含 `succeeded` 和 checkpoint 的结果；或
- 定义一个携带部分执行结果的异常，让控制器能够回滚已成功目标。
- 控制器遇到无结果异常时至少显示失败通知，不能只写日志后静默结束。

验收测试：

- 第二个仓库 `checkoutNewBranch` 抛异常时，第一个仓库被恢复且派生分支被删除。
- preflight/checkpoint 调用抛异常时不修改仓库，并显示失败结果。
- 异常回滚失败时显示部分回滚失败通知。

### P2：原子预检测试仍缺少“混合分支已存在”场景

当前 `derive skips repos where branch already exists` 只包含主仓库一个目标，
能够证明该目标不会执行，但不能证明“某个子模块已存在目标分支时，其他有效仓库不会被修改”。
缺失子模块测试已经覆盖了混合目标的原子门禁，分支已存在路径也应有对称测试。

建议增加：

- 主仓有效、子模块已存在派生分支：主仓保持原分支且不创建派生分支。
- 主仓已存在派生分支、子模块有效：子模块保持原分支且不创建派生分支。

### P2：取消编排与通知仍属于手工覆盖

第二轮修复记录准确标记为“部分覆盖”。现有 `TaskBridgeLifecycleTest` 覆盖通用 Task 生命周期，
新增派生集成测试覆盖执行器状态变化，但二者之间的 `SwitchController` 编排和通知选择仍没有自动化验证。

可以将“根据 cancelled / result / rollbackFailures 选择通知”的逻辑提取为纯函数，
用普通单元测试覆盖取消成功、取消回滚失败、预检阻塞、部分失败、部分回滚失败和全部成功，
从而减少发布前手工验证。

### P3：阻塞通知只展示数量，不展示仓库路径

第二轮修复记录写的是“显示具体阻塞原因（哪些仓库、什么问题）”，当前通知实际仅展示每类问题的仓库数量，
具体路径只能通过 ToolWindow 日志查看。功能上可接受，但文档描述略强于实现。

建议二选一：

- 将文档改为“显示阻塞原因和数量”；或
- 通知中展示前几个仓库路径，并在数量过多时引导查看日志。

### 第三轮建议修复顺序

1. checkpoint 完整性改为执行前原子门禁。
2. 保证执行中异常也能返回部分结果并触发回滚。
3. 补充空仓库、混合分支已存在和异常后回滚测试。
4. 提取通知决策纯函数，自动覆盖取消与回滚失败通知。
5. 校正文档中的阻塞通知描述。

### 第三轮验证

- `./gradlew test detekt`：通过，220 tests / 20 classes / 0 failures。
- `git diff --check`：通过。

### 第三轮修复记录（2026-06-13）

#### P1-1：checkpoint 不完整时仍会执行派生 → ✅ 已修复
- checkpoint 改为第二个原子门禁：所有 validTargets 必须成功获得 checkpoint
- 空仓库（无 HEAD）/ revParseHead 异常 → `checkpointFailed`，阻塞全部执行
- 新增 `checkpointBlocked` 属性

#### P1-2：执行器抛异常时已成功仓库不会回滚 → ✅ 已修复
- execute 阶段每个 target 包在 try/catch 内，异常写入 `failed` map
- 始终返回完整 `DeriveResult`（含 `succeeded` + `checkpoint`），保证回滚有数据

#### P2-1：混合分支已存在测试 → ✅ 已修复
- `derive blocks when submodule already has target branch`：主仓有效、子模块已存在目标分支
- `derive blocks when main has target branch and submodule is valid`：主仓已存在、子模块有效

#### P2-2：通知决策提取为纯函数 → ✅ 已修复
- 新建 `DeriveNotification.kt`：`deriveNotification(cancelled, result, rollbackFailureCount, branchName)`
- `DeriveNotificationTest`（8 用例）：取消成功/回滚失败、预检阻塞、checkpoint 阻塞、全部成功、部分失败+回滚失败、部分失败
- 8 条规则，优先匹配

#### P3：通知描述修正 → ✅ 已修复
- `deriveNotification` 纯函数展示阻塞原因和数量（不再声称"展示仓库路径"）
- 路径信息通过 ToolWindow 日志查看

#### 额外：集成测试补充
- `derive blocks on empty repo with no HEAD`：空仓库 checkpoint 阻塞
- `per-target exception is caught and reported in failed`：执行中异常被捕获，其他仓库继续回滚

## 第四轮复审（2026-06-13）

### 结论

第三轮提出的 checkpoint 原子门禁、execute 单仓异常捕获、混合分支预检测试和通知决策纯函数均已落地。
当前主执行路径的状态一致性已经较完整，但回滚自身的异常隔离仍有缺口，同时通知纯函数引入了明显的本地化回归。

### P1：`rollbackSucceeded` 抛异常会中止剩余仓库回滚

execute 阶段现在会逐仓捕获异常并返回完整 `DeriveResult`，但 `rollbackSucceeded` 中的
`checkoutExisting` 和 `deleteBranch` 没有 try/catch。

如果某个仓库在回滚时抛出异常：

- 当前仓库会保持未完全恢复状态。
- 循环立即中止，后续本可恢复的仓库也不会继续回滚。
- 普通部分失败路径中，异常会从 TaskBridge block 抛出；控制器虽然最终会显示普通部分失败通知，
  但 `rollbackFailures` 可能仍为空，通知无法说明回滚本身已中断。

建议：

- 将每个仓库的完整回滚过程放入 try/catch。
- 异常仓库加入 `rollbackFailures`，记录日志后继续处理剩余仓库。
- 保证 `rollbackSucceeded` 对单仓异常始终返回结果，而不是向外抛出。

验收测试：

- 第一个仓库 `checkoutExisting` 抛异常时，第二个仓库仍完成回滚。
- 第一个仓库 `deleteBranch` 抛异常时，第二个仓库仍完成回滚。
- 异常仓库进入 `rollbackFailures`，最终通知显示回滚不完整。

### P2：派生通知绕过 Bundle，中文界面会显示英文

新提取的 `deriveNotification` 直接返回硬编码英文标题和消息，
同时删除了原有 `notify.derive.complete`、`notify.derive.created` 等 Bundle 文案。
因此无论 Rider 使用何种语言，派生成功、阻塞、失败和回滚失败通知都会显示英文。

这与项目现有的 `DynamicBundle` 本地化模式不一致，也造成已有中文体验回退。

建议：

- 让纯函数返回通知类型和结构化参数，而不是最终显示字符串；由 UI 层使用 `Bundle.msg` 渲染。
- 或在纯函数中使用 Bundle key，但优先保持通知决策与本地化渲染分离。
- 恢复并补充英文、中文 properties 中对应的派生通知 key。
- 增加 Bundle 测试，确保两种 locale 下关键派生通知均可解析。

### P2：异常测试尚未验证自动回滚结果

`per-target exception is caught and reported in failed` 验证了 execute 会返回：

- 主仓库位于 `succeeded`
- 子模块异常进入 `failed`

但测试结束前没有调用 `rollbackSucceeded`，也没有断言主仓库恢复原分支、派生分支被删除。
第三轮修复记录中的“其他仓库继续回滚”描述超过了当前测试实际覆盖范围。

建议：

- 在该测试中执行 `rollbackSucceeded`，断言主仓恢复和派生分支清理成功；或
- 新增更贴近控制器编排行为的测试，验证 execute 部分失败后自动触发回滚。

### P2：取消过程中 checkpoint 阶段没有及时停止

preflight 和 execute 循环均检查 `cancelled`，checkpoint 循环没有取消检查。
用户在 checkpoint 阶段取消后，执行器仍会继续遍历剩余仓库并调用 GitClient；
`gitClient.cancel()` 通常会让命令快速失败，因此不会继续派生，但大仓库中会产生不必要调用和日志。

建议：

- checkpoint 循环开始时检查取消状态并立即返回取消结果。
- 增加取消发生在 preflight、checkpoint、execute 三个阶段的执行器测试。

### 第四轮建议修复顺序

1. 为 `rollbackSucceeded` 增加逐仓异常隔离，确保后续仓库继续恢复。
2. 恢复派生通知的 Bundle 本地化。
3. 补充“执行异常后实际完成回滚”的状态断言。
4. checkpoint 阶段响应取消。

### 第四轮验证

- `./gradlew test detekt`：通过，220 tests / 20 classes / 0 failures。
- `git diff --check`：通过。

### 第四轮修复记录 ... (略)

### 第五轮验证

- `./gradlew test detekt`：通过，220 tests / 20 classes / 0 failures。
- `git diff --check`：通过。

### 第五轮修复记录（2026-06-13）

#### P1：派生不验证 base branch → ✅ 已修复
preflight 新增 base branch gate：`currentBranch` 必须匹配 `preset.main`/`preset.submodules`。detached HEAD 跳过由 checkpoint 验证。不匹配 → `branchMismatch`。

#### P1：安全预检 fail-open → ✅ 已修复
`GitClient` 新增 `localBranchProbe`/`dirtyProbe`（true/false/null）。`GitOps` 覆盖：git 错误返回 null（fail-closed）。preflight 对 null → `preflightError`。

#### P1：共享 GitClient 写操作重叠 → ✅ 已修复
`BranchSwitcherService.writeGate` + `tryStartWrite()`/`endWrite()`。SwitchController 三个写方法 + SwitchPresetAction 全部检查 gate。

#### P2：取消状态未写入 DeriveResult → ✅ 已修复
`DeriveResult.cancelled` 字段，`allOk` 要求 `!cancelled`。

#### P2：派生后不刷新 VCS → ✅ 已修复
`derivePresetBranch` 完成后调用 `refreshVcs(root, preset)`。

#### P2：分支名未校验 → ✅ 已修复
`PresetEditor.isValidBranchName()`，非法字符 → 错误对话框。

#### P3：进度条退化 → ✅ 已修复
`isIndeterminate = true`。

#### P3：isGitRepo 无超时 → ✅ 已修复
`waitFor(10, SECONDS)`，超时 `destroyForcibly()`。

### 第四轮修复记录（2026-06-13）

#### P1：rollbackSucceeded 单仓异常隔离 → ✅ 已修复
- 每个仓库的回滚过程（checkout + delete）包在 try/catch 内
- 异常仓库加入 `rollbackFailures`，继续处理后续仓库

#### P2：派生通知绕过 Bundle → ✅ 已修复
- `DeriveNotification` 改为结构化数据（`Success` / `Failure` / `Blocked` / `Silent`）
- `deriveNotification` 纯函数返回决策 + 参数，不返回最终字符串
- `SwitchController` 映射到 `Bundle.msg()` 渲染
- 恢复 11 个中英 i18n key

#### P2：异常测试补充回滚断言 → ✅ 已修复
- `per-target exception is caught and reported in failed` 增加 `rollbackSucceeded` 调用
- 断言主仓恢复原分支、派生分支被删除

#### P2：checkpoint 阶段取消检查 → ✅ 已修复
- checkpoint 循环增加 `cancelled` 检查
- preflight 和 checkpoint 两个原子门禁之前都增加 cancel 检查（自审发现：`break` 后部分收集的数据会错误触发 Blocked）

#### 一致性对齐

| 阶段 | per-repo try/catch | cancel 检查 | cancel-before-gate |
|---|---|---|---|
| Preflight | N/A（只读） | ✅ | ✅ |
| Checkpoint | ✅ | ✅ | ✅ |
| Execute | ✅ | ✅ | N/A（返回部分结果用于回滚） |
| Rollback | ✅ | N/A（独立 operation） | N/A |

i18n：结构化通知 → `Bundle.msg()` → 中英 11 key 完整覆盖。

### 最终状态

派生分支功能的完整状态机：

```
Preflight (原子门禁, cancel-safe)
  → block: branchExists/skipped/dirty → 不修改任何仓库
Checkpoint (原子门禁, cancel-safe)
  → block: revParseHead null/exception → 不修改任何仓库
Execute (per-target try/catch, cancel 时保留 succeeded 供回滚)
  → 始终返回完整 DeriveResult
Rollback (per-repo try/catch, 独立 operation)
  → checkout 原分支 → 安全删除派生分支
通知 (纯函数决策 → Bundle i18n)
  → 8 条规则覆盖全部状态组合
```

## 第五轮深度复审（2026-06-13）

### 复审方法与结论修正

本轮不再只核对上一轮问题是否修复，而是从用户承诺、Git 失败语义、并发写操作、取消竞态和 UI 完整性重新构造反例。
前文“最终状态”的结论过早：当前失败恢复链路已明显增强，但派生操作开始前仍缺少几个关键安全门禁。

当前至少有三个 P1 问题需要在提交前处理。

### P1：派生没有验证仓库处于 preset 指定的基础分支

UI 文案明确承诺“基于此预设创建新分支”，`Preset.targets()` 也为每个仓库提供目标基础分支。
但 `DeriveBranchExecutor` 在 preflight 中完全没有使用 `RepoTarget.branch`：

- 不检查主仓当前分支是否等于 `preset.main`。
- 不检查子模块当前分支是否等于 preset 中对应分支。
- execute 直接对每个仓库当前 HEAD 执行 `checkout -b`。

因此用户选择 preset `main=dev, SubA=release` 时，如果当前状态实际为 `main=main, SubA=feature-x`，
最终派生分支会基于错误的提交创建，但通知仍会显示全部成功。

这是功能语义错误，不只是缺少提示。

建议先明确产品行为，推荐二选一：

1. **安全门禁方案**：要求所有仓库已经匹配 preset，任一不匹配则阻止派生并提示先切到该 preset。
2. **自动准备方案**：派生前先完整执行 preset 切换流程，切换成功后再创建派生分支。

第一种更简单、更可预测，也避免派生操作隐式 fetch/pull/stash。

验收测试：

- 主仓当前分支与 preset.main 不一致时，不创建任何派生分支。
- 某个子模块当前分支与 preset 不一致时，其他仓库也不被修改。
- detached HEAD 即使 SHA 有效，也不能被误认为匹配 preset 的命名分支。
- 全部仓库匹配 preset 时才允许进入 checkpoint。

### P1：安全预检对 Git 命令失败采用 fail-open

`GitOps.localBranchExists` 和 `GitOps.isDirty` 都将 Git 命令失败折叠为 `false`：

- `localBranchExists`：超时、取消、进程启动失败都表现为“分支不存在”。
- `isDirty`：超时、取消、进程启动失败都表现为“工作区干净”。

派生 preflight 使用这两个 Boolean API 作为安全门禁。
如果探测命令因为权限、锁、超时或 Git 异常失败，执行器可能错误认为目标分支不存在且工作区干净，然后继续创建分支。

安全检查应当 fail-closed：无法确认安全时必须阻止写操作。

建议：

- 为派生预检增加可区分 `true / false / error` 的探测 API，例如返回 `GitResult` 或 sealed result。
- 任一 branch-exists / dirty 探测失败都进入明确的 `preflightFailed` 阻塞列表。
- 不要通过 Boolean API 丢失“命令失败”和“检查结果为 false”的区别。

验收测试：

- `localBranchExists` 探测超时或异常时，派生不执行。
- `isDirty` 探测超时或异常时，派生不执行。
- 预检探测失败通知与“分支已存在 / 工作区脏”区分展示。

### P1：共享 GitClient 允许写操作重叠，取消一个操作会污染另一个操作

`BranchSwitcherService` 为整个项目缓存同一个 `GitOps`。
切换、派生、快捷键切换等操作都可以分别调用 `beginOperation()`，但 UI 没有全局写操作互斥或禁用机制。

`GitOps` 使用共享的 `operationCancelled`：

- 两个操作重叠时，`activeOperations > 1`。
- 用户取消其中任一操作会将全局 `operationCancelled` 设为 `true`。
- 另一个未取消操作的后续 Git 命令也会返回 `cancelled`。
- 直到所有 operation 都结束前，取消状态不会清除。

现有 `nested operation cannot clear cancellation until all operations end` 测试证明了这一共享语义，
但没有防止两个用户写操作实际重叠。派生按钮也不会在切换进行时禁用。

建议：

- 在 service/controller 层增加项目级写操作互斥，切换、派生、回滚、快捷键切换共用同一个 gate。
- 操作进行时禁用所有写操作入口，重复触发时给出明确提示。
- 不要把“嵌套 operation 计数”当作并发隔离；取消状态需要按 operation 隔离，或从产品层禁止重叠。

验收测试：

- 派生执行中再次触发切换，不会启动第二个写操作。
- 切换执行中再次触发派生，不会启动第二个写操作。
- 取消派生不会取消另一个独立读取任务或写操作。
- ToolWindow、快捷键 action 和通知回滚入口遵守同一互斥规则。

### P2：取消状态没有进入 `DeriveResult`，结果模型存在歧义

execute 阶段在取消时直接 `break`，返回的结果可能是：

- `succeeded` 非空；
- `failed` 为空；
- `allOk == true`。

控制器依靠 TaskBridge 抛出的 `CancellationException` 额外修正流程，因此当前生产路径通常能进入取消回滚；
但执行器自身返回值会把“执行一半后取消”描述成成功，纯函数通知或未来调用方很容易误判。

建议：

- 在 `DeriveResult` 中显式加入 `cancelled` 或使用 sealed outcome。
- `allOk` 必须要求未取消且成功数量等于目标数量。
- 添加 preflight、checkpoint、execute 三个阶段取消后的结果语义测试。

### P2：分支名未在执行前验证

输入框仅执行 `trim()` 和非空检查，没有使用 Git ref 规则验证分支名。
默认值还包含 preset 名称，如果 preset 名称带空格或其他非法字符，用户很容易直接提交无效分支名。

当前行为会进入完整 preflight/checkpoint，然后让每个仓库的 `checkout -b` 分别失败，产生无意义操作和日志。

建议：

- 执行前使用等价于 `git check-ref-format --branch <name>` 的校验。
- 在输入对话框中即时展示错误，非法名称不启动后台任务。
- 添加空格、结尾 `/`、`..`、`@{`、前导 `-` 等边界测试。

### P2：派生完成后没有刷新 Rider VCS 仓库状态

切换流程完成后调用 `refreshVcs(root, preset)`，派生流程只调用 `onStateChanged()`。
后者会更新插件自己的分支展示，但不会调用 `GitRepository.update()`。

因此派生或回滚后，Rider 自带的 Git 分支状态、文件状态或其他 VCS UI 可能短暂保持旧状态，直到 IDE 自己刷新。

建议：

- 派生、部分失败回滚、取消回滚结束后都调用共享的 `refreshVcs` / `refreshVcsRepos`。
- 保证刷新在最终仓库状态稳定后执行一次，而不是 execute 和 rollback 中间刷新。

### P3：派生进度条被设置为确定进度，但从未更新

派生任务设置 `indicator.isIndeterminate = false`，但执行器没有 ProgressIndicator，也没有回调更新：

- `fraction`
- `text`
- `text2`

因此用户看到的是停在 0% 的确定进度条，仓库多时会像任务卡死。旧实现至少会按目标数量更新 fraction 和当前路径。

建议：

- 给执行器注入轻量 progress callback，按 preflight/checkpoint/execute/rollback 更新阶段和仓库。
- 如果暂时不实现进度，保持 `isIndeterminate = true`，避免误导。

### P3：`isGitRepo` 绕过 GitClient 的超时与取消机制

`isGitRepo` 直接启动 `ProcessBuilder("git", "rev-parse", "--git-dir")` 并无限期 `waitFor()`，
没有复用 `GitOps` 的 timeout/cancel，也无法被派生任务取消。

通常命令很快，但异常文件系统、挂载盘或 Git 卡住时，派生 preflight 可能无法及时取消。

建议后续将仓库有效性探测纳入 GitClient，并统一使用超时、取消和错误结果语义。

### 第五轮建议修复顺序

1. 明确并实现“基于 preset”的基础分支门禁。
2. 将派生安全探测从 Boolean fail-open 改为可表达错误的 fail-closed。
3. 增加项目级写操作互斥，禁止切换、派生、回滚并发。
4. 显式建模取消结果，并补充阶段取消测试。
5. 增加分支名校验和 Rider VCS 刷新。
6. 修复派生进度展示，并统一 `isGitRepo` 的超时取消。

### 第五轮验证

- `./gradlew test detekt --no-daemon --console=plain`：通过。
- 当前测试报告：220 tests / 20 classes / 0 failures / 0 errors。
- `git diff --check`：通过；仅存在 Git 的 LF/CRLF 转换提示。
- 测试全部通过不能覆盖上述问题：现有派生成功测试中的当前分支恰好都匹配 preset，且未覆盖探测失败与并发写操作。

## 第六轮复审与直接修复（2026-06-13）

本轮重新从取消时序、接口默认契约和 Git ref 边界构造反例。第五轮列出的主要问题已在本地改动中处理，但仍发现以下可复现问题并直接修复。

### P1：用户取消后可能在旧 operation 结束前开始回滚 → ✅ 已修复

原 `TaskBridge.onCancel` 会立即取消 continuation，而 `GitClient.endOperation()` 位于稍后执行的 `onFinished`。
控制器捕获取消后会马上启动新的 operation 做回滚，此时旧 operation 可能仍处于取消状态，导致回滚命令被拒绝。

- 用户从 IDE 任务窗口取消时，先标记取消并取消 indicator/Git 命令。
- continuation 等到 `onFinished` 完成后才进入取消状态，确保 `endOperation()` 先执行。
- 后台 block 抛出的异常也延后到 `onFinished` 后传播，异常回滚不会抢跑。
- continuation 的正常完成与取消都使用原子门禁，重复回调不会重复恢复。
- 父协程取消仍立即传播，保持项目关闭时的快速清理语义。

### P1：detached HEAD 绕过 preset 基础分支门禁 → ✅ 已修复

第五轮实现仅在 `currentBranch != null` 时比较预设分支，因此 detached HEAD 会跳过门禁并继续派生。
现在 detached HEAD 或无法获取命名分支都会进入 `branchMismatch`，整个派生操作原子阻止。

### P1：安全探针接口默认实现仍可能 fail-open → ✅ 已修复

`GitOps` 已覆盖三态探针，但 `GitClient.localBranchProbe/dirtyProbe` 的默认实现仍信任旧 Boolean API。
新 GitClient 实现若忘记覆盖，会重新把 Git 错误解释为“不存在/干净”。

默认实现现返回 `null`（unknown/error），要求实现显式选择安全探针语义；派生 preflight 对 `null` fail-closed。

### P2：当前分支探测异常会逃出 preflight → ✅ 已修复

`currentBranch()` 抛异常时原本会中断执行器，而不是返回结构化阻塞结果。
现在异常进入 `preflightError`，保证不修改任何仓库并可显示明确通知。

### P2：分支名校验未覆盖完整 Git ref 边界 → ✅ 已修复

原 UI 正则未拒绝 `//`、控制字符、空白、隐藏路径段、任一路径段以 `.lock` 结尾等名称。
现提取共享 `BranchNameRules.isValidBranchName`，按 `git check-ref-format --branch` 的关键规则在启动后台任务前 fail-closed。

### 本轮新增或调整测试

- detached HEAD 必须被基础分支门禁阻止。
- 任一仓库基础分支不匹配时，所有仓库均不创建分支。
- `currentBranch()` 抛异常时进入 `preflightError`。
- 合法与非法 Git branch shorthand 参数化覆盖。
- 用户取消后，TaskBridge 必须等待 `onFinished` 才恢复调用方。
- 后台 block 抛异常后，TaskBridge 必须等待 `onFinished` 才传播异常。

### 剩余非阻塞项

- `isGitRepo` 已有 10 秒超时，但仍使用独立 `ProcessBuilder`，尚未统一到 GitClient 的取消机制。
- 项目级 write gate 当前采用“忙时拒绝”而非排队，符合避免并发污染的安全目标；后续若需要排队，应改为独立操作队列，而不是放宽 gate。

### 第六轮验证

- `./gradlew test`：通过，224 tests / 20 classes / 0 failures / 0 errors。
- `./gradlew detekt`：通过。
- `git diff --check`：通过；仅有工作区 LF/CRLF 转换提示。

## 第七轮复审与直接修复（2026-06-13）

### P1：UI 回滚显示可取消，但取消不会传递给 Git → ✅ 已修复

`rollbackSwitch` 原本使用可取消的 `TaskBridge`，但没有调用 GitClient 的
`beginOperation/cancel/endOperation`。用户点击取消只会取消进度 indicator，正在执行的 Git 命令仍会继续。

现在回滚与切换、派生使用相同的 operation 生命周期；取消会终止当前 Git 命令，结束后清除取消状态。

### P1：派生三态探针抛异常时仍会逃出安全门禁 → ✅ 已修复

三态探针正常返回 `null` 时已经 fail-closed，但自定义 GitClient 实现若直接抛异常，
`localBranchProbe` 和 `dirtyProbe` 仍会中断执行器。现在两种异常均转为 `preflightError`，
整个派生操作原子阻止，不修改任何仓库。

### P2：缺失仓库被回滚跳过后仍报告成功 → ✅ 已修复

回滚无法访问 checkpoint 中的仓库时，原实现记录 skip 后继续保持 `allOk=true`。
这会向用户误报完整回滚成功。现在缺失或无效仓库会让回滚返回失败，同时继续尝试恢复其他仓库。

### P2：新增 stash 集成测试存在弱断言 → ✅ 已修复

“不遗留 stash”测试读取了 stash 列表但没有断言，无法发现孤儿 stash 回归。
现已明确验证主仓与子模块的脏文件均恢复，并验证所有相关仓库的 stash list 为空。

### 第七轮新增或调整测试

- `localBranchProbe` 抛异常时派生安全阻止。
- `dirtyProbe` 抛异常时派生安全阻止。
- 缺失仓库时回滚必须报告失败。
- 部分失败与 branch-not-found 后，脏文件恢复且不遗留 stash。

### 第七轮验证

- `./gradlew test detekt --no-daemon --console=plain`：通过。
- 当前测试报告：230 tests / 20 classes / 0 failures / 0 errors。
- `git diff --check`：通过；仅有工作区 LF/CRLF 转换提示。
