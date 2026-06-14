# AI Shared Review

用于 Codex、Claude 和用户之间传递当前审查问题。只详细保留活跃问题；复审通过的问题压缩为一行摘要。

## Commands

- `审查并写共享文档`：审查当前改动并覆盖当前摘要与活跃问题。
- `处理共享审查问题`：修复 `OPEN` / `IN_PROGRESS`，记录修改与验证，标记 `FIXED_PENDING_REVIEW`。
- `复审共享文档`：重新核对代码与测试，确认后移入 Verified Summary；失败则退回 `OPEN`。

## Active Findings

当前没有活跃问题。

## Verified Summary

- `P2-1` applyFilter 全匹配刷新：已验证，始终 revalidate/repaint。
- `P3-1` reload/import O(n²) 过滤：已验证，批量操作后仅重应用一次。
- `P3-2` 遗留临时注释：已验证，已删除。
- `P2-2` 重命名后重新过滤：已验证，名称更新后触发过滤回调。
- `P3-3` onNameChanged KDoc 起始符：已验证，已改为标准 `/**`。

## Maintenance

- 只详细保留 `OPEN`、`IN_PROGRESS`、`FIXED_PENDING_REVIEW`、`ACCEPTED`。
- `VERIFIED` 仅保留一行摘要；文档超过 100 行时立即压缩。
- 仅 P0/P1、跨模块设计决策或用户明确要求时归档到 `docs/reviews/ai-review-YYYY-MM-DD.md`。
