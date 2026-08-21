# Submodule Branch Switcher

[English](README.md) | [中文](README.zh-CN.md)

**Submodule Branch Switcher 是一个 JetBrains IDE 插件,用来一键把主仓库和所有子模块切换到预设的分支组合。**

![version](https://img.shields.io/badge/version-0.8.0-blue)

它适合这类项目:一个主仓库下挂着多个 Git 子模块,日常需要在 `main`、`develop`、发布分支、功能分支之间整组切换。你可以把每组分支保存成 preset,然后在 Tool Window 里直接切换。

preset 是项目内的 JSON 文件。个人 preset 默认保存在 `.idea/branch-presets.json`;需要共享时,团队可以在项目根目录创建并提交 `.branch-presets.json`。活动文件的解析规则见 [Preset 文件](#preset-文件)。

## 目录

- [主要功能](#主要功能)
- [截图](#截图)
- [支持的 IDE](#支持的-ide)
- [安装](#安装)
- [快速开始](#快速开始)
- [Preset 文件](#preset-文件)
- [配置项](#配置项)
- [冲突文件处理](#冲突文件处理)
- [故障诊断](#故障诊断)
- [许可证](#许可证)
- [参与开发](#参与开发)

## 主要功能

- **一键切换分支组合**:主仓库和每个子模块都可以配置目标分支。
- **切换前预览**:显示当前分支、目标分支、dirty 文件数量和分支来源。
- **脏工作区策略**:支持 stash 改动、跳过仓库,或不暂存而直接尝试切换。
- **冲突文件处理**:预览会标出 checkout 将要覆盖的未跟踪文件,并提供丢弃选项——全部丢弃,或只丢弃安全的 Unity `.meta` 文件。
- **失败回滚**:切换失败时保留 checkpoint,可从通知或历史里回滚。
- **子模块处理**:主仓切换后自动 sync,缺失或迁移后的新路径可自动 init;旧 preset 路径会被跳过,本地遗留工作树不会自动删除。
- **派生功能分支**:基于 preset,在主仓库和所有子模块同时创建新分支。
- **preset 管理**:从当前状态创建、重命名、排序、导入/导出、切回上一个 preset。
- **IDE 集成**:Tool Window、`Ctrl+Alt+B` 快速切换、通知、Settings 页面、中英文 i18n。

## 截图

![Tool Window 分支预设](screenshots/01-tool-window.png)

![切换前 dry-run 预览](screenshots/02-preflight-dialog.png)

![Settings 配置页](screenshots/03-settings.png)

## 支持的 IDE

插件使用 IntelliJ Platform API 和内置 `Git4Idea` 插件。构建以 IntelliJ Platform 2025.1 API 为最低基线,目前对 Rider 2025.1 及以上版本进行兼容验证。其他 IDE 系列的支持声明及所需证据见 [兼容性矩阵](docs/SETUP.md#support-matrix-policy)。

当前明确支持的仓库结构是一个主 Git 仓库及其通过 `.gitmodules` 递归注册的子模块。同一 IDE 项目中的多个独立 VCS Root 或同级仓库不在当前产品范围内。

## 安装

目前计划后续发布到 JetBrains Marketplace。现在可以从本地 ZIP 安装:

1. 运行 `./gradlew buildPlugin`,或下载构建好的 ZIP。
2. 打开 `Settings | Plugins | Install Plugin from Disk...`。
3. 选择 ZIP 文件。
4. 重启 IDE。

本地构建:

```bash
./gradlew buildPlugin
```

输出文件:

```text
build/distributions/submodule-branch-switcher-*.zip
```

## 快速开始

这部分讲的是切到已存在的分支。如果你想要的是在主仓库和所有子模块上同时创建一个同名的新分支,请在 preset 编辑器里用 **Derive(派生)**——它会基于 preset 创建该分支。

1. 打开 **SubmoduleBranches** 工具窗口。
2. 点击 **From Current State**,从当前主仓库和子模块分支创建 preset。
3. 按需修改目标分支。
4. 点击 **切换**。
5. 在 dry-run 预览窗口确认后执行切换。

示例 preset 文件:

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

## Preset 文件

preset 加载时会先检查 `.idea/branch-presets.json`,再检查 `.branch-presets.json`,最后向父目录查找,直到 Git 仓库边界。第一个匹配的文件就是当前实际生效的文件,**打开 Preset 文件**会定位到该路径——因此个人 `.idea` 文件可以覆盖团队共享的根目录文件。

打开 Tool Window 不会创建或改写 preset 文件;当不存在任何 preset 文件时,第一次成功保存才会创建个人 `.idea` 文件。插件不会把 preset 额外保存到全局配置;如果 `.idea` 没有被 Git 跟踪,删除该目录也会删除其中的 preset。

## 配置项

全局配置位置:`Settings | Version Control | Submodule Branch Switcher`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 未提交改动 | 暂存改动后切换 | 暂存并恢复改动——这是安全默认,未提交的改动会被保留并在切换后恢复;或跳过脏仓库,或不暂存直接尝试 checkout。恢复后的 stash 条目会作为手动恢复备份保留,直到你用 Git 移除它们。 |
| Git 命令超时 | 60s | 每次检查、fetch、checkout、pull 或子模块命令的最长运行时间。 |
| 刷新远端分支 | 开启 | checkout 前执行 `git fetch --prune`;失败会报告,并尽可能继续切换。 |
| 快进目标分支 | 开启 | checkout 后执行 `git pull --ff-only`,分叉时不会自动 merge。 |
| 确认缺失子模块 | 关闭 | 初始化每个本地缺失的子模块前先询问。 |

冲突文件的处理选项在预览对话框中按次选择——见 [冲突文件处理](#冲突文件处理)。

## 冲突文件处理

当目标分支跟踪了当前工作树里还是未跟踪状态的文件时,这些文件就是冲突文件:`git checkout` 会拒绝覆盖它们。预览会把这些文件标出来,切换前你可以通过确认步骤丢弃它们:

- **仅丢弃 .meta 文件**——保留其他所有文件。Unity `.meta` 文件删除是安全的,因为 Unity 会在导入时重新生成。
- **始终自动丢弃 .meta 文件**——记住这个选择,用于之后的切换。

其他文件一旦选择丢弃就会被永久删除,因此永远不会被静默选中。保留的冲突文件会让该仓库的 checkout 失败,而不会覆盖文件。

## 故障诊断

每次完整切换、派生分支和单仓切换都会生成操作 ID,例如 `switch-a1b2c3d4`;预检、执行、刷新和恢复会沿用同一个 ID,因此属于同一次操作的日志行可以在 Tool Window 日志或 `idea.log` 中一起收集(logger 名称为 `SubmoduleBranchSwitcher`)。

Tool Window 保留一个有上限、带时间戳的日志,支持过滤、复制和清空。Git 诊断中的 URI/SCP remote 和疑似凭据会替换成不可逆占位符;插件不发送任何遥测数据——除非用户主动分享,诊断信息始终保留在本地 IDE 日志中。

## 许可证

MIT——见 [LICENSE](LICENSE)。

## 参与开发

本地环境、架构边界、验证方式和审查约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。当前代码结构从 [中文代码架构与阅读指南](docs/ARCHITECTURE.zh-CN.md) 开始阅读,正式架构边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),完整文档索引见 [docs/README.md](docs/README.md)。日常开发可直接运行 `./gradlew runIde` 启动带插件的沙箱 IDE。
