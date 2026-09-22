# Meridian 0.1.5 与当前 VSS 0.3.3 性能调查

> 阶段记录：正文中的实验范围、未提交状态和测试数字对应当时的工作节点。0.3.2 之后截至本次提交的最终功能与升级说明见 [更新日志](../CHANGELOG.md)。

日期：2026-09-22。结论：当前 VSS 最终展示采样仍明显慢于 Meridian，但粗预览、重复查询、缓存恢复、渲染必须分开比较。0.1.5 最值得借鉴的是成品网格缓存、共享 GPU 缓冲区与跨地块间接绘制，以及 GPU 遮挡命令生成。不能将采样耗时比解释成 FPS 或整张地图加载速度比。

本次只新增离线工具与调查记录；未修改正式实现、重新打包、安装模组、提交或推送。保留此前未提交的渲染优化。

## 测量范围与身份

- CPU：AMD Ryzen 7 7700，8 核 16 线程。显卡 RTX 4070 Ti SUPER；本次没有运行 GPU 对比。
- JDK 21，单调用线程，原版世界生成文档，种子 0 与 917。
- 每个场景五轮，交替版本顺序，独立 JVM/原生世界。先做 32 个不重叠位置的预热；冷采样计时位置此前没有查询。
- 计时仅包含 JNI 调用。初始化另存，不计 Java 输入准备/结果拷贝、完整植被放置、网格、磁盘和渲染。两库输出政策不同，不能视为相同结果的算法加速比。
- VSS 按现有运行路径拆成最多 8×8 点的批次；Meridian 使用 sampleGrid。66×66 是完整地块采样网格；batch8 是一组小网格，不能代替所有距离下的完整地块性能。
- 主对比 220 个计时运行，额外批次局部性对比 60 个运行；各版本各场景五轮原始输出校验和稳定。
- 调查期间未发现运行中的 Minecraft Java 进程，因此没有同场景游戏 FPS、整合包完整加载或实际内存的 A/B 数据。
- 源码证据来自用户给定 JAR 的 Java/GLSL 反编译。原生 DLL 内部算法未取得对应源代码，不能从速度推断它使用 FP32 或某种特定噪声近似。

| 文件 | SHA-256 |
| --- | --- |
| lib/vss-0.3.3-neoforge-1.21.1.jar | a94f24cc3451cf06fc5a76a5eb777c1d4891c76f749d1ccc7a9dc337570c37b6 |
| meridian-1.21.1-NeoForge-0.1.5.jar | 9ecfa0662609f3ea8614ba929653b10bf640ec820b552257e34b843bed42c25c |
| VSS DLL，ABI 6 | 94092db48678999f623e4002abc8942848bb1e41e0a59c9a199d8887ec1105fe |
| Meridian 0.1.5 DLL，ABI 15 | a31d7fa0314fa55c11b275a6a88237f365ed77f229efede1fed4f0590a84a785 |
| Meridian 0.1.4 DLL，ABI 12 | 76ff922e134b4901a348bea530f16f5e703cad7bc873d6d85968eaee2192f52d |
| 输入 JSON | e58b8ee69d9038b4ccc6afd56f06f01db28c63ea3da30db429fd6694b7e58ebb |

## 冷采样实测

下表为五轮中位数，单位微秒/点。比值是 VSS displayPoints 耗时除以 Meridian 0.1.5 sampleGrid 耗时。

| 场景 | 点数 | 种子 | VSS 最终展示 | Meridian 0.1.5 | 耗时比 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 66×66，间隔 1 方块 | 4356 | 0 | 22.681 | 2.601 | 8.72× |
| 66×66，间隔 1 方块 | 4356 | 917 | 9.861 | 2.827 | 3.49× |
| 16 组 8×8，间隔 1 | 1024 | 0 | 13.365 | 4.093 | 3.27× |
| 16 组 8×8，间隔 1 | 1024 | 917 | 32.892 | 3.420 | 9.62× |
| 16 组 8×8，间隔 4 | 1024 | 0 | 83.449 | 16.672 | 5.01× |
| 16 组 8×8，间隔 4 | 1024 | 917 | 97.353 | 15.552 | 6.26× |
| 4 组 8×8，间隔 64 | 256 | 0 | 157.349 | 55.007 | 2.86× |
| 4 组 8×8，间隔 64 | 256 | 917 | 102.144 | 51.810 | 1.97× |

完整 66×66 密集网格：VSS 为 98.80 / 42.96 ms，Meridian 为 11.33 / 12.31 ms（分别对应种子 0 / 917）。这不是一帧耗时，也不包括建网格等后续工作。

