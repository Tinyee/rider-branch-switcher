# Implementation Review Checklist

用于功能代码写完后的实现审查、复审和全量代码走查。目标是把审查从“侦探式找线索”改成“海关式分层过机”：每次都按固定入口扫一遍，避免同类问题反复漏掉。

每一项必须满足以下之一：

- `VERIFIED`: 附源码位置、检索结果、测试方法或验证命令。
- `OPEN`: 写入 `docs/ai-review-current.md`，包含优先级、证据、影响、建议修复和验证方式。
- `ACCEPTED`: 非阻塞或延期，写明原因、风险和后续触发条件。
- `N/A`: 写明为什么不适用于本次改动。

不得只验证上一轮问题，也不得因为编译通过就宣布实现完成。复审 `FIXED_PENDING_REVIEW` 时，必须同时检查二阶回归。

## Review Modes

### Initial Implementation Review

用于代码刚写完或用户要求“审查改动”。

- [ ] 读取设计文档、`docs/ai-review-current.md`、`git status --short`、`git diff --stat`。
- [ ] 枚举全部改动文件，按生产代码、测试、资源、文档、构建脚本分类。
- [ ] 标记本轮审查目标：bug 修复、feature 实现、UI 调整、测试补充、文档一致性或全量架构审查。
- [ ] 区分新引入回归、历史债顺手修复和纯文档/测试同步。

### Fix Verification Review

用于用户说“已修改，复审一下”或共享文档状态为 `FIXED_PENDING_REVIEW`。

- [ ] 重新读取对应源码和测试，不信任文档里的状态文字。
- [ ] 验证原问题是否真的被修复。
- [ ] 检查修复新增的参数、状态、分支、文案、控件、测试迁移是否产生二阶回归。
- [ ] 确认验证命令真实运行并记录结果。
- [ ] 没问题才把状态改为 `VERIFIED`；仍有问题则改回 `OPEN` 并写明原因。

### Full Code Review

用于用户要求“全量审查代码、架构、可读性、可扩展性、可复用性”。

- [ ] 先跑轻量结构检索，再读核心调用链。
- [ ] 不只看 diff，也要看与改动交互的旧代码。
- [ ] 输出时按 P0/P1/P2/P3 排序；没有阻塞问题时明确说明剩余风险。

## Fixed Review Pipeline

按下面顺序执行。前一层发现 P0/P1 时，先记录或修复，不要继续乐观宣布后续层都没问题。

### 1. Scope And Diff

- [ ] 每个生产改动能映射到设计要求、审查修复或明确说明的历史债。
- [ ] 修复没有扩大到无关模块；若扩大，记录原因和额外验证。
- [ ] 新字段、参数、方法、类型的所有调用点已用 `rg -n` 枚举。
- [ ] 删除或替换 API 后没有兼容 helper 掩盖未完成迁移。
- [ ] 没有未使用变量、遗留 import、重复 callback 或只为过门禁添加的 suppress。

Recommended commands:

```bash
git status --short
git diff --stat
rg -n "<NewTypeOrMethodOrField>" src/main src/test
```

### 2. Architecture Boundaries

- [ ] `switch/` 不依赖 `ui/`。
- [ ] 所有 Git 操作通过 `GitClient`；裸 `ProcessBuilder("git")` 只能在 `GitOps` 或明确允许的测试/工具代码里出现。
- [ ] 新的 GitClient 方法在生产实现、测试 fake、mock 和调用点里都完成迁移。
- [ ] 平台层、UI 层、业务纯逻辑边界清晰；业务规则优先放到可测试纯函数或 service/switch 层。
- [ ] 新增多个相关 callback/参数时，优先聚合为有语义的 provider/config/value object。

Recommended commands:

```bash
rg -n "^import.*\\.ui\\." src/main/kotlin/com/submodule/branchswitcher/switch
rg -n "ProcessBuilder\\(\"git\"" src/main/kotlin src/test/kotlin
rg -n "interface GitClient|class GitOps|GitClient by|: GitClient" src/main/kotlin src/test/kotlin
```

