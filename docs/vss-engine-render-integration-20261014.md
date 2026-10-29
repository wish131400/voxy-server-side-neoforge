# vss engine render integration 20261014

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](README.md)，相关旧报告见 [历史索引](history/README.md)。 本报告路径保留，供既有验证工具及证据清单引用。

**引擎渲染思路的正式整合与复测（2026-10-14）**

本轮把常驻候选记录复用接入两个平台的正式渲染路径，并完成整批变化的直接重建、元数据上限、引用回收、Voxy 交接组合回归及发布 JAR。粗树冠代理没有进入正式包：统一透明材质后的质量仍不合格，保留原表面的替代方案又未通过收益／额外驻留检查。

没有启动、退出、替换或采样 Minecraft 客户端，没有提交或推送 GitHub。所有性能数字来自离线组件／GPU 对照。不是 Unity／Unreal 引擎实测，也没有移植 Nanite。

**正式行为**

生产修改集中在 src/main/java/dev/xantha/vss/client/prediction/PredictionGpuSelectionInput.java。现有 Scene、PreparedPass、OpaqueBatches、MDI、当前帧 Hi-Z 和绘制策略继续负责各自工作。

- 原始 Draw 身份、packed 身份、局部 mesh/GPU revision、相机位置和 horizon 共同验证记录；无关资源只改变全局 epoch 时，保留候选对象和列表身份，下游可以继续复用绘制范围及静态命令。
- 对稳定顺序的局部变化只重新归一化受影响记录。仍会扫描候选元数据；没有宣称零扫描，也没有新增一套 GPU Scene ID／脏场景队列。
- 上一原型的双 IdentityHashMap／逐次 Cached 对象改为有上限的可复用记录数组。至多缓存 16,384 条新增元数据；更大集合走直接重建，几何仍按原策略绘制。
- 快照变化时抽查最多 32 个位置。至少四分之三更换，或集合大幅扩大时，直接重建，不维护局部记录缓存。这个采样只决定成本路径；所有实际记录仍按当前资源和视距检查，不能据此漏掉几何。
- 直接重建按原有包围盒公式计算视距及面选择，避免每条记录另外创建 Entry 和两个 AABB。完整替换不再付出原型的双表／缓存对象维护成本。
- 移动、视距变化、GPU 关闭／恢复和资源更新仍会重新检查。场景缩小后清除尾部引用，大幅缩小后容器降容；停止常驻选择、换世界或资源重载沿用已有 clear 调用，释放候选、数组和容器容量。缓存不 pin 或解压网格。
- 不把 Voxy 覆盖结果冻结在输入缓存里。PreparedPass／SubmissionCoverage 仍独立处理覆盖版本；组合测试明确检查同一候选列表下的完整接管、覆盖撤销、重新接管，以及高空超出三维范围时的预测恢复。

水面、Iris 专用提交、父子归属、接缝和 morph 选择未增加新分支。现有驻留 GPU 选择仍仅在已验证的大集合、原地转向、当前帧 Hi-Z、不含接缝等条件成立时使用。本轮收益不能外推到每个视角或 GPU 受限场景。

**CPU 对照：候选维护加正式 PreparedPass**

双方使用相同快照，基线输入源码冻结于修改前；同一正式 PreparedPass 处理两路结果。每组预热 48 轮，交替顺序采集 81 轮。单位 ms/次、中位数；分配为当前线程每次累计新分配字节。此项不包括世界生成、快照生产、OpenGL 提交和 GPU 等待。

