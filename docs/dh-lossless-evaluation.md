# DH 候选方案无损性与本地开销评估

> 阶段记录：正文中的实验范围、未提交状态和测试数字对应当时的工作节点。0.3.2 之后截至本次提交的最终功能与升级说明见 [更新日志](../CHANGELOG.md)。

日期：2026-09-22。基线：NeoForge `19fed7d`，Forge `8e15c45`。仅新增本地实验测试与此报告；未修改生产代码、构建安装包或提交 GitHub。

## 结论与前次判断修正

不能笼统承诺四项优化全部无损且更快。

1. 植被实例化：保留所有原始面片、坐标、材质、UV、光照、透明裁切、morph、覆盖归属及必要绘制顺序，才具备无损前提。统一树模板替换已有详细树形不满足要求。本次只测等价面片的实例提交，没有实现完整的树模板实例化。
2. GPU 缓冲容量复用：可保持上传内容逐字节相同，但速度取决于缓冲大小和 GPU 在途使用；直接覆盖并非始终更快。容量保留也增加显存占用。延迟缩容、资源预算与 fence 池未实现，不能把本次局部原型称为完成整套缓冲生命周期方案。
3. 去重、优先级和精细缓存直达：当前版本已经具备。正确区分世界、维度、配置、修订及失效状态时，不降低最终数据精度；调度次序可改变加载过程中显示的细化进度。不能重复统计为新增优化。
4. 空间索引：当前已有四叉树、局部接缝和不可变分页快照。精确查询应与全扫描集合一致；本次复测通过，不能重复统计为新增优化。

当前植被已合入地块网格，并非一棵树一次 draw；此前以未合批植被为前提的收益判断不适用于当前代码。

## 环境和方法

- Ryzen 7 7700，RTX 4070 Ti SUPER，NVIDIA 572.70，Windows，Java 21.0.6。
- NeoForge 仓库离线 Gradle 测试，隐藏 OpenGL 3.2/3.3 上下文，没有运行真实游戏对照；未测 AMD。
- 绘制测试使用简化 shader，不能覆盖 Iris、真实透明排序、完整材质、阴影、morph 或所有模组模型。
- 帧缓冲对比在初始数据与局部更新后进行；缓冲测试另回读每个活动缓冲并检查全部有效字节。
- 测试中 glFinish/同步回读用于隔离计时和验证，不建议加入游戏渲染循环。
- 不相加各组百分比，不把微基准结果换算成游戏 FPS。

## 1. 等价实例提交与跨地块合批

已有合批实验复测：512 个简化地块，各一个四边形；30 次预热、60 次计时，交替对照。

| 模式 | CPU 提交中位数 | GPU timer 中位数 |
| --- | ---: | ---: |
| 逐地块提交 | 0.057 ms | 0.015 ms |
| 共享数据、多重绘制提交 | 0.030 ms | 0.003 ms |

节省 CPU 0.027 ms（47.4%）、GPU 0.012 ms（80%）。共享方式每面额外存储 4 字节 owner，还需归属表和 mask；48 字节基本面片数据的 owner 增量为 8.3%，不是总显存增幅。这里的 512→1 指提交 API 次数，不代表整个游戏只剩一次绘制。

新增实例实验以相同共享面片数据比较 glMultiDrawElements 和 glDrawElementsInstanced：512 面片，轮换三种模式的执行顺序，30 次预热、60 次计时。

| 模式 | CPU 提交中位数 | GPU timer 中位数 |
| --- | ---: | ---: |
| 共享多重绘制 | 0.022 ms | 0.003 ms |
| 相同面片实例绘制 | 0.001 ms | 0.002 ms |

CPU 绝对节省约 0.021 ms，GPU 约 0.001 ms。两者保留相同 packed payload，初始和修改几何、颜色、mask 后的像素一致。数字已四舍五入到 0.001 ms，极小差值不应过度解读。此实验未降低面片数、未减少植被生成计算，也未验证真实树模型的实例压缩率。

## 2. GPU 缓冲更新

32 个缓冲，每轮每个缓冲上传并绘制两次，中间不插 fence，合计 64 次更新。每次有效大小在最大容量的 75%/100% 间变化。12 轮预热、30 轮测量，三种策略轮换顺序。

- recreate：每次删除并创建 buffer/texture，再 glBufferData，模拟现有更新策略。
- capacity：对象及最大容量保留，通过 glBufferSubData 覆盖有效数据。
- replaceStorage：保留对象，每次用 glBufferData 替换数据存储。

下面是从开始提交到 glFinish 返回的墙钟时间，包含提交、驱动和等待，**不是纯 GPU 时间或游戏每帧时间**。

