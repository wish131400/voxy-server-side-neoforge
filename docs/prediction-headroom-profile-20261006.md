# 100 FPS 后的剩余优化空间：客户端采样（2026-10-06）

## 结论

仍有提升空间。新瓶颈证据最明确的是静态批次每帧重复序列化/比较，以及水面透明顺序跨显存页造成大量小提交。应先做这两项，再依据完整 GPU 阶段计时决定进一步几何、shader 或剔除优化。当前证据不支持继续把主因归到网格上传、归属计划整体重算或 GC。

本轮只读调查生产源码，客户端仅短时开启已有 renderTimings 并恢复原系统属性；没有修改生产源码、游戏画质设置或安装包。用户报告同位置从 60 到 100 FPS；本轮没有切回旧包建立严格前后 A/B，因此不把这两个数字当作本轮受控实验。

## 版本与窗口

- PID 55776；安装包 SHA256：`89e5b3d9916258d4962e11bc1da8c92136f73cf818db77fb3ca68d853d6dc957`。该包与前轮最终独立包 class 内容已核对一致，JAR manifest 等打包元数据使整体 hash 不同。
- 配置：预测距离 5120、fine 1536、surface 512、normal、超采样关闭；原版视距 5、FPS 上限 260、VSync 关闭。
- 采样时间约 03:04:04–03:04:40（香港时间）。30 秒渲染窗口和 35 秒 JFR。
- 位置固定 `(-4039.20,73.62,-970.93)`。窗口中间打开过暂停菜单，后段有轻微转向。采用 sample-01 到 sample-07 的 12.041 秒、固定朝向且无菜单区间作为稳态对照，1180 帧，97.999 FPS。
- 原始证据保存在 `C:/Users/Administrator/.codex/tmp/vss-headroom-20261006/`，不放在会被 gradle clean 删除的 build 目录。包含 JFR、原始计数、稳态汇总、后续只读布局快照和源码 hash。

## 稳态证据

| 指标 | 结果 | 含义 |
|---|---:|---|
| 绘制计划重建 / 复用 | 0 / 1180 | 上轮计划缓存已有效 |
| 网格上传、可见列表发布、排序编辑 | 全部 0 | 静态成本不是这些更新造成 |
| PREPARE CPU | 0.0187 ms，39 样本 | 静止准备成本很低 |
| OPAQUE CPU | 1.350 ms，39 样本 | 含内部构建、比较、GL 调用 |
| WATER CPU | 0.715 ms，39 样本 | 面很少，但提交偏重 |
| OPAQUE GPU 区间 | 5.394 ms，39 样本 | 仍有优化空间；时间戳区间可包含命令供应间隙，不能全当 shader 忙碌 |
| DEPTH_COPY GPU | 0.173 ms，39 样本 | 当前非最大项 |
| INDIRECT_BUILD CPU | 每次 1.016 ms，共 78 次 | 不透明+水面两次/采样帧，共约 2.033 ms；它覆盖构建到提交整段，不是纯构建耗时 |
| INDIRECT_UPLOAD CPU | 每次 0.001287 ms，共 10530 次 | 270 批次/采样帧，合计约 0.347 ms；缓存命中时主要仍做比较，并非真的上传 |
| 渲染提交 | 318600 /1180 =270 次/帧 | 全部通过间接批次路径 |

以上 CPU 阶段有嵌套，不能相加重复计算；CPU 与 GPU 也不能直接相加推算帧时。

稳态 JFR 中 Render thread 共 327 个 Java/native 样本：`PredictionIndirectBatch.add` 出现在 85 个栈中（26.0%，包含其子调用）；`BatchSlot.same` 是 34 个栈顶（10.4%）。这两者有包含关系，不能相加。`Boolean.getBoolean/System.getProperty` 16 个栈（4.9%），源码确认 add 中逐 tile 读取 meshlet 调试开关。抽样占比不是精确毫秒占比。

完整 35 秒 JFR 的 render-thread allocation counter 增量 1261.50 MiB，约 36.07 MiB/s；GC pause 合计约 20.16 ms，最长约 6.03 ms。该窗口包含暂停，且与此前跑图场景不同，不能拿分配速率直接算优化百分比。分配必须按 thread ID 区分同名 worker；ObjectAllocationSample 初次权重可能包含录制前分配，不能用其总和替代该精确线程计数。

## 优先级一：缓存完整的静态提交计划

当前 `PredictionIndirectBatch.add` 每帧重复写 tile record、indirect command、meshlet bounds；`BatchSlot.upload` 再逐字节比较刚生成的所有数据，确定与上一帧一样后才跳过 glBufferData。因此当前缓存主要省 GPU 上传和数组分配，并未省去命令生成与比较。

