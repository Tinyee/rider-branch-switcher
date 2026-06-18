# CLAUDE.md

项目上下文、架构、开发命令、审查流程、测试资源规则 → 见 `AGENTS.md`。
本文件只放 Claude 特有的行为约束和血泪教训。

## 不准 — 可自动验证

- **没跑 `./gradlew quickCheck detekt` 不准说没问题。**
- **不准静默吞异常。** `CancellationException` 必须重抛。探针异常 → Unknown/Error。UI 清理 → 可忽略但注释原因。其他 → 至少 `LOG.warn`。
- **新增异步路径不准缺生命周期。** 每条路径：门禁 → beginOperation → 可取消任务 → endOperation → finally。`invokeLater` 回调检查 `project.isDisposed`。
- **声称完成前不准用 Gradle 缓存声称通过。** 开发增量 OK，声称"没问题"至少跑一次 `--rerun-tasks`。
- **`ProcessCanceledException extends RuntimeException`**——`catch (e: RuntimeException)` 和 `catch (e: Exception)` 都会误吞它。取消异常和进程取消异常必须在任何宽 catch 之前单独处理。**改变任何异常传播路径后，必须 `grep -rn "catch.*Exception\|catch.*RuntimeException" src/main` 扫全量上行 catch 块。**
- **验证分层**：改测试/文档 → L1（testClasses+quickCheck）。改生产代码 → L2（+detekt）。改 GitOps/SwitchExecutor → L3（+相关 test）。push → L4（全量 test detekt --rerun-tasks）。详见 `/handoff` skill。

## 血泪教训 — 没法自动验证但每次都栽

- **在 A 处修 bug 没扫 B/C/D 处。** 缺 try/catch、缺 cancel、缺 operation 生命周期——同一个 bug 在 3+ 个文件各犯一遍。
- **只修实例没修模式。** 同一类 bug 被审查抓 5 轮。
- **为了让测试绿放松安全检查。** tri-state probe 初期设计成 fail-open。
- **审查指出的验证缺口加注释就当修完。** checkQuickCheck 被指出假阳性，只加注释没修根因。
- **取消操作跳过回滚。** 取消了也得新 operation 恢复。
- **顺手改无关代码。** "来都来了"→ scope 失控。
- **补测试凑数不防回归。** PresetOverrides 结构相等性——生产代码全删照样绿。
- **走轻松的路。** resolver 测试应调 service，mock Project 麻烦就绕过去。
- **测试没走到调用链终点。** 迁移测试验证了 domain model 没验证文件写回。
- **多阶段操作先写代码后排状态矩阵。** derive 7 轮审查因为状态边做边发现。
- **写只验证 data class 字段/语言特性的测试。** 已清理 6 个，无自动门禁能区分。
- **凭感觉改文档数字。** 用 `rg -n`/测试输出枚举证据。
- **接口加方法漏更新实现。** `GitClient` 加方法漏 test fake 多次。
- **改了异常传播不扫上游 catch。** `probeOne()` 加了 `throw ProcessCanceledException`，但 TaskBridge/SwitchRunner/SwitchController/PresetListManager 四个上游 `catch (e: RuntimeException)` 全部误吞。改完必须 `grep -rn "catch.*Exception"` 扫全量。
- **加了有逻辑的 getter，内部代码绕过它直接用裸字段。** `exportTelemetry()` 用 `options.telemetryInstallId` 绕过了 getter 的 opt-in 检查，空 ID 泄露到了导出 JSON。加了 getter 后 `grep` 确认内部引用只用 getter 不用裸字段。
- **接口加方法忘了给默认实现。** test fake 炸一片。新方法优先加 `= defaultImpl`，等所有 fake/测试适配完再决定是否去掉默认值。
- **文档数字凭 `replace_all` 一键改，没确认范围。** `270 tests` 改到了 CHANGELOG 历史记录。先用 `grep -n` 确认范围，再决定 replace_all 还是逐处改。
- **加测试后文档数字改不全。** 测试 270→282→297→300→302→304→306→308，每次漏改一两个文件。commit 前 `grep` 旧数字确认全替换。

## 提交前自审 — 在审查者发现问题之前自己先找

改完代码后、commit 前，按改动类型跑对应 grep。目的是打断"审查逐层剥"循环——审查只抓当前最浅的问题，自己扫全量才能一次过关。

```bash
# 改了异常传播 → 扫全量 catch 块
grep -rn "catch.*Exception\|catch.*RuntimeException" src/main

# 改了参数/方法签名 → 扫全量 override + 调用方
grep -rn "FUNCTION_NAME" src/

# 改了接口 → 扫全量 implements + test fake
grep -rn "INTERFACE_NAME" src/

# 加了 Bundle key → 确认两个 locale 文件都有
grep "NEW_KEY" src/main/resources/messages/*.properties

# 改完跑门禁
./gradlew quickCheck detekt
```

## 新功能：先设计再写代码

第一步不是写代码，是回复：
1. 状态矩阵（成功/阻塞/错误/取消/清理失败）
2. 三份合同（行为/失败/取消）
3. 影响的文件和调用链

用户确认后再动手。设计自检用 `docs/templates/design-review-checklist.md`。

## 怎么干活

- **一次只改一件事。** 别修 bug + 重构 + 改文档混一起。
- **先验证再动手。** 读文件，不复现不修。
- **看不懂的代码别删。** 默认它是踩过坑才加的。
- **坏主意要拒绝。** 直接说，当应声虫导致 7 轮审查。
- **想破例先问。** 问一句 < 改错。
- **写完代码对着设计逐条过。** 不看设计直接写 = 漏 UI、漏交互细节。
- **审查修完一轮重读源码。** 不能凭记忆——两轮之间文件已被自己改过。
- **提交前跑 `docs/templates/implementation-review-checklist.md`。**

## Skill

- IntelliJ SDK：`/intellij-plugin-dev`
- 验证分层 + commit 约定 + 审查侧不重跑：`/handoff`
