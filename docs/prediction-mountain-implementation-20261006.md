# 预测 LOD 显示与剔除优化实施记录（2026-10-06）

本次落实调查报告中的 A（水生植被显示 LOD）、B（空间分组）、C（严格等价地形合并的保守子集）、D（材质距离解耦）。E（几何误差驱动的规划）和 F（混合紧凑几何格式）仍是后续架构工作，不属于本次已完成项。

## 实现范围

### A：水生植被在提交前降级

`PredictionSpatialOrder` 识别原版海带、海带茎、海草和高海草的已知 sprite，仅简化未使用自定义模型 UV 的非流体 crossed 植物。按完整 X/Z 植株选择，海带顶、茎、不同高度与两个交叉面使用同一个保留条件；未知材质、地形、建筑、水面保持完整。

每个不透明 face group 排成四级共享前缀；显示等级变化只选择前缀 draw range，不生成大量零碎命令，也不重新采样、构网或上传。每一级使用 1、2、4、8 格的固定网格选株；均匀分布时理论保留比例为 1、1/4、1/16、1/64，真实比例取决于植株分布。

距离取相机到 tile 最近水平边。完整显示半径使用 surfaceDistance，最低 256 格，三个边界为半径、两倍半径、四倍半径，并附加至少 32 格滞回。近看或望远镜恢复完整数据。静止时的 GPU residency 更新也触发新 payload 等级初始化。`aquaticExcludedQuads` 记录该阶段排除量，与 Voxy ownership 排除量分别统计。

取舍：远处水生植物密度降低；完整几何仍驻留，因此该项主要降低提交与绘制工作，不等于减少完整缓存体积。没有修改地形或水面覆盖范围。

### B：先按空间排列，再构建剔除与归属索引

后台打包和磁盘恢复时，在 face group 内按照植被保留等级、XZ Morton 顺序重排完整 canonical quad 记录。随后构建现有 256 quad meshlet 包围盒及 ownership runs，使一组面更集中。保留原 face 分组边界和所有透明面的原顺序。

仍使用当前帧 HiZ、既有命令格式和索引；没有引入新 BVH、双遍 HZB 或新的 mesh codec。另一个 CPU 优化对话加入的线程内排序工作区复用予以保留。

地形 morph 的包围盒从扩到整块 tile 的高度范围，改为原 bounds 加整个位移场的最小/最大 delta。双线性取样受该极值包络约束，范围覆盖整段动画，避免每帧重建 bounds。

### C：保守的颜色连续平顶合并

增加同高度、同 sprite、完整 cell 顶面中共享全局仿射 RGB 场的合并。每个通道要求四角满足仿射条件，且相邻 cell 的斜率和全局截距相同；合并后读取矩形真实最外角，而非拉伸第一个 cell 的颜色。

新增非均匀颜色合并仅用于 builder 明确标记的 dry mesh，且 spacing <= 2。这些网格没有后续水深光照，也不进入现有地形 morph。其他情况沿用原本均匀颜色规则，内部颜色突变、重复但不连续的 cell 渐变均不能被合并掉。分段构网也在首次 packing 前传递该标记。

这是严格子集优化，不代表调查中“不满足原条件的 67.49% 顶面”都可以合并。已有磁盘缓存不会自动重新构网获得这项收益。

### D：纹理退出与地形精细半径分开

普通材质 detail distance 改为 `min(predictionHorizon, max(2048, projectionScale * 4))`，不再直接使用 fineDistance。保留已有 footprint、mipmap 和 textureGrad 路径，并在材质距离最后 25% 平滑淡出。望远镜不使用该距离限制。

跳过材质查询的判断改为整个 quad 的 XZ 包围盒都超出距离，避免 quad 两个三角形的 flat varying 判定不一致。cutout alpha 和流体纹理保留原路径。

该项改善表面纹理感，不会增加山脊采样精度；保留纹理范围扩大也可能增加部分 GPU 采样成本，必须与减面、剔除收益分开测量。

## 验证与证据

使用 `tools/prediction/mountain-tests.gradle` 的独立 `mountainTest` 任务。实际验证副本位于 `C:/Users/Administrator/.codex/tmp/vss-mountain-20261006/{neo,forge}`，日志与源码 hash 位于它们的上一级，避开共享仓库的 `gradle clean`。

