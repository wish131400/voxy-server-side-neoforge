# 兼容范围

[文档目录](../README.md)

本页适用于 **Minecraft 1.21.1 / NeoForge**。接口检查、算法验证与整合包实机验收是不同范围，未列出的版本不能沿用已有结论。

## Voxy 与加载器

| 项目 | 当前记录 |
| --- | --- |
| Minecraft / Java | 1.21.1 / Java 21 |
| 加载器 | NeoForge 21.1.x |
| VSS | 0.3.6；两端使用同次构建；协议 53 |
| Voxy 检查基线 | Voxy `0.2.15-beta`（NeoForge 1.21.1） |

预测和真实体素共同由 Voxy 构网、细化与绘制。Voxy 分支的具体接口仍需匹配；名称相同或版本号接近不能代替实际验证。历史逐项适配清单见 [0.3.5 核对记录](../history/releases/release-0.3.5-mod-compatibility-20261006.md)，其中旧渲染器与旧配置说明按当时版本解读。

## Xaero 世界地图

客户端安装 [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 后，VSS 会把服务端发送的真实远景列写入世界地图，不要求安装 Voxy。原版已经加载的近处区块仍由 Xaero 自己绘制。

预测地形和植被转换为 Xaero 地图的模块暂时停用。VSS 的 Rust / Java 远景预测继续使用 Voxy 渲染；已经保存到 Xaero 的预测地图内容会保留，之后到达的真实数据仍可覆盖。

该功能默认开启，可在 VSS 的 Embeddium/Sodium 设置页控制，需要临时关闭地图写入时执行 `/vssclient xaero disable`，恢复时执行 `/vssclient xaero enable`。执行 `/vssclient xaero reload` 会清除当前服务器所有维度的客户端列存在性记录并自动重新请求已有 LOD 缓存。

Xaero 支持下限仍为 1.40.0。本次分别检查了 NeoForge 1.21.1 和 Forge 1.20.1 对应的 1.40.16、1.45.0、1.46.0 发布 JAR，写入、保存及来源识别接口均通过检查；其他版本和整合包组合仍需验证。反射接口不兼容时，Xaero 桥接会自动停用，不影响 VSS/Voxy 的 LOD 功能。当前状态见 [0.3.6 说明](../user-guide/upgrade.md#xaero-世界地图)。

## 地形模组版本与史诗地形

本项目 JAR 的游戏版本仍为 **Minecraft 1.21.1 / NeoForge**。上游地形模组提供其他 Minecraft 版本的下载，不表示本 JAR 可以跨游戏版本使用。当前验证目标：

| 地形模组 | 1.21.1 验证版本 | 预测路径与边界 |
| --- | --- | --- |
| Tectonic | `3.0.26-neoforge-21.1`，配合 Lithostitched `1.8.0+beta6-neoforge-21.1` | 按实际运行图及自定义注册表重建；未验证所有旧版，不能声称整个 3.x 系列均支持 |
| FreeTerraForged / ReTerraForged | `1.0.0-neoforge-1.21.1` / `0.0.6005-neoforge-1.21.1` | Java 生成上下文加 Rust 瓦片过滤；适配新版命名空间、64 位种子及高山高度压缩。其他发布版尚未逐一验证 |
| [ETN 史诗地形](https://www.mcmod.cn/class/15808.html) / Epic Terrain | 发布名 `v0.1.4b-Beta-1.20.5~1.21.1`，文件 `epicterrain-0.1.4.jar` | Rust 重建密度缓存访问顺序、基础柱与扩展高度；已与发布数据包的 Minecraft NoiseChunk 结果对照 |
| Epic Terrain Compatible | `1.0.3+mod`，文件 `epic-terrain_compatible-1.0.3.jar` | 三维 `cache_2d`、插值切片和单元格批量填充已在 Rust 实现，并验证基础柱液体结果。此处不包含仅标注 Forge 的 `1.0.3b-1.21.1` |

Rust 样条现在保留数据包内重复/未排序控制点，并按 Minecraft `CubicSpline` 的二分查找求值；不会擅自排序或合并。对于随高度变化的 `cache_2d`，原生图构建返回明确的不支持原因，客户端保留已经解码的 Minecraft Java 采样器。诊断仍受 debug 模式控制。

这些是各自生成配置的适配结果，不代表 Tectonic、FreeTerraForged 与史诗地形可以同时叠加生成；它们之间的数据包覆盖、TerraBlender 扩展和特定整合包的光影仍需分别验证。复现命令和历史采样验证见 [Epic Terrain 记录](../../tools/prediction/EPIC_TERRAIN_2026-09-09.md)。

## FreeTerraForged / ReTerraForged

已接入 [ETcodehome/FreeTerraForged](https://github.com/ETcodehome/FreeTerraForged) 发布版 `1.0.0-neoforge-1.21.1`，并保留 ReTerraForged `0.0.6005-neoforge-1.21.1` 支持。上述两个 NeoForge 发行包已有实际接口与数值验证；Forge 1.20.1 的共享代码同步不计作这些发行包的跨平台实测。自动识别新旧命名空间，服务器同步实际预设与噪声注册表，客户端按维度设置其初始化上下文，并保留新版地下生物群系设置。两端需使用同次构建的 VSS，并安装对应生成模组。粗模使用模组自己的点估算；更细地形读取逐方块侵蚀缓存，继续沿用近处/望远镜优先调度。河流、湖泊与湿地水面使用模组的水文高度函数；预测专用地形缓存会在工作线程结束后释放。

FreeTerraForged 的侵蚀、平滑、坡度和海滩检测已由预测专用挂钩批量交给 Rust；旧版保留海滩修正，新版按预设执行高山高度压缩，并使用独立的地形高度缩放与完整种子转换。普通服务器生成器不注册该挂钩。Perlin/Perlin2、Simplex/Simplex2、白噪声、基础组合噪声、密度量化和线性样条也已有原生实现。大陆、河网、气候、部分噪声及其地表/装饰扩展仍使用 Java，完整 FreeTerraForged 生成器尚未全部迁入 Rust。已用发布 JAR 验证数值，整合包中的 Mixin 转换及最终画面仍需实机验证。瀑布流动、河岸补块等区块后处理不保证逐方块复现；已有真实列仍由 Voxy 覆盖。此适配不代表原始 RTF 的所有分支、任意地形模组叠加或 TerraBlender 扩展都已兼容。验证范围见 [FreeTerraForged 专项说明](freeterraforged.md)。
