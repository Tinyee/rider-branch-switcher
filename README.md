# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**JetBrains IDE plugin for switching a main repository and all submodules to a saved branch preset in one click.**

![version](https://img.shields.io/badge/version-0.8.0-blue)

Submodule Branch Switcher is built for teams that keep several related repositories in one Git project and need to move them between known branch combinations, such as `main`, `develop`, release branches, or feature branches.

Presets are project-local JSON. Personal presets default to
`.idea/branch-presets.json`. Teams that want to share one preset collection can
create and commit a project-root `.branch-presets.json`; a personal `.idea`
file takes precedence when both exist.

## Highlights

- **One-click presets**: define the target branch for the main repo and each submodule, then switch all repos together.
- **Dry-run preview**: see current branch, target branch, dirty file count, and branch source before checkout.
- **Dirty working tree strategies**: stash changes, skip the repository, or try switching without stashing.
- **Rollback support**: failed switches keep a checkpoint for one-click rollback.
- **Submodule handling**: sync after main checkout, initialize missing or moved paths, and skip obsolete preset paths without deleting their local worktrees.
- **Feature branch derivation**: create the same new branch across the main repo and all submodules from a preset baseline.
- **Preset tools**: create from current state, rename, reorder, import/export via clipboard, and return to the previous preset.
- **IDE integration**: Tool Window, `Ctrl+Alt+B` quick switch action, notifications, Settings page, and English/Chinese i18n.

## Screenshots

![Tool window with branch presets](screenshots/01-tool-window.png)

![Preflight dry-run dialog](screenshots/02-preflight-dialog.png)

![Settings page](screenshots/03-settings.png)

## Supported IDEs

The plugin uses IntelliJ Platform APIs and the bundled `Git4Idea` plugin. It is
compiled against the IntelliJ Platform 2025.1 API baseline, and Rider 2025.1
and newer are the currently verified product targets. See the
[support matrix](docs/SETUP.md#support-matrix-policy) for the evidence required
before claiming support for another IDE family.

The supported repository shape is one main Git repository and its recursively
registered `.gitmodules` submodules. Multiple independent VCS roots or sibling
repositories in one IDE project are outside the current product scope.

## Install

Marketplace publication is planned later. For now, install from disk:

1. Build or download `submodule-branch-switcher-{version}.zip`.
2. Open `Settings | Plugins | Install Plugin from Disk...`.
3. Select the ZIP file.
4. Restart the IDE.

Build the ZIP locally:

```bash
./gradlew buildPlugin
```

The output is written to:

```text
build/distributions/submodule-branch-switcher-*.zip
```

## Quick Start

1. Open the **SubmoduleBranches** Tool Window.
2. Click **From Current State** to create a preset from the current main repo and submodule branches.
3. Edit the preset branch targets if needed.
4. Click **Switch**.
5. Review the dry-run preview and confirm.

Example preset file:

```json
{
  "presets": [
    {
      "name": "dev",
      "main": "develop",
      "submodules": {
        "lib/common": "develop",
        "lib/net": "develop"
      }
    }
  ]
}
```

Preset lookup checks `.idea/branch-presets.json` first, then
`.branch-presets.json`, and finally parent directories up to a Git repository
boundary. The first match is the active file, and **Open Preset File** reveals
that exact path. This allows a personal `.idea` collection to override a shared
root collection. Opening the Tool Window does not create or rewrite a preset
file; the personal `.idea` file is created on the first successful save when no
file exists. Presets are not stored globally by the plugin. Removing an
untracked `.idea` directory removes presets kept there.

## Options

Configure global behavior at `Settings | Version Control | Submodule Branch Switcher`.

| Option | Default | Description |
| --- | --- | --- |
| Uncommitted changes | Stash changes | Stash and restore changes, skip dirty repositories, or try checkout without stashing. Restored stash entries remain as manual recovery backups until you remove them with Git. |
| Git command timeout | 60s | Maximum time for each inspection, fetch, checkout, pull, or submodule command. |
| Refresh remote branches | On | Run `git fetch --prune` before checkout; report failures and continue where possible. |
| Fast-forward target branches | On | Run `git pull --ff-only` after checkout without automatically merging diverged branches. |
| Confirm missing submodules | Off | Ask before initializing each missing submodule directory. |

## Diagnostics

Every switch, derive, and single-repository write receives an operation ID such
as `switch-a1b2c3d4`; preflight, execution, refresh, and recovery keep that ID
with a phase suffix. The Tool Window timestamps entries and can filter or copy
the latest write, clear its bounded view, and reveal the complete IDE log. All levels
are also written under `SubmoduleBranchSwitcher` in the persistent IntelliJ
`idea.log`. Unexpected exceptions include their full stack trace there.

Use `Help | Show Log in ...` to locate `idea.log`, then collect all lines with
the same operation ID. They include the project root, requested targets,
effective options, checkpoints, Git failure diagnostics, recovery actions, and
the final result. Git diagnostics replace URI/SCP remotes and credential-like
values with non-reversible placeholders so private addresses and secrets are
not exposed. The plugin sends no telemetry; diagnostics remain in local IDE
logs unless the user chooses to share them.

## Contributing

For local setup, architecture boundaries, validation, and review conventions,
see [CONTRIBUTING.md](CONTRIBUTING.md). Start with
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current code structure or
[docs/README.md](docs/README.md) for the complete documentation index. The
usual local development loop is `./gradlew runIde`.
