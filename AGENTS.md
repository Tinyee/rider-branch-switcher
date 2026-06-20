# 项目上下文

## 概述

Rider 插件 — 一键将主仓库和所有子模块切换到预设的分支组合。

- **技术栈**: Kotlin 2.3, IntelliJ Platform Gradle Plugin 2.2.1, Gradle 8.13, JUnit 4 + Kotest 5.9
- **目标**: JetBrains Rider 2026.1 (build 261)
- **测试**: 291 tests / 27 classes (14 core + 13 platform)
- **版本**: 0.7.0

## 架构

```
core/…/com/submodule/branchswitcher/   # 纯 JVM 模块，无 IntelliJ 依赖
├── git/          GitClient (接口)
├── log/          AppLogger (接口), LogEntry
├── model/        Preset, PresetFile, DirtyAction, SwitchOptions
├── ui/           PresetImportResult, UiLayoutRules
├── switch/       SwitchStep, SwitchExecutor, SwitchPreflight, CheckoutStep, DeriveBranchExecutor
├── settings/     SettingsRules
└── PresetLoader.kt  JSON 读写 (.idea/branch-presets.json)

src/…/com/submodule/branchswitcher/    # IntelliJ Platform 模块
├── git/          GitOps (CLI 实现)
├── service/      BranchSwitcherService, TelemetryService, PresetRepository
├── platform/     SwitchRunner, SwitchAdapters, ProgressCancellationHandle, refreshVcsRepos
├── ui/           Panel, Editor, Dialog, Controller, SwitchFlowCoordinator, ToolWindowFactory
├── action/       SwitchPresetAction (Ctrl+Alt+B)
├── settings/     BranchSwitcherConfigurable
├── log/          ToolWindowLogger (AppLogger 实现)
├── Bundle.kt     i18n (DynamicBundle, 中英 .properties)
├── Notifier.kt   IDE 通知封装
└── TaskBridge.kt    suspend 封装 (ProgressManager → 协程)
```

### 关键模式
- **切换管道**: `SwitchExecutor` 顺序执行 `List<SwitchStep>`；每步返回 `Ok | Partial(failures) | Fatal`。仅 `Fatal` 中止管道。
- **GitClient 接口**: 所有 git 操作通过 `GitClient` 接口（测试可 mock）。`GitOps` 是 CLI 实现。
- **协程**: `BranchSwitcherService.scope` 管理所有异步工作。`TaskBridge` 将 `Task.Backgroundable` 封装为 suspend 函数。
- **持久化**: Preset 存在 `.idea/branch-presets.json`，选项存在 `branch-switcher.xml` (PersistentStateComponent)。

## 开发命令

```bash
git config core.hooksPath .githooks   # 首次 clone 后执行一次，启用自动检查
./gradlew pureTest      # alias for :core:test; 139 core pure JVM tests (14 classes)
./gradlew test          # 146 platform JUnit tests + 6 Kotest (13 classes)
./gradlew buildPlugin   # → build/distributions/rider-branch-switcher-{version}.zip
./gradlew runIde        # 启动沙箱 Rider，插件已预装
./gradlew quickCheck    # <1 秒，grep 结构检查（git commit / git push 时自动跑）
./gradlew releaseCheck  # quickCheck + core test/detekt + test + detekt + buildPlugin + verifyPlugin（发布前手动跑）
./gradlew pitestCore    # 手动 PITest 变异测试（单线程、纯规则范围，不进 releaseCheck）
```

## 用户偏好

- **不要自动 push**: commit 后等用户说 push 再推送。
- **清理过期任务**: 对话开始/结束时检查 TaskList，已完成的不再追踪。

## 当前待办

- 大仓真实耗时基准测量放入独立 benchmark task，不在普通 test 里设墙钟阈值。
- 手工发布检查缩减为：窄 Tool Window、Settings UI、i18n、安装构建出的 ZIP。
- Marketplace 截图准备发布时再处理；`pluginIcon.svg` 已存在。

2026-06-08 的历史审查已归档至 `docs/code-review-2026-06-08.md`；大部分已修复，不应将归档当作当前问题列表。

## 测试资源与低负载规则

