# 2026-10-06 Prediction GPU 优化实现

## 实现范围

本次依据 `prediction-gpu-profile-20261006.md` 实现双平台优化，版本仍为 0.3.5。没有替换当前游戏实例或启动/关闭客户端，没有新版实机 FPS 对照结果。

### 剔除的收益选择与批次同步

- 普通预测不透明通道使用独立的异步 GPU timestamp query，比较完整批次阶段的直绘与 Hi-Z 路径；每种模式收集 8 个有效样本，去掉两端样本后比较均值。
- 只有差异超过 `max(0.08 ms, 直绘时间的 4%)` 才改变选择。稳定视角约 480 帧后重新比较；明显的相机移动、转向，以及渲染尺寸、驻留或归属变化会重新测量。
- 相机/覆盖变化后的旧 epoch 结果不参与新的决定。CPU 仅在 query 已可用时读取，不调用 glFinish 或等待可见性。
- 直绘与剔除分别缓存静态命令，减少比较期间的重新组装；场景版本变化会清理失效的另一套计划。
- 一个距离桶中的各页先集中筛选，再共享一次 command/storage barrier，最后依次绘制。各页保留自己的元数据和命令缓冲，水面顺序保持原有路径。
- 当前帧 Hi-Z 仍在距离桶边界重建，不保存旧可见性。直绘只改变提交方式，深度和归属判断仍执行。

### 远景显示层

- 把已有整株抽稀扩展到已识别的普通草、高草和蕨类，按固定 X/Z 坐标选择，所有高度段与交叉面一起保留。未知植被和自定义 baked UV 模型不参与此抽稀。
- 在网格工作线程构建共面叶簇、外墙和屋顶层。合并相邻完整矩形，保持轮廓、孔洞、平面高度、面朝向、材质和天空光级；四角颜色可取均值。
- 不跨越侧面的固定法向归属坐标；合并面的切向覆盖继续由现有 fragment 掩码处理。近处原始几何不变，远景几何追加到同一静态上传中。
- 距离选择带迟滞，并根据投影缩放保留细节；望远镜恢复完整范围。发生地形 morph 时保留原始几何，避免较大矩形改变非线性位移。
- 完成缓存仍只保存原始几何；恢复时重新构建显示层。没有修改磁盘格式或网络协议。
- 工作数组按线程复用，仅处理不超过 131072 个不透明面的单网格，追加面数最多为原始不透明面数的一半。范围碎片过多或减面不足时保留原始范围，新增范围内存计入预算。
- 这是保守的共面简化，不是完整树冠 impostor、任意建筑内部删除或非共面 cluster 树。

### 空间与层级剔除

- 不透明面排序加入 Y 轴和 cutout 类别，仍保留完整记录、五组面边界、透明顺序和显示层前缀。
- 细单元保持 256 四边形；为每个 compute 工作组预建 64 条命令的包围盒并先作整体拒绝。小批次跳过此粗层。
- 原始数据、morph 全程范围和未知深度仍按保守原则处理；近裁面、相机处于包围盒中和天空空洞保留几何。
- 统计改为工作组内汇总，再少量写全局计数，降低诊断采样时的 atomic 争用。

## 诊断与复测

新增 `filterBarriers`、`occlusionPolicy`、`directGpuMs`、`culledGpuMs` 和 `displayExcludedQuads`。直绘期间剔除计数来自最近一次 Hi-Z 样本，需结合模式和样本年龄阅读。

复测时记录固定位置、朝向、分辨率和游戏未暂停状态，等待收益比较结束，再读取 FPS、预测不透明 GPU 时间、候选面数及显示层减少的面数。移动测试另外检查切换、望远镜和 Voxy 覆盖撤销后的恢复。

可诊断强制属性：`vss.forcePredictionOcclusion=true` 固定剔除，`vss.disablePredictionOcclusion=true` 固定直绘，`vss.disablePredictionDisplayLod=true` 在重新构建/恢复网格时禁用新增共面显示层。已有水生植物的属性也控制共用的植被前缀选择。

