# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**Submodule Branch Switcher 是一个 JetBrains IDE 插件，用来一键把主仓库和所有子模块切换到预设的分支组合。**

它适合这类项目：一个主仓库下挂着多个 Git 子模块，日常需要在 `main`、`develop`、发布分支、功能分支之间整组切换。你可以把每组分支保存成 preset，然后在 Tool Window 里直接切换。

preset 是项目内的 JSON 文件。首选位置是 `.idea/branch-presets.json`，也支持项目根目录的
`.branch-presets.json`。如果需要避免本地 IDE 配置清理造成丢失，并让团队共享，请把选定的
preset 文件提交到 Git。

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

插件使用 IntelliJ Platform API 和内置 `Git4Idea` 插件。IntelliJ IDEA
Community 是主要构建目标，Rider 是当前兼容验证目标。其他 IDE 系列的支持声明及所需证据见
[兼容性矩阵](docs/SETUP.md#support-matrix-policy)。

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
build/distributions/submodule-branch-switcher-*.zip
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

preset 加载时会先检查 `.idea/branch-presets.json`，再检查
`.branch-presets.json`，最后向父目录查找，直到 Git 仓库边界。插件不会把 preset
额外保存到全局配置；如果 `.idea` 没有被 Git 跟踪，删除该目录也会删除其中的 preset。

## 配置项

全局配置位置：`Settings | Version Control | Submodule Branch Switcher`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| Dirty working tree | Stash changes | 工作区有未提交修改时，选择 stash、跳过或强制切换。 |
| Timeout | 60s | 每条 Git 命令的超时时间。 |
| Fetch before switch | 开启 | 切换前执行 `git fetch --prune`。 |
| Pull after switch | 开启 | 切换后执行 `git pull --ff-only`。 |
| Confirm before init | 关闭 | 初始化缺失子模块前先确认。 |

## 参与开发

本地环境、架构边界、验证方式和审查约定见
[CONTRIBUTING.md](CONTRIBUTING.md)。当前代码结构从
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 开始阅读，完整文档索引见
[docs/README.md](docs/README.md)。日常开发可直接运行 `./gradlew runIde`
启动带插件的沙箱 IDE。
