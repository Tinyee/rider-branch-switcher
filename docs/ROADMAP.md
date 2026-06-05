# Submodule Branch Switcher — 需求 / 路线图

**当前版本 0.2.1**，已具备：

- 多 preset 持久化（JSON），UI 内增删 preset 与子模块行（基于 `.gitmodules`）
- 一键切换主仓 + 子模块；脏工作区三策略（stash / skip / force）；切换前 fetch；切换后 pull --ff-only；切换后 VCS 自动刷新
- 分支下拉输入即过滤
- **当前命中预设高亮 + 切换按钮自动禁用**（v0.2）
- **切换前 Dry-run 预览表**：每仓 `当前 → 目标`、dirty 计数、远端是否存在、新建/已存在标记（v0.2）
- **主仓切完自动 `submodule sync`，缺失子模块自动 `submodule update --init`**（v0.2）
- **「从当前状态新建 preset」一键录入**：基于主仓与各子模块 HEAD 直接生成预设（v0.2）
- **关键失败走 IDE Notification**（预设解析失败 / 切换有失败项 / VCS 刷新失败，v0.2）
- IntelliJ 原生图标（AllIcons），主题感知色，按钮焦点 ring 行为已修

下面按「切换体验 / 状态可视化 / UI / 工作流 / 质量」五块梳理后续要做的功能点，优先级 **P0(致命) / P1(高价值) / P2(锦上添花)**；状态列标记 v0.x 已落地或下阶段候选。

## 切换体验

| 优先级 | 需求 | 现状缺什么 | 状态 |
|---|---|---|---|
| P0 | **Dry-run 预览** | 每仓 `当前分支 → 目标分支`、是否 dirty、stash 会动多少文件、远端有/无目标分支 | ✅ v0.2 |
| P0 | **部分失败回滚** | 主仓切完后某个子模块挂掉，状态不一致没法一键回去。需要事务式 checkpoint（切前记下每个仓的 HEAD，失败时给「回滚到切换前」按钮） | v0.3 候选 |
| P0 | **submodule 处理** | 主仓切完跑 `git submodule sync`,缺失子模块跑 `git submodule update --init -- <path>` | ✅ v0.2 |
| P1 | **stash 自动 pop** | Stash 模式只 push 不 pop。切回原分支时要手工 `git stash list/pop`。可加「记住 stash → 切回时自动 pop」 | — |
| P1 | **进度可视化** | `Task.Backgroundable` 用 indeterminate，看不到「5 个仓的第 3 个」。改 `indicator.fraction + text2` | — |
| P1 | **可取消** | 进度条有取消按钮但 `SwitchExecutor` 循环里不查 `indicator.isCanceled`，点了没用 | — |
| P2 | **未 init 子模块识别** | v0.2 已能自动 init,但仍可加「init 前先确认」开关而非默默 init | 部分 v0.2 |

## 状态可视化

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **「我现在在哪个 preset」高亮** | 命中的 preset 左侧加色条 + 「当前」副标题 + 切换按钮禁用 | ✅ v0.2 |
| P0 | **每个 preset 头部显示主仓 diff** | 头部应并排显示 `当前分支 → preset.main`，不一致时染色，而不是必须展开才看得到 | v0.3 候选 |
| P1 | **行级状态点** | 每个子模块行左侧一个圆点：绿=已匹配 / 黄=分支对但有 dirty / 红=不匹配 / 灰=未 init | — |
| P1 | **切换中状态贴在 ToolWindow tab 上** | 切换时 stripe icon 加 spinner、迷你状态条。现在切换时面板没有视觉信号 | — |
| P2 | **顶部「当前主仓分支」常驻显示** | 不用展开任何东西就能看到主仓在哪 | — |

