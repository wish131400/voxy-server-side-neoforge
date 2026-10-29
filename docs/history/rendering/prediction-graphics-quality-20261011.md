# prediction graphics quality 20261011

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**预测 LOD 已接入全局图像品质及 Sodium / Embeddium 的独立 Leaves 设置，两个平台保持 0.3.5。** 当前截图的“图像品质：流畅、Leaves：默认”会让已确认的预测树叶作为实体表面绘制；精美模式保留树叶纹理孔洞。切换作用于已上传网格和磁盘恢复网格，无需清空预测缓存或重建地形。

日期：2026-10-11。交付目录：两个仓库各自的 `lib/graphics-quality-20261011`。本包包含上一轮 Voxy 风格扫描合并，以及已有植被、内存和覆盖交接修复。本轮没有替换实例 JAR 或操作游戏，也没有推送 GitHub。

**设置语义**

| 全局图像品质 | Leaves | 预测树叶处理 |
| --- | --- | --- |
| 流畅 | 默认 | 实体表面，不裁掉树叶纹理的透明孔洞 |
| 精美 / 极佳 | 默认 | 按树叶纹理透明度裁剪 |
| 任意 | 流畅 / Fast | 实体表面 |
| 任意 | 精美 / Fancy | 按纹理透明度裁剪 |

独立 Leaves 设置优先于全局设置。没有兼容的 Sodium / Embeddium API 时跟随原版全局图像品质；未知值或 API 尚未初始化时采用“默认”语义。应用图形设置后，下一次预测绘制读取当前值。

草、花、火焰仍使用原来的透明裁剪。树木的位置、尺寸、种类和数量继续由现有预测和植被档位决定。本轮只接入树叶品质，没有把云、天气、粒子、实体阴影或平滑光照等其它选项都映射为预测渲染设置。

**实际 Voxy 行为与收益来源**

核对了实例安装的 Voxy 0.2.15-beta（NeoForge）和 0.2.14-alpha（Forge）的实际 JAR 字节码：模型烘焙的 `SoftwareModelTextureBakery.renderToOutput` 对 LeavesBlock 选择实体渲染层，`ReuseVertexConsumer.quad` 的 forceSolid 路径关闭 alpha 裁剪。因此不能将 Voxy 的全部植被性能优势归因于全局“图像品质”开关；它的 LOD 叶子本来就采用了实体化处理。

VSS 之前已有相邻树叶占据判断及内部面剔除，本轮不把它计为新增收益。Fast 的主要作用是让树冠孔洞不再透出后方的多层几何，使深度遮挡更早成立；远处的实体叶面还可复用已有的不透明材质距离路径，减少不必要的图集查询。提交的命令、基础几何和面数不会仅因为切换本设置而减少。

**实现与兼容边界**

- `PredictionGraphicsQuality` 缓存反射入口，并读取已应用的 options 对象，兼容更换 options 实例。核对的实际 API 分别是 Sodium 0.8.12-beta.2 的 `net.caffeinemc.mods.sodium.client.SodiumClientMod.options()` 和 Embeddium 0.3.31 的 `me.jellysquid.mods.sodium.client.SodiumClientMod.options()`。没有增加这两个模组的强制编译或运行依赖。
- `VssLodSpriteTable` 将确认属于 LeavesBlock 的材质行生成一个 256 位掩码。只有材质映射变化才重新计算；每个 mesh 不保存掩码副本。已知与非树叶方块共用一行的纹理保守地保留 alpha 裁剪。
- 磁盘恢复读取每份材质描述的代表方块。不同描述恢复到同一材质行时，仍合并树叶 / 非树叶分类并使掩码失效，不让首个代表方块覆盖后续共享材质信息。图集重载和关闭清空分类；磁盘格式保持兼容。
- `PredictionTerrainProgram` 的普通、MDI 和 Iris shader 共享 FastLeaves 与 LeafSprites uniform。原 packed cutout 标志保持原值，Fast 只在当前绘制中覆盖已确认树叶的裁剪行为，因此精美恢复不依赖重新生成几何。流体、unshaded 十字草花和特殊模型继续保留自己的处理。
- `PredictionRenderer.bindFrame` 在每次绑定当前绘制程序时应用设置。布尔 uniform 只在品质变化时上传；树叶掩码只在 Fast 且材质快照变化时上传。

非 LeavesBlock 的特殊模组树叶、未知材质、共享行或 unshaded 特殊模型可能继续保留透明裁剪。这是保守兼容边界，不保证所有模组自定义植物都取得 Fast 的收益。

**正确性验证**

新增 CPU 回归覆盖全局 Fast / Fancy / Fabulous 与独立 Leaves 的组合、可选 API 未就绪、设置改变、options 实例替换、未知枚举回退、冷材质恢复、已缓存掩码失效及多个冷描述共用一行的情况。NeoForge headless Bootstrap 不执行 loader 的方块状态 ID 分配，测试显式补齐状态 ID 后验证真实 LeavesBlock 分类。

新增 GPU 回归使用生产 shader 和已上传的紧凑网格，包含草花十字面、树叶平面、静态火焰、mipmap、完整 / 混合 / 撤销 / 恢复覆盖、原版 mask、近处深度挡板和窄视角。4 个模式 × 2 个 FOV × 5 个覆盖状态 × 3 个深度状态 × 2 个原版 mask，共 240 组条件：

