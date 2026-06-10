# 代码审查 — 2026-06-08

**审查范围**: `4710589^...HEAD`(工具栏 + 卡片新布局重构, 4 次 commit, 仅 `BranchSwitcherPanel.kt`, +105 / -95)

**Commit 范围**:
- `4710589` feat: 方案C — 工具栏+卡片 新布局
- `44b4f34` fix: 工具栏改为两行 + 进度条调细
- `7fe2d3f` fix: 工具栏拆三行 — 操作/新建/选项 各一行
- `f17c982` fix: 工具栏按钮加文字标签 + 拆两行操作行

---

## 1. 【高】Log 折叠条初始渲染会显示 Icon 的 toString 字符串

**文件**: `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt:194`

```kotlin
logToggle = JLabel("${AllIcons.General.ArrowRight} Log").apply { ... }
```

Kotlin 字符串模板会对 `Icon` 对象调 `toString()`,结果是类似
`com.intellij.openapi.util.IconLoader$CachedImageIcon@1a2b3c` 这样的内部字符串。
`JLabel(String)` 单参构造只设 text,根本没设 icon 属性。

工具窗口刚打开时,用户会看到 `IconLoader$CachedImageIcon@... Log` 这样的乱码 +
没有真正的箭头图标。只有用户首次点击折叠条触发 `toggleLog()`(L215),icon 才会被
正确赋值、text 才被覆盖成 `" Log"`。

**修复**:

```kotlin
logToggle = JLabel(" Log", AllIcons.General.ArrowRight, SwingConstants.LEFT).apply { ... }
```

---

## 2. 【高】`toggleLog` 中的 text 三元分支两边一样

**文件**: `BranchSwitcherPanel.kt:216`

```kotlin
logToggle.text = if (logVisible) " Log" else " Log"
```

两个分支都是字面量 `" Log"`,这一行是 dead code(看起来是从上一行的 icon 三元复制
来,但忘了改 text 内容)。

**修复**: 删掉这行,或让两态文案区分(如 `"展开 Log"` / `"收起 Log"`)。

---

## 3. 【中】`action.clear.log` 资源 key 成孤儿

**文件**:
- `src/main/resources/messages/BranchSwitcherBundle.properties:19`
- `src/main/resources/messages/BranchSwitcherBundle_zh.properties` (对应行)

这次 diff 删了 "Clear Log" 按钮,但两个 properties 文件里 `action.clear.log=Clear Log`
仍然存在,没有任何 Kotlin 代码引用。

**修复**: 同步删除两份资源条目。

---

## 4. 【中】工具栏行的注释 / 命名错乱

**文件**: `BranchSwitcherPanel.kt:138, 150`

```kotlin
// Row 2: import/export/undo     ← 但变量叫 row1b
val row1b = JPanel(...)
...
// Row 2: new preset buttons     ← 又来一个 Row 2
val row2 = JPanel(...)
```

`row1` / `row1b` / `row2` / `row3` 命名混乱,且 "Row 2" 这条注释出现了两次。

**修复**: 改成语义命名: `actionsRow` / `exportRow` / `addRow` / `optionsRow`。

---

## 5. 【中-低】移除 Clear Log 按钮后,用户失去手动清空日志的入口

**文件**: `BranchSwitcherPanel.kt` (整体)

`append()` (L342-349) 对日志做了 5000 行硬性截断,但这个新布局把唯一的手动清空入口
删掉了。长会话场景下,用户只能等到 5000 行自动 trim,过程中无法主动清掉积累的噪音。

**修复**: 把 Clear Log 移进新工具栏,或在 logToggle 旁边加个小按钮(只在日志展开时
显示)。

---

## 6. 【低】工具栏按钮构造模式重复 7 次

**文件**: `BranchSwitcherPanel.kt:132-158`

每个按钮都是一遍:

```kotlin
JButton(Bundle.msg("..."), icon).noFocusRing().also { it.addActionListener { ... } }
// 加 tooltip 时再多一行 it.toolTipText = ...
```

**修复**: 抽个 helper,能少 ~50% 代码:

```kotlin
private fun toolbarButton(
    textKey: String,
    icon: Icon,
    tooltipKey: String? = null,
    action: () -> Unit,
): JButton = JButton(Bundle.msg(textKey), icon).noFocusRing().also {
    if (tooltipKey != null) it.toolTipText = Bundle.msg(tooltipKey)
    it.addActionListener { action() }
}
```

---

## 总结(Part 1)

