# Per-Preset 选项覆盖 (#29) — 设计文档

## 现状

- 全局 `SwitchOptions`（dirty / fetchFirst / pull / confirmBeforeInit）存在 `BranchSwitcherService.OptionsState`，通过 Settings 页面配置
- 所有 preset 共用一套全局选项
- 两个消费点各自由 service 属性拼出 `SwitchOptions`：`SwitchController.executeSwitch():63-68` 和 `SwitchPresetAction.executeSwitch():99-104`
- `Preset.pullEnabled` 是旧版 per-preset pull 控制（顶层 JSON `"pull": false`）

## 设计原则

1. **数据模型优先** — 纯函数 merge 逻辑可脱离 IntelliJ 测试
2. **JSON 向后兼容** — 旧 preset 无 `overrides` 字段 → `null` → 行为不变
3. **最小侵入** — 只覆盖 dirty / pull / fetchFirst；confirmBeforeInit（安全守卫）和 timeoutSeconds（系统级）不开放覆盖
4. **唯一 pull 模型** — `overrides.pull` 是 per-preset pull 的唯一控制。旧 `pull: false` JSON 自动迁移，消除双重判断（P1-1）
5. **所有 DTO→domain 转换路径无遗漏**（P1-2）
6. **非法字段不阻断整个 preset 加载**（P1-3）

## 1. 数据模型 (`model/PresetConfig.kt`)

### 1.1 域模型

```kotlin
/** Per-preset option overrides. 每个字段 null = 不覆盖，沿用全局。 */
data class PresetOverrides(
    val dirty: DirtyAction? = null,
    val pull: Boolean? = null,
    val fetchFirst: Boolean? = null,
)

/** 唯一 pull 模型：per-preset pull 由 [overrides.pull] 控制。
 *  旧 [pullEnabled] 字段已移除；旧 JSON `"pull": false` 由 Loader 自动迁移为
 *  `overrides: {pull: false}`（见 §3.2 迁移规则）。 */
data class Preset(
    val name: String,
    val main: String,
    val submodules: Map<String, String> = emptyMap(),
    val id: String = UUID.randomUUID().toString(),
    val overrides: PresetOverrides? = null,
) {
    fun targets(): List<RepoTarget> { /* ... */ }
}
```

### 1.2 DTO（Gson-safe，所有字段 nullable）

```kotlin
data class PresetOverridesDto(
    val dirty: String? = null,        // "Stash" / "Skip" / "Force"
    val pull: Boolean? = null,
    val fetchFirst: Boolean? = null,
) {
    /** Safe conversion: illegal [dirty] → null (single field dropped,
     *  not entire preset). Matches fail-contract in §8. */
    fun toOverrides(): PresetOverrides? {
        val d = dirty?.let { raw ->
            try {
                DirtyAction.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                null  // malformed → skip this field, keep others
            }
        }
        if (d == null && pull == null && fetchFirst == null) return null
        return PresetOverrides(dirty = d, pull = pull, fetchFirst = fetchFirst)
    }
}

data class PresetDto(
    val id: String? = null,
    val name: String? = null,
    val main: String? = null,
    val submodules: Map<String, String>? = null,
    @SerializedName("pull") val pull: Boolean? = null,   // legacy, see §3.2
    val overrides: PresetOverridesDto? = null,
) {
    /**
     * Converts to [Preset]. Name and main are trimmed (idempotent — safe for import
     * paths that already trimmed). [explicitId] overrides the JSON [id] and the
     * UUID fallback; Loader passes the normalized ID, import passes a fresh
     * generated ID. All callers that previously manually constructed [Preset]
     * should use this function instead.
     */
    fun toPreset(explicitId: String? = null): Preset = Preset(
        id = explicitId ?: id ?: UUID.randomUUID().toString(),
        name = (name ?: error("preset.name is required")).trim(),
        main = (main ?: error("preset.main is required")).trim(),
        submodules = submodules ?: emptyMap(),
        overrides = migratePullAndOverrides(),
    )

    /** One-shot migration: legacy pull + overrides → unified overrides. */
    private fun migratePullAndOverrides(): PresetOverrides? {
        val ov = overrides?.toOverrides()
        val legacyPull = pull
        return when {
            legacyPull == null -> ov                    // no legacy pull, use overrides as-is
            ov?.pull != null -> ov                      // explicit overrides.pull takes precedence
            legacyPull == true -> ov                    // pull=true is default, no override needed
            else -> PresetOverrides(                    // pull=false → migrate to overrides
                dirty = ov?.dirty,
                pull = false,
                fetchFirst = ov?.fetchFirst,
            )
        }
    }

    /**
     * True when this DTO contains the legacy top-level `pull` field.
     * Any legacy occurrence must trigger write-back so the persisted JSON
     * converges to the unified format, even when `overrides.pull` wins.
     */
    val needsPullMigration: Boolean
        get() = pull != null
}

/** Persistence container — all fields nullable for Gson safety.
 *  Gson bypasses Kotlin default params, so [presets] and list items
 *  must both be nullable; consumers use [presets.orEmpty().filterNotNull()]. */
data class PresetFileDto(
    val presets: List<PresetDto?>? = null,
) {
    fun toPresetFile(): PresetFile =
        PresetFile(presets.orEmpty().filterNotNull().map { it.toPreset() })
}
```

