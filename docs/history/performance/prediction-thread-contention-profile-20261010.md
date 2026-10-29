# 主线程、渲染与后台争用采样（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

## 结论

本次游戏内低帧更直接的原因是渲染主线程自身执行大量 VSS 场景准备、绘制范围过滤、间接命令重建，以及客户端预测调度。没有观察到集成服务器或 C2ME 工作线程持续占满 CPU、让渲染线程长时间等待的证据，也没有复现旧版 Windows CPU 查询持预算锁造成的长等待。

Minecraft 的客户端逻辑、客户端 tick 回调和绘制在本次客户端的同一个 `Render thread` 上执行。因此，VSS 在客户端 tick 中做规划，会直接占用这一线程的帧预算；它不是另一个独立的“主线程”在和渲染线程抢锁。后台预测仍会争用 CPU 缓存、内存带宽和分配资源，但当前首先有证据需要处理的是渲染线程上的实际计算和重建。

本轮完成采样和定位，没有修改生产源码、配置、并发、优先级或客户端 JAR，没有重启游戏或操作 GitHub。报告同步到两个仓库；实测仅覆盖 NeoForge 1.21.1。

## 实际版本与方法

- 客户端 PID：35680；Java 25.0.1；Minecraft 1.21.1；NeoForge 21.1.249。
- CPU：AMD Ryzen 7 7700，8 个物理核心 / 16 个逻辑线程。
- 实例：`F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249`。
- 当前安装 `vss-0.3.5-neoforge-1.21.1.jar`：5,868,079 字节，SHA256 `0F973673BD1E081831279C63E176D5B62E1FF6130FBD50AE518A8DCC48D00DAB`。
- 包与仓库 `lib/vss-0.3.5-neoforge-1.21.1.jar`、`lib/chunky-queue-limit-20261010/vss-0.3.5-neoforge-1.21.1.jar` 一致。它已经包含转向热点优化，不能套用上一轮旧包的结果。
- 11 个重点类及其内部类共 56 个 `.class` 与 `build-turn-optimization-20261010` 逐字节匹配。9 个关键生产源码与已有优化验证记录中的源文件 SHA256 匹配；另保存源码、字节码核验结果和 JAR 副本。
- 主 JFR：香港时间 17:56:41.706–17:57:56.707，约 75 秒。执行 / native 采样周期 5 ms，monitor enter / park 门槛 2 ms；记录 CPU load、GC 和分配。
- 独立后台诊断线程约每 2 秒记录暂停、界面、FPS、视角、渲染计数、CPU / GPU 阶段累计计数及 Java 线程 CPU / 分配总量。临时开启已有的 `vss.renderTimings`，不修改生产类，不在诊断线程调用 GL，不读取可变区块数据。
- 主录制中的有效游戏内窗口：17:56:57.875–17:57:17.904，以及 17:57:25.911–17:57:55.934，共 50.0518394 秒。只有相邻两个诊断点都未暂停且无菜单的区间参与统计，聊天、暂停及过渡区间均排除。
- 补录约 32 秒 JFR，并取得 18:03:06.296–18:03:34.315 共 28.018 秒准入计数。该段全部处于暂停菜单，只用于任务准入问题调查，不用于游戏内 FPS 或 GPU 性能对比。

主段诊断单次读取中位约 0.808 ms，第一次约 13.809 ms，发生在独立诊断线程。有效窗口诊断线程累计 CPU 约 0.016 秒。采样和诊断仍有观测开销，未做无采样的严格 A/B。

## 有效游戏内窗口

