# VSS 参照 Voxy 的渲染架构补齐与验证（2026-10-15）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

> 此交付随后在真实森林场景中暴露了 GPU scene 容量拒绝与反复重建的严重性能回退。已另行修复，当前包和验证见 [秒级卡顿回归修复](prediction-one-fps-regression-20261015.md)；请使用 `one-fps-fix-20261015` 目录中的新包。

本报告接续同日的 `prediction-architecture-audit-20261015.md`，描述补齐后的生产实现。先前报告中“同源父层尚未实现”、旧 GPU scene 准入门槛和植被／接缝未独立计时等结论，是本轮修改之前的状态，以本报告为准。

修改已经同步到 NeoForge 1.21.1 与 Forge 1.20.1，保留两端的事件、矩阵、Minecraft API 和历史兼容差异。验证包括 CPU 回归、实际隐藏 OpenGL 上下文、像素／深度对照、生产构建和 JAR 字节码审计。本轮没有游戏内 FPS 采样。

## 参考了 Voxy 的哪些做法

参考本地 `voxy-263-git2` 的 `common/world/WorldUpdater.java`、`common/world/other/Mipper.java` 与 `shaders/lod/hierarchical/traversal_dev.comp`：

- 子区域变化向直接父层传播，合并重复通知；相机变化不作为重建静态内容的理由。
- 粗层从现有细数据聚合，并携带明确来源；细层和粗层在同一个选择过程内保持互斥。
- 常驻 GPU 记录保存空间边界、来源和命令，当前视角的误差、视锥、方向和遮挡由 GPU 判断。
- 细层不齐或来源无法证明时保留精确覆盖，不能通过跳过必要几何换取更低绘制量。

VSS 目前仍采用地形列和四叉树；本轮实现的是有来源证明的列 mip，不是把 VSS 改写为 Voxy 的完整三维体素树。水面、复杂体积和特征几何的限制详见后文。

## 七项目标的更新状态

| 目标 | 当前生产行为 | 仍需保留的边界 |
| --- | --- | --- |
| 1. Voxy 索引和覆盖列统一边界 | Scene 资格、提交范围与驻留共用 `PredictionAuthoritativeCoverageView`；局部覆盖变化只刷新可能受影响的资格，撤销后恢复预测；Forge 已同步这条增量路径 | VSS 仍管理区域候选、父子 owner 与混合瓦片范围。尚未将数据组织完全改为仅存 Voxy 缺失空间的稀疏体素树；整块资格和局部范围过滤承担不同职责，不能删除必要掩码 |
| 2. 稳定 GPU scene | 固定 metadata 槽、命令区间和节点槽，局部变化更新；普通转向／移动不重编码静态输入；准入改为支持 GPU 选择、至少 64 个地形瓦片和 16,384 个候选面 | 共享 arena 的不透明路径收益最完整；小场景、独立 buffer、GL 能力不足或 scene 被拒绝时仍使用精确提交路径 |
| 3. 同源真实父子 LOD | 四个不可变子网格的列数据生成父 mip，记录子版本和 capture epoch；发布拒绝过期来源；GPU 根据当前投影在完整四子和父替代之间互斥选择 | 只有边缘可证明安全、完整归属、无水面／特征且 morph 结束的父族启用替代；复杂体积、空腔、悬空结构等保守保留细层。来源证明不作为磁盘缓存中的永久事实 |
| 4. GPU 细粒度选择 | GPU 选择父子层级、四档显示表示、meshlet 视锥、方向、horizon 和遮挡；resident pass 跳过 CPU 对方向和显示档位的重复扫描 | CPU 保留区域成员、版本、覆盖范围、预算和资源生命周期。普通后端仍需自己的精确选择；不是所有特殊入口都改成同一个 shader |
| 5. 有限阶段 Hi-Z | 保持 opaque submission 的最多两次重建预算；无当前有效深度时仍可使用 GPU 视锥／方向／LOD 选择 | 无深度时关闭遮挡测试，不引用旧深度猜测遮挡；不将 opaque submission 的预算夸大成所有多视口调用合计恒为两次 |
| 6. 水面、植被和接缝计时／预算 | 水面保留独立透明通道；植被和接缝最终绘制有独立 query pool，LOD 选择也独立计时；软预算影响可选上传与生成并发 | 预算不跳过已显示几何和必要交接；地形、遮罩与接缝仍按同一版本提交。特殊回退后端不强行拆成与 resident scene 完全相同的三阶段 |
| 7. 纯需求计算后台化 | 沿用版本化 plan／demand／schedule／loading／seam worker；本轮父层聚合也在 worker，锁内仅捕获引用和版本 | 可变世界快照、发布校验、GPU 上传／释放及安全交接仍由适当的客户端线程执行 |

