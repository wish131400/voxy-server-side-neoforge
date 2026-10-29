# 新 GPU scene 导致秒级卡顿的回归修复（2026-10-15）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本报告对应 `voxy-alignment-20261015` 交付后“进入世界只有一帧”的反馈。该回退来自上一轮改造：场景缓冲容量检查位于整套 GPU 创建／上传之后，植被分段又放大了命令数，导致真实森林场景反复构建、超限、释放和重试。本轮修复已同步 NeoForge 1.21.1 与 Forge 1.20.1。

## 运行中客户端的证据

- 客户端 PID 34352，Minecraft 1.21.1 / NeoForge 21.1.249，Java 25.0.1。
- 安装的 VSS JAR 为上一轮产物，SHA256 `733f7a5fca0f47188033947c3b3d4abbaad0f29d196ba8e504c578dd227db913`。
- 原始观测窗口约 30.033 秒，累计 620 个渲染帧，窗口平均约 20.64 FPS；FPS 读数为 0–99，最差两秒区间约 0.50 FPS。所有快照均为游戏内、焦点有效、无菜单。不是整个窗口固定为 1 FPS，而是反复出现秒级停顿。
- 渲染线程在约 30.034 秒内消耗 26.375 CPU 秒，约占一个逻辑核的 87.8%。
- 当时 GPU 读数约 59% 利用率，显存 4089 / 16376 MiB；系统约有 39 GiB 可用物理内存。这一现场没有内存或显存耗尽的证据。

一次线程快照直接落在：

```text
GL45C.glNamedBufferSubData
PredictionIndirectBatch$BatchSlot.uploadFallback
PredictionGpuScene.upload
PredictionGpuScene.update
PredictionIndirectBatch.submitScene
PredictionRenderer.drawNormalPass
```

30 秒 JFR 的 360 个 Render thread 执行／native 样本中，182 个包含 `PredictionGpuScene.update`，91 个包含 `PredictionGpuScene.upload`，44 个包含缓冲扩容路径，53 个包含 `uploadFallback`。这些是有包含关系的采样计数，不能相加或解释为精确耗时。

另一个有界只读观测窗口记录到：来源列表稳定在 1344 个瓦片、约 15,483,045 个候选面；材质分类有 519,216 段，而原空间 meshlet 只有 61,127 个。scene 的 entries 在 0、逐步创建至 1344、再次清空和重建之间变化；累计编码／上传计数持续增长。约 19.6 秒内新增 9474 次记录编码、约 234 MiB 的已核算上传量，尚不包括单独安全命令上传的全部字节。

## 为什么旧测试没有挡住

上一轮隐藏 OpenGL 回归覆盖了静态记录复用、局部变化、父子互斥和像素正确性，但主要吞吐场景使用规则网格，未覆盖真实森林的材质交替密度、稀疏分页取整和超限后的连续重试。稳定场景的吞吐改善不能证明进入世界时的构建／拒绝路径没有回退。本轮补入了这些失败条件的回归。

## 修复内容

### 先证明容量可接受，再碰 GPU

`PredictionGpuScene` 在 CPU 上复制 allocator 占用状态，按实际 page、空间 region、命令保留区间和缓冲取整计算上界，包含 metadata、bounds、filtered commands、三套安全命令及 LOD 节点。

容量验证放在 node buffer 创建、scene buffer 分配／清空／扩容和记录上传之前。超限时直接拒绝这套 scene 并走普通精确提交，不再先付出一整套 GPU 构建成本。生产上限仍为 64 MiB；测试可以注入较小预算来确定性复现超限。

### 拒绝状态与正常复用状态分开

旧逻辑可能因无关 epoch 变化清除 rejected 标志，随后让已清空的 scene 走正常增量命中，返回成功。后续输入再变化时又发起整套构建。

现在保留 rejected 状态，使用该输入自身的 GPU 成员变化日志判断是否需要重新验证；无关 mesh／GPU 更新不会使其假成功。真正缩小输入、修改范围或改变相关驻留后，仍可正常重新准入。

### 分类保持 meshlet 粒度

`PredictionMeshletBounds` 不再在每次树干／树叶材质交替时截断命令。包含植被的混合 meshlet 作为一个整体归入植被计时通道；membership、归属范围和 GPU 档位选择继续保留原精度。

