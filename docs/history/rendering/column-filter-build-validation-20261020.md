# 整列过滤 API v1：构建与验证（2026-10-20）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本次完成 VSS 侧的服务端整列回调、三态、逐 section 光照、字节与清单重建、缓存策略隔离、协议传输和客户端局部覆盖保护。接口说明与接入示例见 [整列过滤接口](../../development/column-filter-api.md)。

## 最终本地构建

- 网络协议：**53**，与 release 50、旧开发版 52 不兼容，两端须使用本版。
- 构建 ID：`column-filter-api1-20261020-p53`。
- NeoForge 1.21.1 使用 Java 21；Forge 1.20.1 使用 Java 17 目标字节码，已完成 `reobfJar`。
- 两仓的 API、三态合并规则及源侧过滤流水线保持一致；平台握手与既有磁盘 schema 保持各自实现。
- Forge 旧数据构造器的空正文和自动 CRC 默认行为已保留；新增显式 CRC 入口不修复错误校验值。

两仓各执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules jar -PvssBuildId=column-filter-api1-20261020-p53 --no-daemon --console=plain
```

| 平台 | 架构检查 | 过滤／传输／缓存专项 | 模块回归 | 执行测试合计 | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | ---: | ---: | --- |
| NeoForge 1.21.1 | 11 | 64 | 268 | 343 | 0 / 0 / 0 |
| Forge 1.20.1 | 11 | 66 | 268 | 345 | 0 / 0 / 0 |

专项回归包含每列回调次数、UPDATE/CLEAR/UNTOUCHED、KEEP/SKY_ONLY/NONE、真实源空气、131 段源列筛选、异常拒绝、不可变输入、重新压缩与 CRC、Zstd 能力保留、分片乱序和混策略拒绝、空更新确认、隔离缓存、跨策略脏列失效，以及局部摄入失败后撤销旧完整列声明。

## 产物检查

已检查 JAR ZIP 完整性、加载器与 Minecraft 描述、清单协议/构建标识、生产常量协议值、API/过滤流水线类和 Java 字节码版本；未包含测试类与测试工具。两份交付包、SHA256SUMS.txt 和 validation.json 集中放在 NeoForge 仓库的 `lib/column-filter-api1-p53-20261020/`。

- `vss-0.3.5-neoforge-1.21.1-column-filter-api1-p53-20261020.jar`
  - SHA256：`da273a2c3e613202e679bbb9537daf135ac1cc30d9f5fbbec225228010b43787`
  - 构建时间：`2026-10-20T20:18:43+0800`
- `vss-0.3.5-forge-1.20.1-column-filter-api1-p53-20261020.jar`
  - SHA256：`f97892e9e5fb4447539aa3cd744b41e3873af10a993b61bf47732356d8cd4be5`
  - 构建时间：`2026-10-20T20:26:01+0800`

## 实际接入边界

本轮为本地 CPU 回归和产物验证，没有启动/替换玩家客户端，也没有提交或推送 GitHub。没有实际运行 PYF + Voxy + VSS 组合；PYF 仍需注册调用此 API 后联调。

API v1 为全玩家共享、按源列确定的稳定策略，不提供随玩家高度/距离变化的请求 scope。缓存命中不重复运行回调，改变规则必须升级 provider 版本并重启。

`coverage()` / `authoritative()` 只描述本次交付来源。VSS 不对 UNTOUCHED 段写空气、不把局部结果宣称为完整列。Voxy 同一 section 的其他后续摄入仍可替换既有结果；第三方也要避免把裁掉区域作为空气提交。此版本没有新增 Voxy 来源优先级或逐 voxel 合并。
