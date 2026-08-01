# Environment Setup

This project compiles against the oldest supported common IntelliJ Platform
API and verifies the packaged plugin against each supported Rider platform
branch.

## Requirements

| Requirement | Notes |
| --- | --- |
| JDK 21 | The Kotlin/JVM toolchain and CI use the Java baseline required by IntelliJ Platform 2025.1. The resulting bytecode also runs on newer IDE runtimes. |
| Git CLI | Required by the plugin runtime and integration tests. |
| JetBrains IDE | Optional for local sandbox testing. Gradle can download the configured platform SDK automatically. |

## Platform Configuration

All platform and compatibility settings live in `gradle.properties`:

```properties
platform.type=IC
platform.version=2025.1
platform.localPath=

plugin.sinceBuild=251
plugin.verifier.ideTargets=RD:2025.1.9,RD:2026.2.0.1
```

Product codes commonly used here:

| Code | IDE |
| --- | --- |
| IU | IntelliJ IDEA unified distribution (2025.3+) |
| IC | IntelliJ IDEA Community |
| RD | Rider |
| PY | PyCharm Professional |
| WS | WebStorm |
| CL | CLion |

Default compilation uses IntelliJ IDEA Community 2025.1. Building against the
oldest supported platform makes accidental use of newer IntelliJ APIs a compile
error. Product-specific behavior is checked separately rather than moving the
compile baseline to the newest IDE.

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

## Plugin Versions During Development

Do not change the source version for every commit. The version declared in
`build.gradle.kts`, the README badge, and the latest CHANGELOG heading move
together only when preparing a formal release.

When repeatedly installing local ZIPs, give each package a unique temporary
version so the IDE cannot keep an older installation with the same plugin
version:

```bash
./gradlew buildPlugin -PlocalPluginVersion=0.8.0.20260801.1
```

Increment the final segment for each package installed into the same IDE. This
property changes only the generated ZIP and `plugin.xml`; it does not modify
tracked release metadata. Do not pass it to `releaseCheck`, which intentionally
requires the source version, README badge, and CHANGELOG to match.

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

`releaseCheck` runs Plugin Verifier for the explicit `PRODUCT:VERSION` endpoints
in `plugin.verifier.ideTargets`. The defaults cover the oldest supported Rider
branch and the current Rider branch without downloading every intermediate IDE
for each local release check. CI runs tests on Ubuntu, Windows, and macOS, then
verifies the plugin against the latest patch of every supported Rider platform
branch in parallel.

Rider installer verification is not supported by IntelliJ Platform Gradle
Plugin 2.11, so Rider targets use the larger non-installer SDK artifacts. Expect
a multi-gigabyte download on the first run. Refresh the exact patch versions in
`gradle.properties` and the CI matrix when adding a new supported platform
branch or preparing a release.

## Compatibility Notes

- Set `plugin.sinceBuild` to the oldest verified IDE build. Leave `plugin.untilBuild` unset unless a known incompatibility requires an explicit upper bound.
- Compile against the oldest supported platform, not merely the newest SDK with an older `sinceBuild` value.
- Kotlin is compiled with language and API level 2.1, matching the 2025.1 baseline, and the plugin does not package its compiler's newer standard library.
- Keep JVM bytecode at Java 21. Newer IDE runtimes can execute Java 21 bytecode; raising the target would break older supported branches.
- Prefer IntelliJ Platform common APIs in production code.
- Avoid Rider-only APIs unless they are isolated behind a product-specific adapter.
- Add explicit verifier targets and a manual smoke test before claiming another IDE family.

## Support Matrix Policy

Treat compatibility as evidence-based:

| IDE family | Claim level | Required evidence before advertising support |
| --- | --- | --- |
| Common IntelliJ Platform API | Build baseline | `compileKotlin`, `compileTestKotlin`, tests, and packaging pass against `IC:2025.1`. |
| Rider 2025.1 and newer | Supported | CI runs Plugin Verifier on each platform branch; perform a Rider sandbox smoke test before release. |
| IntelliJ IDEA | Expected compatible, not product-certified | The common API build provides source compatibility evidence; add IDEA Plugin Verifier and a manual Git-project smoke test before advertising it separately. |
| PyCharm / WebStorm / CLion | Not claimed | Add explicit `PRODUCT:VERSION` verifier targets, confirm CI can resolve them, and do a tool-window/settings/manual Git smoke test first. |

Do not broaden Marketplace wording from "JetBrains IDEs that support Git projects" to a named IDE list until the corresponding row has evidence.

## Common Issues

| Error | Cause | Fix |
| --- | --- | --- |
| `Could not resolve ...` | Network or platform SDK download issue | Use `platform.localPath`, configure a proxy, or retry with `-PuseChinaMirrors=true` |
| `Plugin is incompatible with this installation` | The IDE is older than `plugin.sinceBuild`, or an explicit upper bound excludes it | Adjust `plugin.sinceBuild` or remove/update `plugin.untilBuild` |
| `incompatible version of Kotlin` | Language/API level or packaged stdlib is newer than the target IDE runtime | Keep Kotlin API at 2.1 and do not package a newer stdlib |
| Chinese Markdown looks garbled in terminal | Terminal encoding issue | See `docs/encoding-and-line-endings.md`; do not rewrite files just for terminal display |
