# 当前 VSS 服务端与预测 LOD：2026-09-10 评估

评估对象：桌面主仓库当前 NeoForge 1.21.1 / VSS 0.3 源码。未修改生产代码、整合包安装文件或玩家存档。

## 结论

当前服务端能启动并导出客户端预测所需快照，预测逻辑可以在连接它的客户端运行。Rust 地形采样、植被生成、精细化调度、网格生成和 GPU 渲染均在客户端。服务端没有共享的 Rust 预测计算服务，也不向玩家分发预计算预测 tile。
已有真实地形仍走服务端真实列同步及脏列通知，由客户端 Voxy 接管真实显示。

## 本次实测

使用 JDK 21、仓库开发运行时 NeoForge 21.1.234，独立临时目录、127.0.0.1 和系统分配端口启动两个专用服务器。未安装 Voxy、Sodium、Iris。两次均进入 DedicatedServer Done，随后在服务器线程调用实际 WorldgenProfileHolder / WorldgenProfileBuilder，经过生产 payload codec 编码/解码，最终正常保存停止。
不是通过单人游戏的直接对象投递路径。

| 环境 | 首次快照 | 再次读取 | 编码后 payload | 结果 |
|---|---:|---:|---:|---|
| 原版生成 + VSS | 351.556 ms | 0.0138 ms | 2,828,142 字节 | 3 维度；编码解码 sameWorldgen=true |
| Tectonic 3.0.26 + Lithostitched 1.8.0+beta6 + VSS | 1174.089 ms | 0.0158 ms | 2,969,863 字节 | 3 维度；编码解码 sameWorldgen=true |

两次快照导出完成时 loadedVssClientClasses=[]，没有加载 VSS 客户端预测类。
Tectonic 快照实际含 3 个 noise_seed_aliases，以及 lithostitched:fast_noise_config、lithostitched:template_list 两类自定义注册表。
原版导出了 1180 个结构模板，输入模板共 3,631,120 字节；Tectonic 组合为 3,721,653 字节。
结果和日志：build/server-compat-evaluation/{vanilla-result,tectonic-result,dedicated.log,tectonic.log}。
最早 --initSettings 只验证了引导初始化，该模式跳过 ServerModLoader，不能算专用服务端模组启动证据；以上结论使用后续两次正常启动。

## 接入条件

- Minecraft 1.21.1、兼容的 NeoForge 21.1；本次新专用服测试具体为 21.1.234。
- 服务端与客户端使用同次构建的 VSS；显示版本固定 0.3 不表示所有历史构建兼容。当前协议 46、worldgen profile 格式 3。
- 服务端 enabled=true、enablePredictionSync=true，客户端启用预测。
- 客户端需具备对应地形/装饰 codec 和方块运行时。同步 JSON、种子与模板不会把服务器模组的 Java 代码一并同步。
- 服务端不要求图形卡或加载预测 Rust 动态库；GPU 与对应平台 native 库要求位于客户端。
- 预测缓存：单人位于存档 .vss/prediction/surface-v1；远程客户端位于自己的游戏目录 .vss/servers/<身份哈希>/prediction/surface-v1，不是专用服务器集中共享的预测缓存。
- enableChunkGeneration 是另一条真实区块生成流程，默认 true；关闭它不等于关闭预测同步。它可能继续消耗服务器算力，不能因预测在客户端就声称整个 VSS 不消耗服务器生成资源。

## 已发现的限制

1. 首次收到握手会在 HandlerThread.MAIN 上同步构建整份快照。Tectonic 组合约 1.17 秒，期间可造成主线程停顿。每个服务器实例缓存一次；数据包 reload 清缓存后会再做一次。
2. 每次符合能力条件的登录，以及部分会话设置刷新，都会直接发送完整快照。缺少快照哈希确认/按需传输机制；客户端已有磁盘 tile 不能省去这次元数据发送。此发送绕过真实列的 VSS 发送队列和带宽额度，仍会占用实际网络带宽。
3. 编码包超过 1 MiB 并不自动等于不兼容。已查 NeoForge GenericPacketSplitter 的注册和协商路径，它会对支持 NeoForge 分片通道的两端处理超大包。当前 VSS 依赖加载器分片，没有自己的渐进元数据传输。此处未实测远程玩家 TCP、代理、低带宽及丢线重连；不能将同进程 codec round trip 说成端到端联机通过。
4. 结构模板每项最多 512 KiB、总输入最多 5 MiB，超出会跳过；结构模组较多时可能缺失远景建筑，不保证全结构覆盖。注册表压缩数据最大 8 MiB、解压最大 32 MiB，单维生成器压缩最大 2 MiB、解压最大 8 MiB。
5. 单个生成器或注册表编码抛异常时，当前 builder/holder 的整份 payload 构建可能失败；没有完整的按维度独立导出失败隔离。sendWorldgenProfile 捕获 RuntimeException，记录预测停用而保留真实列会话。失败结果也没有缓存，后续登录可能重复尝试。
6. 非 NoiseBasedChunkGenerator 导出空生成器数据；无适配的子类或 TerraBlender 上下文会明确停用该维度预测。JSON 无法表达的一切运行时生成行为，不会因为能启动专用服就自动兼容。
7. 预测反映种子和生成规则，无法预测玩家建筑、挖掘、机器等运行后改变。脏列和真实列同步用于更新/覆盖这些内容。地表结构预测本身还受客户端受限世界接口约束，过去的 lightEngine/getLevel 不支持不因换成专用服而自动解决。
8. enablePredictionSync=true 会将实际世界/维度种子传给支持预测的客户端，这是当前种子预测协议的输入条件。

## 模组边界

Tectonic/Lithostitched 本次完成专用服启动和实际快照导出验证。Epic Terrain 原版 JSON/已适配算子可沿用快照路径，但本次没有启动 Epic Terrain 专用服。FreeTerraForged 当前属于 Java/Rust 混合兼容路径，需要对应客户端模组与预设注册表；不代表完整原生生成，也未在本次做专用服端到端实测。TerraBlender 区域系统及无注册后端的自定义生成器不在完整预测支持范围。

## 建议顺序

优先完成真实远程客户端加入/切维度/重连验收，然后补快照身份确认与传输限速、主线程生成快照的预热和可拆分工作、结构模板按引用或按需同步、按维度的失败隔离。
不能直接把使用实时服务端注册表与第三方 codec 的全部快照构建粗暴扔到后台线程；需先明确线程安全的数据捕获边界。
