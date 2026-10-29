# Issue #9：可选世界生成编码器故障隔离

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

日期：2026-10-10\
问题：https://github.com/wish131400/voxy-server-side-neoforge/issues/9

## 原因与修复后的行为

报告中的 Mo’ Structures `1.6.0+1.21.1` 在编码结构时，直接读取
Minecraft 的私有字段 `Structure.settings`，抛出 `IllegalAccessError`。
旧版 VSS 只在可选注册表和世界生成快照发送处捕获 `RuntimeException`，
因此错误会穿透 `vss:handshake`，也会通过会话刷新进入服务器 tick。

VSS 现在逐条编码可选世界生成注册表。遇到普通编码失败或
`LinkageError`（包括 `IllegalAccessError`）时，记录注册表、条目 ID 和原因，
跳过失败条目，保留其他已经成功或随后成功编码的条目。噪声和密度等必需
数据继续使用严格编码，不通过填空或编造输入来启用预测。

结构集合在编码后检查其结构依赖。含有未发送结构的集合整体从预测快照移除，
保留其他集合的权重与放置参数。不会通过删除一个成员、重新分配权重来预测
不同建筑。集合之间的排除区域依赖也会逐步清理，包括内联的排除集合，
避免客户端得到未绑定的结构／集合引用。

登录、切换维度和配置刷新共用的快照发送边界同时捕获
`RuntimeException | LinkageError`。即使其他必要编码器或发送钩子存在
接口链接问题，也不会从这一 VSS 路径进入服务器 tick。内存不足等 JVM
致命错误不作为普通模组不兼容吞掉。

故障隔离只影响发给客户端的预测元数据。服务器注册表与存档中的真实结构
不被修改；被跳过的结构不参与本地结构预测，其真实区块仍走原有权威列同步。

## 改动位置

- `WorldgenCodecSnapshot`：逐条可选编码、共享条目校正、结构集合依赖清理。
- `PlayerSessionManager`：所有快照发送入口共用链接错误兜底。
- NeoForge 与 Forge 两仓库同步上述通用逻辑，Forge 使用对应 DFU API。
- NeoForge 添加实际 Mo’ Structures JAR 的回归入口
  `-PvssMostructuresJar=<jar>`，测试依赖不进入生产 JAR。

## 实际发行包回归

从 Mo’ Structures 作者 GitHub release 取得：

`mostructures-neoforge-1.6.0+1.21.1.jar`

源地址：
https://github.com/frqnny/mostructures/releases/download/1.6.0%2B1.21.8/mostructures-neoforge-1.6.0%2B1.21.1.jar

SHA256：
`63b41898366ac6370c1cbff7879fa23fce3ebc53ab12a1e66188d6106836f709`

下载文件与作者发布的 SHA256 一致。发行包字节码中的
`ModStructure.lambda$static$0` 确实直接访问 `settings`。

测试加载该发行包的真实 `ModStructure` 类及其 `CODEC`，先确认编码会抛出
指向 `settings` 的 `IllegalAccessError`，再通过生产可选条目隔离逻辑执行。
结果仅遗漏该失败结构；正常沼泽小屋仍包含稳定的生物群系 ID。
经结构集合依赖清理后，把快照交给生产 `ClientWorldgenRegistries`，
正常结构与正常结构集合均成功重建。

这一测试使用实际模组编码器，不初始化整个模组入口、不改动 Minecraft 的
冻结类型注册表，也没有启动完整 ATM 10 整合包。它验证本 issue 的编码器
故障和 VSS 隔离／客户端恢复路径，不等同于整合包的实机验收。

## 验证结果

| 项目 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | --- | --- |
| 相关测试总数 | 40 | 39 |
| 通过 | 38 | 37 |
| 失败／错误 | 0／0 | 0／0 |
| 条件跳过 | 2 | 2 |
| 生产 JAR 构建 | 通过 | 通过，含 reobfJar |
| 类文件版本 | Java 21（65） | Java 17（61） |

每个平台的两项跳过均为已有的可选模组测试：
`runtimeTemplateListsRoundTripWithoutAnyCodecLookup` 与
`installedFastNoiseRegistryRoundTripsAndUsesServerSeed`。
本轮未提供它们所需的 Lithostitched／FreeTerraForged 发行包，
不将跳过计入通过；实际 Mo’ Structures 测试已执行并通过。

覆盖包括：单条访问错误／DFU 错误／运行时错误隔离、保留前后正常条目、
混合集合整体移除、传递排除依赖、内联排除集合、下一玩家仍可接收快照、
发送钩子的链接错误、致命 JVM 错误传播、稳定快照身份、客户端注册表重建、
原版结构放置以及快照分片与负载校验。

测试和构建位于独立的已提交源码快照中，叠加本 issue 的改动：

- NeoForge 基线：`1c9fd92c25db18605ba62d3ecbaf703248953f73`。
- Forge 基线：`a87dffbdd02c0d4b396f672d02fec16209d3b23e`。
- 构建目录：`C:/Users/Administrator/.codex/tmp/vss-issue9-20261010/`。
- HTML 报告：各平台的 `build/reports/tests/test/index.html`。
- 成品审计：确认两个生产类均包含链接错误保护、存在结构集合过滤方法、
  已移除旧的整组结构编码路径、测试类未打进 JAR。
- 构建与校验记录：`validation.json`。

## 修复包

版本元数据保持 0.3.5，成品单独存放于各仓库的 `lib/issue9-20261010/`。

| 平台 | 文件 | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `vss-0.3.5-neoforge-1.21.1.jar` | `1f58b78a51eeac1d061e6d285f44ed49529c05d1a62361e17c495e3f4c9ff150` |
| Forge 1.20.1 | `vss-0.3.5-forge-1.20.1.jar` | `91217069919b0163250f3231ef3f095c655a81ac5ddec2e667b4413b4d3585ed` |

每个成品旁都有 `.sha256` 文件。issue #9 的 ATM 10 / Minecraft 1.21.1
环境使用 NeoForge 成品，替换客户端和服务端的旧 VSS 包后自行复测。

本轮未安装游戏文件，也未向 GitHub 提交、推送、评论或关闭 issue。
issue 提及的新 Voxy 分支 `bakeQueue` 警告属于另一项可选水体模型适配，
不包含在本次 Mo’ Structures 编码器故障隔离中。
