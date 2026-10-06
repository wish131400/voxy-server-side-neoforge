# DeceasedCraft 城市模板与预测 LOD 地表布局修复

日期：2026-10-06。源码、回归验证和 0.3.5 JAR 打包已完成，Forge 1.20.1 与 NeoForge 1.21.1 已同步。未替换整合包内的 JAR，未启动或关闭游戏；实机视觉对照与帧时间仍待用户自行测试。

用户指出的“植被已出现，但不符合城市模板”来自输入与归属缺失：旧实现读取建筑楼层，却没有读取道路、公园的地表组合，然后叠加普通生物群系植物。本轮将城市公共地表纳入规划模型，并让模板区域接管预测植被。

## 已实施的修复

- 服务端读取选中的公园 `parkType`、喷泉 `fountainType` 和邻接建筑门前 `frontType`。保留部件所属调色板、放置高度、位置旋转与方块朝向，不消耗生成器共享随机数、不执行真实区块生成。公园升高、边界和相邻公园连接按实际版本设置处理。
- 道路按实际城市样式、16 种连接形态和旋转读取部件；支持新版本主干道连接件和坡道。同层高速公路、地面车站不宣称普通街道模板地表。规划未保留生成器最终道路随机变体，因此采用同样式、同连接形态的稳定变体，不能保证随机变体逐块完全一致。
- 旧版 `BuildingPartRE` 的内联 `PaletteRE` 和引用调色板通过私有转换读取，克隆部件切片，避免写入模组共享的延迟缓存。零高度屋顶占位部件继续跳过，避免合法空模板导致整片 8×8 区域摘要失败。
- 道路、公园复用现有 `Chunk.floors` / `Placement(0, model)` 传输结构。地表提取暴露顶面与侧面，保留空心绿篱的内侧面；近处使用详细模型，远处使用有界简化模型。详细单模型不超过 4096 个四边形，远处公共地表不超过 512 个四边形，沿用区域总量上限。
- 客户端索引非空城市公共地表。在地形切割和水面遮挡之前移除模板区域的冲突自然植被，避免被剔除的植物仍留下地面孔洞或隐藏水面；保留城外植被与真实捕获样本。简化植被也遵循同样的城市归属。
- 模板地面替代简化顶面，地形仍负责边界墙；绿篱与摆设进入独立特征部分。修正首个四边形为绿篱侧面时误标为顶面的剔除方向，并采用叶色染色。
- 公共地表组合缓存限制为 512 项、8 MiB；模型生成在规划快照之后进行。城市网格身份标记更新为 `0x4C430003`，保留已有城市局部变化失效机制。旧城市成品网格会按新身份重建，原始地形采样缓存保留。

本次城市功能沿用现有消息结构，没有主动修改网络协议。最终构建继承当前仓库协议：Forge 48、NeoForge 49。客户端与对应平台服务端应使用同一次新构建。

## 验证与产物

| 仓库 | 城市及相关地表回归 | 实际模组验证 | 打包 |
| --- | --- | --- | --- |
| Forge 1.20.1 | 107 项通过，0 失败，0 跳过 | 4 个 Forge 版本的实际 API 执行；7 个版本字节码契约检查 | `jar` 与 `reobfJar` 成功 |
| NeoForge 1.21.1 | 115 项通过，0 失败，0 跳过 | 7 个版本的实际 API 执行；2 个旧 NeoForge 版本的真实原始部件/调色板转换 | `jar` 成功 |

版本矩阵：Lost Cities 7.3.6、7.4.11、7.4.13、7.5.7、8.2.6、8.3.8、8.4.6。实际 API 执行使用由真实模组接口构造的规划夹具，不是启动上述版本的完整服务器。包括 DeceasedCraft 所用 7.4.11；检查了旧版配置回退、现代公园设置与道路类型重选差异。

回归覆盖普通空气/硬空气覆盖规则、旋转和朝向、调色板变化缓存、公园高度、道路拓扑、远近 LOD、负坐标边界、捕获保留、城市自然植物过滤、地面与水遮挡顺序、绿篱面方向与染色，以及公共地表网络往返。原有建筑、空屋顶、水面、雪面及相关地表验证同时通过。

