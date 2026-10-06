# 预测 LOD 与 Voxy 交接闪烁修复

日期：2026-10-05。修复已同步到 NeoForge 1.21.1 和 Forge 1.20.1，分发版本仍为 0.3.5。

本轮修复了两个能通过生产路径稳定复现的缺陷：CPU 更新排队导致已有 GPU 地形反复交回预测，以及 Minecraft 缓存状态与原始 OpenGL 状态不同步导致旧深度残留、深度导出涂黑画面。另对前台采样中的边界查询、待上传检查和索引无效删除进行了小范围性能优化。完整游戏的修复后视觉效果与 FPS 尚未验证。

## 现场证据

用户录像 `C:\Users\Administrator\Videos\2026-10-05 15-55-53.mkv` 长约 29.183 秒，录制帧率为 60 FPS。天空、手和 HUD 稳定时，地形仍在相邻帧之间切换。14.333–14.383 秒可见预测沙岛、详细地形、黑片/碎片、预测沙岛的切换；24.350–24.383 秒详细远景只出现一帧。基于固定阈值的分析检测到 18 组、41 次地形跳变；录制帧率与这些图像差异不能当作游戏 FPS。

用户回复“返回了”后，在香港时间 16:17:01–16:18:01 进行了 60 秒前台 JFR 采样，窗口内无新增暂停记录。该进程使用上轮 `9ef222...` 包，采样显示边界覆盖查询和待上传 tile 检查是 Render thread 的主要可见 VSS 热点。包含栈的样本互相重叠，不能将其数量直接当作 CPU 耗时占比。

Render thread 在 60.020 秒内累计分配 3793.682 MiB，约 63.207 MiB/s；这是堆累计分配，包含已经回收的对象。20 次 GC pause 最大为 6.616 ms，采样没有证明 GC 是长卡顿的根因。没有本轮新包的 FPS 或帧耗时对照。

该次运行没有上轮 `this.wrapped=null` 上传异常；上轮上传对象回收竞态的修复继续包含在交付包中。

## 已上传地形的稳定交接

`StrictLodVisibility.predictionCoverage` 与 `predictionCoverageBox` 原来将 pending ingest 或尚未完成的 mesh 任务视为“未覆盖”。然而 Voxy 在替换 mesh 上传前仍绘制原来的 GPU mesh。排队导致预测重新出现，任务完成后预测再被剔除，持续更新就会重复切换。

现在预测剔除的依据是客户端已上传的节点覆盖和当前有效视距窗口。ingest/mesh 排队与完成不再撤销已有 GPU 节点的交接资格，也不再因这些队列事件使预测覆盖缓存失效。实际节点上传、更新、删除和视距窗口变化继续使相应覆盖失效；节点删除后立即恢复预测兜底。

请求环的 readiness 仍检查待处理任务及 ingest 失败状态。服务器 section 清单、客户端文件索引用于确定数据范围与请求需求；实际可见交接由客户端上传节点和帧深度确认。

新 `PredictionVoxyStableOwnershipTest` 在修改前为 2/2 失败，修改后通过。它覆盖连续 120 次 ingest/mesh 更新仍保持已上传地形的交接，以及真实节点删除立即恢复预测。

## 深度与颜色写入状态

Voxy 使用原始 GL 调用，Minecraft 则缓存部分状态。当实际 GL 状态已经被改变、缓存仍是目标值时，单独调用 RenderSystem 可能直接跳过状态安装。

独立 GPU 回归直接调用生产 `PredictionRenderTarget`，在 NVIDIA GeForce RTX 4070 Ti SUPER 上复现：

- 当前目标深度应为约 0.015625，旧代码在 depth mask、depth test 或 depth function 失配时保留上一帧的 0.1250002。
- 深度导出应关闭颜色写入。缓存显示颜色写入已关闭、实际 GL 却仍启用时，旧代码跳过关闭操作，导出 shader 的黑色输出将主目标 RGB (64,128,191) 写成 (0,0,0)。

`PredictionGlState` 的普通渲染路径现在先同步 Minecraft 缓存，再无条件设置实际 GL 状态。`PredictionRenderTarget` 的深度种入、深度导出、颜色写入、纹理单元与绑定统一使用这些 helper。首次创建或调整深度纹理尺寸也明确指定纹理单元。Iris 隔离回调继续只操作原始状态。

修改后，各深度失配用例得到 0.015624428，深度导出后主颜色为 (64,127,191)，在量化容差内保留原色。本轮没有新增每帧 GL 状态读取。