- **优先修**: #1 + #2,这两个合在一起会让 Log 折叠条在用户首次点击前一直显示异常。
- **清理项**: #3 + #4,顺手做。
- **UX/Reuse**: #5 + #6,可以另起一个 commit 跟新布局解耦。

---
---

# Part 2: 全仓代码审查 (2026-06-08 追加)

**审查范围**: 全部 30 个 main Kotlin 文件 (~3000 行) + 资源文件。由 5 个并行 reviewer 按子系统(switch/、git+service+TaskBridge、ui/、model+i18n、action+settings)分头跑,主线路径 (DirtyHandling/Checkout/Pull/PullStep/CheckoutStep/SwitchExecutor) 由我手动抽查代码确认。

按严重度分级。带 ⚠ 的是会导致实际数据损坏或丢失的 critical 级别问题,优先修。

---

## 【Critical】数据损坏 / 数据丢失风险

### 7. ⚠ PullStep 会在 checkout 失败的旧分支上执行 pull,可把 main 快进到 dev

**文件**: `src/main/kotlin/com/submodule/branchswitcher/switch/PullStep.kt:13-23` + `switch/SwitchExecutor.kt:77-82`

```kotlin
// PullStep 不检查 checkout 是否成功就 pull
for (target in context.preset.targets()) {
    val dir = resolveGitDir(context.projectRoot, target.path)
    if (!dir.exists() || !isGitRepo(dir)) continue
    val p = context.git.pullFf(dir, target.branch)   // ← 在 dir 当前分支上 pull
    ...
}
```

**失败场景**: 用户当前在 `main`, 切到 preset `dev`。CheckoutStep 因脏文件冲突失败,但 SwitchExecutor 对 `Partial` 不 break,继续跑 PullStep。PullStep 在 `dir`(还在 main 上)执行 `git pull --ff-only origin dev`,如果 main 是 origin/dev 的祖先,**main 会被快进到 origin/dev 的 commits**,污染 main 分支。

**修复**: PullStep 开头 `context.git.currentBranch(dir)` 检查是否已是 `target.branch`,否则 skip 并记录;更稳是只对 CheckoutStep 成功的 paths(借助 `context.successfulCheckouts`)做 pull。

---

### 8. ⚠ DirtyHandlingStep `Skip` 不真正 skip,CheckoutStep 仍会切分支

**文件**: `switch/DirtyHandlingStep.kt:28-32` + `switch/SwitchExecutor.kt:71-83`

```kotlin
// DirtyHandlingStep
DirtyAction.Skip -> {
    context.log("[skip] working tree dirty — ${target.path}")
    failures[target.path] = "working tree dirty"
    continue   // ← 只在本 step 内 continue,失败信息仅落到 step 返回值
}
```

`failures` 只是本 step 的局部 map,`SwitchExecutor` 拿到 `Partial(failures)` 后只把 `overallSuccess` 设为 false 就继续下一个 step。CheckoutStep 重新 iterate `context.preset.targets()`,完全不知道哪些被 Skip 了。

**失败场景**: 仓库脏 + 用户选 dirty=Skip + 目标分支存在且不冲突 → 本应"跳过这个仓库",实际仓库照样切到目标分支,与用户意图相反。

**修复**: 在 `SwitchContext` 加 `skippedPaths: MutableSet<String>`,DirtyHandlingStep Skip 时写入,CheckoutStep / PullStep / SubmoduleSyncStep 跳过这些 path。或让 Skip 升级为 `StepResult.Fatal` 阻断整条 pipeline。

---

### 9. ⚠ DirtyHandlingStep stash 失败后,CheckoutStep 仍会切分支,把脏改动带到新分支

**文件**: `switch/DirtyHandlingStep.kt:38-44`

```kotlin
val r = context.git.stash(dir, "branch-switcher: before -> ${target.branch}")
if (!r.ok) {
    failures[target.path] = "stash failed"
    continue   // ← 同问题 8,后续 step 仍会处理这个 path
}
```

**失败场景**: dirty=Stash,但 `git stash push -u` 因 index lock / 权限 / unmerged path 失败 → CheckoutStep 仍尝试 `git checkout dev`,如不冲突,**用户未保护的脏改动就跟到 dev 分支**。

**修复**: 同问题 8 — 写 `skippedPaths`,或返回 `Fatal`。

---

### 10. ⚠ CheckoutStep 在目标分支不存在时,已 stash 的改动不会被 pop(用户改动隐式留在 stash 列表)

