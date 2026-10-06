# 预测 LOD 与 Minecraft / Sodium / Voxy 的可见性链路对照

检查日期：2026-10-05。问题：面向预测一侧 FPS 明显降低，地面遮住远景后预测开销似乎不下降。

## 结论

预测有距离、视锥、面朝向和区域覆盖筛选，也有主深度播种及 GPU 深度测试；但没有依据屏幕遮挡结果，在执行实际网格的顶点着色器之前剔除 tile / 子网格的阶段。视锥内、山后完全不可见的网格，仍进入绘制命令。深度测试可以省掉部分片元工作，无法收回已发生的命令、顶点取数、解码和图元处理。

Voxy 则有 HiZ 层级遍历、包围盒遮挡测试、GPU 可见性驱动的间接命令生成。这个结构差距直接解释了“被近处地形挡住时，Voxy 能明显减负，预测减负有限”的现象。不能仅由两张截图或源码，把全部 FPS 差额精确归因于这一项。

本轮完成链路检查和已有回归验证，没有加入新的生产遮挡系统或生成新的优化 JAR。

## 核对对象与证据范围

- 安装版 Sodium：`sodium-neoforge-0.8.12-beta.2+mc1.21.1.jar`，从外层包取出实际嵌套 mod JAR 后反编译相关类。
- 安装版 Voxy：`voxy-0.2.15-beta.jar`，直接提取 shader、反编译管线和 MDIC 后端；同时对照仓库已有上游源码。
- Minecraft 1.21.1：读取本项目 NeoForge 21.1.234 开发缓存中的 `LevelRenderer`、`SectionOcclusionGraph`、`VisGraph` 和 section 编译代码。用户截图运行 NeoForge 21.1.249，因此这一部分作为 Minecraft 1.21.1 原版机制参考；实际客户端地形路径以安装版 Sodium 为准。
- 当前磁盘安装的 VSS SHA-256 为 `52147987e799809fa740601e379066f4f1f440e863158df63980f52c28052b1c`，与上一轮深度 / 水面修复交付一致。磁盘一致不用于反推截图一定加载了哪个包。
- 本轮检查时 `jcmd -l` 未检测到运行中的 Minecraft Java 进程，未作新的实机 FPS 对照。上一轮实机采样与本轮截图分别标明，不混为同一批测量。
- 证据目录：`build/render-visibility-20261005/`。`inputs.json` 保存依赖校验；`analysis.json` 保存配置、旧采样提取和本轮测试计数。

## 三条链路

| 阶段 | Minecraft 原版 / 当前 Sodium | 当前 Voxy MDIC | 当前预测 LOD |
| --- | --- | --- | --- |
| 世界空间单元 | 16³ section | 分层的三维 section 节点 | 水平 tile + 整块网格高度包围盒 |
| 距离 / 视锥 | 有 | 有 | 有 |
| 提交前的遮挡判断 | section 空间连通关系，保守遍历 | HiZ + 光栅化包围盒遮挡 | **缺少基于深度的 tile / 子网格遮挡剔除** |
| 实际绘制列表 | 先收集可见 section，再按 region / pass 批量提交 | GPU 根据可见标记生成间接命令 | CPU 根据 tile / 面组组织 MDI 命令 |
| 被前景挡住的网格 | 图不可达的 section 可以不进列表；不是任意山体轮廓的精确像素遮挡 | 被深度证明遮住的节点 / section 不生成对应新命令 | 若仍在视锥及候选范围内，通常仍提交 |
| 最终深度测试 | 有 | 有 | 有，正常路径允许驱动 early depth 优化 |

### Minecraft / Sodium

Minecraft 的 `SectionCompiler` 调用 `VisGraph.setOpaque` 标记不透明体素，并把 `resolve()` 的结果保存在 section。`SectionOcclusionGraph.runUpdates` 通过 `facesCanSeeEachother` 决定能否继续向相邻 section 传播，`LevelRenderer` 再形成视锥内列表。

Sodium 的 `LevelRendererMixin` 覆盖地形准备 / 绘制，实际走 `SodiumWorldRenderer` → `RenderSectionManager.createTerrainRenderList` → `OcclusionCuller.findVisible`。遍历把距离、视锥和 section 入口 / 出口的可见关系结合；随后 `DefaultChunkRenderer.fillCommandBuffer` 只处理列表中的 section，再按面组和 region 批量绘制。位于图外时也存在树遍历回退，不能把它描述成所有场景都完全依赖连通图。

安装配置中 `use_fog_occlusion`、`use_block_face_culling` 均为 true。图剔除与逐像素 HiZ 是不同机制；不能把 Sodium 描述成使用了 Voxy 的那套 GPU 遮挡。

