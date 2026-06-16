# 后续工作建议（2026-06-13）

## 当前判断

当前核心切换安全问题、UI 重构、设置页、结构化日志、CI、Detekt、Plugin Verifier
和 270 个自动化测试均已落地。项目下一阶段不应继续进行大规模重构，而应优先补齐：

1. 文档和版本信息的持续一致性。
2. Marketplace 发布物料。
3. 少量高价值回归测试。
4. 大仓真实耗时 benchmark 与发布前手工检查。

历史评审文档应视为问题发现记录，不应直接代表当前代码状态。

---

## 推荐执行顺序

### P1：同步文档、版本与发布信息 ✅

已完成：

- `build.gradle.kts`、README、CHANGELOG 和 ROADMAP 已同步到 `0.7.0`。
- README Release 链接和 `plugin.xml` vendor 地址均指向 `Tinyee/rider-branch-switcher`。
- `docs/code-review-2026-06-08.md` 已标记为历史归档。
- `noFocusRing()` 工厂化、CI、Detekt、Plugin Verifier、Settings、结构化日志等状态已在 ROADMAP 中反映。
- 当前测试数量已同步为 270 tests / 21 classes（基于最近一次 `build/test-results/test` 报告）。

仍需保持：

- 每次测试数量或版本号变化后，同步 README badge、AGENTS、ROADMAP、CHANGELOG。
- 历史评审文档只作为问题发现记录，不重新当作当前待办列表。

验收标准：

- README 描述与当前界面、功能和仓库地址一致。
- ROADMAP 不再把已完成事项列为待办。
- CHANGELOG 包含 UI 重构、结构化日志和测试增强。
- 版本号在构建配置、README 和 CHANGELOG 中一致。

### P1：补 Marketplace 发布物料

LICENSE ✅ 已添加（MIT）。
`pluginIcon.svg` ✅ 已添加。

当前主要剩余缺口：

- README 截图仍是 TODO。
- `plugin.xml` 英文描述较短。

建议动作：

- 准备一张 1280x800 左右的英文界面截图，展示 2-3 个 preset、状态点和日志区。
- 扩充英文描述，说明目标用户、核心能力、数据存储位置和安全策略。
- 在发布前执行 `./gradlew releaseCheck`，验证最终 ZIP。

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

已实现：`GitClient` 操作级取消生命周期、取消后阻止启动后续 Git 命令、
`TaskBridge.runBackground` 加 `onCancel` / `onFinished` 回调、`SwitchPresetAction`
和 `SwitchController` 双入口均已接入。

## P2：后续增强

### 大仓规模调用预算与性能基准

已增加 `LargeRepoScalabilityTest`，在 50 个子模块场景下验证 Switch pipeline 和
Preflight 的 Git 调用预算，防止重复查询回归。

后续真实耗时测量应放入独立 benchmark task，不加入普通 `test`：

- Preflight 和状态刷新真实 Git CLI 耗时。
- 多 preset 展开耗时。
- 是否需要限制或引入安全的并发读取。

### TaskBridge 生命周期测试 ✅ (2026-06-13)

生产接入已完成，直接生命周期测试已补完（`TaskBridgeLifecycleTest`，9 用例）：
- 正常完成、block 抛异常、用户取消、父协程取消（前/中）、回调幂等。
- runner 同步抛异常传播、onCancel/onFinished 回调异常已记录日志不传播。
- 竞态修复：父协程在 Task 启动前取消不再允许 block 执行。
- 可注入 `TaskRunner` 边界，测试无需 IntelliJ 运行时。

### releaseCheck 自动化 ✅

`./gradlew releaseCheck` 聚合：
- `test` + `detekt` + `buildPlugin` + `verifyPlugin`
- README 版本 badge + CHANGELOG 最新版本精确校验
- ZIP 名称、LICENSE 存在性检查
- 非致命提醒：README screenshot TODO

### 首次安装引导

已在无预设空状态中增加轻量 Quick Start，引导说明：

- 如何从当前状态创建 preset。
- 如何执行预览和切换。
- `Ctrl+Alt+B` 快捷键。
- Preset 文件位置及团队共享方式。

后续仅需在发布检查中验证窄 Tool Window 和中英文布局。

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
