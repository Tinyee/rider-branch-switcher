<!-- ARCHIVED: 历史设计方案。UI 重构已完成，当前界面以实际代码为准。 -->
# UI 改造方案 — 2026-06-09

## 背景

当前 Tool Window 已经具备主要功能，但界面信息层级偏弱：

- 顶部按钮过多且尺寸相近，导入/导出/撤销/设置项与核心操作抢注意力。
- 切换选项常驻主界面，占用了第一屏空间，但并不是每次使用都需要调整。
- Preset 卡片缺少明确层级，当前状态、目标差异和主要操作不够一眼可读。
- 颜色语义偏散，蓝/青/紫/红/黄同时出现，视觉焦点不稳定。
- 底部空白较大，整体看起来像“表单堆叠”，而不是一个 Rider 原生 VCS 工具。

目标不是做花哨界面，而是更接近 JetBrains 原生工具窗口：紧凑、清晰、状态优先、危险操作降噪。

## 设计原则

1. 主界面只放高频工作流，配置类内容进入 Settings。
2. 主操作突出，次操作收敛，危险操作默认弱化。
3. 状态信息优先于管理按钮，让用户先知道“现在在哪、将切去哪、是否安全”。
4. 保持 Rider 原生观感，优先使用 `AllIcons`、`JBUI`、`JBColor`、`ActionToolbar` / popup menu 风格。
5. 改造分阶段进行，先做低风险布局优化，不动切换核心逻辑。

## 信息架构

### 主界面保留

- 当前主仓分支摘要，例如 `main: dev`。
- `从当前状态新建`。
- `新增预设`。
- Preset 列表。
- 每个 preset 的核心动作：
  - `切到此预设` / `已在此预设`。
  - `派生分支`。
  - 展开后编辑主仓和子模块分支。
  - `添加子模块`。
  - `保存` / `丢弃`。
- Log 折叠区。

### 收进 Settings

这些是全局策略，不应常驻占用主界面：

- 脏工作区策略：`Stash / Skip / Force`。
- Git 命令超时：`30s / 60s / 120s / 300s`。
- 切换前 fetch。
- 切换后 pull。
- 缺失子模块 init 前确认。

Settings 页面可以保留现有 `BranchSwitcherConfigurable`，主界面不再直接展示这些控件。

### 收进右上角更多菜单

低频管理动作建议进入 Tool Window 右上角 `...` 菜单：

- 重载预设。
- 打开预设文件。
- 导入预设。
- 导出预设。
- 撤销切换。
- 打开 Settings。

如果后续要保留快捷入口，可以在 header 右侧放一个小齿轮或 `...`，不要用一整排大按钮。

## 推荐布局

### 总体结构

```text
SubmoduleBranches                         main: dev      [...]

[从当前状态新建] [新增预设]

dev                                      当前
main                                    dev
[已在此预设]

main                                    dev -> main
[切到此预设] [派生分支] [...]
  主仓        [ main                 v ]
  ● SubA      [ dev                  v ]
  ● SubB      [ feature-x            v ]
  [+ 添加子模块]                    [丢弃] [保存]

Log >
```

### Header 区

当前顶部建议改成：

- 左侧：标题 `SubmoduleBranches`。
- 标题下方或右侧：当前主仓摘要 `main: dev`。
- 右侧：`...` 更多菜单。
- 第二行：只放两个 CTA：
  - `从当前状态新建`
  - `新增预设`

可选：在右侧显示非常轻量的策略摘要，例如：

```text
Stash · fetch · pull · 60s
```

点击摘要打开 Settings。这个摘要只用于可见性，不作为可编辑控件。

### Preset 卡片

每个 preset 建议有两种状态。

#### 折叠状态

```text
dev                                      当前
main                                    dev
[已在此预设]
```

或：

```text
main                                    dev -> main
[切到此预设] [派生分支] [...]
```

折叠状态要回答三个问题：

- preset 名是什么。
- 当前主仓与目标主仓是否一致。
- 主要动作是什么。

#### 展开状态

```text
main                                    dev -> main
[切到此预设] [派生分支] [...]

主仓        [ main                 v ]
● SubA      [ dev                  v ]
● SubB      [ feature-x            v ]

[+ 添加子模块]                    [丢弃] [保存]
```

行布局建议：

- 左列固定宽度：repo 名 + 状态点。
- 右列自适应：branch combo。
- 删除子模块按钮只在 hover 或行尾小图标显示，避免红色一直抢注意力。

### 操作优先级

主按钮：

- `切到此预设`
- `已在此预设` disabled 状态

次按钮：

- `派生分支`
- `保存`
- `丢弃`
- `添加子模块`

菜单项：

- `删除预设`
- `导入/导出`
- `打开预设文件`
- `撤销切换`

危险动作：

- `删除预设` 放进 `...` 菜单。
- 菜单项可以红色显示，或点击后保持确认弹窗。
- 不建议在卡片上常驻大红色 `删除` 按钮。

