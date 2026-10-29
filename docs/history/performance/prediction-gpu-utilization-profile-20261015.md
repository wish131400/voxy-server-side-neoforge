# 当前显卡低占用调查（2026-10-15）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮只调查运行中的 NeoForge 客户端，未修改生产源码、游戏设置或客户端 JAR；没有提交或推送 GitHub。临时观测线程和两段 JFR 均已结束。Forge 仓库保存相同报告，但没有对 Forge 客户端进行实时测量。

## 结论

当前有两个不同原因：失焦暂停时，客户端主动维持约 30 FPS；回到游戏后，主要限制来自 VSS 渲染线程的场景选择、绘制范围准备及批次组装／提交。正常游戏窗口中 GPU 平均利用率约 50%，核心保持 2805 MHz，而渲染线程占用约 88% 的单个逻辑核时间。没有证据表明需要解除某个 GPU 性能限制，也没有观察到显存耗尽或明显的 GPU 温度／功率限制。

这次不能将“显卡利用率 50%”换算成可直接翻倍的 FPS，也没有测量完整逐帧 GPU 时间。结论依据是同步的帧数、前后台状态、线程 CPU 时间、GPU 状态和 JFR 调用栈。

## 环境和版本

- 客户端 PID 41664，Minecraft 1.21.1 / NeoForge 21.1.249，Java 25.0.1。
- CPU：AMD Ryzen 7 7700，8 核 / 16 逻辑线程。
- GPU：NVIDIA GeForce RTX 4070 Ti SUPER，16 GiB，驱动 572.70。
- 实际窗口帧缓冲：3840 × 2066。配置 `maxFps=260`、`enableVsync=false`、原版视距 5，Iris 的 `enableShaders=false`。
- 实例：`F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249`。
- 安装包 SHA256：`1d2bccb7578bea740553dd8285bb051b94ff8c0235c25a05b5089273a45430db`。
- 上轮交付包 SHA256：`211e8e0f5b06eed1b63a1d9eb1cdd9c4910e3bb8043e79c66cb14d104f0f8e1e`。
- 两个 JAR 都为 6,088,674 字节。逐 ZIP entry 比较，仅 `META-INF/MANIFEST.MF` 不同；全部类文件、资源和原生库字节相同，不能因整体 SHA 不同就判定加载了旧代码。

## 方法与有效窗口

使用 `nvidia-smi` 约每秒读取全显卡状态；临时后台只读 Java agent 每 2 秒读取 FPS、暂停／焦点／菜单状态、窗口缓存尺寸、渲染累计计数及 ThreadMXBean CPU 时间。不转换类、不调用 GL、不读取或修改世界区块、不切换游戏窗口，也未开启原有 GPU 分阶段计时。

两段 JFR 均约 35 秒，Java / native 执行采样配置为 5 ms，monitor / park 事件门槛为 2 ms。临时 observer 为最低优先级、固定时长的 daemon 线程。观测和 JFR 仍有开销，因此不作为无诊断环境的绝对 FPS 基准。

香港时间（UTC+8）：

1. 暂停计数窗口：00:05:24.514–00:05:54.539，16 次快照全部为 PauseScreen、paused=true、active=false。累计约 899 帧／30.025 秒，约 29.94 FPS。
2. 游戏内计数窗口：00:06:56.556–00:07:26.577，16 次快照无暂停。剔除两端包含失焦快照的区间，保留 13 段两秒区间，共 **26.017701 秒、2050 帧**。最后一个恢复焦点后的区间保留；不能认为所有区间画面完全一致。
3. 游戏内 JFR：00:06:54.781–00:07:29.772。正文的热点只统计上述有效前台窗口，得到 **2487 个 Render thread 执行／native 样本**；原始完整窗口分析另外保存。

每两秒只观察一次状态，无法排除两次观察之间极短的状态变化；GPU 读数也是采样周期内的全卡利用率，包含桌面合成等其他进程，不是仅 Java 进程的 GPU 时间。

## 同步结果

| 指标 | 游戏内有效窗口 |
| --- | --- |
| 按累计渲染帧数计算的帧率 | **78.79 FPS** |
| 两秒平均帧率范围 | **59.97–129.94 FPS** |
| GPU 利用率 | **平均 49.56%，中位 51%，范围 34–62%**（27 个对应读数） |
| GPU 核心频率 | **全部 2805 MHz，P0** |
| GPU 功耗 | **平均 112.41 W，范围 94.03–129.58 W**，功率上限 285 W |
| GPU 温度 | **52–56℃** |
| 显存占用 | **6372–6393 MiB / 16376 MiB**，约 39% |
| Render thread CPU 时间 | **22.828 秒 / 26.018 秒，87.74% 的一个逻辑核** |
| Java 进程 CPU 时间 | **31.594 秒**，平均 1.214 个逻辑核，折算 16 线程 CPU 的约 7.59% |
| 绘制计划准备 / 复用 | **2051 / 0 次** |
| 网格上传 | **4 次** |
| 预测提交调用（VSS 累计计数） | **平均约 477 次／帧** |
| 提交候选四边形（VSS 累计计数） | **平均约 738 万／帧**，不能当作最终通过剔除的可见面数 |

不同计数逐项读取，不是暂停渲染后的一次原子快照，帧数与计划准备数可能相差约 1 次。FPS 范围为两秒均值，不是逐帧最小值或 1% low。暂停窗口不是同画面的性能 A/B。

