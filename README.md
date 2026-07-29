# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**JetBrains IDE plugin for switching a main repository and all submodules to a saved branch preset in one click.**

![version](https://img.shields.io/badge/version-0.7.0-blue)

Submodule Branch Switcher is built for teams that keep several related repositories in one Git project and need to move them between known branch combinations, such as `main`, `develop`, release branches, or feature branches.

Presets are project-local JSON. The preferred location is
`.idea/branch-presets.json`; a project-root `.branch-presets.json` is also
supported. Commit the chosen file if the preset set should survive local IDE
configuration cleanup and be shared by the team.

## Highlights

- **One-click presets**: define the target branch for the main repo and each submodule, then switch all repos together.
- **Dry-run preview**: see current branch, target branch, dirty file count, and branch source before checkout.
- **Dirty working tree strategies**: stash, skip, or force when a repo has uncommitted changes.
- **Rollback support**: failed switches keep a checkpoint for one-click rollback.
- **Submodule handling**: sync submodules after main repo checkout and initialize missing submodule directories.
- **Feature branch derivation**: create the same new branch across the main repo and all submodules from a preset baseline.
- **Preset tools**: create from current state, rename, reorder, import/export via clipboard, and undo recent switches.
- **IDE integration**: Tool Window, `Ctrl+Alt+B` quick switch action, notifications, Settings page, and English/Chinese i18n.

## Screenshots

![Tool window with branch presets](screenshots/01-tool-window.png)

![Preflight dry-run dialog](screenshots/02-preflight-dialog.png)

![Settings page](screenshots/03-settings.png)

## Supported IDEs

The plugin uses IntelliJ Platform APIs and the bundled `Git4Idea` plugin.
The unified IntelliJ IDEA distribution is the primary build target and Rider
is the current compatibility target. See the
[support matrix](docs/SETUP.md#support-matrix-policy) for the evidence required
before claiming support for another IDE family.

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
4. Click **Switch to this Preset**.
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
boundary. Presets are not stored globally by the plugin. Removing an untracked
`.idea` directory removes presets kept there.

## Options

Configure global behavior at `Settings | Version Control | Submodule Branch Switcher`.

| Option | Default | Description |
| --- | --- | --- |
| Dirty working tree | Stash changes | Strategy for uncommitted changes: stash, skip, or force. |
| Timeout | 60s | Maximum time per Git command. |
| Fetch before switch | On | Run `git fetch --prune` before checkout. |
| Pull after switch | On | Run `git pull --ff-only` after checkout. |
| Confirm before init | Off | Ask before initializing missing submodule directories. |

## Contributing

For local setup, architecture boundaries, validation, and review conventions,
see [CONTRIBUTING.md](CONTRIBUTING.md). Start with
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current code structure or
[docs/README.md](docs/README.md) for the complete documentation index. The
usual local development loop is `./gradlew runIde`.