这些是主路径的实现状态，不以文件数计算“完成百分比”，也不表示所有画面、第三方渲染后端和复杂体积都已经与 Voxy 等价。

## 同源父层与交接

新增 `PredictionParentLod`。它在 manager 锁内取得四个子网格的不可变引用及版本，随后在线程池聚合；已有四子可复用时不再为父网格发起新的密度采样。父网格不写入独立来源的地形缓存，也不对派生结果重复进行 exterior enrichment。

聚合保留窄山脊，边界采用已有源值。误差对照所有细列和粗格四角，并累加上一级误差；外边缘不能证明连续、体积不等价或存在悬空／下层结构时，阻止该父层参与 GPU 替代。此策略会少启用一些粗层，目的是避免粗层切换重新制造边缘漏缝。

发布前再次验证四子 revision、dirty 状态和 capture epoch，验证失败就丢弃过期结果。子更新只标记直接父层；已有 dirty 状态合并通知，不反复增加 epoch，也不提前撤掉旧网格。父替代候选纳入 GPU 引用保护；细层不齐、归属部分撤销或边界事务尚未发布时，禁用不安全替代。

## 静态记录与 GPU 选择

`PredictionGpuSelectionPolicy` 删除旧的静止／移动／转向策略和可见面数量准入，scene 资格不再随相机运动在不同实现之间反复切换。新门槛为 64 个地形瓦片、16,384 个候选面；仍遵守 GL 支持与显式禁用开关。

`PredictionGpuSelectionInput` 保留全方向静态输入。resident `PreparedPass` 不再每帧重算方向、植物／水草／地形显示档位。四档已上传几何通过区间 membership 复用；GPU 根据当前相机、投影、视口和保守屏幕误差选择一档，而不是复制四套几何。

新增 `PredictionGpuLodGraph`，每个节点 64 字节，来源和命令记录使用固定槽。网格、归属、驻留、ranges 或 morph 到期才更新相关内容。拓扑缓存仅受本 scene 成员的驻留变化影响，其他区域的上传或 metadata 变化不会触发全图重建。

scene 命令和节点总保留上限为 64 MiB。容量拒绝和重复拒绝缓存输入 token，输入未改变时不每帧重复分配失败。shader 不支持、编译失败、scene 容量拒绝或选择失败时，回退命令只保留精确源；粗父代理和附加档位清零，防止父子／多档重复绘制。SSBO 绑定 0–5、纹理、sampler 和 program 在离开选择过程后恢复。

## 水面、植被与接缝预算

resident scene 通过 render kind 分普通、已知植被 sprite 和接缝提交，同一轮的三个阶段共享 GPU LOD 选择结果。分类在后台打包时建立，避免在渲染线程逐面识别；水面继续使用原独立池和透明顺序。

`GPU_LOD_SELECT`、`VEGETATION_DRAW` 和 `SEAM_DRAW` 使用独立查询额度，避免前面的不透明批次耗尽查询槽。每 30 帧异步采集预算所需的水面／植被／接缝数据，即使未开启 debug 也能反馈压力；不等待尚未完成的 GPU 查询。

`PredictionRenderWorkBudget` 的水面／植被软目标各为 2 ms，接缝为 1 ms，使用移动均值并在 2 秒后过期。超出目标时可选上传收缩至每帧 4 个瓦片／1 MiB，保留首项进度；植被生成并发最多为 1。必要接缝、归属交接和缺失覆盖仍完整处理，不把软目标作为删除当前画面几何的依据。

## 删除与合并的旧路径

