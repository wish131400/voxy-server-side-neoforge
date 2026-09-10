# 采样器初始化耗时调查（2026-09-10）

调查仓库：桌面 `voxyserverside-neoforge-1.21.1`。本次没有修改生产采样/调度代码，也没有替换游戏 JAR。另一个任务正在修改这些文件，本报告对应调查时读取的桌面源码及独立测试。

## 游戏日志

游戏目录：`F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249`。

| 轮次 | 收到快照 | 全部采样器就绪 | 间隔 | 首块就绪 | 首块 buildMs |
|---|---|---|---:|---|---:|
| 10:02 | 10:02:35.037 | 10:02:40.313 | 5276 ms | 10:02:40.565 | 47.0 ms |
| 10:16 | 10:16:28.903 | 10:16:34.585 | 5682 ms | 10:16:34.958 | 50.3 ms |

服务器导出快照分别耗时 1483 ms、779 ms，发生在收到快照之前，不属于上述初始化区间。首块 ready 也不等同于 GPU 已经显示的时刻。用户描述的完整 9.8 秒不能全部归入本报告的 5.276 秒区间。

`samplers ready` 在主线程安装全部 managers 后才打印，区间包括异步队列等待、解压/解析、注册表解码、三个维度的上下文和 native 初始化、manager/cache 打开以及主线程回调等待。现有日志不能精确拆分该 5.276 秒。

两条 `profile received; decoding` 不能证明初始化执行两次：`ClientPredictionState.accept` 会通过 `sameWorldgen(acceptedProfile)` 去重，但网络入口仍无条件打印该消息。

## 独立计时

使用现有 `build/server-compat-evaluation/vanilla-result` 三维度快照、种子 42、Minecraft 1.21.1 运行时类和打包的 Windows native 库。测试调用生产 `ClientWorldgenRegistries.decode`、`decodeJavaSampler`、`RustWorldgenDocument.snapshot`、JNI create 和 `RustTerrainSampler` 构造器。

先执行 headless Minecraft bootstrap 和原版 block/fluid tags 加载，不计入下面结果。无客户端资源管理器，颜色图使用原版 classpath PNG；客户端注册表参数使用已解码的 registry access。每个维度测量后关闭 native handle，关闭耗时不计入初始化合计。首次、第二次是同一个测试 JVM 内的两轮，非统计学多次基准。

| 阶段（三维度合计，毫秒） | 首次 | 同 JVM 第二次 |
|---|---:|---:|
| 注册表文件读取/JSON 解析 | 29.873 | 20.959 |
| 注册表 Codec 重建/seed bind | 213.073 | 19.310 |
| 生成器文件读取/JSON 解析 | 27.008 | 15.060 |
| Java settings/biome/RandomState/上下文 | 338.670 | 131.830 |
| 方块定义和全量状态导出 | 259.776 | 87.506 |
| 两张颜色 PNG 读取和 JSON 数组创建 | 157.221 | 42.079 |
| native 库可用性/首次加载 | 14.448 | 0.555 |
| native 输入文档序列化 | 175.165 | 99.058 |
| JNI native create（含 Rust JSON 解析和初始化） | 340.984 | 339.460 |
| native 状态/feature schedule 回传及 Java 解析、映射 | 629.501 | 549.501 |
| 合计（不含关闭） | **2185.719** | **1305.318** |

三个维度均回传 **26684** 条方块状态。输入文档字符串长度分别为主世界 7166588、下界 5601880、末地 5592417 字符。这里统计字符串字符数，不能泛化为任意模组字符串的 UTF-8 字节数。

维度自身耗时（不含共用注册表解码与关闭）：主世界首次 1125.371 ms，下界 449.318 ms，末地 368.084 ms。下界和末地首次合计 **817.402 ms**，第二次合计 **701.687 ms**。当前全部就绪后才发布的流程，使这些工作也阻塞主世界首块。

