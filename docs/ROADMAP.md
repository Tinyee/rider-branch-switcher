# Submodule Branch Switcher — 需求 / 路线图

**当前版本 0.7.0**，已具备：

- 多 preset 持久化（JSON），UI 内增删 preset 与子模块行（基于 `.gitmodules`）
- 一键切换主仓 + 子模块；脏工作区三策略（stash / skip / force）；切换前 fetch；切换后 pull --ff-only；切换后 VCS 自动刷新
- 分支下拉输入即过滤
- **当前命中预设高亮 + 切换按钮自动禁用**
- **切换前 Dry-run 预览表**：每仓 `当前 → 目标`、dirty 计数、远端是否存在、新建/已存在标记
- **主仓切完自动 `submodule sync`，缺失子模块自动 `submodule update --init`**
- **「从当前状态新建 preset」一键录入**
- **关键失败走 IDE Notification** + Exception Analyzer 自动上报
- **每个 preset 头部显示主仓 diff 标签**
- **部分失败回滚**：切前 checkpoint，失败通知带「回滚到切换前」按钮
- **GitOps 超时可配置 + 取消检查**
- **切换选项持久化**：dirty/fetch/pull/timeout + **历史记录** 存 branch-switcher.xml，IDE 重启保留
- **自动检测外部分支变更**：切回插件窗口时自动刷新 preset 匹配状态
- **stash 自动 pop**：切回原分支时自动 git stash pop
- **进度可视化**：进度条显示步骤名 + 仓名 + 分数
- **快捷键 Action**：Ctrl+Alt+B 弹出 preset 列表快速切换
- **派生功能分支**：基于 preset 一键 checkout -b 到所有仓库
- **Settings 页面**：File → Settings → VCS → Submodule Branch Switcher
- **动态远端名**：自动检测 remote 名，不再硬编码 origin
- IntelliJ 原生图标（AllIcons），主题感知色
- i18n 中英双语（DynamicBundle + @PropertyKey 编译时校验）
- 279 测试 / 27 个测试类（139 个 core pure JVM + 140 个平台/集成；含 6 个 Kotest 属性测试）
- GitHub Actions CI（ubuntu/macOS/Windows）+ Qodana 静态分析

下面按「切换体验 / 状态可视化 / UI / 工作流 / 质量」五块梳理后续要做的功能点，优先级 **P0(致命) / P1(高价值) / P2(锦上添花)**；状态列标记 v0.x 已落地或下阶段候选。

## 切换体验

| 优先级 | 需求 | 现状缺什么 | 状态 |
|---|---|---|---|
| P0 | **Dry-run 预览** | 每仓 `当前分支 → 目标分支`、是否 dirty、stash 会动多少文件、远端有/无目标分支 | ✅ v0.2 |
| P0 | **部分失败回滚** | 切前 checkpoint 记录 branch+SHA, 失败通知带回滚 action, 回滚优先恢复分支 | ✅ v0.3 |
| P0 | **submodule 处理** | 主仓切完跑 `git submodule sync`,缺失子模块跑 `git submodule update --init -- <path>` | ✅ v0.2 |
| P1 | **stash 自动 pop** | Stash 模式只 push 不 pop。切回原分支时要手工 `git stash list/pop`。可加「记住 stash → 切回时自动 pop」 | ✅ v0.4 |
| P1 | **进度可视化** | `Task.Backgroundable` 用 indeterminate，看不到「5 个仓的第 3 个」。改 `indicator.fraction + text2` | ✅ v0.4 |
| P1 | **可取消** | 进度条有取消按钮但 `SwitchExecutor` 循环里不查 `indicator.isCanceled`，点了没用 | ✅ v0.3 |
| P2 | **未 init 子模块识别** | v0.2 已能自动 init，`confirmBeforeInit` 设置项已提供确认开关 | ✅ v0.6 |

## 状态可视化

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **「我现在在哪个 preset」高亮** | 命中的 preset 左侧加色条 + 「当前」副标题 + 切换按钮禁用 | ✅ v0.2 |
| P0 | **每个 preset 头部显示主仓 diff** | 头部应并排显示 `当前分支 → preset.main`，不一致时染色，而不是必须展开才看得到 | ✅ v0.3 |
| P1 | **行级状态点** | 每个子模块行左侧一个圆点：绿=已匹配 / 黄=分支对但有 dirty / 红=不匹配 / 灰=未 init | ✅ v0.4 |
| P1 | **切换中状态贴在 ToolWindow tab 上** | 切换时 stripe icon 加 spinner、迷你状态条。现在切换时面板有 icon 变化 | ✅ v0.5 ToolWindow 图标显示切换中状态 |
| P2 | **顶部「当前主仓分支」常驻显示** | 不用展开任何东西就能看到主仓在哪 | ✅ v0.4 |

