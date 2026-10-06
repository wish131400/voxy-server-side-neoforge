# 2026-10-06 进存档低帧采样

本轮按用户要求采样定位，未修改生产代码、游戏设置或安装包。实际客户端是 Forge 1.20.1 / DeceasedCraft_Beta，PID 66060，Java 21。安装包 SHA-256 为 `0d3e8ec6d31b37a5fc9d587d5e0afdea78e0991ec2bf4482a52cb94f324fbce1`，与闪烁修复包一致。

## 采样范围

- 证据目录：`C:/Users/Administrator/.codex/tmp/vss-entry-profile-20261006/`。
- 用户重新进档，10:50:40 进入游戏，10:50:45.627 预测 ready，日志确认 `rust=true`；世界生成快照准备约 5.014 秒，在后台完成。
- 主 JFR：`active.jfr`，执行样本窗口 10:50:33.855–10:51:33.913（香港时间），包含进入世界前几秒和进入后约 54 秒。
- 主计数窗口：`counters/sample-00.txt` 至 `sample-12.txt`，10:50:48.141–10:51:24.377，共 36.236 秒，13 次主线程只读快照。相机有约几十格以内移动，不是严格固定视角基准。
- 后期 JFR：`later.jfr`，40 秒；后期计数窗口 10:52:20–10:52:56，36.398 秒。日志显示 10:52:08 暂停，后期快照前半段约 30 FPS，后半段恢复。因此只用于热点是否仍存在的辅助证据，不能将前后平均 FPS、接缝比例或分配速率变化全部归因于加载完成。
- `entry.jfr` 是用户重新进档协调前的探索录制，不作为本报告主结论依据。
- 录制已自动结束，两次只读诊断线程均留下 `done.txt`；没有常驻新增采样循环。

## P0：CPU 限流的共享锁阻塞渲染线程

主 JFR 中 `Render thread` 等待 `PredictionCpuBudget.observeLocalGeneration` 的 monitor 累计 **987.335 ms**，`allowsDetail` 另有 **51.234 ms**。最长一次在 10:51:24.698 开始，持续 **129.097 ms**，前一持锁线程是 `vss-prediction-overworld`。其他长等待为 81.604、68.099、62.031 ms。这是实际主线程阻塞时间，不是用 FPS 推测的 GPU 耗时。

源码 `PredictionCpuBudget` 的 synchronized 准入/查询方法内部调用 `refresh()`，其中调用 Windows 系统/进程 CPU getter。JFR 同时抓到 `OperatingSystemImpl.getProcessCpuLoad0 -> refresh -> localGenerationLimit/allowsDetail` 的主线程栈，以及 `getProcessCpuLoad0 -> refresh -> tryAcquire` 的预测工作线程栈。

结论：系统负载探测位于共享锁内，并且能在主线程执行。后台进行探测时会阻塞主线程，主线程也会自己执行该系统调用。不能把全部 monitor 等待精确分解为系统调用、调度和其他锁内耗时，但此调用位置已经是不合理的延迟耦合。

后期仍记录到 `observeLocalGeneration` 累计 318.039 ms 等待，说明不是仅有一次初始化。

建议：由单个后台采样者每 500 ms 更新带时间戳的不可变负载快照，OS 调用不持预算锁。主线程和 worker 读取快照，准入临界区仅更新计数；保留帧率熔断、过期策略、恢复滞回和 lease 释放语义。加入阻塞的假 CPU supplier 回归，验证它无法卡住主线程观察、准入和释放。

## P0：预算拒绝后的重复入队

主计数窗口内：

| 指标增量 | 数量 |
| --- | ---: |
| executor completed | 78,593 |
| admission.cpuBudget 拒绝 | 76,298 |
| built（含重建/精化，不是唯一瓦片） | 1,906 |

约 **97.1%** 的 executor 完成数量对应预算拒绝，而不是完成有效构建；约 2,106 次拒绝/秒。统计快照不是跨线程事务，允许少量边界差异，但不影响数量级。

`PredictionTileManager.enqueue` 的 worker 启动后才 `tryAcquire`，失败直接 return，finally 移除 pending。该路径没有预算拒绝重试期限；`cacheOnly` 路径还会调用 `restoreCached`。这些任务很快又具备入队资格，造成任务分配、队列操作、同步和重复检查。已有 `buildAdmitted` 防护只限制部分后续补队，并未阻止本次观测到的拒绝风暴。

建议：入队前做有界预算预留或容量提示，保留执行时的最终校验；拒绝后等待容量变化/带界限的退避再重试，避免每帧或每次补队重复提交。测试多管理器、关闭、取消、执行器拒绝、预生成竞争下的计数回收与新覆盖不饥饿。不要简单取消限流。