JSON 示例：

```json
// 新 preset：显式 overrides
{
  "name": "hotfix",
  "main": "hotfix/1.0",
  "submodules": {"lib": "hotfix/1.0"},
  "id": "abc-123",
  "overrides": {
    "dirty": "Force",
    "fetchFirst": false
  }
}

// 旧 preset（无 overrides 字段）：pull: false 完成迁移
// 加载前: {"name":"old","main":"dev","pull":false}
// 加载后: Preset(name="old", main="dev", overrides=PresetOverrides(pull=false))
```

## 2. 合并逻辑（纯函数，Layer 1）

放在 `PresetConfig.kt` 顶层，不依赖任何 IntelliJ API：

```kotlin
/** Resolves effective [SwitchOptions] by merging preset overrides into global defaults.
 *  [PresetOverrides] 为 null 时直接返回 global。confirmBeforeInit 始终来自 global。 */
fun PresetOverrides?.effectiveOptions(global: SwitchOptions): SwitchOptions {
    if (this == null) return global
    return SwitchOptions(
        dirty = dirty ?: global.dirty,
        pull = pull ?: global.pull,
        fetchFirst = fetchFirst ?: global.fetchFirst,
        confirmBeforeInit = global.confirmBeforeInit,
    )
}
```

## 3. 数据加载与转换 — 全部路径

### 3.1 转换路径清单（P1-2 修复）

| 路径 | 文件 | 调用方式 |
|------|------|---------|
| `PresetDto.toPreset(explicitId)` | `model/PresetConfig.kt` | 唯一 DTO→domain 入口：trim name/main，调 `migratePullAndOverrides()`，接收 optional explicitId |
| `PresetFileDto.toPresetFile()` | `model/PresetConfig.kt:62` | delegate to `toPreset()` → ✅ 自动覆盖 |
| `PresetLoader.normalizePresetIds()` | `PresetLoader.kt:80-101` | 生成归一化 ID → `presetDto.toPreset(explicitId=normalizedId)`；同时检查 `presetDto.needsPullMigration` 加入 `changed` 标志 |
| `parsePresetImport()` | `ui/PresetImportResult.kt:38-56` | 遍历 `presets.orEmpty().filterNotNull()`（参见 §1.2 nullable 策略）；trim name（已有）→ `presetDto.toPreset(explicitId=idGenerator())` |
| `PresetListManager.addPreset()` | `ui/PresetListManager.kt:210-215` | 新建 Preset（无 DTO），无需改 |
| `PresetListManager.addPresetFromCurrent()` | `ui/PresetListManager.kt:271-276` | 同上 |

✅ 核心策略：**所有从 DTO 构造 Preset 的路径统一调用 `PresetDto.toPreset(explicitId=...)`**。Loader 和 import 不再手动构造 Preset 字段列表。

### 3.2 旧 `pull` 迁移 + 自动写回（P1-1 修复）

迁移规则：

```
旧 JSON pull 值  / overrides.pull /  结果 overrides.pull
─────────────────┼─────────────────┼─────────────────────
缺失             │ null            │ null（使用全局，不写回）
true             │ null            │ null（使用全局，写回时删除 legacy pull）
false            │ null            │ false（迁移）
任意 legacy 值   │ true/false      │ 保留 overrides.pull（显式优先，写回时删除 legacy pull）
```

迁移在 `PresetDto.migratePullAndOverrides()` 中一次完成。**自动写回**由 `PresetLoader.normalizePresetIds()` 确保：

```kotlin
// PresetLoader.normalizePresetIds() — 变更点
private fun normalizePresetIds(dto: PresetFileDto): Pair<PresetFile, Boolean> {
    val usedIds = mutableSetOf<String>()
    var changed = false
    val presets = dto.presets.orEmpty().filterNotNull().map { presetDto ->
        val existingId = presetDto.id?.takeIf { it.isNotBlank() }
        val id = if (existingId != null && usedIds.add(existingId)) {
            existingId
        } else {
            changed = true
            generateUniqueId(usedIds)
        }
        // Any legacy top-level pull triggers write-back, including pull=true
        // and files where explicit overrides.pull already wins.
        if (presetDto.needsPullMigration) {
            changed = true
        }
        presetDto.toPreset(explicitId = id)
    }
    return PresetFile(presets) to changed
}
```

