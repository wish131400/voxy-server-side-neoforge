# vss zoom pixel lod 20261012

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 按缩放与屏幕像素选择 LOD — 2026-10-12**

已补齐现有屏幕空间路径：放大镜头后，真实渲染投影参与可见区域的普通细化目标；缩小后，显示端重新选择满足像素误差的已上传粗表示，并真正减少 MDI 范围。望远镜开启时，也允许远到仍满足像素要求的地形和已确认草类使用粗显示档位。改动在两个仓库同步，独立 GPU 对照和正确性回归通过，保留并完成打包。

普通视野此前已有屏幕空间规划、显示变体和真实 MDI 减面。本次补的是望远镜情况下“普通调度拿未放大比例”和“整个可见预测通道强制最高显示细节”两处不一致，没有把此前已有的普通远景 LOD 作为新收益。

**运行行为**

屏幕比例来自渲染事件的实际投影矩阵与渲染目标高度：`S = height × abs(projection.m11) / 2`。可见瓦片的屏幕大小用于空间细分和 8/16/32/64 轴普通目标；显示端按预先测量的几何误差与最近 eye depth 选择档位，保守加入向眼睛移动和画面边缘的误差放大。地形误差近似关系为 `error × S / (eyeDepth - error)`，不是简单按世界距离固定切档。

1. 放大使投影比例增大，能看清的区域优先要求更细目标。已有细几何可以当前帧选择；缺失几何由原有后台调度生成，在新网格上传前保持粗覆盖。
2. 缩小使像素误差变小，显示端选择可用的粗表示，提交更短的 MDI 范围。不会为了这个切换每帧重新生成 / 上传网格，也不会立即删除已完成的细网格；之后仍按原有内存与显存预算管理驻留。
3. 保留现有 20% 入档滞回，降低缩放阈值附近反复切档。完整空间计划仍按原 tick 节流；不能把“已有范围当前帧恢复”误解为“缺失细节立即生成完成”。
4. 近处、接缝、morph、混合父子 / Voxy 归属及无安全粗表示的几何保持原保护。缺少真实望远镜渲染视角时继续保守恢复源细节。水面几何和透明顺序继续走原机制。

“望远镜加载”设置继续控制是否额外生成远处细节；关闭时调度保留基础像素比例。显示选择仍按真实镜头处理已存在的几何。普通 FOV 或缩放模组只要把实际投影传入世界渲染事件，就会影响选择；本轮没有逐个实机测试所有缩放模组。仅在界面上拉伸最终画面的模组不能凭此获知真正缩放需求。

生产改动仅涉及 `PredictionRenderer.java` 和 `VssLodProjection.java`。本轮开始时的工作树快照保存在 `tools/prediction/zoom-lod-experiments/baseline/{neoforge,forge}/`；使用此前包含方向筛选和水面缓存的新工作树为基线，未重置至 Git HEAD。相对差异归档在 `results/{neoforge,forge}/production.patch`。

**为何能借鉴 Voxy，但仍有不同**

参考 Voxy 263 提交 `b0207f6fcc0a55fa6b33f5c916932e8560a71845`。其 `screenspace.glsl` 投影节点八角，计算屏幕面积，`shouldDecend()` 在 `_screenSize > minSSS` 时下探；缺少子网格时请求数据并继续使用可用覆盖。VSS 是预测高度网格、模型、植物和接缝，当前使用几何误差及独立植物重要性来选可用表示，不能直接把全部网格套成规则体素的粗节点。

本轮沿用 VSS 已有表示，不重做上一轮被拒绝的四块地形连接环或占用树冠代理。整块瓦片共享最大误差、显示变体的容量 / 收益门槛、未知模型精度保护仍然存在，因此不能保证所有远处面都能降低。已确认草类使用整株稳定范围；其“像素误差”是重要性代理，不能当作被删植物每个顶点的严格几何位移上界。

**收益测试**

独立 RTX 4070 Ti SUPER / NVIDIA 572.70 / OpenGL 4.6，256×256 隐藏窗口，24 轮预热、81 轮交替 A/B。一份地形或草类网格重复提交 64 次，采用真实生产打包格式、着色器、几何页、PreparedPass、静态计划重放和 MDI。基线 `ZoomBaselinePass` 从上一份工作树精确提取，两侧使用独立 payload / GPU 记录及对等反射测试入口，避免一侧的显示状态干扰另一侧。

CPU 记录准备与提交，排除测量前 `glFinish` 与测量后同步查询；GPU 记录包围整个准备 / 提交的 `GL_TIME_ELAPSED` 区间。报告中位数，完整逐轮 CSV 与组件 P95 在 JSON。CPU 和 GPU 重叠，不能相加换算帧率。

镜头对照为垂直 FOV 70° / 8.75°，相机朝向样本中心。普通及放大细节样本相机 `(64,1040,1344)`，极远放大样本 `(64,7280,9664)`。这是假设场景，远景只有少量像素；没有把它当作当前存档截图、森林占比或游戏 FPS。

