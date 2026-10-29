# Meridian 0.1.8 采样、LOD 边界与接缝审查

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮回应“Meridian 0.18 如何处理采样与接缝”的源码调查。用户目录中的实际文件是 `C:/Users/Administrator/Desktop/meridian/meridian-1.21.1-NeoForge-0.1.8.jar`，包内 `neoforge.mods.toml` 标明版本 `0.1.8`，大小 9,373,680 字节，SHA256 为 `448986284ad1499dfa92de00fa68dc329e7952eb6ab6d79c0cb73b158b392f45`。

使用本地 CFR 0.152 生成独立审查目录 `C:/Users/Administrator/Desktop/meridian/audit-0.1.8`，包含 513 个 Java 文件，对应 JAR 内 585 个 class。没有把目录中 0.1.1、0.1.2、0.1.4 或 0.1.5 的反编译结果作为 0.1.8 的证据。

## 结论

Meridian 的主要做法是：规则网格带外围样本，后台生成普通侧墙及可形变连接面，保存父层高度偏移，绘制时按距离形变，父子表示按完整覆盖交接。审查到的 Java 生产路径没有 VSS `PredictionLodSeams` 那种根据当前选中邻居维护独立接缝补丁、边界掩码和配套发布事务的系统。

这不是零接缝成本。其 `LodWallMesher.seams()` 确实生成连接面，只是计算发生在地块网格构建阶段，并随该地块一起缓存和上传。其采样也包含代表列选择、去噪和误差容许；不能把较简洁的交接路径解释为世界生成绝对精确。

## 1. 规则网格与外圈保护

- `client/lod/LodLayout.java:9`：每块固定 64×64 个格子，四周各保留一个采样位置，因此每轴 66 个样本；层级间距为 `1 << level`，地块边长为 `64 << level`。
- `client/lod/tile/LodTileBuildPipeline.java:76`：采样起点是地块原点减去一个间距，外围样本与正式网格一起进入后台构建。
- `client/lod/mesh/LodMeshData.java:210`：正式格子 `(i,j)` 对应 `(j+1)*66+i+1`，保持外围样本可供墙面和光照查询。
- `LodMeshData.java:233`：间距不大于 4 时执行孤立高度去噪。循环只修改正式格子下标 1 到 62，正式外圈 0 和 63 不参与此操作，避免该去噪在邻接边界制造不同结果。已用 `javap -p -c` 复核循环边界。

VSS 当前 `PredictionMeshBuilder.java:265` 先将 66×66 裁成 65×65，`stabilizeGeneratedSurface()` 在这个网格上修改 1 到 63。于是东、南最后一个正式格子仍可能被修改，而邻块持有的对应外围采样不经过同样处理。这是需要用边界一致性用例验证的具体风险，尚未证明它解释了用户截图或占据多少接缝时间。

## 2. 接缝在地块构建阶段完成

- `client/lod/mesh/LodMeshBuilder.java:45`：后台构建依次执行地表稳定、结构处理、父层偏移、植被、光照、列网格和打包。
- `client/lod/mesh/LodSurfaceMesher.java:31`：遍历 64×64 正式格子，生成顶面与水面，并查询网格中相邻列生成东、南侧墙；查询来自本任务捕获的规则采样网格。
- `client/lod/mesh/LodWallMesher.java:110`：普通墙面从两列的暴露区间生成，按连续墙段合并。
- `LodWallMesher.java:129`、`:152`：原始高度相同而父层形变偏移不同时，仍预制连接面。顶端和底端分别记录使用本列、邻列还是固定高度，运行时随对应偏移展开。

这些连接面与普通地形一起输出。没有在该路径中查询当前 renderer 的选中邻居并重建独立 patch。普通墙面、悬空层、地板及水位分段仍有计算和几何成本；不能把它称为完全取消接缝。

## 3. 父层偏移与距离形变

- `client/lod/tile/LodTileSamples.java:163`：从已缓存／已存储的父层采样及真实捕获覆盖中获取父高度，并映射到子网格。缺少父数据时可不提供偏移。
- `client/lod/mesh/LodMeshData.java:277`：每列偏移为父高度减子高度，限制在 -127 到 127；已捕获、结构、空列和无有效父高度的列使用零偏移。
- `client/lod/mesh/LodMeshOutput.java:105`：偏移打包成每列一个有符号字节，66×66 对应 4,356 字节，不含缓冲对齐。
- `client/graphics/opengl/terrain/OpenGlTerrainRenderer.java:451` 及 `GlTerrainBatch.java:174`：设置形变起止距离，起点为终点的 0.8 倍，焦点等条件可禁用。
- 展开的顶点着色器 `evidence/meridian-terrain.vert:167`：非液体、允许形变的面按当前顶点距离计算权重，并读取预存偏移。普通地形的位置随距离向父层表示靠拢。

VSS 已有 `PredictionMorph`，其主要机制为上传后约 350 ms 的动画，边缘偏移减弱、限幅并保护河岸／植被；另有同源父层与 GPU LOD 选择。因此 VSS 不是缺少 morph，而是当前这一 morph 的控制目标与 Meridian 的距离过渡不同。不能直接把一种动画参数替换成另一种并宣称交接已经安全。

Meridian 的生成父层仍可以独立调用 sampler，不能称为全部同源父子 mip。其真实捕获数据另有 `CapturePyramid`，把真实子数据逐级合并到父层；两条数据来源应区分。

