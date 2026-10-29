# VSS 与 Voxy 的渲染差距及场景覆盖改造（2026-10-15）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

## 判断

差距既有架构原因，也有当前实现尚未消除的 CPU 准备工作。预测能力并不必然导致这么大的渲染开销；生成未知地形是后台工作，而已经生成的静态网格应当能以稳定记录参与渲染。现阶段 VSS 还未将相机变化与静态场景维护完全分开。

本轮没有重新采样运行中的游戏。下面的实时数据引用此前的有效客户端采样，不能代表本轮改造后的 FPS，也不是 Voxy 与 VSS 的同画面 A/B。

## 已有实测证据

来源：`docs/history/performance/prediction-gpu-utilization-profile-20261015.md`。

| 有效游戏窗口指标 | 数值 |
| --- | ---: |
| 时长 / 渲染帧数 | 26.018 秒 / 2050 帧 |
| 平均帧率 | 78.79 FPS |
| 渲染线程 CPU 时间 | 22.828 秒，约单个逻辑核的 87.74% |
| GPU 利用率 | 平均 49.56%；全显卡采样，非逐帧 GPU 时间 |
| 绘制计划准备 / 复用 | 2051 / 0 |
| 网格上传 | 4 次 |
| VSS 提交计数 | 约 477 次 / 帧 |
| 候选四边形 | 约 738 万 / 帧；不是最终通过剔除的可见面 |

JFR 的 2487 个渲染线程执行 / native 样本中，Scene.prepare 占 27.8%，PreparedPass.prepare 占 21.4%，VisiblePlan.selectStable 占 8.9%，IndirectBatch.add 占 7.5%。这些路径存在包含关系，不能相加，也不能直接当作精确耗时比例。

该窗口支持优先优化渲染线程自己的场景选择、绘制范围准备和批次组装。后台线程争用、上传、GC 或 GPU 都可能在其他窗口成为瓶颈，不能用这一段数据否定森林等其他视角的 GPU 成本。GPU 平均约 50% 也不能换算为可直接翻倍的 FPS。

## 源码对应的架构差异

参考 Voxy 源码：本机 `voxy-263-git2`，核对版本 `b0207f6fcc0a55fa6b33f5c916932e8560a71845`。

| 环节 | Voxy 参考实现 | VSS 当前实现与影响 |
| --- | --- | --- |
| 多级数据 | WorldVoxilizedSectionMipper 从同源 2×2×2 体素逐级汇总；父子节点构成空间层级 | 已有预测父子回退和显示变体，但显示几何简化还不等价于完整同源三维粗代理体系 |
| 视角选择 | traversal_dev.comp 在 GPU 上执行节点视锥、Hi-Z、屏幕误差与下钻；缺失节点发请求，父级网格回退 | CPU 仍维护空间候选和可见成员，GPU 驻留选择仅在经过验证的场景启用 |
| 静态命令 | 层级节点和驻留网格供 GPU 选择并生成绘制输入 | 已有静态命令与范围缓存，但转向导致列表或 pass 输入变化时仍可能重新准备和组装 |
| 几何表示 | 规则 quad 使用 64 位记录，另有模型、材质与节点数据 | 已有 32 字节颜色 / 光照字典格式以及适用面上的 20 字节记录；特殊面保留通用坐标。不能按 8 与 20 的字节比推算帧率 |
| 交接 | 同一体素层级内的父子选择 | 预测还要处理 Voxy 真实覆盖撤销、独立网格形态、水面和接缝；应让这类工作按局部变化发生 |

GPU 选择策略目前要求至少 3072 个地形瓦片、1,572,864 个驻留候选面、524,288 个可见候选面、无接缝、当前 Hi-Z 可用，而且是位置不变的转向。生产路径另有共享 arena、GL 能力、Iris / 水面 / 望远镜等限制。它不能被描述为“全部移动场景已经交给 GPU”。

`PredictionFramePlan.getStable` 要求相机、视图和投影矩阵完全相同。转向退出整帧计划复用，但内部局部缓存仍可能命中；这不是重建整个世界或重新上传全部几何。

## 本轮完成的生产改动

两仓库同步接入 `PredictionAuthoritativeCoverageView`，复用原已验证的稳定覆盖快照和 Voxy 节点 / 窗口适配。场景资格与提交范围使用同一覆盖证明，旧 `PredictionSubmissionCoverage` 类不再入包。

