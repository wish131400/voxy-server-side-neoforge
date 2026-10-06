# Voxy 与预测 LOD 按区域交接（2026-10-05）

## 本轮目标与上一轮的区别

用户明确要求使用 VSS 客户端索引区分 Voxy 已有区域与缺失区域，仅在缺失区域使用预测。上一轮为排除闪烁，将 Voxy 上传节点直接关闭预测的路径移除，交接主要依赖本帧深度。这保留了回退，但更高的预测地形仍可能盖住已经存在的 Voxy 地形，不满足区域互斥目标。

本轮把已有客户端数据索引接入区域所有权；保留上一轮的离屏深度修复，并继续避免用每帧 Voxy traversal 或上传节点替换决定整块预测显隐。

## 已有框架与断点

| 模块 | 已有作用 | 本轮处理 |
| --- | --- | --- |
| `VoxyCompat.LocalSectionIndex` | 维护本次摄入确认与 Voxy 存储发现，返回 PRESENT / MISSING / UNKNOWN | 重用现有接口，不在渲染线程执行数据库扫描。 |
| `ClientPredictionState` / `PredictionExactCoverageIndex` | 后台扫描、正向稀疏页、确认时间与版本 | 新发现的存储也等待 2 秒；重复确认保持首次时间；扫描不再因已有网络确认跳过探测。 |
| `PredictionExactCoverageMask` | 异步生成并上传按区块的 R8 索引纹理 | 重用。刷新过程中保留完整旧快照，不先清空当前掩码。 |
| `PredictionTileManager` | `fullyAuthoritative` 主要控制地表细节，基础地形仍进入计划 | 完全被稳定索引覆盖且整个可能高度范围都在 Voxy 距离内的瓦片退出生成计划与驻留；取消旧任务发布和磁盘恢复。 |
| `PredictionTerrainProgram` | 索引只辅助深度和高度差判断 | 新增空间区域裁剪，稳定索引覆盖区域内的预测地形、水、熔岩、冰统一让位。 |

## 运行规则

1. 未知或缺失区块保留预测。新 PRESENT 记录经过 2 秒交接等待时间；连续确认不会反复重置等待时间。
2. 稳定 PRESENT 区块在 Voxy 距离内归 Voxy 所有，与其本帧是否写入某个像素无关。Voxy 的祖先节点替换、空 mesh 和上传队列更新不会使这个区域在两种来源间反复切换。
3. 着色器按当前片元所在的世界区块裁剪，墙面向所属列内偏移 0.01 方块，避免边界落入相邻列。地形高差不再使预测越过区域所有权。
4. 完整瓦片需要所有区块都已稳定确认才退出生成。混合瓦片保留，已知部分被 GPU 裁剪，缺失部分继续显示；因此不能将“保留混合瓦片的网格缓冲”视为画面仍然重叠。
5. 渲染范围收缩、视点在高空超出范围或明确 MISSING 会恢复预测资格。整块释放采用保守的三维包围盒检查；单个片元采用三维距离检查。交接距离留出 2 个区块的边缘余量。
6. `voxyOwnedTiles` 在释放瓦片前发布，已有任务即使完成也不能重新发布已被 Voxy 接管的瓦片；未知区域仍保留原来的父子 LOD 回退机制。

诊断字段更新为 `handoff=region-index+frame-depth` / `predictionHandoff=region-index+frame-depth`，预测调度诊断新增 `voxyOwnedTiles`。

## 验证

`PredictionRegionalOwnershipTest` 检查本地存储确认不依赖本次网络 ACK、整块退出驻留、旧任务不能重新生成、单区块缺失恢复、等待时间、跨页负坐标、维度隔离及高空/距离边缘。

生产 GPU 回归从真实的 `PredictionExactCoverageIndex.snapshot` 生成半区覆盖纹理，对同一张大瓦片连续 120 帧切换主深度和地形/水/熔岩/冰。普通与 Iris 管线均验证：已知半区始终不画预测，未知半区保持预测；范围关闭、高空超距和索引删除能够恢复片元。原深度播种、原始 GL 状态恢复、批处理、Voxy 边界和实际 Voxy GLSL 回归继续通过。

结果：定向 CPU 回归 45 项通过；独立 GPU 套件 6 项通过、无跳过；全量构建成功，1367 项测试中 1293 通过、74 跳过、0 失败、0 错误。跳过项包括未在全量构建中启用的 GPU 套件、性能实验和缺少额外输入的可选兼容测试，不算通过。

全量构建命令：

```powershell
.\gradlew.bat build -I tools/prediction/regression-tests.gradle `
  '-PvssVoxyJar=F:\NovaEngineering-World整合包\.minecraft\versions\1.21.1-NeoForge_21.1.249\mods\voxy-0.2.15-beta.jar' `
  --console plain
```

GPU 使用 `gpu-tests.gradle` 和 `regression-tests.gradle` 两个 init script，同一个 Voxy JAR 参数，筛选 `PredictionRenderTargetGpuTest`、`PredictionProductionBatchGpuTest`、`PredictionVoxyBoundaryGpuTest`、`PredictionDepthSeedGpuTest`、`PredictionRawStateHandoffGpuTest` 与 `StrictVoxyShaderFixtureGpuTest`。普通提交与实验实例提交均执行了新增区域检查。

产物：`lib/vss-0.3.5-neoforge-1.21.1.jar`，5,484,804 字节。SHA-256：`5d73c1509ff7284bdb379beae993b7cb8ec9a93f751be147837449ff074405f3`。本轮 8 个生产源文件对应的 36 个 class 已与编译输出逐字节核对，未混入新增测试类或 JUnit 类；相关文件的 `git diff --check` 通过。上一轮 JAR 已保存在本轮 `before/` 目录。

## 边界与实机验证

本轮重用现有索引的 PRESENT 定义。Voxy 磁盘发现来源仍是 LOD0 的 32×32 世界 section，在已有适配层中映射为 2×2 Minecraft 区块；本轮没有把它升级为逐体素或逐竖向 section 的完整性证明。2 秒等待同样是给异步处理留出的时间，不是 GPU 上传完成栅栏。极慢加载或索引误报仍需结合实机诊断区分，不能用测试结果声称这两点已获得严格保证。

本轮交付本地构建包，不自动替换整合包内 JAR。GPU 回归使用真实显卡与生产 shader，但未以新包重启原录像场景，实际视觉结果与 FPS 仍待该场景验证。

证据目录：`build/region-handoff-20261005/`，包括本轮修改前备份、独立补丁、CPU / GPU / 全量构建日志和 XML、打包核对与 SHA-256。