### Voxy

安装版 `AbstractRenderPipeline.runPipeline` 的重要顺序是：

1. 准备目标、已有深度 / stencil。
2. `renderOpaque` 利用已有命令绘制，建立深度。
3. `innerPrimaryWork` 构建 HiZ mip chain，再执行层级遍历。
4. `traversal_dev.comp` 在 `outsideFrustum() || isCulledByHiz()` 时停止该节点的可见路径；只有通过的节点才继续按屏幕大小下钻或进入渲染队列。
5. `MDICSectionRenderer.buildDrawCalls` 画膨胀的 section 包围盒，以深度测试写可见性标记。测试关闭颜色和深度写入；专用 `raster.frag` 使用 `early_fragment_tests`。
6. `cmdgen.comp` 读取当前帧可见标记，只有 `shouldRender` 的 section 才产生真实几何命令；同时生成 temporal 补绘命令。
7. `renderTemporal` 补绘新可见几何，再处理后续透明 / 合成阶段。

这是一套有时间复用与补绘的系统，不是“先剔除完，再一次性画所有几何”的简单单通道。刚转头或刚被遮住的瞬间仍可能有额外工作，但稳定遮挡时可以显著减少真实网格提交。

对应证据：

- `build/render-visibility-20261005/voxy-decompiled/me/cortex/voxy/client/core/AbstractRenderPipeline.java`
- `build/render-visibility-20261005/voxy-decompiled/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java`
- `build/render-visibility-20261005/voxy-shaders/assets/voxy/shaders/lod/hierarchical/screenspace.glsl`
- 同目录的 `hierarchical/traversal_dev.comp`、`gl46/cull/raster.frag`、`gl46/cmdgen.comp`

### 预测 LOD

`PredictionRenderer` 创建当前相机的扩展距离视锥。`PredictionSpatialIndex` 做世界空间粗筛，`PredictionVisiblePlan.update` 根据 `frustum.isVisible(box)` 决定进入列表。`PredictionFramePlan.getStable` 比较相机、model-view、projection、分辨率、场景和 residency；转头 / 移动 / FOV 变化不会无条件沿用旧列表。

但上述列表没有接收屏幕深度、HiZ 或遮挡查询结果。`PredictionPackedMesh.drawRanges` 仅按不透明 / 水和面朝向选择连续范围。`PredictionIndirectBatch.add` 把范围直接写入间接命令；`flush` 直接调用 `glMultiDrawElementsIndirect`。批量合并降低 CPU 调用次数，不自动剔除山后几何。

剩余覆盖 / 可见性工作发生在生产 shader：

- 顶点阶段读取 quad 数据、颜色 / 材质、morph 等，再把已知不可见面的顶点移出裁剪空间。这里已经支付了顶点处理成本。
- 片元阶段还要处理真实深度、覆盖索引、所有权、原版 mask、接缝和材料。
- 正常管线已不写 `gl_FragDepth`，并已将有效主深度播种到预测目标，因此允许驱动进行早期深度拒绝。它省的是后段工作，不能等同于 tile 在提交前已被剔除。
- 整个 tile 的最小 / 最大高度形成保守包围盒。即使只有顶部少量内容可能可见，整块的某些面组也仍需提交；缺少更细的三维子网格包围盒。

明确区分三种状态：完全在镜头后面 / 视锥外的 tile 会被剔除；镜头方向内但被近山完全遮住的 tile 仍可能提交；已经驻留或后台构建的 tile 不等于本帧实际提交了绘制。

## 两张截图与此前实机采样

用户提供的方向标识为西侧预测、东侧 Voxy。两图高度和视角略有变化，属于现场观察，未严格锁定相同位置、世界进度及采样窗口。

| 截图 | FPS | 由 FPS 换算的帧时间 | Voxy 调试 QC 合计 |
| --- | ---: | ---: | ---: |
| 面向预测侧 | 63 | 15.87 ms | 4,211,841 |
| 面向 Voxy 侧 | 104 | 9.62 ms | 9,527,930 |

Voxy 自己的 QC 在较快那张反而更多。安装版 `RenderStatistics` 证明 QC 是 Voxy 的分 LOD quad 计数，不能当作 VSS 预测统计，也不能直接与总 FPS 建立线性关系。`cmdgen.comp` 明确未把 temporal 重绘加入该计数；这些值不是总 GPU 图元数。

此前 20.032 秒固定视角样本中，预测平均提交约 7,050,633 quads / 帧，约 510 次实际 GL 绘制入口 / 帧。采样时 VSS 为上一轮修复前版本；这些数字用于说明已观察到的工作规模，不冒充当前新包测量。

