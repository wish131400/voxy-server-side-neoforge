# 2026-10-06 预测几何显存优化

本轮在 NeoForge 1.21.1 和 Forge 1.20.1 两个仓库同步实现。没有使用子 agent，没有替换运行中的客户端，也没有进行新版存档 FPS 或显存对照。

## 水体去重

GPU 不透明载荷现在只包含原始不透明面和追加的远景不透明面。GPU 水体载荷只包含原始水体面，两者分别保留自己的颜色／光照字典。

CPU／磁盘记录仍使用原始顺序：原始不透明 → 水体 → 追加显示层。包围盒、归属过滤和透明排序沿用这个索引空间；直接绘制、Iris 和 MDI 在最终提交时通过 `gpuFirst` 转换索引。MDI 仍按原始索引切分 meshlet 和查找 bounds，因此去掉水体造成的偏移不会使遮挡测试读到错误的包围盒。

morph 数据追加在不透明载荷的末尾，其起点根据新载荷长度计算。只有水体的网格不分配不透明缓冲。独立缓冲回退与共享 arena 使用同一布局。失败或尚未完成的冷恢复保留旧 GPU 网格，上传完成后才发布新驻留版本。

发布工作线程准备两套载荷；压缩网格的冷恢复由现有单 worker 队列完成，渲染线程不解压、不构建颜色映射。上传 staging 核算原始解压数据和两套载荷，上传完成或 staging 淘汰后释放临时载荷。通常额度为 32 MiB，允许一个不超过 48 MiB 的大网格单独恢复，以保证进度。

`uploadBytes` 同时计入不透明、水体和 morph，现有每帧上传预算因此覆盖两次实际载荷上传。

## GPU 驻留预算

新增 `PredictionGpuResidency`，与已完成 GPU 上传的 `PredictionRenderResidency` 配合工作。

- 默认软预算 1024 MiB；达到预算后以 90% 为恢复目标。可使用 JVM 属性 `-Dvss.predictionGpuBudgetMiB=768` 调整，范围 128～4096 MiB。
- 预算针对 VSS 常驻几何，包括共享页、独立缓冲和备用池；这不是整张显卡的可用显存查询，也不是整个游戏的显存硬限制。
- 每 500 ms 扫描一次。CPU 视锥以外的细节连续未使用 10 秒，且存在已上传的普通粗级替代时，才在压力下参与淘汰。
- 只有稳定索引证明全部几何位于 Voxy 内部，且三维视距和 morph 全程也满足要求，连续成立 2 秒后才参与归属淘汰。同样要求 GPU 粗级替代已经存在。
- 最近实际绘制时间单独更新。短暂被山遮挡不会触发显存淘汰，不使用 Hi-Z 的瞬时结果作为淘汰依据。
- 保护距相机 256 方块以内、当前望远镜焦点、最粗两级以及现有 LOD／真实区块接缝的地块。
- 每次最多淘汰 8 个地块或约 32 MiB；第一个超过额度的大地块仍允许取得进展。
- 淘汰通过驻留 revision 发布。粗级的归属、遮罩和接缝在正常准备路径一起更新，GPU 删除沿用原有 retirement／fence 流程。
- CPU 源快照不变时，被淘汰地块也会重新进入 pending uploads。视野外的淘汰地块不会立即重新上传；转回视野、覆盖撤销或网格 revision 变化时可以恢复。
- 重新上传仍使用现有每帧最多 2 地块、4 MiB 和约 2 ms 的软额度；第一个大网格允许取得进展。恢复完成前粗级覆盖保留。

扫描复用已有几何 bounds。Voxy 归属检查先用三维包围盒快速排除远处网格，再用索引前缀表证明整块覆盖；需要时才检查细粒度 runs。

预算是软目标。当前可见或仍承担回退／接缝的几何不会为了满足额度被强制删除。共享页中的空闲空间只有在整页空闲并且 GPU fence 完成后才能实际释放，所以 live bytes 下降不等于驱动分配量马上同比下降。

## 规则面统计

`PredictionGeometryStats` 在网格发布前统计四角能无损恢复的轴对齐矩形，并分别记录水体矩形。斜面、退化面、非矩形边和自定义 baked UV 模型保守地不计入。

运行诊断新增 `gpuResidency={...}`：

