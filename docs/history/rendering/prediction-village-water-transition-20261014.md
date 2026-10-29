# 村庄、水面与粗细 LOD 交接修复记录（2026-10-14）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮针对截图中的村庄缺失、水面横向条带和粗细 LOD 交接进行调查与修复。生产修改已同步 NeoForge 1.21.1 / Forge 1.20.1，两边均构建为 0.3.5。已完成现场只读检查、真实模组模板池克隆验证、CPU 回归和 OpenGL 回归。

用户客户端仍运行上一轮 far-terrain-20261014 包。本文区分现场旧版本证据与新代码的独立验证；没有记录修复后客户端画面、FPS 或完整细化加载耗时。未替换客户端 JAR，未改用户配置，未推送 GitHub。

## 现场范围与设置

在用户指向问题区域后取得的有效现场快照（live-05）中：

- 相机约为 (-30.51, 989.07, 177.77)，未暂停；视线与 y=63 水面交点约为 (1127.14, 63, -866.29)。
- 原生地形后端 rust=true，结构预测开启，植被为 medium。
- predictionDistanceBlocks=8192，predictionSurfaceDistanceBlocks=512，当时管理器 surfaceRadius=848。
- predictionSpyglassLoading=false。此设置关闭望远镜额外请求远处精细地形、植被和建筑；缩放仍显示已有数据。正常视野中的屏幕细节需求仍由原有规划规则处理。
- 后续只读检查时客户端已暂停，没有据此制作新的 GPU 现场结论。

远处地形可以先于建筑细节显示。当前望远镜开关和地表需求范围会影响何时请求远处建筑，因此修复模板池后也不能承诺任意距离立即显示全部结构。

## 村庄结构：模板池缺少运行时初始化

现场安装 Lithostitched 1.8.0+beta6（NeoForge 21.1）。VSS 隔离注册表通过 Codec 解码模板池后，池中保留 rawTemplates，但没有完成 Lithostitched 的 compileRawTemplates 阶段。该模组接管 Jigsaw 选择后，运行时道路池为空；现场记录的 12 个有效村庄 starts 均只有 1 个 piece。

新增 PredictionTemplatePoolRuntime 兼容桥接，在 ClientWorldgenRegistries 解码自有模板池之后补齐初始化：

- 没有 Lithostitched 时直接返回。
- 只处理 VSS 刚解码的池，不修改真实世界注册表。
- 已有编译条目时不再次编译，避免模板权重翻倍。
- 自定义模板池注册表因解码故障已禁用时，不进入该初始化。

用真实模组接口和生产新 classpath 对道路池的独立克隆进行验证：

| 道路池 | 编译条目数 | raw 权重和 | 编译权重和 | 第二次初始化后权重和 | live 原池条目数 |
| --- | ---: | ---: | ---: | ---: | --- |
| desert | 11 | 35 | 35 | 35 | 0 → 0 |
| plains | 16 | 49 | 49 | 49 | 0 → 0 |
| savanna | 19 | 56 | 56 | 56 | 0 → 0 |
| snowy | 16 | 47 | 47 | 47 | 0 → 0 |

数据来自 pool-clone-check3.json；旧探针曾将迭代条目数误命名为 compiledWeight，本文不使用该旧字段结果。

完整村庄回归使用记录的模板池 JSON 和原版结构 NBT，两平台均生成 125 个 pieces，包含 streets 与 houses。该结果证明模板池可以继续展开，不代表用户截图位置的新版本实机村庄已有 125 个 pieces。

## 结构隔离环境的维度与实体 marker

现场还记录了 RuinedPortal 调用 dimensionType 不受支持，以及 OceanRuin 调用 getLevel 不受支持。

WorldgenCodecSnapshot / WorldgenProfileBuilder 现在将实际维度类型编码到 generator JSON 的可选 vss_dimension_type 字段。客户端解码后绑定到 Java decoration context，包括原生地形后端使用的 Java 装饰环境。PredictionDecorationLevel.dimensionType 返回该类型。