VSS 粗预览 previewPoints：间隔 4 时为 36.010 / 34.669 微秒，比 Meridian 慢 2.16 / 2.23 倍；间隔 64 时为 36.765 / 36.140 微秒，比 Meridian 的 55.007 / 51.810 微秒少用约 33.2% / 30.2% 时间。粗预览并不等价于最终细节。

Meridian 0.1.5 相比同场测试的 0.1.4，八个冷场景的中位耗时降低约 4.4%–20.0%，且所测场景的全部原始记录校验和相同。采样更新确有收益，但并非冷采样本身又快了数倍。

VSS 同点原生热查询为 0.061–0.065 微秒/点，Meridian 为 2.219–2.288。此路径绕过 Meridian Java SampleStore/LodSampleCache，故不能宣传成 VSS 游戏缓存比它快几十倍。

## 输出差异与慢路径

以下以 VSS surfacePoints 为比较基准，不是以实际 Minecraft 生成区块为真值；材料编号不可跨 DLL 直接比较。

| 场景 | Meridian 高度不同点 | 平均绝对高度差 | 最大高度差 |
| --- | ---: | ---: | ---: |
| 间隔 4，种子 0 | 290/1024 | 0.585 方块 | 34 方块 |
| 间隔 4，种子 917 | 254/1024 | 0.610 方块 | 21 方块 |
| 间隔 64，种子 0 | 230/256 | 6.723 方块 | 52 方块 |
| 间隔 64，种子 917 | 222/256 | 5.176 方块 | 43 方块 |

因此远处查询不能宣称“相同精度下 Meridian 快 2–6 倍”。VSS display 本身也存在视觉近似：例如密集网格种子 917 有 154/4356 点高度不同、最大 3 方块；其液体有无也与完整路径存在差异。双方不能简单标成“精确”和“不精确”。

水体另按存在性、种类、共同有水位置的水面高度拆分：此次所有共同有液体的位置，其种类与水面高度相同，但液体有无不同。干列 waterY 的约定差异不算水面误差。详见 output-differences.json。

VSS 诊断显示密集种子 0 的 displayFallback 为 4357，种子 917 为 0，后者还有 18 次 adaptiveGrids。计数含 32 点预热及内部探测，不能除以输入点数当成准确回退率，但足以显示两个场景走了明显不同的路径。源码也确认：不支持的生物群系/地表规则、结构调整及特定密度图顺序要求会回退到更完整的计算。应细分回退原因，复用回退前已得出的密度/生物群系结果，而非直接放行全部近似。

额外五轮测试将同一 66×66 请求改成按区块分组、每批仍不超过 64 点：两个种子下 VSS display 分别从 93.138→94.005 ms、41.433→42.762 ms，慢约 0.9% / 3.2%；exact 仅减少约 3.1% / 1.0%。原始输出校验和完全相同。简单重排批次不是高收益方向；整网格上下文共享仍需单独实现和验证，不能把 JNI 次数减少直接当成计算量同比减少。

## 值得借鉴的实现

1. **成品网格磁盘缓存，优先改善重进世界。** Meridian 的 LodTileBuildPipeline 在任何采样前尝试 restore；成功就直接 offer 已解码网格，跳过采样、植被组合和网格重建。LodMeshCache 的键覆盖世界、维度、生成器、资源指纹、树木设置、Sodium、混色半径，另检查捕获地形签名及未完成结构。它仍用 zlib/Deflater(1)，收益不依赖换成特殊压缩格式。

   VSS 已有带固定索引的 32×32 .vpr 区域存储、批量缓存探测、精细采样/颜色/植被结果保存。命中后仍进入颜色处理、简单植被与网格重建。因此下一步应缓存不依赖当前视角的成品网格。Voxy 当前驻留范围、真实区块遮罩和动态交接必须在恢复后重新应用，不能把上次玩家位置对应的裁切结果永久保存，否则会重现移动后的空洞。网格格式、资源包、色彩、采样策略及世界修改需进入失效条件。

2. **共享 GPU 缓冲区 + 跨地块间接绘制，优先改善稳定渲染 CPU 开销。** Meridian 的 GlTerrainArena 使用 32 MiB geometry 页，每页最多 4096 个地块槽；GlTerrainBatch 通过 glMultiDrawElementsIndirect 按页提交多地块，并缓存候选集合/地块参数。VSS 当前已合并同一地块多个朝向的 draw ranges，但每个地块仍绑定纹理缓冲、设置参数并提交一次。跨地块尚未实现。

   这个方向与此前“一个四边形一个 instance”的实验不同。应保留当前索引/四边形语义，把地块参数放入缓冲区，再合并命令；逐级测 CPU 提交和 GPU 时间、逐像素/深度一致性。透明顺序、接缝、Voxy 遮罩与 Iris 状态恢复都是约束。优先提供支持能力检测和现有路径回退。

