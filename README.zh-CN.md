# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**Submodule Branch Switcher 是一个 JetBrains IDE 插件，用来一键把主仓库和所有子模块切换到预设的分支组合。**

![version](https://img.shields.io/badge/version-0.7.0-blue)
![tests](https://img.shields.io/badge/tests-288-green)
![JetBrains](https://img.shields.io/badge/JetBrains-2026.1-blue)

它适合这类项目：一个主仓库下挂着多个 Git 子模块，日常需要在 `main`、`develop`、发布分支、功能分支之间整组切换。你可以把每组分支保存成 preset，然后在 Tool Window 里直接切换。

preset 存在 `.idea/branch-presets.json`，可以随项目提交，让团队成员共享同一套分支组合。

## 主要功能

- **一键切换分支组合**：主仓库和每个子模块都可以配置目标分支。
- **切换前预览**：显示当前分支、目标分支、dirty 文件数量和分支来源。
- **脏工作区策略**：支持 stash、跳过、强制切换。
- **失败回滚**：切换失败时保留 checkpoint，可从通知或历史里回滚。
- **子模块处理**：主仓库切换后自动 sync，缺失子模块可自动 init。
- **派生功能分支**：基于 preset，在主仓库和所有子模块同时创建新分支。
- **preset 管理**：从当前状态创建、重命名、排序、导入/导出、撤销最近切换。
- **IDE 集成**：Tool Window、`Ctrl+Alt+B` 快速切换、通知、Settings 页面、中英文 i18n。

## 截图

![Tool Window 分支预设](screenshots/01-tool-window.png)

![切换前 dry-run 预览](screenshots/02-preflight-dialog.png)

![Settings 配置页](screenshots/03-settings.png)

## 支持的 IDE

插件使用 IntelliJ Platform API 和内置 `Git4Idea` 插件。

| IDE 系列 | 状态 | 说明 |
| --- | --- | --- |
| IntelliJ IDEA Community | 主要构建目标 | 本地开发和 CI 兼容构建默认使用。 |
| Rider | 兼容验证目标 | `plugin.verifier.ideCodes=RD` 覆盖；发布前保留 Rider 沙箱手工冒烟。 |
| IntelliJ IDEA Ultimate | 预期兼容 | 使用同一套 Platform + Git API，但暂未列为主要 verifier 目标。 |
| PyCharm / WebStorm / CLion | 暂不宣称支持 | 宣传 Marketplace 支持前，需要增加 verifier codes 和手工冒烟证据。 |

默认开发 SDK：

```properties
platform.type=IC
platform.version=2026.1.3
plugin.sinceBuild=261
plugin.untilBuild=261.*
plugin.verifier.ideCodes=RD
```

## 安装

目前计划后续发布到 JetBrains Marketplace。现在可以从本地 ZIP 安装：

1. 运行 `./gradlew buildPlugin`，或下载构建好的 ZIP。
2. 打开 `Settings | Plugins | Install Plugin from Disk...`。
3. 选择 ZIP 文件。
4. 重启 IDE。

本地构建：

```bash
./gradlew buildPlugin
```

输出文件：

```text
build/distributions/submodule-branch-switcher-0.7.0.zip
```

## 快速开始

1. 打开 **SubmoduleBranches** 工具窗口。
2. 点击 **From Current State**，从当前主仓库和子模块分支创建 preset。
3. 按需修改目标分支。
4. 点击 **Switch to this Preset**。
5. 在 dry-run 预览窗口确认后执行切换。

示例 preset 文件：

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

## 配置项

全局配置位置：`Settings | Version Control | Submodule Branch Switcher`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| Dirty working tree | Stash changes | 工作区有未提交修改时，选择 stash、跳过或强制切换。 |
| Timeout | 60s | 每条 Git 命令的超时时间。 |
| Fetch before switch | 开启 | 切换前执行 `git fetch --prune`。 |
| Pull after switch | 开启 | 切换后执行 `git pull --ff-only`。 |
| Confirm before init | 关闭 | 初始化缺失子模块前先确认。 |
| Local anonymous stats | 主动选择开启 | 本地匿名计数，不会自动发送数据。 |

## 开发与验证

```bash
# clone 后启用本地 hooks
git config core.hooksPath .githooks

# 快速结构检查
./gradlew quickCheck

# core 纯 JVM 测试
./gradlew pureTest

# 平台/集成测试
./gradlew test

# 构建插件 ZIP
./gradlew buildPlugin

# 启动带插件的沙箱 IDE
./gradlew runIde
```

日常开发优先跑轻量检查：

```bash
./gradlew quickCheck
./gradlew :core:test --tests "<ClassOrMethod>" --max-workers=1 --no-parallel
./gradlew test --tests "<ClassOrMethod>" --max-workers=2 --no-parallel
```

发布前再跑完整检查：

```bash
./gradlew :core:test test :core:detekt detekt --max-workers=2 --no-parallel
./gradlew releaseCheck
```

手动重型诊断：

```bash
# 大仓真实耗时基准
./gradlew benchmark

# 范围化变异测试
./gradlew pitestCore
```

`benchmark` 和 `pitestCore` 不进入普通 `test` 或 `releaseCheck`。