| 内容 / 场景 | 实际 MDI 面：基线 → 当前 | 命令数 | NeoForge GPU | Forge GPU |
|---|---:|---:|---:|---:|
| 地形，普通视野 | 52,608 → 52,608 | 64 → 64 | 0.038912 → 0.038912 ms | 0.038912 → 0.038912 ms |
| 地形，放大且需细节 | 1,048,576 → 1,048,576 | 64 → 64 | 0.885760 → 0.886784 ms | 0.840704 → 0.842752 ms |
| 地形，放大但仍可用粗档 | 1,048,576 → 52,608 | 64 → 64 | 0.398336 → 0.037888 ms | 0.398336 → 0.037888 ms |
| 草类，普通视野 | 131,072 → 131,072 | 128 → 128 | 0.058368 → 0.057344 ms | 0.057344 → 0.057344 ms |
| 草类，放大且需细节 | 2,097,152 → 2,097,152 | 64 → 64 | 3.550208 → 3.563520 ms | 3.545088 → 3.560448 ms |
| 草类，放大但仍可用粗档 | 2,097,152 → 131,072 | 64 → 128 | 0.780288 → 0.058368 ms | 0.780288 → 0.058368 ms |

极远放大地形减少约 95.0% 提交面、90.5% GPU 区间；草类减少 93.75% 提交面、92.5% GPU 区间。相应 NeoForge CPU 为 0.027000 → 0.026700 ms、0.021300 → 0.021800 ms；Forge 为 0.025700 → 0.025700 ms、0.020800 → 0.019200 ms。普通与需要细节的放大样本保持原面数，微小时间波动不作性能收益承诺。

粗地形选中档的保守投影误差为 1.593359 px，低于 normal 的 2 px；草类重要性代理为 1.125419 px，低于 1.5 px。普通 / 需要细节的放大路径两侧颜色、深度一致；每个选中范围的直绘 / MDI 颜色、深度一致。故意选用不同几何细节的极远粗档不要求和完整源几何逐像素一致，两侧额外验证真实非空图像。

**正确性与集成验证**

| 检查 | NeoForge 1.21.1 | Forge 1.20.1 |
|---|---:|---:|
| CPU / 正确性回归 | 228 / 228 通过 | 227 / 227 通过 |
| 新缩放 GPU A/B | 1 / 1 通过 | 1 / 1 通过 |
| 生产 GPU 回归 | 10 / 10 通过 | 10 / 10 通过 |
| 构建、ZIP 与本地库检查 | 通过 | 通过，包含 reobfJar |

新增集成测试使用真实 PredictionTileManager 准入 / 构建：普通视野 32 轴瓦片在放大后要求并完成 64 轴，缩小后普通目标回落但保留完成网格。独立目标测试还验证 6 px 配置下的 8 → 64 轴选择、FOV / 窗口比例刷新、禁用望远镜加载和其他 FOV 变化。

显示端验证放大 / 缩小立即改变实际 PreparedPass 范围、几何存储数量不变、极远望远镜仍能粗显示，以及近处、morph、混合归属和缺少视角时恢复源范围。原有 Voxy 覆盖撤销、父子交接、接缝、水面、深度约定、紧凑解码、GL 状态和植被回退通过回归。

成品不含 Zoom 实验类或 JUnit，五份 Rust 本地库与上一份 design-benefit 包逐字节一致。没有修改玩家安装目录、启动游戏或推送 GitHub。

| 产物 | 大小 | SHA256 |
|---|---:|---|
| [NeoForge 新包](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/zoom-lod-20261012/vss-0.3.5-neoforge-1.21.1.jar) | 5,953,042 bytes | `ff5093c88ac1f2fb3f05c59bcdc230f4786cb1f834bbb52cb6b0c47f56b6bab1` |
| [Forge 新包](C:/Users/Administrator/Desktop/voxyserverside/lib/zoom-lod-20261012/vss-0.3.5-forge-1.20.1.jar) | 6,625,348 bytes | `c00bb23d8c236bab01b5fa52ca09aba391f18c855a5321991684d5ba7a867d1a` |

**复现与实机边界**

测试 init script 为 `tools/prediction/zoom-lod-tests.gradle`。NeoForge 使用 JDK 21；Forge 使用 JDK 17 并添加 `-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17`。GPU 测试应单独运行，避免和客户端争用显卡：

```powershell
./gradlew.bat -I tools/prediction/zoom-lod-tests.gradle zoomLodRegressionTest --console=plain
./gradlew.bat -I tools/prediction/zoom-lod-tests.gradle zoomLodGpuTest zoomLodProductionGpuTest jar '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' --console=plain
```

机器可读报告为 [vss-zoom-pixel-lod-20261012.json](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-zoom-pixel-lod-20261012.json)。原始 CSV、XML、基线与生产差异在两仓库的 `tools/prediction/zoom-lod-experiments/`。只整理已完成结果的脚本：

```powershell
python tools/prediction/zoom-lod-experiments/summarize.py --neoforge-root C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1 --forge-root C:/Users/Administrator/Desktop/voxyserverside
```

本轮未采当前客户端 FPS、整帧 P95 / P99 或缩放加载秒数。新增逻辑及独立组件收益成立；当前世界能少多少面、整体帧率提高多少，仍应在同位置分别记录普通 / 放大 / 缩小的实际 MDI、GPU 与准备 CPU 后判断。