因此植被通道的时间可能包含同一 meshlet 内的部分普通几何，属于保守预算。此变化不删除树叶、不改变透明排序，也不把某个粗档位永久固定为当前显示质量。

### 回退不继承 resident 专用状态

scene 超限或显式禁用时，`PredictionIndirectBatch` 关闭本次 resident GPU selection。普通提交在 Hi-Z 未启用时可能没有 bounds buffer，不能继续按 resident selector 的约定发起 dispatch。完整源几何回退继续由普通 ownership plan 提交。

## 验证与产物

本轮测试入口为 `tools/prediction/one-fps-fix-tests.gradle`，构建输出隔离在 `build-one-fps-fix-20261015/`，JAR 输出位于 `lib/one-fps-fix-20261015/`。

新增回归验证：

- 树干／树叶逐面交替仍保持 meshlet 级命令数，各档 membership 和源面集合保持完整。
- 超限输入返回失败，场景记录编码数为 0、上传字节为 0，node／choice buffer 均未创建。
- 连续无关版本变化及输入重排仍然拒绝，不能误报空 scene 成功。
- 输入缩小后可以恢复，已有可用场景被超限输入替换时不会先部分改写记录。
- `submitScene` 拒绝后 resident GPU selection 标志关闭。

本轮隐藏 GPU 测试与游戏同时运行时的吞吐数字不作为性能结论；以容量拒绝、命令规模、像素／深度与状态恢复断言为验证依据。修复包尚未替换到运行中客户端，不能报告修复 JAR 的游戏内 FPS。

## 可恢复的现场对照

另在没有隐藏 GPU 回归并行运行时，做了约 30 秒的运行时开关对照：6 秒保持原状、18 秒临时设置 `vss.disableGpuPredictionSelection=true`、6 秒恢复原状。全过程位置、俯仰和朝向读数完全一致，处于前台游戏内，没有暂停菜单。

原路径前段已经处于较快的暖状态，不能把两段平均值当作严重卡顿的严格前后 A/B。重新启用该路径后，恢复窗口再次出现连续一秒渲染帧增量为 0，支持将秒级停顿与这条路径的重建关联起来。该对照修改的是旧包的运行时开关，并非运行修复 JAR。

原开关不存在，结束后已在 `finally` 中恢复为不存在，`selection-ab-restored.txt` 确认 `original=null`、`restored=null`。所有有界观测线程和 JFR 均已结束。未替换实例 JAR、未写入游戏配置、未启动或关闭游戏。

## 最终交付记录

| 平台 | CPU 回归 | 隐藏 OpenGL 回归 | 失败／错误／跳过 | JAR 大小 |
| --- | --- | --- | --- | --- |
| NeoForge 1.21.1 / JDK 21 | 35 组／235 项 | 10 组／11 项 | 全部 0 | 6,135,590 字节 |
| Forge 1.20.1 / JDK 17 | 34 组／232 项 | 10 组／11 项 | 全部 0 | 6,805,995 字节 |

两端已完成生产构建，Forge 完成 `reobfJar`。联合产物审计确认 20 个共享生产源码一致、旧入口未回包、测试／基线／观测 agent 未进入生产 JAR、Java class major 分别为 65／61，以及五个平台原生资源的哈希匹配。

新包位于两个仓库各自的 `lib/one-fps-fix-20261015/`，同目录提供 `validation.json` 与 `SHA256SUMS.txt`：

- NeoForge：`vss-0.3.5-neoforge-1.21.1.jar`，SHA256 `61f0a37114fcc7d62a44a3e4568516ac0b61ede87015b2ffd639d8aac1599abe`。
- Forge：`vss-0.3.5-forge-1.20.1.jar`，SHA256 `c8cf3dbd116a430e7ff6dcd48b4f70a6cf4b7ec26350a4a64c27300cbd9e792b`。

原始证据保存在 NeoForge 仓库的 `tools/prediction/one-fps-20261015/live/`、根目录 `build-one-fps-20261015.jfr`、两份 `build-one-fps-threads*-20261015.txt` 及 JFR 分析 JSON；工具源码位于同名工具目录。它们不进入生产包。
