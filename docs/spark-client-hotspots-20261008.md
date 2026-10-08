# Spark 客户端性能热点复核（2026-10-08）

本报告分析用户提供的 `D:/缓存/qq/接受/spark60秒.rar`。压缩包中的既有说明仅作为待核对的分析材料；结论来自独立解码的原始 Spark profile、调用树与当前仓库源码。此次仅完成分析和报告同步。

## 输入与统计口径

- 原始文件：`profile-2026-10-07_23.29.51.sparkprofile`，3,421,534 字节。
- 元数据记录的实际采样时间：2026-10-07 23:27:47.407 至 23:28:48.307（UTC+08:00），60.900 秒；文件名是导出命名时间，不作为采样起止时间。
- 模式：Java execution sampler，间隔 4,000 微秒，只采集一个指定线程。
- 唯一采集的线程为 `Render thread`，15,000 个样本，累计采样权重 60,000 毫秒，25,926 个调用树节点。
- 采样数据包含 1,198 个 tick；tick 数不是渲染帧数。
- Minecraft 1.21.1 / NeoForge 21.1.256，636 个模组，VSS 0.3.5-neoforge-1.21.1、Voxy 0.4.14、Sodium 0.8.13、Iris 1.8.14-beta.1。
- JVM：Oracle GraalVM 25.0.4，ZGC；CPU：i5-10400F，12 个逻辑处理器。
- 按 Spark 官方 `spark_sampler.proto` 与 `spark.proto` 的字段定义解析。线程总权重、根节点权重之和、全部节点 exclusive self 权重之和均为 60,000 毫秒。每条边的子节点引用有效，全部节点 self 非负。
- 各节点的“毫秒”是样本权重估算，不是逐次调用的精确计时。Java sampler 能采到等待和 native API 调用，不能将其一概视为正在消耗 CPU 的时间，更不能当成 GPU 时间。
- inclusive 数字包含子调用，不同系统之间可能嵌套；下表各行不能直接相加。

## 主要结果

| 热点 | 累计采样权重 | 占渲染线程样本 | 说明 |
| --- | ---: | ---: | --- |
| OpenGL 状态查询 native self | 6,676 ms | 11.13% | `nglGetIntegerv`、`nglGetIntegeri_v`、`nglGetNamedFramebufferAttachmentParameteriv`，由多个模组调用 |
| Iris 阴影通道（inclusive） | 8,108 ms | 13.51% | 内含本报告的 VSS 阴影失效及部分 OpenGL 开销，不是独立 GPU 耗时 |
| Better Health Indicator 相关支路去重 | 3,216 ms | 5.36% | 主要是血条可见性射线检查，内含原版及其他模组碰撞查询 |
| VSS `invalidateAll` 内的 `Arrays.fill` | 2,500 ms | 4.17% | 主画面 1,332 ms，阴影通道 1,168 ms |
| Reflex 的 query 创建、删除、结果读取 native self | 2,052 ms | 3.42% | 此数不含单纯 `park` 等待 |
| Sodium 地形批量绘制 native self | 2,008 ms | 3.35% | 主画面 1,076 ms，阴影通道 932 ms；不是 GPU 计时 |
| Tetratic Combat Expanded 缓存读取 self | 1,128 ms | 1.88% | 各调用场景合计，其中一部分被血条射线查询的碰撞链间接触发 |
| VSS `PredictionVoxyBoundaryBridge.bind` | 448 ms | 0.75% | 主要为当前 OpenGL program 查询 |
| VSS `LodRequestManager.tick` | 520 ms | 0.87% | 包含请求扫描与 deferred 队列维护 |

VSS 的归因需要区分口径：

