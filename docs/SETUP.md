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
platform.type=IC
platform.version=2026.1.3
platform.localPath=

plugin.sinceBuild=261
plugin.untilBuild=261.*
plugin.verifier.ideCodes=RD
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
./gradlew buildPlugin
```

This resolves the configured SDK and verifies that the plugin package can be
built. Installation instructions remain in the project README.

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
avoid heavy downloads. CI runs tests, plugin build, Detekt, and structural
checks on Ubuntu, Windows, and macOS; Plugin Verifier runs only on Linux.

## Compatibility Notes

- Keep `plugin.sinceBuild` and `plugin.untilBuild` aligned with the IntelliJ Platform major build you support.
- If lowering `plugin.sinceBuild`, also check Kotlin runtime compatibility for the oldest target IDE.
- Prefer IntelliJ Platform common APIs in production code.
- Avoid Rider-only APIs unless they are isolated behind a product-specific adapter.
- Add extra verifier IDE codes only when you intentionally claim support for those IDEs.

## Support Matrix Policy

Treat compatibility as evidence-based:

| IDE family | Claim level | Required evidence before advertising support |
| --- | --- | --- |
| IntelliJ IDEA Community | Primary | `compileKotlin`, `compileTestKotlin`, `buildPlugin`, and normal CI pass with `platform.type=IC`. |
| Rider | Compatible | Default plugin verifier target plus manual smoke test in a Rider sandbox before release. |
| IntelliJ IDEA Ultimate | Expected compatible | Add `IU` to verifier list if Marketplace copy explicitly names it. |
| PyCharm / WebStorm / CLion | Not claimed | Add product code to `plugin.verifier.ideCodes`, confirm CI can resolve that IDE distribution, run `verifyPlugin`, and do a tool-window/settings/manual Git smoke test first. |

Do not broaden Marketplace wording from "JetBrains IDEs that support Git projects" to a named IDE list until the corresponding row has evidence.

## Common Issues

| Error | Cause | Fix |
| --- | --- | --- |
| `Could not resolve ...` | Network or platform SDK download issue | Use `platform.localPath` or retry with proxy/mirror available |
| `Plugin is incompatible with this installation` | Build range does not cover target IDE | Adjust `plugin.sinceBuild` / `plugin.untilBuild` |
| `incompatible version of Kotlin` | Compiler output newer than target IDE Kotlin runtime | Lower Kotlin compiler or raise target IDE build |
| Chinese Markdown looks garbled in terminal | Terminal encoding issue | See `docs/encoding-and-line-endings.md`; do not rewrite files just for terminal display |
