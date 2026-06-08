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

## 总结

- **优先修**: #1 + #2,这两个合在一起会让 Log 折叠条在用户首次点击前一直显示异常。
- **清理项**: #3 + #4,顺手做。
- **UX/Reuse**: #5 + #6,可以另起一个 commit 跟新布局解耦。
