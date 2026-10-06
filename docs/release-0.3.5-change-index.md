# 0.3.5 累计变更核对索引

本索引支撑 [更新日志](../CHANGELOG.md) 中自 0.3.4 以来的累计记录。按两个平台各自基线与已提交发布快照逐路径核对，再将源码、回归入口和专项报告对应到最终行为；调查中的临时方案不能替代最终代码。

## 固定范围与复核入口

| 平台 | 0.3.4 基线 | 发布源码快照 | 完整比较 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | [bbc4f3d](https://github.com/wish131400/voxy-server-side-neoforge/commit/bbc4f3d74bec2b2e1fc1661520d2f493a0a4b232) | [f87fc8c](https://github.com/wish131400/voxy-server-side-neoforge/commit/f87fc8ca86b30efb1e1c42c48cddc035ae7d2bea) | [基线至发布快照](https://github.com/wish131400/voxy-server-side-neoforge/compare/bbc4f3d74bec2b2e1fc1661520d2f493a0a4b232...f87fc8ca86b30efb1e1c42c48cddc035ae7d2bea) |
| Forge 1.20.1 | [da9725a](https://github.com/wish131400/voxy-server-side-forge/commit/da9725aecdc1ff99e347116afa0074b56392bccf) | [f09bba8](https://github.com/wish131400/voxy-server-side-forge/commit/f09bba85faab45c24eab48d3b67c976fd4c1337e) | [基线至发布快照](https://github.com/wish131400/voxy-server-side-forge/compare/da9725aecdc1ff99e347116afa0074b56392bccf...f09bba85faab45c24eab48d3b67c976fd4c1337e) |

上述快照已完成代码提交、测试与 JAR 验收。此次日志扩充及本索引本身不计入以下源码范围；工作区后续未提交开发同样不计入。客户端文件、采样媒体、本地构建产物和忽略文件不属于 Git 变更清单。

复核净变更使用 `git diff --name-status <基线> <发布快照>`；完整比较会包含删除文件。两端共享逻辑只记录为一项功能，不将平台文件数量相加当作独立优化数量。

## 净变更路径数量

| 类别 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | ---: | ---: |
| 生产 Java | 178 | 179 |
| 生产资源 | 10 | 10 |
| Rust 生产代码 | 10 | 7 |
| Java 测试源、夹具与资源 | 201 | 203 |
| Rust 集成测试 | 2 | 2 |
| 文档与调查记录 | 46 | 45 |
| 构建、平台原生资源、工具与其他 | 27 | 26 |
| **总计** | **474** | **472** |

NeoForge 1.21.1：新增 234、修改 232、删除 8 个路径。

Forge 1.20.1：新增 232、修改 232、删除 8 个路径。

Java 测试类别包含测试类、基准、辅助夹具、存根和资源，因此不是测试方法数量；Rust 类别按 `src/` 与 `tests/` 分开。文档与工具的数量也不是功能数量。

相对各自 0.3.4 的生产路径差异为：NeoForge 额外变更 Rust 的 `density/cache_order.rs`、`density/height_plan.rs`、`density/surface_plan.rs`；Forge 额外变更 `PredictionMotionPace.java`。这是基线差异，不表示这些文件在另一平台不存在。协议、Java 版本、网络注册、Voxy 接口及少数测试／工具保留平台适配。

## 关键实现与回归入口

下表用于逐项定位实现和测试。测试入口可能含条件跳过或未在本次选择的 GPU 用例，运行结果以 [提交验收记录](release-0.3.5-validation.md) 为准。专项性能采样只描述各自版本与场景，不推算固定 FPS 或全整合包收益。

| 主题 | 关键实现 | 对应回归入口 |
| --- | --- | --- |
| 稳定真实覆盖、局部归属失效 | [StrictVoxyCoverageRegions.java](../src/main/java/dev/xantha/vss/compat/StrictVoxyCoverageRegions.java)、[PredictionExactCoverageIndex.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionExactCoverageIndex.java)、[PredictionSubmissionCoverage.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionSubmissionCoverage.java) | [PredictionSubmissionCoverageTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionSubmissionCoverageTest.java)、[StrictVoxyCoverageRegionsTest.java](../src/test/java/dev/xantha/vss/compat/StrictVoxyCoverageRegionsTest.java) |
| 预测父子 LOD 与缺失回退 | [PredictionCoverageOwners.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCoverageOwners.java)、[PredictionRenderResidency.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionRenderResidency.java) | [PredictionCoverageOwnersTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageOwnersTest.java)、[PredictionHandoffRegressionTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionHandoffRegressionTest.java)、[PredictionRegionalOwnershipTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionRegionalOwnershipTest.java) |
| Voxy 上传回收竞态 | [StrictVoxyUploadMixin.java](../src/main/java/dev/xantha/vss/mixin/voxy/StrictVoxyUploadMixin.java)、[StrictVoxyPipeline.java](../src/main/java/dev/xantha/vss/compat/StrictVoxyPipeline.java) | [StrictVoxyUploadOwnershipTest.java](../src/test/java/dev/xantha/vss/compat/StrictVoxyUploadOwnershipTest.java) |
| 静态批次索引与水面入口 | [PredictionIndirectBatch.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java)、[PredictionRenderer.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java) | [PredictionProductionBatchGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionProductionBatchGpuTest.java)、[PredictionPassStorageTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionPassStorageTest.java) |
| 真实深度、透明合成与 GL 状态 | [PredictionNormalDepthBridge.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionNormalDepthBridge.java)、[PredictionNormalTranslucencyBridge.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionNormalTranslucencyBridge.java)、[PredictionGlState.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionGlState.java) | [PredictionVoxyDepthConventionGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyDepthConventionGpuTest.java)、[PredictionDepthSeedGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionDepthSeedGpuTest.java)、[PredictionRawStateHandoffGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionRawStateHandoffGpuTest.java) |
| 当前帧 Hi-Z、粗组筛选与收益选择 | [PredictionOcclusionCuller.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionOcclusionCuller.java)、[PredictionOcclusionPolicy.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionOcclusionPolicy.java)、[PredictionMeshletBounds.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionMeshletBounds.java) | [PredictionOcclusionGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionOcclusionGpuTest.java)、[PredictionOcclusionPolicyTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionOcclusionPolicyTest.java)、[PredictionMeshletBoundsTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionMeshletBoundsTest.java) |
| 稳定可见列表与静态提交重放 | [PredictionVisiblePlan.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionVisiblePlan.java)、[PredictionOpaqueBatches.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionOpaqueBatches.java)、[PredictionSpatialOrder.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionSpatialOrder.java) | [PredictionSpatialOrderTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionSpatialOrderTest.java)、[PredictionProductionBatchGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionProductionBatchGpuTest.java) |
| 后台本机上传暂存 | [PredictionUploadStaging.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionUploadStaging.java)、[PredictionGpuTile.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionGpuTile.java) | [PredictionUploadStagingTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionUploadStagingTest.java)、[PredictionUploadStagingGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionUploadStagingGpuTest.java)、[PredictionCompressedUploadGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionCompressedUploadGpuTest.java) |
| 水体去重、专用页与软 GPU 驻留 | [PredictionTerrainArena.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainArena.java)、[PredictionGpuEncoding.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionGpuEncoding.java)、[PredictionGpuResidency.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionGpuResidency.java) | [PredictionAquaticSubmissionTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionAquaticSubmissionTest.java)、[PredictionGpuResidencyTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionGpuResidencyTest.java)、[PredictionGpuEncodingTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionGpuEncodingTest.java) |
| 保守显示简化、平顶合并与密集网格 | [PredictionAdaptiveDisplayGrid.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionAdaptiveDisplayGrid.java)、[PredictionDisplayGeometry.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java)、[PredictionMeshBuilder.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionMeshBuilder.java) | [PredictionAdaptiveDisplayGridTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionAdaptiveDisplayGridTest.java)、[PredictionAffineMergeTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionAffineMergeTest.java)、[PredictionSegmentedMeshGpuTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionSegmentedMeshGpuTest.java) |
| 植被三档、水草前缀、高草 cutout | [PredictionVegetationSelection.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationSelection.java)、[PredictionVegetationTraits.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationTraits.java)、[PredictionAquaticLod.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionAquaticLod.java)、[VSSVoxyOptionsIntegration.java](../src/main/java/dev/xantha/vss/client/VSSVoxyOptionsIntegration.java) | [PredictionVegetationSettingsTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationSettingsTest.java)、[PredictionGrowingGrassTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionGrowingGrassTest.java)、[PredictionVegetationRunsTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationRunsTest.java) |
| CPU 后台采样、入队预约与加载进度 | [PredictionCpuBudget.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCpuBudget.java)、[PredictionTileManager.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java)、[PredictionLoadingProgress.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionLoadingProgress.java)、[GenerationSchedulingPolicy.java](../src/main/java/dev/xantha/vss/networking/server/generation/GenerationSchedulingPolicy.java) | [PredictionCpuAdmissionIntegrationTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionCpuAdmissionIntegrationTest.java)、[PredictionCpuLeaseLifecycleTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionCpuLeaseLifecycleTest.java)、[PredictionLoadingProgressIntegrationTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionLoadingProgressIntegrationTest.java) |
| Java 密度编译、气候和 TerraBlender | [DensityGraphCompiler.java](../src/main/java/dev/xantha/vss/client/prediction/DensityGraphCompiler.java)、[PredictionClimateSampler.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionClimateSampler.java)、[TerrablenderUniqueness.java](../src/main/java/dev/xantha/vss/client/prediction/TerrablenderUniqueness.java) | [DensityGraphCompilerTest.java](../src/test/java/dev/xantha/vss/client/prediction/DensityGraphCompilerTest.java)、[PredictionClimateSamplerTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionClimateSamplerTest.java)、[TerrablenderUniquenessTest.java](../src/test/java/dev/xantha/vss/client/prediction/TerrablenderUniquenessTest.java) |
| 虚拟世界、放置器与 Rust 增量装饰 | [PredictionColumnVolume.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionColumnVolume.java)、[PredictionPlacementExecutor.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutor.java)、[RustVegetationStage.java](../src/main/java/dev/xantha/vss/client/prediction/RustVegetationStage.java)、[RustVegetationDescriptors.java](../src/main/java/dev/xantha/vss/client/prediction/RustVegetationDescriptors.java) | [PredictionDecorationQueryTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionDecorationQueryTest.java)、[PredictionPlacementExecutorTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutorTest.java)、[RustVegetationDeltaUploadTest.java](../src/test/java/dev/xantha/vss/client/prediction/RustVegetationDeltaUploadTest.java) |
| Rust 颜色与显示地表 | [RustWorldgenDocument.java](../src/main/java/dev/xantha/vss/client/prediction/RustWorldgenDocument.java)、[biome.rs](../tools/rust/vss-native-core/src/biome.rs)、[terrain.rs](../tools/rust/vss-native-core/src/terrain.rs)、[display.rs](../tools/rust/vss-native-core/src/backend/display.rs) | [biome_colors.rs](../tools/rust/vss-native-core/tests/biome_colors.rs)、[display_surface.rs](../tools/rust/vss-native-core/tests/display_surface.rs) |
| FreeTerraForged 新旧适配 | [FreeTerraForgedCompat.java](../src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedCompat.java)、[FreeTerraForgedTerrainSampler.java](../src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedTerrainSampler.java)、[FreeTerraForgedNativeFilters.java](../src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedNativeFilters.java) | [FreeTerraForgedCompatTest.java](../src/test/java/dev/xantha/vss/client/prediction/FreeTerraForgedCompatTest.java)、[FreeTerraForgedNativeFiltersTest.java](../src/test/java/dev/xantha/vss/client/prediction/FreeTerraForgedNativeFiltersTest.java)、[FreeTerraForgedSnapshotTest.java](../src/test/java/dev/xantha/vss/networking/server/session/FreeTerraForgedSnapshotTest.java) |
| 稳定缓存身份、资源映射与城市暖恢复 | [PredictionCacheMigration.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCacheMigration.java)、[PredictionCacheMappings.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCacheMappings.java)、[PredictionMeshCodec.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionMeshCodec.java)、[PredictionMeshRestore.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionMeshRestore.java) | [PredictionPersistentMappingsTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionPersistentMappingsTest.java)、[PredictionWarmSurfaceRestoreTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionWarmSurfaceRestoreTest.java)、[PredictionMeshCodecTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionMeshCodecTest.java) |
| 延后写入、区域 I/O 与批量失效 | [PredictionCacheStorage.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCacheStorage.java)、[PredictionRegionStorage.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionRegionStorage.java)、[PredictionSampleStore.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionSampleStore.java) | [PredictionDeferredCacheWriteTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionDeferredCacheWriteTest.java)、[PredictionRegionStorageTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionRegionStorageTest.java)、[PredictionCaptureInvalidationBatchTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionCaptureInvalidationBatchTest.java) |
| 城市公共模板与旧调色板 | [LostCityPlanningReader.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityPlanningReader.java)、[LostCityStreetPlan.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityStreetPlan.java)、[LostCityLegacyPalettes.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityLegacyPalettes.java)、[PredictionCityGeometry.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionCityGeometry.java) | [LostCityPlanningReaderTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityPlanningReaderTest.java)、[LostCityStreetPlanTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityStreetPlanTest.java)、[LostCityLegacyPalettesTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityLegacyPalettesTest.java)、[LostCityReleaseMatrixTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityReleaseMatrixTest.java) |
| Lost Cities 旧缓存并发与规划锁 | [TimedCacheConcurrencyMixin.java](../src/main/java/dev/xantha/vss/mixin/lostcities/TimedCacheConcurrencyMixin.java)、[LostCitiesMixinPlugin.java](../src/main/java/dev/xantha/vss/mixin/lostcities/LostCitiesMixinPlugin.java)、[LostCityPlannerAccess.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityPlannerAccess.java) | [LostCityTimedCacheConcurrencyTest.java](../src/test/java/dev/xantha/vss/mixin/lostcities/LostCityTimedCacheConcurrencyTest.java)、[LostCityPlannerAccessTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityPlannerAccessTest.java) |
| 桥梁、高速路与水下铁路 | [LostCityInfrastructurePlan.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityInfrastructurePlan.java)、[LostCityExterior.java](../src/main/java/dev/xantha/vss/networking/server/compat/LostCityExterior.java)、[LostCityPreview.java](../src/main/java/dev/xantha/vss/common/worldgen/LostCityPreview.java) | [LostCityInfrastructurePlanTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityInfrastructurePlanTest.java)、[LostCityExteriorTest.java](../src/test/java/dev/xantha/vss/networking/server/compat/LostCityExteriorTest.java)、[LostCityHintsPayloadTest.java](../src/test/java/dev/xantha/vss/networking/payloads/LostCityHintsPayloadTest.java) |
| 请求空缺、排队续期与重复分片 | [LodRequestManager.java](../src/main/java/dev/xantha/vss/networking/client/LodRequestManager.java)、[StrictLodFrontier.java](../src/main/java/dev/xantha/vss/networking/client/StrictLodFrontier.java)、[ClientColumnTransferAssembler.java](../src/main/java/dev/xantha/vss/networking/client/ClientColumnTransferAssembler.java)、[RetryBackoff.java](../src/main/java/dev/xantha/vss/networking/client/RetryBackoff.java) | [LodRequestManagerGenerationQueueTest.java](../src/test/java/dev/xantha/vss/networking/client/LodRequestManagerGenerationQueueTest.java)、[LodRequestManagerStrictOrderTest.java](../src/test/java/dev/xantha/vss/networking/client/LodRequestManagerStrictOrderTest.java)、[ClientColumnTransferAssemblerTest.java](../src/test/java/dev/xantha/vss/networking/client/ClientColumnTransferAssemblerTest.java) |
| Netty、玩家发送窗口与 BO 背压 | [PlayerSendWindow.java](../src/main/java/dev/xantha/vss/networking/server/sending/PlayerSendWindow.java)、[QueuedColumnSender.java](../src/main/java/dev/xantha/vss/networking/server/sending/QueuedColumnSender.java)、[BandwidthOptimizerCompat.java](../src/main/java/dev/xantha/vss/compat/BandwidthOptimizerCompat.java) | [PlayerSendWindowTest.java](../src/test/java/dev/xantha/vss/networking/server/sending/PlayerSendWindowTest.java)、[BandwidthOptimizerCompatTest.java](../src/test/java/dev/xantha/vss/compat/BandwidthOptimizerCompatTest.java)、[PlayerSendQueueTest.java](../src/test/java/dev/xantha/vss/networking/server/state/PlayerSendQueueTest.java) |
| 超高维度列与 Xaero 保存 | [NbtSectionSerializer.java](../src/main/java/dev/xantha/vss/networking/server/storage/NbtSectionSerializer.java)、[ClientColumnProcessor.java](../src/main/java/dev/xantha/vss/networking/client/ClientColumnProcessor.java)、[XaeroMapCompat.java](../src/main/java/dev/xantha/vss/compat/XaeroMapCompat.java) | [NbtSectionSerializerTest.java](../src/test/java/dev/xantha/vss/networking/server/storage/NbtSectionSerializerTest.java)、[XaeroActualJarContractTest.java](../src/test/java/dev/xantha/vss/compat/XaeroActualJarContractTest.java)、[XaeroMapCompatBufferUpdateTest.java](../src/test/java/dev/xantha/vss/compat/XaeroMapCompatBufferUpdateTest.java) |
| 汉化、help 与无限吞吐 chunky | [VSSCommandHelp.java](../src/main/java/dev/xantha/vss/networking/command/VSSCommandHelp.java)、[VSSChunkyCommands.java](../src/main/java/dev/xantha/vss/networking/server/command/VSSChunkyCommands.java)、[ChunkyGenerationService.java](../src/main/java/dev/xantha/vss/networking/server/generation/ChunkyGenerationService.java)、[ChunkyWorkQueue.java](../src/main/java/dev/xantha/vss/networking/server/generation/ChunkyWorkQueue.java) | [VSSCommandTranslationTest.java](../src/test/java/dev/xantha/vss/networking/command/VSSCommandTranslationTest.java)、[VSSServerCommandsTest.java](../src/test/java/dev/xantha/vss/networking/server/command/VSSServerCommandsTest.java)、[ChunkyWorkQueueTest.java](../src/test/java/dev/xantha/vss/networking/server/generation/ChunkyWorkQueueTest.java)、[ChunkyAreaTest.java](../src/test/java/dev/xantha/vss/networking/server/generation/ChunkyAreaTest.java) |
| chunky 增量可用通知与持久写确认 | [ChunkyColumnAvailability.java](../src/main/java/dev/xantha/vss/networking/server/generation/ChunkyColumnAvailability.java)、[PersistentColumnWriter.java](../src/main/java/dev/xantha/vss/networking/server/storage/PersistentColumnWriter.java)、[GeneratedColumnFlusher.java](../src/main/java/dev/xantha/vss/networking/server/sending/GeneratedColumnFlusher.java) | [ChunkyColumnAvailabilityTest.java](../src/test/java/dev/xantha/vss/networking/server/generation/ChunkyColumnAvailabilityTest.java)、[PersistentColumnWriteAcknowledgementTest.java](../src/test/java/dev/xantha/vss/networking/server/storage/PersistentColumnWriteAcknowledgementTest.java) |
| 计时、几何与回归诊断 | [PredictionRenderTimings.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionRenderTimings.java)、[PredictionGeometryStats.java](../src/main/java/dev/xantha/vss/client/prediction/PredictionGeometryStats.java) | [PredictionFrameEventsTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionFrameEventsTest.java)、[PredictionRendererTest.java](../src/test/java/dev/xantha/vss/client/prediction/PredictionRendererTest.java) |

实现与测试路径以固定 Git 快照核对；相对链接方便当前仓库内浏览，历史复现使用上方不可变提交与比较链接。

## 记录边界与修正

- 最终 CPU 单元掩码负责已上传预测父子瓦片的归属，临时 Voxy 节点／traversal 不再直接抹除预测掩码；稳定列、三维范围与当前帧深度仍参加交接。
- `PredictionCoverageBudget` 类及测试保留在仓库，最终渲染路径未调用它；因此不能沿用早期报告把“4096 组／2 ms 验证队列”列作当前渲染策略。
- 颜色／光照字典压缩和区域文件缓存在 0.3.4 之前已有，本次记录的是去重、暂存、局部失效、I/O、驻留与恢复改进。
- 当前帧 Hi-Z、静态提交和保守显示层已经实现；双遍时间复用 HZB、完整 cluster/BVH、整树 impostor、新规则面坐标和完整几何误差规划未作为本版已完成功能。
- 显式 `/vss chunky` 不使用 VSS 性能吞吐门控；普通自动预生成仍保留自己的 CPU／帧率准入规则。坐标、任务生命周期、线程安全与数据版本保护不是吞吐限额。
- 原生颜色修复已验证 Rust 源码与重建 Windows DLL；其他平台预编译资源需要各自确认，不能仅因随包存在便宣称包含后续修复。
- FreeTerraForged 的实际发布版接口检查针对 NeoForge 1.21.1 的 `0.0.6005`、`1.0.0`；Lost Cities、Xaero 等按报告列出的对应平台与版本处理，接口验证不等于每个整合包实机启动验收。

## 完整净变更清单

下列折叠表包含两端去重后的 **480 个路径**，各平台状态来自上述精确 Git 比较：`A` 新增、`M` 修改、`D` 删除；`—` 表示该平台相对自身 0.3.4 没有该路径的净变更，不代表文件不存在。删除路径保留原名，便于从固定基线查看。

<details>
<summary>生产 Java：NeoForge 178 / Forge 179</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `src/main/java/dev/xantha/vss/client/VSSVoxyOptionsIntegration.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ClientColumnSample.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ClientFeatureHintCache.java` | D | D |
| `src/main/java/dev/xantha/vss/client/prediction/ClientPredictionState.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ClientStructureHintCache.java` | D | D |
| `src/main/java/dev/xantha/vss/client/prediction/ClientTerrainSampler.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ClientWorldgenProfileDecoder.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ClientWorldgenRegistries.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/DensityCompilation.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/DensityCompilerSupport.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/DensityGraphCompiler.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/DensityMemo.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/ExactCoverageGate.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedCompat.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedDensity.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedNativeFilters.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedTerrainSampler.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/FreeTerraForgedWater.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/LostCityHints.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionAdaptiveDisplayGrid.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionAquaticLod.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCacheMappings.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCacheMigration.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCacheStorage.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCityGeometry.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCityMeshCache.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionClimateSampler.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionColumnVolume.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCoverageBudget.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCoverageOwners.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionCpuBudget.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionDecorationLevel.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionDensityOrder.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionDiskCache.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionDrawRanges.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionExactCoverageIndex.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionExactCoverageMask.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionFeatureStampCache.java` | D | D |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionFogBridge.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionFramePace.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionGeometryStats.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionGlState.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionGpuEncoding.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionGpuResidency.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionGpuTile.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionJavaExterior.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionLoadingProgress.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMesh.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMeshBuilder.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMeshCodec.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMeshResources.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMeshRestore.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMeshletBounds.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMorph.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionMotionPace.java` | — | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionNormalDepthBridge.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionNormalTranslucencyBridge.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionOcclusionCuller.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionOcclusionPolicy.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionOpaqueBatches.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionOwnershipRuns.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionPackedMesh.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionPerformanceProfile.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutor.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionQuadMesh.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRawDensity.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRegionStorage.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRenderResidency.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRenderTarget.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRenderTimings.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionResources.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionSampleStore.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionSpatialOrder.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionSubmissionCoverage.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionSurfaceFeatureAdapters.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionSurfaceShapes.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainArena.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainColors.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTileTable.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionTreeModels.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionUploadBudget.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionUploadStaging.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVegetation.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationDisplayCache.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationRuns.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationSelection.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationTraits.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVisiblePlan.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyBoundaryBridge.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyDepth.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyOwnership.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionWorkOrder.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/PredictionWorldgenCapabilities.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/RustTerrainSampler.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/RustVegetationDescriptors.java` | A | A |
| `src/main/java/dev/xantha/vss/client/prediction/RustVegetationStage.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/RustWorldgenDocument.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/TerrablenderUniqueness.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/VssLodSampleCache.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/VssLodSpriteTable.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/feature/FeatureSimulator.java` | D | D |
| `src/main/java/dev/xantha/vss/client/prediction/feature/FeatureStamp.java` | D | D |
| `src/main/java/dev/xantha/vss/client/prediction/feature/FeatureStampLevel.java` | M | M |
| `src/main/java/dev/xantha/vss/client/prediction/feature/StampMesher.java` | D | D |
| `src/main/java/dev/xantha/vss/common/VSSConstants.java` | M | M |
| `src/main/java/dev/xantha/vss/common/worldgen/FreeTerraForgedVariant.java` | A | A |
| `src/main/java/dev/xantha/vss/common/worldgen/LostCityPreview.java` | A | A |
| `src/main/java/dev/xantha/vss/common/worldgen/WorldgenJson.java` | M | M |
| `src/main/java/dev/xantha/vss/compat/BandwidthOptimizerCompat.java` | A | A |
| `src/main/java/dev/xantha/vss/compat/StrictLodVisibility.java` | M | M |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyCoverageCache.java` | A | A |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyCoverageChanges.java` | A | A |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyCoverageRegions.java` | A | A |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyNodeIndex.java` | M | M |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyPendingColumns.java` | A | A |
| `src/main/java/dev/xantha/vss/compat/StrictVoxyPipeline.java` | M | M |
| `src/main/java/dev/xantha/vss/compat/VoxyCompat.java` | M | M |
| `src/main/java/dev/xantha/vss/compat/XaeroMapCompat.java` | M | M |
| `src/main/java/dev/xantha/vss/config/PredictionVegetationDensity.java` | A | A |
| `src/main/java/dev/xantha/vss/config/VSSClientConfig.java` | M | M |
| `src/main/java/dev/xantha/vss/config/VSSServerConfig.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/client/ByePregenPredictionPredicateMixin.java` | A | A |
| `src/main/java/dev/xantha/vss/mixin/client/FreeTerraForgedPredictionFiltersMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/lostcities/LostCitiesMixinPlugin.java` | A | A |
| `src/main/java/dev/xantha/vss/mixin/lostcities/TimedCacheConcurrencyMixin.java` | A | A |
| `src/main/java/dev/xantha/vss/mixin/voxy/NormalRenderPipelineDepthMaskMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/voxy/StrictVoxyBatchMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/voxy/StrictVoxyUploadMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/voxy/StrictVoxyVisibilityMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/mixin/voxy/VoxyBoundaryTerrainMixin.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/VSSNetworking.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/ClientColumnProcessor.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/ClientColumnTransferAssembler.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/ClientPresenceReporter.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/DeferredColumnQueue.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/LodRequestManager.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/PredictionGenerationPriority.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/RetryBackoff.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/StrictLodFrontier.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/VSSClientCommands.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/client/VSSClientNetworking.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/command/VSSCommandHelp.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/payloads/LostCityHintsS2CPayload.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/payloads/VoxelColumnS2CPayload.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/VSSServerNetworking.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/command/VSSChunkyCommands.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/command/VSSServerCommands.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityExterior.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityHintService.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityInfrastructurePlan.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityLegacyPalettes.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityPlannerAccess.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityPlanningReader.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/compat/LostCityStreetPlan.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/generation/ChunkGenerationService.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/generation/ChunkyArea.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/generation/ChunkyColumnAvailability.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/generation/ChunkyGenerationService.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/generation/ChunkyWorkQueue.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/generation/GenerationSchedulingPolicy.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/request/ColumnStorageReadPipeline.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/runtime/DiskTaskRuntime.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/runtime/ServerNetworkingLifecycle.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/runtime/TrackedTaskExecutor.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/sending/GeneratedColumnFlusher.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/sending/PlayerSendWindow.java` | A | A |
| `src/main/java/dev/xantha/vss/networking/server/sending/QueuedColumnSender.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/session/WorldgenCodecSnapshot.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/state/PlayerRequestState.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/state/PlayerSendQueue.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/storage/NbtSectionSerializer.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/storage/PersistentColumnLodStore.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/storage/PersistentColumnWriter.java` | M | M |
| `src/main/java/dev/xantha/vss/networking/server/storage/SectionSerializer.java` | M | M |

</details>

<details>
<summary>生产资源：NeoForge 10 / Forge 10</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `src/main/resources/META-INF/THIRD_PARTY_LICENSES.md` | M | M |
| `src/main/resources/META-INF/vss-natives/linux-aarch64/libvss_native_core.so` | M | M |
| `src/main/resources/META-INF/vss-natives/linux-x86_64/libvss_native_core.so` | M | M |
| `src/main/resources/META-INF/vss-natives/macos-aarch64/libvss_native_core.dylib` | M | M |
| `src/main/resources/META-INF/vss-natives/macos-x86_64/libvss_native_core.dylib` | M | M |
| `src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll` | M | M |
| `src/main/resources/assets/vss/lang/en_us.json` | M | M |
| `src/main/resources/assets/vss/lang/zh_cn.json` | M | M |
| `src/main/resources/vss.compat.mixins.json` | M | M |
| `src/main/resources/vss.lostcities.mixins.json` | A | A |

</details>

<details>
<summary>Rust 生产代码：NeoForge 10 / Forge 7</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `tools/rust/vss-native-core/src/backend/display.rs` | M | M |
| `tools/rust/vss-native-core/src/biome.rs` | M | M |
| `tools/rust/vss-native-core/src/density.rs` | M | M |
| `tools/rust/vss-native-core/src/density/cache_order.rs` | M | — |
| `tools/rust/vss-native-core/src/density/column_plan.rs` | M | M |
| `tools/rust/vss-native-core/src/density/height_plan.rs` | M | — |
| `tools/rust/vss-native-core/src/density/surface_plan.rs` | M | — |
| `tools/rust/vss-native-core/src/freeterraforged_filters.rs` | M | M |
| `tools/rust/vss-native-core/src/freeterraforged_noise.rs` | M | M |
| `tools/rust/vss-native-core/src/terrain.rs` | M | M |

</details>

<details>
<summary>Java 测试源、夹具与资源：NeoForge 201 / Forge 203</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `src/test/java/dev/xantha/vss/client/prediction/ClientCaptureExtractorTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/ClientWorldgenRegistriesTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/DensityGraphCompilerTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/DensityMemoBenchTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/FeatureStampPipelineTest.java` | D | D |
| `src/test/java/dev/xantha/vss/client/prediction/ForgeTestBootstrap.java` | — | M |
| `src/test/java/dev/xantha/vss/client/prediction/FreeTerraForgedCompatTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/FreeTerraForgedNativeFiltersTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/FreeTerraForgedNativeNoiseTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/JavaDensityCompilationBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/JavaPreviewSamplingTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/LostCityHintsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PackagedDensityCompilerTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAdaptiveDisplayGridTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAffineMergeTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAllocationBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAquaticMeshTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAquaticSubmissionTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionAsyncTerrainCacheTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionBiomeOptimizationBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCacheStorageTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCacheTestFiles.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCacheWorkerLifecycleTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCaptureInvalidationBatchTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCaptureQueueTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCaptureRefreshTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCaptureStageTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCityGeometryTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCityMeshCacheTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCitySurfaceTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionClimateSamplerTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionColumnVolumeTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCompactGpuTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCompressedUploadGpuTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageBudgetTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageCacheTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageIndexTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageOwnersTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCoverageWorkloadTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCpuAdmissionIntegrationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCpuBudgetTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionCpuLeaseLifecycleTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDecorationInvalidationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDecorationLocalityBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDecorationQueryBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDecorationQueryTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDeferredCacheWriteBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDeferredCacheWriteTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDepthSeedGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDiskCacheTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDiskIntegrationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometryTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionDisplayLodGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionExactCoverageIndexTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionExteriorColumnsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionFinishedMeshCacheBenchmark.java` | M | — |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionFirstCoverageTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionFluidStructureTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionFrameEventsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionGpuEncodingTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionGpuResidencyTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionGrowingGrassTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionHandoffRegressionTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionInitializationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionInteriorTerrainTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionJavaExteriorTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionLiveMeshReplayTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionLoadingProgressIntegrationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionLoadingProgressTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionLodSeamsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMemoryLifecycleTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMemoryOptimizationTest.java` | M | — |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshBuilderTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshCacheIntegrationBenchmark.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshCleanupBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshCleanupTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshCodecTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshMemoryTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshWriteOrderingTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMeshletBoundsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMotionAdmissionTest.java` | — | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMotionPaceTest.java` | — | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionMountainReplayTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionNetherDecorationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionNetherStructuresTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionOcclusionGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionOcclusionPolicyTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionPassStorageTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionPersistentMappingsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutorBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutorTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionProductionBatchGpuTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionProgressiveLoadingTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRawStateHandoffGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRefinementOptimizationsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRefinementPipelineBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRegionStorageTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRegionalOwnershipTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRenderTargetGpuTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionRendererTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSamplingWorkTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionScopeBudgetTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionScopedResidencyTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSegmentedMeshGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSimpleVegetationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSnowSurfacesTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSpatialOrderTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSpatialReplayTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSpriteTableTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSubmissionCoverageTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSurfacePipelineTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionSurfaceRetryTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionTerrainColorsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionTileTableHashTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionTreePlacementBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionTreeTintTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionUploadHandoffTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionUploadStagingGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionUploadStagingTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationLoadingBenchmark.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationLocalInvalidationBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationLocalInvalidationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationOccupancyBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationOccupancyTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationPublicationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationReuseBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationRunsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationSettingsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVegetationTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVillageGroundTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVisibleHandoffTest.java` | A | — |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyBoundaryGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyCoverageInvalidationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyDepthConventionGpuTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyDepthTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionVoxyStableOwnershipTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionWarmSurfaceRestoreBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionWarmSurfaceRestoreTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionWarmSurfaceSignatureBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/PredictionWorldgenCapabilitiesTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/RustDecorationReuseTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/RustGridReuseTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/RustVegetationDeltaUploadBenchmark.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/RustVegetationDeltaUploadTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/RustVegetationDescriptorsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/client/prediction/RustVegetationStageTest.java` | M | M |
| `src/test/java/dev/xantha/vss/client/prediction/TerrablenderUniquenessTest.java` | M | M |
| `src/test/java/dev/xantha/vss/common/VSSConstantsTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/BandwidthOptimizerCompatTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictLodShaderGpuTest.java` | D | D |
| `src/test/java/dev/xantha/vss/compat/StrictLodVisibilityHandoffTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyCoverageCacheTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyCoverageChangesTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyCoverageRegionsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyNodeIndexTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyPendingColumnsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyPipelineTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyShaderFixtureGpuTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/StrictVoxyUploadOwnershipTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/VoxyLocalIndexProbeTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/XaeroActualJarContractTest.java` | A | A |
| `src/test/java/dev/xantha/vss/compat/XaeroMapCompatBufferUpdateTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/XaeroMapCompatStubContractTest.java` | M | M |
| `src/test/java/dev/xantha/vss/compat/XaeroTileExtractorTest.java` | — | M |
| `src/test/java/dev/xantha/vss/mixin/lostcities/LostCityTimedCacheConcurrencyTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/client/ClientColumnTransferAssemblerTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/ClientConnectionIdentityTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/ClientPresenceReporterBudgetTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/client/DeferredColumnQueueTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/LodRequestManagerDeferredProgressTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/LodRequestManagerDirtyRefreshTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/LodRequestManagerGenerationQueueTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/LodRequestManagerStrictOrderTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/LodRequestManagerXaeroBackfillTest.java` | — | M |
| `src/test/java/dev/xantha/vss/networking/client/PredictionGenerationPriorityTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/RetryBackoffTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/StrictLodFrontierTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/client/VSSClientCommandsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/command/VSSCommandTranslationTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/payloads/LostCityHintsPayloadTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/command/VSSServerCommandsTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityExteriorTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityHintServiceApiTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityInfrastructurePlanTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityLegacyPalettesTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityPlannerAccessTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityPlanningReaderTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityReleaseMatrixTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/compat/LostCityStreetPlanTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/generation/ChunkyAreaTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/generation/ChunkyColumnAvailabilityTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/generation/ChunkyWorkQueueTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/generation/GenerationSchedulingPolicyTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/generation/GenerationToggleTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/runtime/DiskTaskRuntimeTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/sending/PlayerSendWindowTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/session/FreeTerraForgedSnapshotTest.java` | A | A |
| `src/test/java/dev/xantha/vss/networking/server/state/PlayerRequestStateTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/state/PlayerSendQueueTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/storage/NbtSectionSerializerTest.java` | M | M |
| `src/test/java/dev/xantha/vss/networking/server/storage/PersistentColumnWriteAcknowledgementTest.java` | A | A |
| `src/test/java/me/cortex/voxy/client/core/NormalRenderPipeline.java` | A | A |
| `src/test/java/me/cortex/voxy/client/core/gl/GlTexture.java` | A | A |
| `src/test/java/me/cortex/voxy/client/core/rendering/util/DepthFramebuffer.java` | A | A |
| `src/test/java/xaero/map/region/MapRegion.java` | M | M |
| `src/test/java/xaero/map/region/MapTileChunk.java` | M | M |

</details>

<details>
<summary>Rust 集成测试：NeoForge 2 / Forge 2</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `tools/rust/vss-native-core/tests/biome_colors.rs` | A | A |
| `tools/rust/vss-native-core/tests/display_surface.rs` | M | M |

</details>

<details>
<summary>文档与调查记录：NeoForge 46 / Forge 45</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `docs/chunky-incremental-delivery-20261006.md` | A | A |
| `docs/freeterraforged-compatibility.md` | A | A |
| `docs/java-density-compilation.md` | A | A |
| `docs/lod-network-backpressure.md` | A | A |
| `docs/lostcities-cache-concurrency-20261006.md` | A | A |
| `docs/lostcities-release-matrix-20261006.json` | A | A |
| `docs/prediction-035-hitch-fix.md` | A | A |
| `docs/prediction-aerial-depth-20261005.md` | A | A |
| `docs/prediction-bridge-water-infrastructure-20261006.md` | A | A |
| `docs/prediction-cache-material-fix-20261006.md` | A | A |
| `docs/prediction-city-surface-mismatch-20261006.md` | A | A |
| `docs/prediction-city-warm-cache-20261006.md` | A | A |
| `docs/prediction-coverage-invalidation.md` | A | A |
| `docs/prediction-entry-profile-20261006.md` | A | A |
| `docs/prediction-flicker-artifacts-20261006.json` | A | A |
| `docs/prediction-flicker-fix-20261006.md` | A | A |
| `docs/prediction-flicker-handoff-20261005.md` | A | A |
| `docs/prediction-gpu-optimization-20261006.md` | A | A |
| `docs/prediction-gpu-profile-20261006.md` | A | A |
| `docs/prediction-grass-cutout-20261006.md` | A | A |
| `docs/prediction-headroom-profile-20261006.md` | A | A |
| `docs/prediction-live-water-perf-20261005.md` | A | A |
| `docs/prediction-loading-artifacts-20261006.json` | A | A |
| `docs/prediction-loading-optimization-20261006.md` | A | A |
| `docs/prediction-loading-profile-20261006.md` | A | A |
| `docs/prediction-lostcities-compat-20261006.md` | A | A |
| `docs/prediction-mountain-implementation-20261006.md` | A | A |
| `docs/prediction-mountain-quality-cost-20261006.md` | A | A |
| `docs/prediction-movement-performance.md` | A | A |
| `docs/prediction-movement-profile-20261006.md` | A | — |
| `docs/prediction-native-color-compatibility-20261006.md` | A | A |
| `docs/prediction-occlusion-20261005.md` | A | A |
| `docs/prediction-ownership-submit-20261005.md` | A | A |
| `docs/prediction-regional-ownership-20261005.md` | A | A |
| `docs/prediction-render-handoff-audit-20261005.md` | A | A |
| `docs/prediction-render-visibility-20261005.md` | A | A |
| `docs/prediction-seam-fps-20261005.md` | A | A |
| `docs/prediction-upload-ownership.md` | A | A |
| `docs/prediction-upload-staging-20261006.md` | A | A |
| `docs/prediction-vegetation-settings-20261006.md` | A | A |
| `docs/prediction-vram-artifacts-20261006.json` | A | A |
| `docs/prediction-vram-optimization-20261006.md` | A | A |
| `docs/prediction-warm-cache-stream-20261006.md` | A | A |
| `docs/prediction-water-streak-20261005.md` | A | A |
| `docs/release-0.3.5-validation.md` | A | A |
| `docs/vss-commands-and-chunky-20261006.md` | A | A |

</details>

<details>
<summary>构建、平台原生资源、工具与其他：NeoForge 27 / Forge 26</summary>

| 路径 | NeoForge | Forge |
| --- | :---: | :---: |
| `.gitignore` | M | M |
| `CHANGELOG.md` | M | M |
| `README.md` | M | M |
| `build.gradle` | M | M |
| `gradle.properties` | M | M |
| `tools/prediction/LiveRenderOwnership.java` | A | — |
| `tools/prediction/biome-optimization.gradle` | A | A |
| `tools/prediction/city-cache-tests.gradle` | A | A |
| `tools/prediction/city-compat-tests.gradle` | A | A |
| `tools/prediction/compat-tests.gradle` | M | M |
| `tools/prediction/decoration-queries.gradle` | A | A |
| `tools/prediction/exterior-loading-tests.gradle` | A | A |
| `tools/prediction/flicker-tests.gradle` | A | A |
| `tools/prediction/gpu-optimization-tests.gradle` | A | A |
| `tools/prediction/java-density-compilation.gradle` | A | A |
| `tools/prediction/loading-regression-tests.gradle` | A | A |
| `tools/prediction/mountain-tests.gradle` | A | A |
| `tools/prediction/packaged-density.gradle` | A | A |
| `tools/prediction/query-locality.gradle` | A | A |
| `tools/prediction/regression-tests.gradle` | A | A |
| `tools/prediction/spatial-replay.gradle` | A | A |
| `tools/prediction/vegetation-reuse.gradle` | A | A |
| `tools/prediction/vegetation-settings-tests.gradle` | A | A |
| `tools/prediction/vram-tests.gradle` | A | A |
| `tools/rust/java/dev/xantha/vss/client/prediction/NativeSnapshotImport.java` | A | A |
| `tools/rust/vss-native-core/examples/adaptive_display_experiment.rs` | A | A |
| `tools/rust/vss-native-core/examples/snapshot_import.rs` | A | A |

</details>
