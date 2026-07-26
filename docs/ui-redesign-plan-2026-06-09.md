# Archived UI Redesign: 2026-06-09

Status: **implemented and superseded by the current UI**.

The original proposal reorganized the Tool Window around a compact,
work-focused layout. Its major decisions were delivered:

- Global dirty, fetch, pull, timeout, and init options moved to Settings.
- The header shows the current main branch, a compact strategy summary, and a
  low-frequency actions menu.
- The primary row keeps `From Current State` and `Add Preset` visible.
- Preset cards prioritize current state, branch difference, switch, and derive
  actions.
- Preset editing, submodule rows, save/discard, and contextual actions are
  separated into focused UI collaborators.
- Empty state, status colors, IntelliJ icons, HiDPI sizing, and the collapsible
  log were aligned with JetBrains UI conventions.

Current UI behavior is defined by:

- `BranchSwitcherPanel.kt`
- `PresetEditor.kt`
- `PresetListManager.kt`
- `PresetCollectionActions.kt`
- `SubmoduleRowManager.kt`
- `SwitchController.kt`

The current screenshots are in `screenshots/`. New UI work should follow the
domain-specific frontend guidance in the codebase and verify both narrow and
wide Tool Window layouts. The original detailed wireframes and phase plan
remain available in Git history.