| 指标 | 实测 | 含义 |
| --- | ---: | --- |
| 渲染帧数 / 有效时间 | 1,983 / 50.052 秒 | 由 frameStarts 增量计算 |
| 实际平均渲染帧率 | 39.62 FPS | 不是游戏 FPS 字段的简单平均 |
| 两秒区间帧率范围 | 28.98–66.97 FPS | 不是单帧最低 FPS 或 1% low |
| Render thread CPU 时间 | 44.797 CPU 秒 | 占一个逻辑核心约 89.50% |
| Render thread 平均 CPU / 帧 | 22.59 ms | 包含客户端逻辑、渲染准备、提交等全部工作 |
| 全进程平均 CPU | 3.72 个逻辑核心的工作量 | CPU 秒 / 墙钟秒，包含 JVM 内部线程 |
| 系统总 CPU | 平均 30.1%，样本峰值 57.3% | 来自 JFR CPULoad；没有持续跑满全部逻辑线程 |
| Server thread CPU | 3.688 CPU 秒，约 0.074 核 | 本窗口内集成服务器主线程负载较轻 |
| c2me 开头线程 CPU 合计 | 0.578 CPU 秒，约 0.012 核 | 只按线程名称归类，不能涵盖其他线程上使用的 C2ME 编译代码 |
| 预测生成线程 CPU 合计 | 103.047 CPU 秒，约 2.06 核 | 后半段升至约 3.11 核 |
| 预测准备 CPU 抽样均值 | 6.503 ms，66 个样本 | 由本轮计时增量计算 |
| 预测不透明 CPU 抽样均值 | 5.904 ms，66 个样本 | 部分准备和编码在该通道内 |
| 预测不透明 GPU timestamp 区间均值 | 5.326 ms，66 个样本 | GPU 仍有成本；不是全游戏 GPU 帧时间 |
| 水面 GPU timestamp 区间均值 | 0.115 ms，66 个样本 | 本轮确实获得新样本 |

CPU 阶段可能包含子阶段，CPU 与 GPU 可并行；上述阶段时间不能直接相加。全进程 CPU 与 Java 线程 CPU 合计也不能相加。

前半段有效窗口约 20.029 秒，预测工作线程只有约 0.477 核，实际仍约 41.89 FPS；后半段约 30.023 秒，预测线程升至约 3.114 核，实际约 38.10 FPS。两段位置、朝向、视野和加载进度不同，不是关闭后台生成的受控对照，不能据此算出后台生成造成了多少 FPS 损失。但前半段说明：后台预测 CPU 较低时，渲染线程自身的瓶颈也已经存在。

## 1. 渲染线程主要在计算，而不是等待另一个线程

有效窗口共有 6,586 个渲染线程执行 / native 样本，其中 Java 执行样本 6,372 个，native 样本 214 个。

| 调用路径 | 样本数 | 占渲染线程样本 |
| --- | ---: | ---: |
| PredictionRenderer.renderStageUnpacked | 4,975 | 75.54% |
| PredictionRenderer.Scene.prepare | 2,238 | 33.98% |
| PredictionRenderer.PreparedPass.prepare | 1,344 | 20.41% |
| PredictionTileManager.tick | 799 | 12.13% |
| PredictionSubmissionCoverage.filter | 711 | 10.80% |
| PredictionVisiblePlan.selectStable | 665 | 10.10% |
| PredictionIndirectBatch.add | 607 | 9.22% |

这是调用栈包含关系和样本占比，不是精确耗时占比。例如归属过滤包含在 pass 准备中，不能把表格全部相加。

关键调用链：

```text
Render thread
  Minecraft.runTick
    Minecraft.tick
      VSSClientNetworking.onClientTick
        ClientPredictionState.tick
          PredictionTileManager.tick
```

```text
Render thread
  GameRenderer.renderLevel
    Sodium / Voxy 绘制入口
      PredictionRenderer.renderStageUnpacked
        Scene.prepare / 可见候选整理
        PreparedPass.prepare / 绘制范围与 Voxy 归属过滤
        drawNormalPass -> PredictionIndirectBatch.add / encodeItem
```

Voxy / Sodium 是预测绘制的父入口，不能把整条栈的时间都归给 Voxy 或 Sodium。