3. **GPU 上完成遮挡与间接命令生成，作为第二阶段。** Meridian 普通路径先绘制上一轮可见地块，再以包围盒测试当前深度，随后在 GPU 补画本帧新显露地块，避免将遮挡判定同步读回 CPU。VSS 目前主要做视锥/朝向裁剪与片元级深度交接；被遮住的山后地块仍可能走顶点和提交流程。此优化对山地有潜力，平坦开阔场景可能得不偿失，需实际场景验证。

   Meridian 的 DH 光影接口路径并未使用上述整套批量绘制，而是逐地块 drawPackLod + 异步遮挡查询。不能将普通路径优势外推为所有光影的 FPS 优势。

4. **持久映射上传环，降低加载阶段上传抖动。** GlUploadRing 使用 16 MiB staging ring、GPU fence 与零超时检查，空间不足退回 BufferSubData。VSS 上一轮已加入带 fence 的缓冲复用池，本次不是“发现咱们完全没做复用”；共享 arena 和持久映射是再下一层，收益需同当前池方案对照。16 MiB ring 与 32 MiB 页也有固定分配和碎片成本，不能照搬后就宣称显存一定降低。

5. **植被模板复用属于质量策略，不是无损加速。** Meridian 每树种最多 48 个模板，间隔 8 用简单树，间隔 <=4 用模板，远处进一步简化。VSS 已有简单植被、森林着色、生成缓存与原生地表/装饰能力。将精细层也换为固定模板会改变真实种子下的位置/形状，不能当作无损方案；可选的远景外观层才适合进一步借鉴。

Meridian 还有根据帧时间减线程/上传预算、内存压力提高 LOD 误差阈值的自动调节。它有实际降载作用，但会影响加载速度或细节分配，不满足用户希望优先减少实际计算量的方向，因此不列为本轮首选。

建议顺序：成品网格缓存 → 共享 arena 与跨地块批量提交 → 按实际瓶颈加入 GPU 遮挡/上传环；冷生成单独继续定位回退计算。上述前两项可以以不改变几何和采样精度为目标，但必须用失效、像素/深度与性能回归验证，尚不能给出未经实测的 FPS 百分比。

## 复现与证据

主测量：`build/meridian-015-current-20260922-rerun/`，包含 manifest.json、timings.csv、summary.json、output-differences.json、每次 JVM 的 stdout/stderr、逐点 TSV 与独立 Java 源码。

局部性诊断：`build/meridian-015-locality-20260922/`。最初 `build/meridian-015-current-20260922/` 因对旧版本误用了新 ABI 断言而提前终止，不参与结果；修正 0.1.4 ABI=12 / 0.1.5 ABI=15 后整组重跑。

```powershell
python tools/prediction/benchmark-meridian-015.py --vss-jar lib/vss-0.3.3-neoforge-1.21.1.jar --meridian-jar C:/Users/Administrator/Desktop/meridian/meridian-1.21.1-NeoForge-0.1.5.jar --previous-meridian-jar C:/Users/Administrator/Desktop/meridian/meridian-1.21.1-NeoForge-0.1.4.jar --preserved-run build/fluid-final-shipping --output build/meridian-015-new-run --rounds 5
python tools/prediction/report-meridian-015-differences.py build/meridian-015-new-run
```

Meridian 源码目录：`C:/Users/Administrator/Desktop/meridian/audit-0.1.5/com/leclowndu93150/meridian/`。关键文件：

- `client/lod/tile/LodTileBuildPipeline.java:87`：先恢复成品网格。
- `client/lod/cache/LodMeshCache.java:47`：网格缓存身份与资源指纹。
- `client/lod/cache/LodMeshCodec.java:56`：Deflater(1)。
- `client/graphics/opengl/terrain/GlTerrainBatch.java:303`：两阶段可见性与 GPU 命令；`:378`：多地块间接绘制。
- `client/graphics/opengl/terrain/GlTerrainArena.java:35`：共享页；`GlUploadRing.java:26`：持久映射上传环。
- `client/graphics/opengl/terrain/OpenGlTerrainRenderer.java:340`：DH 光影专用逐地块路径。
- `client/lod/feature/FeatureStampCache.java:44`：每树种 48 个模板。

VSS 对照：`PredictionTileManager.java:1652` 缓存恢复；`:2054` 8×8 原生分批；`PredictionRenderer.java:854` 逐地块绑定/提交；`:923` 单地块多朝向合并；`tools/rust/vss-native-core/src/backend/display.rs:81` 展示路径与回退。
