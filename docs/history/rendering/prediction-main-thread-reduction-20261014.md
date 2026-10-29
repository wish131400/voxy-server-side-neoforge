# prediction main thread reduction 20261014

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**客户端主线程继续减负（2026-10-14，0.3.5）**

本轮接续 turn-main-20261014 包，已同步 NeoForge 1.21.1 和 Forge 1.20.1。没有采样当前游戏，因此以下结果是源码结构和回归证据，不是实际帧率或逐帧延迟收益。

**改动和实际生产入口**

1. 完整四叉树规划、精度目标、可选 idle 目标合并、普通祖先链、medium 结构、过渡目标以及 Voxy 归属候选选择，由 PredictionPlanPreparation 在共享的 vss-client-planning 控制线程计算。输入是不可变视角、布局、瓦片/epoch 快照、错误估计、脏列集合和独立 idle 状态副本。覆盖页快照也在该工作线程构建，不读取 Minecraft 区块或执行 OpenGL。
2. 规划返回后，客户端 tick 核对世界对象生命周期、设置/内容版本、布局、望远镜 focus、相机位移与 hard cut、Voxy 覆盖撤销版本，并再检查当前相机的三维 Voxy 范围。失配结果丢弃；等待时继续使用已有驻留地形。新覆盖的漏收只减少跳过量，已撤销的覆盖不能继续作为退休依据。轻微运动允许短期近似调度优先级，绘制仍使用当前帧相机。
3. PredictionSchedulePreparation 在同一个控制线程扫描、计算优先级并排序普通、植被、望远镜和磁盘缓存候选，生成队列优先级提示及压力回收顺序。实际 CPU/内存预约、父级/覆盖回退、暂停及重试资格由主线程 enqueue 再检查；工作线程开始运行也继续使用原有校验。
4. 主线程普通接纳每 tick 最多检查 64 项，服务窗口约 1 ms；缓存恢复和望远镜各最多 32 检查、约 0.25 ms；尚未启动任务的队列更新最多 32 项、约 0.25 ms。排完的候选继续跨 tick 服务，后台结果不 join。共享控制线程复用了原需求线程的角色，没有为每个规划阶段增加独立线程池。
5. CPU 驻留巡检每 tick 最多 64 瓦片、约 1 ms，最多退休 8 瓦片。无需每轮复制和排序整个驻留集合；缺失父级仍先补充再退休。内存压力下可见降级先按片累积足够替代预算，再重新核对当前回退和重要区域，最多 8 项退休。候选只保留 key/revision/字节计数；网格发布/删除立即撤销该候选计数，避免额外网格引用。重试与缓存 miss 元数据也采用 64 项分片清理。原有 CPU 冷缓存维护预算保留。
6. GPU 驻留每帧最多检查 64 瓦片、约 0.5 ms，使用支持增删的轮转游标。统计按瓦片贡献更新，接缝独立缓冲字节随变更维护；不再周期遍历全部几何做 census。退休列表只使用刚在当前帧核验过的候选，并逐项再次检查回退；Voxy 撤销 revision 会重启该瓦片的 ownership 稳定期。完整遍历仍逐步完成，瞬时指标在一轮巡检完成前是渐进更新值。
7. 原版 chunk identity / 编译覆盖常规检查每帧最多 128 列、约 0.5 ms；真实边缘采样单独按脏列分片，最多 128 列、约 0.5 ms，并逐片发布，避免跑图时窗口变化使交接一直等待。新增 ClientChunkCachePredictionMaskMixin 通知到达和卸载；块更新/卸载列先撤销覆盖，编译完成后才重新接管。必要世界块状态读取仍在主线程。NeoForge drop 使用 ChunkPos，Forge 使用 X/Z，已按平台源码与 Forge SRG refmap 核对。
8. 每个规划/排序/需求 owner 最多一个任务在途。世界退出、暂停、重置不会累积重复任务，已完成或正在完成的废弃 Future 会释放结果，即使退出后不再 poll。后台失败保留已发布状态并可重试。原版覆盖维护在可复用绘制计划的帧仍推进，有真实边缘变化时重新准备交接。

