本实验用于 2026-10-12 的 Voxy / Distant Horizons 合并逻辑调查。它只生成临时 Java 实验类，不修改生产源码，不启动游戏，也不测量客户端 FPS。

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

从同一份历史捕获恢复 canonical 网格，剥离捕获时追加的显示变体，再重放当前生产扫描器、空间排序、显示变体与植被范围代码。输入只统计唯一的普通不透明瓦片，排除接缝。保留原水面尾部并验证逐字节一致；精确合并前后核对完整材质、颜色、光照、方向、平面的有向面积。

对照包含三条路径：

1. 当前生产扫描器：矩形上限 16×16 光栅单元，32×32 稀疏页，世界跨度上限 256 方块。
2. 临时 32×32 上限：只扩大扫描矩形上限，页面、世界跨度、材质/光照和归属条件不变。
3. 当前扫描器之后再做排序相邻合并：独立实现的双方向矩形连接，允许跨扫描页，跨度上限 2,048 方块。它沿用 VSS 的完整属性和归属分组。此原型参考 DH 的排序连接思路，并未移植 DH 的列降采样、垂直段压缩、数据结构或渲染树，不能代表完整 DH 的性能。

显示变体在当前生产准入判断之前记录候选面数及已用额外驻留预算。插桩只发生在临时副本中，不改变准入结果。统一 tier 统计用于诊断可简化的工作量；真实相机仍需根据误差、距离、morph 和混合覆盖选择。

使用 Python 3.14、NumPy 和 JDK 21，在任一仓库根目录运行：

```powershell
python tools/prediction/merge-review-experiments/replay_merge_limits.py `
  --capture C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry `
  --output C:/Users/Administrator/.codex/tmp/vss-dh-merge-review-20261012/limits
```

输入语料来自 2026-10-10，缺少有效相机。材质适配使用捕获的 leafClass、原版雪和草/水草 sprite 名称，缺少完整方块注册表及 tag 快照；不适合外推所有整合包的植被数量。输出记录源文件 SHA256、逐瓦片面数、准入原因及统计结果。两个候选均只存在于实验类中，未进入生产代码。

本次最终结果和比较依据保存于仓库的 `docs/history/rendering/vss-voxy-dh-merge-review-20261012.md` 和同名 JSON。
