本目录保存 2026-10-14 远景粗网格试验。最终生产改动仅在 `PredictionDisplayGeometry`：既有策略无法生成任何可用表示时，补做一次按实际高度保护水、模型和边界的构建。已有可用表示保持原样。

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

`final-results/rows.json` 是最终 2,019 网格重放结果。`comparison-src` 的算法文件与生产源码相同，只有 Minecraft 材质查询由最小适配器提供。输入来自 10 月 10 日历史几何捕获，不是 10 月 14 日海岸截图的新采样。各网格分别放在同一测试距离，不能把候选面数当成当前游戏画面的面数。

`baseline` 保存本轮开始时 NeoForge 工作树的源码，不是 Git HEAD。`rejected-terrain-only`、`height-only.java.txt` 和其余 `*-results` 保存探索过程；它们不属于交付实现。Forge 保留自身的 PackedMesh、Renderer 和平台适配代码。

完整重放需要原历史捕获目录及 Python 的 numpy、zstd 支持：

```powershell
python tools/prediction/far-terrain-20261014/replay.py --source tools/prediction/far-terrain-20261014/comparison-src --main FarTerrainReplay --limit 0 --output tools/prediction/far-terrain-20261014/final-results --capture <geometry-directory>
```

CPU 和 GPU 回归使用目录内精简的 11 个真实网格 fixture，不需要完整捕获。fixture 只有局部几何与材质分类，没有世界种子、相机或玩家信息。

NeoForge，在 NeoForge 仓库执行：

```powershell
.\gradlew.bat --offline --no-daemon -I tools/prediction/far-terrain-tests.gradle turnMainRegressionTest farTerrainCpuTest turnMainGpuTest farTerrainGpuTest jar
```

Forge，在 Forge 仓库执行：

```powershell
.\gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' '-Dnet.minecraftforge.gradle.check.certs=false' -I tools/prediction/far-terrain-tests.gradle turnMainRegressionTest farTerrainCpuTest turnMainGpuTest farTerrainGpuTest jar reobfJar
```

GPU 测试创建隐藏的 OpenGL 窗口，不启动 Minecraft。两平台 GPU 测量应依次进行。测试在固定诊断视角绘制按 4,096 / 8,192 方块、900 像素投影尺度、2 像素误差选择的范围，使用替代图集，每个网格重复提交 64 次。结果用于比较组件成本，不能换算为游戏 FPS。

两平台 CSV 和 JUnit XML 位于各自 `build-far-terrain-20261014`。完成双平台打包后，运行 `python tools/prediction/far-terrain-20261014/analyze.py`，核对生产同步、回归、JAR 字节码版本、原生资源及测试资源排除，并生成两边的验证 JSON 与交付校验文件。

诊断开关 `-Dvss.disablePredictionHeightProtection=true` 只关闭本轮新增回退构建。粗表示在网格构建时产生，改变该开关后需要重启客户端。正常使用无须添加此参数。