## UI

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **去硬编码颜色** | 改用 `JBColor.border()` / `JBUI.CurrentTheme.Link.Foreground.ENABLED` / `NamedColorUtil.getErrorForeground()` 等主题感知色 | ✅ v0.2 |
| P0 | **替换 ▶/▼/✕/✓ 字符为 IntelliJ Icons** | `AllIcons.General.ArrowRight/ArrowDown/Add/Remove`、`AllIcons.Actions.MenuSaveall/Rollback/Cancel/Execute/Refresh/EditSource/GC` | ✅ v0.2 |
| P1 | **错误用 Notification 弹** | 切换失败 / 预设解析失败 / VCS 刷新失败,使用 `NotificationGroupManager` 在 IDE 右下角弹气泡 | ✅ v0.2 |
| P1 | **空状态占位** | 第一次打开/找不到 git root/没有 preset 时，中央应该有大字提示 + CTA 按钮，不是只在日志输出 | ✅ v0.4 |
| P1 | **日志区染色 + 折叠** | 改用 IntelliJ `ConsoleView` 替代 `JTextArea`，INFO/WARN/ERROR 自动染色，带搜索 | ⚡ JTextPane+颜色匹配 (ConsoleView 需 execution 模块, Rider SDK 不含) |
| P1 | **行布局对齐** | 主仓/子模块 combo 和右侧 ✕ 按钮在不同字号下对不齐。统一用 `GridBag` 或 `MigLayout`，140px 硬编码改成 `JBUI.scale` | ✅ v0.5 JBUI.scale + JBUI.Borders |
| P2 | **预设拖拽排序** | 多 preset 时只能按 JSON 顺序，加 drag handle | ✅ v0.5 ↑↓ 按钮 |
| P2 | **窄宽自适应** | tool window 拖窄时按钮换行/截断。响应式收起次要按钮到 ⋯ 菜单 | ✅ v0.5 minimumSize 防截断 |

## 工作流

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **「从当前状态新建 preset」** | 后台读主仓 + `.gitmodules` 全集的 HEAD，detached 拒绝主仓、跳过子模块；输入框默认填主仓分支名 | ✅ v0.2 |
| P1 | **派生功能分支** | 选某 preset → 输入分支名 `feature/xxx` → 主仓和所有子模块同时 `checkout -b feature/xxx`，基于 preset 的 base。Unity feature 流极常见 | ✅ v0.4 |
| P1 | **预设重命名** | 现在改名要手工编辑 JSON | ✅ v0.4 |
| P1 | **快捷键** | `Tools → Branch Switcher → 切到 X` 注册成 Action，可绑快捷键 (Ctrl+Alt+B) | ✅ v0.4 |
| P1 | **导入/导出** | 团队成员手工拷贝 JSON。给「导出到剪贴板/导入」按钮 | ✅ v0.4 |
| P2 | **右键菜单** | preset / 子模块行没 context menu。子模块行右键应有「在 Finder 打开」「跳转到 Git tool window」「仅切此一个」 | ✅ v0.4 |
| P2 | **历史记录** | 最近 5 次切换记录，可「撤销到上一次切换之前」 | ✅ v0.4 |

## 质量

| 优先级 | 需求 | 状态 |
|---|---|---|
| P0 | GitOps 60s 超时:子模块多/网络慢会卡 UI。需要可配置 + 真异步(目前 Thread + invokeLater,够用但不可中断) | ✅ v0.3 |
| P1 | **单元测试**:GitOps / SwitchExecutor 都没有。mock GitOps 跑 SwitchExecutor 至少覆盖「主仓成功子模块失败」的 case | ✅ 287 用例, mock GitClient, cmd 可跑 |
| P2 | **i18n** | ✅ Strings.kt → Bundle.message() 接入完毕，@PropertyKey 编译时校验，中英 properties 各 ~135 key |
| P2 | **git worktree 兼容**:副工作树会失败,需要友好提示 | ✅ v0.5 检测 .git 文件并输出提示 |

## v0.2 已交付（按合入顺序）