`PresetLoader.load()` 的 `needsMigration` 分支在发现任何顶层 legacy `pull` 时触发。写回使用 domain `PresetFile`，因此输出不再包含顶层 `pull`；`pull:true` 被删除，`pull:false` 被迁移到 `overrides.pull=false`，显式 `overrides.pull` 始终优先。

### 3.3 `PullStep` 简化（P1-1 修复）

```kotlin
// 改前：双条件
if (!context.options.pull || !context.preset.pullEnabled) { /* skip */ }

// 改后：options.pull 已是 effective 值，无需判断 preset
if (!context.options.pull) { /* skip */ }
```

## 4. UI — PresetEditor

### 4.1 布局

```
┌──────────────────────────────────────────────────┐
│ ▶ develop    [当前]              [切换] [⚙] [⋯] │
│   主仓: develop                                   │
│   ⚙ Overrides: Dirty [Force ▼]  Pull [Off ▼]     │  ← 可折叠行
│   子模块: lib → develop                           │
│                                 [Revert] [Save]   │
└──────────────────────────────────────────────────┘
```

⚙ 按钮使用 `AllIcons.General.GearPlain`。无 override 时默认色；有 override 时变色为 `JBColor(0xE07B00, 0xFFA726)` 并在 tooltip 显示摘要。

### 4.2 控件

- **Dirty**：`JComboBox` 4 项 — `Bundle.msg("option.override.use.global", current)` / `Bundle.msg("label.strategy.stash")` / `Bundle.msg("label.strategy.skip")` / `Bundle.msg("label.strategy.force")`。index 0 = null。
- **Pull**：`JComboBox` 3 项 — `Bundle.msg("option.override.use.global", current)` / `Bundle.msg("option.override.on")` / `Bundle.msg("option.override.off")`。index 0 = null。
- **Fetch first**：同上。

`{current}` 为全局当前值，动态从 `service.dirtyAction` / `service.pullAfterSwitch` / `service.fetchFirst` 获取。所有可见标签必须通过 `Bundle.msg()` 生成，不硬编码英文。

### 4.3 Override 加载（P1-4 修复）

**构造器变更**：`PresetEditor` 加三个 callback 供 `refreshGlobalLabels()` 获取全局当前值，不直接依赖 `BranchSwitcherService` 类型：

```kotlin
class PresetEditor(
    // ... existing params ...
    private val globalDirtyLabel: () -> String,    // ← NEW
    private val globalPullLabel: () -> String,     // ← NEW
    private val globalFetchLabel: () -> String,    // ← NEW
)
```

`PresetListManager.addEditorRow()` 同步传入：
```kotlin
globalDirtyLabel = {
    when (service.dirtyAction) {
        DirtyAction.Stash -> Bundle.msg("label.strategy.stash")
        DirtyAction.Skip -> Bundle.msg("label.strategy.skip")
        DirtyAction.Force -> Bundle.msg("label.strategy.force")
    }
},
globalPullLabel = {
    if (service.pullAfterSwitch) Bundle.msg("option.override.on") else Bundle.msg("option.override.off")
},
globalFetchLabel = {
    if (service.fetchFirst) Bundle.msg("option.override.on") else Bundle.msg("option.override.off")
},
```

**Hook 位置**：`applyOverridesToUI()` 追加在现有 `applyOriginalToUI()` **末尾**，这样 init 和 revert 两条路径自动覆盖：

```kotlin
// PresetEditor.kt:260 — 改后
private fun applyOriginalToUI() {
    mainCombo.selectedItem = original.main
    subManager.applyPresetToUI(original)
    applyOverridesToUI()   // ← NEW（追加在末尾）
    updateDirty()
}
```

```kotlin
/** Restore combo selections from [original.overrides]. */
private fun applyOverridesToUI() {
    val ov = original.overrides
    dirtyCombo.selectedIndex = if (ov?.dirty != null) dirtyActionToIndex(ov.dirty) + 1 else 0
    pullCombo.selectedIndex = when (ov?.pull) {
        true -> 1; false -> 2; else -> 0
    }
    fetchCombo.selectedIndex = when (ov?.fetchFirst) {
        true -> 1; false -> 2; else -> 0
    }
    updateOverrideIndicator()
}

/** Refresh combo index-0 labels after Settings change. */
fun refreshGlobalLabels() {
    dirtyCombo.setItemAt(Bundle.msg("option.override.use.global", globalDirtyLabel()), 0)
    pullCombo.setItemAt(Bundle.msg("option.override.use.global", globalPullLabel()), 0)
    fetchCombo.setItemAt(Bundle.msg("option.override.use.global", globalFetchLabel()), 0)
}
```

