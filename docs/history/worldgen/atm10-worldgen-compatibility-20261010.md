# ATM 10 8.2 地形与结构兼容核对（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

> 后续实现（2026-10-10）：本文保留调整前的审计证据。模板容量、客户端本地校验和缺失补发已在两个加载器实现，BWG / OTTYG 的 NBT 树木模板入口也已接入。实现范围和验证结果见 [模板同步实现说明](structure-template-local-sync-20261010.md)；自定义维度、地标放置与部分结构阶段的缺口仍须分别适配。

**目前存在明确的预测兼容缺口。影响面最大的几项是结构模板传输容量、BWG 的 NBT 树木接口、结构生成阶段、自定义维度生成器和暮色森林地标放置。** 仅修复 issue #9 的崩溃隔离，并不等于整包所有地形和建筑都能预测。

本文的兼容对象是客户端根据种子复现尚未交付的远景。服务器已生成区块的真实 Voxy 列同步走实际区块数据，不依赖下文这些客户端复现接口。一个建筑不能预测，不表示该建筑永远不能通过真实远景同步显示。

## 核对对象与证据

- ATM 10 **8.2**，Minecraft **1.21.1**，NeoForge；对应 issue #9 使用的整合包版本。
- CurseForge 项目 `925200`，发行文件 `8945086`，发布日期 2026-09-22。
- 从实际发行 ZIP 通过 HTTP 范围读取提取 `manifest.json` 和 `overrides/config/crash_assistant/modlist.json`，校验 ZIP CRC；未下载完整 199 MB 整合包。
- 发行 ZIP 内的模组清单与官方仓库提交 `e5e3d1d83ec6885bb9a5e9fb309fb8e9730db025` 的清单解析后完全一致。
- 实际下载并逐个校验 **57 个相关发行 JAR** 的 SHA-1，与发行包清单对应；另记录 SHA-256。检查内部生成资源、类继承和关键方法字节码。
- VSS 对照 NeoForge 当前工作区：HEAD `1c9fd92c25db18605ba62d3ecbaf703248953f73`，包含本轮 issue #9 未提交修复。Forge 仓库保存本文副本，**不将 ATM 10 的 NeoForge 结论扩展到 Forge 1.20.1**。
- 没有启动完整 ATM 10 实例，没有进行游戏内坐标对照。通用路径存在只能说明具备接入基础，不能算整包实机通过。

发行包结构／维度 JSON 的直接覆盖文件检查为 0 个；包内另有生物群系标签和动态 KubeJS 数据脚本。统计按实际 JAR 原始资源，最终运行配置、标签、模组初始化仍可能改变哪些结构在世界中出现。

完整版本、哈希、生成器、阶段和模板统计见 [机器可读核对证据](../../atm10-worldgen-audit-20261010.json)。JSON 中的模板截断模拟仅包含这 57 个 JAR，不包括原版、其他模组或整合包资源，不能作为完整 ATM 游戏的实际发送计数。

## 1. 结构模板传输容量是通用缺口

`WorldgenCodecSnapshot.encodeTemplates` 按 ResourceLocation 自然顺序遍历模板：每个文件最多 **512 KiB**，累计最多 **5 MiB**。计量的是读出的、内部已经 gzip 压缩的 NBT 文件字节，尚未 Base64 编码。

检查到的 57 个 JAR 在当前 1.21.1 `data/<namespace>/structure/` 路径下有 **12,508 个唯一模板，合计 137.911 MiB**。另有旧 `structures/` 路径，统计已排除它们，避免混用两个版本的资源目录。

只按这批 JAR 重放现有截断规则，会保留 113 个模板，因总量上限舍弃 12,382 个，因单文件上限舍弃 13 个。**这不是完整整合包的实际发送结果**，但足以确认当前同步方式无法覆盖 ATM 的模板规模。

| 实际资源 | 文件大小 | 现有处理 |
| --- | ---: | --- |
| When Dungeons Arise `kisegi_sanctuary_main_0.nbt` | 3.757 MiB | 超过单文件限额 |
| When Dungeons Arise `keep_kayra_main_0.nbt` | 3.645 MiB | 同上 |
| When Dungeons Arise `infested_temple_main_0.nbt` | 2.645 MiB | 同上 |
| YUNG's Better Ocean Monuments `start.nbt` | 1.668 MiB | 同上 |
| Bumblezone 六个 sanctum 主体模板 | 各约 0.578 MiB | 同上 |
| Nullscape 两个 destroy 模板 | 各约 0.507 MiB | 同上 |