| 字段 | 含义 |
| --- | --- |
| `budgetBytes` / `targetBytes` | 软预算和恢复目标 |
| `liveBytes` / `committedGeometryBytes` | 常驻切片／独立缓冲使用量和已分配几何页／池容量 |
| `opaquePayloadBytes` / `waterPayloadBytes` | 两套实际载荷字节数，不含 mask、morph 和页对齐 |
| `waterDuplicationSavedBytes` | 对同一批网格与旧完整主载荷加水体载荷比较的字节差 |
| `budgetEvictions` / `ownedEvictions` | 按显存压力／完整归属淘汰的次数 |
| `releasedBytes` / `coldTiles` / `restoreRequests` | 释放的累计切片容量、保留恢复资格的地块数和恢复请求计数 |
| `quads` / `rectangles` / `waterRectangles` | 当前常驻网格的面数和可编码矩形面数，含追加显示层 |
| `coordinateSavingUpperBoundBytes` | 每个符合条件的面按坐标 24→12 字节计算的理论上界 |

矩形比例为 `rectangles / quads`。坐标节约上界未扣除格式标志、混合格式寻址和 GPU 对齐；本轮没有启用新的坐标格式，没有改变磁盘或网络协议，也没有把已有颜色字典的 48→32 字节收益重复计算。

## 验证入口

```powershell
.\gradlew.bat -I tools/prediction/vram-tests.gradle vramCpuTest vramGpuTest --console=plain --no-daemon
```

CPU 检查分别准备载荷后的逐条记录、颜色／光照／标志位、透明顺序、追加显示层、缓存往返、后台冷恢复、负坐标父级回退、同源快照重新排队、迟滞与淘汰保护、覆盖撤销、morph 和矩形识别。

GPU 使用独立隐藏 OpenGL 4.6 窗口，运行生产 shader 和批次实现。检查直接绘制／MDI、共享页／独立缓冲、原始／紧凑载荷、不透明／水面、完整／缺失／混合归属、远景追加层、冷恢复保留旧网格以及 GPU 缓冲逐字回读。

一份受控紧凑网格的载荷从 454560 字节变为 444128 字节，节约 10432 字节（约 2.30%），共享页与独立缓冲结果一致。该样例证明水体去重生效，不能外推为当前存档显存降幅或 FPS 增益。

验证日志、改动前 JAR 备份及验收 XML 保存于 `C:/Users/Administrator/.codex/tmp/vss-vram-20261006/`。最终测试计数与 JAR 指纹由旁边的产物清单记录。

## 最终验收与产物

| 平台 | CPU 专项 | 真实 OpenGL 专项 | 构建 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | 43 通过、1 条件跳过、0 失败／错误 | 5 通过、0 跳过／失败／错误 | `jar` 成功 |
| Forge 1.20.1 | 43 通过、1 条件跳过、0 失败／错误 | 5 通过、0 跳过／失败／错误 | `jar reobfJar` 成功 |

条件跳过的是需要 `vss.gpuCorpus` 外部真实网格语料的编码成本基准；普通载荷往返和 GPU 验证均已执行。GPU 验证额外覆盖水体插在原始与追加显示层之间的布局。两仓库 `git diff --check` 均通过。

- NeoForge：`lib/vss-0.3.5-neoforge-1.21.1.jar`，5668136 字节，SHA256 `34A58E669C468DE7185648D809B9E0D90BA97C207A14076BFE054251389B8077`。
- Forge：`lib/vss-0.3.5-forge-1.20.1.jar`，6341676 字节，SHA256 `81F3A025F0A4FE5B6F3BF3E0B8E4EADBAC533C5ECA2007C5B5D1856866C161F7`；与 `build/reobfJar/output.jar` 完全一致。

全部 ZIP 条目通过校验，无重复条目和测试类；关键类和 5 个平台的原生库完整。NeoForge 776 个类与当前编译结果逐字一致，Java class 版本为 65；Forge 787 个类确认完成重混淆，Java class 版本为 61。共享显存优化源码两端一致，平台 API 适配保持各自版本。

完整产物信息：[prediction-vram-artifacts-20261006.json](prediction-vram-artifacts-20261006.json)。没有安装新包、关闭现有 Minecraft 或采集新版存档 FPS。

## 实机复测

自行替换新 JAR 后，进入原位置等待加载稳定，读取 `gpuResidency` 中两套载荷和已分配容量。驻留压力测试应包含：持续朝同方向移动超过 10 秒、转身、望远镜、Voxy 覆盖撤销和跨 LOD 边界。对比固定位置、朝向和分辨率下的帧时间、重新上传次数与显存；保留可见几何超过软预算时的记录，不能把它直接判作回收失效。