| 路径 | 触发 | 行为 |
|------|------|------|
| Initial load | `init{}` → `applyOriginalToUI()` → 末尾调 `applyOverridesToUI()` | 从 `original.overrides` 恢复所有 combo |
| Save | `saveBtn.actionPerformed` → `original = cur` → `updateDirty()` | dirty 自动清；combo 保留当前选择 |
| Revert | `revertBtn.actionPerformed` → `applyOriginalToUI()` → 末尾调 `applyOverridesToUI()` | 与 initial load 相同 |
| Reload | `PresetListManager.reload()` 重建 PresetEditor | 同上 |
| Settings change | `PresetListManager.refreshAllGlobalLabels()` 逐 editor 调用 `refreshGlobalLabels()` | 见 §4.5 调用链 |

### 4.4 Dirty 检测

`buildCurrent()` 返回含 `overrides` 的 `Preset`。`original != cur` 自动触发 dirty flag。无需额外逻辑。

### 4.5 Settings 变更调用链（P1-4 修复）

`refreshGlobalLabels()` 需要在两个时机被调用：

```kotlin
// PresetListManager — 新增方法
fun refreshAllGlobalLabels() {
    editors.forEach { it.refreshGlobalLabels() }
}
```

调用链：

```
Settings 关闭后
  → BranchSwitcherPanel.openSettings()        // line 233
  → 在 refreshStrategySummary() 之后增加:
       presetManager.refreshAllGlobalLabels() // ← NEW

面板重新显示时
  → BranchSwitcherPanel.detectCurrentState()  // line 362
  → 末尾增加:
       presetManager.refreshAllGlobalLabels() // ← NEW
```

`BranchSwitcherPanel` 已持有 `presetManager` 引用（line 112），无需额外注入。

## 5. 有效选项接入 — 单一入口（P2-1 修复）

### 5.1 已解析请求 + 类型级结构守卫（P2-1 修复）

```kotlin
/**
 * A request whose effective options have already been resolved.
 * The private constructor prevents entry points from pairing a preset with
 * arbitrary options; all requests go through [resolve].
 */
class ResolvedSwitchRequest private constructor(
    val preset: Preset,
    val options: SwitchOptions,
) {
    companion object {
        fun resolve(preset: Preset, global: SwitchOptions): ResolvedSwitchRequest =
            ResolvedSwitchRequest(
                preset = preset,
                options = preset.overrides.effectiveOptions(global),
            )
    }
}

// SwitchExecutor — before
fun execute(preset: Preset, options: SwitchOptions): Boolean

// SwitchExecutor — after: callers cannot execute an unresolved pair
fun execute(request: ResolvedSwitchRequest): Boolean {
    val preset = request.preset
    val options = request.options
    // existing pipeline unchanged
}
```

生产代码只通过 `BranchSwitcherService.resolveSwitchRequest()` 建立 request，避免两个入口分别拼 global options：

```kotlin
// BranchSwitcherService
fun resolveSwitchRequest(preset: Preset): ResolvedSwitchRequest =
    ResolvedSwitchRequest.resolve(
        preset,
        SwitchOptions(
            dirty = dirtyAction,
            pull = pullAfterSwitch,
            fetchFirst = fetchFirst,
            confirmBeforeInit = confirmBeforeInit,
        ),
    )
```

结构保证：

- `ResolvedSwitchRequest` 构造器为 `private`，调用方不能手工组合 preset 与任意 effective options。
- `SwitchExecutor.execute()` 只接受 `ResolvedSwitchRequest`；所有生产调用点若未先 resolve 将无法编译。
- 两个生产入口统一调用 `service.resolveSwitchRequest(preset)`，global options 的字段映射只有一份。
- 面板预览读取 `request.options.dirty`，执行器消费同一个 `request`，避免预览后重新读取 Settings 导致策略漂移。
- 不新增 quickCheck rule 8，也不维护 Kotlin 注释/字符串伪解析器。入口接线由编译期 API 和 `ResolvedSwitchRequestTest` 覆盖。

### 5.2 Force 覆盖安全确认（P1-5 修复）

当 effective dirty 为 `Force` 时，在以下时机展示确认：

| 触发路径 | 确认方式 |
|---------|---------|
| 面板切换 (SwitchController) | `SwitchPreviewDialog.showAndConfirm()` 接收同一个 `ResolvedSwitchRequest`；当 request dirty=Force 且存在 dirty 或 dirty 状态未知（`dirtyCount != 0`，含 `-1` fail-closed）的仓库时，`buildSummary()` 追加醒目红色 "⚠ Force: checkout may fail on conflicts" 文案 |
| 快捷键 (Ctrl+Alt+B) | 在 preflight-warnings 确认**之前**，若 `shouldShowForceWarning(request, probeResult)` 为 true 才弹 Force 确认。clean repo + Force preset 不弹（与面板行为一致） |
| derive | Force 不影响 derive（derive 有独立 `requireClean` 门禁） |

