# 2026-10-20 粗地形局部交接修复

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本次修复针对录像 `2026-10-20 20-32-36.mkv` 中，细地形到达后仍被粗顶板覆盖、粗格整片消失及局部旧侧墙没有退出的问题。调查证据见 [原始调查](prediction-video-regression-20261020.md)。原始调查里的“尚未修改”描述的是前一阶段；本报告记录后续实现与验收。

## 根因与行为变化

8192 方块父瓦片的初始网格可以只有 8×8，每个源格覆盖 1024×1024 方块，而最小细瓦片是 64×64。原归属掩码按源格中心选一个拥有者，然后保留或删除整个源格，无法表达格内细瓦片的边界。

- 细瓦片避开粗格中心时，粗面仍覆盖细瓦片的 4096 平方格。
- 细瓦片包含粗格中心时，粗格其余 1,044,480 平方格也被误删。

现在保持高度采样、原始网格和 morph 的坐标定义，在归属掩码内对受影响的粗格建立稀疏四叉细分。GPU 按像素位置读取细分归属，只让出已由可绘制细瓦片接管的范围。没有细分需求的瓦片继续使用原固定网格路径。

`PredictionCoverageGrid` 只表达当前渲染快照的归属结果，拥有者仍由已有 `PredictionCoverageOwners` 与快照空间表查询。它没有另建一套世界索引。细瓦片范围外的一方块邻域也参与细分，以便同一块粗源墙只撤掉相邻细瓦片对应的短段。

## 交接、侧墙与恢复

- 只有完成 GPU 上传并进入渲染驻留快照的细瓦片才能撤销父层。仅有 CPU 结果时仍保留旧覆盖。
- 地形与植被分别选择拥有者。地形先到、植被未完成时，父层植被继续保留；明确完成且为空的子层可以接管植被范围。
- 掩码、侧墙替换和接缝沿用一个有版本的异步边界事务，提交前继续绘制上一版。
- 接缝索引、边界遍历和旧墙裁切都使用归属叶节点边界，网格高度和源样本仍索引原始粗格。
- 直接贴着父瓦片外侧的细邻块也会使对应粗邻块的归属拓扑失效；远处瓦片的缓存保持可复用。
- 驻留撤回后，父层覆盖和原墙恢复；不通过延长失效缓存的寿命掩盖问题。

没有恢复按 Voxy 半径、磁盘列或任意已上传节点无条件删面的旧逻辑。Voxy 当前帧深度、真实表面与预测表面的保守交接规则保持原有职责。

## GPU 与内存

普通固定网格继续使用 R8 掩码；细分网格的直绘路径使用 RG32F，分别保存归属位和精确子节点索引。间接绘制把同样的树记录放在既有 arena slice 的掩码尾部，沿用 64 字节提交记录，仅增加一个格式标记。直绘、MDI 和 Iris 共用有上界的树遍历代码。

归属树增大时，在现有 arena 预算内通过 GPU 拷贝保留几何，不在 CPU 重新生成网格。无法扩容时，保留几何并退回支持树纹理的直绘路径。恢复普通掩码后可以重新使用间接绘制。纹理显存核算包含细分格式。

没有按 `瓦片范围 / 64` 分配全域位图。最大范围父瓦片与一个细瓦片的 CPU 回归要求节点数小于 300，避免创建 524288×524288 的密集掩码。这项是空间增长保护，不是实际游戏的性能测量。

## 样本标记兼容

一并保留前一阶段的 `ClientColumnSample.FLAG_PARENT_LOD` 修复：从与侧壁检查冲突的 bit 25 移到空闲 bit 18，避免经过侧壁检查的真实列被拒绝复用，以及近似列被误认为同源父级输入。持久化侧壁证据的 bit 25 不变。

本次不改变网络协议 53、地形/网格缓存格式或配置默认值，不需要清理已有缓存。

## 回归方法

