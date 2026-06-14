# Design Review Checklist

用于新功能设计文档的实现前审查。直接按本模板执行，不要把全文复制到
`docs/ai-review-current.md`；共享文档只记录范围、证据摘要、活跃问题和最终门禁结果。

每一项必须满足以下之一：

- `VERIFIED`：附源码位置、检索结果或验证命令。
- `N/A`：写明为什么不适用于本设计。

不得无证据勾选，也不得静默跳过。

## Designer Pre-flight（设计者交审前自检）

以下检查不替代完整审查，但能前置拦截本项目多轮设计复审中反复出现的低级问题：

- [ ] 从零通读一遍完整设计文档（不看 diff，假装第一次读）。
- [ ] 新建或修改且计划直接照抄实现的伪代码块标注 `COMPILE_SHAPED`；其余代码块在最终复审证据中分类为 `ILLUSTRATIVE_ONLY` 或已验证的 `COMPILE_SHAPED`。
- [ ] 影响范围由 `rg -n` 枚举并区分机械迁移与语义重写；行数估计不凭感觉，也不只依赖匹配行数。
- [ ] 外部输入矩阵（缺失/null/空/null item/非法值/重复ID/新旧格式优先级/写回/写回失败）已填入设计文档，不留在审查者脑补。
- [ ] 新字段的全部读、写、构造、序列化和测试调用点已用 `rg -n` 枚举。
- [ ] 文档没有过期行号、审查轮次头或已删除方案残留。

## Review Modes

### Incremental Review

只验证上一轮活跃问题是否真正修复：

- 重读修改后的设计段落和对应源码，不依赖上一轮记忆。
- 每项必须有源码证据和验证结果。
- 修复引入的新结构必须检查直接影响和二阶变化，但不允许据此宣告设计整体可开工。
- 单项结果只能是 `VERIFIED`、`OPEN` 或 `ACCEPTED`；`VERIFIED` 详情随后压缩进摘要。

### Final Readiness Review

从零重新执行本模板全部检查，不只看活跃问题。只有此模式可以输出：

```text
Result: PASS — ready to implement
```

`PASS` 必须记录设计文档的 `git hash-object <design-file>`。设计内容发生任何修改后，旧 `PASS`
立即失效；根据改动范围重新执行最终复审，不允许沿用旧结论。

正式开工前还要重新读取设计列出的受影响入口。如果最终复审后相关源码发生变化，先复核变化
是否影响调用链、API、迁移或测试计划；无影响可记录后继续，有影响则旧 `PASS` 失效。

## 1. Scope And Evidence

- [ ] 写清目标、非目标和用户可观察行为。
- [ ] 记录受影响调用链：入口 → 编排 → 纯逻辑 → 副作用 → 清理。
- [ ] 列出影响文件，并用 `rg`/源码读取验证实际调用点。
- [ ] 每个设计声明都有源码位置或明确标记为新代码。
- [ ] 数据模型字段的全部读、写、构造、序列化和测试调用点已枚举。

Evidence table:

| Design claim | Source/call sites | Affected tests | Verification | Status |
|---|---|---|---|---|
| | | | | |

## 2. Contracts And State Matrix

- [ ] 行为合同完整：给定状态、动作、最终可观察状态。
- [ ] 失败合同完整：探针失败、命令失败、部分成功如何处理。
- [ ] 取消合同完整：取消边界、清理和回滚使用什么 operation。
- [ ] 状态矩阵包含成功、阻塞、错误、取消、清理失败，且无空白单元格。
- [ ] Unknown/error 状态 fail-closed，不伪装成 clean/not-found。

## 3. External Input And Migration

对 JSON、配置、剪贴板、持久化状态等每个入口检查：

- [ ] 字段缺失。
- [ ] 显式 `null`。
- [ ] 空集合。
- [ ] 集合包含 `null`。
- [ ] 非法枚举或非法值。
- [ ] 重复或空 ID。
- [ ] 旧格式与新格式同时存在时的优先级。
- [ ] 迁移后的持久化文件形态和自动写回条件。
- [ ] 写回失败不会静默丢数据或错误清除 dirty 状态。
- [ ] 所有 DTO → domain 路径复用同一转换规则。