1. 当前命中预设高亮 + 切换按钮禁用 + 自动检测
2. 切换前 Dry-run 预览表
3. VCS 后台刷新（避免切完高亮不刷新）
4. 主仓切完自动 `submodule sync`、缺失子模块自动 `submodule update --init`
5. 按钮 focus ring 残留修复（action / 松手 / disable / ToolWindow 显示四个时机都覆盖）
6. 去硬编码颜色，改主题感知色
7. 字符 ▶/▼/✓/⟲/+/✕ 替换为 AllIcons
8. 关键失败走 IDE Notification
9. 「从当前状态」一键新建 preset

## v0.3 已交付

2026-06-06 三项 P0 全部完成：

1. ✅ **每个 preset 头部显示主仓 diff** — PresetEditor header 加 `当前分支 → preset.main` 橙色染色标签
2. ✅ **部分失败回滚** — SwitchExecutor 切前 checkpoint + Notifier 带「回滚到切换前」action
3. ✅ **GitOps 60s 超时可配置 + 真异步** — 面板超时选项 (30/60/120/300s), indicator 传入 SwitchContext, 各 Step 循环内 checkCanceled

后续按 P1 / P2 滚动。

## v0.4 已交付

2026-06-07 SDK 规范对齐 + 代码审查收尾：

1. ✅ **SwingUtilities.invokeLater → Application.invokeLater** — 13 处替换，使用 IntelliJ modality 系统
2. ✅ **plugin.xml 去冗余** — 移除 `@Service` 已覆盖的 projectService 声明
3. ✅ **Action text i18n** — `<action text="...">` 改用 resource-bundle key
4. ✅ **CoroutineScope 平台注入** — BranchSwitcherService 构造函数接收平台 CoroutineScope
5. ✅ **@Nls 注解** — Bundle.msg 返回值 + Notifier 方法参数
6. ✅ **UiUtil 用 Application.invokeLater** — 替代裸 Swing Timer/Thread
7. ✅ **Bundle 改用 DynamicBundle 官方 public API** — `getResourceBundle(classLoader, path)` + IDE locale 感知
8. ✅ **Context menu bug fix** — SubmoduleRowManager 右键"仅切此一仓"使用 preset 目标分支
9. ✅ **i18n 补完** — ~20 处硬编码中文 → Bundle, 中英 properties 各 ~105 key
10. ✅ **UI / i18n 测试** — BundleTest + UI 纯规则测试；低价值 SubmoduleRowManager 结构测试已清理

2026-06-06 P1 + 架构改进：

1. ✅ **stash 自动 pop** — DirtyHandlingStep 记录，CheckoutStep checkout 后自动 git stash pop
2. ✅ **进度可视化** — indicator.isIndeterminate=false，各 Step 更新 fraction + text2
3. ✅ **快捷键 Action** — Ctrl+Alt+B / Tools → 切到 Preset，MessageBus 通知面板刷新
4. ✅ **派生功能分支** — PresetEditor「派生分支」按钮，所有仓库同时 checkout -b
5. ✅ **coroutines 异步** — detectCurrentState 用 service.scope.launch 替代 Thread，Service 实现 Disposable
6. ✅ **单元测试文件** — SwitchExecutorTest (mock GitClient)，待配 testFramework 后可在 Rider 运行
7. ✅ **显示真实项目名** — 进度条和日志用实际目录名替代硬编码 `<main>`
8. ✅ **消除刷新闪烁** — setHighlighted 只 repaint，revalidate 统一批处理
9. ✅ **代码审查修复** (2026-06-07, 8 项) — preflight 不丢弃, sync 失败上报, Thread→coroutines, resolveDir 去重, Strings.kt 接入, 依赖注入, pipeline 可配置

## 架构 / 设计债

2026-06-05 代码审查后梳理的结构问题, 2026-06-06 完成三波重构。

### 主要问题

