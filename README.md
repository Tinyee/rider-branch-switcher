# Submodule Branch Switcher

**Rider plugin** — one-click switch the main repo and all submodules to a preset branch combination.

![version](https://img.shields.io/badge/version-0.7.0-blue)
![tests](https://img.shields.io/badge/tests-287-green)
![Rider](https://img.shields.io/badge/Rider-2026.1-purple)

## Features

- **Preset management**: save branch combinations (main + submodules) as named presets, switch between them in one click
- **Preflight preview**: dry-run table showing `current → target` per repo, dirty file count, and branch source (local/remote)
- **Dirty tree strategies**: stash / skip / force — choose what happens when the working tree has uncommitted changes
- **Auto stash pop**: when switching back to the original branch, stashed changes are automatically restored
- **Derive feature branches**: create `feature/xxx` on main + all submodules simultaneously from a preset
- **Rollback**: failed switches record a checkpoint — one-click rollback to pre-switch state
- **Keyboard shortcut**: `Ctrl+Alt+B` opens the preset picker from anywhere
- **i18n**: English + 中文, follows IDE language setting
- **Persistent settings**: dirty strategy, timeout, fetch/pull preferences survive IDE restarts

## Screenshot

See [Screenshots](#screenshots) section below for capture guidelines.

## Install

**From Marketplace** (coming soon):
`Settings → Plugins → Marketplace → "Submodule Branch Switcher"`

**From disk**:
1. Download `rider-branch-switcher-{version}.zip` from [Releases](https://github.com/Tinyee/rider-branch-switcher/releases)
2. `Settings → Plugins → ⚙️ → Install Plugin from Disk...`
3. Select the `.zip` file
4. Restart Rider

## Quick Start

### 1. Create a preset

Open the **SubmoduleBranches** tool window (right sidebar, branch icon), then either:

- **From current state** (recommended): click `From Current State` — reads HEAD branches of the main repo and all initialized submodules, generates a preset automatically.
- **Manually**: click `Add Preset`, enter a name. Expand the preset to edit branch combos for main + each submodule.

### 2. Switch

Click `Switch` on any preset. A preview dialog shows what will change. Approve to execute.

### 3. Keyboard shortcut

`Ctrl+Alt+B` anywhere in the IDE opens the preset picker.

### Preset file location

Presets are stored as JSON in `.idea/branch-presets.json` (auto-created on first run). Team members can share this file via git.

```json
{
  "presets": [
    {
      "name": "dev",
      "main": "develop",
      "submodules": { "lib/common": "develop", "lib/net": "develop" }
    }
  ]
}
```

## Options

Configured via `Settings → Version Control → Submodule Branch Switcher`:

| Option | Default | Description |
|--------|---------|-------------|
| Dirty working tree | Stash | Strategy for uncommitted changes: Stash / Skip / Force |
| Timeout | 60s | Max time per `git` command |
| Fetch before switch | ✅ | `git fetch --prune` before checkout |
| Pull after switch | ✅ | `git pull --ff-only` after checkout |
| Confirm before init | ❌ | Ask before `git submodule update --init` for missing dirs |

## Dev

```bash
# Run core pure JVM tests first, then platform/integration tests
./gradlew :core:test
./gradlew test

# Build plugin zip
./gradlew buildPlugin
# → build/distributions/rider-branch-switcher-{version}.zip

# Large-repo wall-clock benchmark (heavy; 51 independent git repos, real GitOps)
./gradlew benchmark
# Prints preflight + switch wall-clock timing. Not part of ./gradlew test.

# Scoped mutation testing (heavy; manual diagnostic, not part of releaseCheck)
./gradlew pitestCore
# Writes HTML/XML reports to build/reports/pitest.
```

**Benchmark** (`./gradlew benchmark`) creates 1 main + 50 independent git repos and runs
preflight probe + full switch pipeline using real `GitOps`. It measures wall-clock time
and prints results for human review — no thresholds are enforced. The task is gated
behind a dedicated Gradle task so it never runs as part of normal `./gradlew test`.

**Mutation testing** (`./gradlew pitestCore`) runs a scoped PIT pass over pure rules
and notification/import decision logic only. It is intentionally manual and single-threaded:
the full IntelliJ Platform classpath makes broad mutation runs noisy and expensive.

Tech stack: Kotlin 2.3, IntelliJ Platform Gradle Plugin 2.2.1, Gradle 8.13, JUnit 4 + Kotest 5.9.

## Screenshots

For the JetBrains Marketplace listing (1280×800, 16:10, no device borders):

1. **Tool window with multiple presets** — Show the Submodule Branches tool window docked in Rider, with 2-3 presets visible, one expanded to show main repo + submodule rows, and the current preset highlighted
2. **Preflight dry-run dialog** — The preview table showing current → target branches, dirty counts, and branch sources before confirming a switch
3. **Settings page** — File → Settings → Version Control → Submodule Branch Switcher, showing dirty strategy, timeout, fetch/pull checkboxes

Place screenshots in a `screenshots/` directory at the repo root.