| 平台 | 候选数 | 每轮替换记录 | 修改前 ms | 正式实现 ms | 耗时变化 | 分配字节/次 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| neoforge | 4096 | 1 | 1.8649 | 0.8660 | -53.6% | 803,192 → 197,088 |
| neoforge | 8192 | 1 | 4.3645 | 1.7445 | -60.0% | 1,605,832 → 393,664 |
| neoforge | 4096 | 4096 | 2.0385 | 1.9864 | -2.6% | 803,192 → 786,768 |
| neoforge | 8192 | 8192 | 4.1727 | 4.0951 | -1.9% | 1,605,928 → 1,573,120 |
| forge | 4096 | 1 | 2.3799 | 0.9595 | -59.7% | 1,327,480 → 197,088 |
| forge | 8192 | 1 | 5.5996 | 2.2297 | -60.2% | 2,654,440 → 393,696 |
| forge | 4096 | 4096 | 2.7019 | 2.4787 | -8.3% | 1,327,480 → 786,768 |
| forge | 8192 | 8192 | 5.4999 | 5.1118 | -7.1% | 2,654,536 → 1,573,152 |

局部替换显著减少准备和临时分配；本次完整替换的整段中位数也未再出现上一原型约 10% 的退步。微小差异受 JIT、平台与系统调度影响，不作为固定收益承诺。原地无变化窗口两路都已有缓存，不能重复计为本轮收益。

**包含 GPU 完成的正式提交对照**

基线为修改前 IndirectBatch 的冻结副本，仅类型名和输入类型重命名；候选使用正式 PredictionIndirectBatch 和正式输入缓存。两路均沿用生产 PreparedPass、OpaqueBatches、arena、MDI、当前帧 Hi-Z，并按相同策略选择 GPU 过滤。

特意让一个不属于候选集合、但已 trackSubmission 的 mesh 每帧改变全局 epoch，以覆盖本次修复的失效压力。测试断言全局版本确实增加、重型转向窗口确实执行选择。每批 32 帧，8 批预热后测量 21 批，交替 A/B，批边界 glFinish。表为整批总墙钟时间除以 32 的中位数，不能换算游戏 FPS。

| 平台 | 地形瓦片 | 修改前 ms/次 | 正式实现 ms/次 | 减少 |
| --- | ---: | ---: | ---: | ---: |
| neoforge | 3072 | 5.0613 | 2.4026 | 52.5% |
| neoforge | 4096 | 7.3950 | 3.2687 | 55.8% |
| forge | 3072 | 6.3095 | 2.8379 | 55.0% |
| forge | 4096 | 7.5076 | 2.6164 | 65.1% |

同一测试的静止／禁用选择控制如下。它们不触发常驻候选选择，不应期待缓存修改带来收益；同一语义路径仍有测量波动。

| 平台 | 地形瓦片 | 修改前 ms/次 | 正式实现 ms/次 |
| --- | ---: | ---: | ---: |
| neoforge | 3072 | 0.4547 | 0.4375 |
| neoforge | 4096 | 1.7659 | 1.9514 |
| forge | 3072 | 1.2784 | 1.4518 |
| forge | 4096 | 1.0988 | 1.0947 |

每平台 12 对提交颜色和 12 对深度逐像素完全一致。GPU query 区间可能包含 CPU 提交间隙，不能据此把变化全部解释成着色器耗时下降。没有改写 shader 或减少正式几何。

**树冠方案的两项结论**

补充检查发现，上一轮二值代理实验的历史源叶面缺少 CUTOUT 标记，而代理有该标记。虽然两路使用相同真实纹理，原先质量数字仍受透明模式不一致影响。本轮将源叶面与两种代理都设为透明树叶，显式关闭 FastLeaves，并扩充为 0／45／90／135 度四个朝向；后续质量判断以本轮材料一致的结果为准，旧原始数据保留。

两个平台材料一致的结果相同。满足原试验距离条件的 2 方块代理有 48 个视角，树叶掩码 IoU 中位数 0.819，新增覆盖约 22.2%；4 方块代理有 24 个视角，IoU 0.655，新增覆盖约 52.6%。丢失覆盖中位数为零，主要问题仍是增加覆盖、改变孔隙和树冠轮廓。远处仅数个像素的树冠对一像素误差非常敏感，不能将 IoU 当作完整视觉质量指标。

