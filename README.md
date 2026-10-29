# Voxy Server Side NeoForge

Voxy Server Side（VSS）让服务端读取、生成、缓存并发送真实远景列，减少客户端重复生成与下载。客户端还可使用 Rust / Java 预测远处地形、完整植被和地表结构；真实数据与预测体素共同交给 Voxy 构网、细化和渲染，真实数据优先。

[文档目录](docs/README.md) · [0.3.6 更新日志](CHANGELOG.md) · [升级说明](docs/user-guide/upgrade.md) · [下载](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

## 版本

| 项目 | 版本 |
| --- | --- |
| Minecraft / Java | 1.21.1 / Java 21 |
| 加载器 | NeoForge 21.1.x |
| VSS | 0.3.6；协议 53 |
| Voxy 检查基线 | Voxy `0.2.15-beta`（NeoForge 1.21.1） |
| 标准安装包 | `vss-0.3.6-neoforge-1.21.1.jar` |

客户端与服务端使用对应平台、同一次构建的 VSS。标准产物位于 `lib/`；不同加载器的 JAR 不能互换。具体 Voxy 与地形模组版本见 [兼容范围](docs/compatibility/README.md)。

## 安装与使用

1. 服务端安装 NeoForge 和 VSS；专用服务器不需要 Voxy。
2. 客户端安装 NeoForge、VSS、对应的 Voxy 及渲染依赖。只使用 Xaero 世界地图接收真实远景时，可以不安装 Voxy。
3. 两端使用同次构建后进入游戏。首次安装的预测默认开启，标记为 Beta；已有配置保留保存值。

[安装依赖](docs/user-guide/installation.md) · [配置](docs/user-guide/configuration.md) · [命令与预生成](docs/user-guide/commands.md) · [缓存与排错](docs/user-guide/cache-and-troubleshooting.md)

预测默认距离为 4096 方块，支持本地缓存和望远镜额外加载。预测是生成规则的近似，无法覆盖所有模组后处理或玩家改动；准确内容以服务端真实列为准。Xaero 地图继续接收真实列，预测转地图暂时停用。行为与开关见 [预测与远景](docs/user-guide/prediction.md)。

## 开发与项目

[贡献约定](CONTRIBUTING.md) · [模块架构](docs/development/architecture.md) · [公开 API](docs/development/column-filter-api.md) · [历史报告](docs/history/README.md)

其他平台独立维护：[Forge 1.20.1](https://github.com/wish131400/voxy-server-side-forge) · [Fabric Horizon](https://github.com/wish131400/voxy-server-side-Horizon)。

项目使用 [MIT 许可证](LICENSE)，第三方原生库与依赖信息见 [第三方许可证](src/main/resources/META-INF/THIRD_PARTY_LICENSES.md)。