## 4. API And Pseudocode Verification

每段伪代码必须标记为以下之一：

- `COMPILE_SHAPED`: 应能按真实 API 直接实现。
- `ILLUSTRATIVE_ONLY`: 仅表达概念，不允许用于证明接线正确。

计划直接照抄实现的新建或修改代码块必须在设计文档中显式标记为 `COMPILE_SHAPED`。
其他未标记代码块必须在最终复审证据中分类；未分类代码块不能作为设计正确或入口已接线的证据。

对于所有 `COMPILE_SHAPED` 示例：

- [ ] 类、方法、字段真实存在，接收者正确。
- [ ] 参数顺序、返回类型、可见性和 nullable 类型正确。
- [ ] 示例使用真实 import/API，不引用不存在的局部变量。
- [ ] 接口签名变更已枚举所有实现、fake 和调用点。
- [ ] 新类型守卫无法被生产入口绕开。
- [ ] 没有使用脆弱文本扫描代替类型或行为测试。

## 5. Production Entry Points

- [ ] 所有生产入口已枚举，包括 UI、Action、快捷键、菜单和后台回调。
- [ ] 所有入口使用同一解析、验证和安全门禁路径。
- [ ] 预览、确认与实际执行消费同一个 resolved snapshot。
- [ ] 异步写路径包含 gate、begin、cancel、finished/end、finally release。
- [ ] Settings 或状态变化不会造成预览与执行策略漂移。

## 6. UI, i18n And Diagnostics

- [ ] 所有用户可见文本使用 `Bundle.msg()`。
- [ ] 新 i18n key 同时存在于所有 locale 文件。
- [ ] 提示语准确覆盖真实触发条件，包括 unknown/fail-closed。
- [ ] 初始化、保存、恢复、重载和 Settings 刷新路径均有定义。
- [ ] 相邻且选项内容相似的控件有独立可见标签或等价的可访问标识，不依赖排列顺序表达含义。
- [ ] HiDPI、主题色、窄窗口和 disposal 约束已考虑。

## 7. Test Plan Quality

- [ ] 纯逻辑测试覆盖合并、状态矩阵和边界值。
- [ ] Loader/import/controller/action 等真实入口分别有接线测试。
- [ ] 迁移测试覆盖旧格式、优先级、写回和失败。
- [ ] 外部输入测试覆盖缺失/null/空/null item/非法值。
- [ ] API 签名迁移已统计所有生产与测试调用点。
- [ ] 测试会在生产代码损坏时失败，不只验证 data class 或语言行为。
- [ ] 测试迁移区分机械修改与语义重写。
- [ ] 测试计划中的每个条目都能映射到计划新增或修改的实际测试方法，不留笼统的“后续补测试”。
- [ ] 重型测试与轻量迭代测试已分开。

## 8. Cross-Document Consistency

- [ ] 设计原则、行为合同、状态矩阵、影响文件和测试计划口径一致。
- [ ] i18n 文案与触发条件一致。
- [ ] 影响行数和调用点数量由检索结果支撑。
- [ ] 文档没有过期行号、审查轮次或已删除方案。
- [ ] 共享审查文档没有 `OPEN`、`IN_PROGRESS` 或 `FIXED_PENDING_REVIEW`。
- [ ] 共享审查的 PASS baseline hash 与当前设计文档 `git hash-object` 一致。

## Implementation Readiness Gate

```text
[ ] Final Readiness Review 从零完成，不是增量复审
[ ] 每个检查项均有 VERIFIED 证据或 N/A 理由
[ ] PASS baseline hash 已记录且与当前设计文档一致
[ ] 开工前已确认最终复审后相关源码没有影响设计的变化
[ ] 所有生产入口和转换入口已列出
[ ] 所有 COMPILE_SHAPED 示例已对照真实 API
[ ] 外部输入与迁移矩阵完整
[ ] 错误、取消、unknown 和清理行为完整
[ ] UI/i18n 提示与真实条件一致
[ ] 测试计划覆盖纯逻辑与真实入口
[ ] 共享审查无未验证项
[ ] git diff --check 通过

Result: PASS / NOT READY
Residual non-blocking risks:
```