## UI

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **去硬编码颜色** | 改用 `JBColor.border()` / `JBUI.CurrentTheme.Link.Foreground.ENABLED` / `NamedColorUtil.getErrorForeground()` 等主题感知色 | ✅ v0.2 |
| P0 | **替换 ▶/▼/✕/✓ 字符为 IntelliJ Icons** | `AllIcons.General.ArrowRight/ArrowDown/Add/Remove`、`AllIcons.Actions.MenuSaveall/Rollback/Cancel/Execute/Refresh/EditSource/GC` | ✅ v0.2 |
| P1 | **错误用 Notification 弹** | 切换失败 / 预设解析失败 / VCS 刷新失败,使用 `NotificationGroupManager` 在 IDE 右下角弹气泡 | ✅ v0.2 |
| P1 | **空状态占位** | 第一次打开/找不到 git root/没有 preset 时，中央应该有大字提示 + CTA 按钮，不是只在日志输出 | — |
| P1 | **日志区染色 + 折叠** | 改用 IntelliJ `ConsoleView` 替代 `JTextArea`，INFO/WARN/ERROR 自动染色，带搜索 | — |
| P1 | **行布局对齐** | 主仓/子模块 combo 和右侧 ✕ 按钮在不同字号下对不齐。统一用 `GridBag` 或 `MigLayout`，140px 硬编码改成 `JBUI.scale` | — |
| P2 | **预设拖拽排序** | 多 preset 时只能按 JSON 顺序，加 drag handle | — |
| P2 | **窄宽自适应** | tool window 拖窄时按钮换行/截断。响应式收起次要按钮到 ⋯ 菜单 | — |

## 工作流

| 优先级 | 需求 | 现状 | 状态 |
|---|---|---|---|
| P0 | **「从当前状态新建 preset」** | 后台读主仓 + `.gitmodules` 全集的 HEAD，detached 拒绝主仓、跳过子模块；输入框默认填主仓分支名 | ✅ v0.2 |
| P1 | **派生功能分支** | 选某 preset → 输入分支名 `feature/xxx` → 主仓和所有子模块同时 `checkout -b feature/xxx`，基于 preset 的 base。Unity feature 流极常见 | — |
| P1 | **预设重命名** | 现在改名要手工编辑 JSON | — |
| P1 | **快捷键** | `Tools → Branch Switcher → 切到 X` 注册成 Action，可绑快捷键 | — |
| P1 | **导入/导出** | 团队成员手工拷贝 JSON。给「导出到剪贴板/导入」按钮 | — |
| P2 | **右键菜单** | preset / 子模块行没 context menu。子模块行右键应有「在 Finder 打开」「跳转到 Git tool window」「仅切此一个」 | — |
| P2 | **历史记录** | 最近 5 次切换记录，可「撤销到上一次切换之前」 | — |

## 质量

| 优先级 | 需求 | 状态 |
|---|---|---|
| P0 | GitOps 60s 超时:子模块多/网络慢会卡 UI。需要可配置 + 真异步(目前 Thread + invokeLater,够用但不可中断) | v0.3 候选 |
| P1 | **单元测试**:GitOps / SwitchExecutor 都没有。mock GitOps 跑 SwitchExecutor 至少覆盖「主仓成功子模块失败」的 case | — |
| P2 | **i18n**:目前中英混杂,要么全中要么走 `Bundle.message()` | — |
| P2 | **git worktree 兼容**:副工作树会失败,需要友好提示 | — |

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

## v0.3 候选（剩余 P0）

按价值/工作量排序：

1. **每个 preset 头部显示主仓 diff** — 改 `PresetEditor` 头部布局，加一行 `当前 → 目标` 染色标签；状态可视化的最后一块短板
2. **部分失败回滚** — `SwitchExecutor` 切前记 checkpoint（每仓 HEAD），失败时通知 + 回滚按钮；事务化但单仓回滚也可能 dirty，需要小心
3. **GitOps 60s 超时可配置 + 真异步** — `run` 方法接 `ProgressIndicator`，循环 `isCanceled` 检查；切换/preflight/检测全链路过一遍

后续按 P1 / P2 滚动。

## 架构 / 设计债

2026-06-05 代码审查后梳理的结构问题。不在 v0.x 功能线上，但会直接决定 v0.3 候选 P0 能否低成本落地。