**SwitchPreviewDialog 变更**（+`SwitchPreviewDialog.kt` 入影响文件）：

```kotlin
// 改前
class SwitchPreviewDialog(project: Project, preset: Preset, rows: List<PreflightRow>)
fun showAndConfirm(project, preset, rows): Boolean

// 改后
class SwitchPreviewDialog(
    project: Project,
    request: ResolvedSwitchRequest,         // ← NEW: preset + resolved options
    rows: List<PreflightRow>,
)
fun showAndConfirm(
    project: Project,
    request: ResolvedSwitchRequest,
    rows: List<PreflightRow>,
): Boolean
```

`buildSummary()` 从单个 `JLabel` 改为纵向 `JPanel`。当前文本拼接逻辑（line 73-86）提取为 `buildSummaryText()`，`foreground` 染色保留在调用处。Force 警告条件提取为纯函数：

```kotlin
/** Builds the summary statistics text line. Current buildSummary() line 73-86. */
private fun buildSummaryText(): String {
    val parts = mutableListOf<String>()
    parts += Bundle.msg("summary.repos", rows.size)
    parts += Bundle.msg("summary.to.switch", rows.count { it.exists && it.needsSwitch && !it.branchMissing })
    val noChange = rows.count { it.exists && !it.needsSwitch }
    if (noChange > 0) parts += Bundle.msg("summary.already", noChange)
    val dirty = rows.count { it.dirtyCount > 0 }
    if (dirty > 0) parts += Bundle.msg("summary.dirty", dirty)
    val missingBranch = rows.count { it.branchMissing }
    if (missingBranch > 0) parts += Bundle.msg("summary.missing.branch", missingBranch)
    val missingDir = rows.count { !it.exists }
    if (missingDir > 0) parts += Bundle.msg("summary.missing.dir", missingDir)
    return parts.joinToString("  ·  ")
}

internal fun shouldShowForceWarning(
    request: ResolvedSwitchRequest,
    rows: List<PreflightRow>,
): Boolean = request.options.dirty == DirtyAction.Force
    && rows.any { it.exists && it.dirtyCount != 0 }
// dirtyCount=-1 (git error / unknown) also triggers — fail-closed.
// Non-existing dirs (exists=false, dirtyCount=-1) are excluded here;
// they are handled by the existing missing-directory confirmation.

private fun buildSummary(): JComponent {
    val missingBranch = rows.count { it.branchMissing }
    val missingDir = rows.count { !it.exists }

    val summaryLabel = JLabel(buildSummaryText()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 4, 6, 4)
        // Preserve existing foreground coloring (line 90)
        if (missingBranch > 0 || missingDir > 0) foreground = warnColor
    }

    return JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(summaryLabel)
        if (shouldShowForceWarning(request, rows)) {
            add(JLabel(Bundle.msg("dialog.force.warn")).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = warnColor
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(0, 4, 6, 4)
            })
        }
    }
}
```

**SwitchController 调用链**：`showAndConfirm()` 调用在 `runSwitch()`（line 51），不在 `executeSwitch()`。effective options 在 `runSwitch()` 解析一次，dirty 传给 dialog，完整 options 传入 `executeSwitch()` 避免重复构造：

```kotlin
// SwitchController.kt — 改后
fun runSwitch(preset: Preset) {
    ...
    invokeLaterIfProjectAlive {
        // Resolve once here — dirty for dialog, full opts for execution
        val request = service.resolveSwitchRequest(preset)
        if (SwitchPreviewDialog.showAndConfirm(project, request, probeResult)) {
            executeSwitch(root, request)   // pass through, don't re-resolve
        }
    }
}

// executeSwitch 签名改为接收已解析 request
fun executeSwitch(root: Path, request: ResolvedSwitchRequest) {
    if (!service.tryStartWrite()) { ... }
    ok = executor.execute(request)
}
```

`SwitchPresetAction` 同步创建一个 `ResolvedSwitchRequest`，使用 `request.options.dirty` 决定 Force 确认，并将同一个 request 传给 executor。

## 6. 影响文件