`PredictionStructureTemplates` 只使用服务器传来的模板，缺失时不读取本地模组默认资源。缺模板的建筑会放弃预测。Moog 四个结构模组、CTOV、Repurposed Structures、When Dungeons Arise、Cataclysm 和多个 YUNG 模组都受这一公共入口影响。

这项需要模板身份校验、按需交付与缓存；不能仅把 5 MiB 改成 138 MiB。整个注册表元数据另有 **8 MiB 压缩、32 MiB 解压**上限，整批传输也会增加进档解码和内存压力。若使用本地资源，必须确认它与服务器的资源／数据包内容一致。

源码入口：`WorldgenCodecSnapshot.java:125`、`PredictionStructureTemplates.java:42`、`WorldgenProfileS2CPayload.java:28`。

## 2. BWG 的 NBT 树木缺少运行时桥接

ATM 安装 **Oh The Biomes We've Gone 2.6.0** 和 **Oh The Trees You'll Grow 5.3.2**。BWG JAR 中有 **281 条** `ohthetreesyoullgrow:tree_from_nbt_v1` 配置资源；这是资源条目数，不是 281 种不同树的游戏验收结果。

实际字节码中，`TreeFromStructureNBTFeature.place` 与 V2 都调用：

```text
WorldGenLevel.getLevel()
  -> ServerLevel.getStructureManager()
  -> 读取树干和树冠结构模板
```

VSS 的 `PredictionDecorationLevel` 继承 `FeatureStampLevel`，后者的 `getLevel()` 直接抛出 `UnsupportedOperationException`。`PredictionSurfaceFeatureAdapters` 目前只针对原版湖泊和末地柱子提供替代入口；NBT 树不在其中。`PredictionTreeModels` 对这类自定义 Feature 也不使用原版树的快速模型缓存。

因此，**TerraBlender 生物群系路由适配不等于 BWG 的 NBT 树木生成适配完成**。当前通用放置会在获取真实服务端世界时被阻止。需要通过专用树木适配入口读取服务器认可的模板，并在有界预测世界中放置；还需要同时解决前一节的模板供应。

源码入口：`feature/FeatureStampLevel.java:228`、`PredictionSurfaceFeatureAdapters.java:29`、`PredictionTreeModels.java:53`。

## 3. 建筑并不都位于 SURFACE_STRUCTURES 阶段

当前 `PredictionSurfaceStructures.supported` 只允许 `SURFACE_STRUCTURES`，以及**原版下界维度**中的 `UNDERGROUND_DECORATION`。普通 Feature 的阶段处理范围更大，不能把它与 Structure 的筛选混为一谈。

| 模组及 ATM 版本 | 实际资源中的缺口 | 对预测的影响 |
| --- | --- | --- |
| **Towns and Towers 1.13.11** | 60 条结构定义中，57 条在 `fluid_springs`，3 条在 `underground_decoration`；后者为海洋相关建筑 | 该发行包的这些村庄、前哨站和残骸没有进入当前支持的结构阶段 |
| **Structory 1.3.17** | 15 条中 11 条在 `raw_generation` | 多数遗迹、营地、小建筑被排除；其余 4 条有通用入口 |
| **BWG 2.6.0** | 18 条中 6 条在 `raw_generation` | 滴水石／红岩／茂密拱门、大型冷湖、台地和尖锐岩石等结构地貌被排除 |
| **When Dungeons Arise 2.1.68** | 40 条中 6 条在地下、泉水或要塞阶段 | 含 infested temple、kisegi sanctuary、plague asylum 等；其余仍受模板容量限制 |
| **Repurposed Structures 7.5.22** | 107 条中 23 条在植被、地下、要塞或顶层阶段 | 部分矿井、要塞、村庄等缺少预测；84 条地表结构具有通用入口 |
| **Iron's Spells 'n Spellbooks 3.16.3** | citadel 在 `fluid_springs`；其余 4 条在 `underground_decoration` | citadel 被排除；其余仅在原版下界可进入该阶段，其他维度的同阶段建筑缺失 |
| **The Aether 1.5.10** | bronze dungeon 在 `top_layer_modification` | 青铜地牢不进入当前结构重放；不能因此把整个天境地形判为不支持 |
| **Dungeon Crawl 2.3.17** | `underground_structures` | 未纳入当前预测范围 |
| **Underground Villages 4.0.3** | 主世界 `underground_decoration` | 同上 |
| **YUNG Better Dungeons 5.1.4 / Better Mineshafts 5.1.1 / Better Strongholds 5.1.3** | 地下或 `strongholds` 阶段 | 对应地下建筑未纳入当前范围 |