- 只扩展 JSON 元数据，既有网络字段布局保持兼容。
- 旧快照可从同名 DIMENSION_TYPE 注册表项回退。
- 自定义维度若使用不同名称的类型且旧快照缺少元数据，明确报告缺失，不擅自替换成主世界类型。
- 自定义维度的完整元数据需要服务端也更新本轮包。
- CPU 回归确认 Rust 仍接受带该元数据的文档，地形采样与基线一致，Java 装饰环境得到正确维度类型。

原版 underwater_ruin 模板的 drowned marker 会先创建实体，再进入预测不生成实体的限制。现在只在这些原版模板中清空 drowned 元数据，保留 chest 和其他 marker，自定义模板不受此规则影响。

新增结构运行时缓存版本位 bit12，与植被密度 bits9～11 分开。新的 surface / finished mesh 缓存身份避免复用旧版本缺建筑的结果，原始地形采样缓存继续可用。PredictionDiskCache 的真实编辑失效枚举同步包含新设置，防止新版缓存逃过编辑失效。无需手动删除整个缓存目录。

## 水面：导出的预测河床深度被误当作真实遮挡

普通渲染路径先将预测不透明地形的 32 位浮点反向深度导出到 Minecraft 主深度，再经过真实透明阶段，最后绘制预测水面。高空或较大裁剪距离下，主 D24 深度量化可能把水面与其自身河床放进同一档。水面 shader 再读取主深度时，会将已导出的预测河床当成真实地形，误剔除水面。

使用生产 RenderTarget 与 shader 的 OpenGL 回归复现：

| 视角 | 原路径缺失水像素 | 修复路径缺失水像素 |
| --- | ---: | ---: |
| 高度 997、FOV 70°、576 个水像素 | 168 | 0 |
| 高度 128 / 997 / 2048 × FOV 70° / 7° | 其中高空配置可复现缺失 | 六组均为 0 |

新回归保留负对照：关闭修复后高度 997 / FOV 70° 只显示 408/576 个水像素，避免测试因场景不再触发问题而误通过。此复现证明存在该水深度故障，尚不能证明截图中每处水异常都只来自此原因。

新增 PredictionWaterDepthHistory，为有 deferred water 的本帧保存两份主深度：

1. 预测不透明深度导出前的真实主深度。
2. 导出后的主深度。

两份纹理采用源主深度的相同存储格式，避免 D24 转 D32F 后丢失精确比较。主纹理报告未定尺寸 GL_DEPTH_COMPONENT 时，通过实际 DEPTH_SIZE / DEPTH_TYPE 选择匹配的 sized format。

水面只有在当前主深度等于本帧导出值、且导出确实写入了更近深度时，才恢复导出前的真实深度进行判断。后画的较近真实表面继续遮挡；原始 Voxy 深度继续处理真实水的归属。预测自身仍由独立 32F 深度附件处理，不采样当前绘制附件形成反馈。

资源与状态约束：

- 仅有 deferred water 的帧复制两次；普通无水帧不新增这些复制。
- 按视口与源纹理复用，开始新帧清除有效状态，resize / close 释放；不会跨帧使用旧 provenance。
- 两份 1920×1080 深度纹理按每像素 4 字节估算约 15.8 MiB；实际驱动存储和额外 GPU 耗时尚未实机量化。
- 使用 copyImageSubData；不可用时回退 copyTexSubImage2D 并恢复 read FBO。分配隔离外部 pixel-unpack buffer。
- 仅普通 deferred water 状态捕获扩展到纹理单元 0～10；恢复相关纹理和 sampler 状态。
- Iris 光影专用路径保持自身深度处理方式，没有启用这套历史主深度判断。
- 普通地形 shader 继续保持 early-Z，没有重新引入 gl_FragDepth。

回归覆盖导出前后真实水优先、较深真实河床不误删较近预测水、较近真实表面遮挡、帧重置、resize / close 资源释放和 GL_NO_ERROR。

## 粗细交接：改为局部保护与局部渐变

原有高度场 morph 是 350 ms，但有两条过宽的保护会将整块瓦片的渐变关闭：

