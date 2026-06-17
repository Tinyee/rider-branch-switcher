# 环境准备 & 适配不同 Rider 版本

记录从零跑起来需要装什么，以及切换 Rider 主版本时要改哪几处。

## 必装依赖

| 依赖 | 怎么装 | 为什么 |
|---|---|---|
| **JDK 17 或 21** | macOS：`brew install openjdk@17`（或 21）；Windows：[Adoptium](https://adoptium.net/) | Gradle 工具链 + 编译。Rider 2026 警告需要 21，17 也能跑 |
| **Rider** | 装到默认路径；`build.gradle.kts` 里写死了 `local("/Applications/Rider.app")` | 用本机 Rider 当 SDK，省 1.5 GB 下载 |
| **Git CLI** | macOS：`xcode-select --install`；Windows：[git-scm](https://git-scm.com/) | 插件本身就是包 `git` 命令 |

## 镜像/网络（国内无 VPN 也能跑）

| 项 | 当前配置 | 说明 |
|---|---|---|
| Maven 镜像 | `build.gradle.kts` + `settings.gradle.kts` 都指了 aliyun | aliyun 是公网公开镜像，无需 VPN，**保持就好** |
| Gradle 发行版 | `gradle-wrapper.properties` 指官方 `services.gradle.org` | 国内首装可能慢几分钟，装完缓存到 `~/.gradle/wrapper/dists/`，之后不再下载 |
| IntelliJ Platform 依赖 | `intellijPlatform { defaultRepositories() }` 走 jetbrains 官方 + cache-redirector | 首次跑会下一些 jar，全本地化到 `~/.gradle/caches/`，之后无网也能编译 |

## 完全不需要

- 不需要单独装 Kotlin / Gradle / IntelliJ Platform SDK，全部由 Gradle wrapper 拉
- 不需要 Android SDK / .NET / Unity

## 第一次跑

```bash
cd ~/Code/rider-branch-switcher

# 启用提交前自动检查（Windows/macOS/Linux 通用）
git config core.hooksPath .githooks

./gradlew buildPlugin
# 产物：build/distributions/rider-branch-switcher-0.7.0.zip
```

`git config core.hooksPath .githooks` 只需执行一次。之后每次 `git commit` 会自动跑 quickCheck，
`git push` 会自动跑 releaseCheck。跳过检查：`git commit --no-verify`。

Rider 里 `Settings → Plugins → ⚙ → Install plugin from disk` 选这个 zip。

---

# 适配不同 Rider 版本

所有版本相关配置集中在 **`gradle.properties`**，不动 `build.gradle.kts`。

## 只需要改 `gradle.properties`

```properties
# Rider SDK 版本（在线下载时使用，或设 rider.path 指向本机安装）
rider.version=2026.1.1
# 插件兼容的 IDE build 号
plugin.sinceBuild=261
plugin.untilBuild=261.*
# 指向本机 Rider 安装（设了就跳过 1.5GB 下载）
rider.path=
```

各 OS 的 `rider.path` 示例：

| OS | 路径示例 |
|---|---|
| macOS | `/Applications/Rider.app` |
| Windows | `C:/Program Files/JetBrains/JetBrains Rider 2026.1` |
| Linux | `~/.local/share/JetBrains/Toolbox/apps/rider/...` |

切换 Rider 版本只需改 `rider.version` 和 `plugin.sinceBuild`，对应关系：

| Rider | build 前缀 |
|---|---|
| 2024.3 | 243 |
| 2025.1 | 251 |
| 2025.2 | 252 |
| 2026.1 | 261 |
}
```

## 2. `build.gradle.kts` 里 sinceBuild / untilBuild

```kotlin
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"      // ← Rider 主版本对应的 build 前缀
            untilBuild = "261.*"    // ← 不想锁可改 "999.*"
        }
    }
}
```

Rider 主版本与 build 前缀对照：

| Rider | build 前缀 |
|---|---|
| 2024.1 | 241 |
| 2024.2 | 242 |
| 2024.3 | 243 |
| 2025.1 | 251 |
| 2025.2 | 252 |
| 2026.1 | 261 |

想插件跨多 Rider 版本可装：`sinceBuild = "243"`、`untilBuild = "999.*"`。SDK 仍按编译那个版本，运行时偶有 API 不兼容，改完测一遍。

## 3. Kotlin 编译器版本 ≤ Rider 内置 Kotlin

在 `build.gradle.kts` 的 `plugins` 块中（Gradle `plugins` 块不支持属性懒加载，需直接改版本号）：

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0"     // ← 改这里
}
```