- 对每个 exclusive self 样本，沿调用栈向上寻找最近的 `class_sources` 模组来源，得到 VSS 3,604 ms（6.007%）。这与附带说明的 3,604 ms 一致。
- 将包含 `dev.xantha.vss.*` 调用的样本去重，得到 3,620 ms（6.033%）。
- 额外包含 VSS 的 Mixin 注入入口，得到 3,672 ms（6.120%）。
- 仅将 `dev.xantha.vss.*` 类自身的 exclusive self 相加为 216 ms；这不能用于否定 VSS 的间接成本，因为数组清零和 OpenGL 查询位于 Java/LWJGL 类中。
- 2,500 ms 的数组清零约占 3,604 ms VSS 归因的 69.4%。

## VSS 第一热点的具体触发原因

附带说明正确识别出 `invalidateAll → Arrays.fill`，但原始调用树进一步显示：最重的两个叶节点分别来自主画面和 Iris 阴影通道。仅凭采样不能认定是“两条内部 fill 各自在每帧执行”，也不能推出精确的单帧 0.5–2 ms。

阴影路径：

```text
IrisRenderingPipeline.renderShadows
→ ShadowRenderer.renderShadows
→ LevelRenderer.renderSectionLayer
→ Sodium DefaultChunkRenderer
→ VoxyRenderSystem.renderOpaque
→ VSS strictFrame
→ StrictLodVisibility.beginFrame:214
→ updateRenderWindow:114
→ StrictVoxyCoverageChanges.invalidateAll:91
→ Arrays.fill:3143                 1,168 ms
```

主画面路径：

```text
LevelRenderer.renderSectionLayer
→ Sodium DefaultChunkRenderer
→ VoxyRenderSystem.renderOpaque:490
→ VSS strictFrame
→ StrictLodVisibility.beginFrame:236
→ updateRenderWindow:114
→ StrictVoxyCoverageChanges.invalidateAll:91
→ Arrays.fill:3145                 1,332 ms
```

当前源码中的关键行为：

1. `StrictVoxyVisibilityMixin` 在 Voxy `renderOpaque` 的 HEAD 调用 `beginFrame`，阴影调用同样进入该钩子。
2. `beginFrame:212–215` 遇到空或宽/高非正的 viewport 时调用 `updateRenderWindow(null, 0, 0, 0)`。原始阴影栈命中 :214，可将该路径定位到此分支；Spark 不记录具体参数，不能区分空 viewport 或哪个尺寸非正。
3. `beginFrame:236` 在正常主画面中重新提交维度、相机坐标和 Voxy 视距。
4. `updateRenderWindow:110` 仅在窗口完全相同的时候返回。有效窗口与空窗口互相切换，进入 :114 全量失效分支。
5. 因此一轮阴影/主画面调用足以造成“有效窗口 → 空窗口 → 有效窗口”的共享状态往返，每次往返都可触发两次全量失效，即使玩家没有移动。
6. `!active()` 的检查在 :240，晚于上述窗口更新。不能从“关闭 strict/预测”推断这条重置路径一定不再运行；此次文件本身也没有配置快照证明其开关状态。

这两条调用栈与当前源码行号精确对应，足以将 VSS 的反复失效定位到阴影 viewport 干扰主视图状态的兼容逻辑。此次没有运行现场探针，因此不声称获得了每帧调用次数。

## 为什么清零成本明显

`StrictVoxyCoverageChanges` 保留：

```java
long[6][65_536] columnVersions;
long[6][65_536] regionVersions;
```

两个表共 786,432 个 long，数组元素体积为 6,291,456 字节，即 6 MiB。每次 `invalidateAll` 清零两个表、清空变化历史并推进全局重置版本。若阴影和主画面各重置一次，逻辑上每轮要写约 12 MiB 数组元素；这只是代码级写入量，不能由此直接计算实际 DRAM 流量或带宽。

全局 `resetRevision` 的推进还会让覆盖查询缓存下次访问时需要重新验证。此次未采到 `PredictionRenderer` / `PredictionTileManager` 的调用，因此不能把预测批次重建、预测后台生成成本添加到本次实测数字里。

