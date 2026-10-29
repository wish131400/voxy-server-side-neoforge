# 近处植被渲染优化与验证（2026-10-11）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](README.md)，相关旧报告见 [历史索引](history/README.md)。 本报告路径保留，供既有验证工具及证据清单引用。

已同步 NeoForge 1.21.1 / Forge 1.20.1，打包版本仍为 0.3.5。以用户“左侧 Voxy、右侧 VSS”的近处草地比较为背景，本轮优化现有几何的处理方式。草、花、树的密度选择与生成位置沿用原设置，GPU 紧凑表示无损保存每个面，原始 CPU/磁盘缓存继续可恢复。

## 已实现

### 植被片元采用适合其语义的处理路径

`PredictionTerrainProgram` 增加 `renderFeature`。有有效材质、非水面、没有地形侧壁/真实接缝/近似显示标记且纹理坐标屏幕导数不超过 0.0625 的 feature 使用该路径。选择仅改变执行方式，不删除几何或改动透明度阈值。它先检查自己的可见状态、预测视距和 regional ownership，再按原 UV、mipmap、sprite 边界与 0.1 alpha 阈值采样一次材质。透明孔洞到此结束；保留像素继续当前帧真实深度、Voxy 原始深度和 VanillaMask 交接，最后读取光照并输出原色彩与雾。

植被无需地表法线重建、地形高度误差偏好、流体平面交点和流体深度 tie，因此没有把这些通用路径状态带进来。微小植被、接缝、水面、地形与无法满足前提的模型继续通用路径。通用路径也把光照读延后到材质 alpha 检查之后；合成 sprite 直接使用原有不受光照影响的颜色。原始地表的 `dFdx/dFdy` 在 discard 之前保持完整，不通过植被路径影响其相邻采样。

这是 OpenGL shader 内部的选择，适用于普通直绘、MDI 及 Iris 注入的生产 shader，没有每帧 CPU 重组或新增绘制通道，也不写 `gl_FragDepth`。

### 去掉草花的冗余顶点工作

透明裁剪面和流体始终要保留材质读取，所以不再为它们重算“是否可按远处平均颜色绘制”的四角 XZ 包围盒。仍需要距离材质切换的实体面保持整面 bounds 判断，保证四角的 flat varying 一致。

普通十字草花在完成坐标、UV、颜色和投影以后，直接设置双面可见状态与零 fluid plane，跳过实体面朝向及恒定面判断。它们的两条对角线、原有 UV 轴、顶点颜色和光照保持原值。

### 普通十字草花使用 20 字节 GPU 记录

`PredictionGpuEncoding` 扩展现有负 `paletteBaseTexel` 布局，直接存十字面两端点、属性及 cell/palette 元数据。符合条件的每面固定记录由 32 bytes 减到 20 bytes，下降 37.5%，另计共享颜色/光照字典。特殊模型的自定义 UV、不同底端高度、不可表示端点、流体和不符合十字拓扑的面保存精确例外记录，压缩收益不足时继续旧布局。

标签放在 selected normal-coordinate word 的 bit 30，bit 31 沿用例外标记；端点 2 在该轴须小于 16384，端点 0 保持完整 unsigned16。CPU 参考解码会清理标签位，GPU 解码保持原绕序、四角颜色、coverage 信息与空间范围。这只改变 GPU 上传表示，CPU palette、磁盘几何、冷缓存恢复与 canonical 面序不变；编码由现有发布/恢复工作路径执行。

## 真实网格离线验证

回放 `C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry` 中之前捕获的 3,890 个独立网格、22,667,547 个源四边形，旧包对应源码与当前编码分别生成 opaque/water 记录，每条记录解码后逐 int 比较 canonical 输入，全部一致。统计中包含共享字典、对齐与特殊面的例外记录。

| 该批独立网格 GPU 几何载荷 | Bytes | MiB |
| --- | ---: | ---: |
| 任务开始前布局 | 494,105,680 | 471.22 |
| 加入十字面紧凑表示后 | 479,940,656 | 457.71 |
| 差值 | 14,165,024 | 13.51 |

新布局实际包含 530,848 个十字面记录，571 个 pass 载荷改变，总量减少 2.87%。这一比例的分母包含所有地形/叶片/水面和其他模型，不能把“单个适配草面固定记录下降 37.5%”当作整个场景 VRAM 降幅。GPU 页容量与对齐仍影响真实显存分配；这里没有测量换包后的显卡占用。

## GPU 对照及性能边界

`PredictionVegetationRenderGpuTest` 从上一轮实际分发的 `memory-residency-20261010` JAR 读取旧顶点/片元 shader，和当前生产 shader 比较。384 面 fixture 包含两条草花对角线、不同四角颜色与天空光照、叶片透明孔洞、地表导数控制面、mipmap、合成 sprite、local/fixed ownership。普通/MDI/Iris 普通及反向深度，普通与望远镜 FOV，共 192 组颜色及深度逐像素比较通过；额外 12 组饱和 main depth 配合原始 Voxy 深度及撤销比较也通过。光照纹理为 16×16 渐变并使用线性过滤。