这里需要优先纳入玩家能在远景中看到的非默认阶段建筑，保留真实生成顺序、随机种子索引和事务回滚。全面开启地下生成是另一个成本和显示范围决定，不能仅凭建筑名字带“地表”来选阶段。

结构重放另有候选起点搜索 **8 区块**、虚拟区域 **5×5 区块**和每次放置 **65,536 次写入**等界限。大型建筑即使通过前面的筛选，也要验证它的查询范围和后处理接口。

源码入口：`PredictionSurfaceStructures.java:81`、`:90`、`:114`，`PredictionDecorationLevel.java:18`。

## 4. 四类维度地形有明确的生成器入口缺口

| 模组及版本 | 实际维度与生成器 | 当前结论 |
| --- | --- | --- |
| **The Undergarden 0.9.6** | `undergarden:undergarden`，`UGNoiseBasedChunkGenerator` | 自定义噪声生成器没有对应 VSS 后端；当前通用路径拒绝预测 |
| **The Bumblezone 7.16.1** | `the_bumblezone:the_bumblezone`，`BzChunkGenerator` | 同上；另有自定义生物群系源 |
| **Eternal Starlight 0.9.1** | `eternal_starlight:starlight`，`ESChunkGenerator` | 同上；另有 landmark 放置规则 |
| **Allthemodium 3.0.1 的采矿维度** | `allthemodium:mining`，`CustomFlatLevelSource`，类型 `allthemodium:flat` | 不属于现有噪声快照入口，没有可复现的生成器快照 |

`WorldgenCodecSnapshot.encodeGenerator` 对非 `NoiseBasedChunkGenerator` 返回空快照，对该类的自定义子类标记 `vss_unsupported_reason`。客户端会检查这一标记。内置注册后端为 TerraBlender，未找到这些维度的专用注册。

**Allthemodium 的其他维度不能一并判为不支持**：`the_other`、`the_beyond` 使用普通 `minecraft:noise`。Aether、Ad Astra 各行星与轨道、Deeper and Darker 的 Otherside、Ice and Fire 的 Dread Land 也使用标准噪声入口。这些有通用地形接入基础，实际曲面规则、特征和结构仍需要逐项验证。

源码入口：`WorldgenCodecSnapshot.java:154`、`:165`，`ClientWorldgenProfileDecoder.java:133`、`VSSClientBootstrap.java:23`。

## 5. 暮色森林地标与 YUNG 末地主岛还需专项接入

**Twilight Forest 4.8.3345** 的该版维度实际使用 `minecraft:noise`，不是自定义 ChunkGenerator；生物群系源为 `twilightforest:twilight_biomes`，依赖自定义数据注册表。现有自定义注册表收集和 Java Codec 路径具备接入基础，Rust 原生允许列表不覆盖该源，不能把“没有 Rust 支持”写成“完全没有 Java 支持”。本轮没有进行地形解码及坐标对照。

它的 19 个结构集有 **16 个**使用 `LandmarkGridPlacement`，该类直接继承 `StructurePlacement`；另 3 个 AvoidLandmarkGridPlacement 继承 RandomSpread。VSS 只收集 `instanceof RandomSpreadStructurePlacement` 的集合，因此主要地标不参与预测。需要支持其地标网格算法及关联数据。

