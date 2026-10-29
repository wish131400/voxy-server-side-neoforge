# prediction ground cover lod 20261015

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**远景小植物 LOD：实现与验证，2026-10-15**

本轮已将“远景降低小草等细碎植物的重要性”的思路接入 VSS，完成 NeoForge 1.21.1 与 Forge 1.20.1 的同步、回归和打包。远处的小草、蕨类和常规交叉面花朵会逐档变稀，在屏幕上足够小时停止提交；靠近或放大视野后恢复。

安装的 Voxy 0.2.15-beta 会在生成粗体素时，从 2×2×2 的输入中主要按不透明度选取代表值。草往往被地形取代，但孤立植物也可能保留。该版本未发现按非空气块数量设置的最低门槛，因此不能把它概括为“远处绝不加载草”。VSS 借鉴的是远景重要性选择，具体实现使用现有的静态网格范围与 GPU 命令选择器。

**玩家能看到的变化**

| 档位 | 草丛保留方式 | 屏幕尺寸名义阈值 |
| --- | --- | --- |
| 0 | 完整保留 | 近景或未满足简化条件 |
| 1 | 按固定空间网格保留约 1/4 | 16 像素 |
| 2 | 保留约 1/16 | 8 像素 |
| 3 | 保留约 1/64 | 4 像素 |
| 4 | 停止提交小植物 | 2 像素 |

尺寸估算使用 2 方块大小，覆盖高草和高花的上下半株。进入更简档位使用表中阈值的 80%，恢复细节使用完整阈值，减少临界位置反复切换。空间选择保持稳定，同一株的交叉面和上下半株一起保留或移除，不按帧随机抽样。实际密度受植物分布影响，表中比例不是每个瓦片的固定数量。

完整显示距离至少为 256 方块，并受 `predictionSurfaceDistanceBlocks` 的更大设置保护。判断还使用实际投影、视口和几何边界，不能把某一档换算成所有分辨率和视野下都相同的方块距离。放大视野后，植物占据更多像素，会恢复相应细节。

树叶、地面草方块、竹子、甘蔗、树苗、农作物、水草、流体、预览几何以及使用模型 UV 的特殊模型不进入这个新增的完全移除档位。与其他内容共享材质的行采取保守分类，未知材质保留。它们原有的其他 LOD 规则仍独立工作。

**接入方式与交接保护**

工作线程将小植物整理成独立的连续范围，并生成静态 meshlet 分组；没有为五个档位复制五份 GPU 几何。CPU 提交路径缓存所选范围；常驻 GPU 场景使用当前帧投影选择小植物档位，再过滤对应间接命令。只改变相机和投影时，静态命令无需重新编码或上传，生产路径无需回读可见列表。

小植物选择与地形细分档位分开，不会为了省略草丛而强迫地形降档。只有完整、安全的归属范围允许简化；混合归属、接缝、morph 过渡和无法可靠计算投影的 CPU 路径保留源细节。GPU 节点槽重用时校验身份版本，避免新网格继承旧网格“完全移除”的历史。

冷生成与磁盘暖缓存恢复共用材质分类；分类缓存随材质变化失效。磁盘与网络格式没有变化，存量缓存可以继续恢复。原有植被和水草 LOD 的诊断禁用属性继续生效。

本轮节省的是提交、顶点处理和光栅工作。完整源几何仍然保留，便于靠近后立即恢复，因此不能把它宣传为等比例节省显存，也不能据此声称树冠和其他 CPU 热点已经解决。

**回归结果**

| 平台 | CPU 测试 | GPU 测试 | 失败 / 错误 / 跳过 |
| --- | --- | --- | --- |
| NeoForge 1.21.1，Java 21 | 274 | 14 | 全部 0 |
| Forge 1.20.1，Java 17 | 272 | 14 | 全部 0 |

两库原有测试集合不同：NeoForge 额外包含 `PredictionVisibleHandoffTest` 的两个测试。新增的小植物 CPU 与 GPU 测试在两库均运行。