OpenGL 异步 `GL_TIME_ELAPSED` 微基准使用 RTX 4070 Ti SUPER；旋转旧/新路径的测量顺序，10 轮预热、15 轮采样取中位数。下面是最终记录，均为固定测试几何，不是运行中 Minecraft 的单帧耗时或 FPS：

| 微基准 | NeoForge 旧 → 新 | Forge 旧 → 新 | 含义 |
| --- | ---: | ---: | --- |
| 256×256，384 面，重复绘制 16 次 | 0.054272 → 0.062464 ms | 0.054272 → 0.062464 ms | 小画面增加约 0.0082 ms，约 15.1% |
| 1024×1024，同几何与重复数 | 0.444416 → 0.401408 ms | 0.444416 → 0.401408 ms | 像素工作较多时降低约 9.68% |
| 262,144 十字面，4 次绘制，禁用光栅化 | 0.121856 → 0.157696 ms | 0.121856 → 0.156672 ms | 紧凑解码与 shader 顶点控制存在额外指令成本 |

只采用当前 shader、仍用 32 字节草面时，最后一项为 0.130048 ms；因此不能把数据变小解释为顶点执行必然更快。无损表示明确减少载荷；片元路径在上述大画面 fixture 有收益，较小画面或顶点占主要成本时没有证据支持净帧率收益。

曾试验在整套通用 shader 开头采样透明度并把样本一直保存到色彩阶段。画面虽然一致，256/1024 测试均更慢，此实现已撤回。分发包里没有 `sampledCutout` 实验。任务日志保留结果，避免把该方案当成已确认收益。

**本轮没有实际客户端同位置 A/B，不能承诺整体 FPS 增幅，也不能由这组数字认定已经解决用户现场的全部掉帧。** 交付保留外观的可验证实现；换包后的场景需要同时观察不透明 GPU 区间、帧时间和显存。

## 验证时修复的旧 Voxy 兼容问题

注入 DeceasedCraft_Beta 实际 `voxy-0.2.14-alpha-51ea1a841fff1717b261d2fbd3b3f6dafd470663.jar` 时，远处水面/前景回归失败。其 final-blit shader 使用 literal `clip*2-1`、`min(...)` 和 `depth*.5+.5`，而原 `PredictionNormalDepthBridge` 只认识 0.2.15 depthutils 宏，未注入保留前景预测深度的检查。

现已精确识别上述两套已知契约。旧版同样接入 source convention、destination depth 转换以及 `predicted > seed` 的前景保护。未知/incomplete shader 保持原样，重复调用不叠加补丁；三项新 CPU 回归覆盖这些条件。实际 NeoForge 0.2.15-beta 与 Forge 0.2.14 的窗口深度模式、水面背景、远平面前景以及 seeded real occluder 用例均通过。该问题是本次验证中额外发现的既有兼容缺口，并非将植被交接改为列索引删除。

## 回归、构建与产物

| 平台 | CPU 回归 | OpenGL 回归 | 构建 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | 132 项，131 通过、1 条件跳过，0 失败 | 10 项全部通过 | JDK 21，jar |
| Forge 1.20.1 | 131 项，130 通过、1 条件跳过，0 失败 | 10 项全部通过 | JDK 17，jar + reobfJar |

唯一 CPU 跳过项是已有 `PredictionGpuEncodingTest.realCorpusRoundTripAndEncodingCost`，没有传入它专用的外部语料目录。本轮独立离线回放已验证完整 3,890 个真实网格。GPU 没有条件跳过。

其余 GPU 回归覆盖 arena/独立缓冲、MDI、当前帧 Hi-Z、压缩上传、冷恢复、密集森林分段、显示 LOD、覆盖撤销与 feature fallback。两个 JAR 的 ZIP、版本、三个本轮修改生产类、字节码平台版本、五个平台 native 资源与测试类排除均核对。NeoForge 全部编译输出 class 与 JAR 对应 class 一致；Forge 分发包与 `reobfJar/output.jar` 一致。

- NeoForge：`lib/vegetation-render-20261011/vss-0.3.5-neoforge-1.21.1.jar`，5,902,810 bytes。
- Forge：`lib/vegetation-render-20261011/vss-0.3.5-forge-1.20.1.jar`，6,575,146 bytes。

SHA256：

```text
1d01e30e125e4e7304c77b856ae6398fac26b3a2d67720dd35723764ec56c807  vss-0.3.5-neoforge-1.21.1.jar
2f10ab3d22c04e90445458f74fb16dc81a06188a059ddbd69df1b20df827c5c8  vss-0.3.5-forge-1.20.1.jar
```

每个平台产物目录附 `SHA256SUMS.txt` 与 `build-verification.json`。源修改、结果 JSON 与此报告已同步两个仓库。原始日志、旧/新源码 diff、离线编码程序保存在 `C:/Users/Administrator/.codex/tmp/vss-vegetation-render-20261011`。

客户端未替换或重启，GitHub 未推送，生成预算保持 CPU 指标。用户自行退出游戏后替换对应平台 JAR；缓存可直接沿用。相同草地位置依次检查普通视角、望远镜和移动交界，同时记录 GPU/帧时间；草花数量与形状应与同一密度设置下原网格一致。
