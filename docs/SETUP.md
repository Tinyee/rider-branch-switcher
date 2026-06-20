# Environment Setup

This project is a generic JetBrains IDE plugin. It is no longer built against Rider by default.

## Requirements

| Requirement | Notes |
| --- | --- |
| JDK 17 or 21 | Gradle toolchain uses JDK 17. Newer JetBrains IDEs may warn about JDK 21, but builds still run with 17. |
| Git CLI | Required by the plugin runtime and integration tests. |
| JetBrains IDE | Optional for local sandbox testing. Gradle can download the configured platform SDK automatically. |

## Platform Configuration

All platform and compatibility settings live in `gradle.properties`:

```properties
platform.type=IC
platform.version=2026.1
platform.localPath=

plugin.sinceBuild=261
plugin.untilBuild=261.*
plugin.verifier.ideCodes=IC,RD
```

Product codes commonly used here:

| Code | IDE |
| --- | --- |
| IC | IntelliJ IDEA Community |
| IU | IntelliJ IDEA Ultimate |
| RD | Rider |
| PY | PyCharm Professional |
| WS | WebStorm |
| CL | CLion |

Default local development uses `IC` because it is the lightest generic IntelliJ Platform SDK.
The build resolves the non-installer platform artifact by default, so it does not download a full IDE installer unless `platform.localPath` or a product-specific workflow requires it.

## Using A Local IDE

Set `platform.localPath` to avoid downloading an SDK:

```properties
platform.localPath=C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2026.1
```

Examples:

| OS | Example |
| --- | --- |
| macOS | `/Applications/IntelliJ IDEA CE.app` |
| Windows | `C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2026.1` |
| Linux | `~/.local/share/JetBrains/Toolbox/apps/intellij-idea-community-edition/...` |

For Rider-specific sandbox testing, point `platform.localPath` at Rider or set `platform.type=RD`.

## First Run

```bash
git config core.hooksPath .githooks

./gradlew quickCheck
./gradlew buildPlugin
```

The plugin ZIP is written to:

```text
build/distributions/submodule-branch-switcher-0.7.0.zip
```

Install it with `Settings | Plugins | Install Plugin from Disk...`.

## Sandbox IDE

```bash
./gradlew runIde
```

`runIde` launches a sandbox for the configured platform SDK or `platform.localPath`.

## Validation Levels

Use the low-load flow during development:

```bash
./gradlew quickCheck
./gradlew pureTest --max-workers=1 --no-parallel
./gradlew test --tests "<ClassOrMethod>" --max-workers=1 --no-parallel
```

Before commit or broad architecture changes:

```bash
./gradlew :core:test test :core:detekt detekt --max-workers=2 --no-parallel
git diff --check
```

Before release:

```bash
./gradlew releaseCheck
```

`releaseCheck` also runs `verifyPlugin` for the product codes in `plugin.verifier.ideCodes`. Keep that list short during local development to avoid heavy downloads.

## Compatibility Notes

- Keep `plugin.sinceBuild` and `plugin.untilBuild` aligned with the IntelliJ Platform major build you support.
- If lowering `plugin.sinceBuild`, also check Kotlin runtime compatibility for the oldest target IDE.
- Prefer IntelliJ Platform common APIs in production code.
- Avoid Rider-only APIs unless they are isolated behind a product-specific adapter.
- Add extra verifier IDE codes only when you intentionally claim support for those IDEs.

## Common Issues

| Error | Cause | Fix |
| --- | --- | --- |
| `Could not resolve ...` | Network or platform SDK download issue | Use `platform.localPath` or retry with proxy/mirror available |
| `Plugin is incompatible with this installation` | Build range does not cover target IDE | Adjust `plugin.sinceBuild` / `plugin.untilBuild` |
| `incompatible version of Kotlin` | Compiler output newer than target IDE Kotlin runtime | Lower Kotlin compiler or raise target IDE build |
| Chinese Markdown looks garbled in terminal | Terminal encoding issue | See `docs/encoding-and-line-endings.md`; do not rewrite files just for terminal display |