### 3. Entry Points And Call Chain

- [ ] 所有生产入口均已接线，包括 Tool Window、Action、快捷键、菜单、Settings、后台回调。
- [ ] 预览、确认和执行使用同一个 resolved snapshot，不在中途重新读取可变设置导致漂移。
- [ ] Loader/import/controller/action 等真实入口有测试或明确的手工验证理由。
- [ ] 修一处模式问题后，已检索并检查全部同类位置。

Call-chain table:

| Entry point | Orchestration | Pure logic / switch step | Side effects | Cleanup | Status |
|---|---|---|---|---|---|
| | | | | | |

### 4. State Machine And Failure Matrix

- [ ] 成功、部分失败、阻塞、错误、取消、unknown、cleanup failure 都有明确行为。
- [ ] `unknown` / probe failure / parse uncertainty 默认 fail-closed，不伪装成 clean 或 success。
- [ ] broad `catch (Exception)` 不吞 `CancellationException` 或 `ProcessCanceledException`。
- [ ] `Fatal`、`Partial`、普通失败和取消的日志、通知、返回值语义不混淆。
- [ ] rollback / cleanup 使用独立 operation，失败不会覆盖原始关键错误。

State matrix:

| State | Trigger | User-visible result | Side effects | Cleanup | Test / Evidence | Status |
|---|---|---|---|---|---|---|
| success | | | | | | |
| partial failure | | | | | | |
| blocked | | | | | | |
| fatal error | | | | | | |
| cancellation | | | | | | |
| cleanup failure | | | | | | |
| unknown | | | | | | |

### 5. Async Write Lifecycle

- [ ] 每条异步写路径都具备 `tryStartWrite` -> `beginOperation` -> cancellable background task -> `onCancel` / `onFinished` -> `endOperation` -> `finally endWrite`。
- [ ] `TaskBridge.runBackground` 有匹配的取消和完成处理。
- [ ] service/project disposed 后不会继续更新 UI 或持久化状态。
- [ ] Alarm、listener、Disposable、invokeLater 都绑定正确生命周期。

Recommended commands:

```bash
rg -n "TaskBridge\\.runBackground|beginOperation|onCancel|onFinished|endOperation" src/main/kotlin
rg -n "tryStartWrite|endWrite" src/main/kotlin
rg -n "invokeLater|Alarm\\(|Disposable|connect\\(" src/main/kotlin
```

### 6. UI And User Walkthrough

从用户视角走查，不只看组件树。

- [ ] 每个控件用途无需依赖排列顺序、源码知识或猜测即可识别。
- [ ] 相邻且选项内容相似的控件有独立可见标签或等价可访问标识。
- [ ] 初始化、编辑、保存、保存失败、回退、重载、Settings 刷新不会静默丢状态。
- [ ] 展开/折叠、搜索、刷新等展示操作不会误改 dirty 状态。
- [ ] 当前值、未保存值、禁用态、错误态、成功态反馈准确。
- [ ] 用户可见文本均来自 `Bundle.msg()`，所有 locale key 对称。
- [ ] 窄 Tool Window、HiDPI、主题色和 disposal 行为已检查。

Recommended commands:

```bash
rg -n "Bundle\\.msg\\(\"[^\"]+\"\\)" src/main/kotlin
rg -n "label\\.|button\\.|message\\.|notification\\." src/main/resources
```

### 7. External Input And Persistence

对 JSON、配置、剪贴板、持久化状态、导入文件逐项检查：

- [ ] 字段缺失。
- [ ] 显式 `null`。
- [ ] 空集合。
- [ ] 集合包含 `null`。
- [ ] 非法枚举或非法值。
- [ ] 重复或空 ID。
- [ ] 新旧格式同时存在时的优先级。
- [ ] 迁移后的持久化文件形态和自动写回条件。
- [ ] 写回失败不会静默丢数据或错误清除 dirty 状态。
- [ ] 所有 DTO -> domain 路径复用同一转换规则。