新增用例覆盖完整记录/水顺序不变、整株保留、距离滞回、望远镜恢复、静止时新网格替换、真实提交范围、仿射颜色外角与覆盖索引、内部颜色突变和不安全光照/形变拒绝合并。

早期真实雪山回放的工具输出记录为：12 块网格、820,938 个不透明 quad 全部保留；平均包围盒最长水平边 110.523 → 35.165 格；ownership runs 262,050 → 161,437。该值是离线同数据比较，不是客户端 FPS。之后并行工作的构建清理了主仓库 build，原始二进制回放样本与日志已不在原路径，不能作为随包可复现实验。水生网格样本虽曾抓取，但完整回放未完成，故不报告真实场景减面比例。

最终测试、打包记录见下方收尾记录；在实际客户端换包并进行固定视角对照之前，不宣称 FPS、p95/p99 或接缝视觉验收已通过。

## 调试开关

- `-Dvss.disablePredictionSpatialOrder=true`：禁用空间 Morton 排序，仍允许植被前缀分组。
- `-Dvss.disablePredictionAquaticLod=true`：不生成植物稀疏等级，保留空间排序。

两个开关在 mesh 打包/恢复时读取，修改后须重启或重新构建相应网格；不是运行时即时切换。默认均开启。原有 codec 未改版，旧 canonical 缓存恢复后可获得新排序和植物 ranges。

## 最终收尾记录

- NeoForge 1.21.1：CPU 定向测试 45 项，其中 44 通过、1 个外部真实语料用例跳过；6 类 GPU 用例全部通过（生产批次用例修正旧反射签名后单独重跑通过）。`assemble` 成功。
- Forge 1.20.1：相同 CPU 定向测试 44 通过、1 跳过；相同 6 类 GPU 用例全部通过。`assemble` 和 `reobfJar` 成功。
- 两端各计 51 项、50 通过、1 跳过、0 失败。保存目录内 `verification-summary.json` 汇总了最终报告，production-results 替代早期 gpu-results 中反射签名过期的失败记录，历史日志保留。
- GPU 覆盖包含 compact/raw、direct/MDI/fallback 的像素和深度一致性、当前帧 HiZ、GL 状态与深度种子交接、分段密集植被、Voxy 替换边界。实际 NVIDIA RTX 4070 Ti SUPER OpenGL 上运行；不等于真实整合包截图或 FPS 对照。
- CPU 归属与 GPU primitive query 联合用例确认：测试中完整稳定 Voxy 覆盖时不透明/水面预测提交三角形均为 0，覆盖撤销后恢复原数量；混合覆盖保留未接管部分。该项为合成生产路径回归，不能证明用户所有实际边界区域都已消除漏光。
- 已保存 NeoForge 包：`lib/render-quality-20261006/vss-0.3.5-neoforge-1.21.1.jar`，5,527,890 bytes，SHA256 `784bac64499c76ca0ae2fe80609baee591e44ac9f46efa3f62e3ff369b4db11c`。
- 已保存 Forge 包：`lib/render-quality-20261006/vss-0.3.5-forge-1.20.1.jar`，6,200,989 bytes，SHA256 `e5819878eb588f55ba0a761dc5cfe1472f30c363b21c58d52988e7d1b5f54e84`。
- 验证副本 src 与两个工作仓库逐文件 SHA256 一致；两个独立产物中的所有 class 也与并行 CPU 优化对话生成的各仓库根 lib 产物一致。本次没有替换用户游戏 mods 中的 JAR，也没有进行改动后的客户端 FPS 采样。

可复跑 CPU 验证：`gradlew.bat mountainTest --tests '*PredictionSpatialOrderTest' --tests '*PredictionAffineMergeTest' --tests '*PredictionAquaticSubmissionTest' --tests '*PredictionMeshletBoundsTest' --tests '*PredictionMeshCodecTest' --tests '*PredictionGpuEncodingTest' --tests '*PredictionSubmissionCoverageTest' --tests '*PredictionRegionalOwnershipTest' --tests '*PredictionRendererTest' --tests '*PredictionMeshMemoryTest' -I tools/prediction/mountain-tests.gradle`。

可复跑上述 6 类 GPU 验证：`gradlew.bat mountainTest -PmountainGpu -I tools/prediction/mountain-tests.gradle`。GPU 测试每类独立 JVM；脚本已增加 class include，避免为不相关测试类启动空 worker。