- `0.3.5-forge-1.20.1`：`C:/Users/Administrator/Desktop/voxyserverside/lib/vss-0.3.5-forge-1.20.1.jar`；6,272,764 字节。SHA256：`B3436CC517CEDE05FD5C97C7D290597197519AAED7D88B755A52887483D18088`。
- `0.3.5-neoforge-1.21.1`：`C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/vss-0.3.5-neoforge-1.21.1.jar`；5,598,770 字节。SHA256：`B575D6ADDB59452F140DA77F1641FF6B8CE186FF2DB2A10ECFE5C3A2FE76C958`。

最终 JAR 已验证 ZIP 完整性、版本元数据、8 个修改类、城市缓存身份、5 个平台原生库与资源源文件一致；Forge 产物已检查 SRG 重映射。NeoForge 的修改类与当前编译输出逐字节一致。测试类未打入发布 JAR。

验证日志与包检查：`C:/Users/Administrator/.codex/tmp/vss-city-surface-20261006/` 下的 `city-forge.log`、`city-neoforge.log`、`build-forge.log`、`build-neoforge.log`、`package-verification.json`。旧源码与旧 JAR 备份位于该目录 `before/`；同步前的 Forge 测试文件另存于 `sync-before/`。

## 仍有的预测精度限制

本轮补齐的是城市部件布局。建筑、长椅等物体仍以 LOD 外形简化；道路最终随机变体、随机破坏、废墟碎石、后续随机城市蔓生植被、模组自定义额外生成，以及喷泉液体还没有完整重建。模板内的叶块、绿篱和设施位置来自选中的模板，未重新随机选择一个公园。

尚未在用户实例做同坐标、同方向的前后对照或 FPS/JFR 采样，不能给出实机画面完全一致或性能改善幅度的结论。建议用户换包后返回截图对应城市，比较生成前后的道路、绿篱、公园和门前设施；首轮旧城市网格按新身份重建时需等待摘要和模型加载。

以下保留初次调查的日志、截图和旧源码证据；其中“当前”指调查窗口、源码行号指修复前版本，不代表新 JAR 的实现。

## 初次调查环境与对比范围

- 实际运行：DeceasedCraft_Beta，Forge 1.20.1。
- 城市生成：lostcities-1.20-7.4.11.jar。
- 城市资产：DCTweaks_5.10.14.jar。
- 实例路径：F:/NovaEngineering-World整合包/.minecraft/versions/DeceasedCraft_Beta。
- 当前实例 VSS 文件：vss-0.3.5-forge-1.20.1.jar。
- 实例 VSS 文件 SHA256：546B1FFAD21F3EE1EBECE9931AD938920C8949B8BA03F2488A982EFC4D729C35。
- 用户截图相机附近：X=-6243、Z=-4119。

真实地形截图：C:/Users/ADMINI~1/AppData/Local/Temp/codex-clipboard-87f4244d-b29a-4cf7-b19b-7ac4cb468c77.png。

望远镜预测截图：C:/Users/ADMINI~1/AppData/Local/Temp/codex-clipboard-d3707c1f-6488-40fb-98fc-9e87f9bd495f.png。

截图可以说明布局类别的差异，但没有逐个确认两张图对应完全相同的目标区块，不能据此断言某一株植物或某一栋建筑发生了精确位置偏移。

| 内容 | 真实地形截图 | 预测截图与修复前实现 |
| --- | --- | --- |
| 城市绿化 | 连续且有规则的绿篱、对称绿地、花坛、庭院边界 | 能看到灌木、草和花，但缺少模板规定的排列 |
| 道路和步道 | 道路、铺装、庭院和绿化具有完整的布局关系 | 道路仅用材质、宽度和连接方向近似，未读取住宅区道路部件 |
| 建筑 | 房屋、屋顶、庭院与街区布局组合 | 已有建筑预览，但部分区域的城市摘要请求发生失败 |
| 自然区域 | 有自然山体与森林 | 生物群系预测适用于这些区域，修正城市绿化时应保留它们 |

## 修复前的源码证据（旧行号）

1. 服务端只提取建筑楼层部件。

   src/main/java/dev/xantha/vss/networking/server/compat/LostCityPlanningReader.java:85 的 plan() 读取城市类型、地面高度、路面材质、路宽和连接方向。在第 97 行识别 PARK，但第 113 行仅在 hasBuilding 时读取 floorTypes、floorTypes2。当前没有提取实际选中的 parkType、fountainType 或道路部件及其布局。

