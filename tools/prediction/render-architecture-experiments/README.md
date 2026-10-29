**渲染架构 A/B 证据**

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

本目录保留本轮原始数据和已撤回显示层级候选；生产源码只保留页组缓存、溢出页批量提交及计数修正。两仓库都有相同报告和结果。没有游戏帧率 A/B。

结果在 results/neoforge、results/forge：regional-submission.csv 是 3,072 个绘制条目的精确修改前/后提交循环；overflow-submission.csv 是实际填满主 arena 后的 384 个地块。mode=0 是本场景基线，mode=1 是候选。encoded_items 是累计编码项，需取相邻帧差；uploaded_* 是本帧值。全部使用 24 轮预热、41 轮交替记录。

results/rejected-display.json 保存 2,019 个捕获网格的逐网格比较。BaselineDisplayGeometry.java 是本轮前源码；RejectedCoarseFirstDisplay.java 是已撤回的最粗优先候选。它们位于 tools，只有重放脚本将其作为生产算法适配编译，不参与模组打包。

PowerShell 运行提交 A/B 和回归：

```powershell
# NeoForge 使用 JDK 21；Forge 使用 JDK 17。
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
.\gradlew.bat --offline --console=plain -I tools/prediction/render-architecture-tests.gradle architectureRegressionTest architectureGpuTest '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar'
```

Voxy JAR 路径用于实际 final blit 深度回归，按本机安装路径调整。测试脚本把测试主 arena 限到 128 MiB，以验证真实“池满→溢出→回收”路径；生产默认值保持 2 GiB。

需要原捕获目录及带 compression.zstd、numpy 的 Python，重放已撤回显示候选：

```powershell
python tools/prediction/replay-display-architecture.py --capture C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry --baseline tools/prediction/render-architecture-experiments/BaselineDisplayGeometry.java --candidate tools/prediction/render-architecture-experiments/RejectedCoarseFirstDisplay.java --output build-render-architecture-20261011/rejected-display-replay
```

源码工作树基线额外备份在 C:/Users/Administrator/Desktop/vss-render-architecture-20261011-baseline。详细判定、口径和 JAR 哈希见 docs/vss-render-architecture-20261011.md/json。
