# 后续工作建议（2026-06-13）

## 当前判断

当前核心切换安全问题、UI 重构、设置页、结构化日志、CI、Detekt、Plugin Verifier
和 150 个自动化测试均已落地。项目下一阶段不应继续进行大规模重构，而应优先补齐：

1. 文档和版本信息的一致性。
2. Marketplace 发布物料。
3. 少量高价值回归测试。
4. 会真实影响用户行为的稳定 Preset ID。

历史评审文档应视为问题发现记录，不应直接代表当前代码状态。

---

## 推荐执行顺序

### P1：同步文档、版本与发布信息

当前存在的已知不一致：

- `docs/ROADMAP.md` 仍写“无 CI/CD”，实际已有三平台 CI、Detekt 和 `verifyPlugin`。
- ROADMAP 中部分 Marketplace 和架构事项已完成，但仍显示为待办。
- ROADMAP 中 `noFocusRing()` 工厂化仍显示未完成。
- README 仍写设置项位于 Tool Window 底部，与当前 UI 不一致。
- README Release 链接使用 `Tinyee`，而 `plugin.xml` vendor 地址使用 `jty75`。
- `build.gradle.kts`、README 和 CHANGELOG 仍停留在 `0.5.0`，未体现近期 UI、
  测试与结构化日志改动。

建议动作：

- 更新 README、ROADMAP、CHANGELOG 和版本号。
- 将 `docs/code-review-2026-06-08.md`、`docs/ui-redesign-plan-2026-06-09.md`
  标记为历史/归档文档，并注明完成状态以当前代码和 ROADMAP 为准。
- 清理 ROADMAP 中已经完成或已经明确暂停的事项。

验收标准：

- README 描述与当前界面、功能和仓库地址一致。
- ROADMAP 不再把已完成事项列为待办。
- CHANGELOG 包含 UI 重构、结构化日志和测试增强。
- 版本号在构建配置、README 和 CHANGELOG 中一致。

### P1：补 Marketplace 发布物料

当前主要发布缺口：

- README 截图仍是 TODO。
- 尚未发现自定义 `pluginIcon.svg`。
- `plugin.xml` 英文描述较短。
- 尚未发现 `LICENSE` 文件。

建议动作：

- 准备一张 1280x800 左右的英文界面截图，展示 2-3 个 preset、状态点和日志区。
- 增加符合 Marketplace 要求的插件 SVG 图标。
- 扩充英文描述，说明目标用户、核心能力、数据存储位置和安全策略。
- 明确许可证并增加 `LICENSE`。
- 在发布前执行 `buildPlugin` 和 `verifyPlugin`，检查最终 ZIP。

验收标准：

- README 和 Marketplace 页面具备图标、截图、完整英文描述和许可证信息。
- 安装包通过 Plugin Verifier。

### P1：补结构化日志契约测试 ✅ (2892a6d)

已实现：`AppLoggerTest` 13 用例，覆盖 createStringAppender 格式、Step 失败 WARN、
Fatal ERROR、Partial WARN、Activity 级别、git init 真实仓库验证。

### P1：为 Preset 增加稳定 ID ✅ (5a9e109)

已实现：`Preset.id` UUID、`PresetDto` 兼容旧 JSON 自动生成、`SwitchHistoryEntry.presetId`、
`undoLastSwitch` 按 ID 优先查找、导入生成新 ID、加载旧 JSON 后自动回写。

---

### 真实取消正在运行的 Git 命令 ✅ (e789848, 后续修订)

已实现：`GitClient.cancel()`、`GitOps` 取消纪元（`AtomicLong` epoch, 无竞态）、
`TaskBridge.runBackground` 加 `onCancel` 回调、`SwitchPresetAction` 和
`SwitchController` 双入口均已接入。

## P2：后续增强

### 大仓性能基准

建议为 50+ 子模块场景建立可重复性能测试，重点关注：

- Preflight 执行的 Git 命令数量和耗时。
- 状态探测是否重复执行同一 Git 命令。
- 多 preset 展开和状态刷新耗时。
- 是否需要限制或引入安全的并发读取。

性能优化应以测量结果为依据，不提前引入复杂并发。

### 首次安装引导

可增加轻量首次安装提示，说明：

- 如何从当前状态创建 preset。
- 如何执行预览和切换。
- `Ctrl+Alt+B` 快捷键。
- Preset 文件位置及团队共享方式。

该项适合在 Marketplace 发布物料完成后实施。

---

## 暂不建议

- 迁移到 git4idea API：改动面和兼容风险较大，当前 CLI 实现已可测试且行为清晰。
- Rider fixture 或 UI 截图测试：基础设施成本高，当前 UI 规则测试和人工发布检查更划算。
- 匿名遥测：当前用户规模和收益不足以覆盖隐私说明与维护成本。
- 继续拆分 `PresetEditor`：现有复杂度尚可控，除非新功能再次明显推高职责。
- 迁移 `TaskBridge` 到不稳定的新进度 API：当前封装已经隔离实现细节，暂时收益有限。

---

## 建议发布检查清单

- `./gradlew test detekt buildPlugin verifyPlugin`
- Stash / Skip / Force 三种 dirty 策略各执行一次。
- 验证目标分支不存在、缺失子模块 init、无 remote 和用户取消。
- 验证 Preset 新建、重命名、导入、导出、删除和撤销。
- 验证窄 Tool Window、Settings 页面和中英文界面。
- 验证 README、版本号、CHANGELOG、截图和安装包名称一致。