2. 客户端仅对建筑区块索引城市模型。

   src/main/java/dev/xantha/vss/client/prediction/PredictionCityGeometry.java:25 对非 building() 区块直接 continue。第 76 行的 groundState() 用路宽和四方向连接计算十字形路面；PARK 使用单一 surfaceState。该摘要没有表达公园内的绿篱、步道或花坛。

3. 植被来源没有城市模板语义。

   src/main/java/dev/xantha/vss/client/prediction/PredictionVegetation.java:558 的 generate() 创建 PredictionDecorationLevel，执行已支持的结构与生物群系 placed features。它没有城市选中部件及其已放置地表作为输入。

   src/main/java/dev/xantha/vss/client/prediction/PredictionSimpleVegetation.java:59 从生物群系特征提取树和草的提示，第 101 行后的粗粒度构建使用世界坐标与种子选择视觉代表。它也没有城市绿化布局输入。

4. 自然植被只对建筑区块被抑制。

   src/main/java/dev/xantha/vss/client/prediction/PredictionMeshBuilder.java:383 计算 cityBuilding，第 386 行仅在 !cityBuilding 时加入 placed vegetation，第 395 行同样只用 cityBuilding 过滤简化植被。因此道路和公园仍可能加入自然生物群系植物，而真正的城市绿化部件没有被绘制。

5. 自然装饰计算与城市显示地面是分开的。

   src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:2108 获取自然植物，第 2117 行构建简化植物，然后组装城市预览。该顺序没有让自然特征看见最终城市模板的道路、绿篱或步道占用范围。城市地面材质近似不能替代真实模板，也不能自动修正已经计算的植物分布。

## 实际模组资产证据

检查 DCTweaks_5.10.14.jar，住宅区 citystyle 位于：

data/deceasedcraft/lostcities/citystyles/suburb_residential.json。

该样式的 streetblocks.parts 明确列出不同连接形态的道路部件，包括：

- deceasedcraft:streets/street_suburb_all；
- deceasedcraft:streets/street_suburb_straight；
- deceasedcraft:streets/street_suburb_t_shape；
- deceasedcraft:streets/street_suburb_bend；
- deceasedcraft:streets/street_suburb_end。

selectors.parks 明确选择 park_1、park_2、park_4 等公园部件。这证明住宅区街道与公园采用具体部件，而非只有“道路宽度”和“草地材质”。该资产示例说明数据结构，尚未证明截图中每个目标区块具体选择了哪一个部件。

park_1 位于 data/deceasedcraft/lostcities/parts/parks/park_1.json，大小为 16×16，高度为 3，使用固定字符网格描述位置。它引用 deceasedcraft:parks/park_1_palette。调色板将字符映射为：

| 字符 | 方块或设施 |
| --- | --- |
| b | minecraft:dark_oak_leaves，persistent=true，用于模板中的连续绿篱 |
| c、d、e、f | cfm 的深色橡木公园长椅，具有不同方向与左右部件 |
| g | minecraft:smooth_stone_slab |
| h、i、j、k | 蕨类、枯灌木和草的盆栽 |

street_suburb_all 的部件大小为 16×16，高度为 2。其引用调色板包含 embellishcraft:polished_paving、minecraft:moss_block、createdeco:umber_bricks，以及 quark:dark_oak_hedge。这些方块的位置属于道路部件布局，无法从普通生物群系树木生成推导出来。

对实际 Lost Cities 7.4.11 的字节码检查也确认：generateStreet() 选择 BuildingInfo.parkType 或 fountainType 并调用 generatePart()，还执行街道装饰等后续逻辑。除模板以外，真实世界还有随机破坏、废墟/碎石、额外装饰等阶段；只增加模板模型，不等于完整复现所有最终世界状态。

## 初次调查运行状态：主要问题不是继续等待自然植被

latest.log 在 10:13:57.673 和 10:21:26.697 均记录：

VSS prediction dimension ready ... rust=true。

当前原生地形后端已启用，之前 signed biome color 导致的 Java 全局回退不再是这次布局问题的解释。Rust 后端启用代表地形采样恢复，不代表已经实现城市地表模板。

只读现场快照显示：

