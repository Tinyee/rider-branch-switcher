# 代码架构与阅读指南

这份文档面向熟悉 Java、C#、TypeScript 等语言，但刚接触 Kotlin 和
IntelliJ 插件开发的维护者。它说明代码从哪里进入、怎样流动，以及阅读本项目所需的
Kotlin 语法。

模块边界的正式定义仍以 [英文架构文档](ARCHITECTURE.md) 为准。本指南侧重帮助理解，
不重复维护所有实现细节。

## 先建立整体概念

项目分成两个 Gradle 模块：

- `core/` 是纯 Kotlin/JVM 代码，不依赖 IntelliJ 或桌面 UI。分支切换规则、
  preset 模型、checkpoint、恢复逻辑、后台操作契约和纯展示决策主要在这里。
- `src/` 是 IntelliJ 插件层，负责 Swing UI、项目级服务、后台任务、Git 进程和通知。

可以把一次完整 preset 切换理解成下面这条调用链：

```text
按钮或快捷键
  -> SwitchPreflightUi：预检和确认
  -> WriteOperationLauncher：统一获取和释放写操作 lease
  -> SwitchFlowCoordinator：执行、结果展示和 VCS 刷新
  -> SwitchRunner：平台无关的应用层切换
  -> GitOperationRunner：后台操作契约
     GitBackgroundRunner：该契约的 IntelliJ 实现，管理任务和 Git 会话
  -> SwitchExecutor：按顺序执行切换步骤
  -> SwitchRecoveryExecutor：失败或取消后的恢复
  -> SwitchResultPresenter：历史记录和通知
```

依赖只能朝底层流动：UI 可以调用 workflow、platform 和 core；workflow 不能引用
IntelliJ、platform、service 或 UI；core 不能引用 IntelliJ 或桌面 UI。UI 通过 core 中的
操作契约注入平台实现，`quickCheck` 会检查这些关键边界。

产品模型只包含一个主 Git 仓库和通过 `.gitmodules` 注册的递归子模块图。
多个独立 VCS Root 和任意同级仓库不在当前架构范围内；支持它们需要另一套
preset、checkpoint 和恢复模型。

## 推荐阅读顺序

1. [`PresetConfig.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/model/PresetConfig.kt)
   了解 preset、切换选项和请求模型。
2. [`SwitchStep.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchStep.kt)
   了解步骤返回值、不可变状态和执行上下文。
3. [`SwitchExecutor.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchExecutor.kt)
   查看完整步骤顺序。
4. [`CheckoutStep.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/CheckoutStep.kt)
   查看主仓 checkout，再阅读
   [`SubmoduleTreeStep.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SubmoduleTreeStep.kt)、
   [`SubmoduleInitializer.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SubmoduleInitializer.kt)
   和
   [`BranchCheckout.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/BranchCheckout.kt)
   了解父级优先的子模块流程、缺失子模块初始化、`.gitmodules` 路径校验和分支选择。路径迁移后，
   新路径可以正常初始化，preset 中已废弃的旧路径会被跳过，但本地旧工作树不会自动删除。
5. [`SwitchRecoveryExecutor.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchRecoveryExecutor.kt)
   了解 `SwitchRecoveryPlan`、逐仓恢复结果和 stash 恢复。
