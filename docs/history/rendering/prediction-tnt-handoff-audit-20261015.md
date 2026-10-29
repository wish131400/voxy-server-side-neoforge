# TNT 修改地形后，离远恢复旧 LOD 的交接审查

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

后续修复：自然地表与流体的捕获回退、区域粗层同步及预算控制已在 2026-10-16 实现，见 [实施与验证报告](prediction-edited-terrain-20261016.md)。本文保留修复前的诊断结果，不能作为新包的行为验收。

日期：2026-10-15。审查对象：当前 NeoForge 1.21.1、Forge 1.20.1 源码。本轮仅增加诊断用例和报告，没有修改生产源码、生成发布 JAR、替换客户端或推送 GitHub。

**结论：当前 VSS 确实存在能产生这种视觉回退的路径，两个平台都已通过受控诊断确认。** Voxy 覆盖索引负责判断何时可以让真实绘制接管，并不等于预测缓存已经保存了玩家修改后的几何。离开 Voxy 有效渲染范围后，预测回退可以重新显示尚未更新的种子地形。这不能证明玩家的世界存档被回滚，也不能仅凭本轮源码审查排除玩家所用 Voxy 版本的更新问题。

用户提供的是现象描述，没有这次复现的客户端日志、配置或存档。本轮测试是合成炸坑的生产组件诊断，不是进入玩家存档引爆 TNT 的现场复现，也没有 GPU 截图或帧率测量。

**1. 已确认的回退链路**

```text
真实区块被修改，例如 TNT 炸出坑
→ Voxy 本地采集保存真实体素 / 更新自己的 LOD
→ VSS 的覆盖探测发现该列存在，确认覆盖
→ Voxy 有效范围内，覆盖过滤可以隐藏旧的预测地形
→ 玩家走远，该位置离开 Voxy 的交接范围
→ 预测几何恢复绘制
→ 若预测数据没有收到这次修改，重新出现的是种子计算出的原始地表
```

关键代码：

- [本地列探测](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/ClientPredictionState.java:596)：`PRESENT` 只确认 `exactCoverage`；这条路径没有读取列内容，也没有调用 `captureExactColumn`。
- [服务端真实列入口](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/ClientPredictionState.java:541)：携带 `VoxelColumnData` 的 `onExactColumn` 才会调用预测的真实样本捕获。
- [Voxy 交接半径](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionVoxyOwnership.java:7)：半径为 `max(0, voxyRadiusChunks - 2) * 16`，还要求三维 bounds 完整处于相机球内。32 区块配置对应 480 方块的保守交接半径；单个面或整块 bounds 的退出位置会有所不同。
- [提交过滤](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionSubmissionCoverage.java:187)：不在该范围内会恢复原始绘制范围；这是防止真实渲染不再覆盖后出现空洞的回退设计。
- [片元交接](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java:835)：也限制真实渲染距离、实际深度与空间对应关系，不能靠一个历史存在索引永久隐藏预测。

因此，“Voxy 已经保存了炸坑”和“预测回退已经包含炸坑”是独立的状态。近处看起来正确，并不能证明远处使用的预测数据已更新。

**2. 服务端修改通知与捕获已经存在，不能说 TNT 完全没有被处理**

服务端 `LevelChunk.setBlockState`、`Level.setBlock` 和 `ServerLevel.sendBlockUpdated` 的 VSS 注入会标记脏列。`DirtyColumnBroadcaster` 更新版本、失效列缓存，并向符合范围与已知旧版本条件的玩家通知。客户端收到通知后会使预测缓存失效，随后接收的新真实列可以更新 256 根地表柱。

`PredictionTileManager.captureExactColumn` 有在途版本校验，旧捕获不能覆盖新脏版本。合成测试确认，完整新列到达后，采样点命中炸坑时，外部地表采样从 Y=64 更新到 Y=56，多个采样间距下都保留这个新高度。

但两处缺口仍存在：

- Voxy 普通本地采集 / 本地列存在探测没有自动把完整真实内容交给预测缓存。`StrictLodVisibility.ingestCompleted` 仅结束采集跟踪，没有补这条内容同步链路。
- [脏列失效](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:1507) 会删除该列旧的捕获样本。新捕获尚未到达时，单柱解析可重新走种子采样。生产代码保留旧网格直到替代网格就绪，但这并不保证新构建使用的就是修改后的真实数据。测试证明的是数据源回退窗口，不是每次通知都会立刻在屏幕出现旧地形。

服务端通知过滤要求已知旧版本；本轮没有证据确认玩家这次是否收到通知、是否完成真实列重传，不能把通知丢失列为已确定的现场原因。

**3. 已收到新列，也不代表所有粗 LOD 都会保存炸坑形状**

[稀疏网格依赖](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:1922) 按实际采样点判断捕获是否影响祖先瓦片。一列落在两个采样点之间，可以不触发该祖先的捕获刷新。新构建的粗网格也没有按编辑区域做体素归约，而是查询其网格节点上的柱。

测试把炸坑放在区块 `(1,1)` 的十乘十区域。细采样点 `(24,24)` 正确得到 Y=56；32 格间距的四个周围采样点 `(0,0)`、`(32,0)`、`(0,32)`、`(32,32)` 全部得到 Y=64，且 `captureIntersectsGrid` 为 false。真实样本存在，但这个粗网格可以完全漏掉它。