| 文件 | 改动 | 估计行数 |
|------|------|----------|
| `model/PresetConfig.kt` | +`PresetOverrides`, +`PresetOverridesDto`、+`effectiveOptions()`、+`ResolvedSwitchRequest`，Preset 改字段，PresetDto 加 `migratePullAndOverrides()` | +90 |
| `PresetLoader.kt` | `normalizePresetIds()` 改用 `PresetDto.toPreset()`；任何 legacy `pull` 均标记写回 | -10 / +5 |
| `service/BranchSwitcherService.kt` | +`resolveSwitchRequest(preset)`，集中构造 global options | +10 |
| `ui/PresetEditor.kt` | +override UI 行、`applyOverridesToUI()`、`refreshGlobalLabels()`、`buildCurrent()` 含 overrides；构造器 +3 callback | +85 |
| `ui/PresetListManager.kt` | `addEditorRow()` 传 3 个 global label callback | +3 |
| `ui/PresetImportResult.kt` | `parsePresetImport()` 中 `Preset(...)` 改为 `presetDto.toPreset(explicitId=idGenerator())` | -5 / +2 |
| `ui/SwitchController.kt` | 创建 request → 传 dirty 给 dialog → 同一 request 传给 `executeSwitch()` | ~10 |
| `ui/SwitchPreviewDialog.kt` | 接收 `ResolvedSwitchRequest`；纵向 summary panel + `buildSummaryText()` + `shouldShowForceWarning()` | +25 |
| `ui/BranchSwitcherPanel.kt` | `openSettings()` + `detectCurrentState()` 末尾调 `presetManager.refreshAllGlobalLabels()` | +2 |
| `action/SwitchPresetAction.kt:99-104` | 创建 request + Force 确认 + executor 接收 request | ~10 |
| `switch/SwitchExecutor.kt` | `execute(preset, options)` 改为 `execute(request)` | ~5 |
| `switch/PullStep.kt` | 移除 `preset.pullEnabled` 判断 | -1 |
| `resources/.../Bundle*.properties` ×2 | +~10 key | +20 |
| `test/.../PresetConfigTest.kt` | +merge 矩阵 + pull 迁移测试 | +40 |
| `test/.../ResolvedSwitchRequestTest.kt` | request factory、已解析快照与 executor API 合同 | +30 |
| `test/.../PresetJsonTest.kt` | +round-trip + old pull 迁移 | +20 |
| `test/.../PresetLoaderTest.kt` | `pullEnabled` → `overrides.pull`；覆盖 legacy pull 自动写回 | ~25 |
| `test/.../ui/PresetImportRulesTest.kt` | 覆盖 import overrides、trim 与 fresh ID | ~15 |
| `test/.../ui/SwitchPreviewDialogTest.kt` | 覆盖 Force 警告显示条件纯函数 | ~15 |
| `SwitchExecutorTest.kt` / `SwitchIntegrationTest.kt` / `LargeRepoScalabilityTest.kt` / `AppLoggerTest.kt` | 33 处 `executor.execute(preset, options)` 迁移为 `executor.execute(ResolvedSwitchRequest.resolve(preset, options))`；不包含 `DeriveBranchExecutor.execute(preset, branchName)` | ~40 |
| `test/.../switch/SwitchStepTest.kt` | **语义重写**（非机械删除）：`pullEnabled=false` + `pull=true` 的双条件跳过测试 → 重写为 `options.pull=false` 单条件；`pullEnabled=true` + `pull=true` 执行测试 → 简化为 `options.pull=true` | ~15 |
| `test/.../PropertyTest.kt` | 生成器移除 `pullEnabled` 参数；相等性断言改为比较 `overrides` | ~10 |
| `test/.../` (机械) | Preset 构造函数移除 `pullEnabled` 参数（PresetJsonTest、PresetLoaderTest、PresetImportRulesTest 等剩余引用） | ~5 |
| **总计** | | **~420 行** |

## 7. 完整状态矩阵（P2-2 修复）

```
Phase               / Success                      / Blocked                       / Error
─────────────────────┼──────────────────────────────┼───────────────────────────────┼─────────────────────
Load JSON           │ overrides 解析成功            │ 无 overrides 字段             │ malformed dirty
                    │ → PresetOverrides             │ → null（向后兼容）            │ 枚举名 → 单字段跳过
                    │                              │                              │ 不阻断 preset
Load: old pull      │ pull=false → overrides.pull   │ pull=true → null（默认）     │ N/A
migration           │ =false；触发统一格式写回       │ 写回删除顶层 legacy pull，   │
                    │                              │ 不生成冗余 override          │
Load: import        │ 复用 toPreset() → overrides   │ 重名 → 跳过                  │ 非法名 → 跳过
                    │ 正确带入                      │                              │ + invalidNames
Save JSON           │ 含 overrides 的 PresetFile    │ N/A                          │ 原子写入失败
                    │ 序列化                        │                              │ → log error
                    │                              │                              │ + keep dirty flag
                    │                              │                              │ （不新增 notify；
                    │                              │                              │ 保持现有行为）
Edit override       │ 用户改 override → dirty       │ 重名 preset →                │ N/A
                    │ flag on → save                │ nameValidator 拦             │
Edit: revert        │ applyOverridesToUI() 恢复     │ N/A                          │ N/A
                    │ original.overrides            │                              │
UI: initial load    │ applyOverridesToUI() 从       │ N/A                          │ N/A
                    │ original.overrides 填充 combo │                              │
Switch execute      │ effectiveOptions() 正确合并   │ N/A                          │ cancel 不受影响
(preset+global)     │                              │                              │
Switch: Force dirty │ Dry-run 预览显示 Force 标记   │ 用户取消确认                  │ N/A
                    │ + 快捷键确认对话框            │                              │
Settings change     │ refreshGlobalLabels() 更新    │ N/A                          │ N/A
                    │ combo index-0 文本            │                              │
Import JSON         │ 含 overrides 的导入正确       │ 重名/非法名 → 跳过           │ 剪贴板异常
                    │ 生成新 id                     │                              │ → log + notify
Export JSON         │ 含 overrides 的 Preset 正确   │ N/A                          │ 剪贴板异常
                    │ 序列化到剪贴板                │                              │ → log + notify
```