6. [`GitOperationRunner.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/operation/GitOperationRunner.kt)、
   [`SwitchRunner.kt`](../src/main/kotlin/com/submodule/branchswitcher/workflow/SwitchRunner.kt) 和
   [`GitBackgroundRunner.kt`](../src/main/kotlin/com/submodule/branchswitcher/platform/GitBackgroundRunner.kt)
   了解纯操作边界怎样由 IntelliJ 后台任务实现。
7. 最后阅读
   [`SwitchPreflightUi.kt`](../src/main/kotlin/com/submodule/branchswitcher/ui/SwitchPreflightUi.kt)、
   [`SwitchFlowCoordinator.kt`](../src/main/kotlin/com/submodule/branchswitcher/ui/SwitchFlowCoordinator.kt) 和
   [`SwitchResultPresenter.kt`](../src/main/kotlin/com/submodule/branchswitcher/ui/SwitchResultPresenter.kt)。

先读 core，再读平台和 UI，会比从最大的 Swing 类开始容易很多。

遇到具体任务时，可以直接按下面的路径追踪：

```text
Preset 切换：
  SwitchController -> SwitchFlowCoordinator -> SwitchRunner -> GitOperationRunner -> SwitchExecutor

派生分支：
  SwitchController -> DeriveBranchRunner -> DeriveBranchExecutor

Preset 编辑：
  PresetListManager -> PresetEditor -> SubmoduleRowManager

Git 命令：
  GitOps -> GitCommandClient -> GitProcessRunner -> GitOutputDrainer
```

## 完整切换流程

`SwitchExecutor` 按固定顺序执行：

1. 处理已有仓库的 dirty worktree。
2. Fetch、checkout、pull 主仓库。
3. 根据更新后的 `.gitmodules` 执行 `submodule sync --recursive`。
4. 按父级优先顺序逐个处理子模块：初始化、fetch、checkout、pull。
5. 父子模块完成 pull 后同步并重新读取其 `.gitmodules`，再处理下一层子模块。

主仓库必须先更新，因为远端主分支可能刚增加或修改子模块。缺失子模块使用
`git submodule update --init --recursive -- <path>` 初始化；嵌套子模块会在其直接父仓库中
使用相对路径初始化。主仓 checkout 或 submodule sync 失败时，后续子模块目标会被禁用，
但流水线仍会恢复此前创建的 stash。

完整切换、右键单仓切换和 Derive 共用 `SubmoduleTopology.isUnregistered` 写入门禁。
已经不在当前 `.gitmodules` 图中的旧 worktree 会保留在磁盘上，但这些入口不会再修改它。
Recovery 不使用当前注册状态，因为主仓回滚后某个 checkpoint 路径可能合法地变为废弃路径；
它通过独立的 checkpoint 安全规则恢复。

预检、执行、刷新和恢复共用一个 `OperationContext`，日志中的操作 ID 不会在预检后断开。
可恢复失败使用包含阶段、错误码和仓库路径的 `OperationIssue`，展示文字不再承担控制流语义。

切换前会记录 checkpoint。每个步骤返回新的 `SwitchState`，而不是直接修改共享状态。
这样即使中途抛异常或取消，恢复流程仍然知道哪些仓库已切换、哪些 stash 尚未恢复。
stash 使用不可变的 Git object ID 跟踪，而不是容易变化的 `stash@{n}` 序号；恢复时按 object ID
直接 apply，并保留 Git stash 条目作为人工恢复备份，不会再映射回可变序号后 pop 或自动 drop。
每个 stash 在调用 apply 前就会标记为已尝试，因为失败或中断的 apply 也可能已经部分修改工作区；
后续自动阶段不会再次 apply，通知中的回滚动作启动后也会立即失效。保留的 stash object 仍可供
人工检查和恢复。
stash 已创建但身份读取失败时，该仓库会停止后续写入，未知身份
仍保留在结构化恢复状态中，只允许人工检查，不会退回弹出栈顶的危险行为。
checkpoint 还会记录规范化后的 Git 目录身份；如果同一路径后来被另一个仓库占用，
Recovery 会先生成可检查的 `SwitchRecoveryPlan`，再逐项执行。每次 checkout 或 reset 前都会
重新确认路径和仓库身份；只有破坏性的 hard reset 要求工作区干净，普通 checkout 由 Git 自己
拒绝冲突，从而允许刚恢复的用户改动跟随回原分支。命令成功后还会校验最终 HEAD；重复执行已恢复的动作不会
再次写入。Recovery 和 Derive rollback 会拒绝修改被替换的仓库。普通写入前也会确认已初始化子模块属于项目内的
superproject，并使用父仓吸收后的外部 Git 元数据，而不是 worktree 内独立的 `.git` 目录。
结构化注册信息还保留 `.gitmodules` section 名和直接父路径，worktree 必须匹配对应的
`.git/modules/<section>`；因此两个子模块交换路径时不会操作旧 worktree。`submodule sync`
之后，`.gitmodules` 声明的 URL 也必须与 checkpoint 一致，同一路径换成另一个仓库会被阻止。

`.gitmodules` 通过 `git config --null --file` 读取，不再手工按行匹配。引号、注释、转义和
损坏配置均使用 Git 自身的解析与错误语义。

缺失子模块 init 成功后，路径会立刻写入 `SwitchState`。如果后面的 fetch、checkout
或 pull 失败，恢复流程不会删除这个新工作区，因为它在切换前没有 checkpoint，自动删除还可能
丢失已经下载或随后产生的内容。日志和通知会列出被保留的路径。

## Preset 持久化

preset 会按 `.idea/branch-presets.json`、项目根目录 `.branch-presets.json`、父目录
`.branch-presets.json` 的顺序查找，直到 Git 仓库边界，没有固定层数限制。第一个匹配项生效，
因此 `.idea/branch-presets.json` 在共享的根目录文件同时存在时是个人覆盖。
**打开 Preset 文件**会定位到当前生效的路径。加载只读取和校验，不会创建文件，也不会因为旧 ID
迁移而改写文件；没有文件时，第一次显式保存会创建个人 `.idea` 文件。团队通过主动创建并提交
根目录文件来选择共享。

`PresetRepository` 使用同一把互斥锁串行化 load/save，并在 I/O dispatcher 上访问磁盘。
编辑器和列表只在保存成功后更新内存与界面状态，因此旧的异步结果不会覆盖较新的操作。

分支下拉框的每次异步加载都会打开独立 `GitOperationSession`。preset 被折叠、编辑器或
子模块行被移除、同一个下拉框启动更新的加载时，旧协程和旧 Git 进程会一起取消；组合框上的
代次标记会阻止旧结果回写新界面。

仓库状态刷新由 `RepositoryStateRefreshCoordinator` 管理独立 Git 会话。新刷新会取消旧协程和
旧 Git 进程，最终 UI 投递仍会检查代次；Tool Window 销毁时会关闭当前刷新。

## 派生分支流程

派生分支和完整切换使用同一种后台任务边界：

```text
SwitchController
  -> DeriveBranchRunner
  -> GitBackgroundRunner
  -> DeriveBranchExecutor
```

`DeriveBranchExecutor` 明确分成三个阶段：检查全部目标、记录全部 checkpoint、创建分支。
前两个阶段是原子门禁，只要任意仓库不安全或无法记录 checkpoint，就不会修改任何仓库。
`DeriveBranchRunner` 管理任务取消；取消发生后，它会在已取消的 Git 会话关闭后，打开新会话
执行回滚。回滚结果只记录仍待处理的仓库路径；如果回滚中途被取消，新会话只重试这些路径，
既不会漏掉清理，也不会重复删除已经恢复的分支。`SwitchController` 只保留写锁、
VCS 刷新和通知展示。

## Preset 界面职责

- `PresetListManager` 渲染 preset 列表，并向 Tool Window 暴露稳定操作。
- `PresetCollectionActions` 负责加载、保存、新建、删除和持久化错误提示。
- `PresetTransferActions` 负责剪贴板导入导出。
- `CurrentStatePresetCreator` 探测所有仓库当前分支，并从完整快照创建 preset。
- `PresetEditor` 渲染和绑定单个 preset 的事件，`PresetEditRules` 负责纯草稿构造、
  未保存状态和重命名判断，`SubmoduleRowManager` 管理动态变化的子模块行。
- `ToolWindowLogPanel` 管理时间戳、最近写操作过滤/复制、清空、文本上限和完整
  `idea.log` 入口。

`BranchSwitcherPanel` 会先构造 `SwitchController`，再把明确的命令回调传给
`PresetListManager`。两者不再依赖延迟初始化来解决循环组装。

这些 UI 协作者仍通过同一个 preset 持久化入口保存，因此拆分不会产生不同的错误处理或刷新
顺序。导入、快捷键和预览判断位于 `core/presentation`；Swing 布局组件只保留在插件层
`ui`。

`ViewportWidthPanel` 强制滚动内容采用 Tool Window 的实际可见宽度；`ResponsiveRowPanel`
只在两侧内容确实放不下时改成上下排列；`TrailingControlRowPanel` 会先给右侧图标操作保留
空间，再省略过长的左侧文字；`CollapsibleActionBar` 始终保留主操作和更多菜单，
空间不足时把次要操作放入已有菜单。主仓和子模块分支选择器共用同一个响应式表单行构造器。
这些组件读取 Swing 的实际 preferred size：命令按钮保持原生紧凑宽度，分支输入框只在有限
范围内使用剩余空间。表单行和 preset 标题会适应中英文及 Look-and-Feel 的实际尺寸；唯一
明确的宽度断点是设计稿确认的 340 px 紧凑模式，此时新增 preset 只保留图标，Derive 进入
更多菜单。宽度足够时，preset 标识和操作位于同一行；空间不足时仅换行，不拉伸按钮。
隐藏区域不会继续占用间距或第二行；当获配宽度比正常内边距还小时，内边距和纵排缩进会
被限制在容器内部，避免操作落到可见区域之外。
响应式行在获得下一次实际宽度前，会保留上次已渲染的双行高度，并可根据自身到祖先链上
最窄的已布局宽度保守预估；只有 `doLayout()` 能依据当前获配宽度切换模式。模式变化会延迟
触发一次祖先重排，避免 `BoxLayout` 保留过期的单行高度。操作栏优先保留更多按钮，再压缩
主操作。编辑器底部使用嵌套响应式行：先将 Add Submodule 与保存操作分行，极窄时再将
Discard 与 Save 分行，不依赖 `FlowLayout` 的隐式换行。纵向排列时三个操作共用同一条左侧
基线；宽度足够时，外层布局仍会把保存操作组放在右侧。响应式行把内容最小宽度声明为零，
因为自定义布局已经保证子组件会在获配宽度内换行或裁剪；最小高度则跟随当前的横排或纵排
模式。这样 `BoxLayout` 不会继续分配超出可见区域的自然宽度。当前 preset 不再
显示不可用的切换按钮；更多操作共用一个 Swing 菜单，在保留 IntelliJ Look-and-Feel 的同时，
限制在设计稿要求的宽度范围内，并保持分组、图标间距、危险操作颜色和右侧锚点。已确认的交互布局保存在
[`design/branch-switcher-ui-v1.html`](design/branch-switcher-ui-v1.html)。

## Kotlin 语法速查

### `data class`

类似只承载数据的 Java record 或 C# record。Kotlin 自动生成比较、字符串表示和
`copy()`：

```kotlin
data class CheckpointEntry(
    val sha: String,
    val branch: String?,
)
```

`String?` 表示允许为 `null`；没有 `?` 的类型默认不允许 `null`。

### `sealed class` 和 `when`

`sealed class` 表示结果只能是文件中声明的几种情况，类似 TypeScript 的联合类型：

```kotlin
when (val result = execution.result) {
    is StepResult.Success -> ...
    is StepResult.Partial -> ...
    is StepResult.Fatal -> ...
}
```

编译器会检查是否处理了所有分支，因此很适合成功、部分失败和致命失败这类结果。

### 空值操作

```kotlin
value?.method()       // value 不为 null 时才调用
value ?: fallback    // value 为 null 时使用 fallback
```

项目关键安全流程会优先使用明确的局部变量和 `if`，避免把多个空值操作嵌套在一起。

### 表达式函数和扩展函数

```kotlin
fun isSuccessful(): Boolean = status == SUCCESS
```

等号右边就是返回值。扩展函数看起来像对象方法，但实际是在其他位置定义的普通函数：

```kotlin
result.toSwitchResult()
```

阅读时如果找不到成员方法，可以使用 IDE 的“Go to Declaration”定位扩展函数。

### `val`、`var` 和集合转换

- `val` 表示引用不能重新赋值，优先使用。
- `var` 表示后续会重新赋值。
- `map`、`filter`、`associate` 会产生新集合，不会修改原集合。

切换状态使用不可变对象，是为了让失败恢复能够保留每一步完成后的准确快照。

### `object`

`object` 声明一个全局唯一实例，类似只包含静态方法的工具类。本项目用它表示无状态、
职责单一的协作者，例如 `SubmoduleInitializer.prepare(...)`。它不保存某次切换的状态，
操作状态仍通过参数和返回值显式传递。

### 协程

`suspend fun` 表示函数可以挂起，但不等同于自动创建线程。插件层通过
workflow 只依赖 `GitOperationRunner`，不认识 IntelliJ。`GitBackgroundRunner` 实现这个契约，
把 Git 操作放入 IntelliJ 后台任务，并将取消信号传到当前 `GitOperationSession`。完成和取消
通过同一个原子状态交接，因此两者同时发生时不会丢失恢复所需的执行结果。同步的 preset 文件
访问、分支读取和仓库状态 Git 命令会显式调度到 I/O dispatcher；`SwitchRunner`、
`DeriveBranchRunner` 和单仓写入会在入口内部建立 I/O 上下文，不依赖调用者选择线程。
IntelliJ 完成回调即使发生在 EDT，协程也会通过自己的 dispatcher 恢复；最终界面更新仍显式
回到 UI 线程。core 本身不依赖协程或 IntelliJ。

`GitProcessRunner` 全局最多允许四个 Git 进程，并用八个专用线程读取 stdout/stderr。
stdout 超过 8 MiB 会明确失败，stderr 只保留最后 128 KiB 诊断内容，不会无限占用内存。
取消、超时或输出捕获卡住时，会终止运行期间观察到的子进程并关闭父进程流；如果强制终止在预算内仍未
完成，对应的 Git 并发许可只会在进程实际退出后异步归还。后续命令等待许可的时间不超过配置的 Git 超时，
超时后返回独立的 `PROCESS_CAPACITY` 错误，不会无限等待。如果 JVM 无法提供 `onExit` future，最多四个
守护线程会轮询实际退出状态，确认退出后再归还许可。remote 名称只在单个 `GitOperationSession` 内缓存，
预检也使用独立短会话，不会跨请求沿用失效结果。
仓库状态刷新把分支、HEAD 和 dirty 状态合并为每仓库一个进程；预检再读取目标 refs，首次还会
查询 remote 名称，最多三个进程。真正的切换、checkpoint 和恢复仍在写操作附近重新读取状态，
不会使用可能过期的界面快照。

## 故障日志

`ToolWindowLogger` 会把所有可见级别同时写入名为 `SubmoduleBranchSwitcher` 的 IntelliJ
诊断 logger。Tool Window 是有行数上限的临时视图，持久化排障来源是 `idea.log`；带 Throwable
的 warn/error 会在其中保留完整堆栈。

完整切换、Derive 和单仓切换分别生成短操作 ID。请求参数、实际选项、各仓 checkpoint、Git
失败详情、恢复动作、VCS 刷新和最终摘要使用同一个 ID。`.gitmodules` 声明的 URL 不记录
原文，只记录 SHA-256 指纹，既能比较切换前后身份，又不会泄露凭据或私有地址。

## 修改代码时先找职责

- 修改切换规则：`core/switch`
- 修改后台操作契约：`core/operation`
- 修改 Git 命令：`core/git` 接口和插件层 `git`
- 修改 preset JSON：core 的 model、validation 和 `PresetLoader`
- 修改可复用业务流程：`workflow`，不得直接引用 IntelliJ 或 `platform`
- 修改后台任务或取消：`platform`
- 修改对话框、通知和组件：`ui`

不要因为某个函数调用方便就跨层引用。新增逻辑放在哪里、最低需要运行哪些测试，可分别
查看 [英文架构文档](ARCHITECTURE.md#change-guide) 和
[贡献指南](../CONTRIBUTING.md#validation)。