| 优先级 | 问题 | 影响 | 状态 |
|---|---|---|---|
| P0 | `BranchSwitcherPanel` 是 god class（419 行），`PresetFile` 状态住在 UI 里 | Action / 状态栏 widget 拿不到 preset；ToolWindow 关掉 state 重新 load | ✅ v0.2.2 |
| P0 | `GitOps` 是 object，不可 mock | ROADMAP P1「单元测试」无法落地 | ✅ v0.2.2 |
| P0 | `SwitchExecutor` 一个 130 行 `execute` 串了所有步骤 | P0「部分失败回滚」需要 checkpoint，无 step 抽象就没法切入 | ✅ v0.2.2 |
| P0 | 没有 `BranchSwitcherService`（Project Service） | 状态、CRUD、监听全没地方放 | ✅ v0.2.2 |
| P1 | `PresetEditor` 是 god view（约 500 行） | 加拖拽/复制/导出/重命名只能继续塞这个文件 | ✅ v0.5 拆分 SubmoduleRowManager(265行), PresetEditor→397行 |
| P1 | 异步 API 四种混用（Thread / pooledThread / Task.Backgroundable / Task.Modal） | cancel/进度/错误处理语义不一致 | ✅ v0.5 全部统一为 `scope.launch`, TaskBridge 封装底层 |
| P1 | `Preset` 没有稳定 ID | 重命名后历史 / 快捷键绑定 / 颜色标签都断 | ✅ v0.6 Preset.id UUID |
| P1 | 切换选项（dirty / fetch / pull）不持久化 | IDE 重启重置 | ✅ v0.2.2 |
| P1 | 没有 EventBus / Listener 模式 | 加任何派生组件都得回头改 Panel | ✅ v0.4 (BranchSwitchListener) |
| P1 | `GitOps` 用 CLI fork 而非 git4idea API | 慢 + 依赖 PATH | ⚡ v0.6 cancel 已解决, git4idea 迁移仍暂缓 |
| P2 | 包结构扁平（`com.submodule.branchswitcher` 全平铺，11 个文件） | 加新功能继续平铺会变难找 | ✅ v0.2.2 |
| P2 | 中英文硬编码，无 `BundleMessage` | i18n 时机械迁移 | ✅ v0.5 ResourceBundle(en/zh) + Bundle.kt |
| P2 | `noFocusRing()` 每个按钮手动调，容易漏 | 应该工厂化或全局 LAF | ✅ v0.6 `jButton()` 工厂 |
| — | `TaskBridge` 底层仍用 `Task.Backgroundable` | 见下方决策记录 | ✅ 有意保留 |

### TaskBridge 决策记录 (2026-06-07)

`TaskBridge.runModal` 和 `runBackground` 对外暴露 suspend 函数，但内部实现使用了
`ProgressManager.runProcessWithProgressSynchronously` 和 `Task.Backgroundable`。

**为什么不用 IntelliJ 2024.1+ 的 coroutine-based 进度 API (`com.intellij.platform.ide.progress.*`)：**

1. **Task API 未被标记 deprecated** — `Task.Backgroundable` 和 `Task.Modal` 在 IntelliJ 2024/2025/2026
   源码中均无 `@Deprecated`，绝大多数 JetBrains 官方插件（Git4Idea、Database Tools 等）仍在使用。

2. **新 API 版本间不稳定** — `com.intellij.platform.ide.progress` 包在 2024.1→2024.3→2025.1
   之间多次变更签名，Rider 不同版本可能不可用。而 `Task.Backgroundable` 自 2015 年至今
   接口未变。

3. **封装隔离已完成** — 6 个调用方只看到 `suspend fun runModal/runBackground`，
   底层实现完全透明。将来新 API 稳定后，只需改 `TaskBridge.kt` 一个文件。

4. **风险收益不匹配** — 改为不稳定 API 需要重新验证所有 93 个测试、手动测试进度对话框
   行为、处理 Rider 不同版本的兼容性，收益仅为去掉一个内部实现细节。

**结论**：等 `com.intellij.platform.ide.progress` 在 2-3 个大版本内保持 API 稳定后，
再考虑替换 `TaskBridge` 内部实现。

### 可扩展性现状

| 扩展方向 | 难度 | 卡在哪 |
|---|---|---|
| 新切换动作（rebase / tag / commit） | **低** | SwitchExecutor 构造函数接受 steps 列表 |
| Tools 菜单 / 快捷键 | **低** | v0.4 已实现 Ctrl+Alt+B + MessageBus |
| 状态栏 widget | **中** | 同上 |
| 单元测试 | **低** | 287 用例已配通, `./gradlew :core:test test` |
| 多 VCS（hg / p4） | 高 | GitOps 直接绑 git |
| 切换历史 / 撤销 | 低 | v0.4 已实现 |
| Preset 拖拽/复制/导入导出 | 中 | PresetEditor 已胖，但加按钮可行 |
| git worktree | 中 | 假设 `.git` 是 dir/file |
| i18n | 低 | Strings.kt 常量化, 替换硬编码即可 |

### v0.2.2 已交付 — 架构重构

2026-06-06 按以下顺序完成：

**Wave 1: 打通单测 + 重构基础**
1. ✅ `GitClient` 接口 — `GitOps` 实现, 4 个消费者构造注入
2. ✅ 包重组 — `git/` `model/` `ui/` `service/` `switch/` 五个子包
3. ✅ `BranchSwitcherService` — Project Service, 状态从 Panel 移入, 选项持久化到 `branch-switcher.xml`

