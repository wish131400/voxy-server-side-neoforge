# VSS、Voxy 263 与 Sodium 渲染对比：新 Spark 与同窗口日志

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**当前最明确的差距是：加载和视角变化时，VSS 仍频繁维护场景、归属、接缝与提交计划；共享不透明几何池达到上限后，大量网格进入逐块绘制路径。几何方面，普通预测优先保留已生成的最细表示，尚未像 Voxy 一样在层级遍历中按屏幕大小选择粗细表示。**

Sodium 最值得借鉴的是按区域保存批次、局部失效和透明索引独立更新；Voxy 最值得借鉴的是多级体素重新生成外露面、GPU 层级选择和紧凑面记录。这些设计分别解决 CPU 重复工作、可见几何规模和带宽成本。

VSS 与 Voxy 的现有四边形计数处于不同阶段。当前数据能够确认候选几何与提交维护成本较大，不能直接计算“最终实际绘制比 Voxy 多几倍”，也不能据此断言父子 LOD 在画面中重复显示。

## 1. 调查范围与版本

调查日期：2026-10-11。主样本：[CuvzdaFFdW](https://spark.lucko.me/CuvzdaFFdW)。参考介绍：[MC 百科 Sodium 页面](https://www.mcmod.cn/class/2785.html)。实现判断以对应版本源码为准。

| 对象 | 本轮核对的版本或提交 |
| --- | --- |
| 客户端 | Minecraft 1.21.1、NeoForge 21.1.249、Java 25.0.1 |
| VSS | 0.3.5-neoforge-1.21.1，实例 JAR 与 resource-optimization-20261011 产物完全一致 |
| Voxy 客户端 | 0.2.15-beta |
| Voxy 比较源码 | 263 分支，b0207f6fcc0a55fa6b33f5c916932e8560a71845 |
| Sodium | 0.8.12-beta.2+mc1.21.1；按同名上游标签读取，提交 ec15afd7c85f850552577739eb30858faea1e592 |
| Iris | 1.8.14-beta.1+mc1.21.1，日志确认光影关闭 |
| 硬件 | Ryzen 7 7700、RTX 4070 Ti SUPER |

VSS 安装包 SHA256 为 `a5d6e26f38dd460d53910cb0de4584674f38aa2e7d953a1fb510c332e77c64e9`；采样文件 SHA256 为 `65a6a8502126ea35c45208c72c8990ccf141474007f07d3e32a94a094e54592a`。Voxy 安装包 SHA256 为 `802d49edc07c4e4a7af15ffd59337a82af383dfd551b67c278233098737e2f52`。

安装版 Voxy 的 cmdgen、hierarchical traversal、screenspace、quad_format 和 block_model 五个 shader 与 263 比较源码在统一换行后完全一致。这验证了本报告引用的 GPU 层级选择、命令计数和面格式；不等于声明整个 Java JAR 与该提交逐字节一致。

本轮未修改生产源码、构建或安装新 JAR，未推送 GitHub。两个仓库已有的未提交工作保留。

## 2. Spark 说明了哪些成本

采样时间为 **21:03:40.026–21:05:40.481，UTC+08**，墙钟时长 120.455 秒。只有 Render thread，Java 执行抽样间隔 4 ms，共 30,000 个样本、120,000 ms 权重。平铺的 9,275 个节点按 childrenRefs 重建，引用、唯一父节点、非负 self 和总 self 权重均验证通过。

这份样本不能衡量全部后台线程、总分配速率或服务器各线程负载。三个窗口的服务器 MSPT 中位数约 1.06–1.17 ms，最大值 49.24–88.86 ms，不能把个别服务器长 tick 当作持续客户端低帧的完整解释。

### 2.1 先消除回调嵌套造成的归因误差

| 抽样口径 | 占 Render thread 权重 |
| --- | ---: |
| 完整栈经过 VSS，包含 VSS Mixin | 80.903% |
| VSS Java 类自身 self | 36.830% |
| 完整栈经过 Voxy | 66.597% |
| 完整栈同时经过 Voxy 与 VSS | 64.873% |
| 经过 Voxy，但栈中没有 VSS | 1.720% |
| 完整栈经过 Sodium | 71.570% |
| 经过 Sodium，但栈中没有 VSS | 2.350% |
| Xaero 栈 | 2.717% |
| glFinish 栈 | 0.073% |

Voxy 的主要回调位于 Sodium 地形渲染入口中，VSS 的普通预测又位于 Voxy 普通管线回调中：

```text
Sodium DefaultChunkRenderer
  → Voxy VoxyRenderSystem.renderOpaque
    → NormalRenderPipeline.postOpaquePreTranslucent
      → VSS predictionBeforeWater
        → PredictionRenderer.renderBeforeVoxyWater
          → VSS 场景准备与绘制提交
```

因此这些模组的栈占比相互包含，不能相加，也不能将页面上的 Sodium 71.57% 或 Voxy 66.60% 直接算作它们自身的开销。排除 VSS 后的两项也可能相互包含。

本轮 drawIris 权重为零，实际执行 drawNormalPass。Xaero 的 glFinish 确实出现，但权重仅 88 ms；本轮证据不支持将地图同步等待列为主要热点。

### 2.2 当前 VSS 热点

| 路径 | inclusive 抽样占比 | 对应工作 |
| --- | ---: | --- |
| Scene.prepare | 26.633% | 场景变化、覆盖、接缝与可见集合准备 |
| drawNormalPass | 18.557% | 普通预测绘制准备与提交 |
| PredictionIndirectBatch.add | 11.747% | 检查和组装批次 |
| VSSClientNetworking.onClientTick | 10.710% | 预测管理与调度等 tick 工作 |
| PredictionIndirectBatch.stage | 8.230% | 提交计划检查、命令准备等 |
| PreparedPass.prepare | 6.967% | 过滤并构建绘制范围 |
| PredictionLodSeams.apply | 6.240% | 更新接缝 |
| BatchSlot.upload | 5.940% | 数据比较、必要时上传批次 |
| PredictionCoverageOwners.coveringTileAtDetail | 5.200% | 归属缓存及查询 |
| PredictionVisiblePlan.selectStable | 4.933% | 可见列表与视锥判断 |
| PredictionLodSeams.stitch | 4.607% | 接缝拼接 |
| BatchSlot.same | 3.367% | 逐字节比较，self 同为 3.367% |
| PredictionCoverageOwners.version | 2.757% | 局部版本查询，self 同为 2.757% |

这些路径存在包含关系，不得求和。百分比是抽样权重，不是每帧精确毫秒，更不能直接换算优化后的 FPS。

已有静态计划和局部归属缓存确实存在。剩余问题是它们在加载、细化、归属和驻留变化时的命中率与验证成本，而不是缺少这些功能。

## 3. 同窗口日志：重建、回退与 GPU 时间

匹配到 24 条渲染诊断，首末时间为 **21:03:42.290–21:05:37.754**，间隔 115.464 秒。累计计数使用首末差；逐通道或逐帧计数只报告快照分布。

| 累计指标 | 这段窗口的增量 |
| --- | ---: |
| 网格上传 | 6,284，约 54.4 次/秒 |
| 绘制计划重建 | 3,608 |
| 绘制计划复用 | 247 |
| 完整提交计划命中 wholePlanHits | 72 |
| 批次计划命中 / 未命中 | 5,689 / 4,464 |
| 接缝索引 builds | 4,786 |
| 接缝地表 updated | 79,678 |
| 显存预算淘汰 | 1,481 |
| 冷恢复请求 | 927 |

| 快照指标 | 最小 / 中位 / 最大 |
| --- | ---: |
| 日志当前通道 draw calls | 26 / 626 / 1,162 |
| opaquePageGroups | 0 / 175 / 225 |
| opaqueFallbacks | 5 / 450 / 940 |
| quadsCurrentPass 候选范围 | 133,586 / 9,982,621 / 38,907,586 |
| GPU 驻留 tile | 5,347 / 8,066 / 9,232 |
| 驻留存储 quad census | 133,465,364 / 152,946,425.5 / 160,490,448 |
| 已提交几何分配 | 约 2.97 / 3.39 / 3.57 GiB |

24 条诊断中，不透明 arena 都是 **32 页 × 64 MiB = 2 GiB**，已到该池上限。水体 arena 都是 **2 页 × 64 MiB = 128 MiB**。

共享 arena 上传失败会使用 PredictionQuadBufferPool；无 arenaSlice 的网格进入保守逐块绘制路径，并使完整提交计划无法直接重放。池容量、碎片、单次分配超过半页以及能力条件都可以触发该路径。本轮池容量达到上限且回退很多，但不能将每一次回退都只归因于容量。

现有 GPU 预算是软预算：近处、粗回退、望远镜和接缝有保护，淘汰还要求可用替代。日志里 1 GiB 的软预算与约 3 GiB 的实际几何驻留并不矛盾。仅提高 arena 上限不能降低几何规模；仅强制压低软预算也可能损害回退覆盖。需要同时改善表示选择和维持批量绘制的分配路径。

日志还包含最近最多 90 帧的滚动 FPS，24 个快照为 **14.4–103.0，中位 24.6**。这不是完整 120 秒的平均 FPS，也不是最低帧或 1% low；场景正在变化，不能视为固定位置基准。

GPU 计时使用 `(末累计均值×末样本数－首累计均值×首样本数) / 新样本数` 还原本窗口新增样本：

| GPU 区间 | 新样本均值 | 新样本数 | 样本单位 |
| --- | ---: | ---: | --- |
| OPAQUE | 6.162 ms | 128 | 不透明阶段采样 |
| WATER | 1.150 ms | 128 | 水体阶段采样 |
| DEPTH_COPY | 0.233 ms | 128 | 深度复制阶段采样 |
| ANTIALIAS | 0.211 ms | 128 | 抗锯齿阶段采样 |
| HZB_REDUCE | 0.122 ms | 486 | 一次 HZB build 区间 |
| GPU_FILTER | 0.043 ms | 486 | 一次 filter 区间 |
| INDIRECT_SUBMIT | 0.054 ms | 1,792 | 一次批次区间 |

由日志三位小数均值还原的舍入误差约为 ±0.003–0.004 ms。样本单位不同，阶段可能包含子阶段，CPU/GPU 又会重叠，不得把这些数字求和成为帧时间。

前 21 条诊断处于 measuring-hiz，后 3 条处于 measuring-direct。新增 HZB 与 filter 查询确认普通路径确实在执行剔除。直绘测量期间旧 hiddenQuads 样本不刷新，后期 sampleAge 增长到 403；旧计数不能解释当前画面。场景变化还可能使自适应测量重新开始，不能将一次模式选择当作所有视角的结论。

## 4. 四边形差距：先统一口径，再解释结构

| 计数 | 真正含义 | 不代表什么 |
| --- | --- | --- |
| VSS packed.quadCount / 驻留 census | 常驻存储记录，包含原始网格和附加显示变体 | 本帧全部送入 GPU 的面 |
| VSS quadsCurrentPass | 本通道 ranges.quads 的累计差，普通路径在 GPU filter 前计数 | Hi-Z 后非零命令的最终面数 |
| VSS testedQuads / hiddenQuads | 有时间延迟的 GPU 剔除样本 | 同帧全部几何，尤其不包含全部池外直绘 |
| Voxy RenderStatistics.quadCount | 当前层级选择、可见 section 判断及面方向选择后的命令面数 | 最终有像素贡献的面数 |

Voxy 的 cmdgen 计数包含透明面、双面和六方向面；其源码明确暂不计 temporal 重复提交的额外倍数。VSS 的当前通道统计不能与这个总口径直接相比。

显示变体附加总量被限制为原不透明 source 的 **50% 以内**。绘制时选择一种表示，不会把原始网格和所有变体一起绘制。因此存储膨胀与绘制膨胀也必须区分。

### 4.1 Voxy 会在遍历中选择层级；VSS 普通归属保留最细表示

Voxy 使用 32³ section 和 LOD 0–4。多级体素由 2×2×2 子数据降采样，并在该分辨率重新判断外露面。GPU 按投影大小决定是否访问 children；节点可在层级处被视锥或 Hi-Z 拒绝。子结果未就绪时保留可用父网格，避免先删旧覆盖。

VSS 普通非 scoped 地形的 coveringTileAtDetail 会走 coveringTile，按实际 spacing 选择已有的最细 tile。细网格生成以后，距离增加本身不让普通归属自动切回已有粗父级。

已有显示 LOD 可降低其中一部分提交面数，但受以下条件限制：原不透明面数 128–131,072、显示误差约 1.5 像素、附加存储预算、模型/水体/边界保护；scoped、morph 和不完整归属还会保留原表示。它不是 Voxy 的完整三维降采样与层级表示选择。

这种差异可以同时增加 CPU 候选维护、可见细节几何和驻留量。当前日志没有显示变体按类别的命中/拒绝计数，尚不能量化分别贡献多少。

### 4.2 两边都有合面，合并范围和属性约束不同

Voxy 的 RenderDataFactory 在二维 section 切片扫描，用模型、光照、biome 与渲染属性等完整键合并同平面矩形，并读取邻接 section 来判断外露面。

VSS 已有地表贪心合并、竖向连续段和 PredictionScanMesher2D；不能说它没有 greedy meshing。当前 packed exact scan 的额外扫描使用 featuresOnly，显示变体还会对更多可合并面扫描。特殊模型、morph、归属边界、颜色与光照变化限制合面。真正的差距要通过同一份几何的来源分类、合面前后数量和拒绝原因来测量。

### 4.3 特殊模型表示不同，但不能一概解释为“VSS 的草更多”

Voxy ModelFactory 把方块模型捕获为六方向的颜色/深度图和边界元数据，远景网格引用这些方向代理矩形。

VSS 的竹子读取真实 BakedQuad 顶点，部分实心植物按形状盒输出；普通草花则使用两个十字平面。这可以使复杂模型的面数与 Voxy 不同，但 Voxy 的某些植物也有多个方向的代理面。没有当前分类计数，不能认定全部植物都比 Voxy 多面，也不能把 spriteQuads 当作植被面数——地形与建筑同样可以带 sprite 材质。

### 4.4 父子在列表中都存在，不等于在画面中重叠

VSS 分别选择地表与装饰 owner。细地表先到时，父 tile 可能仍提供尚未到达的树木或建筑。fullyCoveredByChildren 不会直接移除仍提供 feature 的 parent；顶点或片元归属掩码负责裁掉失去归属的几何。

本轮 suppressedParents 全为零，partialCoverageParents 为 268–550、transitionTiles 为 290–623。这说明整 tile 排除的效果有限，部分被掩码拒绝的几何仍可能消耗候选管理或顶点工作；不能据此证明几百块父子都重复可见。需要按同帧、同归属统计被拒绝的地表/装饰面才能判断。

## 5. 三套渲染方案中值得迁移的设计

| 设计 | Sodium 对应版本 | Voxy 263 | 当前 VSS 与适合的改进 |
| --- | --- | --- | --- |
| 静态批次 | region 缓存 MultiDrawBatch，未失效直接使用 | GPU 维护 section 与命令数据 | 已有完整计划和批次缓存；把整通道的失效依赖继续拆到空间区域或实际 page group |
| 可见集合变化 | region 位图和受边界约束的相机 section 判定；不按浮点相机位置反复失效 | 层级 GPU 遍历 | 已有稳定可见列表；让局部变化仅修改受影响记录与归属版本 |
| GPU 驻留 | 8×4×8 section 一个 region；几何与索引 arena 分开 | 紧凑 section 几何驻留 | 共享页有利于 MDI；需要避免池满或大 tile 后长期进入逐 tile direct |
| 透明排序 | 排序任务写 index buffer，排序结果不重建顶点或可见图 | 独立透明命令及排序 | 保留水面正确顺序，同时使排序变化与静态几何、归属数据解耦 |
| 面方向 | 六方向加 unassigned，在命令阶段选择与合并连续范围 | 六方向加双面/透明，在 cmdgen 中选择 | 五组中 up/down 同 horizontal，shader 继续裁；可评估更早的上下方向命令筛选 |
| 遮挡 | 根据完整区块可见连通信息遍历 CPU 图，并做视锥/距离判断 | GPU 层级与 Hi-Z | 已有当前帧 Hi-Z 和 meshlet；优先减少测试前的候选与 CPU 重组 |
| 生成/交接 | 估计任务与上传时长、上传大小，再预约任务预算 | 异步 section 请求和数据交接 | 已有预约和预算；应测剩余 tick、接缝交接与上传变化造成的失效范围 |

Sodium 的 buffer arena 会按需要扩容并整理，发生地址变化时刷新对应 region 的数据偏移、批次和 tessellation。因此应迁移“局部生命周期和更新范围”，而不是声称这些 GPU 地址永久不变。

Sodium 同样需要遍历可见集合，也不是完全没有 CPU 剔除或重排。它的收益是区域数据不变时跳过批次重编码，并把不可避免变化限制在较小范围。

Sodium 的紧凑格式是 **20 字节/顶点**，一张四边形四个顶点约 80 字节，索引另计；VSS 当前规则矩形是 **20 字节/面**加共享 palette、例外坐标与掩码，通用格式仍有 32/48 字节路径；Voxy 规则面记录为 **8 字节/面**，共享模型与图集另计。不能混淆“每顶点”和“每面”，也不能将格式比例直接换算为 FPS。

Sodium 的可见连通图依赖完整区块遮挡信息。预测的表面、缺失数据和交接区不能直接套用这一假设；需要保守边界。它主要处理近景完整区块，Voxy 处理远景层级，两者适用层次不同。

## 6. 建议实施顺序与收益验证

1. **补同帧计数，统一比较口径。** 分地形、树叶/木质、草花、水体、建筑、接缝；分别记录 source、备用 display storage、实际选择表示、归属过滤后、GPU filter 后和池外 direct 的面数。GPU 结果按 frame/pass 标记异步读回，不在渲染线程等待。记录当前模式与 sampleAge，避免把旧样本减当前候选数。
2. **优先改局部批次生命周期。** 借鉴 Sodium region，让同一空间区域或 page group 的静态命令与 bounds 常驻。细化/上传只失效所属区域；归属变化只更新受影响记录。现有 replayPass 依赖整计划输入、版本、submissionEpoch、驻留 revision 和归属 revision；局部变化仍可能导致整计划重放失败，再进入 add 与 same。保留已有批次重放能力，减少它的验证与重组范围。
3. **让超出共享 arena 的几何继续具有批量提交能力。** 先统计容量、碎片、大 tile、能力等回退原因，再评估切分大记录或使用仍可批量寻址的多 arena/分配方案；限制后台搬迁量，避免一次搬迁重新上传大量页。驻留淘汰和重新上传需要滞回与回退保护。应以 direct tile 数、draw calls、完整计划命中和 GPU 时间验收，不能仅看空闲显存。
4. **让显示表示真正按屏幕误差选择。** 学习 Voxy：常驻精细源数据与当前绘制表示分离。先在已就绪粗细表示间选择，并使树冠/建筑与地形细节可以独立选择；更大范围的降采样重新外露面可作为后续阶段。粗表示未就绪或交接条件不满足时保留旧覆盖。需要验证远景轮廓、材质、树干、细分接缝与望远镜恢复。
5. **再根据 GPU 数据推进方向分组、模型代理和 shader 简化。** 评估六方向命令筛选、归属完整远景的专用路径、更小的面记录和复杂模型代理。现有 Hi-Z 有运行证据；其有效性必须按模式、当前样本和池外路径解释，不能由一个总 quad 数认定失效。

透明排序的独立索引方案与第二项可共同设计，但不能按显存页随意重排透明水面。这里的任务预算针对客户端渲染交接，不是修改 /vss chunky 的玩家配置。

验收采用同存档、同位置、同朝向、同可视距离与画质的静止、转向、移动三个场景，区分加载期与收敛后。分别测准备/提交 CPU、上传次数与字节、命令重建数、同帧有效面、opaque/water GPU、显存与长帧。只有有明确收益且通过交接正确性验证的改动才保留。

本轮没有同场景的 Voxy-only 对照，也没有实施这些新改动。因此能够给出源码与热点对应的优先级，不能承诺固定增加多少 FPS、某项几何必然减少几倍，或宣称已经获得性能收益。

## 7. 可复核的源码位置

VSS（主样本为 NeoForge；这里链接到对应工作区）：

- [完整提交计划检查与重放](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java:177)、[逐字节比较](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java:772)。
- [局部归属缓存与版本检查](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionCoverageOwners.java:38)、[普通表示选择](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:1863)。
- [arena 上传与独立缓冲回退](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionGpuTile.java:259)、[共享池分配限制](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainArena.java:80)。
- [候选范围计数](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:1528)、[驻留存储统计](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:777)。
- [显示变体附加预算](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java:174)、[选择时保护条件](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:258)。
- [20 字节面编码](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionGpuEncoding.java:11)、[竹子模型](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionBlockModel.java:19)。
- [仍提供 feature 的父级保留](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:1138)、[顶点归属裁剪](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java:551)。

Sodium（固定到已安装版本的上游标签提交）：

- [DefaultChunkRenderer 批次复用](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer.java#L80)。
- [ChunkRenderList 位图与局部相机失效](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList.java#L58)。
- [RenderRegion 布局与分离 arena](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion.java#L29)。
- [RenderRegionManager 分离顶点/索引上传与缓存失效](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager.java#L71)。
- [GlBufferArena 扩容整理](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/gl/arena/GlBufferArena.java#L326)。
- [独立透明排序任务](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderSortingTask.java#L21)、[任务与上传预算](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java#L568)。
- [CPU 可见连通图](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java#L79)、[20 字节顶点格式](https://github.com/CaffeineMC/sodium/blob/ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex.java#L12)。

Voxy 263（固定提交）：

- [GPU 层级选择](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp#L119)。
- [cmdgen 的 quad 统计](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp#L104)。
- [扫描合面与 8 字节输出](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java#L75)。
- [六方向颜色/深度模型捕获](https://github.com/MCRcortex/voxy/blob/b0207f6fcc0a55fa6b33f5c916932e8560a71845/src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java#L228)。

本报告与同名 JSON 同步保存到 Forge、NeoForge 两个仓库。核对的批次、arena、显示变体、GPU 编码、归属缓存、GPU 预算及竹子模型代码在统一换行后相同；packed mesh 的已检查差异是 Java 版本对应的 clamp 写法。该核对不是两个仓库全部文件一致的声明，也不是对 Forge 客户端进行过同场景采样。