### 主要问题

| 优先级 | 问题 | 影响 |
|---|---|---|
| P0 | `BranchSwitcherPanel` 是 god class（419 行），`PresetFile` 状态住在 UI 里 | Action / 状态栏 widget 拿不到 preset；ToolWindow 关掉 state 重新 load |
| P0 | `GitOps` 是 object，不可 mock | ROADMAP P1「单元测试」无法落地 |
| P0 | `SwitchExecutor` 一个 130 行 `execute` 串了所有步骤 | P0「部分失败回滚」需要 checkpoint，无 step 抽象就没法切入 |
| P0 | 没有 `BranchSwitcherService`（Project Service） | 状态、CRUD、监听全没地方放 |
| P1 | `PresetEditor` 是 god view（463 行） | 加拖拽/复制/导出/重命名只能继续塞这个文件 |
| P1 | 异步 API 四种混用（Thread / pooledThread / Task.Backgroundable / Task.Modal） | cancel/进度/错误处理语义不一致 |
| P1 | `Preset` 没有稳定 ID | 重命名后历史 / 快捷键绑定 / 颜色标签都断 |
| P1 | 切换选项（dirty / fetch / pull）不持久化 | IDE 重启重置 |
| P1 | 没有 EventBus / Listener 模式 | 加任何派生组件都得回头改 Panel |
| P1 | `GitOps` 用 CLI fork 而非 git4idea API | 慢 + 不响应 cancel + 依赖 PATH |
| P2 | 包结构扁平（`com.submodule.branchswitcher` 全平铺，11 个文件） | 加新功能继续平铺会变难找 |
| P2 | 中英文硬编码，无 `BundleMessage` | i18n 时机械迁移 |
| P2 | `noFocusRing()` 每个按钮手动调，容易漏 | 应该工厂化或全局 LAF |

### 可扩展性现状

| 扩展方向 | 难度 | 卡在哪 |
|---|---|---|
| 新切换动作（rebase / tag / commit） | 高 | 改 SwitchExecutor 主循环 |
| Tools 菜单 / 快捷键 | 高 | PresetFile 状态在 Panel 里 |
| 状态栏 widget | 高 | 同上 |
| 单元测试 | 高 | GitOps 不可 mock |
| 多 VCS（hg / p4） | 高 | GitOps 直接绑 git |
| Per-preset 选项覆盖 | 中 | 数据模型加字段 + UI 改 |
| 切换历史 / 撤销 | 中 | 无持久化层 |
| Preset 拖拽/复制/导入导出 | 中 | PresetEditor 已胖，但加按钮可行 |
| git worktree | 中 | 假设 `.git` 是 dir/file |
| i18n | 低 | 机械迁移 |

### 重构路径（按 ROI 排序）

**第一波 — 撬动单测 + 后续重构**：

1. **`GitClient` interface** — `GitOps` 实现它，`SwitchExecutor` / `Preflight` 持有 `GitClient`；一个改动让 P1 单测可做
2. **包重组** — `ui/` `git/` `model/` `service/` `action/` 子包，先腾空间
3. **抽 `BranchSwitcherService`（Project Service）** — `PresetFile` + `currentBranches` + CRUD + load/save 从 Panel 移过来，Panel 退化为 view

**第二波 — 让 v0.3 候选 P0 变简单**：

4. **`SwitchStep` 抽象** — 拆 `SwitchExecutor` 主循环；回滚 / cancel 检查 / 进度上报都在 step 接口里统一
5. **`Preset` 加 `id: String`** — UUID 默认；重命名/历史/Action 绑定靠它

**第三波 — 扩展面**：

6. **PersistentStateComponent** — 选项持久化 + 后续 settings page
7. **MessageBus topic** — `PresetChanged` / `BranchSwitched` / `CurrentStateDetected`
8. **plugin.xml 加 Action 框架** — `tools/branch-switcher/switch-X` 动态生成

**第四波 — 投入产出比变低**：

9. git4idea API 迁移
10. coroutines 替代 Thread/Task 混用
11. i18n