- 只要任意 sample 有水，整块不渐变。
- 只要存在代表树或草，管理器就不创建整块 morph。

现在水面本身继续固定，邻水顶点周围 3×3 samples、父级插值 cell 中的水或无效数据均局部固定；同块远离水岸的干地可以继续原有 350 ms 渐变。代表树草本体保持固定，脚下插值顶点按树半径 3、草半径 1 保护，其他干地仍可渐变。

边界固定、最大 ±8 方块位移、静态动画起始时间和逐帧时间参数保留。CPU 回归确认岸边与树草脚下固定、干地继续渐变，树草坐标没有被改动。

以下保护仍存在：spacing ≤2 的精细网格、完整 feature blocks、城市建筑、scopePromoted、captured / volume 地形。这次没有通过新增线程或放宽 CPU 预算缩短全部生成队列，也没有实测新的细化等待时间。渐变改善的是网格准备就绪后的部分交接；望远镜额外加载关闭、细节尚未生成、上述保护区域仍会影响观感。

## 验证、构建与交付

六个 suite 的执行结果均为 failure=0、error=0、skipped=0。部分 suite 包含相同测试，按 classname#name 去重后的统计如下：

| 平台 | CPU 独立用例 | OpenGL 独立用例 | CPU suite 执行数 | GPU suite 执行数 |
| --- | ---: | ---: | ---: | ---: |
| NeoForge 1.21.1 | 323 | 14 | 325 | 16 |
| Forge 1.20.1 | 321 | 14 | 323 | 16 |

包含村庄池初始化与幂等、村庄完整展开、维度元数据和原生兼容、OceanRuin marker、局部 morph、旧缓存失效/真实编辑/冷恢复，以及现有主线程减负、远景提交、归属切换和渲染目标回归。有意故障注入和 fixture 缺实体池的 warning 不作为成功依据，结果以 JUnit XML 为准。

隔离构建配置：tools/prediction/village-water-tests.gradle。输出目录：build-village-water-20261014。NeoForge 使用 Java 21；Forge 使用 Java 17，产物已 reobf。两个包都保留原有五个平台的 native 库；元数据版本分别为 0.3.5-neoforge-1.21.1 和 0.3.5-forge-1.20.1。Forge 包含 refmap，相关 RenderTarget 调用已转为生产命名。

构建命令（各自在对应仓库执行）：

~~~powershell
# NeoForge
.\gradlew.bat --offline --no-daemon -I tools/prediction/village-water-tests.gradle jar

# Forge
.\gradlew.bat --offline --no-daemon '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' '-Dnet.minecraftforge.gradle.check.certs=false' -I tools/prediction/village-water-tests.gradle jar reobfJar
~~~

交付目录均为 lib/village-water-20261014，包含 JAR、SHA256SUMS.txt、validation.json 和小型 evidence 副本：

| 平台 | 文件 | 字节数 | SHA256 |
| --- | --- | ---: | --- |
| NeoForge 1.21.1 | vss-0.3.5-neoforge-1.21.1.jar | 6,075,027 | 708199ae1a995290a4faed884faa66822c99c1f208385606e825054b98d72c30 |
| Forge 1.20.1 | vss-0.3.5-forge-1.20.1.jar | 6,745,338 | c3936d472cef9e7173d58a1f38fdaba8ae6510ce6393fde8d124d2e289603b2f |

evidence/lithostitched-pool-clone.json 为真实模组克隆检查；deferred-water-before.xml 为修复前失败复现；deferred-water-after.xml 为当前生产实现回归；village-runtime.xml 为村庄 fixture 展开结果。完整现场临时证据保留在 C:/Users/Administrator/.codex/tmp/vss-village-water-transition-20261014，未将大型现场快照复制进仓库。

用户自行替换对应平台 JAR 后，应先核对问题位置的水面、村庄道路房屋和移动时交接。若需用望远镜请求尚未准备好的远处建筑、植被与精细地形，开启已有的“望远镜远处加载”设置。实机验收前不能据独立回归推断 FPS 改善幅度或全部视觉异常消失。