**Wave 2: 为 v0.3 P0 铺路**
4. ✅ `SwitchStep` 管道 — 5 个 Step 类 (Dirty/Fetch/Checkout/Pull/Sync), SwitchExecutor 130 行缩为 30 行编排
5. ~~`Preset.id`~~ — 已移除; Gson 绕过 Kotlin 默认参数导致 null, 待有实际使用场景时配合迁移策略重新加入

**Wave 3: 扩展面（预留接口）**
6. ✅ PersistentStateComponent — 切换选项持久化
7. — MessageBus topic（未实施, 等有派生组件时再加）
8. — plugin.xml Action 框架（未实施）

**包结构 (19 文件)**

```
com.submodule.branchswitcher/
├── git/          GitClient (接口) + GitOps (实现)
├── model/        Preset/PresetFile/DirtyAction/SwitchOptions/PreflightRow
├── service/      BranchSwitcherService (Project Service)
├── switch/       SwitchStep + 5个Step + SwitchExecutor + SwitchPreflight
├── ui/           Panel/Editor/Dialog/Factory/UiUtil
├── Notifier.kt
└── PresetLoader.kt
```

### 待重构路径（剩余）

**必要重构（已实施）**：Wave 1—3 核心项全部完成。

**第四波 — 2026-06-07 代码审查修复 (8 项)**：

9. ✅ **SwitchPresetAction** — preflight 结果不再丢弃, log 回调捕获错误附到通知
10. ✅ **SubmoduleSyncStep** — 失败时返回 Partial（与 Fetch/Pull 一致）
11. ✅ **PresetEditor Thread→coroutines** — 3 处 `Thread{}.start()` 替换为 `scope.launch`, 注入 CoroutineScope
12. ✅ **detectCurrentState 防过期** — invokeLater 回调校验 `editor in editors`
13. ✅ **resolveGitDir/isGitRepo 去重** — 提取到 SwitchStep.kt 顶层, 删除 6 处重复
14. ✅ **Strings.kt 接入 UI** — 按钮/标签/提示替换为常量, 68→20+ 引用
15. ✅ **移除默认 GitOps()** — PresetEditor/SwitchExecutor/SwitchPreflight 强制注入
16. ✅ **SwitchExecutor pipeline 可配置** — steps 改为构造函数参数

**第五波 — 投入产出比变低，等待有需求再做**：

17. git4idea API 迁移 — 替代 CLI fork，原生 cancel + 更快
18. i18n `Bundle.message()` — 当前 Strings.kt 常量化已足够

**未列入计划的低优先级**：

19. `noFocusRing()` 工厂化 / 全局 LAF

---

## v0.6 候选 — 2026-06-07 全面代码审查（50 项发现）

三个维度同时扫描代码库（逐行 diff + 调用链追踪 + 并发/资源审查），发现 24 Bug + 18 质量问题 + 8 功能缺口。
以下是按优先级排列的关键项，完整的 50 项列表见底部。

### 🔴 致命 Bug（v0.6 第一波优先）

| # | 文件:行 | 问题 | 后果 |
|---|---------|------|------|
| 1 | `TaskBridge.kt:49-67` | `onCancel()` 后 `onFinished()` 再次 `cont.resume(Unit)` | 取消任务时 double-resume 崩溃 |
| 2 | `SwitchPresetAction.kt:67` | 硬编码 `DirtyAction.Stash`，忽略用户偏好 | Ctrl+Alt+B 切换时脏文件被意外 stash |
| 3 | `SwitchController.kt:206` | ToolWindow ID 走 `Bundle.msg()` 查 i18n key | 切 IDE 语言后 `getToolWindow()` 找不到 |

### 🟡 高价值修复（v0.6 第一波）

| # | 文件:行 | 问题 | 建议 |
|---|---------|------|------|
| 4 | `Bundle.kt:22` | `msg()` 无编译时 key 校验 | 加 `@PropertyKey(resourceBundle = PATH)` |
| 5 | `BranchSwitcherPanel.kt:85-89` | 日志 `JTextPane` 从不裁剪 | 限制 5000 行，超出 trim 前半 |
| 6 | `BranchSwitcherPanel.kt:250-255` | messageBus 连接挂在 service 生命周期而非 panel | 改用 `Disposable` 模式，panel 销毁时断开 |
| 7 | `BranchSwitcherService.kt:88` | 每次访问 `gitClient` 都 `new GitOps()` | 缓存实例，仅在 timeout 变更时重建 |
| 8 | `PresetEditor.kt:232-255` + `SubmoduleRowManager.kt:223-246` | `loadComboBranches` 重复两份 | 提取到 `BranchComboUtil.kt` |

