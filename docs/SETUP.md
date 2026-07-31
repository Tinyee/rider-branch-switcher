# Environment Setup

This project is a generic JetBrains IDE plugin. It is no longer built against Rider by default.

## Requirements

| Requirement | Notes |
| --- | --- |
| JDK 21 | The Kotlin/JVM toolchain and CI both use Java 21 for IntelliJ Platform 2026.1. |
| Git CLI | Required by the plugin runtime and integration tests. |
| JetBrains IDE | Optional for local sandbox testing. Gradle can download the configured platform SDK automatically. |

## Platform Configuration

All platform and compatibility settings live in `gradle.properties`:

```properties
platform.type=IU
platform.version=2026.1.3
platform.localPath=

plugin.sinceBuild=251
plugin.verifier.ideCodes=RD
```

Product codes commonly used here:

| Code | IDE |
| --- | --- |
| IU | IntelliJ IDEA unified distribution (2025.3+) |
| IC | Legacy IntelliJ IDEA Community releases |
| RD | Rider |
| PY | PyCharm Professional |
| WS | WebStorm |
| CL | CLion |

Default local development uses `IU`, the unified IntelliJ IDEA distribution
published for the configured 2026.1 platform.
The build resolves the non-installer platform artifact by default, so it does not download a full IDE installer unless `platform.localPath` or a product-specific workflow requires it.

## Using A Local IDE

Set `platform.localPath` to avoid downloading an SDK:

```properties
platform.localPath=C:/Program Files/JetBrains/IntelliJ IDEA 2026.1
```

Examples:

| OS | Example |
| --- | --- |
| macOS | `/Applications/IntelliJ IDEA.app` |
| Windows | `C:/Program Files/JetBrains/IntelliJ IDEA 2026.1` |
| Linux | `~/.local/share/JetBrains/Toolbox/apps/intellij-idea/...` |

For Rider-specific sandbox testing, point `platform.localPath` at Rider or set `platform.type=RD`.

## First Run

```bash
./gradlew buildPlugin
```

This resolves the configured SDK and verifies that the plugin package can be
built. Installation instructions remain in the project README.

## Dependency Sources

The Gradle wrapper and dependency repositories use official sources by
default. If those repositories are slow or unavailable from mainland China,
enable the optional dependency mirrors for a command:

```bash
./gradlew buildPlugin -PuseChinaMirrors=true
```

This property adds the Aliyun, Tencent, and Huawei mirrors after the official
repositories. It does not change dependency coordinates or commit a
machine-specific repository choice. Configure a Gradle proxy or pre-populate
Gradle 8.13 if the wrapper distribution itself cannot be reached.

## Sandbox IDE

```bash
./gradlew runIde
```

`runIde` launches a sandbox for the configured platform SDK or `platform.localPath`.

## Validation

Contributor validation commands and the change-to-test matrix live in
[`../CONTRIBUTING.md`](../CONTRIBUTING.md). This document only owns environment
and compatibility configuration.

`releaseCheck` runs Plugin Verifier for the product codes in
`plugin.verifier.ideCodes`. Keep that list short during local development to
avoid heavy downloads. CI runs tests on Ubuntu, Windows, and macOS. A separate
Linux job runs Detekt, structural checks, plugin packaging, and Plugin
Verifier once.

## Compatibility Notes

- Set `plugin.sinceBuild` to the oldest verified IDE build. Leave `plugin.untilBuild` unset unless a known incompatibility requires an explicit upper bound.
- If lowering `plugin.sinceBuild`, also check Kotlin runtime compatibility for the oldest target IDE.
- Prefer IntelliJ Platform common APIs in production code.
- Avoid Rider-only APIs unless they are isolated behind a product-specific adapter.
- Add extra verifier IDE codes only when you intentionally claim support for those IDEs.

## Support Matrix Policy

Treat compatibility as evidence-based:

| IDE family | Claim level | Required evidence before advertising support |
| --- | --- | --- |
| IntelliJ IDEA unified distribution | Primary | `compileKotlin`, `compileTestKotlin`, `buildPlugin`, and normal CI pass with `platform.type=IU`. |
| Rider | Compatible | Default plugin verifier target plus manual smoke test in a Rider sandbox before release. |
| PyCharm / WebStorm / CLion | Not claimed | Add product code to `plugin.verifier.ideCodes`, confirm CI can resolve that IDE distribution, run `verifyPlugin`, and do a tool-window/settings/manual Git smoke test first. |

Do not broaden Marketplace wording from "JetBrains IDEs that support Git projects" to a named IDE list until the corresponding row has evidence.

## Common Issues

| Error | Cause | Fix |
| --- | --- | --- |
| `Could not resolve ...` | Network or platform SDK download issue | Use `platform.localPath`, configure a proxy, or retry with `-PuseChinaMirrors=true` |
| `Plugin is incompatible with this installation` | The IDE is older than `plugin.sinceBuild`, or an explicit upper bound excludes it | Adjust `plugin.sinceBuild` or remove/update `plugin.untilBuild` |
| `incompatible version of Kotlin` | Compiler output newer than target IDE Kotlin runtime | Lower Kotlin compiler or raise target IDE build |
| Chinese Markdown looks garbled in terminal | Terminal encoding issue | See `docs/encoding-and-line-endings.md`; do not rewrite files just for terminal display |
