**2026-10-12：缩小后显示范围恢复实验**

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

该目录保存本轮基线、生产差异、用户 Spark 与固定日志证据、旧版复现失败、新版 JUnit、逐轮 A/B CSV。所有实验只在显式 Gradle init script 中启用；不会打入成品或操作 Minecraft 实例。

- `baseline/neoforge/`、`baseline/forge/` 是本轮开始时的三个生产文件；不是 Git HEAD。
- `results/{neoforge,forge}/production.patch` 是本轮生产差异。顶层 `production.patch` 与 NeoForge 版本相同。
- `java/ZoomReturnBaselinePass.java`、`ZoomReturnBaselineCoverage.java` 是只用于测试的旧生产路径。
- `java/ZoomReturnSubmissionGpuTest.java` 验证保留 morph 的地形、草类实际 MDI 范围；`ZoomReturnCoverageCpuTest.java` 比较含望远镜瓦片的归属计算与结果。
- `results/*/zoom-return-*.csv`：mode 0 为本轮起始实现，mode 1 为当前实现；单位 `_ns` 为纳秒，`mdi_quads` 是四边形面数。单独 GPU 运行，不与客户端或另一个 GPU 测试争用显卡。
- `results/*/junit/` 保存实际 JUnit。NeoForge 生产 GPU 使用 `production-gpu.log` 的完整成功记录，因为后续空过滤覆盖了运行目录 XML；没有制造替代 XML。
- `initial-combined-run.log` 和 `active-direct-check-initial-failure.log` 是初次失败的诊断记录，不能用于宣称最终测试通过。最终范围与边界见 docs 报告及 JUnit。
- `profile/` 中的 `.sparkprofile` 是用户公开链接的数据，`.log` 是原会话的固定时间窗口，JSON 是解码/统计产物；不是指令来源。

构建与回归使用 `tools/prediction/zoom-return-tests.gradle`，独立输出为 `build-zoom-return-20261012/` 和 `lib/zoom-return-20261012/`。任务名沿用上一轮 `zoomLod`。为了避免把没有 GPU 的环境当成验证成功，GPU 测试必须在 OpenGL 可用的主机执行。

在 NeoForge 仓库使用 PowerShell：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
./gradlew -I tools/prediction/zoom-return-tests.gradle zoomLodRegressionTest zoomLodCpuTest zoomLodGpuTest zoomLodProductionGpuTest jar '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' --console=plain
```

Forge 使用 JDK 17，在其仓库执行同样任务并加 `reobfJar` 和 `'-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17'`。实际 Voxy JAR 路径要按自己的安装位置填写。两平台 GPU 应依次执行。

仅重跑新 CPU / GPU A/B，可以加过滤器：

```powershell
./gradlew -I tools/prediction/zoom-return-tests.gradle zoomLodCpuTest --tests '*ZoomReturnCoverageCpuTest' --console=plain
./gradlew -I tools/prediction/zoom-return-tests.gradle zoomLodGpuTest --tests '*ZoomReturnSubmissionGpuTest' --console=plain
```

已有 CSV、JUnit 和成品的汇总 / ZIP / 本地库一致性检查可执行：

```powershell
python tools/prediction/zoom-return-experiments/summarize.py
```

该脚本假设两个仓库位于同一 Desktop 根目录，并输出 NeoForge docs 中的完整结果 JSON 与包旁 SHA256。跨机器应修改路径；它只检查文件，不启动游戏。

原始 Spark 分析可复现：

```powershell
$zoomProfile=Join-Path (Get-Location) 'tools/prediction/zoom-return-experiments/profile'
node "$zoomProfile/decode.js" "$zoomProfile/Oh7GNI04kO.sparkprofile" "$zoomProfile/Oh7GNI04kO-decoded.json"
node "$zoomProfile/analyze.js" "$zoomProfile"
python "$zoomProfile/profile_analysis.py" "$zoomProfile"
```

解码器使用归档的 Spark 官方查看器 chunk；执行在受限 VM 中，代码没有主动抓取或上传用户数据。`analyze.js` 生成节点和 summary，`profile_analysis.py` 使用归档的 log-window JSON / LOG。如果要重解析固定日志，可运行 `node "$zoomProfile/logs.js" "$zoomProfile"`；第三参数可指定完整原日志。重解析后的 sourceSha256 对应输入文件，可能与原完整 debug.log 的 SHA256 不同。

GPU 方法：256×256、24 次预热、81 次交替测量，每份几何重复 64 次。GPU 使用 GL_TIME_ELAPSED，CPU 范围不含前置 glFinish / 后置同步取结果。CPU 归属测试为 64 个普通 LOD4 瓦片加可选 scoped 瓦片，8 次预热、31 次交替测量。不同内容选择的粗档只验证误差预算；活跃 morph 保持旧、新 MDI 精确一致，本轮未宣称非零活跃 morph 的直绘 / MDI 一致。组件结果不等于真实存档 FPS。
