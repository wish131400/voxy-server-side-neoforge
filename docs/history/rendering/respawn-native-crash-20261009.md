# 远处死亡后的原生崩溃分析（2026-10-09）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

分析日期：2026-10-10。

输入：`C:/Users/Administrator/Downloads/-2026-10-9_20.45.51.zip`。压缩包中的启动脚本未执行。分析依据是本次 `hs_err_pid12992.log`、`latest.log` 和 `debug.log`；启动器日志中其他时间的崩溃不并入本次结论。

## 结论与确定程度

本次客户端在渲染线程执行 Iris 注入的扩展顶点数据计算时，读取了无效原生地址，触发 `EXCEPTION_ACCESS_VIOLATION (0xc0000005)`。现有日志不支持把本次退出归因为 Java 堆耗尽、原生内存分配失败或显存耗尽。

转储中的第一个顶点偏移异常，与缓冲基址组合后能精确得到非法访问地址。这确认了发生错误的读操作及其缓冲状态，但不足以确定哪个模组、哪次缓冲生命周期变化或 JVM 行为最先造成异常。

此前源码检查发现的同维度重生、远距离传送资源峰值风险仍值得改善，但本次报告没有证明该风险触发了这次崩溃。

## 运行环境

| 项目 | 日志记录 |
| --- | --- |
| Minecraft / 加载器 | 1.21.1 / NeoForge 21.1.256 |
| VSS | 0.3.5-neoforge-1.21.1 |
| Voxy / Roxy | 0.2.16-beta / 0.4.0 |
| Iris / Sodium | 1.8.14-beta.1+mc1.21.1 / 0.8.12+mc1.21.1 |
| Accelerated Rendering | 1.0.14-1.21.1-alpha |
| ImmediatelyFast | 1.6.14+1.21.1 |
| Java | Microsoft OpenJDK 25.0.1+8-LTS |
| 相关 JVM 参数 | `-Xmx8192m`、`-XX:+UseCompactObjectHeaders`、G1、32 MiB region |
| 显卡 / 光影 | AMD Radeon RX 5600 XT / Sundial Lite v1.2.0 |
| 死亡相关模组 | Corpse 1.21.1-1.1.13、Yes Steve Model 2.6.5、Yes! Corpse Model 1.0.0-ysm2.6.5 |

版本来自 `latest.log` 的模组清单。VSS 原生地形后端记录 `rust=true`，没有本次快照拒绝后回退 Java 的证据。

## 崩溃证据

`hs_err_pid12992.log`：

- 第 4 行：`EXCEPTION_ACCESS_VIOLATION (0xc0000005)`。
- 第 9 行：`BufferBuilder.fillExtendedData(I)V` 的 C2 编译代码。
- 第 22 行：崩溃时间 20:45:45，进程运行约 32 分 55 秒。
- 第 26 行：当前线程为 `Render thread`。
- 第 34 行：读取 `0x000000000005fa0f` 失败。
- 第 70、95、97、139 行：BufferBuilder 的当前顶点指针、Iris polygon 和顶点偏移数组，以及 polygon 的基址。
- 第 107–110 行：四个顶点偏移，其中第一个为 `0xffffffff0fa4f97f`，后面三个分别为 `0x1aa20`、`0x1aa58`、`0x1aa90`。

用转储值进行 64 位地址计算：

```text
polygon.pointer      = 0x00000000f0610080
vertexOffsets[0]     = 0xffffffff0fa4f97f
UV 的 u 分量偏移      = 0x10

(pointer + vertexOffsets[0] + 0x10) mod 2^64
                     = 0x000000000005fa0f
```

本地同版本 Iris JAR 的静态字节码检查显示：`MixinBufferBuilder.fillExtendedData` 会调用 `BufferBuilderPolygonView.u`；后者按基址、顶点偏移和 16 字节偏移调用 `MemoryUtil.memGetFloat`。这与转储完全对应。本地参考 JAR 与玩家 JAR 的版本相同，未取得玩家 JAR 的哈希，不能宣称二进制已逐字节核对。

