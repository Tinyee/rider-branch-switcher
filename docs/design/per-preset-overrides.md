# Per-preset Overrides Design (Deprecated)

Status: **removed before first public release** (2026-06-19).

This design used to describe per-preset overrides for dirty strategy, pull, and fetch. The feature was removed because it made the Tool Window visually noisy and duplicated global Settings in a way that was hard to understand.

Current rule:

- Presets only store branch targets: `name`, `main`, `submodules`, and stable `id`.
- Dirty strategy, fetch, pull, timeout, and init confirmation are global Settings only.
- Do not reintroduce per-preset option controls in the main panel unless there is a fresh design that keeps advanced options out of the default workflow.

Replacement UX:

- Main panel stays focused on creating presets, editing branch targets, switching, deriving, and rollback/log visibility.
- Less common actions live in the top-right `...` menu or contextual menus.