| 单缓冲最大容量 | recreate 中位数 | capacity 中位数 | replaceStorage 中位数 | capacity 相对基线 |
| --- | ---: | ---: | ---: | ---: |
| 48 KiB | 3.813 ms | 0.706 ms | 1.385 ms | 减少 81.5% |
| 192 KiB | 4.635 ms | 1.652 ms | 2.781 ms | 减少 64.4% |
| 768 KiB | 8.585 ms | 15.599 ms | 18.083 ms | 增加 81.7% |

| 单缓冲最大容量 | recreate CPU 提交 | capacity CPU 提交 | replaceStorage CPU 提交 | recreate / capacity / replaceStorage 完成时间 p90 |
| --- | ---: | ---: | ---: | --- |
| 48 KiB | 3.474 ms | 0.131 ms | 0.139 ms | 4.775 / 3.282 / 2.069 ms |
| 192 KiB | 4.354 ms | 0.420 ms | 0.404 ms | 5.611 / 3.758 / 3.513 ms |
| 768 KiB | 7.887 ms | 9.825 ms | 9.833 ms | 9.622 / 21.526 / 20.625 ms |

全部有效字节与验证帧像素一致，OpenGL 无错误。capacity 在该夹具的稳定阶段比活动有效字节多保留约 14.3% 容量；不含驱动内部在途存储。真实场景的容量浪费取决于变化幅度，并非固定 14.3%。

初次两策略测试同样出现小缓冲加速、大缓冲退步。最终测试让每次修改的首个数据字影响所有输出像素，加强在途更新的验证，增加 replaceStorage 对照。大缓冲退步与同步/存储管理成本一致，但未用驱动 profiler 精确归因，不能断言全部都是 fence 等待。

建议仅继续研究受预算约束、避开在途缓冲的复用池；不能根据单机夹具直接固定一个生产阈值。当前 ensureMesh 在 revision 不变时直接返回，所以这项主要影响加载和更新，静止场景或仅转视角而网格未变化时没有对应上传可省。

## 3. 已有调度与缓存路径

PredictionProgressiveLoadingTest 的 13 项通过，包括缓存两槽补充、满队列近处升级、望远镜优先级迁移、争用重试和清理。

精细缓存测试：66×66 输入样本恢复为 cellAxis=64 的可上传精细网格，采样函数调用数为 0。损坏缓存不发布、不偷偷落入昂贵生成路径。该测试关闭树和结构，不能据此声称所有植被/结构缓存都零构建成本。

当前已使用 PriorityBlockingQueue、pending reservation、优先级提升和独立 restoreCached 通道。无新的 before/after 调度实现，因此不报告新增速度百分比。

## 4. 已有索引与增量更新

PredictionIncrementalRenderTest 的 9 项通过：混合层级、负坐标、替换/删除后的四叉树查询结果与独立矩形全扫描一致；粗细边界、维度清理、覆盖归属、视角转动与局部修改均检查。

- 1,024 个地块中更新一个，接缝只处理 9 个 owner，packed quads 与完整重建逐项一致。这是工作量局部性，不是 CPU 减少 99% 的测量。
- 8,192 个地块中更新一个，旧全量快照参考路径 2.268 ms，当前分页快照 0.029 ms；线程分配量 417.3 KiB→19.1 KiB。快照内容一致。该能力已在现有版本中。

## 复现与验证记录

首次运行的现有四个测试类均通过（共 30 项）；新增缓冲测试最初缺少 RenderSystem.initRenderThread，触发测试线程检查。修正后两策略重跑通过，再进行最终三策略和实例对照，两项均通过。合计 32 个不同测试已成功运行，未重跑全仓库套件。

```powershell
.\gradlew.bat test --offline --console=plain -I tools/prediction/gpu-tests.gradle --tests '*PredictionBatchSubmissionGpuTest' --tests '*PredictionIncrementalRenderTest' --tests '*PredictionProgressiveLoadingTest' --tests '*PredictionSimpleVegetationTest'
.\gradlew.bat test --offline --console=plain -I tools/prediction/gpu-tests.gradle --tests '*PredictionBufferReuseGpuTest' --tests '*PredictionInstanceSubmissionGpuTest'
```

日志：build/dh-lossless-evaluation.log（含最初测试环境失败）、build/dh-buffer-reuse-evaluation.log（两策略复测）、build/dh-lossless-gpu-final.log（最终原型）。这些是本地生成文件。新增测试均由 vss.gpuTests=true 显式启用。

后续优先候选是保留精确数据的批量/实例提交，以及有显存上限和在途保护的缓冲池。完整实施前仍需验证真实材质、透明顺序、morph、遮挡交接和光影，并用游戏帧时间确认收益。未证明所有设备和所有场景都更快，未降低采样精度、植被密度或 LOD 距离。