**文件**: `switch/CheckoutStep.kt:88-92` + `:100-109`

```kotlin
} else {
    context.log("[fail] branch '${target.branch}' not found locally or on origin")
    failures[target.path] = "branch not found"
    continue   // ← 直接跳到下一 target,未走到 100 行的 stashedPaths.remove → stashPop
}
```

**失败场景**: 仓库脏 + dirty=Stash → DirtyHandlingStep stash 成功,`stashedPaths[target.path]` 已写入。CheckoutStep 发现本地+远端都无目标分支 → `continue`,**stash 不会 pop**。用户工作区变干净,改动留在 stash 里只显示一行 fail 日志。

**修复**: branch-not-found 分支也要 `context.stashedPaths.remove(target.path)?.let { stashPop }`;更整洁是把 stash 恢复抽成 step finally 清理。

---

### 11. ⚠ Gson 反序列化 Preset 时 Kotlin 默认值不可靠

**文件**: `model/PresetConfig.kt:14-19` + `PresetLoader.kt:55-63` + `ui/PresetListManager.kt:252-270`

```kotlin
data class Preset(
    val name: String,                              // 必填,无默认 → Gson 用 Unsafe 分配
    val main: String,                              // 必填,无默认
    val submodules: Map<String, String> = emptyMap(),  // 默认值不会被 Gson 应用
    @SerializedName("pull") val pullEnabled: Boolean = true,  // 默认 true 也不会被应用
)
```

`Preset` 没有全默认参数,Kotlin 不会生成 no-arg ctor,Gson 用 `UnsafeAllocator.newInstance` 跳过 ctor → 缺失字段保持 JVM 默认(`null` for object, `false` for boolean primitive)。

**失败场景**:
- JSON 是 `[{"name":"x","main":"dev"}]`(无 submodules)→ `Preset.submodules == null` → `targets()` 里 `submodules.forEach` **NPE**。
- JSON 缺 `"pull"` 字段 → `pullEnabled == false`,默认行为从"切完 pull"翻转为"不 pull"。
- import 路径(PresetListManager.importPresets)无校验,任意剪贴板 JSON 都会触发。

**修复**: 用 nullable DTO 中转,parse 后 normalize:
```kotlin
data class PresetDto(val name: String?, val main: String?, val submodules: Map<String,String>? = null, val pull: Boolean? = null)
fun PresetDto.toPreset() = Preset(
    name = name ?: error("preset.name required"),
    main = main ?: error("preset.main required"),
    submodules = submodules ?: emptyMap(),
    pullEnabled = pull ?: true,
)
```
load + import 都走这个转换。

---

## 【High】资源、错误处理、并发

### 12. TaskBridge 缺 invokeOnCancellation,父 coroutine 取消不会真正取消 IDE Task

**文件**: `TaskBridge.kt:49-68`

```kotlin
suspendCancellableCoroutine<Unit> { cont ->
    ProgressManager.getInstance().run(object : Task.Backgroundable(...) {
        override fun onCancel() { cont.cancel() }  // ← 只处理 IDE Task → coroutine 方向
        // 但反过来:cont.cancel() 不会让 Task 退出
    })
}
```

**失败场景**: `service.scope` 在 project dispose 时被取消,所有 launch 中断;但已经提交给 ProgressManager 的 Backgroundable 仍跑到完成或超时(60s),期间 `invokeLater` 回调可能访问已 disposed 的 project/UI。

**修复**:
```kotlin
val taskRef = AtomicReference<Task.Backgroundable?>(null)
cont.invokeOnCancellation { /* 拿到 indicator 后 indicator.cancel() */ }
```
或要求 block 内显式 `indicator.checkCanceled()`。注意 git 子进程不响应 cancel,需要超时短或可中断的 process handler。

---

### 13. SwitchController 在 TaskBridge.runBackground 之后修改 Swing,但 launch 用 Dispatchers.Default → EDT 违例

**文件**: `ui/SwitchController.kt:73, 95-120`(executeSwitch)、`:125-144`(rollbackSwitch)、`:148-174`(derivePresetBranch);+ `TaskBridge.kt:42` 注释误导

```kotlin
service.scope.launch(Dispatchers.Default) {
    ...
    TaskBridge.runBackground(project, ...) { ... }
    // 注释说 "Resumed on EDT" — 错的
    setSwitchInProgress(false)         // ← 改 ToolWindow icon + JProgressBar,但在 Default 上
    Notifier.info(project, ...)
}
```

