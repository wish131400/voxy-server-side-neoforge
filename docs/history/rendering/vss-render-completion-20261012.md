# VSS 渲染优化完成记录 — 2026-10-12

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮围绕五个方向完成了生产实现或独立 A/B。测试使用已经退出的客户端环境，在 RTX 4070 Ti SUPER / OpenGL 4.6 上运行隐藏窗口；没有启动 Minecraft，也没有替换客户端文件。组件毫秒数不能直接换算游戏 FPS。

## 已保留

### 1. 接缝依赖、驻留保护和上传准入

上一轮已经保留的接缝空间索引、真实 patch bounds、可见接缝保护和安全父级回退继续保留。本轮回归确认：接缝增删、负坐标、多 LOD、父级未完成、Voxy 覆盖撤销和冷恢复都不会产生空洞或错误覆盖。

### 2. PreparedPass 条目级局部缓存

`PredictionRenderer.PreparedPass` 现在按 `Draw` 身份保存绘制范围、网格提交版本、局部覆盖版本、显示档位和 GPU 提交版本。可见列表身份不变时，网格替换、局部覆盖变化只重建受影响条目；不相关条目沿用原 ranges。水面仍在缓存范围结果后按相机重新排序，保持 back-to-front 顺序。

384 个可见条目的生产路径 A/B（每种 151 个测量轮次，包含预热）：

| 场景 | 原实现 | 条目缓存 | 变化 |
|---|---:|---:|---:|
| 单条网格替换 | 0.0759 ms | 0.0538 ms | -29.1% |
| 16 条网格替换 | 0.0508 ms | 0.0381 ms | -25.0% |
| 局部覆盖更新 | 0.0554 ms | 0.0530 ms | -4.3% |
| 新列表对象、成员不变 | 0.0485 ms | 0.0335 ms | -30.9% |
| 水平移动 | 0.0259 ms | 0.0244 ms | -5.8% |
| 静止 | 0.0007 ms | 0.0028 ms | 该轮受测量噪声影响，不能宣称收益 |

更早的交替轮次也得到同方向结果。生产实现保留了版本检查和静止快路径；静止场景没有增加额外的范围重建。GPU 覆盖掩码变化会推进可见工作量版本，但不会错误地重建 CPU ownership ranges。

### 3. Meshlet 命令级方向剔除

工作线程为 256 个 quad 一组的 meshlet 计算包围盒方向掩码，并将掩码放入 bounds 的空闲字段。Compute filter 只有在整组几何确定为单面且全部背向相机时才丢弃命令；植物双面模型、流体、斜面、混合方向和未知模型都保守保留。bounds 聚合忽略掩码字段，世界坐标和相机偏移语义不变。

独立 shader fixture：

| 场景 | 原 GPU | 方向掩码 | 提交面数 |
|---|---:|---:|---:|
| 全部背向 | 0.257 ms | 0.033 ms | 524,288 -> 0 |
| 一半背向 | 0.140 ms | 0.120 ms | 262,144 -> 131,072 |
| 全部朝向 | 0.273 ms | 0.273 ms | 不变 |

使用真实打包格式、arena page、MDI 和生产 terrain program 的重复墙面测试中，全背向 524,288 面为 `0.257 -> 0.031 ms`，朝向相机为 `0.273 -> 0.273 ms`。颜色、深度和 GL 状态均通过回归。

方向表增加约 4 KiB / 262,144 面；工作线程构建成本约 `5.77 -> 6.87 ms`，不在渲染线程执行。它只在上传/构建阶段付费，换取 GPU 端明确收益。

### 4. 按可见工作量进行 Hi-Z/直绘收益比较

`PredictionOcclusionPolicy` 现在接收 PreparedPass 的可见工作量版本；视野外驻留或无关版本变化不会重启比较，真实可见列表、覆盖、材质或 GPU 提交变化仍会重启。当前帧 Hi-Z 深度和每个距离桶的遮挡测试没有改成历史可见性，因此不会使用过期结果裁掉新几何。

生产 batch fixture 在可见几何固定、全局版本每帧变化时：旧逻辑 210 帧重启 210 次、始终处于 measuring；新逻辑完成一次比较并选择直绘，GPU 区间约 `0.574 -> 0.219 ms`。低遮挡场景选择直绘；高遮挡场景继续选择 Hi-Z。两种路径像素和深度一致。

### 5. GPU 驻留和接缝局部索引

上一轮的动态四叉树接缝索引、可见 patch 保护、普通父级完成条件、延迟淘汰和冷恢复继续通过 NeoForge/Forge 回归及 GPU handoff 测试。此次没有扩大淘汰范围来制造显存数字，也没有把测试页大小当成实际存档收益。

## 未保留的实验

- 持久映射读回：mapped-copy CPU `0.699 -> 0.736 ms`、GPU `0.044 -> 0.103 ms`；direct-mapped CPU `0.530 -> 0.489 ms`，GPU `0.044 -> 0.208 ms`，没有稳定正收益，生产代码未保留。
- 提前在 terrain shader 做面级背面判断：三个视角分别变慢约 0.57%、改善 0.26% 和 1.22%，方向不稳定，已撤回。保留的是更早的 meshlet 命令级判断，因为它能避免整条命令进入顶点/材质阶段。
- 仅按投影屏幕尺寸选择普通父级：测试中 8 方块间距只有 1.18 像素，但 32 方块高度差为 4.72 像素，水体高度差为 2.36 像素，且父级可以缺少子级植被；没有足够的几何误差和内容版本证明，未进入生产。

## 验证和产物

NeoForge 和 Forge 都运行了条目缓存、ownership、LOD、接缝、GPU batch、Hi-Z、方向剔除、显存驻留和 depth convention 回归。最后一轮关键套件均为 0 failures / 0 errors / 0 skipped；Forge 使用 Java 17，NeoForge 使用 Java 21。GPU 回归使用同一块 RTX 4070 Ti SUPER，顺序执行。

交付 JAR：

- `lib/render-completion-20261012/vss-0.3.5-neoforge-1.21.1.jar`
- `lib/render-completion-20261012/vss-0.3.5-forge-1.20.1.jar`

SHA256：NeoForge `2dab7c45e7cee3fdd337516674ab911182fbbbf428e46f265d4b430b32049c54`；Forge `075d57b99bc71b2381a472a5d8ea6cecf59e7ae705b4c7a8e468600c01251eb6`。两个 JAR 均通过 ZIP 完整性检查，NeoForge class major 65，Forge class major 61，产物中没有本轮测试类。

本轮 A/B 原始 CSV 位于 `build-render-completion-20261012/followup-results/`，包括 `prepared-pass.csv`、`direction-build.csv`、`production-command-direction.csv`、`production-visible-workload-policy.csv` 和 `parent-selection.csv`。没有提交 GitHub。