这些时间预算是循环的软上限：单项 Minecraft 查询、GPU 上传/删除或安全交接不能在操作中途截断，因此不能承诺整个客户端 tick 或一帧小于上述毫秒数。

**验证**

| 平台 | CPU 回归 | OpenGL 回归 | 失败 / 错误 / 跳过 | 打包 |
| --- | ---: | ---: | --- | --- |
| NeoForge 1.21.1 | 210 | 12 | 0 / 0 / 0 | jar 成功 |
| Forge 1.20.1 | 208 | 12 | 0 / 0 / 0 | reobfJar 成功 |

新增回归包含：控制任务非阻塞/单槽/重置结果释放/失败恢复；完整规划及精度目标与同步参考一致；负坐标覆盖快照、稳定期、撤销和跨维度独立性；两平台编辑列 key 语义一致；真实 tick 传送时拒绝旧规划；覆盖撤销无需等待轮询；维护游标增删后完整遍历；GPU 退休不使用旧候选；生产显存维护入口 130 瓦片的单次检查数不超过 64 且最终完成。GPU 验证使用隐藏 GLFW 上下文，没有启动 Minecraft。

原有生命周期回归仍验证字节上限、关闭释放、暂停/恢复、资源重载、捕获数据替换和回退覆盖。测试现在在后台任务完成后驱动后续 tick；捕获测试等待实际捕获样本进入成品，避免将并发的普通精化 revision 误认为捕获结果。提交批次、Hi-Z、水面、植被、望远镜、接缝、普通与 Iris shader 以及外部 GL 状态回归保留。

包检查包括 ZIP 完整性、0.3.5 平台版本、Java class 版本、四个新后台/游标类、Mixin 注册、Forge SRG refmap、五平台原生库与源文件一致和测试类未混入。验证 JSON 保存逐套件计数及源码/JAR/原生库 SHA256；两版完整日志是 build-main-reduction-final.log。

**保留的主线程工作和实际限制**

主线程仍取得与发布不可变快照、合并已准备的小型目标集合、更新实际生成队列和资源预算、读取 Minecraft 可变状态并执行 OpenGL。加载进度、前景债务、可见性反馈及部分元数据仍有线性检查，不能说所有遍历或分配已经消失。Iris 专用提交仍逐瓦片处理；本轮没有改成完整 Voxy GPU 层级遍历或 GPU 命令压缩。必要的数据上传/安全接缝交接和世界切换清理仍可能出现单项尖峰。

原版完成编译后的接管和冷缓存统计允许短暂延后到后续分片；已获通知的覆盖撤销会先恢复预测。初次进入世界的完整目标会在后台完成后由下一 tick 接收，现有地形与绘制回退保留。客户端线程没有等待后台任务。

**交付和实测**

- NeoForge：lib/main-reduction-20261014/vss-0.3.5-neoforge-1.21.1.jar，SHA256 daad1bdc007bfc1afc735e7b4d3dec7031f8024029678c50c3dcdedee1ef1b53
- Forge：lib/main-reduction-20261014/vss-0.3.5-forge-1.20.1.jar，SHA256 a9dba7615e2f4718997c8f1ce7359a6f6a7ae4ccbc320d2100d7677c93111ac8

用户自行替换并启动。未修改整合包、未推送 GitHub。对照静止、持续转向、跑图、望远镜、传送/重生、资源重载和真实/预测边缘。关注 CLIENT_SCHEDULE / GPU_RESIDENCY / VANILLA_MASK，planJobs/Commits/Discards、scheduleJobs/Commits、admissionChecks、queuedRefreshChecks、retirementChecks、maskIdentityChecks，以及后台 planPrepareMs / schedulePrepareMs。后台计时为累计经过时间，不能直接当客户端线程的 CPU 时间；每个阶段原有 p95/p99 是采样窗口指标，不是完整帧序列。

复现：

```powershell
# NeoForge
./gradlew.bat -I tools/prediction/main-reduction-tests.gradle turnMainRegressionTest turnMainGpuTest jar --console=plain
# Forge：使用 JDK 17
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat -I tools/prediction/main-reduction-tests.gradle turnMainRegressionTest turnMainGpuTest reobfJar --console=plain
```
