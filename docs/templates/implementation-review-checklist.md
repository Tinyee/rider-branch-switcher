# Implementation Review Checklist

用于功能代码写完后的实现审查与复审。设计是否可开工使用
`docs/templates/design-review-checklist.md`；代码是否可提交使用本模板。

每项必须满足以下之一：

- `VERIFIED`：附源码位置、检索结果或验证命令。
- `OPEN`：写入 `docs/ai-review-current.md`，包含优先级、证据、影响、修复建议和验证方式。
- `N/A`：写明为什么不适用。

不得只验证上一轮问题，也不得因为编译通过就宣告实现完成。

## Review Modes

### Initial Implementation Review

从设计基线和完整 diff 开始：

- [ ] 读取设计文档、共享审查文档、`git status --short` 和 `git diff --stat`。
- [ ] 枚举全部改动文件，并区分生产代码、测试、资源和文档。
- [ ] 按入口 → 编排 → 纯逻辑 → 副作用 → 清理重新走调用链。
- [ ] 将设计测试计划与实际测试名称逐项核销。

### Fix Verification Review

复核 `FIXED_PENDING_REVIEW` 时必须执行两层检查：

1. **原问题验证**：重新读取对应代码和测试，确认修复真实生效。
2. **二阶回归扫描**：检查修复新增的参数、状态、分支、文本、控件和测试迁移是否产生新问题。

复审不能只把状态从 `FIXED_PENDING_REVIEW` 改为 `VERIFIED`。任何新发现写成新的
`OPEN` 项；已验证详情压缩为一行摘要。

## 1. Diff And Scope

- [ ] 每个生产改动都能映射到设计要求、审查修复或明确说明的历史债。
- [ ] 修复没有扩大到无关模块；若扩大，记录原因和额外验证。
- [ ] 新字段、参数、方法和类型的全部调用点已用 `rg -n` 枚举。
- [ ] 删除/替换 API 后没有兼容 helper 掩盖未完成的迁移。
- [ ] 没有未使用变量、遗留 import、重复 callback 或只为过门禁添加的 suppress。

## 2. Behavior And Entry Points

- [ ] 所有生产入口均已接线，包括面板、Action、快捷键、菜单和后台回调。
- [ ] 预览、确认和执行使用同一 resolved snapshot。
- [ ] 成功、阻塞、错误、取消、unknown 和清理失败路径均符合设计状态矩阵。
- [ ] 每条异步写路径都具备 gate、begin、cancel、finished/end 和 finally release。
- [ ] 修复一处模式问题后，已检索并检查全部同类位置。

## 3. UI And User Walkthrough

从用户视角而非组件树视角走查每个新增或修改的交互：

- [ ] 每个控件的用途无需依赖排列顺序、源码知识或猜测即可识别。
- [ ] 相邻且选项内容相似的控件有独立可见标签或等价的可访问标识。
- [ ] 初始化、编辑、保存、保存失败、回退、重载和 Settings 刷新不会静默丢状态。
- [ ] 展开/折叠、搜索、刷新等展示操作不会误改 dirty 状态。
- [ ] 当前值、未保存值、禁用态、错误态和成功态反馈准确。
- [ ] 用户可见文本均来自 `Bundle.msg()`，所有 locale key 对称。
- [ ] 窄 Tool Window、HiDPI、主题色和 disposal 行为已检查。

## 4. Structure And Static Gates

- [ ] 新增多个相关 callback/参数时，优先聚合为有语义的 provider/config/value object。
- [ ] 构造器和方法参数数量、复杂度及职责没有明显恶化。
- [ ] `quickCheck`、`detekt` 和 `git diff --check` 均通过。
- [ ] 生产与测试代码均能编译。
- [ ] 静态门禁失败必须作为活跃问题，不得降级为非阻塞备注。

推荐低负载命令：

```bash
./gradlew quickCheck detekt --max-workers=2 --no-parallel
./gradlew compileKotlin compileTestKotlin --max-workers=2 --no-parallel
git diff --check
```

## 5. Test Plan Reconciliation

- [ ] 设计测试计划中的每个条目都映射到实际测试方法，或明确标为 `OPEN` / `ACCEPTED`。
- [ ] 新增安全门禁、纯函数、迁移和真实入口均有自动化覆盖。
- [ ] 测试调用生产函数，而不是只验证 data class、helper 或语言行为。
- [ ] API 迁移测试直接表达新合同，不用兼容 helper 保留旧调用形状。
- [ ] 每个修复至少有一个能在缺陷版本失败、修复版本通过的回归测试，或明确说明无法自动化的原因。
- [ ] 断言覆盖全部关键副作用，不只覆盖主返回值。
- [ ] 目标测试优先；完整测试、真实 Git 集成和 releaseCheck 串行运行。

## 6. Documentation And Shared Review

- [ ] `docs/ai-review-current.md` 只保留活跃详情，已验证项压缩为摘要。
- [ ] 文档明确区分新引入回归、原有历史债和上一轮漏审。
- [ ] 验证命令及其 PASS/FAIL/超时结果如实记录。
- [ ] 测试超时或未运行时，不得声称测试通过。
- [ ] 功能行为、测试数量和近期变更文档按需同步。

## Completion Gate

```text
[ ] 原问题逐项重新验证
[ ] 二阶回归扫描完成
[ ] 用户视角 UI 走查完成
[ ] 设计测试计划逐项核销
[ ] quickCheck PASS
[ ] detekt PASS
[ ] compileKotlin + compileTestKotlin PASS
[ ] git diff --check PASS
[ ] 相关目标测试 PASS，或未完成验证明确记录为 OPEN
[ ] 共享审查无 OPEN / IN_PROGRESS / FIXED_PENDING_REVIEW

Result: PASS / CHANGES_REQUESTED
Residual non-blocking risks:
```