## P1：加载成果发布引发主线程接缝重建

主 JFR 共 1,465 个渲染线程执行/原生方法样本：

- 649 个栈含 `PredictionRenderer.onRenderLevel`，约 44.3%。
- 384 个含 `Scene.prepare`，约 26.2%；其中 209 个含 `PredictionLodSeams.apply`，约 14.3%。这些包含关系不能相加。
- `stitch`、`edge`、`exteriorEdge`、`PredictionBoundaryWalls`、`PredictionPackedMesh.terrainRecords` 均有实际热点。

主计数窗口有 1,412 次网格上传、23,050 次接缝 surface 更新、1,509 次 wall-index build。preparedPlans 增加 2,955，reusedPlans 增加 526，约 84.9% 走准备路径。此时 resident tiles 从 319 到 1,600，持续发布新地块/精度替换使邻接关系反复变化。

源码已对部分 boundary mask 并行化，但 `Scene.prepare -> lodSeams.apply -> stitch -> terrainRecords` 仍在渲染线程执行。OpenGL 上传需要渲染线程，不代表接缝几何计算也必须占用它。

建议：先合并同帧多次地块替换及邻接失效，再对不可变场景/归属快照异步计算接缝。地块、覆盖 mask 和接缝必须按同一版本原子交接，结果过期则丢弃，未就绪保留完整旧组合。上传和发布预算要覆盖整组必要接缝，不能仅延迟补缝而先撤旧网格，否则会重新漏天空或闪烁。这项改动复杂度高于前两个，应独立实现并跑真实 GPU 接缝/交接回归。

## P1：缓存签名、材质序列化与中间网格分配

主 JFR 的 3,186 个预测线程执行/原生方法样本，以最靠近栈顶的 VSS 方法分类：`PredictionMeshCodec.signature` 496 个（15.6%）、缓存地形解码 208、压缩 157、`materialBlocks` 147，另有其 lambda 77、`PredictionMeshCodec.ints` 137。JNI 样本不能完整呈现 Rust 内部 CPU，不能将这些数字解释为所有预测成本的精确占比。

`writeMaterial()` 每写一行材质都会调用 `materialBlocks()`；后者在同步锁内新建 256 项数组、遍历多张完整材质表。缓存网格写入会因此重复重建整张材质到方块映射，并与渲染端的材质查询争锁。

真实 `ThreadAllocationStatistics` 首尾差分：

- 新建的 8 个预测线程在共同的 **34.596 秒有效统计窗口**内累计分配 **38,861,335,272 bytes（36.19 GiB）**，合计约 **1,071 MiB/s**。
- 渲染线程统计窗口更长，累计约 12.37 GiB，平均约 211 MiB/s。不能把整个渲染线程分配都归给 VSS。
- 加权分配样本仅用来定位来源：`VertexAccumulator` 扩容/导出、`QuadStorage`、压缩 byte[]、缓存读入 byte[]、接缝 int[]。这些是临时累计分配，不是同时占用内存或泄漏。
- 主录制 44 次 GC pause，共 746.040 ms，最长 42.575 ms。GC 可造成短卡，但并非全部低帧原因。

建议：材质映射按材质表 generation 建一次不可变快照，每条材质 O(1) 查询，资源重载正确失效；签名按不可变输入版本复用、固定缓冲分块编码，保持原字节契约；网格中间数据减少重复 float/quad/packed 格式转换和数组复制。不能直接关闭校验，否则会破坏缓存正确性及跨会话材质恢复。

## 已排除与结论限制

全部 26 个诊断快照的预生成均 disabled、limit=0、inFlight=0；预生成抢跑不是本轮原因。Rust 后端确实启用，不能沿用旧的 Java fallback 结论。后台初始化/JIT 也在消耗 CPU，但主线程锁等待和接缝热点有独立证据，不应统称为正常预热。

主窗口每 3 秒的 FPS 快照在 46.5–133.3 之间，包含 63.8、51.0 和 46.5 的低点。这不是逐帧分布或 1% low，且相机发生过移动。没有启用额外 GPU timestamp 查询，本轮无法给出 GPU 毫秒级归因，也不保证上述修复带来特定 FPS 增幅。

推荐实施顺序：**预算锁与异步负载采样 → 拒绝任务退避/准入 → 材质映射和缓存分配 → 有版本保障的异步接缝与预算发布**。前两项有最直接的卡顿和空转证据。生产实现与 GPU/并发回归留待下一轮，本轮只保存诊断结果。
