# vss voxy dh merge review 20261012

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**结论：VSS 远景量大的主要缺口在粗层表示、父子选择和变体驻留分配。扩大同属性矩形的合并范围，只能解决其中一小部分。**

日期：2026-10-12。本轮阅读 Voxy 263 与 Distant Horizons 的源码，并用历史真实网格重放当前 VSS 算法及两个临时合并原型。没有修改生产源码、替换实例文件、构建新 JAR 或提交 GitHub。

**源码版本与比较范围**

| 项目 | 固定版本 | 本轮查看的路径 |
| --- | --- | --- |
| Voxy | 263 分支，`b0207f6fcc0a55fa6b33f5c916932e8560a71845` | 体素降采样、外露面生成、二维扫描、GPU 屏幕层级遍历 |
| Distant Horizons | 主项目 `d9b60924cc6615a6d8faa9c2a8b49ea5c15c9c52`；对应 core 子模块 `285de488b10995951529a31ca1df2c92628e5397` | 2×2 列汇总、垂直段压缩、外露侧面、矩形合并、渲染树与缩放 |
| VSS | 两个本地仓库的当前未提交源码，包含上一轮缩放恢复修复 | 精确扫描合并、显示变体、预算准入、普通/望远镜归属与实际绘制范围 |

上游只作为研究参照，没有将其源码加入 VSS。实验 JSON 记录实际使用的 VSS 源文件 SHA256，便于与后续改动区分。

**三者减少远景几何的方式**

| 环节 | Voxy 263 | Distant Horizons 当前 core | VSS 当前 |
| --- | --- | --- | --- |
| 生成粗层 | 每 2×2×2 体素汇总为 1 个，逐级降采样 | 每 2×2 地形列汇总为 1 列，保留代表性的垂直区间 | 规划器已有 8/16/32/64 采样轴选择；已有网格再生成三档显示变体，主要通过坐标格点化、退化面删除及合并减少几何 |
| 垂直简化 | 体素在 X/Y/Z 三轴一起变粗 | 按质量及层级限制每列的垂直段数，允许有损压缩细小夹层 | 显示变体的 Y 格点固定为 1、2、4 方块，X/Z 格点随采样 spacing 增长；还保留模型、水体和边缘相关保护 |
| 生成可见面 | 在当前粗体素层做相邻遮挡判断，只输出外露面 | 比较相邻粗列的高度、透明度与光照，输出需要显示的顶底和侧段 | 已有地表/墙面生成与精确合并，但显示变体没有完整重建一个新的粗体素/粗列外壳 |
| 最终矩形合并 | 32×32 切片扫描，每矩形最多 16×16 个当前层体素 | 同方向面排序后连接；顶底面再做另一方向；普通最大跨度为 2,048 方块 | 地表格合并、静态 feature 扫描及显示变体扫描都已有；扫描矩形上限 16×16 光栅格、32×32 稀疏页、世界跨度上限 256 方块 |
| 材质边界 | 当前层的模型/面/光照键相同才合并 | 颜色、纹理 tile、Iris 材质、天空光、方块光均须相同 | 完整材质、四角颜色/光照、平面、绕序、归属条件决定能否精确合并 |
| 细层与粗层选择 | GPU 按投影面积决定是否继续遍历子层；不需要细层时直接提交本层 | 按距离质量和缩放锥选择层级；子层未齐时保留父层，回粗后关闭并释放子渲染节点 | 普通归属继续优先最细的已有瓦片；望远镜有专用回退。屏幕误差仍可选择已有瓦片的简化显示范围，但不是一套完全可逆的普通父子层级选择 |

Voxy 的体素代表值不是简单平均，也不是当前已实现的多数投票。此版本优先选择非空气且不透明度较高的体素，同优先级通过固定角点次序决定；全部为空气时才平均光照。它的收益来自先改变当前层的体素表示，再重建网格，不能把 8 合 1 直接换算成面数或 FPS 的八倍改善。

Voxy 的 16×16 上限按当前层体素计算，层级变粗后对应的世界跨度也随之增大。它不等于始终只合并 16×16 个原始方块；这与 VSS 额外的世界跨度限制需要分别比较。

DH 也不只是把高度求平均。四列按共同的垂直切片汇总，非空气材质按出现次数选代表，光照只平均代表材质所在的列。超出垂直质量预算时，再合并或删去较小区间；最后仍要求相同颜色、纹理及光照才进行矩形连接。当前源码已有可选纹理，不能将它概括成完全无纹理的纯色高度图。

因此，远处的杂色碎面、细小高度差、树冠内部结构，首先需要在粗层数据中被合理概括。仅提高最终矩形上限，不能让原本不同平面或不同属性的面自动合到一起。

