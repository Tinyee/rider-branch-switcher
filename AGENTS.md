# 项目上下文

## 概述

Rider 插件 — 一键将主仓库和所有子模块切换到预设的分支组合。

- **技术栈**: Kotlin 2.3, IntelliJ Platform Gradle Plugin 2.2.1, Gradle 8.13, JUnit 4 + Kotest 5.9
- **目标**: JetBrains Rider 2026.1 (build 261)
- **测试**: 241 tests, `./gradlew test`
- **版本**: 0.6.0

## 架构

```
com.submodule.branchswitcher/
├── git/          GitClient (接口) + GitOps (CLI 实现)
├── model/        Preset, PresetFile, DirtyAction, SwitchOptions, PreflightRow
├── service/      BranchSwitcherService (Project Service, PersistentStateComponent)
├── switch/       SwitchStep 管道 + 5 Steps + SwitchExecutor + SwitchPreflight
├── ui/           Panel, Editor, Dialog, ComboUtil, Controller, ToolWindowFactory, ListManager
├── action/       SwitchPresetAction (Ctrl+Alt+B)
├── settings/     BranchSwitcherConfigurable
├── Bundle.kt     i18n (DynamicBundle, 中英 .properties)
├── Notifier.kt   IDE 通知封装
├── PresetLoader.kt  JSON 读写 (.idea/branch-presets.json)
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
./gradlew test          # 241 tests
./gradlew buildPlugin   # → build/distributions/rider-branch-switcher-{version}.zip
./gradlew runIde        # 启动沙箱 Rider，插件已预装
./gradlew quickCheck    # <1 秒，grep 结构检查（git commit 时自动跑）
./gradlew releaseCheck  # quickCheck + test + detekt + buildPlugin + verifyPlugin（git push 时自动跑）
```

## 用户偏好

- **不要自动 push**: commit 后等用户说 push 再推送。
- **清理过期任务**: 对话开始/结束时检查 TaskList，已完成的不再追踪。

## 当前待办

- 大仓真实耗时基准测量放入独立 benchmark task，不在普通 test 里设墙钟阈值。
- 手工发布检查缩减为：窄 Tool Window、Settings UI、i18n、安装构建出的 ZIP。
- Marketplace 截图和 pluginIcon.svg 等准备发布时再处理。

2026-06-08 的历史审查已归档至 `docs/code-review-2026-06-08.md`；大部分已修复，不应将归档当作当前问题列表。

## 测试资源与低负载规则

- **开发中先跑最小验证，不要默认跑全量。** 顺序为 `./gradlew quickCheck`，再跑最相关的测试类或方法。
- **重型 Gradle 命令不准并行启动。** 完整 `test`、真实 Git 集成测试、`buildPlugin`、`verifyPlugin`、`releaseCheck` 必须串行；工具支持并行调用也不能并行跑这些任务。
- **本地广泛验证默认限流。** 使用 `--max-workers=2 --no-parallel`；用户反馈发热、风扇噪音或机器受限时改为 `--max-workers=1 --no-parallel`。
- **不要靠降低全局测试覆盖率降温。** 不得减少 Kotest 全局迭代次数或跳过测试；应选择目标测试、限流，或增加明确命名的低负载任务。
- **完成与发布分开。** 声称完成前按改动范围运行相关测试的 `--rerun-tasks`；只有发布/推送前运行 `releaseCheck`，启动前说明它耗时且高负载。
- 测试结束后仅在用户希望释放资源或会话结束时运行 `./gradlew --stop`，不要每次测试后都停止 daemon。

```bash
./gradlew quickCheck
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel
./gradlew test detekt --max-workers=2 --no-parallel
./gradlew test detekt --rerun-tasks --max-workers=2 --no-parallel
./gradlew releaseCheck
```

## Recent Changes (v0.6, through 2026-06-14)

- 增强 derive 安全性：预检门禁、checkpoint 门禁、per-repo try/catch、取消后独立 operation 回滚、安全删除派生分支、base branch 校验、fail-closed 探针。
- 统一 TaskBridge / SwitchExecutor / GitOps 的取消生命周期 (`beginOperation` / `cancel` / `endOperation`)。
- `rollbackSwitch` 补上缺失的 operation 生命周期。
- 新增 write gate 互斥锁 (`tryStartWrite` / `endWrite`)，覆盖所有写入口。
- 新增 `DeriveNotification` 纯函数 + 结构化通知决策 + i18n 映射。
- `isGitRepo` 增加 10s 超时。
- 分支名校验 (`isValidBranchName`)。
- 新增 MIT `LICENSE`、`quickCheck` + `releaseCheck` Gradle task、git pre-commit/pre-push hooks。
- 241 tests 覆盖：真实 Git 集成、取消、rollback、derive 安全、通知决策、stash+rollback、50 子模块调用预算。

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

### 设计时

7. **先穷举状态机再写代码。** 多阶段 pipeline 先列出所有状态（成功/部分失败/取消/阻塞/错误），验证每个转换再实现。derive 功能 7 轮审查因为状态是边做边发现的。
8. **一开始就提取可测试的纯逻辑。** commit 历史上 `refactor: 抽取纯逻辑` 总是在 feature 之后。如果业务逻辑需要运行时才能测试，一开始就把它跟 UI/平台层分开。
9. **安全关键默认值必须保守。** 探针返回 `null` 意味着"不确定，阻止"，而不是"假设安全"。检查可能失败时，默认解释必须是停，不是过。

### 写测试时

10. **断言所有副作用，不只主结果。** 操作 stash 了文件 → 验证 stash list 为空。切换了分支 → 验证每个仓库当前分支。不完整的断言让 bug 存活。
11. **行为覆盖优于数据结构验证。** 只测 data class 字段、`copy()`、Boolean 表达式 → 维护成本换来零回归保护。本项目已有部分低价值测试被清理（HistoryTest + PresetJsonTest 3 个），剩余约 11 个待清理。
12. **基础设施也要测。** write gate、probe 方法、通知决策逻辑最初都无测试。

### 集成时

13. **每个异步写路径走同一生命周期。** 门禁检查 → 开始操作 → 可取消后台任务 → 结束操作 → finally 释放门禁。本项目的具体链条：`tryStartWrite` → `beginOperation` → `runBackground(onCancel/onFinished)` → `endOperation` → `endWrite`。
14. **文档更新是功能的一部分，不是事后补的。** 每个 feature 分支批量同步一次文档，不是每次 commit 改一遍。同一个会话里改 5 个文件 8 次 = 流程有问题。

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
3. **结构边界**：`switch/` 不能 import `ui/`；裸 `ProcessBuilder("git")` 不能在 `GitOps` 外面。
   ```bash
   grep -rn "^import.*\.ui\." src/main/kotlin/com/submodule/branchswitcher/switch && echo "FAIL"
   grep -rn "ProcessBuilder.*\"git\"" src/main/kotlin | grep -v GitOps && echo "FAIL"
   ```
4. **i18n 对称**：新的 `Bundle.msg` key 必须在两个 locale 文件都存在。
   ```bash
   grep -roh 'Bundle.msg("[^"]*")' src/main/kotlin | sort -u
   ```
5. **测试桩覆盖**：新的 `GitClient` 方法必须在所有 test fake 里有桩。
   ```bash
   grep -l "GitClient" src/test --include="*.kt"
   ```

### 完整（提交前）

轻量通过后跑一次：
```bash
./gradlew test detekt && git diff --check
```
日常开发用增量 `./gradlew test detekt`。声称完成前 `--rerun-tasks`。发布前 `releaseCheck`。
