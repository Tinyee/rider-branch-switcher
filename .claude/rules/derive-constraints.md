# 自动加载：编码前约束

以下规则每次会话自动生效，不需要手动调用 skill。

## 多阶段操作：先填状态矩阵再写代码

```
Phase / Success / Blocked / Probe-error / User-cancel / Cleanup-failure
```

空单元格 = 设计未完成。然后定义三份合同：

```text
行为合同：给定<状态>，当<操作>，则<可观测最终状态>
失败合同：如果<探针/命令>失败，阻止/回滚因为<原因>
取消合同：取消发生在<边界>。通过<机制>在<新 operation>中恢复。
```

## 写完自审：跑这些 grep

```bash
# Cancel 对称性
grep -rn "TaskBridge.runBackground" src/main/kotlin | wc -l
grep -rnc "beginOperation\|onCancel\|onFinished\|endOperation" src/main/kotlin

# Write gate 配对
grep -rnc "tryStartWrite\|endWrite" src/main/kotlin

# Result 返回路径参数数一致
grep "return.*Result(" Executor.kt | awk -F, '{print NF}' | sort -u
# 必须只有一个数字
```

## 不准写这些 API（已废弃/会出错）

- `project.coroutineScope` → 注入 `CoroutineScope` 到 service 构造器
- `SwingUtilities.invokeLater`（模型访问） → `Application.invokeLater` + 显式 `ReadAction`/`WriteAction`
- `Dispatchers.Main`（模型访问，2025.1+） → `Dispatchers.EDT` 或显式 write action
- `ServiceLevel.PROJECT` → `Service.Level.PROJECT`
- `ReadAction.compute{}`（2026.1+） → `readAction {}`（suspend）
- `catch (_: Exception) {}` → 按类型处理：CancellationException 重抛 / 探针异常转 Error / UI 清理可忽略需注释 / 其他至少 LOG.warn
- `proc.waitFor()` 无超时 → `proc.waitFor(10, SECONDS)` + `destroyForcibly()`
- `allOk = succeeded.isNotEmpty()` 不加 `!cancelled` → `allOk = !cancelled && ...`
- Boolean 探针 fail-open → tri-state 探针 fail-closed（`null` = 阻止）

## 测试负载约束

- 开发迭代先跑 `./gradlew quickCheck`，再跑最相关的测试类或方法，不默认跑全量测试。
- 完整 `test`、真实 Git 集成测试、`buildPlugin`、`verifyPlugin`、`releaseCheck` 属于重型任务，禁止并行启动。
- 广泛本地验证默认加 `--max-workers=2 --no-parallel`；机器发热或风扇噪音明显时使用 `--max-workers=1 --no-parallel`。
- 不得通过减少 Kotest 全局迭代次数或跳过测试来降低负载；选择目标测试或限流。
- 声称完成前按改动范围运行相关测试的 `--rerun-tasks`；发布/推送前才运行 `releaseCheck`，并在启动前告知用户其高负载。

## 共享审查约束

- 共享审查文件固定为 `docs/ai-review-current.md`。
- “处理共享审查问题”必须先读取该文件，修复 `OPEN` / `IN_PROGRESS` 项并记录验证，将状态改为 `FIXED_PENDING_REVIEW`。
- “复审共享文档”必须重新核对代码和测试；确认后标记 `VERIFIED`，失败则改回 `OPEN`。
- 不得仅修改文档状态；每个已修复项必须有代码证据和验证命令。
- 复审结束后将 `VERIFIED` 详情压缩为一行摘要；共享文档超过 100 行时必须立即压缩，只详细保留活跃状态和 `ACCEPTED`。
- 仅 P0/P1、跨模块设计决策或用户明确要求时归档完整审查到 `docs/reviews/ai-review-YYYY-MM-DD.md`。
