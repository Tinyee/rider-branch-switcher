# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**JetBrains IDE plugin for switching a main repository and all submodules to a saved branch preset in one click.**

![version](https://img.shields.io/badge/version-0.7.0-blue)
![tests](https://img.shields.io/badge/tests-288-green)
![JetBrains](https://img.shields.io/badge/JetBrains-2026.1-blue)

Submodule Branch Switcher is built for teams that keep several related repositories in one Git project and need to move them between known branch combinations, such as `main`, `develop`, release branches, or feature branches.

Presets are stored as JSON in `.idea/branch-presets.json`, so they can be committed and shared with the project.

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

The plugin uses IntelliJ Platform APIs plus the bundled `Git4Idea` plugin.

| IDE family | Status | Notes |
| --- | --- | --- |
| IntelliJ IDEA Community | Primary build target | Default SDK for local development and CI-compatible builds. |
| Rider | Compatibility target | Covered by `plugin.verifier.ideCodes=RD`; keep manual smoke checks before release. |
| IntelliJ IDEA Ultimate | Expected compatible | Same platform + Git APIs, but not listed as a primary verifier target yet. |
| PyCharm / WebStorm / CLion | Not claimed yet | Add verifier codes and manual smoke checks before Marketplace support is advertised. |

Default development SDK:

```properties
platform.type=IC
platform.version=2026.1.3
plugin.sinceBuild=261
plugin.untilBuild=261.*
plugin.verifier.ideCodes=RD
```

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
build/distributions/submodule-branch-switcher-0.7.0.zip
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

## Options

Configure global behavior at `Settings | Version Control | Submodule Branch Switcher`.

| Option | Default | Description |
| --- | --- | --- |
| Dirty working tree | Stash changes | Strategy for uncommitted changes: stash, skip, or force. |
| Timeout | 60s | Maximum time per Git command. |
| Fetch before switch | On | Run `git fetch --prune` before checkout. |
| Pull after switch | On | Run `git pull --ff-only` after checkout. |
| Confirm before init | Off | Ask before initializing missing submodule directories. |
| Local anonymous stats | Opt-in | Keeps local counters only; no data is sent automatically. |

## Development

```bash
# Enable local hooks once after clone
git config core.hooksPath .githooks

# Fast structural checks
./gradlew quickCheck

# Core pure JVM tests
./gradlew pureTest

# Platform/integration tests
./gradlew test

# Build plugin zip
./gradlew buildPlugin

# Launch sandbox IDE with the plugin installed
./gradlew runIde
```

Use low-load validation during normal development:

```bash
./gradlew quickCheck
./gradlew :core:test --tests "<ClassOrMethod>" --max-workers=1 --no-parallel
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel
```

Before broad changes or release preparation:

```bash
./gradlew :core:test test :core:detekt detekt --max-workers=2 --no-parallel
./gradlew releaseCheck
```

Manual heavy diagnostics:

```bash
# Large-repo wall-clock benchmark
./gradlew benchmark

# Scoped mutation testing
./gradlew pitestCore
```

`benchmark` and `pitestCore` are intentionally not part of normal `test` or `releaseCheck`.