本轮每平台 6 样本 × 3 距离 × 4 朝向 × 3 表示的直绘／MDI，共 216 对颜色和 216 对深度完全一致。这证明提交一致，不证明粗代理与源几何质量一致。代理的实验距离／归属布尔门槛仍未接入正式交接，因此没有被用来替代生产几何。

![统一透明模式后的树冠对照](engine-integration-20261014-canopy-alpha.png)

另外制作了严格保留源坐标、材质、颜色、光照和表面孔隙的平面聚合试验。它只合并已有共面矩形，不从二值占据格外推树冠；用增量范围保留原地形、树干、植物和水体。

但在现有正式精确合并之后，六个样本剩余叶面只能再减少约 0～3.8%。要另存这些远景叶面，额外驻留却远超“至少减少一半叶面、附加面数不超过源网格四分之一”的准入要求。六个样本全部拒绝，生产路径未保留该计算、数据或开关。结果不支持为它增加运行时负担。

此结论只针对六个经过筛选的历史森林样本及当前表示，不能说明所有带覆盖率的 HLOD 都无效。需要体积／覆盖率模型的新树冠表示仍属于后续研究，本轮没有将其冒充为完成的生产优化。

**验证与产物**

NeoForge 使用 JDK 21，Forge 使用 JDK 17。最终 GPU 测试在同一 RTX 4070 Ti SUPER、OpenGL 4.6、NVIDIA 572.70 上串行运行。

- neoforge：去重 305 项测试，最终失败／错误／跳过均为零；包括候选正确性、CPU 对照、覆盖组合、原有 CPU／GPU 回归、选择退回与材料一致的森林实验。
- forge：去重 303 项测试，最终失败／错误／跳过均为零；包括候选正确性、CPU 对照、覆盖组合、原有 CPU／GPU 回归、选择退回与材料一致的森林实验。

回归包括局部版本、完整替换、候选上限、引用释放、Voxy 覆盖撤销与三维范围、缺失／混合归属、父子和接缝交接、资源失效、冷恢复、透明水面，以及当前帧 Hi-Z 和不支持模式的退回。没有替代真实整合包、Iris shader pack 或其他显卡上的客户端验证。

JAR 检查确认 Java class major 分别为 65／61，Forge 已执行 reobfJar，Windows 等五种原生库资源包含在包内，所有实验／基线／测试类都未进入发布 JAR。版本保持 0.3.5，使用独立产物目录。

- neoforge：`C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/engine-integration-20261014/vss-0.3.5-neoforge-1.21.1.jar`，6,077,998 字节。SHA256：`855e9433a687afbe833ac2a935c65e7fead0e343e07bba1fb1729ca0146c1a6a`。
- forge：`C:/Users/Administrator/Desktop/voxyserverside/lib/engine-integration-20261014/vss-0.3.5-forge-1.20.1.jar`，6,748,296 字节。SHA256：`de486568b96154759dd6c18ad3910855d7eeecfa2433cfb011979f00b8fd5dba`。

源码、冻结基线、复现脚本、每平台原始 CSV／JUnit／日志保存在两个仓库相同的 tools/prediction/engine-render-integration-20261014；JSON 汇总为本目录同名 .json。开发中材料对照曾因夹具文件误命名未运行，已修正并成功独立重跑；该早期失败日志与最终成功日志均保留，最终 JUnit 和成功重跑结果为准。

```powershell
# NeoForge；先完成后再运行 Forge 的 GPU 测试。
./gradlew.bat --offline --no-daemon -I tools/prediction/engine-integration-tests.gradle residentRecordTest residentCoverageTest turnMainRegressionTest canopySurfaceTest residentMaintenanceGpuTest turnMainGpuTest gpuSelectionFallbackTest canopyMaterialQualityGpuTest jar --console=plain

# Forge（进入 Forge 仓库后运行）
./gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/engine-integration-tests.gradle residentRecordTest residentCoverageTest turnMainRegressionTest canopySurfaceTest residentMaintenanceGpuTest turnMainGpuTest gpuSelectionFallbackTest canopyMaterialQualityGpuTest jar reobfJar --console=plain
```