针对该热点的修复顺序：

1. 阴影或其他辅助通道不应改写主视图的共享 handoff 窗口；对这种 viewport 应跳过主窗口维护。真正的退出世界、切换维度、关闭 Voxy 仍通过明确的生命周期逻辑失效。
2. 优化全局失效为版本基线推进。当前 `columnRevision` 与 `regionRevision` 都从 `resetRevision` 开始取最大值，旧 bucket stamp 不会超过新的 reset 基线，逻辑失效有条件避免数组清零。实现时须核对全部读写与版本单调性，继续处理变化历史、覆盖撤销和生命周期重置。
3. 将可省略的维护放到正确的激活条件之后，确保辅助通道不重复更新前沿。不能通过保留已经撤销的覆盖结果提高缓存命中率。
4. 在回归中覆盖 Iris 阴影、移动、传送、视距改变、世界切换和覆盖撤销。主/辅助视图交替期间 resetRevision 不应持续上涨；真实失效必须让预测及时恢复。

## 其他热点

### OpenGL 状态查询

native self 合计 6,676 ms / 11.13%。按相关调用栈定位到的入口：

| 入口 | 查询 native self | 样本占比 |
| --- | ---: | ---: |
| AcceleratedRendering | 2,720 ms | 4.53% |
| Veil | 1,884 ms | 3.14% |
| AsyncParticles | 764 ms | 1.27% |
| Voxy | 744 ms | 1.24% |
| VSS | 392 ms | 0.65% |
| Xaero World Map | 172 ms | 0.29% |

- AcceleratedRendering 主要在 `CoreStates.recordBuffers → SimpleBlockBufferBindingState.record → glGetIntegeri`，涉及阴影批次、outline、第一人称和 Iris 批次。
- Veil 主要在 `VertexBuffer.handler$...$veil$drawPatches → glGetInteger`，包含 Iris 全屏合成阶段。其调用栈定位的开销可能被 nearest-class 模组归因算到 Iris；两种口径不能直接相加。
- VSS 的边界绑定方法在检查预测是否启用之前，已经查询当前 program 和 uniform location。当前 program 查询贡献 392 ms；值得将已知 program 作为参数、按 program 生命周期缓存 uniform 位置，并在可行处提前判断功能是否需要运行。

这些采样点表示渲染线程停留在驱动 API 中，可能包含驱动处理或同步等待；Spark 文件没有 GPU 时间戳，不能断言全部是 CPU 计算或某个模组自身的 GPU 渲染时间。下一步应分别对 AcceleratedRendering、Reflex 或对应功能做相同场景的独立 A/B，记录帧时间和 GPU 时间，避免同时改多个开关。

### 血条模组与碰撞查询联动

Better Health Indicator 支路去重 3,216 ms / 5.36%；其中：

```text
EntityHealthBarRenderer.collect                 2,908 ms
→ EntitySelector.shouldShow / passesCommonFilters
→ EntitySelector.isBlockedBySolidWall           2,772 ms
→ BlockGetter.clip / traverseBlocks
→ getBlockState / getCollisionShape
→ Kaleidoscope 碰撞钩子、装备检查、BetterCombat 等
```

不能将上述父子节点相加。部分相同类/方法名来自 Kotlin bridge/重载，直接把同名方法的 inclusive 汇总同样会重复统计。

元数据只有 35–42 个实体，仍能产生明显开销，因为射线检查的成本取决于每条射线路径穿过多少方块以及每次碰撞查询附带的模组事件。附带说明的“实体少，因此无实体压力”推论不成立。

可优先测试关闭血条的穿墙/遮挡判断；如果行为必须保留，可考虑降低检查频率、限制距离，并根据相机、实体和附近方块变化失效缓存。不能将实体渲染或碰撞行为整体禁用。

### Reflex 与其他成本