## 颜色语义

建议收敛到以下语义：

- 蓝色：当前高亮 / 主操作 / 链接。
- 绿色：仓库已匹配目标分支。
- 黄色或橙色：当前分支与目标分支不同。
- 红色：错误、失败、删除确认，不常驻展示。
- 灰色：disabled、未初始化、不重要元信息。

具体实现优先使用：

- `JBUI.CurrentTheme.Link.Foreground.ENABLED`
- `JBColor.border()`
- `NamedColorUtil.getErrorForeground()`
- `UIUtil.getLabelForeground()`
- `UIUtil.getContextHelpForeground()`

避免直接写过多固定 RGB。确实需要状态色时，统一集中到一个小 helper，避免散落在各个 UI 类里。

## 文案建议

当前中英文混用较少，但有些技术词可以更自然：

| 当前 | 建议 |
|---|---|
| `Stash 脏改动` | `暂存未提交改动` |
| `切换前 fetch` | Settings 中写 `切换前拉取远端引用` |
| `超时` | `Git 命令超时` |
| `从当前状态` | `从当前状态新建` |
| `已在此预设` | 保留 |
| `切到此预设` | 保留 |

如果主界面空间紧张，可以主界面用短文案，tooltip 用完整解释。

## 实施计划

### Phase 1：低风险整理

目标：不改核心逻辑，只调整可见控件和布局。

1. 从 `BranchSwitcherPanel` 移除主界面的 dirty / timeout / fetch / pull / confirm init 控件。
2. 保留 Settings 中这些选项，并确保 tool window 初始化仍读取 service 状态。
3. 顶部只保留 `从当前状态新建`、`新增预设`。
4. 新增右上角 `...` 菜单，放入：
   - 重载预设
   - 打开预设文件
   - 导入预设
   - 导出预设
   - 撤销切换
   - 打开 Settings
5. Preset header 中弱化 `删除`，改入每个 preset 的 `...` 菜单。

验收标准：

- 第一屏能直接看到更多 preset 内容。
- 主界面没有全局配置控件。
- 所有原动作仍能从菜单或 Settings 找到。
- `./gradlew test` 通过。

### Phase 2：Preset 卡片重排

目标：让 preset 状态和主操作更清晰。

1. 重做 `PresetEditor` header：
   - 左侧：展开箭头 + preset 名 + 当前 badge。
   - 中间或副标题：`当前主仓 -> 目标主仓`。
   - 右侧：主按钮 `切到此预设` / `已在此预设`，次按钮 `派生分支`，更多菜单。
2. 展开区改成两列对齐：
   - repo label 列固定宽度。
   - branch combo 列自适应。
3. 子模块删除按钮改为小图标，降低默认视觉重量。
4. 保存/丢弃放在展开区底部右侧。

验收标准：

- 折叠状态下也能判断每个 preset 是否匹配当前主仓。
- 展开后主仓和子模块分支控件左右对齐。
- 删除动作不再和切换动作同等显眼。

### Phase 3：状态和空白优化

目标：提升完成度。

1. Log 折叠条贴底，但不要制造大面积空白；preset 列表自然撑开。
2. 空状态改成更简洁的 CTA：
   - `从当前状态新建`
   - `手动新增`
3. 状态点 tooltip 统一：
   - 已匹配
   - 分支不同
   - 未初始化
   - dirty
4. 如果有空间，顶部显示策略摘要：
   - `暂存 · fetch · pull · 60s`
   - 点击打开 Settings。

验收标准：

- 空状态看起来像功能入口，不像日志输出。
- 非空状态下没有大面积无意义空白。
- 状态点语义一致。

## 相关文件

预计主要涉及：

- `src/main/kotlin/com/submodule/branchswitcher/ui/BranchSwitcherPanel.kt`
- `src/main/kotlin/com/submodule/branchswitcher/ui/PresetEditor.kt`
- `src/main/kotlin/com/submodule/branchswitcher/ui/PresetListManager.kt`
- `src/main/kotlin/com/submodule/branchswitcher/settings/BranchSwitcherConfigurable.kt`
- `src/main/resources/messages/BranchSwitcherBundle.properties`
- `src/main/resources/messages/BranchSwitcherBundle_zh.properties`

如果引入 popup menu helper，可以新建：

- `src/main/kotlin/com/submodule/branchswitcher/ui/UiActions.kt`

## 不建议本轮做

- 不建议改 Git 切换 pipeline。
- 不建议迁移到复杂 UI 框架。
- 不建议引入自定义绘制大背景或重视觉插画。
- 不建议把所有按钮改成无文字图标；Rider tool window 中文字按钮更适合低学习成本。

## 推荐第一版目标

第一版只做：

1. Settings 收纳全局配置。
2. Header 简化。
3. 更多菜单。
4. Preset header 主次按钮重排。

这四项能显著改善截图里的“按钮堆叠感”，同时风险最低。