## 4. 按完整覆盖交接，减少局部组合

- `client/lod/tile/LodTileManager.java:318`：有仍有效、已上传祖先时，不绘制对应子地块。
- `client/lod/tile/LodTileSelection.java:53`：检查四个子分支可用覆盖，再允许旧父地块退出；边界外区域视为无需覆盖。
- `LodTileManager.java:274`：需求变化和上传后重新判断旧地块是否可退休。

这让子层逐步生成时继续由父层完整覆盖，减少“一个父块内部挖出任意形状、再逐条连接”所需的组合状态。代价是单个子块先完成后也可能等待其他分支，细节显示会更迟。

VSS 还承担与 Voxy 的局部、三维归属交接，以及部分父子覆盖、回退、水面和体积地形。该能力不能直接用 Meridian 的整块策略替换。适合优先试验的是普通预测内部的完整父子族交接，并保持真实 Voxy 交接单独处理。

## 5. 真实区块以覆盖掩码和捕获数据交接

- `client/lod/tile/LodVisibilityMask.java:50`：构建 16×16×16 区段覆盖状态，检查真实区段已编译及可见情况，并用相机位移／50 ms 条件减少重复轮询。
- `client/lod/tile/LodHandoff.java:17`：覆盖出现时使用约 180 ms 的平滑增长。
- 展开的片段着色器 `evidence/meridian-terrain.frag:87`：使用三维覆盖纹理和屏幕抖动阈值丢弃预测片元；水面侧边另查两侧覆盖。
- `client/lod/mesh/CaptureOverlay.java:33`、`LodMeshData.java:135`：已经捕获的真实列进入网格构建，用真实高度、材质和流体替换预测数据。
- `client/capture/CaptureStore.java:320` 和 `CapturePyramid.java:19`：真实捕获逐级向上合并，有效列可局部进入父缓存。

本轮在 0.1.8 的 Java 渲染路径中未发现 Voxy 专用归属接口，也没有 VSS `PredictionRealBoundarySeams` 那样逐条真实地面边缘生成连接 patch 的独立系统。覆盖、深度及捕获覆盖承担该部分工作。

## 6. 更新合并和采样精度的实际取舍

- `client/lod/tile/LodTileState.java:15`：真实区块变更只标记覆盖相关地块，重复通知用 `putIfAbsent` 合并；普通脏块重建使用 2 秒等待门槛。构建期间版本变动会再次排队。
- `client/lod/tile/LodTileBuildPipeline.java:154`：完成时检查 revision 与染色范围，变化时重试。该路径是提交成果再标脏重试，不能等同于 VSS 的精确版本匹配原子交接。
- `api/terrain/TerrainGrid.java:31`、`terrain/AbstractTerrainSampler.java:15`：Java 基类间距大于等于 2 时，使用 4 个探针；间距大于等于 16 时使用 5 个探针，取高度中位数及外观支持更高的代表列。它不是每个粗格只取一点，也不是完整区域的逐块精确模型。
- `terrain/graph/NativeTerrainSampler.java:120`：主原生后端整体调用 JNI `sampleGrid`。JAR 没有 Rust 源码，本轮没有重建原生数值算法，不能把 Java 基类的每项内部规则无条件归给 native。
- `terrain/graph/NativeParityProbe.java:12`：对 36 个固定位置检查 native 地表高度与原版 `OCEAN_FLOOR_WG`；允许平均差不大于 4、最大单点差不大于 96。这是有限位置的后端准入检查，既不证明全世界误差上限，也不表示实际普遍存在 96 格偏差。

## 对 VSS 的建议

1. 先验证并统一同一世界边段的采样、外围样本和后处理；优先排查裁剪后去噪影响东／南正式边格的风险。它比增加另一层修补更接近根因。
2. 对不变的规则列网格，在后台预制能表达父层偏移的过渡面，与地块一起缓存；以边界来源／内容版本决定是否更新。平面、体积、液体和特殊结构分别验证适用范围。
3. 对预测内部的完整父子族试验整族交接、距离过渡，统计等待细节的延迟和实际减少的接缝任务。保留缺失数据和 GPU 容量失败的完整回退。
4. Voxy／真实地形的局部交接继续保留，但改成完整边段依赖与局部失效。不能以整块延迟或统一两秒等待推迟真实归属撤销。
5. 使用相同几何更新序列比较采样、网格生成、接缝、临时分配、上传和发布的总成本，同时记录渲染帧 P95/P99。没有全链路收益的预制或近似应退出。

## 验证范围

本轮只进行文件审查、反编译、静态调用链核对与若干关键方法的字节码复核。没有运行 Meridian 客户端，没有执行两模组同存档 FPS A/B，没有测量原生采样吞吐或接缝 CPU/GPU 时间，没有修改 VSS 生产源码、构建新 JAR 或替换实例文件。

`audit-0.1.8/evidence/manifest.json` 保存了 JAR 版本、哈希、文件计数及证据哈希；`evidence` 下保存了展开的普通顶点／片段着色器，以及 `LodMeshData`、`LodTileManager` 和 `NativeParityProbe` 的字节码输出。展开着色器解析了 Java 字符串引用及 `Integer.numberOfTrailingZeros(4)` 常量。