**当前 VSS 已完成的部分与实际缺口**

完整显示层级已经影响实际 `drawRanges` 和下游提交范围，不是只在着色器里缩小物体；此前的静态提交、meshlet 和 Hi-Z 优化也都在当前路径上。本轮的差异不能再归因于“完全没有 Greedy”或“LOD 没有改绘制面数”。

主要剩余限制包括：

- 显示变体只对已有面进行简化，不会完整消除粗表示内部已经不需要的层次。水平格点继续变粗时，Y 方向仍只用 1/2/4 方块格点，远景的垂直碎段及不同高度平面可能继续存在。
- 全网格超过 131,072 个不透明面时不构建显示变体；其它网格至少要节省 64 面、减面达到 35%，并满足所有变体合计不超过源网格 50% 的额外驻留预算。第一个完整变体实际上必须达到至少约 50% 减面才能单独放进预算。
- 变体按 tier 1 → 2 → 3 依次占用空间。较细表示先获准后，较粗表示可能没有空间，只能沿用较细表示。这使单个合并步骤更有效时，最终最粗档反而可能增加面数。
- 精确 feature 与可简化地形一起进入完整变体。不变的部分会随各档重复存储，还参与整网格准入比例。地形、植被和建筑目前虽已有各自选择逻辑，存储上仍有进一步拆分的空间。
- 普通覆盖归属在已有瓦片中保持最细优先。这是为了避免粗细独立采样带来的地形/建筑/水面不一致；不能直接让旧粗父级抢回覆盖。若希望像 Voxy/DH 一样可逆切换，粗代理需要从同一份细数据汇总，并携带对应的版本、覆盖和边界信息。
- source 与追加的显示变体一起保存在网格/GPU 数据中。少画某个档位，不等于细数据已经释放。显存驻留与绘制选择仍需分别管理。

**本轮真实历史网格回放**

输入来自 `C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry`，捕获 epoch `1791630438011`。只重放 2,019 个唯一普通不透明瓦片，排除接缝。剥离捕获时的显示变体，再使用本轮当前生产源码重新生成。原始普通不透明面为 22,405,691 个，当前精确合并后为 21,344,411 个。

材质适配使用捕获的 leafClass 与原版雪、草/水草 sprite 名称，没有完整注册表/tag 快照。语料缺少有效相机；以下是全部网格或统一显示档的工作量，不能当成当前帧提交量，更不能推算实机 FPS。spacing 分组指采样间距，不等于相机距离。

| 对照路径 | 精确合并后的源面数 | 全部统一 tier 1 | 全部统一 tier 2 | 全部统一 tier 3 |
| --- | ---: | ---: | ---: | ---: |
| 当前生产扫描 | 21,344,411 | 14,683,754 | 10,899,446 | 10,232,042 |
| 只将扫描矩形上限 16 → 32 格 | 21,343,259 | 14,660,687 | 10,901,832 | 10,238,269 |
| 当前扫描后追加排序连接，跨页、最高 2,048 方块 | 20,945,686 | 14,244,860 | 10,986,911 | 10,300,299 |

第一种候选只额外减少 1,152 个源面，0.0054%。第二种候选额外减少 398,725 个源面，1.868%，但其最粗统一档比当前增加 68,257 面，0.667%。排序连接原型保留 VSS 的完整属性与归属判断，只研究 DH 的相邻矩形连接思路；并没有运行 DH 的降采样、列压缩和渲染器。它不代表完整 DH 算法的收益。

回放发现一个清楚的预算反例，mesh 2241：

| 量 | 当前生产 | 追加排序连接 |
| --- | ---: | ---: |
| 源面数 | 24,282 | 24,282 |
| tier 1 实际面数 | 24,282，候选 12,159 超出 12,141 预算，被拒绝 | 11,663，获准并先占用空间 |
| tier 2 实际面数 | 5,413 | 11,663，复用 tier 1 |
| tier 3 实际面数 | 3,436 | 11,663，复用 tier 1 |

这个例子证明，应评估最终选择到的绘制范围，而不能只看某一步合并后少了多少面。两个候选均未进入生产。

**没有显示变体的瓦片占了多少**

当前生产代码重放后，1,262 个瓦片有显示变体，757 个没有，约占瓦片数 37.49%。没有变体的瓦片包含 6,109,899 个当前源面，约占源面 28.63%；即使把所有网格都强行统计在 tier 3，它们仍占该档面数约 59.71%。这个比例是统一档的离线统计，不是对实际远景画面的测量。