`TaskBridge.onFinished` 里 `cont.resume()` 是从 EDT 调的,但 continuation 按 caller 的 dispatcher(Default)派发,后续代码并不在 EDT。

**失败场景**: 切换完成后,`tw.setIcon(...)`、`progressBar.isVisible = ...` 在后台线程跑,触发 IntelliJ threading assertion 或随机 UI 状态错乱。

**修复**: 二选一
- 调用方在 `runBackground` 后所有 UI 操作显式包 `ApplicationManager.getApplication().invokeLater { ... }`
- 引入 EDT dispatcher (`Dispatchers.Main` from kotlinx-coroutines-swing,或自定义 `Dispatchers.EDT`),launch 时用 EDT,后台耗时段用 `withContext(Default)`。

---

### 14. GitOps `remoteName` 取 `git remote` 第一行,非 origin 优先

**文件**: `git/GitOps.kt:60-69`

```kotlin
val name = r.stdout.lines().firstOrNull()?.trim().orEmpty().ifEmpty { "origin" }
```

`git remote` 默认按字母排序输出 → `backup` + `origin` 配置时拿到 `backup`,但日志(`CheckoutStep:86 "creating from origin/${target.branch}"`)硬编码 `origin`,语义错位。

**失败场景**: 仓库有 `backup` + `origin`,目标分支只在 `origin` 上 → `remoteBranchExists()` 查的是 `refs/remotes/backup/...` 返回 false → CheckoutStep 报"branch not found";或 `pullFf` 从错误 remote 拉取。

**修复**: 优先 `origin`,其次当前分支的 upstream remote,最后兜底取第一个;同步把日志的 `origin` 字面量替换为实际 remote name。

---

### 15. SwitchPresetAction 把用户取消当切换失败处理

**文件**: `action/SwitchPresetAction.kt:76` + `:89`

```kotlin
// 用户在 preflight warning 选 "不继续" → return@runBackground,
// 但外层逻辑没区分 cancel/fail,继续走失败通知 + onBranchSwitched()
} catch (e: Exception) { ... }   // ← line 89 还吞 CancellationException
```

**失败场景 A**: preflight 弹"目标分支不存在,继续?"用户选 No → 弹"switch failed"通知 + 触发 `onBranchSwitched()`(让 panel 误以为切换发生过)。
**失败场景 B**: 用户点 Background Task 的 Cancel → `CancellationException` 被 `catch (e: Exception)` 吞,走失败路径。

**修复**: 加 `cancelled` 标志;`catch (e: CancellationException) { throw e }` 在前,普通 Exception 在后。

---

### 16. PresetListManager `onDerive` 捕获 stale preset 引用

**文件**: `ui/PresetListManager.kt:86`

```kotlin
fun addEditorRow(root: Path, preset: Preset, presetsInner: JPanel) {
    ...
    onDerive = { branchName -> onDerive(root, preset, branchName) },  // 闭包捕获 ctor 时的 preset
}
```

**失败场景**: 用户编辑 preset(添加新子模块、改主分支)并保存,然后点 Derive Branch → 派生用的是**初始化时的 preset**,新增的子模块不会被处理,或派生到旧的 main 分支。

**修复**: `onDerive = { branchName -> onDerive(root, editor.currentPreset(), branchName) }`(注意命名遮蔽,需重命名其中一个 `onDerive`)。

---

### 17. PresetListManager.importPresets 不校验字段

**文件**: `ui/PresetListManager.kt:252-270`(import 路径)

只检查 `imported != null && presets.isNotEmpty()`,未对每个 preset 校验 `name/main/submodules` 非 null/非空。结合问题 11,坏 JSON 直接 NPE。

**修复**: 走问题 11 的 DTO + normalize 路径,无效项跳过并通知用户。

---

### 18. BranchComboUtil `loadComboBranches` 顺序错位 → "loading..." 可被保存

**文件**: `ui/BranchComboUtil.kt:96-99` + `ui/SubmoduleRowManager.kt:159-162`

```kotlin
combo.model = ...                  // 触发 ItemListener → updateDirty(),此刻 loadingCount==0
combo.selectedItem = "loading..."  // 同上
onLoadStart()                      // ← loadingCount++,但已经晚了
```

**失败场景**: 用户展开 preset 卡片瞬间点 Save → `buildCurrent()` 把 "loading..." 字符串当成分支名持久化到 JSON。

**修复**: 调换顺序 — 先 `onLoadStart()` (loadingCount++) 再 mutate combo;或 onLoadStart 末尾立即 `updateDirty()` 把 Save 按钮置灰。