本窗口没有超过 2 ms 门槛的 Render thread `JavaMonitorEnter` 或 `ThreadPark` 事件。未复现早期 CPU 查询持锁导致几十至上百毫秒阻塞。Windows CPU 查询的 native 样本出现在独立 `vss-cpu-budget-sampler` 上。

Render thread 中约 83 个样本经过 `glfwSwapBuffers`，约占 1.26%。没有出现以交换缓冲或 native GL 调用为主的调用栈分布。JFR 的 native 样本不能准确量化 GPU 等待，也不能代替完整 GPU 帧计时；本次结论是 CPU 重计算有明确证据，而不是断言 GPU 完全没有瓶颈。

## 2. 移动时计划和范围仍频繁重建

有效 50.052 秒内：

- 绘制计划准备 1,960 次，复用 24 次；计数后台读取存在帧边缘误差，不能要求与 1,983 帧严格逐一相等。
- 可见列表发布 1,152 次。
- 可见性访问 15,319,917 次，约每帧 7,726 次。
- 排序编辑 162,922 次；不能全部叫作无用排序，镜头变化确实需要更新成员和顺序。
- 网格上传 681 次。
- 间接计划身份快路径命中 3,287,088 次，完整内容检查仅 1,770 次。

最后一项说明：上轮身份 / 版本短路已经生效，不能再说“每条静态记录每帧都做完整签名比较”。剩余热点更多在可见成员、归属与绘制范围变化后重建，以及重建后的命令重新编码和 PlanItem 分配。

`Scene.prepare`、视锥检查、`PredictionDrawRanges` 构造、`PredictionOwnershipRuns.filter`、`PredictionIndirectBatch.encodeItem` 在本轮均有直接执行样本。当前保存的候选集合复用和分页快照也确实存在，但移动仍使大量 draw / range 身份和批次内容发生变化，后续准备不能直接跳过。

继续优化应沿着变化传播链追踪：哪些成员真的改变、哪些归属证明或距离桶真的改变、哪些范围仅创建了等价对象。将网格、GPU 分配、归属和显示档位的静态记录保留，只对受影响记录更新；当前帧视锥、覆盖撤销、透明排序仍需及时正确。

## 3. 后台确有额外压力，并存在准入后的快速拒绝路径

主有效窗口预测线程池完成 324,732 个任务，约 6,488 次/秒。这个计数是线程池任务结束数，不是有效网格构建数，不能和上传数直接等同。预测线程在后半段执行植被放置、Rust surface / display / exterior 采样，以及 C2ME 编译后的密度函数代码。

补采暂停窗口中：

| 计数增量 | 28.018 秒内 |
| --- | ---: |
| 预测线程池完成任务 | 46,060 |
| builtTiles | 16 |
| ADMIT_CPU_BUDGET | 65,244 |
| 其他 ADMIT 拒绝原因 | 全部为 0 |
| 失败构建 | 0 |
| contentionDeferrals | 0 |

`ADMIT_CPU_BUDGET` 同时在入队前预约失败和工作线程最终 lease 失败处增加，因此大于线程池完成数是可能的，不能把它除以任务数得到“拒绝百分比”。暂停窗口也不能用于解释游戏内 FPS。这些计数与实际代码共同确认：预算紧张时仍存在大量快速结束、再次尝试的调度工作。

对应当前代码：

1. `PredictionCpuBudget.tryReserveQueued` 仅检查 `queuedOverflow` 上限，没有在这里约束实际 `active` / 生成压力 / 细节配额。
2. 工作线程启动后消费预约，再调用 `tryAcquire` 最终检查。
3. `PredictionTileManager` 在最终 lease 失败时计数并立即返回，未设置该键的重试退避。
4. `finally` 清除 pending；下一轮规划又可为同一仍欠缺的目标生成请求。