| 当前准入情况 | 瓦片数 | 当前源面数 | 说明 |
| --- | ---: | ---: | --- |
| 有变体 | 1,262 | 15,234,512 | 某档获准；后续是否绘制仍受屏幕误差、morph、混合覆盖等约束 |
| 没有候选获准 | 756 | 5,945,150 | 几何减面不足，或额外驻留预算不允许 |
| 超过大网格门槛 | 1 | 164,749 | 不是这份语料中的主要原因 |

756 个候选未获准的瓦片中，113 个只因额外驻留预算而没有变体；643 个即使取各自最少面的候选，仍不能满足节省至少 64 面和至少 35% 的条件。整组最佳候选合计为 5,084,896 面，对应源面 5,945,150，潜在减幅也只有约 14.47%。因此，放开准入阈值不会自然得到 Voxy/DH 那样的几何缩减。

这组未获准瓦片在精确合并前包含基础地形 5,515,951 面、feature 450,353 面，基础地形约占 92.45%。本次证据指向地形主体，不支持把剩余成本全归于树叶或草。全语料中，约 516.6 万面四角颜色/天空光不完全相同，这也是精确同属性合并的限制之一，但这些分类相互有重叠，不能相加推算可省面数。

**适合 VSS 的调整顺序**

1. **先调整显示变体的存储及准入。** 让不变的 feature/建筑记录被各档共享，避免完整副本反复占用预算；根据最终粗档的可用性选择保留哪些表示，防止浅档占满空间后粗档无法驻留。保持显存预算，不能通过无限增加变体容量来解决当前驻留压力。需要用距离带的实际选档、候选面和 GPU 时间验证取舍。
2. **地形优先参考 DH 的粗列汇总。** VSS 已有不可变的 `PredictionColumnVolume` 垂直区间，可以研究从同源细列构建 2×2 粗列，按屏幕误差限制垂直段，重建顶面和可见侧段。保护水位、跨瓦片边界、建筑/桥梁等内容；同版本生成粗层和接缝。这样才会真正减少粗层结构及内部侧面。
3. **树冠参考 Voxy 的粗体素外壳，草类保留单独的屏幕选择。** 树叶方块可汇总为粗占据体，再只生成粗外壳；小草/水草已有范围选择，继续核对实际 MDI 范围。未知模型、花卉、自定义 UV、建筑和透明水面保留各自的处理，不以扩大平面矩形代替植被代理。
4. **同源粗代理稳定后，再完善普通父子可逆选择。** 放大选细、缩小选粗；新一组覆盖完全可用时交接，缺失时保留当前有效覆盖。距离/FOV 选择不应要求重做昂贵地形生成；取消过时细化请求，冷细数据按 GPU 预算和延迟回收，CPU 压缩数据可继续缓存。

验收应同时记录各距离带的 source、terrain、display、selected 面数，以及无变体、混合覆盖、morph 和各变体预算拒绝计数；区分源驻留、GPU 驻留和实际提交。固定位置/朝向/FOV 与缩放往返测试后，再比较不透明 GPU 时间、提交 CPU 时间、转身/跑图长帧和显存，正收益才保留。

**源码证据**

- [Voxy 体素代表值选择](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/java/me/cortex/voxy/common/world/other/Mipper.java#L19)；[逐级体素降采样](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/java/me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper.java#L26)。
- [Voxy 扫描上限 16](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/java/me/cortex/voxy/client/core/util/ScanMesher2D.java#L7)；[投影层级阈值](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/resources/assets/voxy/shaders/lod/hierarchical/screenspace.glsl#L195)；[父子遍历和缺失回退](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp#L134)。
- [DH 2×2 列汇总](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2.java#L722)；[材质代表值和光照](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2.java#L881)。
- [DH 垂直质量对应的段数](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/api/src/main/java/com/seibel/distanthorizons/api/enums/config/EDhApiVerticalQuality.java)；[垂直段有损压缩规则](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/util/RenderDataPointReducingList.java#L268)。
- [DH 排序矩形合并](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder.java#L258)；[完整颜色/纹理/光照条件](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/BufferQuad.java#L318)。
- [DH 子层未齐时父层覆盖](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java#L653)；[回粗后删除子渲染节点](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/285de488b10995951529a31ca1df2c92628e5397/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java#L430)。
- VSS：[显示简化、门槛与分配](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java:100)；[普通覆盖保持最细](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:1863)；[实际选择绘制范围](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:244)。

复现方法见 [实验说明](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/tools/prediction/merge-review-experiments/README.md)。同名 JSON 保存逐网格结果、候选定义和源文件身份。实验已完整处理 2,019 个网格并通过材质/光照/平面有向面积与水面尾部一致性校验；这不替代生产 GPU 像素回归和同场景帧时间测试。