这一现象与“未捕获修改时显示种子地形”应分别记录。小坑在远景简化中失去细节可以是显示误差；当前采样方式还允许整个修改区域不参与粗层数据，没有保证编辑后的形状进入父级表示。不能将所有“看起来恢复”的反馈都当作同一类缓存错误。

此外，真实捕获目前是自然地表与有限空腔证据，不是完整修改后体素世界的副本。建筑、植被和任意方块状态主要由真实渲染器负责。当前地下体积后端直接解析 `sampleInterior`，不使用只有地表高度的捕获记录替换整根地下柱。该分支也在诊断中确认，但没有证据表明玩家此次发生在这种维度。

**4. Voxy 更新链的审查边界**

仓库附带的 `external/voxy-src` 标记为 **Voxy 0.2.17-beta / Minecraft 26.1.2**，不是玩家当前安装版本的证明。其参考源码包含：

- 本地区块增加、卸载，以及部分方块移除时的采集钩子；
- `VoxelIngestService.processJob` 将区块截面体素化并生成多个 mip 层；
- `WorldUpdater.insertUpdate` 将变化逐级传播，标记网格和子节点存在状态为脏。

因此，这份参考实现没有“粗层永远固定为首次生成数据”的通用设计。但不能据此宣布玩家版本的 Voxy 一定更新成功。VSS 原始列桥也处理缺失截面的清空，不是始终忽略炸成空气的截面。

VSS 的 `enableLocalChunkIngestion` 默认 true；关闭它会阻止普通本地采集，但仍放行 VSS 服务端采集。玩家是否关闭此项未知，应列为现场分支，不能作为本轮确定根因。

**5. 实际验证结果**

独立诊断目录：`tools/prediction/tnt-handoff-audit-20261015`。用例明确标注为当前行为刻画；部分断言确认现存问题，并不是修复验收。

| 合成诊断 | 两平台实际结果 |
| --- | --- |
| 真实内容为 Y=56，只向交接提供列存在信息 | 预测仍为 Y=64；480 方块交接范围内提交 0 面，走到范围外恢复 1 个旧面 |
| 完整服务端新列已捕获，查询命中炸坑 | 1、2、4、8、16、32 格查询间距都返回 Y=56 |
| 捕获存在，32 格粗网格未命中修改区域 | 炸坑内查询为 Y=56；四个粗网格节点为 Y=64，祖先网格依赖为 false |
| 已捕获 → 脏失效 → 新捕获 | 单柱数据源依次为 Y=56 → Y=64 → Y=56 |
| 地下体积后端 | 捕获缓存为 Y=56；地下预测体积仍以 Y=64 为顶部 |

两平台各运行 **25 项**：5 项新增诊断，以及提交覆盖、捕获提取、脏列批量失效的 20 项相关回归。NeoForge 使用 Java 21，Forge 使用 Java 17；两平台均为 **0 失败、0 错误、0 跳过**。运行前后核对审查涉及的十个生产文件 SHA256，无变化。`source-snapshot.json` 与 `results.json` 保存在各自诊断目录中。

复现命令：

```powershell
# NeoForge
.\gradlew.bat --offline --no-daemon --console=plain --project-cache-dir .gradle-tnt-handoff-audit-20261015 -I tools/prediction/tnt-handoff-audit-tests.gradle tntHandoffAuditTest

# Forge
.\gradlew.bat --offline --no-daemon --console=plain --project-cache-dir .gradle-tnt-handoff-audit-20261015 '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' '-Dnet.minecraftforge.gradle.check.certs=false' -I tools/prediction/tnt-handoff-audit-tests.gradle tntHandoffAuditTest
```

**6. 建议修复范围与现场区分方式**

应补充修改后的真实数据回退，而不是只扩大剔除范围：

1. 把服务端真实列与本地区块更新 / 卸载采集接入统一、带版本的捕获路径。读取 Minecraft 可变状态前取得安全快照；尽量合并同列连续编辑，控制后台成本。
2. 将“此处曾有真实数据 / 编辑数据”的历史与“Voxy 本帧能绘制此处”的覆盖分开管理。前者选择预测回退的数据源，后者控制绘制交接。
3. 让受修改区域影响的祖先 LOD 基于修改后的真实数据生成粗表示，不能仅因为修改不在原始采样点上就完全忽略。地表、空气、装饰 / 建筑以及地下体积需要各自合适的表示和预算。
4. 脏通知期间保留最后有效的真实捕获或真实粗代理，带版本交换替代结果；拒绝把等待新真实数据的区域重新提交为种子状态。持久缓存也应遵循相同版本规则。

单纯把历史覆盖列永久隐藏会在 Voxy 距离之外留下空洞；单纯无损压缩不会改变源数据是否包含玩家编辑。

现场可用同位置、同视角临时隐藏预测绘制区分责任：若恢复的完整地表随预测一起消失，则该旧表面来自预测；若 Voxy 自己仍显示旧表面，需要核对实际 Voxy 版本、本地采集开关、列版本和父级更新。仅关闭“预测加载”会保留已有预测网格，不能用于这个对照。还应记录炸坑大小、发生距离及返回近处后的形状，以区分数据回退与正常粗层细节损失。