六份快照的最近一次不透明提交距离桶一致：

| 水平 tile 中心距离 | 提交 quads |
| --- | ---: |
| 0～256 | 25,846 |
| 256～1,024 | 1,310,222 |
| 1,024～4,096 | **5,283,635（75.10%）** |
| 4,096～16,384 | 415,747 |
| 16,384 以上 | 0 |

这些是不透明通道的最近一次计数，不是六次累计平均；包含实际被遮挡、裁剪或 shader 丢弃的几何。不能从 75.10% 推断它们全部不可见，也不能推断都可以剔除。

上一轮受控窗口内预测 GPU 不透明约 7.58 ms、水约 0.57 ms、深度播种约 0.08 ms、AA 约 0.33 ms。不透明几何路径是已测出的主要 GPU 工作。窗口总体被约 30 FPS 限制，不能据此给出新包 FPS 提升比例。

## 其他明确差异

- Voxy quad 主记录为 64 bit / 8 字节，另有模型和材料数据。预测普通记录 48 字节，紧凑格式为 32 字节 / quad 加颜色字典；它包含独立角点、颜色、光照和覆盖等信息。格式大小、额外取数和解码说明单个 quad 的成本不同，不能把 4～6 倍主记录大小直接说成 4～6 倍实际带宽或耗时。
- Voxy GPU 生成集中式命令流；预测按 arena page 以及距离桶拆批。不透明仍有分桶分组，水面为维持顺序按反向列表提交，page 切换会 flush。它已使用 MDI，缺口在可见性筛选和批次组织，不能用“换成 MDI”概括优化。
- 当前配置预测半径 5,120 方块，精细距离 1,536，AA 开、supersample 关。没有修改这些设置。屏幕约 3840×2066，确有全屏深度 / AA 成本，但旧窗口实测规模小于不透明阶段。
- 生成、网格更新、上传与渲染提交是独立链路。旧静止采样仍有构建 / 上传；加入绘制遮挡不会自动取消后台生成。贸然用当前视线停止缓存生成也可能让转头时缺 LOD。

## 推荐落点与验收条件

第一优先级是在预测实际间接命令执行前加入保守的 GPU 遮挡筛选，避免不可见网格执行顶点着色器。接入位置是 `PredictionRenderer` 的可见候选 / 深度准备与 `PredictionIndirectBatch` 的命令提交之间。VSS 索引负责区域归属，遮挡只负责当前视图是否提交；不能用某帧的遮挡结果修改永久覆盖归属或释放必要回退瓦片。

仅使用原版 / Voxy 深度还不能解决“近处预测山挡住远处预测山”。完整方案还需利用已经画出的近处预测不透明深度，可考虑近处优先绘制后构建 / 更新 HiZ，或带当前帧补绘的时间复用。透明水面不能未经区分就充当完全不透明遮挡物；空白深度、树叶孔洞、视锥近平面相交和未知结果应保守保留。

逐 tile 的 CPU 同步 occlusion-query 回读会引入等待，不应以它取代当前瓶颈。GPU 筛选应直接让不可见命令归零 / 压缩列表。复用结果必须按视图、相机、投影和深度版本失效，快速转头、飞行、切换维度时不能出现迟到一帧的山体。

第二优先级是网格分段：有紧凑边界的子网格更容易被证明完全遮挡；整列跨越海底、海面、树顶的 AABB 可能长期保留。之后才根据每个距离桶、材质与 pass 的统计调整密度和 page 批次。

不能在现有含大量 `discard` 的地形 shader 上直接强制 `layout(early_fragment_tests)` 当作替代方案：深度写入可能先于覆盖 / cutout discard，重新制造不可见遮挡。也不能直接全局打开背面剔除来替换当前面朝向逻辑，现有三角形 winding 混合。

验收应在同一地点依次测量无遮挡远景、地面近山遮挡、背向该区域、快速转头。分别记录 frustum 候选、depth-rejected tile / 子网格、最终提交 quads / commands、vertex / fragment invocations、不透明 / 水面 GPU 时间和 CPU 时间。目标是地面遮挡时最终提交 quads 下降，同时边界缺水回退和快速转头保持完整。

## 本轮验证

运行现有 `PredictionRenderWorkTest`、`PredictionFramePlanTest`、`PredictionRendererTest`，16 项全部通过、0 跳过、0 失败。覆盖移动 / 投影变化的缓存失效、窄视野筛选、面组索引范围和场景归属行为。这些测试支持“已有视锥与缓存失效正常”，不把它们当作新遮挡系统或新游戏帧率的验证。

日志：`build/render-visibility-20261005/visibility-tests.log`；XML 已单独复制到同目录的 `test-results/`。
