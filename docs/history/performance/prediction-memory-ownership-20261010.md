# 内存占用与 Voxy 覆盖剔除调查、修复（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

结论：当前客户端 Java 堆接近上限，同时发现上一轮显示 LOD 实现额外保留完整坐标数组，阻止它在网格压缩后回收。该引用在 21:52 的只读快照中多占约 **1.76 GiB**。已修复两个仓库并构建新 JAR。

Voxy 已有真实覆盖时，预测剔除仍然执行。CPU 提交范围过滤、片元深度剔除和网格内存回收分别使用不同条件；画面上看不到预测面，不代表其 CPU/GPU 副本已经释放。

**现场对象与采样范围**

客户端 PID 38236，Java 25，21:39 启动。加载路径为 `F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/vss-0.3.5-neoforge-1.21.1.jar`；SHA256 为 `032fe84f91ea10932501a8ce6ff4d7e7a3f56709aed1f72d6ef4b32b4491fd24`，与上一轮 `forest-optimization-20261010` 成品一致。

本轮 35 秒 JFR 和 5 次约 8 秒间隔的只读快照均处于游戏内，未暂停。诊断只复制已发布瓦片引用、读取数组浅大小和 CPU 元数据；没有请求 GC、导出堆、遍历完整 JVM 堆、修改世界或进行 GL 诊断调用。采样已自动结束。

| 指标 | 实测 |
| --- | --- |
| Java 堆使用范围 | 9.31～10.06 GiB |
| Java 堆上限 | 10.81 GiB |
| JFR 中 GC 后堆使用范围 | 9.22～9.44 GiB |
| 游戏进程物理工作集，后续 OS 快照 | 12.88 GiB |
| 整机可用物理内存，后续 OS 快照 | 31.02 GiB |
| 显卡整体使用/总量，初始 nvidia-smi | 6.38/15.99 GiB |
| JVM direct buffer pool | 49.70 MiB |

系统物理内存和显卡均有余量；Java 堆接近其启动上限。进程工作集包含 JVM、本地库及驱动等，不能与 Java 堆相加。NMT 未启用，因此本轮不能完整拆分 Rust/LWJGL/驱动的堆外内存。

35 秒录制记录 4 次 GC，累计暂停 32.42 ms，最大单次暂停 10.65 ms。G1 并发周期的约 565 ms 总时长包含并发工作，实际暂停约 6.30 ms。GC 后仍有约 9.2～9.4 GiB 被引用，不能把高占用全部解释成尚未收集的临时对象。

JFR 分配样本只作为调用路径线索，本报告没有用其权重总和推算当前窗口的分配速率或保留内存，也没有对整个堆作对象归因。

**完整坐标数组为何仍被保留**

生产 PackedMesh 构造先取得 `PredictionDisplayGeometry.Result`，将 `Result.words` 交给自身坐标存储。上一轮实现还把整个 Result 保存在 `displayGeometry` 字段里。之后 `prepareGpuStorage()` 将坐标转换为紧凑存储，`prepareStorage()` 将紧凑数据压缩并清空 `quads`，但 `displayGeometry.words` 仍强引用先前完整的 12-int 坐标数组。

引用链为：

```text
已发布 CPU/GPU 瓦片 → PredictionPackedMesh → displayGeometry → words（完整坐标）
```

这条引用影响有显示变体和没有变体的网格，也影响接缝 factory 返回的结果。现场 2,176 个独立 payload 合计多保留 **1,885,995,712 bytes（1.76 GiB）** 的坐标数组。这些数组与 `quads`、opaqueStorage、waterStorage 均不同；诊断按对象身份去重后计算浅大小。该值为当时不必要的强引用载荷，不能当作客户端更新后的实测堆下降值。

同一快照，ready 瓦片为 501 个，其保留估算约 195.18 MiB；ready 与 GPU 瓦片集合中的压缩 blob 合计约 302.77 MiB。ready 估算不包含所有 GPU 旧回退瓦片，且与 blob 等数据重叠，不能相加。旧坐标引用也不在既有压缩后保留估算中，导致这部分压力没有被准确核算。

修复增加 `Result.metadataOnly()`，PackedMesh 只保存显示范围、选择表和误差，移交坐标后不再保留 Result.words。原始和附加显示几何继续保存在现有紧凑/压缩存储中，冷恢复、颜色表、范围和显示 LOD 选择语义不变。此项移除冗余引用，保留原来的画质和生成范围。

新增回归分别覆盖普通显示网格与接缝：验证 retained plan 不持有坐标、压缩上传后 raw 字段为 null、范围与误差仍可复用、冷恢复得到全部原坐标和显示几何。

**Voxy 已产生 LOD 后的预测剔除**

1. 真实列索引通过后台发现或已交付列更新。新 positive 列使用约 2 秒稳定期，快照刷新间隔为 250 ms，边缘列保留 fallback 标记。服务端或磁盘“已有数据”并不等同于当前帧实际选择了对应 Voxy 网格。
2. `PredictionRenderer` 在绘制准备前推进 `exactMask` 和 `submissionCoverage`。稳定内部列与三维有效范围满足条件时，`PredictionOwnershipRuns.filter()` 从不透明/水体绘制范围中减去已覆盖的地形 runs。实际范围/归属 revision 变化会使完整提交计划失效，下一次重建恢复或去掉对应范围；稳定时可复用剔除结果。
3. feature runs（树木、建筑等）不单凭列存在获得 CPU 删除证明。列有地形无法证明当帧 Voxy 已绘制该树/建筑；这些面与边缘/特殊交接继续由当前实际深度处理。预测与 Voxy 的 Hi-Z/深度路径也仍在执行。
4. 真实数据删除会推进 removal revision，使旧快照立即失效；新索引或相机跨范围时重新准备，恢复应保留的预测绘制。