- 删除 `PredictionGpuSelectionPolicy` 的旧 `residentFrame`、`residentMotion`、`turning`、`choose`、空 `clear` 与相应运动／可见面门槛。
- 删除静态 GPU 输入中的相机／horizon 缓存身份与恒真视角判断；当前视角由 shader 使用。
- resident pass 不再运行 CPU 方向和显示档位扫描；普通后端保留必要实现。
- 延续上一轮删除的 `PredictionSubmissionCoverage` 和 TileManager 同步需求／覆盖入口，最终 JAR 字节码审计确认没有重新打入这些实现。
- 修复 scene clear 顺序，避免材质变化时清掉刚创建的 LOD 图；重复上传和覆盖撤销后的回退仍参与回归。

保留的 owner mask、published／pending 接缝状态、透明排序及 direct／Iris 安全后端有明确职责，不因外观相近而删除。测试和工具里的历史对照实现不进入生产 JAR。

## 验证与离线收益

测试入口为 `tools/prediction/voxy-alignment-tests.gradle`，构建目录为 `build-voxy-alignment-20261015/`。两端分别使用 JDK 21 和 JDK 17：

```powershell
.\gradlew.bat --no-daemon -I tools/prediction/voxy-alignment-tests.gradle architectureAuditTest architectureAuditGpuTest jar --console=plain
```

Forge 的生产打包同时完成 `reobfJar`。GPU 测试串行运行，使用实际 NVIDIA GeForce RTX 4070 Ti SUPER OpenGL 上下文。

| 平台 | CPU 回归 | OpenGL 回归 | 失败／错误／跳过 | JAR 字节数 | Java class major |
| --- | --- | --- | --- | --- | --- |
| NeoForge 1.21.1 | 35 组／234 项 | 10 组／11 项 | 全部 0 | 6,132,949 | 65 |
| Forge 1.20.1 | 34 组／231 项 | 10 组／11 项 | 全部 0 | 6,803,326 | 61 |

新增或扩展回归覆盖：真实 production enqueue 复用四子、不新增密度调用、过期来源在内存 retain 前拒绝、dirty 父通知合并、负坐标、窄山脊和平台误差；GPU 近／远／放大视角父子互斥、缺子／部分归属／撤回 GPU 驻留安全回退；四档 membership 与覆盖裁剪、近远近恢复、静态记录零重编码／零上传；像素／深度对照、SSBO 范围绑定恢复和预算过期恢复。

固定 3072 个瓦片的离线流水吞吐对照，21 轮取中位数、每轮 32 帧：

| 场景 | 现有 ordered 提交 | 固定 scene | 每 pass 时间减少 |
| --- | --- | --- | --- |
| 几何不变、持续转向 | 0.436541 ms | 0.285422 ms | 约 34.6% |
| 每帧局部 metadata 改变 | 1.657563 ms | 1.192075 ms | 约 28.1% |

这是隔离场景的流水吞吐，包含 CPU／GPU 重叠，不是独立渲染线程 CPU 计时，也不是游戏 FPS 或整个 VSS 绘制耗时。它证明此场景中固定记录方向有收益，不能据此承诺森林、跑图或第三方 shader 的固定帧率提升。

## 交付产物

两个仓库各自的 `lib/voxy-alignment-20261015/` 保存生产 JAR、`validation.json` 和 `SHA256SUMS.txt`。联合审计检查了 18 个共享生产源码的一致性、新类入包、旧入口移除、测试／基线未入包、Java 字节码版本，以及五个平台原生资源的哈希。

- NeoForge：`vss-0.3.5-neoforge-1.21.1.jar`，SHA256 `733f7a5fca0f47188033947c3b3d4abbaad0f29d196ba8e504c578dd227db913`。
- Forge：`vss-0.3.5-forge-1.20.1.jar`，SHA256 `653bc98ac1a6d9ba98b2816ce9b554dcdb45b16399d63864be8274f61a06141d`。

游戏内实际收益仍需用相同存档、位置、视角、资源包和配置对照确认；建议特别观察森林静止／转向、Voxy 接管与撤销、含水边界、窄山脊和传送后的完整覆盖。本轮未将离线通过解释为所有模组和 shader 均无条件兼容。
