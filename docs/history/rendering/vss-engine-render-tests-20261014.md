# vss engine render tests 20261014

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**Unity／虚幻渲染思路的 VSS 原型对照（2026-10-14）**

后续整合与更正：常驻记录已完成正式实现，见 [正式整合与复测](../../vss-engine-render-integration-20261014.md)。本报告的历史森林输入存在源叶面／代理 CUTOUT 标记不一致，质量判断请使用后续材料一致的复测结果；原始实验数据保持不变。

测试已完成。常驻候选记录的局部复用在指定更新压力下有明确 CPU 与完整提交收益；粗树冠代理有 GPU 收益，但没有证明能保留当前树叶孔隙和覆盖密度。两项都保留为独立实验，没有进入生产源码或发布 JAR。

这是借鉴引擎设计思路的 VSS 原型与当前 VSS 对照，没有运行 Unity／Unreal 引擎，也没有移植完整 Nanite。两个平台按相同输入分别编译，最终 GPU 测量串行执行；初次并行启动的 GPU 数据废弃。NeoForge 使用 JDK 21，Forge 使用 JDK 17。显卡为 RTX 4070 Ti SUPER，OpenGL 4.6，NVIDIA 572.70。没有启动、退出、采样或替换 Minecraft 客户端，没有提交／推送 GitHub。

**常驻记录复用：实际减少了什么**

原型沿用现有 Scene、PreparedPass、MDI、arena 与当前帧 Hi-Z，只复用未变的候选记录：以原始 Draw 身份、packed 身份、局部 mesh/GPU revision、相机位置和 horizon 校验。全球版本变化时仍扫描记录，但不反复创建相同的 normalized Draw；下游 PreparedPass 因身份稳定可复用原条目。删除和换世界清空引用。它还没有实现稳定 GPU Scene ID、脏瓦片队列或零扫描的常驻场景表。

输入维护与正式 PreparedPass 一起测量，双方使用相同场景快照；不包含生成、快照生产、OpenGL 提交及 GPU 等待。81 次记录前预热 48 次，逐轮交替顺序。下表单位 ms/次，中位数。

| 平台 | 候选数 | 每轮替换记录 | 当前路径 | 复用原型 | 耗时变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| neoforge | 4096 | 1 | 1.6323 | 0.7407 | -54.6% |
| neoforge | 8192 | 1 | 4.2330 | 1.9357 | -54.3% |
| neoforge | 4096 | 4096 | 1.8780 | 2.0595 | +9.7% |
| neoforge | 8192 | 8192 | 3.9958 | 4.4024 | +10.2% |
| forge | 4096 | 1 | 1.7531 | 0.8027 | -54.2% |
| forge | 8192 | 1 | 4.0179 | 1.9238 | -52.1% |
| forge | 4096 | 4096 | 1.8766 | 1.9278 | +2.7% |
| forge | 8192 | 8192 | 3.9196 | 4.1110 | +4.9% |

负值表示减少。局部替换时分配也显著减少；完整替换时新增版本检查／缓存记录使时间和分配增加，所以不能直接全场景启用。CPU 微基准的 fixture 共享 packed 输入、没有 GPU 分配，不作为 GPU 选择实际启用的证据。

**包含 GPU 完成的提交对照**

另一个独立测试使用上传后的 MODEL_UV 合成地形、生产 PreparedPass、OpaqueBatches、MDI 和当前帧 Hi-Z。对照为当前生产 IndirectBatch；候选为同一源码仅改用复用输入的实验副本。每帧让一个不属于当前集合、但已 trackSubmission 的 mesh 改变全局提交 epoch；单帧检查断言 epoch 确实增加。相机原地转向；两条路径按各自条件执行真实 GPU 命令选择，并断言有选择帧。

这刻意模拟高频无关资源更新，不表示所有游戏帧都发生该更新。计时每批 32 帧，8 批预热后测 21 批，批边界等待 GPU 完成，交替 A/B 顺序。下表为总墙钟时间除以 32 的中位数，不能把它换算成实际游戏 FPS。

| 平台 | 地形瓦片 | 当前 ms/次 | 复用 ms/次 | 减少 |
| --- | ---: | ---: | ---: | ---: |
| neoforge | 3072 | 3.5774 | 1.8161 | 49.2% |
| neoforge | 4096 | 6.1901 | 2.9584 | 52.2% |
| forge | 3072 | 3.7735 | 1.6474 | 56.3% |
| forge | 4096 | 5.5831 | 2.2794 | 59.2% |

每平台 4 个场景 × 3 个检查帧，12 对颜色及 12 对深度全数一致。CPU 局部替换、移动、horizon、payload 撤销、引用释放的检查也通过。静止／禁用选择是控制场景，没有稳定的大幅收益承诺。Iris、水面及实际客户端下的交接未接入该原型。

**树冠 HLOD／粗体素代理**

