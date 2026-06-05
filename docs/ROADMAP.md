# Submodule Branch Switcher — 需求 / 路线图

当前版本 0.1.0 已具备：多 preset 持久化（JSON）、一键切换主仓 + 子模块、脏工作区三策略（stash/skip/force）、切换前 fetch、切换后 pull --ff-only、切换后 VCS 自动刷新、分支下拉输入即过滤、UI 增删 preset、UI 增删子模块行（基于 `.gitmodules`）。

下面按「切换体验 / 状态可视化 / UI / 工作流 / 质量」五块梳理后续要做的功能点，优先级 **P0(致命) / P1(高价值) / P2(锦上添花)**。

## 切换体验

| 优先级 | 需求 | 现状缺什么 |
|---|---|---|
| P0 | **Dry-run 预览** | 现在 confirm 框只说「X 个子模块」，点完直接执行。应展示一张表：每个仓 `当前分支 → 目标分支`、是否 dirty、stash 会动多少文件、远端有/无目标分支 |
| P0 | **部分失败回滚** | 主仓切完后某个子模块挂掉，状态不一致没法一键回去。需要事务式 checkpoint（切前记下每个仓的 HEAD，失败时给「回滚到切换前」按钮） |
| P0 | **submodule 处理** | 切完只动各子模块自身分支，**不跑 `git submodule sync`，也不跑 `git submodule update --init --recursive`**。Config~/Level 跨主分支时 `.gitmodules` 的 URL 或 commit pointer 会漂，Unity 编译期会拿到错的数据 |
| P1 | **stash 自动 pop** | Stash 模式只 push 不 pop。切回原分支时要手工 `git stash list/pop`。可加「记住 stash → 切回时自动 pop」 |
| P1 | **进度可视化** | `Task.Backgroundable` 用 indeterminate，看不到「5 个仓的第 3 个」。改 `indicator.fraction + text2` |
| P1 | **可取消** | 进度条有取消按钮但 `SwitchExecutor` 循环里不查 `indicator.isCanceled`，点了没用 |
| P2 | **未 init 子模块识别** | `.gitmodules` 里有但本地空目录的子模块，现在静默跳过。应提示并给「init 后再切」选项 |

## 状态可视化（当前最弱的一环）

| 优先级 | 需求 | 现状 |
|---|---|---|
| P0 | **「我现在在哪个 preset」高亮** | 折叠态看不出当前主仓+子模块组合是否匹配某个 preset。命中的 preset 应该左侧加色条 + 名字加粗 + 副标题「当前」 |
| P0 | **每个 preset 头部显示主仓 diff** | 头部应并排显示 `当前分支 → preset.main`，不一致时染色，而不是必须展开才看得到 |
| P1 | **行级状态点** | 每个子模块行左侧一个圆点：绿=已匹配 / 黄=分支对但有 dirty / 红=不匹配 / 灰=未 init |
| P1 | **切换中状态贴在 ToolWindow tab 上** | 切换时 stripe icon 加 spinner、迷你状态条。现在切换时面板没有视觉信号 |
| P2 | **顶部「当前主仓分支」常驻显示** | 不用展开任何东西就能看到主仓在哪 |

## UI

| 优先级 | 需求 | 现状 |
|---|---|---|
| P0 | **去硬编码颜色** | `Color(80,80,80)` / `Color(180,60,60)` 在 Light 主题下糟糕。改用 `JBColor.namedColor("...")` / `UIUtil.getLabelForeground()` / `JBColor.RED` |
| P0 | **替换 ▶/▼/✕/✓ 字符为 IntelliJ Icons** | `AllIcons.General.ArrowRight`/`ArrowDown`、`AllIcons.Actions.Cancel`、`AllIcons.Actions.MenuSaveall`、`AllIcons.Vcs.Branch` 等。视觉一致性立刻拉满 |
| P1 | **空状态占位** | 第一次打开/找不到 git root/没有 preset 时，中央应该有大字提示 + CTA 按钮，不是只在日志输出 |
| P1 | **错误用 Notification 弹** | 切换失败 / fetch 超时，目前只在面板日志里。改用 `Notifications.Bus.notify(...)` 让 IDE 右下角弹气泡 |
| P1 | **日志区染色 + 折叠** | 改用 IntelliJ `ConsoleView` 替代 `JTextArea`，INFO/WARN/ERROR 自动染色，带搜索 |
| P1 | **行布局对齐** | 主仓/子模块 combo 和右侧 ✕ 按钮在不同字号下对不齐。统一用 `GridBag` 或 `MigLayout`，140px 硬编码改成 `JBUI.scale` |
| P2 | **预设拖拽排序** | 多 preset 时只能按 JSON 顺序，加 drag handle |
| P2 | **窄宽自适应** | tool window 拖窄时按钮换行/截断。响应式收起次要按钮到 ⋯ 菜单 |

## 工作流

| 优先级 | 需求 | 现状 |
|---|---|---|
| P0 | **「从当前状态新建 preset」** | 现在新增 preset 只能填名字、子模块抄第一个 preset。实际最常见需求是「按我现在所有仓的分支组合保存一个 preset」，一键即可 |
| P1 | **派生功能分支** | 选某 preset → 输入分支名 `feature/xxx` → 主仓和所有子模块同时 `checkout -b feature/xxx`，基于 preset 的 base。Unity feature 流极常见 |
| P1 | **预设重命名** | 现在改名要手工编辑 JSON |
| P1 | **快捷键** | `Tools → Branch Switcher → 切到 X` 注册成 Action，可绑快捷键 |
| P1 | **导入/导出** | 团队成员手工拷贝 JSON。给「导出到剪贴板/导入」按钮 |
| P2 | **右键菜单** | preset / 子模块行没 context menu。子模块行右键应有「在 Finder 打开」「跳转到 Git tool window」「仅切此一个」 |
| P2 | **历史记录** | 最近 5 次切换记录，可「撤销到上一次切换之前」 |

## 质量

| 优先级 | 需求 |
|---|---|
| P0 | GitOps 60s 超时：子模块多/网络慢会卡 UI。需要可配置 + 真异步（目前 Thread + invokeLater，够用但不可中断） |
| P1 | **单元测试**：GitOps / SwitchExecutor 都没有。mock GitOps 跑 SwitchExecutor 至少覆盖「主仓成功子模块失败」的 case |
| P2 | **i18n**：目前中英混杂，要么全中要么走 `Bundle.message()` |
| P2 | **git worktree 兼容**：副工作树会失败，需要友好提示 |

## 推荐迭代顺序（v0.2 目标）

1. **当前 preset 高亮 + 每行状态点**（P0 状态可视化两条）— 打开 UI 立刻知道在哪
2. **Dry-run 预览表**（P0 切换体验）— 不再「瞎切」
3. **submodule sync + update**（P0 工作流）— 跨主分支不再翻车
4. **替换图标 + 去硬编码颜色 + 错误走 Notification**（P0 UI 三件套）— 视觉立刻像个正经插件
5. **从当前状态新建 preset**（P0 工作流）— 录入成本降到接近 0

后续按 P1 / P2 滚动。
