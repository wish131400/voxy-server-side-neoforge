# 安装

[文档目录](../README.md)

Minecraft **1.21.1** / **NeoForge 21.1.x** / **Java 21**。

服务端需要：

- NeoForge `21.1.x`
- VSS
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)

客户端可选安装 VSS。不安装仍可进入服务器，使用普通区块视距；不会接收 VSS 远景或执行 VSS 预测。服务器其他模组的客户端依赖仍需满足。

需要 VSS 远景功能的客户端需要：

- NeoForge `21.1.x`
- VSS
- Voxy，或仅安装 Xaero's World Map 作为远景地图消费者
- Sodium 兼容渲染环境
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)

服务端不需要安装 Voxy。安装 VSS 的客户端与服务端应使用对应平台、同一次构建的 VSS；本仓库协议为 **53**。本仓库产物为 `vss-0.3.7-neoforge-1.21.1.jar`，不能与其他 Minecraft 版本或加载器互换。

首次使用后配置位于 `config/`，详细设置见 [配置](configuration.md)。升级现有存档见 [0.3.7 升级说明](upgrade.md)；依赖组合见 [兼容范围](../compatibility/README.md)。