- **开发中先跑最小验证，不要默认跑全量。** 顺序为 `./gradlew quickCheck`；纯 JVM 逻辑跑 `:core:test`（或别名 `pureTest`），平台/集成逻辑再跑相关 `test` 类或方法。
- **重型 Gradle 命令不准并行启动。** 完整 `test`、真实 Git 集成测试、`buildPlugin`、`verifyPlugin`、`releaseCheck` 必须串行；`pureTest` 较轻但也不要和重型 Gradle 命令并行。
- **本地广泛验证默认限流。** 使用 `--max-workers=2 --no-parallel`；用户反馈发热、风扇噪音或机器受限时改为 `--max-workers=1 --no-parallel`。
- **不要靠降低全局测试覆盖率降温。** 不得减少 Kotest 全局迭代次数或跳过测试；应选择目标测试、限流，或增加明确命名的低负载任务。
- **完成与发布分开。** 声称完成前按改动范围运行相关测试的 `--rerun-tasks`；只有发布前运行 `releaseCheck`，启动前说明它耗时且高负载。
- **PITest 只作手动诊断。** `pitestCore` 单线程运行，只覆盖纯规则/决策逻辑；不要放进普通 `test`、`releaseCheck`、git hooks 或频繁复审流程。
- 测试结束后仅在用户希望释放资源或会话结束时运行 `./gradlew --stop`，不要每次测试后都停止 daemon。
- **终端中文乱码不是文件损坏。** PowerShell/Git Bash 读取 Markdown 出现乱码时，先看 `docs/encoding-and-line-endings.md`，不要因为显示问题重写文档。

### AI 互审测试预算

- **开发中也按预算跑。** 不要每改一个文件就跑测试；先完成一个最小闭环（代码 + 对应测试/文档），再按变更类型选择 Level。连续小修只跑静态 grep/quickCheck，等准备声明完成或交给另一 AI 复审时再跑目标测试。

### 架构审查分级

重大重构后做架构审查，问题按优先级分类执行：

- **P2（现在修）**：改动可控、不破坏现有测试结构、消除明确的架构泄漏或重复。例如：CancellationClassifier 抽取、SwitchFlowCoordinator 统一双入口、服务拆分。
- **P3（长期 defer）**：改动范围大、涉及核心抽象重设计、测试需大量重写。例如：SwitchContext mutable state → explicit pipeline、GitClient 接口拆分。这些记录在案但不阻塞当前迭代。
- **修完验证**：每修完一个 P2 项就跑对应测试 + detekt，不攒到最后一起跑——避免多个问题交织定位困难。
- **审查文档归档**：架构审查文档放 `docs/architecture-review-{date}.md`，标记哪些已修、哪些 defer，避免后人重复审查同一问题。
- **文档批量同步。** README/ROADMAP/SETUP/AGENTS/plugin.xml 的测试数量、规则和状态同步应集中做一次；纯文档同步只需要 `git diff --check`，不触发 Gradle。
- **复审默认不重复跑测试。** Codex 和 Claude 互相审查同一批 diff 时，先复用已有 PASS 证据；只有确认相关代码在该命令之后又变化，才升级验证。
- **Level 0: 只审不测。** 仅文档、注释、审查状态、计划或证据压缩变化时，不跑 Gradle；只读 diff / 文档并说明“未跑测试，原因是无代码行为变更”。
- **Level 1: 静态门禁。** 构建脚本、quickCheck 规则、i18n、轻量迁移或调用点替换时，跑 `quickCheck` + `git diff --check`；签名/import/构造函数变化时再加 `:core:compileTestKotlin compileKotlin compileTestKotlin`。
- **Level 2: 目标测试。** 纯模型/JSON/导入解析/settings 规则优先跑 `:core:test --tests ...`；生产逻辑、状态机、异常处理、GitClient/GitOps、持久化、controller/action 入口变化时，只跑最相关 `test --tests ...`。
- **Level 3: 目标测试强制重跑。** 要把共享审查项标为 `VERIFIED`、声称功能完成、或涉及取消/rollback/迁移/持久化时，相关目标测试加 `--rerun-tasks`，除非写明不需要的理由。
- **Level 4: 广泛验证。** 仅提交前、跨模块架构改动、测试基础设施改动、Gradle 配置大改或用户明确要求时，跑 `:core:test test :core:detekt detekt`；发布前才跑 `releaseCheck`。
- **不要把未运行写成通过。** 任何没跑的测试必须记录为“未运行 + 原因”；共享审查文档只能记录真实执行过的 PASS/FAIL/timeout。

```bash
./gradlew quickCheck
./gradlew :core:test --tests "<ClassOrMethod>" --max-workers=1 --no-parallel
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel
./gradlew :core:test test :core:detekt detekt --max-workers=2 --no-parallel
./gradlew :core:test test :core:detekt detekt --rerun-tasks --max-workers=2 --no-parallel
./gradlew releaseCheck
RUN_RELEASE_CHECK_ON_PUSH=1 git push  # 需要 push 时同时跑 releaseCheck 才使用
```

## Recent Changes (v0.6, through 2026-06-14)