正式入口为两仓共享的 `tools/prediction/coarse-handoff-tests.gradle`，不再依赖临时诊断 main。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat -I tools/prediction/coarse-handoff-tests.gradle verifyModules coarseHandoffTest planningConsumerTest --no-daemon
.\gradlew.bat -I tools/prediction/coarse-handoff-tests.gradle coarseHandoffGpuTest --no-daemon
.\gradlew.bat -I tools/prediction/coarse-handoff-tests.gradle jar -PvssBuildId=coarse-handoff-fix2-20261020-p53 --no-daemon
```

本次使用 `-PcoarseHandoffBuildDir=build-video-regression-20261020` 和 `-PcoarseHandoffJarDir=lib/coarse-handoff-fix-20261020` 保存独立构建与产物。Forge 的 `jar` 之后必须完成 `reobfJar`。GPU 任务分别运行，避免两个平台同时占用同一张显卡。

新增 CPU 用例覆盖粗格中心/非中心交接、负坐标、多层 LOD、上传前回退、独立植被接管、撤回恢复、相同根位但不同局部范围的失效、内外侧局部墙，以及接缝事务。完整父瓦片逐 64 方块单元核对，要求重叠面积与缺失面积均为 0。

其中外侧邻块增量用例在补充邻域失效前失败：细邻块上传后父块的 epoch 未变化。补上仅涉及相交区域及需要细分的直接粗邻块的失效后，8 项局部交接 CPU 用例全部通过；远处块 epoch 不变的断言保留。

新增 GPU 用例通过生产上传、shader 和间接命令读回颜色与深度，包含共享页和独立缓冲、地形/水面/植被、直绘/MDI/Iris，以及完整→局部接管→恢复的 54 组场景；另有 6 组侧墙局部替换检查。恢复画面要求与原像素覆盖一致。其余 GPU 回归继续检查紧凑格式、生产批次、Voxy 表面交接、植被深度与接缝驻留。

## 最终验收与产物

| 最终任务 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | --- | --- |
| `verifyModules` | 351 通过 | 353 通过 |
| `coarseHandoffTest` | 120 通过 | 119 通过 |
| `planningConsumerTest` | 32 通过 | 32 通过 |
| `coarseHandoffGpuTest` | 8 通过 | 8 通过 |
| 打包 | `jar` 成功 | `jar`、`reobfJar` 成功 |

以上最终任务均为 0 失败、0 错误、0 跳过。不同任务包含部分相同测试，不把表格相加为唯一用例数。新增 GPU 测试在两个平台均完成 54 组归属场景和 6 组侧墙场景，检查期间没有 OpenGL 错误。显卡为 NVIDIA GeForce RTX 4070 Ti SUPER。

验证过程中，NeoForge 的一次 `planningConsumerTest` 曾在旧内存压力用例 `constrainedBudgetKeepsCoarseCoverageAndClosesWithoutLeakingReservations` 中未达到连续 5 轮稳定（实际 3 轮）。未放宽断言、未排除该用例；该类单独复测 7 项通过，最终完整任务 32 项也通过。初次失败日志 `coarse-neoforge-release.log` 与复测日志 `coarse-memory-recheck.log` 均保留。这不构成该历史用例在所有调度条件下都稳定的证明。

两包构建标识均为 `coarse-handoff-fix2-20261020-p53`，Manifest 的 `VSS-Protocol` 均为 53。输出位于各仓的 `lib/coarse-handoff-fix-20261020/`；文件名仍使用仓库当前版本号，选择时以完整目录和构建标识为准。

| 平台 | JAR | SHA256 |
| --- | --- | --- |
| NeoForge | `vss-0.3.5-neoforge-1.21.1.jar` | `034322F6226F7B2201BF34ABED0331D69681137B7120B1FFF28E598BBE3DE6DF` |
| Forge | `vss-0.3.5-forge-1.20.1.jar` | `11DE3B61AAAFC62BF0C6FC4B210D01929450FC936718F4558F3B462C95380833` |

每个 JAR 旁有同名 `.sha256` 文件。已检查包内包含新的覆盖树生产类，未混入本轮测试、JUnit 和临时诊断 main。NeoForge 新类与最终编译结果一致；Forge 完成重混淆后，覆盖树类的指令与编译结果一致（忽略常量池编号和排版差异）。两仓共享实现已核对，Renderer 保留加载器事件和矩阵读取的三处差异，TileManager 仅同步本轮新增方法。

最终日志位于各仓 `tmp/video-regression-20261020/`：NeoForge 为 `coarse-neoforge-verified.log`，Forge 为 `coarse-forge-verified.log` 和 `coarse-forge-gpu-verified.log`。两仓均保存 `coarse-final-verification.json`，包含逐任务 XML 统计、构建标识与 JAR 哈希。

验证范围限于源码、CPU 行为和 GPU 夹具。未启动或替换用户的游戏实例，未修改存档、缓存或配置；没有在录像原存档中确认每个异常像素的来源，也不据此承诺实际 FPS。两个仓库只完成本地同步与打包，未提交或推送 GitHub。
