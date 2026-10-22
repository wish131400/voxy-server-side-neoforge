# Section 归属 API 1 验证记录（2026-10-22）

实现契约及 PYF 接入示例见 [ColumnCoverageApi 说明](column-coverage-api-20261022.md)。本记录针对服务端当前列结果的归属查询，不表示客户端 Voxy 已写入、已落盘或已可绘制，也没有新增独占写入授权。

## 实现范围

- 新增公共 `api.column.ColumnCoverage` 和 `ColumnCoverageApi`，保留提案的 `section`、`authoritative`、`revision`，另提供原子一致的不可变 `snapshot`。
- 原 `ColumnLodCache` 是唯一状态所有者；列发布、失效、普通/预加载淘汰和 clear 同步维护小型只读投影。无独立无限增长的世界索引，查询未知位置不创建条目。
- worker 查询不获取缓存写锁，不加载世界或 chunk，不执行过滤、压缩或磁盘读取。过滤 `UPDATE/CLEAR` 属 VSS；省略项为 UNTOUCHED；完整未过滤真实列才有整列权威性。
- 生命周期入口负责绑定、配置发布与撤销；旧接口对象在停止/重启后一直保持 UNKNOWN。正数 revision 在当前 JVM 内不重用，失效/淘汰回到零，重新发布得到更大的正数。
- `UNMANAGED` 保留给将来明确授权，本版不主动返回；缺信息、禁用或远处都不能证明其他来源可写。没有新增 listener、客户端回执或来源抢占。
- Manifest 增加 `VSS-Column-Coverage-API: 1`；列过滤 API 仍为 2，协议仍为 53，缓存格式和过滤身份算法均不变。

## 两平台验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat columnFilterTest --tests '*ColumnCoverage*Test' --no-daemon --console=plain
.\gradlew.bat verifyModules jar -PvssBuildId=column-coverage-api1-20261022-p53 --no-daemon --console=plain
```

两平台定向回归各 17 项独立用例全部通过。`verifyModules` 的完整结果：

| 任务 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | ---: | ---: |
| architectureTest | 11 | 11 |
| moduleRegressionTest | 277 | 277 |
| columnFilterTest | 124 | 126 |
| 合计执行次数 | **412** | **414** |
| 失败 / 错误 / 跳过 | **0 / 0 / 0** | **0 / 0 / 0** |

`ColumnCoverageLifecycleTest` 同时属于模块和归属套件，因此上述合计是执行次数，不能当成独立新增用例数。两端 `BUILD SUCCESSFUL`；Forge 最终 `reobfJar` 成功后才核验并归档。

17 项新增用例覆盖 API 探测不冻结过滤注册、不可变快照、错误运行时无法解绑新绑定、完整列及省略空气、维度/坐标隔离、null/未知/不完整/外来策略、同来源时间戳下的数据变化、旧结果拒收、脏失效及暖恢复、普通/字节/预加载淘汰、禁用再启用、保留旧句柄、worker 不等待 LRU 锁和并发一致性、过滤 UPDATE/CLEAR/UNTOUCHED、全更新/全省略、磁盘命中不重跑过滤、过滤异常和真实启停入口。

共享 API、能力查询、包契约、共享回归、验证任务及接口说明共 10 个文件逐字节一致。列缓存中的查询/发布逻辑一致；Forge 保留自己的预加载晋升/淘汰、Entry 字段、共享读取协调器及停服刷盘行为，生命周期回归按平台接口调用。没有把 NeoForge 的内部缓存实现覆盖到 Forge。

## 产物

两仓分别在 `lib/column-coverage-api1-20261022/` 保存新 JAR、`validation.json`、`SHA256SUMS.txt`、`api-descriptors.txt` 和 Manifest 副本。根 `lib/` 的对应 0.3.5 JAR 与归档相同。

| 平台 | JAR | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `vss-0.3.5-neoforge-1.21.1.jar` | `981b7165527796902a7c8db350d57bc4583fff674514b43a756b5053c8c11f81` |
| Forge 1.20.1 | `vss-0.3.5-forge-1.20.1.jar` | `f54fb0b1990c8f012c5098f1012a92be4cd3c5e75e3c1bf1172f4f7d6234badc` |

已检查 ZIP 完整性、Manifest 构建 ID、三个查询及 snapshot 的 `javap -s` 描述符、API 类字节码目标 65/61，以及测试框架/夹具未混入主 JAR。既有 API 2 高度接口和旧构造器兼容回归继续通过，既有未提交工作保留。

## 实测边界

本轮只进行源码、CPU 行为、依赖边界与打包验证，没有启动专服/客户端，没有实际 PYF + Voxy 联调，也没有测 FPS/TPS。客户端真实完成、最后写入者和自动填补 UNTOUCHED 的安全协调仍不是本接口的能力。产物为本地验证包，未作为 GitHub Release 发布。