### 8. Test Plan Reconciliation

- [ ] 设计测试计划中的每个条目都映射到实际测试方法，或明确标为 `OPEN` / `ACCEPTED`。
- [ ] 新增安全门禁、纯函数、迁移和真实入口都有自动化覆盖。
- [ ] 测试验证行为和副作用，不只验证 data class 字段、`copy()` 或 Boolean 表达式。
- [ ] API 迁移测试直接表达新合同，不用兼容 helper 保留旧调用形状。
- [ ] 每个修复至少有一个能在缺陷版本失败、修复版本通过的回归测试，或说明无法自动化原因。
- [ ] 断言覆盖关键副作用：stash list、当前分支、持久化文件、通知决策、operation 生命周期。
- [ ] 目标测试优先；完整 `test`、真实 Git 集成、`releaseCheck` 串行运行。

Test mapping table:

| Requirement / bug | Test method | Would fail before fix? | Side effects asserted | Status |
|---|---|---|---|---|
| | | | | |

### 9. Static Gates And Low-Load Validation

默认低负载，不并行启动重型 Gradle 命令。

- [ ] `quickCheck` 通过。
- [ ] `detekt` 通过。
- [ ] 生产与测试代码能编译。
- [ ] `git diff --check` 通过；换行提示可以记录为 warning，不等于空白错误。
- [ ] 相关目标测试通过；未跑的测试必须记录为未验证，不得声称通过。

Recommended low-load commands:

```bash
./gradlew quickCheck --max-workers=1 --no-parallel
./gradlew compileKotlin compileTestKotlin --max-workers=1 --no-parallel
./gradlew detekt --max-workers=1 --no-parallel
./gradlew test --tests "<ClassOrMethod>" --max-workers=1 --no-parallel
git diff --check
```

### 9.1 Review-To-Test Budget

用于 Codex 和 Claude 互相审查同一批改动时减少重复测试。默认不要每轮复审都跑 Gradle；先按证据决定本轮需要哪一级验证。

Development loop budget:

- Do not run tests after every small edit. Finish a coherent mini-batch first: production change + matching test/doc update, or a pure documentation batch.
- During active editing, prefer static inspection and focused `rg` checks. Save Gradle work for the first stable checkpoint, handoff, review request, or completion claim.
- If a mini-batch only changes docs, comments, review status, screenshots, or planning text, do not run Gradle; run `git diff --check` only.
- If a mini-batch changes code but not behavior, run static gates first. Escalate to compile/tests only when signatures, generated resources, test code, or runtime behavior changed.
- Do not rerun the same target test after every one-line fix unless that line directly changes the failing behavior. Accumulate follow-up fixes, then rerun once.
- Batch documentation synchronization such as test counts in README/ROADMAP/SETUP/AGENTS/plugin.xml; pure sync changes reuse the latest code-test evidence.

Level 0 - No Test, Review Only:

- 适用：只改文档、注释、审查状态、计划、截图说明，或复审者只是在核对上一轮证据。
- 必做：`git diff --stat`、相关源码/文档读取、必要的 `rg` 静态检索。
- 禁止：没有新代码风险时重复跑 `test`、`detekt` 或同一个目标测试。
- 记录：写明“未跑测试，原因是本轮无代码/行为变更”。

Level 1 - Static Gates Only:

- 适用：构建脚本、quickCheck 规则、文案/i18n、轻量重构、调用点迁移但行为不变。
- 必跑：`./gradlew quickCheck --max-workers=1 --no-parallel` 和 `git diff --check`。
- 按需：如果 Kotlin 签名、import、构造函数或测试代码改了，再跑 `compileKotlin compileTestKotlin`。
- 不跑：完整 `test`，除非静态检查发现风险或调用链涉及真实行为。

Level 2 - Targeted Tests:

- 适用：生产逻辑、状态机、异常处理、GitClient/GitOps 合同、持久化、导入解析、UI controller/action 入口发生变化。
- 必跑：最小相关测试类或方法，使用 `--max-workers=1 --no-parallel`。
- 示例：改 `GitOps` 跑 `GitOpsTest`；改 derive 跑 `SwitchIntegrationTest` 相关类；改 TaskBridge 跑 `TaskBridgeLifecycleTest`。
- 记录：写清楚为什么这些测试覆盖本次风险，哪些测试未跑。

Level 3 - Targeted Tests With Rerun:

- 适用：准备把共享审查问题标为 `VERIFIED`、声称功能完成、修复取消/rollback/持久化/数据迁移等容易受缓存影响的路径。
- 必跑：相关目标测试加 `--rerun-tasks`，或至少说明为什么本轮不需要强制重跑。
- 不要默认全量：除非改动跨多个核心模块，目标测试无法覆盖风险。

Level 4 - Broad Validation:

- 适用：提交前、合并前、跨模块架构改动、测试基础设施改动、Gradle 配置影响广、用户明确要求“全量验证”。
- 推荐：`./gradlew test detekt --max-workers=1 --no-parallel`。
- 发布/推送前：才考虑 `releaseCheck`，启动前说明耗时和负载。

Escalation triggers:

- 新增或修改 `GitClient` 方法。
- 修改 `TaskBridge`、`SwitchRunner`、`SwitchExecutor`、`DeriveBranchExecutor`、rollback、write gate、operation lifecycle。
- 修改 DTO/domain 转换、导入/导出、持久化 schema、preset ID/override 解析。
- 修改 quickCheck/detekt/Gradle task/test fixture。
- broad `catch (Exception)`、取消异常、unknown/fail-closed 行为发生变化。
- 用户或另一 AI 声称“已修复”但没有给出对应测试证据。

De-duplication rules:

- 同一个 commit/diff 上，已有 PASS 的命令可以复用，不必每轮重复跑；但必须确认代码没有在该命令之后再次变化。
- 如果变化只影响文档，复用上一轮代码测试结果，并只跑 `git diff --check`。
- 如果变化影响测试本身，不能只复用旧测试结果；至少跑被修改的测试类。
- 如果复审只发现文档状态需要压缩，不跑测试。
- 任何未运行的测试必须写成“未运行 + 原因”，不能写成 PASS。

### 10. Documentation And Shared Review

- [ ] `docs/ai-review-current.md` 只保留活跃详情；已验证项压缩为摘要。
- [ ] 文档明确区分新引入回归、历史债和上一轮漏审。
- [ ] 验证命令及 PASS/FAIL/timeout 结果如实记录。
- [ ] 测试超时或未运行时，不得声称测试通过。
- [ ] 测试数量、ROADMAP、SETUP、README、plugin.xml、AGENTS.md 按需同步。
- [ ] 重要 P0/P1、跨模块设计决策或用户明确要求时才归档完整 review。

## Completion Gate

```text
[ ] Scope and diff checked
[ ] Architecture boundaries checked
[ ] Entry points and call chain checked
[ ] State/failure matrix checked
[ ] Async write lifecycle checked
[ ] UI/user walkthrough checked or N/A recorded
[ ] External input and persistence checked or N/A recorded
[ ] Test plan reconciled
[ ] quickCheck PASS
[ ] detekt PASS
[ ] compileKotlin + compileTestKotlin PASS
[ ] git diff --check PASS
[ ] Relevant target tests PASS, or missing verification recorded as OPEN
[ ] docs/ai-review-current.md has no unresolved P0/P1 unless explicitly accepted

Result: PASS / CHANGES_REQUESTED
Residual non-blocking risks:
```

## Findings Format

写入 `docs/ai-review-current.md` 时使用这个格式，避免只写一句“有问题”：

```markdown
### REVIEW-ID - Short title

- Status: `OPEN`
- Priority: P0 / P1 / P2 / P3
- Evidence: file/line, grep result, failing test, or call-chain proof
- Impact: user-visible risk or maintenance risk
- Suggested fix: concrete change, not vague advice
- Verification: command or test method that proves the fix
```