## 验证入口

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
./gradlew.bat -I tools/prediction/gpu-optimization-tests.gradle gpuOptimizationCpuTest gpuOptimizationTest --console=plain
```

CPU 覆盖缓存、整株选择、迟滞、范围保存、压缩/恢复、内存分配和收益选择；GPU 执行真实生产 shader，验证近远显示层、直绘/MDI、缺失/混合归属、近裁面、深度空洞、镜头转向、水面以及 GL 状态恢复。GPU 使用独立隐藏测试窗口。

受控模型中，1024 个共面叶面在三个远景层分别为 256、64、16 面；相同受控实体平面分别为 16、8、8 面。它们不能换算成实机 FPS 或全部城市的减面比例。

## 最终验证结果

两端验收进程均已正常结束，专项测试无失败、无错误、无跳过：

| 平台 | CPU 专项 | 真实 OpenGL 专项 | 合计 |
| --- | ---: | ---: | ---: |
| Forge 1.20.1 | 33 | 3 | 36 |
| NeoForge 1.21.1 | 33 | 3 | 36 |

- CPU 包含新增的高层建筑 XYZ 排序用例、收益比较与旧 epoch 拒绝、整株抽稀、切换迟滞、原始缓存重建和有界分配。
- GPU 使用生产 shader 和批次实现，验证受控共面模型的近远层 0～3、直绘/MDI、完整/缺失/混合覆盖。保留非空画面断言，避免空画面之间的比对误报通过。
- GPU 还验证当前帧深度、近裁面、镜头转向、天空空洞、水面、直接回退、覆盖撤销后的恢复和静态计划复用。
- NeoForge `jar` 与 Forge `jar reobfJar` 构建均成功。GPU 优化初次验收时，NeoForge 成品的 762 个类与当时编译结果逐项 SHA256 一致；Forge 成品确认已使用重混淆名称。Java class 版本分别为 65（Java 21）和 61（Java 17）。后续缓存崩溃修复已重新打包，当前成品核对结果见下文。
- 两个 JAR 全部 ZIP 条目均可解压且长度正确，无重复条目或测试类；新增 GPU 类、模组/manifest 版本和五个平台的原生库均已核对，打包资源与当前处理结果一致。

JUnit XML 分别位于 `build/test-results/gpu-optimization-cpu` 和 `build/test-results/gpu-optimization`，HTML 报告位于对应的 `build/reports/tests` 目录。最终日志保存于 `C:/Users/Administrator/.codex/tmp/vss-gpu-optimization-20261006/`：`neoforge-cpu-final.log`、`forge-cpu-accepted.log`、`neoforge-gpu-accepted.log`、`forge-accepted.log`、`neoforge-build.log` 和 `forge-build.log`。

## 打包结果

以下记录叠加 Lost Cities 缓存并发修复时的 0.3.5 成品快照。GPU 专项结果沿用上面的已完成验收；缓存修复另有两端各 58 项专项，57 项通过、1 项条件跳过、零失败，详见 [并发缓存修复记录](lostcities-cache-concurrency-20261006.md)。当时成品全部 ZIP 条目、配置、处理资源和协议再次核对；NeoForge 766 个类与该次编译结果逐项一致，Forge 777 个类已完成重混淆。没有在该次缓存修复中重复进行游戏 FPS 或 GPU 专项测试。

同名 `lib` JAR 后续叠加了植被三档与望远镜加载开关，最新产物及 SHA256 见 [植被与望远镜设置记录](prediction-vegetation-settings-20261006.md)。以下大小和校验值保留为历史快照。

| 平台 | 成品 | 大小（字节） | 包内协议 |
| --- | --- | ---: | ---: |
| Forge 1.20.1 | `lib/vss-0.3.5-forge-1.20.1.jar` | 6316472 | 49 |
| NeoForge 1.21.1 | `lib/vss-0.3.5-neoforge-1.21.1.jar` | 5642388 | 50 |

SHA256：

```text
Forge:    6CF8C3BAEB01E09B647B8E97F80254AB6DDDDF8251976BF3933209AD041D0D90
NeoForge: 505CB939C7FDDABA3ACCC6BE85363780E6C9E784BC48CA95BCA826CD734369C9
```

本轮 GPU 修改和后续缓存修复没有改变协议常量；成品包含各仓库当前协议，客户端和服务端应使用对应平台的同次构建。GPU 修改前的旧 JAR 已备份到上述 GPU 证据目录的 `before-forge` 和 `before-neoforge` 子目录；缓存修复前的 GPU 优化 JAR 另存于 `C:/Users/Administrator/.codex/tmp/vss-city-cache-crash-20261006/` 下同名备份目录。

本轮没有安装成品或重新进行游戏 FPS 对照。原调查中临时关闭剔除的约 10% 提升不能作为这些新包的实测结果；显示层的受控减面比例也不能直接换算为城市场景帧率。替换后应在原位置、朝向和分辨率下等待自适应比较结束，再测试固定视角、移动、望远镜和覆盖交接。