| Rider | 内置 Kotlin | 编译器版本 |
|---|---|---|
| 2024.1 / 2024.2 | 1.9.x | `1.9.x` |
| 2024.3 / 2025.1 | 2.0.x / 2.1.x | `2.0.x / 2.1.x` |
| 2025.2 | 2.2.x | `2.2.x` |
| 2026.1 | 2.3.x | `2.3.x` ← 当前 |

> 编译期 Kotlin 高于 Rider 运行时会报：`incompatible version of Kotlin`。

---

# 开发加速：Sandbox Rider

不用每次改完手动装插件，一行命令启动预装插件的 Rider 实例：

```bash
./gradlew runIde
```

- 自动打开一个沙盒 Rider，插件已预装
- 改代码后关掉重跑 `runIde` 即可
- 沙盒不会影响你正常装的 Rider 和配置

---

# 运行测试

```bash
./gradlew test          # 282 用例（mock GitClient / 真实 git 仓库 / UI 规则 / Kotest 属性测试），`./gradlew test` 即可运行
```

# 常见错误速查

| 报错 | 原因 | 对策 |
|---|---|---|
| `Could not resolve rider:JetBrains.Rider:<ver>... Connect timed out` | 在线 rider() 配置 + 网络慢 | 改回 `local("/Applications/Rider.app")` |
| `incompatible version of Kotlin: binary version 2.3.0, expected 1.9.0` | Kotlin 编译器版本高于 Rider 运行时 | 把 `id("org.jetbrains.kotlin.jvm") version "..."` 降到 Rider 内置 Kotlin 的同等或更低版本 |
| `sourceCompatibility='17' but IntelliJ Platform '2026.1.1' requires sourceCompatibility='21'` | JDK 警告，**不是错误** | 升 `kotlin.jvmToolchain(21)` + 本机装 JDK 21；不升也能跑 |
| `Plugin 'XXX' is incompatible with this installation` | sinceBuild/untilBuild 没覆盖目标 Rider | 调整 `untilBuild` 或重新编译 |
| `Could not resolve all artifacts ... Connection refused` 等 maven 拉取失败 | 国内官方源被墙 | 检查 `settings.gradle.kts` / `build.gradle.kts` 顶部的 aliyun 镜像是否还在 |

---

# 发布到 JetBrains Marketplace

## 编译产物

```bash
./gradlew buildPlugin
# 产出: build/distributions/rider-branch-switcher-0.4.0.zip
```

## 首次上传

1. 注册账号：https://plugins.jetbrains.com/
2. 点右上角 **Upload plugin** → 拖上面的 `.zip`
3. 填写信息：
   - License：推荐 `MIT` 或 `Apache-2.0`
   - Category：`VCS Integration`
   - Tags：`git`, `submodule`, `branch`
4. 提交审核（通常 2-3 个工作日）

## 后续更新（命令行一键发布）

1. 到 https://plugins.jetbrains.com/authorize 生成 Personal Access Token
2. 把 token 写入 `gradle.properties`：
   ```properties
   publishToken=perm:...
   ```
   或者设环境变量 `export PUBLISH_TOKEN=perm:...`
3. 更新 `build.gradle.kts` 顶部的版本号：
   ```kotlin
   version = "0.4.1"
   ```
4. 发布：
   ```bash
   ./gradlew publishPlugin
   ```

## 兼容多 Rider 版本

改 `gradle.properties` 三行，然后 `./gradlew buildPlugin` 重新编译：

```properties
rider.version=2025.2       # 编译用的 SDK 版本
plugin.sinceBuild=243      # 最低兼容 IDE build
plugin.untilBuild=999.*    # 最高兼容（999.* 表示不封顶）
```

| Rider 版本 | build 前缀 |
|---|---|
| 2024.1 | 241 |
| 2024.2 | 242 |
| 2024.3 | 243 |
| 2025.1 | 251 |
| 2025.2 | 252 |
| 2026.1 | 261 |

> ⚠️ `sinceBuild` 降到低于 261 时，Kotlin 编译器版本（`build.gradle.kts` 的 `plugins` 块）也要同步降，否则运行时报 incompatible Kotlin version。
