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
- `catch (_: Exception) {}` → 至少 `LOG.warn`
- `proc.waitFor()` 无超时 → `proc.waitFor(10, SECONDS)` + `destroyForcibly()`
- `allOk = succeeded.isNotEmpty()` 不加 `!cancelled` → `allOk = !cancelled && ...`
- Boolean 探针 fail-open → tri-state 探针 fail-closed（`null` = 阻止）
