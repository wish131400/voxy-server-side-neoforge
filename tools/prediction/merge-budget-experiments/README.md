# 显示 LOD 构建分配与合并收益实验

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

日期：2026-10-12。只保留已验证的分配减少；试验过的共享记录、前后缀共享、大范围相邻矩形和完整档位去重均已从生产源码撤回。

当前生产变化只有：`PredictionDisplayGeometry` 在工作线程已有数组内向前紧凑记录；`PredictionScanMerge.mergeDisplay` 只处理有效不透明前缀；显示档位通过原有准入条件后才复制需要发布的数据。网格、误差、预算、绘制范围和 GPU 编码逐字一致。

`MergeBudgetLegacyDisplayGeometry.java` 是本轮开始时的原实现，独立线程工作数组保留不变。它只进入实验 source set，不进入发布 JAR。

`resources/merge-budget/historical-source.bin.gz` 是历史捕获 mesh 13、1074、2241 的源记录和材质分类适配，包含块内地形，不包含种子或有效相机。压缩文件 SHA256 为 `b67cc53ddc57428b874df545045b799ec2eff4b9601666d4046748238927da14`。完整捕获不在 Git 中，位于 `C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry`。分类适配使用历史 leafClass 和原版草、水草、雪的材质名称，未捕获完整注册表和 tag。

## CPU 与正确性

NeoForge 使用 JDK 21；Forge 使用 JDK 17，并额外传入 `'-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17'`。

```powershell
.\gradlew.bat -I tools/prediction/merge-budget-tests.gradle mergeBudgetCpuTest mergeBudgetRegressionTest --no-daemon --console=plain
```

CPU 基准每个网格热身 20 轮，测量 41 轮，交替执行旧/新实现。`ThreadMXBean` 测量工作线程累计对象分配，时间用中位数。时间与分配只覆盖显示 LOD 构建，不覆盖完整生成、磁盘、上传或整个帧。不要与游戏或其它 CPU 基准同时运行。结果在 `build-merge-budget-20261012/results/merge-budget-worker.csv`。

## 真实完整语料

需要 Python 3.14、NumPy 和 JDK 21：

```powershell
python tools/prediction/merge-budget-experiments/replay.py --capture C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry --output C:/Users/Administrator/.codex/tmp/vss-merge-budget-20261012/final-copy
```

回放 2,019 个唯一普通不透明网格，逐字检查源、水面、追加显示档和误差一致，并检查 11 个距离 × 4 个投影尺度 × 4 个质量误差 × 31 个方向组合的面数。GPU 字节使用生产颜色表、分通道和矩形编码计算。`oldAlloc/newAlloc` 包含该构建调用的全部分配；总时间按旧后新的顺序执行，不能用作严格时间 A/B。

## GPU 组件验证与打包

先退出游戏；两平台 GPU 测试串行运行：

```powershell
.\gradlew.bat -I tools/prediction/merge-budget-tests.gradle mergeBudgetGpuTest mergeBudgetProductionGpuTest jar --no-daemon --console=plain
```

Voxy 深度测试需要 `'-PvssVoxyJar=<本机 Voxy JAR 的绝对路径>'`。Forge 打包应额外执行 `reobfJar`。

历史几何 GPU 测试使用生产上传、着色器、MDI、完整计划重放，以及直绘的像素/深度读回。原整合包的纹理不在捕获中，使用固定替代图集。距离曲线负责选档，另用固定近视角检查索引和绘制布局，64 次重复提交放大顶点工作量；24 轮热身、81 轮交替测量。它是组件测试，不能换算游戏 FPS。结果在 `build-merge-budget-20261012/results/merge-budget-submission.csv`。

发布文件输出到 `lib/merge-budget-20261012/`，所有实验类与历史数据只属于测试 source set。