- 场景在可见性 / 排序之前排除已经完整交接的普通地形，同一资格也应用到 GPU 驻留候选输入。
- 隐藏仅改变候选资格；原 draw、父子回退和资源生命周期仍保留，以便覆盖撤销恢复。
- 相同覆盖和网格版本下，转向不重复计算候选归属；被隐藏的记录跳过视锥和距离更新。
- 覆盖撤销后，即使相机不动，也拒绝旧整帧计划并恢复候选。
- 第一次就被隐藏的网格也登记 submission 变化，后续 morph 扩大有效范围时能恢复。
- 维度、三维范围、morph、未稳定的边缘和特征内容继续采用保守判断；接缝和完整特征不会仅凭二维列索引被整块移除。

这一步是统一场景候选和提交判断，尚不是完整的共享 GPU scene。覆盖 revision、相机跨 8 方块 cell 或全局 mesh submission epoch 改变时，资格层仍扫描 retained entries；尚未实现按局部变化日志只访问受影响资格记录。原 run 过滤的局部失效继续保留。

## 验证与交付

| 平台 | CPU 回归 | 隐藏 OpenGL GPU 回归 | 构建 |
| --- | ---: | ---: | --- |
| NeoForge 1.21.1 | 250 通过 | 13 通过 | 生产编译、JAR 成功 |
| Forge 1.20.1 | 248 通过 | 13 通过 | 生产编译、JAR、reobfJar 成功 |

全部为 0 failure / error / skipped。新增 6 项 CPU 和 1 项 GPU 测试包含：120 次转向不重复归属判定，覆盖撤销无相机运动恢复，维度 / 高度 / morph 保守性，以及生产 Scene 复用原 GPU payload 和 arena slice。

回归还覆盖既有父子回退、水面、接缝、特征交接、深度和 Iris 路径。GPU 测试使用隐藏 GLFW 上下文，不是游戏内 FPS 基准。隔离测试依赖脚本列出的历史夹具；不能据此声称所有历史基准脚本均可直接运行。

构建脚本：`tools/prediction/scene-coverage-tests.gradle`。

NeoForge：
```powershell
.\gradlew.bat --offline --no-daemon -I tools/prediction/scene-coverage-tests.gradle turnMainRegressionTest turnMainGpuTest jar
```

Forge：
```powershell
.\gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/scene-coverage-tests.gradle turnMainRegressionTest turnMainGpuTest jar
```

交付目录：两个仓库各自的 `lib/scene-coverage-20261015/`。完整 SHA256、class major、原生库保留和测试汇总记录在该目录的 `validation.json` 与 `SHA256SUMS.txt`。

- NeoForge JAR：`vss-0.3.5-neoforge-1.21.1.jar`，6,090,404 字节，SHA256 `f6cce652f04e1a159d7e1f363fb3e0a7bc1eed84686a4a48e54d1662154b14e5`；Java class major 65。
- Forge JAR：`vss-0.3.5-forge-1.20.1.jar`，6,760,679 字节，SHA256 `4a23d11dea781ca1e24b30ce7f4ad1198f605e87d46707c1de153f1b56b756f7`；Java class major 61。
- 新覆盖类在包内，旧覆盖类不在包内；测试和实验类未入包；五个平台的原生后端资源保留。
- 共用覆盖类、VisiblePlan、新测试和测试脚本的字节哈希一致。Renderer 按平台逐片同步，保留 Forge / NeoForge 事件与矩阵 API 差异。

没有安装、替换或启动客户端，没有推送 GitHub。产物留给用户自行测试；目前没有本轮游戏内 FPS、1% low 或 GPU 时间改善的测量结果。

## 后续架构工作

本轮只完成覆盖候选统一的一部分。共享稳定 GPU scene / 固定命令槽位、完整同源父子粗代理、移动场景的 GPU 细粒度选择，以及纯需求计算后台化仍需继续实施与验证。有限阶段 Hi-Z 和分通道计时已有实现，不能再当作完全未做；植被的独立 GPU 预算尚不完整。

后续验收应在相同位置、视角、分辨率和画质下，分别测静止、原地转向、跑图与 Voxy 交接；记录渲染线程准备时间、实际版本 / 槽位更新量、批次数、候选 / 通过面数、上传量及分阶段 GPU 时间。对 GPU scene 的收益不能只看离线小窗口，也不能靠增加无效绘制来提高显卡占用。