验证覆盖五档选择、滞回、近景恢复、缩放、无效视图、整株一致性、暖缓存分类、共享材质保护、CPU 提交、GPU 命令选择、节点身份重用，以及相机变化不重复编码和上传。相关既有回归包括 Voxy 覆盖撤销、LOD 交接、接缝、水面、深度顺序、直接绘制与间接绘制。

GPU 图像对照使用生产着色器与真实 OpenGL。命令压缩的工作组输出顺序未定义，交叉面等深交点会受顺序影响；像素对照仅在测试读回后按源索引统一顺序，同时验证 primitive 集合无重复、无越界。下面的性能测试直接绘制 GPU 输出，不加入该排序过程。

**离线 GPU A/B**

设备：NVIDIA GeForce RTX 4070 Ti SUPER，OpenGL 4.6，驱动 572.70。两种模式使用相同常驻场景，只切换新增的小植物选择标记；地形档位保持一致。每次测量包含一次 GPU 选择与 64 次重复 MDI，预热 16 轮，正式交替测量 41 轮，取中位数。顶点场景启用 rasterizer discard，光栅场景正常绘制。

| 平台 / 场景 | GPU 关闭 → 开启 | CPU 提交关闭 → 开启 |
| --- | --- | --- |
| NeoForge，顶点 | 0.879616 → 0.641024 ms | 0.4295 → 0.4548 ms |
| NeoForge，光栅 | 1.645568 → 0.516096 ms | 0.4711 → 0.5423 ms |
| Forge，顶点 | 0.983040 → 0.442368 ms | 0.5614 → 0.4282 ms |
| Forge，光栅 | 1.803264 → 0.488448 ms | 0.4800 → 0.6226 ms |

两平台的 64 次重复绘制均从 2,098,048 个四边形、8,256 条命令降为 896 个四边形、64 条命令。单次绘制的 32,768 个草丛四边形停止提交，所选地表 14 面保留。近景与放大视野的测试确认恢复植物。

这证明该草丛合成负载的 GPU 工作减少；CPU 提交时间存在波动，本轮没有得到 CPU 必然加速的结论。数据不是游戏单帧耗时，不能乘除后换算游戏 FPS。本轮未启动游戏或进行存档前后对照，也未替换客户端模组文件。

**交付与复核**

两个平台保留现有 0.3.5 版本号，使用独立日期目录区分本次产物：

- NeoForge：[vss-0.3.5-neoforge-1.21.1.jar](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/ground-cover-20261015/vss-0.3.5-neoforge-1.21.1.jar)，6,143,108 字节。SHA256：`ff07b5ff0496fe933ebd4aba03cb696a57f9e7208a291f5bc36059a41a4ebbc3`。
- Forge：[vss-0.3.5-forge-1.20.1.jar](C:/Users/Administrator/Desktop/voxyserverside/lib/ground-cover-20261015/vss-0.3.5-forge-1.20.1.jar)，6,813,546 字节。SHA256：`3f930b498caf09faf1b0af4bacea9884490ad8a29003a79cd83d5ace3055ec04`。

每个产物目录包含 `artifact-manifest.json`，记录包哈希、测试计数、生产源文件哈希和原生库校验。检查已确认包内无测试类、类版本正确、资源与原生库完整。NeoForge 的所有生产 class 与测试使用的固定 runtime JAR 逐字节一致；Forge 已完成 `reobfJar` 并检查重混淆名称和 refmap。

完整构建日志、两个平台的原始 A/B CSV 与汇总保存在两仓库各自的 `tools/prediction/ground-cover-20261015` 目录，分别为 `validation.log`、`neoforge-ground-cover.csv`、`forge-ground-cover.csv` 与 `validation-summary.json`。

复现命令（在对应仓库执行）：

```powershell
# NeoForge / Java 21
.\gradlew.bat -I tools/prediction/ground-cover-tests.gradle groundCoverRegressionTest groundCoverGpuTest jar --console=plain --max-workers=2
python tools/prediction/ground-cover-20261015/audit-artifacts.py . neoforge

# Forge / Java 17
.\gradlew.bat '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/ground-cover-tests.gradle groundCoverRegressionTest groundCoverGpuTest jar --console=plain --max-workers=2
python tools/prediction/ground-cover-20261015/audit-artifacts.py . forge
```