**YUNG's Better End Island 3.1.2** 的部分主岛建筑、祭坛与平台由 `EndDragonFight` 等服务端生命周期入口放置，不是普通结构集快照。其 `SpikeFeatureMixin` 还把柱子放置改为 `BetterSpikeFeature.placeSpike`，而 VSS 的 `PredictionEndSpikes` 使用自身原版柱子方块重放。改版祭坛、平台和柱子形状不能按目前的原版路径视为已适配。现有 `BetterEndCompat` 面向另一款 BetterEnd，不能用它证明 YUNG 模组兼容。

## 6. 有通用基础但尚未整包验收的部分

- **BWG 2.6.0 / Regions Unexplored 0.5.9**：已有 TerraBlender 区域、气候路由及表面规则基础。BWG 的 NBT 树和特殊结构属于前述明确缺口；RU 的自定义植物 Feature 能调用通用 Java 放置入口，但大树、有界读取和后处理仍需实测。
- **Nullscape 1.2.14**：实际覆盖末地的普通 noise / multi_noise 资源，具有通用地形入口；它的两个大模板超过单模板限额。
- **CTOV 3.6.3、Explorify 1.6.5、Formations Overworld 1.0.5+c、Formations Nether 1.0.5+b、Structory Towers 1.0.16**：地表结构具备通用重放入口，模板、处理器、运行时世界访问与体积界限仍影响实际结果。Formations Nether 另有 2 个地下阶段结构被排除。
- **Moog 四个结构模组、Repurposed Structures、Cataclysm、YUNG API 的 enhanced placement 及沙漠／丛林神庙 placement**：检查实际类继承，均可进入 RandomSpread 的筛选。不同的 type 名称不构成自动排除条件；模板和生成阶段更值得优先处理。
- **Ars Nouveau 5.13.1、Apotheosis 8.8.0、Corail Tombstone 9.5.6、Illager-Warship 1.0.1**：本次资源定义的结构位于地表阶段，具备通用入口。并非已通过游戏内预测验收。
- **Lithostitched 1.8.0**：VSS 已有密度与模板列表依赖适配。此前具体版本资料为 `1.8.0+beta6`，不能把它等同于 ATM 的 `1.8.0` 正式版全量验证。
- **Mo' Structures 1.6.0+1.21.1**：issue #9 已用实际 JAR 验证故障隔离。该包的非法私有字段访问仍导致其损坏结构条目跳过，尚未使这些建筑恢复预测。

Bibliobiomes Legacy 的本次发行包主要是 Bibliocraft 木材兼容模块，不能仅根据名字把它当成额外地形生成器。官方清单中未列出 Terralith、Tectonic、Biomes O' Plenty、Lost Cities 或 Blueprint，已有这些模组的适配记录不能替代 ATM 当前组合的验收。

## 建议的后续适配顺序

1. **模板按需供应及资源身份校验**：覆盖最多结构模组，也为 NBT 树提供必要输入。
2. **BWG / Oh The Trees You'll Grow 的专用 NBT 树入口**：直接解决获取真实 ServerLevel 时失败的问题。
3. **非默认阶段的可见建筑**：先处理 Towns and Towers、Structory、BWG 地貌、WDA 和 Iron's 的可见部分，再决定地下结构范围。
4. **自定义维度后端与暮色地标网格**：采矿维度、Undergarden、Bumblezone、Eternal Starlight 逐个加入准确的快照和复现，暮色地形与地标分别验收。
5. **YUNG 末地主岛及 Mo' Structures 真正生成适配**：补生命周期几何和异常发行包的可用生成入口。

每一项验收应在相同种子、维度和坐标比较预测与实际区块，覆盖远景 → 真区块交接，并记录缺模板、放置失败和预算越界。这里的静态核对没有提供 FPS 改善结论。

## 来源与本轮变更

- [ATM 10 8.2 实际发行文件](https://www.curseforge.com/minecraft/modpacks/all-the-mods-10/files/8945086)
- [官方固定提交模组清单](https://github.com/AllTheMods/ATM-10/blob/e5e3d1d83ec6885bb9a5e9fb309fb8e9730db025/config/crash_assistant/modlist.json)
- [既有 VSS 0.3.5 模组验证范围](../releases/release-0.3.5-mod-compatibility-20261006.md)
- [issue #9 的修复范围](issue9-worldgen-codec-isolation-20261010.md)

本轮新增本报告和核对证据，未修改生产源码、构建新包或替换游戏文件。没有向 GitHub 提交、推送或发送消息。