游戏进程 CPU 总占比低与渲染线程接近跑满可以同时发生：任务管理器的进程 CPU 百分比按 16 个逻辑线程归一化，单个关键线程已经很忙时，整体仍然只有几个百分点。

## 热点与源码对应

以下为有效窗口 2487 个渲染线程执行／native 样本中的包含关系；它们不是精确耗时百分比，存在嵌套，不能相加。

| 调用路径 | 样本 | 占渲染线程样本 |
| --- | ---: | ---: |
| `PredictionRenderer.renderStageUnpacked` | 1842 | **74.1%** |
| `PredictionRenderer$Scene.prepare` | 692 | **27.8%** |
| `PredictionRenderer$PreparedPass.prepare` | 532 | **21.4%** |
| `PredictionVisiblePlan.selectStable` | 221 | **8.9%** |
| `PredictionIndirectBatch.add` | 187 | **7.5%** |
| `PredictionVoxyBoundaryBridge.bind` | 111 | **4.5%** |

叶节点集中在 HashMap / IdentityHashMap 查询、视锥盒测试、绘制记录读取、方向和归属判断、批次编码等。GPU 调用也有成本，但本次不能将这些样本直接换算为 GPU 阶段毫秒数。

源码能解释计数：

- `PredictionFramePlan.getStable` 要求 camera、modelView、projection 都完全相等。移动、转向或画面矩阵变化会离开整帧计划复用途径。
- `PredictionRenderer.Scene.prepare` 在镜头变化时仍进行空间候选、接缝候选和可见成员检查。已经有空间粗筛和稳定距离桶，因此不能说每帧都做完整世界重建；但候选整理自身仍有明显 CPU 成本。
- `PreparedPass.prepare` 只有输入列表身份、网格、归属、GPU 驻留与动态细节条件匹配时才能直接命中；否则逐条重建 pass 元数据。内部范围缓存能继续复用部分范围，不等于整个循环已经跳过。
- `PredictionIndirectBatch.replayPass` 需要 pass 身份和 revision 等条件稳定。它上游不断变化时，仍进入批次添加、分组和部分编码路径。
- 当前 GPU 驻留候选选择也有明确适用门槛：相机位置不变的转向、足够数量的瓦片、Hi-Z 可用且没有接缝等。源码不能支持“所有移动场景都由 GPU 接管细粒度候选选择”的说法；本次未专门记录其每帧拒绝原因。

上轮变更日志优化主要减少无关 mesh／GPU 版本更新导致的重验证，Iris 合批优化则作用于满足条件的 Iris 渲染路径。当前未开启光影，又在持续改变视角，不能把那些离线场景的收益直接套成此次游戏内 FPS。

## 其他排查结果

- 有效窗口基本没有网格上传，却接近每帧准备计划，不能将主因解释为不断上传新地形。
- 30 秒观察中 Render thread 消耗 25.547 CPU 秒；服务器线程约 1.031 秒，边界后台线程约 0.969 秒。此窗口没有支持“后台生成占满所有 CPU，把渲染线程挤下去”的证据。
- 有效 JFR 窗口未记录到 Render thread 超过 2 ms 门槛的 Java monitor / park 等待；这不排除短等待、调度延迟或驱动内部等待。
- 有效窗口有 4 次 GC pause，总计 **18.67 ms**，最长 **6.84 ms**。它可能影响个别帧，但不能解释整个窗口持续的 CPU 热点和 GPU 中等利用率。
- 先前单次主线程快照碰到客户端实体 tick；完整采样后，实体／客户端 tick 占比远小于 VSS 准备路径，不能凭那张快照认定实体 tick 是本次主因。
- 暂停段限帧路径 `RenderSystem.limitDisplayFPS -> glfwWaitEventsTimeout` 有 398 / 928 个渲染线程样本；有效游戏内窗口没有采到该限帧路径。结合实际帧数低于 260、VSync 关闭，支持区分后台 30 FPS 限帧与游戏内 CPU 成本。

## 后续优化目标

优先让镜头变化复用稳定的候选记录、局部 bounds 和静态命令，把当前视角选择与网格内容／归属更新进一步分离；在保守可见性和接缝安全前提下缩小 `Scene.prepare`、`PreparedPass.prepare` 和下游提交的更新范围。需要用相同画面 A/B 验证，不能直接放宽所有旧剔除结果的寿命，也不应为了提高 GPU 占用而增加无效绘制。

## 原始证据

位于 NeoForge 仓库 `tools/prediction/gpu-utilization-20261015/`：

- `GpuUtilizationAgent.java` / `observer.jar`：有界只读观测工具。
- `observe.jfc`：本轮 JFR 配置。
- `results/`：失焦暂停段的 `runtime.jsonl`、`gpu.csv`、`thread-cpu.tsv`、JFR 和分析。
- `active/`：游戏内段相同证据；`active-windows.tsv` 定义有效前台窗口，`jfr-analysis-active.json` 是按其过滤的热点。
- `analyze.py`：从原始运行计数和 GPU CSV 重算统计。
- `GpuJfrReport.java`：执行／native 样本分析，支持窗口过滤；另外保存了旧通用 `JfrDeepReport` 的辅助输出，正文统计以本轮专用分析为准。

复算运行计数：`python tools/prediction/gpu-utilization-20261015/analyze.py`。所有 GPU 调用仅来自正常游戏渲染，采样没有启动离线 GPU 基准。