### 🟢 中等优先级（后续做）

| # | 文件:行 | 问题 | 建议 |
|---|---------|------|------|
| 9 | `SwitchPresetAction.kt:69-71` / `SwitchController.kt:84-86` | `catch (_: Exception) { ok = false }` 吞掉异常信息 | 记录 `e.message` 到日志或通知 |
| 10 | `BranchSwitcherService.kt:139-141` | `detectGen: Long` 无 `@Volatile`，理论上 32-bit JVM 有数据竞争 | 加 `@Volatile` 或改用 `AtomicLong` |
| 11 | `PresetListManager.kt:253-259` | 导入时名字全部冲突仍报 "imported N preset(s)" | 统计实际成功导入数 |
| 12 | `PresetEditor.kt:369-381` | 重命名不检查重名 | 复用 `newNameValidator()` |
| 13 | `PresetListManager.kt:225-228` | 导出剪贴板无异常处理 | wrap try-catch，失败时通知用户 |
| 14 | `SubmoduleRowManager.kt:251-265` | 右键"仅切此一仓"不检查 dirty、不支持 remote-only 分支 | 加 dirty 检查 + `checkoutFromRemote` 回退 |
| 15 | `CheckoutStep.kt:37-44` | `invokeAndWait` 弹确认框时取消不生效 | 弹框前检查 `indicator.isCanceled` |
| 16 | `PresetEditor.kt` | ComboBox 索引用 0/1/2/3 魔法数字 | ✅ 已修复：`triStateFromCombo`/`triStateToCombo` helper 函数 |
| 17 | `GitOps.kt:58,64,67,109` | 硬编码 `origin` 远端名 | 检测实际 remote 名或使用 `git branch -a` |
| 18 | `PresetLoader.kt:82-84` | 原子写入的临时文件在非 `AtomicMoveNotSupportedException` 异常时泄漏 | 加 `finally` 清理 |
| 19 | `BranchSwitcherService.kt:143-144` | `setCurrentBranches`/`getCurrentBranches` 无调用方 | 加调用方或删除死代码 |
| 20 | `BranchSwitcherPanel.kt:77-78` | checkbox 初始值硬编码 `true`，与 service 持久值可能短暂不一致 | 直接从 `service` 取值初始化 |
| 21 | `SwitchPreviewDialog.kt:49` | 列宽用像素硬编码 `180/140/140/90/110` | 改用 `JBUI.scale()` |
| 22 | `BranchSwitcherService.kt:34` | 切换历史不持久化（IDE 重启丢失） | 加入 `OptionsState` |
| 23 | `BranchSwitcherService.kt:117-124` | `addHistory` 两次创建中间列表 | 用 `ArrayDeque` 优化 |
| 24 | `SwitchController.kt:67-79` | ProgressIndicator delegate 只覆写 `setFraction`/`setText2` | 补 `setText`/`setIndeterminate`/`checkCanceled` |
| 25 | `BranchSwitcherPanel.kt:337` | `private fun runSwitch()` 死代码 | 删除 |

### 功能缺口

| # | 需求 | 说明 | 状态 |
|---|------|------|------|
| 26 | Settings 页面 | 用户无法通过 File→Settings 配置超时/策略，只能在面板里改 | ✅ v0.6 |
| 27 | 状态栏 widget | 切换后无常驻指示当前 preset，类似 Git branch widget | ⏸️ SDK 兼容性 |
| 30 | 嵌套子模块 | `listSubmodulePaths` 递归解析嵌套 `.gitmodules`，前缀路径拼接 | ✅ v0.7 |
| 32 | git 不在 PATH 的友好提示 | `GeneralCommandLine("git", ...)` 失败时无提示 | ✅ v0.6 |

---

## v1.0 路线图 — Marketplace 发布准备工作