需要保留有界队列维持有效吞吐，但让最终拒绝与容量变化联动，合并每键重复请求，在配额释放或短暂退避后重试，并正确处理取消和预约归还。直接增加线程或提高优先级不能消除这个循环。主游戏窗口没有同步取得每种拒绝原因增量，因此本报告不声称已经精确算出主窗口中多少任务因预算退出。

## 4. 分配与 GC 加重部分卡顿

有效窗口精确线程分配增量：

- Render thread：4,153,683,880 字节，约 3.87 GiB，79.14 MiB/s。
- 预测生成线程合计：7,990,850,024 字节，约 7.44 GiB，152.26 MiB/s；后半段约 191.58 MiB/s。

这些都是累计分配量，不是同时驻留内存或内存泄漏。

加权分配样本中，渲染线程突出来源为 HashMap 节点及扩容数组、PlanItem、临时集合数组、LOD 规划 lambda、覆盖 bool 数组和 DrawRanges 等。`PlanItem` 单项加权估算约 364 MiB，仅用于定位分配来源，不替代线程分配精确总数。

有效窗口 GC 暂停 22 次，累计 169.458 ms，最长 17.313 ms。它会加重个别长帧，但不足以单独解释约 40 FPS 的持续低帧。

## 后续实施优先级

1. 收紧队列预约与最终 lease 的衔接，避免拒绝后 pending 清空引发重复入队；保留必要覆盖的恢复与有效吞吐。
2. 优化移动时 `Scene.prepare`、Voxy 归属过滤及 DrawRanges 更新传播，复用真正等价的静态记录，减少整套 pass 和命令计划重建。
3. 按变化记录更新间接命令，减少 PlanItem、HashMap 和范围数组分配；保留已经实测生效的身份快路径。
4. 进一步减少客户端 tick 中的完整视野调度 / 优先级整理工作。任何后台化都要基于安全快照，并按同版本交接网格、遮罩与接缝。
5. 用同位置、同朝向、同加载状态复测 CPU / 帧、准备时间、计划重建、任务有效完成量、分配及 GPU 区间。

普通 JFR 没有本轮 Windows 线程 ready-time / 完整上下文切换跟踪，不能严格量化操作系统抢占、SMT 或缓存争用的份额。当前结论由实际线程 CPU 时间、调用栈、锁等待和系统 CPU 共同支持。GPU 全帧以及受控关闭后台工作的 A/B 不在本轮测量范围。

## 原始证据与采样状态

目录：`C:/Users/Administrator/.codex/tmp/vss-thread-contention-20261010-175700`。

- `contention.jfr`、`live/counters.ndjson`：主录制与原始两秒诊断。
- `ranges.tsv`、`intervals.json`、`counter-summary.json`、`jfr-report.json`：有效区间、计数和调用栈分析。
- `admission.jfr`、`admission/counters.ndjson`、`admission/counters-repaired.ndjson`、`admission-summary.json`：补录与任务准入数据。
- `loaded-vss.jar`、`loaded-package-check.json`、`loaded-inner-class-check.json`：实际包冻结副本和核对结果。
- `inspected-source/`、`inspected-source-manifest.json`：与实际构建核对过的源文件快照。
- `ThreadContentionAgent.java`、`ThreadAdmissionAgent.java`、`ThreadContentionReport.java`、`counter-report.py`：诊断及离线分析工具。

补录诊断工具误将 `ADMIT_REASONS`（数组长度常量）当成有效索引，导致原始 JSON 的 admission 对象未闭合。离线修复只移除了这个无值常量并补齐对象结束符，保留实际读取的各 ADMIT 计数；原始文件未改写，异常字段也保留。`cpuBudgetFields` 未成功取得，不参与结论。主录制原始 JSON 正常。

两段录制均已结束，两个诊断任务均写出 complete / restored 标记，原先不存在的 `vss.renderTimings` 属性已恢复为不存在。最终 `JFR.check` 确认没有剩余录制。