- Reflex 的 query native self 为 2,052 ms / 3.42%，其中删除 query 是大头。
- Reflex 完整支路去重 4,024 ms / 6.71%，里面包含主动等待。等待可能服务于帧节奏与延迟目标，不能把全支路时间都当成可直接删除的计算。
- Tetratic Combat Expanded 的 `tetraticperf$readCache` self 1,128 ms / 1.88%。某些调用来自血条射线的碰撞形状 → 装备查询 → BetterCombat 武器属性链，存在与血条热点嵌套的成本。
- YSM 支路去重 2,064 ms / 3.44%，是后续模型/动画优化候选；此次没有展开其混淆方法内部语义。
- Sodium 的地形 native draw self 2,008 ms 包含主画面与阴影绘制，无法凭这个数字得到 GPU 帧成本。
- `EventBus.post` 的聚合 inclusive 可达到 72.64%，其中许多事件调用互相嵌套；这不是事件总线本身消耗 72.64%，不能据此直接替换事件总线。

## 内存、GC 和后台工作的限制

- 导出快照的 Java heap used 为 16.81 GiB、max 24.70 GiB（`-Xmx25292m`），committed 24.28 GiB。当前快照没有显示堆已满，也不能由一个快照判断泄漏。
- 全系统物理内存 used 52.84 / total 63.92 GiB；过去一分钟系统 CPU 使用率约 78.9%，游戏进程约 53.5%。这说明系统还有其他负载，但没有后台线程调用树来定位它们。
- GC 是 JVM 运行期累计的系统指标，没有本次 60.9 秒的首尾差分。ZGC Major Cycle 平均约 14.8 秒是并发回收周期，不是停顿 14.8 秒；Major/Minor pause 平均约 0.01 ms。不能从当前字段计算本次 GC 总暂停或 allocation stall。
- Spark tick 元数据 last1m 约 19.99 TPS，mean 8.27 ms、P95 12.70 ms、max 322.96 ms。此文件的平台是 CLIENT，tick 指标不是 FPS，也不替代独立的服务器线程采样。
- 此次调用树没有 `ChunkyGenerationService`、`ChunkGenerationService`、`PredictionJavaExterior` 等后台/服务端调用，也没有预测主体绘制样本。只采渲染线程无法复核上一份预生成 OOM 的任务积压或确定后台生成成本。
- 尚未获得逐帧时间序列、实际帧数和 GPU 计时；不能根据 2,500 ms 直接算单帧耗时、1% low 或承诺 FPS 提升。
- 同版本字符串不能证明外部采样 JAR 与当前仓库构建字节完全相同；本报告依赖吻合的调用栈和源码行号，不宣称完成 JAR SHA256 一致性核验。

## 后续优先级

1. 修正 VSS 阴影/主画面窗口往返，并用版本基线降低全局失效写入量；这是本项目最明确、可定位的修复点。
2. 对 AcceleratedRendering 状态记录、Veil 状态查询和 Reflex query 管理做独立 A/B，结合 GPU 计时判断 driver API 中的等待和执行成本。
3. 优化或配置血条遮挡判断，减少长距离逐帧碰撞射线。
4. 同一视角重复采样，核对 `invalidateAll`、覆盖 reset 计数、缓存命中率、GL 查询数、帧时间与 GPU 时间。若需要调查预生成/预测后台，另采所有相关线程或 JFR。

当前直接可消除的 VSS fill 权重约为渲染线程样本的 4.17%。还可能存在缓存二次失效的后续收益，但本文件没有量化，不能以此承诺翻倍或固定的 FPS 增幅。

## 修复状态（2026-10-08）

上述 VSS 阴影／主窗口往返及全局版本表清零已在两个平台同步修复，相关回归与独立 OpenGL 验证通过，并构建了新的 0.3.5 JAR。具体实现、条件跳过、成品校验与未复采的范围见 [修复验证记录](spark-shadow-coverage-fix-20261008.md)。