参考 [JetBrains Marketplace 审核指南](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html) (v1.3, 2026-03)、[SDK DevGuide](https://plugins.jetbrains.com/docs/intellij) 和高质量插件实践。

### P1 — Marketplace 上架必要条件

| # | 事项 | 工作量 | 说明 | 状态 |
|---|------|--------|------|------|
| M1 | 升级 IntelliJ Platform Gradle Plugin 2.2.1 → 2.10+ | 低 | 新版兼容 Kotlin 2.3.0，自带更严格的 Plugin Verifier | ⏸️ 与 Rider local SDK 不兼容 |
| M2 | CI 加 `verifyPlugin` | 低 | 自动检测二进制不兼容和 `@ApiStatus.Internal` 使用 | ✅ v0.6 |
| M3 | 修复 `<vendor>` 信息 | 极低 | 改为真实 vendor（url + email），否则审核不通过 | ✅ v0.6 |
| M4 | 加 Exception Analyzer | 极低 | `plugin.xml` 加一行 `<errorHandler>`，崩溃自动上报 Marketplace | ✅ v0.6 |
| M5 | 插件图标 | 低 | 40×40 SVG，不模仿 JetBrains 产品 logo | ✅ v0.6 |
| M6 | 英文描述 + 截图 | 中 | 1280×800 (16:10)，不带设备边框；描述已更新到 plugin.xml，README 已嵌入 Tool Window、Dry-run 和 Settings 三张截图 | ✅ v0.7 |
| M7 | CI 加 Qodana/InspectCode | 低 | 静态分析在每次 push 自动跑 | ✅ v0.6 |

### P2 — 生产级打磨

| # | 事项 | 工作量 | 说明 | 状态 |
|---|------|--------|------|------|
| M8 | Settings Configurable | 中 | File→Settings→Version Control 下注册配置页 | ✅ v0.6 |
| M9 | 结构化日志 | 中 | `com.intellij.openapi.diagnostic.Logger` 替代 lambda `log()` | ✅ v0.6 `AppLogger` |
| M10 | 动态插件兼容 | 中 | 确保 service.dispose() 取消协程、不泄漏 classloader | ✅ v0.6 |
| M11 | Bundle 加 `@PropertyKey` | 低 | 编译时校验 key 有效性 | ✅ v0.6 |

### P3 — 锦上添花

| # | 事项 | 说明 | 状态 |
|---|------|------|------|
| M12 | 首次安装提示 | 无预设空状态已增加 Quick Start、Ctrl+Alt+B 和团队共享提示 | ✅ v0.6 |
| M13 | 大仓规模测试 | 已覆盖 50 目标仓库 Switch/Preflight Git 调用预算（`LargeRepoScalabilityTest`）+ 真实耗时基准（`./gradlew benchmark`，独立 task，51 个预设目标目录真实 GitOps wall-clock） | ✅ v0.7 |

---

## 测试策略（个人开发者）

### 当前状态

- ✅ 279 测试，27 个测试类：`./gradlew :core:test` 跑 139 个 core 纯 JVM 测试，`./gradlew test` 跑 140 个平台/集成测试
- ✅ `GitClient` 接口 + Fake 实现 → 架构已隔离 IntelliJ 运行时
- ✅ 真实 git 临时仓库集成测试（`SwitchIntegrationTest`）
- ✅ 50 目标仓库 Switch/Preflight Git 调用预算测试（`LargeRepoScalabilityTest`，counting fake）
- ✅ 大仓真实耗时基准（`./gradlew benchmark`，独立 Gradle task，51 个独立 git 仓库真实 GitOps wall-clock）
- ✅ GitHub Actions CI（ubuntu/macOS/Windows）+ Detekt + quickCheck + checkQuickCheck + verifyPlugin
- ✅ `quickCheck`：7 条 grep 结构性检查 + `checkQuickCheck` 自测（5 fixture）
- ⚠ 已覆盖 UI 规则与 Swing 几何约束，尚无 Rider fixture / 截图测试
- ✅ `TaskBridge.runBackground` 生命周期已覆盖（`TaskBridgeLifecycleTest`，9 用例）

### 推荐方案（按投入排序）

**1. GitHub Actions CI** ✅

三平台 CI（ubuntu/macOS/Windows）+ test + buildPlugin + detekt + quickCheck + checkQuickCheck + verifyPlugin + artifact upload。

**2. Kotest 属性测试** ✅

`io.kotest:kotest-runner-junit5:5.9.1` + `io.kotest:kotest-property:5.9.1`

5 个不变性：Preset JSON 往返、.gitmodules 解析鲁棒性、GitResult.ok 契约、PreflightRow 可计算属性、子模块路径提取。

**3. 结构性检查** ✅

`quickCheck`（7 条规则）+ `checkQuickCheck`（5 fixture 自测），pre-commit hook 自动运行。

**4. PITest 变异测试** ✅

`id("info.solidsoft.pitest") version "1.19.0"` → `./gradlew pitestCore`。

当前只覆盖纯规则/决策逻辑（SettingsRules、BranchNameRules、DeriveNotification、PresetImportRules、UiRules），
单线程手动运行，不进入 `test` / `releaseCheck`。2026-06-19 结果：80 mutations / 79 killed / 99%，
剩余 1 个 Kotlin lambda 等价噪音。

**5. 手工 Release Checklist（每次发版 5-10 分钟）**

```
□ 切换核心: 创建/切换/回切/三策略(stash/skip/force)
□ 边界: 缺失子模块init / 目标分支不存在 / 取消 / 无remote
□ UI: 状态点颜色 / Ctrl+Alt+B / IDE重启持久化 / Notification
□ 数据: 无效JSON不崩溃 / 空状态 / 历史恢复
```

### 不推荐（ROI 低）

- ❌ IntelliJ 轻量测试框架 — 对 tool window 插件 setup 太复杂
- ❌ UI 快照测试 — 基础设施重，手工更灵活
- ❌ 复杂发布流水线 — `./gradlew buildPlugin` + 手动上传够用到 10+ 用户

---

## v0.6 已交付 — 2026-06-07（50→34 已修复）

### 第一波 — 3 Bug + 4 质量 (278f1a5)
1. ✅ **Bug1** — TaskBridge onFinished 加 cont.isActive 防 double-resume
2. ✅ **Bug2** — SwitchPresetAction DirtyAction 读 service 配置
3. ✅ **Bug3** — SwitchController toolWindow ID 改用字面常量
4. ✅ **Q1** — Bundle.msg 加 @PropertyKey 编译时校验
5. ✅ **Q2** — 日志 JTextPane 限制 5000 行
6. ✅ **Q3** — gitClient 加缓存
7. ✅ **Q4** — 删除 BranchSwitcherPanel 死代码

### 第二波 — CI + Kotest (ba9405e)
8. ✅ **CI** — test.yml 加 test results + plugin artifact 上传
9. ✅ **Kotest** — 6 个属性测试 (131 tests total)

### 第三波 — 8 项 Bug/质量修复 (5748ce9)
10. ✅ **Bug5** — catch 块记录异常信息
11. ✅ **Bug11** — PresetLoader.save 改 try/finally 清理临时文件
12. ✅ **Bug7+8** — 右键菜单加 dirty 检查 + checkoutFromRemote 回退
13. ✅ **Bug18** — CheckoutStep 确认框前检查 isCanceled
14. ✅ **Bug28** — PresetEditor.rename 加 nameValidator 防重名
15. ✅ **Q4+5** — 删除死代码 + GitOps 去冗余 filter
16. ✅ **Q7** — SwitchPreviewDialog 列宽 JBUI.scale()
17. ✅ **Q9** — PresetLoader.DEFAULT_JSON 序列化真实 PresetFile()

### 第四波 — 11 项 (1acbe33)
18. ✅ **B3** — import 计数用实际导入数
19. ✅ **B4** — export 加 try-catch
20. ✅ **B7** — checkbox 初始值从 service 取
21. ✅ **Q5** — History 持久化到 OptionsState
22. ✅ **M3+M4** — vendor + errorHandler
23. ✅ **B5** — messageBus 注释
24. ✅ **B8** — 动态 remoteName()
25. ✅ **Q3** — loadComboBranches 提取
26. ✅ **F1** — Settings Configurable
27. ✅ **M1** — Gradle 8.13
28. ✅ **M2** — CI verifyPlugin

### 第五波 — 5 项快速收尾 (17a844f)
29. ✅ **B2** — SwitchPresetAction preflight 警告弹确认框
30. ✅ **Q9** — addPreset 加预设加载守卫
31. ✅ **B23** — ProgressIndicator delegate 补 setText/setIndeterminate
32. ✅ **Q6** — isGitRepo 加 rev-parse 验证
33. ✅ **Q10** — Preset.pull → pullEnabled + @SerializedName JSON 向后兼容

### 第六波 — 文档 + 工具 (5b48859)
34. ✅ **README.md** — 功能列表、安装方式、快速上手、JSON 格式、开发指南
35. ✅ **CHANGELOG.md** — v0.1.0 → v0.5.0 完整变更记录
36. ✅ **F7** — git PATH 检查（首次打开面板异步检测）
37. ✅ **Qodana** — GitHub Actions 静态分析

### 无法交付

- ⏸️ **F2 状态栏 widget** — Rider 2026.1 SDK `StatusBarWidget.TextPresentation.getClickConsumer()` 返回类型与 Kotlin 类型推断不兼容。
- ⏸️ **M1 (Gradle Plugin 2.10)** — IntelliJ Platform Gradle Plugin 2.10.0 与 Rider local SDK 不兼容 (#1852)，保持 2.2.1。