## 8. 行为合同

```text
行为合同：
  给定 preset.overrides = {dirty: Force, pull: false}，全局 dirty=Stash, pull=true，
  当调用 service.resolveSwitchRequest(preset) 得到 ResolvedSwitchRequest，
  然后 executeSwitch(request)，则所有仓库以 dirty=Force + pull=false 切换。

失败合同：
  如果 overrides JSON 中 dirty 枚举名非法，跳过该 dirty 字段（=null，fallback 全局），
  保留合法 pull/fetchFirst，不阻塞 preset 加载。
  如果 overrides 字段合法但运行时 git 命令失败，按现有 fail-partial 逻辑回滚。
  如果 preset JSON 在项目内被共享且 dirty=Force，面板 Dry-run 预览 + 快捷键确认
  均会醒目告知用户 Force 策略的风险。
  Save JSON 原子写入失败：log error，保持 dirty flag 不自动清除（沿用现有行为，不新增 notify）。

取消合同：
  overrides 是数据层的静态配置，不引入新的取消边界。cancel 行为由现有 SwitchExecutor 处理。
  取消发生时 overrides 不产生额外清理需求。

Settings 变更合同：
  给定 Settings 页修改全局 dirty 为 Skip，
  当重新打开 PresetEditor，则所有 override combo 的 index-0 标签刷新为新全局值，
  已有 override 的 preset 不受影响（继续使用 override 值）。
```

## 9. 不在范围

- ❌ Per-preset `timeoutSeconds`（系统级资源约束，不应 per-preset）
- ❌ Per-preset `confirmBeforeInit`（安全守卫，不应 per-preset 关闭）
- ❌ Settings 页面显示各 preset 覆盖摘要（P2，可后续加）
- ❌ Per-preset `requireClean`（derive 专用，不在本次 SwitchOptions 范围内）

## 10. 实现顺序

1. **数据模型 + DTO + migration** — `model/PresetConfig.kt`, `PresetLoader.kt`, `ui/PresetImportResult.kt`
2. **纯逻辑与入口测试** — merge 矩阵 + pull 迁移/写回 + import + malformed dirty + JSON round-trip
3. **PullStep 简化** — 移除 `preset.pullEnabled` 判断
4. **PresetEditor UI** — override 折叠行 + applyOverridesToUI + refreshGlobalLabels
5. **类型级接入** — `SwitchExecutor.execute()` 只接收 `ResolvedSwitchRequest`；两个入口调用 `service.resolveSwitchRequest()`，预览与执行共享同一对象
6. **入口与测试迁移** — 更新 2 个生产调用点和 33 个测试调用点，运行编译/相关测试，确认不存在旧 `SwitchExecutor.execute(preset, options)` 调用；不要改动 `DeriveBranchExecutor.execute(preset, branchName)`
7. **更新现有测试** — `SwitchStepTest` 重写双条件语义（`pullEnabled` + `options.pull` → 仅 `options.pull`）；`PropertyTest` 生成器/相等性迁移到 `overrides`；其余测试机械移除 `pullEnabled` 参数 + executor 调用迁移为 `ResolvedSwitchRequest`

## 11. 测试计划

### 11.1 Pure logic (JUnit, no runtime)