---

### 19. PresetEditor 保存路径乐观更新,写盘失败时 UI 已认为已保存

**文件**: `ui/PresetEditor.kt:167-174` 附近(Save 按钮 actionListener 与 rename)

```kotlin
original = cur          // ← 先更新内存 original
onSave(cur)             // ← saveAll() 抛异常时,UI 状态已经认为保存成功
updateDirty()
```

**失败场景**: 磁盘只读 / `.idea` 不可写 / JSON 文件被锁 → `service.savePresets()` 抛 IOException,但 `original` 已变成 `cur`,`currentPreset()` 返回新值,Save 按钮 disable;reload 后回退,用户以为已保存。

**修复**: `onSave` 返回 `Result`/Boolean,成功后才更新 `original` + label;或在 `PresetEditor` 内 try/catch 异常,失败时保留 dirty 状态 + 弹错。

---

## 【Medium】并发与边界

### 20. BranchSwitcherService.detectGen 非原子

**文件**: `service/BranchSwitcherService.kt:150` 附近

`detectGen` 是普通 `Long`,`nextDetectGen()` 不是原子操作。当 `detectCurrentState()` 同时被 EDT 触发(message bus / hierarchy event)和后台 coroutine `onStateChanged()` 触发时,自增可能丢失,旧探测结果反而通过 `gen == getDetectGen()` 校验并覆盖新结果。

**修复**: 改为 `AtomicLong` + `incrementAndGet()` / `get()`。

---

### 21. service.history 非线程安全 + 失败也写历史

**文件**: `service/BranchSwitcherService.kt:131` 附近 + `ui/SwitchController.kt:105`

`addHistory()` / `getHistory()` 直接操作 `MutableList`,无锁;`SwitchController` 在 switch 失败后也调 `service.addHistory(preset.name)`(L105 在 `setSwitchInProgress(false)` 之后,无 ok 判断)。

**失败场景**: undo 时取 `history[1]` 拿到失败的目标 preset,导致 undo 切到错误目标;并发 add/getHistory 还可能 ConcurrentModificationException。

**修复**: history 限定 EDT 或加 `synchronized`;`addHistory` 仅在 `ok == true` 时调用。

---

### 22. PresetLoader 的 IO 异常逃逸 Result

**文件**: `PresetLoader.kt:55` 附近

`ensureFile()`、`Files.readString()` 在 try 之外或只 catch `JsonSyntaxException`。SecurityException / AccessDeniedException / IOException 会抛到调用方,但调用方按 `Result` API 用 `.onFailure` 处理 → UI action 直接 crash。

**修复**: 把 ensureFile + read + parse 全包进 `runCatching { }.recoverCatching { }`。

---

### 23. SubmoduleSyncStep 在主仓库 checkout 失败的旧分支上跑 sync

**文件**: `switch/SubmoduleSyncStep.kt:7-15` + `SwitchExecutor.kt:77-82`

主仓库 checkout 失败仅返回 `Partial`,Executor 不 break,SubmoduleSyncStep 在主仓库**旧分支**的 `.gitmodules` 上跑 `git submodule sync --recursive`,可能把 submodule URL 同步回旧配置。

**修复**: 同问题 8/9,通过 `successfulCheckouts` 集合判断,或主仓 checkout 失败升级为 `Fatal`。

---

### 24. Stash pop 在 CheckoutStep 内立即执行 → PullStep 在脏工作树上 pull

**文件**: `switch/CheckoutStep.kt:100-109` + `switch/PullStep.kt`

```kotlin
// CheckoutStep 切完立刻 pop stash
context.stashedPaths.remove(target.path)?.let { ... stashPop ... }
// 之后 PullStep 在带 pop 出来的脏改动的工作树上跑 pull
```

**失败场景**: dirty=Stash + pullAfterSwitch=true + 远端 dev 修改了相同文件 → stash pop 后工作树脏 → `git pull --ff-only` 报 untracked/conflict 失败。正确顺序应是 checkout → pull → pop。

**修复**: stash pop 推迟到 PullStep 之后(独立 step,或 PullStep 末尾按 `stashedPaths` 统一 pop)。

---

### 25. invokeLater 回调缺 disposed guard

**文件**: `ui/BranchComboUtil.kt:107`、`ui/PresetListManager.kt:183`、`ui/SwitchController.kt:197/202`、`ui/BranchSwitcherToolWindowFactory.kt:42` 等