后续布局快照：opaque 48 个 slot、22428 条命令、1230 个 tile record、每帧比较 1,244,976 bytes；water 225 个 slot、629 条命令、532 个 tile record、46,628 bytes。合计约 1.29 MB/帧。不是单靠内存带宽大小判断热点；逐字节 Java 比较与重复写入在 JFR 中已有证据。

建议将每个 bucket/page 的完整静态提交计划缓存：metadata、原始 indirect buffer、bounds、命令数及队列计数均附带版本/身份。命中时直接绑定并重用；相机、投影、时间继续走 frame uniform，HiZ/filter 仍每帧执行。失效条件必须包含 draw/ranges 成员与顺序、GPU slice/page/residency、ownership ranges、aquatic tier、face selection、材质模式、morph scale 和相关 shader contract。不能仅凭数量相同判定有效，也不能把上帧遮挡结果作为静态内容。

小修可先将 meshlet/debug 属性读取移到 begin，每 pass 一次；消除重复 ranges 查找和命令数量计算。若短期只加速 same，可评估 JDK ByteBuffer mismatch 的向量化路径，但它仍然保留整帧序列化，因此不是最终方案。

验收：静止的 metadata/bounds/command 编码量降至 0；覆盖撤销、网格替换、望远镜、aquatic 距离变化、跨桶及 water 排序变化立即正确失效；direct/MDI/HiZ 的像素、深度、primitive query 与基线一致。测 CPU 和 frame time，不以 reusedBatches 一项作为成功标准。

## 优先级二：水面专用紧凑存储，减少透明批次碎片

布局快照：opaque 4,854,426 quad、48 批；water 21,075 quad、225 批，来自 532 tile、12 个显存 page。水面仅占该快照候选 quad 约 0.43%，却占批次约 82.4%。稳态稍早为47+223=270，后续快照为48+225=273；不要将两个时刻数字拼成同一帧。

原因：全部几何共用按上传分配的 arena page；水面必须保持透明顺序，相邻 tile 往往属于不同 page，所以持续 flush。0.715 ms 的 CPU 阶段时间支持这个优化方向，但水面 GPU 时间本轮缺失，不能声称水面 GPU 是零或很小。

优先原型：将透明水面记录与必要的颜色/材质/coverage 数据置于少量专用 page，仍按原远近顺序提交，以减少跨页绑定。必须避免复制全部 opaque payload；处理 compact color 字典偏移、yield mask 更新、tile 替换/回收和资源重载。可比较多页统一寻址方案，但需先验证目标 OpenGL 和 Iris shader 接口，不假定 sampler 数组能无条件跨 draw 使用。

禁止为了合批把所有水面按 page 任意重排；也不建议只放大所有 arena page，显存浪费和硬件纹理 buffer 上限都要验证。该项比优先级一更复杂，适合独立实现及像素回归。

## 必要配套：计时查询池避免后部阶段饥饿

`PredictionRenderTimings` 所有阶段共享 16 对 query。OPAQUE 内的 bucket/filter/submit 抢占可用槽；12 秒稳态丢弃12051次 query请求，WATER GPU 和 ANTIALIAS GPU 新样本均为0。diagnostics 中显示的历史 WATER GPU 均值不是此次新测值。HZB/submit 也只采到前部部分批次，不可按其均值乘所有批次推算全帧 GPU 时间。

改为顶层阶段保留 query 配额，加上跨帧有界环；细粒度子阶段独立配额/轮转抽样。保持非阻塞 availability 检查和 query 上限。不要通过阻塞读取来换取完整计时。这是判断下一项 GPU 优化收益的前提。

## GPU 后续方向及当前不宜承诺的内容

aquatic 排除计数仍有效，最新为1,513,218个不透明候选quad；之后仍有4,854,426个opaque候选。最近一次异步HiZ计数剔除795,224个（16.4%，sampleAge=9），还余约405.9万个候选经过该级。hidden含视锥剔除，余下也不等于最终可见/光栅化面，后面还有shader/深度拒绝。

32位通用记录、细节密度和当前固定256面分组仍有中期空间，但单个开阔视角16.4%的剔除率不能证明HiZ失效。当前几何量按距离看主要集中在1024–4096格（约309.7万），建议下一步重新导出材质/面类型分布，验证剩下的是地形、树叶、墙面还是边界，再决定增加其他植被显示LOD、继续合并或启用几何误差驱动规划。

不建议现在直接重写成双遍HZB、强制全局背面剔除、降低预测距离或取消远处材质。前两项有边界/透明/时序正确性成本，后两项会混入画质变化。混合紧凑格式与误差驱动细化仍是较大后续项目，应在完成上述低风险CPU收益和准确GPU分阶段测量后决策。

100 FPS约10 ms/帧。即使消除约2 ms的整个预测CPU提交区间，也不代表一定达到125 FPS：CPU/GPU存在重叠、GPU阶段仍重，而且提交区间中的GL调用不可能全部取消。本轮不承诺固定增加多少帧。