- 普通直接绘制；MDI；Iris 标准深度；Iris 反向深度。
- Fast 的颜色及深度与“仅树叶关闭 cutout 标志”的独立几何对照逐像素一致。
- 切回 Fancy 后同一 GPU 网格的颜色及深度恢复；Fast 状态换成空材质掩码后恢复裁剪。
- 另用上一轮植被渲染 JAR 的 shader 作 Fancy 对照，确认原来草花 / 树叶 / 火焰的颜色和深度保持一致。MDI 对照显式使用纹理路径，避免平均色路径掩盖透明裁剪测试。

此前的扫描合并、紧凑编码、压缩上传、密集森林分段、显示 LOD、覆盖交接、MDI、GPU 遮挡及安装版 Voxy 最终深度 blit 回归继续通过。扫描合并自身已有的 alpha 边缘插值容差仍沿用上一轮报告，不把它说成完全逐像素相同的合并。

| 平台 | CPU 回归 | OpenGL 回归 | 打包 |
| --- | ---: | ---: | --- |
| NeoForge 1.21.1 | 156 通过，1 项可选语料回放跳过 | 12 通过，0 跳过 / 失败 | JDK 21，成功 |
| Forge 1.20.1 | 155 通过，1 项可选语料回放跳过 | 12 通过，0 跳过 / 失败 | JDK 17，jar + reobfJar 成功 |

跳过项是 `PredictionGpuEncodingTest.realCorpusRoundTripAndEncodingCost`，需要额外指定完整历史语料入口；本轮没有重新运行该可选全语料编码测试。已有上一轮完整扫描回放报告可继续参照。

**合成 GPU 计时结果与限制**

显卡：NVIDIA GeForce RTX 4070 Ti SUPER，OpenGL 4.6，驱动 572.70。两个平台依次执行，未并行运行 GPU 测试。每轮 32 次绘制，256×256 目标，预热 8 轮，然后交替测量 15 轮，取 GL_TIME_ELAPSED 中位数。相同的几何、材质和视角，仅切换 FastLeaves。

| 合成场景 | 深度测试 | NeoForge Fancy → Fast | Forge Fancy → Fast |
| --- | --- | ---: | ---: |
| 384 面混合草花 / 叶面 | 关闭 | 0.107520 → 0.107520 ms | 0.107520 → 0.107520 ms |
| 384 面混合草花 / 叶面 | 开启 | 0.106496 → 0.105472 ms | 0.106496 → 0.105472 ms |
| 4,096 面叠层叶面 | 关闭 | 0.544768 → 0.543744 ms | 0.545792 → 0.545792 ms |
| 4,096 面叠层叶面 | 开启 | **0.203776 → 0.156672 ms，减少 23.12%** | **0.203776 → 0.156672 ms，减少 23.12%** |

混合场景的 0～1% 差异接近本测试的时间查询粒度，不视为有意义的帧率收益。叠层场景刻意放大树叶的深度复杂度，展示 Fast 在多层叶片遮挡时的收益；深度测试关闭时基本持平，支持收益主要来自遮挡的判断。它不是生成树、真实世界回放或一整帧，不能推导游戏 FPS 提升 23.12%，也不能保证草花主导的场景获得明显改善。

原始计时见两个仓库 `build-graphics-quality-20261011/gpu-final.log`，汇总见同名 JSON。实际客户端固定视角 A / B 帧率和预测 GPU 帧时间尚未采样。本轮无需也没有修改用户正在使用的图像品质设置。

**产物与复现**

两个平台交付 JAR 分别为 `vss-0.3.5-neoforge-1.21.1.jar`、`vss-0.3.5-forge-1.20.1.jar`，各自的交付目录附 `SHA256SUMS.txt` 和 `BUILD-INFO.json`。模组元数据保持 0.3.5 的平台版本，Forge 产物已完成混淆映射。生产包包含新设置读取、树叶材质识别、shader 和上一轮扫描器，不包含测试类。

PowerShell 复现命令，平台使用相应 JDK 和安装版 Voxy JAR 路径：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21' # Forge 使用 jdk-17
./gradlew.bat --no-daemon --project-cache-dir .gradle-graphics-quality-20261011 `
  -I tools/prediction/feature-handoff-tests.gradle `
  -I tools/prediction/voxy-scan-tests.gradle `
  -I tools/prediction/graphics-quality-tests.gradle voxyScanTest jar

./gradlew.bat --no-daemon --project-cache-dir .gradle-graphics-quality-20261011 `
  -I tools/prediction/feature-handoff-tests.gradle `
  -I tools/prediction/voxy-scan-tests.gradle `
  -I tools/prediction/graphics-quality-tests.gradle `
  '-PvssVoxyJar=实际安装的 Voxy JAR 完整路径' voxyScanGpuTest
```

GPU 套件的旧 shader 对照默认读取上一轮 `lib/vegetation-render-20261011` 产物；也可用 `-PvssVegetationReferenceJar=...` 显式指定。Voxy / Sodium / Embeddium 的字节码核对记录保存在 `C:/Users/Administrator/.codex/tmp/vss-graphics-quality-20261011`，没有将这些外部模组打进 VSS JAR。