`debug.log` 第 18902–18913 行显示 Iris、Accelerated Rendering、Sodium、Cyclops Core、gb 和 Particle Core 等对同一个 BufferBuilder 应用了 mixin。转储也含 Accelerated Rendering 的字段，但当时 `acceleration=null`；这不能证明加速分支在执行，更不能单凭字段存在把它定为根因。VSS 两仓库源码没有对 BufferBuilder / ByteBufferBuilder 的直接注入。

## 内存状态

`hs_err_pid12992.log` 第 884、1742–1745 行：

| 指标 | 转储原值 | 约值 |
| --- | --- | --- |
| Java 堆上限 | 8388608 KiB | 8.00 GiB |
| Java 堆使用 | 4827930 KiB | 4.60 GiB |
| Java 堆已提交 | 6062080 KiB | 5.78 GiB |
| 系统可用物理内存 | 5008 MiB | 4.89 GiB |
| 进程物理工作集 | 7941 MiB | 7.75 GiB |
| 进程私有提交量 | 12723 MiB | 12.42 GiB |
| 系统报告的可用提交空间 | 3636 MiB | 3.55 GiB |

`hs_err`、`latest` 和 `debug` 未找到 `OutOfMemoryError`、堆耗尽暂停或原生分配失败记录。进程堆外占用值得关注，但现有数据与“本次因分配失败而退出”的说法不符。

## 死亡与重生的时间关系

`latest.log` 第 5913–5922 行：

- 20:45:29：玩家传送到约 X=4368.5、Z=2272.5。
- 20:45:44.955：服务端记录玩家被杀死，随后客户端显示死亡消息。
- 20:45:45：JVM 原生崩溃。

本次运行是集成服务器单人游戏。日志结束前未记录到回到出生点；因此只能确认崩溃紧接着死亡发生，不能确认已完成重生或已经开始出生点的大量加载。死亡时的玩家、尸体及其自定义模型绘制是应优先隔离的触发场景，但缺少完整调用链，尚不能确认具体实体。

## 旧世界引用告警

`latest.log` 第 5537–5541、5896–5902 行的 AllTheLeaks 诊断记录了一个 LocalPlayer、一个 ClientLevel，以及后一次的 127 个 LevelChunk 疑似未释放。

这支持继续检查旧世界、旧玩家引用的生命周期。其 B/C 内存差值不能直接解释为这些对象的泄漏体积，日志没有给出持有路径，不能据此归责 VSS。需要在稳定状态及 GC 后核实引用，并用堆转储确定 GC root。

## 建议的最小隔离顺序

每个对照尽量保持同一存档、位置、模型和死亡操作，逐项改变，保留各次结果：

1. 使用 64 位 Java 21，保留 8 GiB 堆配置，移除仅适用于 Java 25 的 compact object headers 参数；排除运行时版本变量。不能因为当前使用 Java 25 就认定 JVM 存在已证实的错误。
2. 在能够继续复现的环境中，只禁用 Accelerated Rendering，保留 Iris、Voxy、Roxy 和 VSS；检验直接修改顶点缓冲的优化兼容。
3. 保留 Iris 模组但关闭光影，比较死亡操作；检验是否依赖 Iris 的扩展顶点路径。
4. 若仍能复现，隔离 Yes! Corpse Model / 尸体自定义模型绘制。恢复对照后再判断具体组合，避免一次删除多个模组造成归因不清。
5. 若上述对照仍不能定位，比较关闭 VSS 预测功能的结果，并补充完整调用栈或转储，继续检查间接关联。

远近两处都应比较“死亡后等待”和“点击重生”两个阶段，以确定距离、尸体产生和实际重生哪个是触发条件。

不建议仅增加 Xmx，或通过 catch / 跳过 fillExtendedData 隐藏错误：前者不能纠正无效地址，后者可能继续产生格式不完整的顶点。

本轮仅完成日志与静态字节码分析，未复现客户端、未修改生产代码、未构建修复包。
