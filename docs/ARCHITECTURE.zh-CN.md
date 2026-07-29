# 代码架构与阅读指南

这份文档面向熟悉 Java、C#、TypeScript 等语言，但刚接触 Kotlin 和
IntelliJ 插件开发的维护者。它说明代码从哪里进入、怎样流动，以及阅读本项目所需的
Kotlin 语法。

模块边界的正式定义仍以 [英文架构文档](ARCHITECTURE.md) 为准。本指南侧重帮助理解，
不重复维护所有实现细节。

## 先建立整体概念

项目分成两个 Gradle 模块：

- `core/` 是纯 Kotlin/JVM 代码，不依赖 IntelliJ 或桌面 UI。分支切换规则、
  preset 模型、checkpoint、恢复逻辑和纯展示决策主要在这里。
- `src/` 是 IntelliJ 插件层，负责 Swing UI、项目级服务、后台任务、Git 进程和通知。

可以把一次完整 preset 切换理解成下面这条调用链：

```text
按钮或快捷键
  -> SwitchFlowCoordinator：确认、提示和 UI 编排
  -> SwitchRunner：启动一次应用层切换
  -> GitBackgroundRunner：管理后台任务和 Git 会话
  -> SwitchExecutor：按顺序执行切换步骤
  -> SwitchRecoveryExecutor：失败或取消后的恢复
```

依赖只能朝底层流动：UI 可以调用 workflow 和 core，但 core 不能反过来引用 IntelliJ
或 UI。`quickCheck` 会检查关键边界。

## 推荐阅读顺序

1. [`PresetConfig.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/model/PresetConfig.kt)
   了解 preset、切换选项和请求模型。
2. [`SwitchStep.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchStep.kt)
   了解步骤返回值、不可变状态和执行上下文。
3. [`SwitchExecutor.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchExecutor.kt)
   查看完整步骤顺序。
4. [`CheckoutStep.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/CheckoutStep.kt)
   查看 checkout 的整体编排，再阅读
   [`SubmoduleInitializer.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SubmoduleInitializer.kt)
   和
   [`BranchCheckout.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/BranchCheckout.kt)
   了解缺失子模块初始化和分支选择。
5. [`SwitchRecoveryExecutor.kt`](../core/src/main/kotlin/com/submodule/branchswitcher/switch/SwitchRecoveryExecutor.kt)
   了解 checkpoint、回滚和 stash 恢复。
6. [`SwitchRunner.kt`](../src/main/kotlin/com/submodule/branchswitcher/workflow/SwitchRunner.kt)
   和
   [`GitBackgroundRunner.kt`](../src/main/kotlin/com/submodule/branchswitcher/platform/GitBackgroundRunner.kt)
   了解 IntelliJ 后台任务如何连接 core。
7. 最后阅读
   [`SwitchFlowCoordinator.kt`](../src/main/kotlin/com/submodule/branchswitcher/ui/SwitchFlowCoordinator.kt)
   和 UI 类。

先读 core，再读平台和 UI，会比从最大的 Swing 类开始容易很多。

遇到具体任务时，可以直接按下面的路径追踪：

```text
Preset 切换：
  SwitchController -> SwitchFlowCoordinator -> SwitchRunner -> SwitchExecutor

派生分支：
  SwitchController -> DeriveBranchRunner -> DeriveBranchExecutor

Preset 编辑：
  PresetListManager -> PresetEditor -> SubmoduleRowManager

Git 命令：
  GitOps -> GitCommandClient -> GitProcessRunner
```

## 完整切换流程

`SwitchExecutor` 按固定顺序执行：

1. 处理已有仓库的 dirty worktree。
2. Fetch、checkout、pull 主仓库。
3. 根据更新后的 `.gitmodules` 执行 `submodule sync --recursive`。
4. Fetch 已存在的子模块。
5. 初始化本地缺失的子模块，并切换每个目标分支。
6. Pull 已成功切换的子模块。

主仓库必须先更新，因为远端主分支可能刚增加或修改子模块。缺失子模块使用
`git submodule update --init --recursive -- <path>` 初始化。

切换前会记录 checkpoint。每个步骤返回新的 `SwitchState`，而不是直接修改共享状态。
这样即使中途抛异常或取消，恢复流程仍然知道哪些仓库已切换、哪些 stash 尚未恢复。

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
执行回滚。`SwitchController` 只保留写锁、VCS 刷新和通知展示。

## Preset 界面职责

- `PresetListManager` 渲染 preset 列表，并向 Tool Window 暴露稳定操作。
- `PresetCollectionActions` 负责加载、保存、新建、删除和持久化错误提示。
- `PresetTransferActions` 负责剪贴板导入导出。
- `CurrentStatePresetCreator` 探测所有仓库当前分支，并从完整快照创建 preset。
- `PresetEditor` 编辑单个 preset，`SubmoduleRowManager` 管理其中动态变化的子模块行。
- `ToolWindowLogPanel` 管理日志折叠、颜色、文本裁剪和展示状态。

`BranchSwitcherPanel` 会先构造 `SwitchController`，再把明确的命令回调传给
`PresetListManager`。两者不再依赖延迟初始化来解决循环组装。

这些 UI 协作者仍通过同一个 preset 持久化入口保存，因此拆分不会产生不同的错误处理或刷新
顺序。导入、快捷键、预览和响应式布局规则位于 `core/presentation`；Swing 布局组件只保留
在插件层 `ui`。

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
`GitBackgroundRunner` 把 Git 操作放入 IntelliJ 后台任务，并将取消信号传到当前
`GitOperationSession`。core 本身不依赖协程或 IntelliJ。

## 修改代码时先找职责

- 修改切换规则：`core/switch`
- 修改 Git 命令：`core/git` 接口和插件层 `git`
- 修改 preset JSON：core 的 model、validation 和 `PresetLoader`
- 修改可复用业务流程：`workflow`
- 修改后台任务或取消：`platform`
- 修改对话框、通知和组件：`ui`

不要因为某个函数调用方便就跨层引用。新增逻辑放在哪里、最低需要运行哪些测试，可分别
查看 [英文架构文档](ARCHITECTURE.md#change-guide) 和
[贡献指南](../CONTRIBUTING.md#validation)。