```kotlin
// effectiveOptions merge matrix
@Test fun `null overrides returns global unchanged`()
@Test fun `all overrides set — global completely replaced`()
@Test fun `partial overrides — only overridden fields differ`()
@Test fun `empty PresetOverrides (all null) same as null`()
@Test fun `confirmBeforeInit always from global`()

// ResolvedSwitchRequest
@Test fun `request resolves preset overrides over global options`()
@Test fun `request keeps preset and effective options as one resolved snapshot`()
@Test fun `preview dirty and executor options come from same request`()
@Test fun `service request factory maps all global option fields`()

// Pull migration
@Test fun `old pull:false without overrides → overrides.pull=false`()
@Test fun `old pull:true without overrides → overrides null`()
@Test fun `old pull:false with explicit overrides.pull → overrides.pull wins`()
@Test fun `old pull:false with overrides.dirty only → overrides.pull=false + dirty preserved`()
@Test fun `any legacy pull value requires migration write-back`()

// Malformed field
@Test fun `malformed dirty enum → field null, pull/fetch preserved`()
@Test fun `all overrides malformed → returns null`()

// JSON round-trip
@Test fun `preset with overrides survives serialize-deserialize`()
@Test fun `old JSON without overrides loads with overrides=null`()
@Test fun `JSON with partial overrides — only set fields non-null`()

// Gson null safety — PresetFileDto
@Test fun `empty JSON object ({}) loads as PresetFileDto(presets=null) without NPE`()
@Test fun `JSON with presets=null loads without NPE`()
@Test fun `JSON with single null preset in list → filtered out by toPresetFile`()

// Force warning condition
@Test fun `Force with dirty repo shows warning`()
@Test fun `Force with clean repos does not show warning`()
@Test fun `Force with existing repo but dirtyCount=-1 (unknown) shows warning`()
@Test fun `Force with non-existing dir (exists=false, dirtyCount=-1) does not show warning`()
@Test fun `non-Force with dirty repo does not show warning`()
```

### 11.2 PresetEditor UI

- 新 preset 默认无 overrides，⚙ 为默认色
- 改 dirty 为 "Force" → ⚙ 变色 → save 按钮 enable
- Revert → ⚙ 恢复默认色 → save disable
- 展开/折叠覆盖行不触发 dirty
- 修改 Settings 页全局 dirty → 回到面板看到 combo index-0 标签已刷新
- 打开已有 override 的 preset → combo 显示 override 值而非 "Use global"
- Override combo 初始项与 Settings 刷新后的标签均来自 `Bundle.msg()`，中文环境不混入硬编码英文

### 11.3 SwitchPresetAction

- Force + dirty repo → 快捷键弹出 Force 确认对话框
- Force + clean repo → 不弹 Force 确认（复用 `shouldShowForceWarning`）
- 非 Force + dirty repo → 不弹 Force 确认
- 非 Force override → 快捷键正常弹出 preset 列表选择

### 11.4 Loader / Import entry points

- `PresetLoader.load()`：`pull:true`、`pull:false`、legacy pull + explicit `overrides.pull` 均触发 `migrationSaver`
- Loader 写回的 domain model 不再含 legacy 顶层 `pull`；显式 override 优先且其他 override 字段保留
- `parsePresetImport()`：partial overrides 正确保留，name/main trim，输入旧 ID 被 `idGenerator()` 的 fresh ID 替换
- import 的 malformed dirty 仅丢弃 dirty 字段，合法 pull/fetchFirst 保留

### 11.5 Entry/API contract

- 编译期合同：`SwitchExecutor.execute()` 不再提供 `(Preset, SwitchOptions)` 重载，只接受 `ResolvedSwitchRequest`
- 搜索所有生产与测试调用点，全部迁移为 `executor.execute(request)`
- 两个生产入口均调用 `service.resolveSwitchRequest(preset)`；`BranchSwitcherServiceTest` 覆盖四个 global 字段映射
- `ResolvedSwitchRequestTest` 验证 override 合并结果以及 preview/executor 共享同一个 resolved request
- 新增 Force + dirtyCount=-1 测试，验证既有仓库未知 dirty 状态时仍显示/弹确认

## 12. i18n keys（新增）

```
option.override.title       = Overrides                 / 选项覆盖
option.override.use.global  = Use global ({0})          / 使用全局 ({0})
option.override.on          = On                        / 开
option.override.off         = Off                       / 关
option.override.summary     = Overrides: {0}            / 覆盖: {0}
option.override.summary.force = Force — attempts switch without stashing; checkout may fail on conflicts / Force — 尝试不 stash 直接切换；checkout 可能因冲突失败
option.override.gear.tip    = Preset override options   / Preset 选项覆盖
dialog.force.confirm.title  = Force Dirty Strategy      / Force 脏工作区策略
dialog.force.confirm.msg    = Preset ''{0}'' uses Force dirty strategy. Uncommitted or unreadable changes will attempt switch without stashing — checkout may fail on conflicts. Continue? / Preset「{0}」使用 Force 策略，未提交或无法读取的改动将尝试不 stash 直接切换 — checkout 可能因冲突失败。继续？
dialog.force.warn           = ⚠ Force: dirty or unreadable working trees — checkout may fail on conflicts / ⚠ Force：仓库存有脏改动或状态未知 — checkout 可能因冲突失败
```