- 增强 derive 安全性：预检门禁、checkpoint 门禁、per-repo try/catch、取消后独立 operation 回滚、安全删除派生分支、base branch 校验、fail-closed 探针。
- 统一 TaskBridge / SwitchExecutor / GitOps 的取消生命周期 (`beginOperation` / `cancel` / `endOperation`)。
- `rollbackSwitch` 补上缺失的 operation 生命周期。
- 新增 write gate 互斥锁 (`tryStartWrite` / `endWrite`)，覆盖所有写入口。
- 新增 `DeriveNotification` 纯函数 + 结构化通知决策 + i18n 映射。
- `isGitRepo` 增加 10s 超时。
- 分支名校验 (`isValidBranchName`)。
- 新增 MIT `LICENSE`、`quickCheck` + `releaseCheck` Gradle task、git pre-commit/pre-push hooks（push 默认只跑 quickCheck，releaseCheck 手动或 opt-in）。
- 291 tests / 27 classes 覆盖：139 个 core pure JVM 测试 + 152 个平台/集成测试（含 6 个 Kotest 属性测试），含真实 Git 集成、取消、rollback、derive 安全、通知决策、stash+rollback、50 子模块调用预算。

## 会话流程规则

每条来自本项目的具体失败，不是抽象建议。

### 声称完成前

1. **没过自审清单不准说没问题。** 7 轮审查，每轮都能发现清单能抓到的遗漏。
2. **用户说"审查在本地文档"先去读文档，不要问里面有什么。**
3. **分层验证**：开发中允许增量测试。声称完成前至少跑一次相关测试的 `--rerun-tasks`。发布前跑 `releaseCheck`。

### 修 bug 时

4. **修模式，不修实例。** 在一处找到 bug（缺 try/catch、缺 cancel 检查），grep 所有同类位置一并修。本项目同一个 bug 在 4+ 个文件里各犯了一遍。
5. **分清你改坏的和本来就坏的。** 修审查发现时，明确哪些是你的改动引入的回归，哪些是趁机修的历史债。这建立信任、帮审查者理解范围。
6. **别人审出你漏掉的问题，别光修——分析为什么漏了，加一条规则。** 这份清单就是因为 GPT-5.5 发现了多轮自查都漏掉的问题。
7. **加了有逻辑的 getter 后，确认内部代码没有绕过它用裸字段。** `exportTelemetry()` 用 `options.telemetryInstallId` 绕过了 getter 的 opt-in 检查。加 getter 后 `grep` 同名裸字段确认所有引用点。
8. **replace_all 之前先 grep -n 确认匹配范围。** `270 tests` 一键替换改到了 CHANGELOG 历史记录。逐处确认后再决定全量替换还是逐处改。

### 设计时

7. **先穷举状态机再写代码。** 多阶段 pipeline 先列出所有状态（成功/部分失败/取消/阻塞/错误），验证每个转换再实现。derive 功能 7 轮审查因为状态是边做边发现的。
8. **一开始就提取可测试的纯逻辑。** commit 历史上 `refactor: 抽取纯逻辑` 总是在 feature 之后。如果业务逻辑需要运行时才能测试，一开始就把它跟 UI/平台层分开。
9. **安全关键默认值必须保守。** 探针返回 `null` 意味着"不确定，阻止"，而不是"假设安全"。检查可能失败时，默认解释必须是停，不是过。

### 写测试时

10. **断言所有副作用，不只主结果。** 操作 stash 了文件 → 验证 stash list 为空。切换了分支 → 验证每个仓库当前分支。不完整的断言让 bug 存活。
11. **行为覆盖优于数据结构验证。** 只测 data class 字段、`copy()`、Boolean 表达式 → 维护成本换来零回归保护。本项目已清理 HistoryTest、PresetJsonTest 部分弱测试，以及 SubmoduleRowManager / StepResult / CheckpointEntry 的 11 个低价值结构测试。
12. **基础设施也要测。** write gate、probe 方法、通知决策逻辑最初都无测试。
13. **加测试后批处理同步文档数字。** 测试数变了改 6 个文件（AGENTS/ROADMAP/SETUP/README/plugin.xml/ai-review）。commit 前 `grep` 旧数字确认全替换，不要漏。

### 集成时

14. **每个异步写路径走同一生命周期。** 门禁检查 → 开始操作 → 可取消后台任务 → 结束操作 → finally 释放门禁。本项目的具体链条：`tryStartWrite` → `beginOperation` → `runBackground(onCancel/onFinished)` → `endOperation` → `endWrite`。
15. **文档更新是功能的一部分，不是事后补的。** 每个 feature 分支批量同步一次文档，不是每次 commit 改一遍。同一个会话里改 5 个文件 8 次 = 流程有问题。
16. **接口加方法优先给默认实现。** `GitClient.isGitRepo` 加了 `= File(workDir, ".git").exists()` 默认实现，所有 test fake 自动兼容。日后如果要去掉默认值，先确认所有 fake 已迁移。

### 共享审查流程