这不是整合包 5.3 秒的完整复现，不能把这组结果直接从游戏时间中相减推算 GC、资源包或 Tectonic 的占比。未覆盖网络解压、兼容性检查、资源包管理器、颜色指纹、磁盘管理器安装、主线程排队以及真实客户端并发负载。测试每个维度关闭的顺序也与生产同时保留三个维度不同。首次比第二次慢只说明存在首次运行开销，不能仅据此量化 JIT 或 GC。

测试最终通过；早期 headless 注册表/标签未加载造成的失败已排除，未计入表格。

临时可复现文件：
- `build/init-audit/src/dev/xantha/vss/client/prediction/SamplerInitAuditTest.java`
- `build/init-audit/check.gradle`（只编译审计测试，隔离另一个任务正在改的测试）
- `build/init-audit/run.log`
- `build/init-audit/xml/TEST-dev.xantha.vss.client.prediction.SamplerInitAuditTest.xml`

运行：JDK 21，Gradle 8.8，`gradle -I build/init-audit/check.gradle test --tests '*SamplerInitAuditTest' --offline`。Gradle task 缓存命中时需重新触发 test；本次执行实际输出测试阶段计时。

## 已确认的实现问题

1. `ClientPredictionState.accept` 在一次异步任务里等待 `ClientWorldgenProfileDecoder.decode` 返回所有维度，然后才打开并安装全部 managers；主世界不能先发布。
2. Decoder 逐维度串行创建完整 Java 上下文再创建 Rust。Java 上下文承载模组结构/不支持的 feature 等能力，不能不加区分地删除。生成器 JSON 还被重复解析。
3. `RustWorldgenDocument.snapshot/create` 每个维度重新枚举全部方块和状态，并分别读取草、叶颜色图。两张颜色图每维度产生 131072 个 JSON 数字。
4. Java 已经持有全部 BlockState，却先 Codec 编码传入 native，然后把 native `describe` 返回的状态表再 Codec 解码一次。独立测试中这部分状态回传/解码阶段比 native create 本身更贵。
5. 本地 tile 缓存依赖 manager 安装；缓存命中不能跳过上述全部 sampler 初始化。`PredictionCacheStorage.open` 通常不遍历所有缓存瓦片，目标目录存在时 legacy migration 立即返回；没有证据显示 5 秒来自全量缓存扫描。
6. DLL 解包加载有 JVM 内一次性保护，不能当成每维度重复加载的主要问题。

源码位置：
- `src/main/java/dev/xantha/vss/client/prediction/ClientPredictionState.java:87`
- `src/main/java/dev/xantha/vss/client/prediction/ClientWorldgenProfileDecoder.java:31`
- `src/main/java/dev/xantha/vss/client/prediction/RustWorldgenDocument.java:17`
- `src/main/java/dev/xantha/vss/client/prediction/RustTerrainSampler.java:63`
- `src/main/java/dev/xantha/vss/client/prediction/PredictionCacheStorage.java:65`
- `tools/rust/vss-native-core/src/backend.rs:207`

## 建议顺序

1. 优先创建并发布当前维度，让其读取缓存和出首块；其他维度按需或低优先级初始化。正确处理切维度、断开连接和旧 generation 取消，不能让过期 sampler 安装回来。
2. 将方块定义、状态编码、颜色图作为同一 profile/资源代际的不可变共享输入，避免每维度重复导出。世界/注册表/tag/资源包变化时必须失效。
3. 用明确校验过的状态索引协议减少状态 JSON 往返。不能未经证明就假设不同 native handle 的 palette ID 完全相同；处理 native 新增状态和资源生命周期。
4. 避免重复解析生成器 JSON；在确认调用依赖后延迟不影响地形首块的植被/结构准备。
5. 游戏端加 debug-only 单次分阶段计时，将队列等待、registry decode、逐维度 Java/doc/native/state decode、cache open、主线程安装拆开，才能对真实整合包 5.3 秒逐项归因。不要恢复每瓦片刷屏日志。

当前证据不支持把这次延迟直接归因为“CPU 核心限制”或“Rust 地形计算太慢”，也没有足够数据承诺优化后的完整入场秒数。
