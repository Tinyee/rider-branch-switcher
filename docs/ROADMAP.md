# Submodule Branch Switcher — 需求 / 路线图

**当前版本 0.5.0**，已具备：

- 多 preset 持久化（JSON），UI 内增删 preset 与子模块行（基于 `.gitmodules`）
- 一键切换主仓 + 子模块；脏工作区三策略（stash / skip / force）；切换前 fetch；切换后 pull --ff-only；切换后 VCS 自动刷新
- 分支下拉输入即过滤
- **当前命中预设高亮 + 切换按钮自动禁用**（v0.2）
- **切换前 Dry-run 预览表**：每仓 `当前 → 目标`、dirty 计数、远端是否存在、新建/已存在标记（v0.2）
- **主仓切完自动 `submodule sync`，缺失子模块自动 `submodule update --init`**（v0.2）
- **「从当前状态新建 preset」一键录入**：基于主仓与各子模块 HEAD 直接生成预设（v0.2）
- **关键失败走 IDE Notification**（预设解析失败 / 切换有失败项 / VCS 刷新失败，v0.2）
- **每个 preset 头部显示主仓 diff 标签**（v0.3）
- **部分失败回滚**：切前 checkpoint，失败通知带「回滚到切换前」按钮（v0.3）
- **GitOps 超时可配置 + 取消检查**：面板 30/60/120/300s 选项，Step 内 checkCanceled（v0.3）
- **切换选项持久化**：dirty/fetch/pull/timeout 存 branch-switcher.xml，IDE 重启保留（v0.3）
- **自动检测外部分支变更**：切回插件窗口时自动刷新 preset 匹配状态（v0.3）
- **stash 自动 pop**：切回原分支时自动 git stash pop（v0.4）
- **进度可视化**：进度条显示步骤名 + 仓名 + 分数（v0.4）
- **快捷键 Action**：Ctrl+Alt+B 弹出 preset 列表快速切换（v0.4）
- **派生功能分支**：基于 preset 一键 checkout -b 到所有仓库（v0.4）
- IntelliJ 原生图标（AllIcons），主题感知色，按钮焦点 ring 行为已修

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
| P2 | **未 init 子模块识别** | v0.2 已能自动 init,但仍可加「init 前先确认」开关而非默默 init | 部分 v0.2 |

## 状态可视化

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **「我现在在哪个 preset」高亮** | 命中的 preset 左侧加色条 + 「当前」副标题 + 切换按钮禁用 | ✅ v0.2 |
| P0 | **每个 preset 头部显示主仓 diff** | 头部应并排显示 `当前分支 → preset.main`，不一致时染色，而不是必须展开才看得到 | ✅ v0.3 |
| P1 | **行级状态点** | 每个子模块行左侧一个圆点：绿=已匹配 / 黄=分支对但有 dirty / 红=不匹配 / 灰=未 init | ✅ v0.4 |
| P1 | **切换中状态贴在 ToolWindow tab 上** | 切换时 stripe icon 加 spinner、迷你状态条。现在切换时面板有 icon 变化 | ✅ v0.5 面板内 JProgressBar 实时显示 |
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
| P1 | **单元测试**:GitOps / SwitchExecutor 都没有。mock GitOps 跑 SwitchExecutor 至少覆盖「主仓成功子模块失败」的 case | ✅ 61 用例, mock GitClient, cmd 可跑 |
| P2 | **i18n**:目前中英混杂,要么全中要么走 `Bundle.message()` | ⚡ Strings.kt 68 常量, UI 按钮/标签已接入 |
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
10. ✅ **UI 测试** — BundleTest(8) + SubmoduleRowManagerTest(5), 总测试 80→93

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
| P1 | `Preset` 没有稳定 ID | 重命名后历史 / 快捷键绑定 / 颜色标签都断 | — |
| P1 | 切换选项（dirty / fetch / pull）不持久化 | IDE 重启重置 | ✅ v0.2.2 |
| P1 | 没有 EventBus / Listener 模式 | 加任何派生组件都得回头改 Panel | ✅ v0.4 (BranchSwitchListener) |
| P1 | `GitOps` 用 CLI fork 而非 git4idea API | 慢 + 不响应 cancel + 依赖 PATH | — |
| P2 | 包结构扁平（`com.submodule.branchswitcher` 全平铺，11 个文件） | 加新功能继续平铺会变难找 | ✅ v0.2.2 |
| P2 | 中英文硬编码，无 `BundleMessage` | i18n 时机械迁移 | ✅ v0.5 ResourceBundle(en/zh) + Bundle.kt |
| P2 | `noFocusRing()` 每个按钮手动调，容易漏 | 应该工厂化或全局 LAF | — |
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
| 单元测试 | **低** | 61 用例已配通, `./gradlew test` |
| 多 VCS（hg / p4） | 高 | GitOps 直接绑 git |
| Per-preset 选项覆盖 | 中 | 数据模型加字段 + UI 改 |
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