17. **“审查并写共享文档”**：审查当前改动，将可执行问题写入 `docs/ai-review-current.md`。每项必须包含状态、优先级、证据、影响、建议修复和验证方式。
18. **“处理共享审查问题”**：先读取 `docs/ai-review-current.md`，逐项核对代码后修复 `OPEN` / `IN_PROGRESS` 项；完成后记录实际修改和验证结果，将状态改为 `FIXED_PENDING_REVIEW`。
19. **复审不信任状态文字。** 收到“复审共享文档”时，必须重新检查对应代码和测试；确认后标记 `VERIFIED`，仍有问题则改回 `OPEN` 并写明原因。
20. **共享文档只保留活跃详情。** `docs/ai-review-current.md` 只详细保留 `OPEN`、`IN_PROGRESS`、`FIXED_PENDING_REVIEW` 和 `ACCEPTED`；复审结束后将 `VERIFIED` 详情压缩为一行摘要。超过 100 行时必须立即压缩。
21. **不得只修改审查文档状态。** `FIXED_PENDING_REVIEW` 和 `VERIFIED` 必须有对应代码证据与验证命令；非阻塞建议使用 `ACCEPTED` 并写明理由。
22. **仅重要审查需要归档。** P0/P1、跨模块设计决策或用户明确要求保留时，才将完整内容归档到 `docs/reviews/ai-review-YYYY-MM-DD.md`；普通 UI/P3 问题不归档。开始新的独立审查时覆盖当前文档的摘要和活跃问题。
23. **实现复审必须检查二阶回归。** 逐项验证旧问题后，还要检查修复新增的参数、状态、分支、文本、控件和测试迁移；运行静态门禁并从用户视角走查 UI。使用 `docs/templates/implementation-review-checklist.md`，不能只把状态改成 `VERIFIED`。
24. **测试计划必须逐项核销。** 声称功能完成前，将设计测试计划映射到实际测试方法；缺失项必须标为 `OPEN` 或有理由的 `ACCEPTED`，不得只写成非阻塞备注。
25. **相似控件必须可辨识。** 相邻且选项内容相似的控件必须有独立可见标签或等价的可访问标识，不能依赖排列顺序让用户猜测含义。
26. **复审结论必须主动写回共享文档。** 只要用户要求“审查”“复审”“再审”“看改动”且当前已有 `docs/ai-review-current.md` 上下文，必须在同一轮把结论、延期/接受理由、验证命令同步更新到该文档；不要等用户再次提醒“写入文档”。

## 提交前自审

两级：轻量（每次改动，秒级 grep）和完整（提交/推送前）。

### 轻量（每次改动）

不需要跑测试，只 grep：

1. **取消对称性**：每个 `TaskBridge.runBackground` 必须有匹配的 `beginOperation` / `onCancel` / `onFinished` / `endOperation`。
   ```bash
   grep -rn "TaskBridge.runBackground" src/main/kotlin | wc -l
   grep -rnc "beginOperation\|onCancel\|onFinished\|endOperation" src/main/kotlin
   ```
2. **写门禁配对**：每个 `tryStartWrite()` 必须在 finally 里有 `endWrite()`。
   ```bash
   grep -rnc "tryStartWrite\|endWrite" src/main/kotlin
   ```
3. **结构边界**：`core/.../switch` 不能 import `com.intellij` 或 `ui/`；裸 `ProcessBuilder("git")` 不能在 `GitOps` 外面。
   ```bash
   grep -rn "^import com\.intellij" core/src/main/kotlin/com/submodule/branchswitcher/switch && echo "FAIL: core switch leaks IntelliJ"
   grep -rn "^import.*\.ui\." core/src/main/kotlin/com/submodule/branchswitcher/switch && echo "FAIL: core switch imports UI"
   grep -rn "ProcessBuilder.*\"git\"" src/main/kotlin | grep -v GitOps && echo "FAIL"
   ```
4. **i18n 对称**：新的 `Bundle.msg` key 必须在两个 locale 文件都存在。
   ```bash
   grep -roh 'Bundle.msg("[^"]*")' src/main/kotlin | sort -u
   ```
5. **测试桩覆盖**：新的 `GitClient` 方法必须在所有 test fake 里有桩（接口在 core，fake 分布在两个模块）。
   ```bash
   grep -rl "GitClient" src/test core/src/test --include="*.kt"
   ```

### 完整（提交前）

轻量通过后跑一次：
```bash
./gradlew :core:test test :core:detekt detekt && git diff --check
```
日常开发用增量 `./gradlew :core:test` 或相关 `test --tests ...`；提交前 `./gradlew :core:test test :core:detekt detekt`。声称完成前相关目标测试加 `--rerun-tasks`。发布前 `releaseCheck`。