这些回归证明修复了生产渲染路径的状态失配缺陷；尚不能单独确定录像中由哪个外部 pass 留下了该失配。

## 采样热点优化

`PredictionRenderer.voxyBoundaryCoverage` 使用现有 `PredictionCoverageOwners` 减少重复的 covering tile 查询。保留 128 扇区、每扇区三次角度采样、desired LOD=0 和 GPU drawable/allowed 检查。每次仍构建当前遮罩，避免遗漏同 snapshot 下的覆盖更新。

普通帧准备先尝试获取可复用计划，只有可能复用时才验证相关 pending uploads。移动或转向已经使计划不可复用时，省去提前扫描待上传 tile 的工作。

`PredictionRenderResidency.pendingUploads` 仅在 source snapshot 或 residency revision 改变时清理候选。视界外未上传 tile 继续保留，进入视界后仍能立即发现。回归覆盖 120 次同状态查询不重复清理、视界外转入视界、上传完成、同键替换和 reset。

索引优化只减少无变化查询的分配：exact index 空表/缺页/缺格提前返回；authoritative/edit 集合为空时提前返回，非空集合共享 boxed key；本地索引空区域表提前返回。实际删除仍使用同步的页更新逻辑，保留末格删除与同页并发插入的正确性。

| 200000 次生产方法调用的线程分配 | 修改前字节 | 修改后字节 |
| --- | ---: | ---: |
| 空 exact index 删除 | 14400128 | 128 |
| 非空 exact index 的缺页删除 | 14400000 | 4800000 |
| 空 authoritative/edit 集合撤销 | 9600000 | 0 |
| 空本地 confirmed/stored 查询对 | 9600088 | 88 |

这些结果是分配实验，不代表实机 FPS 提升。

## 双加载器兼容性

同步按方法和代码块完成，保留加载器已有差异。Forge 的 Xaero 本地回放与补图、移动判定和 `hasResidentCover` 保持其原有行为。Forge authoritative/edit 集合继续使用 `cellSetKey`，NeoForge 使用其原有 `pack`；最终 Forge 包反汇编确认 `revokeAuthoritative` 调用 `cellSetKey`。

## 最终验证与产物

| 验证 | 总数 | 通过 | 跳过 | 失败/错误 |
| --- | ---: | ---: | ---: | ---: |
| NeoForge 完整 build | 1367 | 1294 | 73 | 0 |
| Forge 最终完整 build | 1374 | 1303 | 71 | 0 |
| NeoForge GPU 与交接专项 | 29 | 29 | 0 | 0 |

专项包含真实 GPU 渲染目标、原始/缓存状态失配、实际 Voxy shader fixture、覆盖更新和上传交接回归；29 项不全是独立 GPU 测试。Forge 最终 `reobfJar` 已完成。

包审计确认 ZIP 完整、无重复条目、未打包测试类/JUnit；NeoForge Java class major 为 65，Forge 为 61。两套包的五个平台 native 资源与源码逐字节一致，且与上轮交付审计一致。

交付路径：

- NeoForge：`C:\Users\Administrator\Desktop\voxyserverside-neoforge-1.21.1\lib\vss-0.3.5-neoforge-1.21.1.jar`，5489902 字节。
- Forge：`C:\Users\Administrator\Desktop\voxyserverside\lib\vss-0.3.5-forge-1.20.1.jar`，6162579 字节。

SHA-256：

```text
NeoForge 5fd9a418234c8a2aa5d5bf97ae510f50c7202ccdb82997dd172f0028b65e5925
Forge    4bfe071ba5397629d32283a847af8a185bcc67f068739f9bfdc7b741cf76f5a7
```

用户最新指示是自行替换 JAR 和启动游戏。本轮收尾只读核对发现，测试实例 `F:\NovaEngineering-World整合包\.minecraft\versions\1.21.1-NeoForge_21.1.249\mods` 中只有一个可加载的 VSS JAR，其磁盘哈希已经与本轮 NeoForge 新包一致。该观察记录于 `instance-observation.json`，仅证明磁盘文件，不确认当前游戏进程的加载来源或修复后视觉效果。

现场证据、baseline/fixed 回归 XML、最终构建日志和包审计位于两个仓库的 `build/flicker-20261005-1555/`。录像、GPU 和采样资料主要位于 NeoForge 仓库。Forge 最终 XML 已保存至 `full-forge-final-results/`，最终同步差异保存为 `final-task.patch`。