- 基础覆盖：234/234；
- 近处目标精度：169/169；
- 目标精度：169/169；
- 当前符合地表细化条件的瓦片：616/630，约 97.8%；
- 已缓存城市区域：508；
- 待请求城市区域：2707；
- 在途城市请求：0；
- 城市能力退避剩余：约 17.7 秒，随后逐步减少。

616/630 是当前符合条件范围的进度。另一项 surfaceDiagnostics.ready=644 包含历史或范围外条目，不能与 630 相除。以上是总体进度，也不能证明望远镜中的每个目标区块都已细化完成。

现场没有新的 skippedFeatures、disabledRegistryFeatures、unsupportedFeatures 记录。但该会话大量恢复了磁盘缓存，零失败计数并不证明城市装饰与真实世界已经一致。

采样期间帧率约 30、工作线程 active=0，且日志出现暂停；本报告不把该窗口当作游戏内性能基准。

## 部分建筑缺失：另一个已出现的城市查询失败

latest.log 在 10:16:06.317 记录：

VSS Lost Cities planning unavailable; city preview query failed; client will retry
java.lang.IllegalArgumentException: Unsupported Lost Cities part dimensions
at LostCityPlanningReader.templateInput(...:184)

按运行包的旧尺寸校验扫描实际资产，共检查 8149 个模板，发现 49 个会被拒绝的模板，均来自 DCTweaks，大小为 16×16、高度为 0，包含 multi_filmworkstower_*_top 等占位屋顶部件。它们没有待绘制内容，空部件不应让同一查询区域的其他楼层或建筑一起失败。

服务端区域查询一次处理 8×8 个区块；旧处理会把部件异常提升为整个区域查询失败。客户端 LostCityHints.java:96 将 unavailable 响应转为整个城市能力的 30 秒退避，因此其他待请求区域也会延迟。现场快照确实处于这种退避中。

初次调查时两个仓库源码的 LostCityPlanningReader.java:187 已包含 slices.length==0 时 return null 的处理，跳过空部件；当时实例仍使用上述 SHA256 对应的旧处理。该已有修复现已随本轮 JAR 打包，并通过空屋顶占位部件的回归验证。实例未替换，实际游戏效果仍待用户测试。

该错误能够解释部分建筑摘要迟迟到不了客户端，不足以证明所有缺失建筑都只有这个原因。

## 初次调查提出的修复目标

1. 验证并打包已有空部件处理，避免合法占位部件让城市摘要查询失败。明确区分真正的兼容性不可用与单个部件异常，避免无关区域被一并长期退避。
2. 扩展城市规划快照，提取服务器实际选中的道路、公园、喷泉和重要街道装饰部件，以及调色板、变换和放置高度。复用已有有界模型提取路径；不能简单在客户端重新随机选一个看起来相似的公园。
3. 客户端为道路和公园等非建筑区块绘制城市地表模型，保留路面、绿篱、步道的布局关系；远距离简化应保留占用范围和轮廓。
4. 给城市模板与自然植被明确的占用/替换规则。在城市模板占用的路面、绿篱、设施位置剔除冲突的自然植物；保留城外自然植被，以及真实生成允许的后续城市植被。只关闭全部城市树木会留下空白公园，不能解决模板一致性。
5. 将城市模板内容与版本纳入网格、装饰和磁盘缓存身份。城市摘要补齐或发生变化时局部更新相关瓦片，避免复用旧的“自然植被+粗城市地面”网格，也避免整片重算带回此前的移动卡顿。

实施后的验收应使用相同种子、目标区块和观察方向，在真实区块生成前后比较道路部件选择、绿篱占用、步道与建筑关系；同时复查移动时的 CPU 准备成本、上传量和帧时间。对随机损坏、额外装饰等尚未支持的生成阶段单独记录误差，不能用“模型能显示”替代与真实世界的对照。

## 原始证据位置

临时只读调查目录：

C:/Users/Administrator/.codex/tmp/vss-lod-content-20261006/

其中包括：

- live/sample-0.txt、sample-1.txt、sample-2.txt：只读客户端诊断；
- part-dimensions.json：模板尺寸扫描；
- city-template-examples.json：城市资产初步示例；
- LostCityTerrainFeature.bytecode.txt：实际 Lost Cities 7.4.11 生成调用。

住宅区道路、公园及其调色板在本轮直接读取原始 JAR 核实。报告仅选择说明问题的数据，没有将文件名筛选得到的部件数量当作截图实际选择的模板数量。