异步 git/switch/refresh 完成后的 `ApplicationManager.invokeLater { ... }` 没传 `ModalityState` + `project.disposed`。项目被关闭后回调仍可能访问 disposed project / 已移除组件。

**修复**: `invokeLater(runnable, ModalityState.any(), project.disposed)`,或回调首行 `if (project.isDisposed) return`。

---

## 【Low】清理 / 一致性

### 26. 孤儿 i18n keys

**文件**:
- `src/main/resources/messages/BranchSwitcherBundle.properties`
- `src/main/resources/messages/BranchSwitcherBundle_zh.properties`

下面这些 key 在 .kt 代码里没有任何 `Bundle.msg("KEY")` 引用(`action.switch.preset.action*` 实际由 `plugin.xml` 引用,要保留):
- `plugin.toolWindow.id`
- `plugin.notification.group`
- `action.clear.log`(本次审查 Part 1 已列出)
- `action.move.up`
- `action.move.down`
- `label.current`
- `init.confirm.title`
- `init.confirm.msg`
- `status.not.init`
- `log.imported`
- `log.added.from.current`
- `log.derive.created`

**修复**: 删除真正未引用的 key,两份 properties 同步。如某些 key 是计划保留的"未来文案",最好加 `# TODO` 注释明示。

---

### 27. SwitchPresetAction 缺 `update()` / `getActionUpdateThread()`

**文件**: `action/SwitchPresetAction.kt`

未覆盖 `update()`,Welcome screen / 无 project 窗口里 Action 仍可见可触发,触发后只 `e.project ?: return` 静默 no-op。同时未实现 `getActionUpdateThread()`,IntelliJ 2022.3+ 会有 deprecation 警告或运行时报错。

**修复**: 覆盖 `update()` 按 `e.project != null` 设置 enabled/visible;实现 `getActionUpdateThread() = ActionUpdateThread.BGT`(或 EDT,看实现)。

---

### 28. BranchSwitcherConfigurable 缺 `disposeUIResources()`

**文件**: `settings/BranchSwitcherConfigurable.kt:25` 附近

未覆盖 `disposeUIResources()`,Settings 关闭后 configurable 实例若被缓存,会保留旧 Swing 组件引用。

**修复**: 覆盖 `disposeUIResources()` 把 `panel`、各 combo / checkbox 字段置 null。

---

### 29. PresetListManager.deleteEditor 不删 vertical strut

**文件**: `ui/PresetListManager.kt:93` 添加,`:106` 删除

`addEditorRow` 给每个 editor 后 `add(Box.createVerticalStrut(4))`,但 `deleteEditor` 只 `presetsInner.remove(editor)`,strut 残留 → 多次增删后留下不可见空白。

**修复**: 用 wrapper panel 把 editor + strut 包成一个组件,删除时整块移除;或在 deleteEditor 里找到 editor 的 component index,同时删除其后的 strut。

---

### 30. GitOps `timeoutSeconds * 1000` 整数溢出风险

**文件**: `git/GitOps.kt:21`

```kotlin
val out = handler.runProcess(timeoutSeconds * 1000)
```

`timeoutSeconds` 来自 `BranchSwitcherService.options.timeoutSeconds`,是持久化的 XML 字段。手改成 3_000_000 → `* 1000` Int 溢出成负数,timeout 行为不可预期。

**修复**: 加载时 `timeoutSeconds.coerceIn(1, 3600)`;或 `runProcess(timeoutSeconds.toLong() * 1000L)` 但 IntelliJ API 可能本就是 int millis,coerce 更稳。

---

## 总结(Part 2)

- **优先修(数据风险)**: #7-#11 — 这五条会导致用户分支被污染、改动丢失或 NPE,任何线上版本都应该先修这一组再发。
- **接着修(资源 / EDT / UX)**: #12-#19 — coroutine cancel + EDT 违例是潜在偶发崩溃源,#15-#19 是用户能立刻感知的功能 bug。
- **可分批修**: #20-#25 medium 项可以分批跟随重构;#26-#30 是清理和最佳实践,顺手做。

**审查方法学说明**: Part 2 的 30 项中,#7-#11 + #16 由我读 SwitchExecutor.kt / DirtyHandlingStep.kt / CheckoutStep.kt / PullStep.kt / GitOps.kt / PresetConfig.kt / PresetListManager.kt 直接核对成立;#12-#15、#17-#30 来自 5 个独立 reviewer 投票,主模型未逐条进入实现层验证,实施前再读一遍确保不是误报。