21:52 快照提供了实际生效证据：

| 指标 | 值 |
| --- | ---: |
| 已稳定内部列 | 392 |
| 保留深度回退的边缘列 | 108 |
| Voxy 三维有效半径 | 8,160 blocks |
| 最近准备的不透明 plan 排除面 | 21,147 |
| 最近准备的水体 plan 排除面 | 195 |
| 当前整块被判定为 Voxy 完整接管的 GPU 瓦片 | 0 |

`voxyExcludedQuads` 是最近 PreparedPass 排除的候选面，既不是累计淘汰网格，也不包含所有 shader 丢弃的片元；本轮没有测量 shader 实际隐藏的所有面数。

若“剔除”指 Hi-Z，本轮初始诊断也显示 `occlusion=current-frame-hiz`。最近一次有效异步读回测试 97,263 个命令，隐藏 35,790 个命令、6,923,332 个候选面；读回年龄为 18 帧。这是相机/场景遮挡统计，不是 Voxy 已接管面的计数。自适应策略可以依据净 GPU 收益选择直绘或剔除，因而 Voxy 有 LOD 不意味着所有帧都强制开启 Hi-Z。

**为何剔除后占用不一定下降**

CPU 提交过滤只改 DrawRanges，片元 discard 只改变本帧像素，均不释放几何缓冲。GPU retirement 还要求整块完整覆盖、三维体积在有效范围内、拥有可绘制粗回退、经过 2 秒 owned grace，并绕开近处、接缝、望远镜 focus 和最粗覆盖等保护。当前 `wholeStaticCoverage()` 对含 feature 的网格不提供完整归属证明。

初始快照 GPU residency 的 `ownedTiles=0`、`ownedEvictions=0`，而预算淘汰累计为 2,783；当时逻辑驻留约 900.1 MiB，但 arena 整页分配为 2 GiB。页面仍被其他网格使用时，释放一个网格不会立刻释放整个页面。即使 GPU 副本退休，压缩 CPU 数据也可保留用于转身、覆盖撤销和回退恢复。

22:07 的补充只读检查位于另一个活动状态：3,270 个 GPU 瓦片中，2,994 个含 feature，172 个完整处于三维半径内，**0 个完整 footprint 处于稳定内部列矩形**。因此这次现场没有整块索引证明，并不能期待所有预测网格都随 Voxy 出现而回收。两个时间的瓦片数不用于同场景性能对比。

本轮修复内存引用，不放松整块回收/feature 深度保护。若后续要减少“已覆盖地形和未接管 feature 同块驻留”的显存，适合按地形/feature 拆分驻留与覆盖证明；需要可靠的实际绘制/版本交接，避免重新引入消失或闪烁。

**验证与交付**

| 平台 | CPU 回归 | OpenGL 回归 | 构建 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | 113 通过，1 条件跳过，0 失败 | 13 通过，0 跳过，0 失败 | jar 成功 |
| Forge 1.20.1 | 110 通过，1 条件跳过，0 失败 | 12 通过，1 条件跳过，0 失败 | jar + reobfJar 成功 |

CPU 条件跳过为旧独立 corpus 用例；上一轮 3,890 个真实网格已另行验证。Forge GPU 条件跳过为未提供实际 Voxy JAR 的 final-blit 测试；NeoForge 使用实际 Voxy 0.2.15-beta，该项通过。两包 ZIP、五个平台 Rust native 资源和最新编译/reobf 输出均已核验。

版本仍为 0.3.5。本轮包是上一轮森林优化的内存修正版，应选 `lib/memory-ownership-20261010` 中的包；附 SHA256SUMS.txt。

| 平台 | 新包 | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | [vss-0.3.5-neoforge-1.21.1.jar](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/memory-ownership-20261010/vss-0.3.5-neoforge-1.21.1.jar) | `1a7c3f03b10795d7fa7e5a4a1e804d48b8e91df7de68370a9c4d552a5a85d74d` |
| Forge 1.20.1 | [vss-0.3.5-forge-1.20.1.jar](C:/Users/Administrator/Desktop/voxyserverside/lib/memory-ownership-20261010/vss-0.3.5-forge-1.20.1.jar) | `19588c0f6c40676fe17d4a0d6e877410005fd42cf41cd2c80dde9bbf6bd2346d` |

可复现构建需依次使用：

```powershell
./gradlew.bat --no-daemon --project-cache-dir .gradle-memory-ownership-20261010 `
  -I tools/prediction/feature-handoff-tests.gradle `
  -I tools/prediction/forest-optimization-tests.gradle `
  -I tools/prediction/memory-ownership-build.gradle `
  forestOptimizationTest forestOptimizationGpuTest jar
```

NeoForge 用 JDK 21，并可传 `-PvssVoxyJar=<实际包绝对路径>`；Forge 用 JDK 17，加 `reobfJar`。XML 结果在 `build-memory-ownership-20261010/test-results/forest-optimization` 和 `forest-optimization-gpu`。原始 JFR、快照、构建日志和诊断源码在本机 `C:/Users/Administrator/.codex/tmp/vss-memory-ownership-20261010`。

当前客户端仍加载旧包，本轮没有热修改 JVM、替换客户端 JAR、重启游戏或推送 GitHub。1.76 GiB 是旧快照中可避免的数组保留量，实际更新后的堆下降和 FPS 尚未测量。