从历史捕获的 3,890 个网格中找出 189 个满足筛选条件的森林瓦片，选择 6 个叶面较多且涵盖不同叶材质的样本。选取不是随机抽样，不能外推为全世界收益。使用同一 1.21.1 原版客户端 JAR 提取的真实树叶 alpha 纹理作为两平台共同测试图集；它不是用户当前资源包，也不是各平台实时图集。其他材质在原型和基线中保持相同。

原型从叶面附近重建 2／4 方块二值占据格，保留边缘 8 方块原几何，树干、地形和水体源记录不被替换。这个试验没有完整树木体积快照、覆盖率／法线分布或 Nanite 体素光栅化；用的是现有生产顶点／片元 shader 和 MDI。它属于“简单二值树冠代理”的验证，不能证明完整引擎方案的质量和收益。

六个样本源不透明面数 191,776。代理源记录分别为 162,925／136,657 面，减少约 15.0%／28.7%；随后两条路径各自执行已有地形显示 LOD。性能测试为 512×512、每个样本一次提交，预热 24 次、交替 81 次，单独比较无 Hi-Z 和完整当前帧 Hi-Z。以下是六个独立用例 GPU 时间中位数之和，单位 ms，不是同一游戏帧。

| 平台 | 距离 | Hi-Z | 当前 | 2 方块代理 | 4 方块代理 |
| --- | ---: | --- | ---: | ---: | ---: |
| neoforge | 1024 | 开 | 0.152576 | 0.136192 | 0.123904 |
| neoforge | 4096 | 开 | 0.145408 | 0.117760 | 0.090112 |
| forge | 1024 | 开 | 0.152576 | 0.136192 | 0.122880 |
| forge | 4096 | 开 | 0.144384 | 0.117760 | 0.089088 |

4 方块代理在 1,024 方块处超出本试验的 2 px 位移预算，数字只反映潜在工作量，不计为可启用收益。代理与原几何没有要求跨 LOD 像素一致；每个平台对 6 样本 × 3 距离 × 2 朝向 × 3 表示的直绘／MDI 做了 108 对颜色和 108 对深度一致检查，通过。

另外把树叶单独绘制，统计同视角覆盖掩码交并比和新增／丢失覆盖像素。满足距离保护的 2 方块代理有 24 个视角，IoU 中位数约 0.815；4 方块代理有 12 个视角，约 0.651。新增覆盖占原覆盖的中位数约 22%／54%，丢失覆盖中位数为零，显示主要问题是变实和孔隙填充。4,096 方块处原覆盖只有 5～23 像素，IoU 对单像素很敏感；图像也保留了 1,024 方块处 61～439 像素的对照。

![同视角树冠代理及放大对照](../../engine-render-20261014-forest-comparison.png)

近处、望远镜、接缝、morph、部分归属条件只检查了实验选择门槛；没有将代理接入 Renderer 的真实父子／Voxy 交接。因此不能据此声称完成了动态交接验证。下一版需要同源体积、覆盖率及轮廓约束，并在有完整回退的版本事务内发布。

**保留和后续顺序**

先推进稳定记录和细粒度版本失效，但上线前需要解决完整替换退步、限制额外缓存引用、测试实际资源更新比例和 Iris 路径。森林继续研究带覆盖率的树冠表示，先通过多视角质量对照，再接真实归属和接缝；当前二值代理保留为反例。已有常驻 GPU 选择控制也重跑完成，但属于已有机制的复测，不能算作本轮新收益。

没有测试完整 Unity／UE 引擎、重复部件实例化、时序遮挡改动或 Render Graph；没有把此前无净收益的深层级或 Mesh Shader 重新启用。

**复现与证据**

两个仓库的 tools/prediction/engine-render-20261014 保存相同原型、输入、各平台原始 CSV、最终日志和 JUnit XML；同名 JSON 包含分组中位数、样本数、计时分位、源码／输入 SHA256 和限制。每个平台 6 个测试，失败／错误／跳过均为零。

```powershell
# NeoForge（JDK 21）
./gradlew.bat --offline --no-daemon -I tools/prediction/engine-render-tests.gradle engineInputTest engineForestGpuTest engineResidentGpuTest engineMaintenanceGpuTest --console=plain

# Forge（JDK 17）；两平台 GPU 测试须串行
./gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/engine-render-tests.gradle engineInputTest engineForestGpuTest engineResidentGpuTest engineMaintenanceGpuTest --console=plain
```

官方参考：[Unity DOTS Instancing](https://docs.unity3d.com/6000.0/Documentation/Manual/dots-instancing-shaders.html)、[虚幻 Mesh Drawing Pipeline](https://dev.epicgames.com/documentation/en-us/unreal-engine/mesh-drawing-pipeline-in-unreal-engine)、[HLOD](https://dev.epicgames.com/documentation/en-us/unreal-engine/world-partition---hierarchical-level-of-detail-in-unreal-engine)、[Nanite Foliage（Experimental）](https://dev.epicgames.com/documentation/en-us/unreal-engine/nanite-foliage)。
