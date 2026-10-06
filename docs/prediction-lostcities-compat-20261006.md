# Lost Cities 建筑预测跨版本修复（2026-10-06）

本轮已完成代码修复，同步到 NeoForge 1.21.1 和 Forge 1.20.1 两个项目，并分别构建安装包。尚未替换客户端模组，也未完成换包后在 DeceasedCraft 世界中的画面对照。

## 根因

DeceasedCraft_Beta 安装的是 `lostcities-1.20-7.4.11.jar`。现场日志于 03:13:28.800 记录 `NoSuchMethodException: BuildingInfo.getDimensionLock(ResourceKey)`。VSS 的服务入口和规划读取器都强制依赖这个较新接口，旧版在获取任何楼层模板前就失败了。

旧客户端将 `active=false` 响应缓存成空区域，后续把失败区域当作“成功查询但没有城市”，不再主动请求。两点叠加导致建筑预测完全缺失。配置中的 `predictionStructures=true`，并非用户没有开启结构预测。

## 实际发行版接口矩阵

| Minecraft | Lost Cities | 规划同步契约 |
|---|---|---|
| 1.20.1 | 7.3.6 | `static synchronized getBuildingInfo`，使用 BuildingInfo 类监视器 |
| 1.20.1 | 7.4.11（当前整合包） | 类监视器 |
| 1.20.1 | 7.4.13 | 类监视器 |
| 1.20.1 | 7.5.7 | `getDimensionLock(ResourceKey)` 返回的维度监视器 |
| 1.21.1 | 8.2.6 | 类监视器 |
| 1.21.1 | 8.3.8 | 类监视器 |
| 1.21.1 | 8.4.6 | 维度监视器 |

检查的是实际发行 JAR，并非只读上游最新分支。七个 JAR 的 SHA-512 均与 Modrinth 发布元数据匹配，下载地址及校验值见 `lostcities-release-matrix-20261006.json`。此矩阵不代表已验证全部历史版本或其他 Minecraft 版本。

## 已实施

- `LostCityPlannerAccess` 按实际能力选择模组自身的锁。不存在已知同步契约时明确报错，不无锁降级。
- `LostCityHintService` 与 `LostCityPlanningReader` 共用锁适配，移除对新版接口的单一依赖。
- 旧版不调用会写共享惰性字段的 `getCompiledPalette` / `getLocalPalette`。构造查询独享的编译材质；引用材质从 Minecraft 注册表定义读取，不填充旧版 AssetRegistries 的 HashMap。材质变体也读取注册表定义，不触发全局 VARIANTS 缓存写入。保留直接方块、别名、加权方块和变体的视觉映射；战利品、刷怪及损坏元数据不用于预测外壳。
- 在访问模板时复制楼层数组和切片，并固定本次读取的地下层偏移。限制索引及建筑高度；不调用会修改原规划楼层/地下层数量的 `getBuildingBottomHeight`。
- 新版可能等待其他规划任务的区块、邻居和城市样式查询在取得快照锁前完成，避免持 memoization 监视器等待规划任务。
- 模板网格构建和外壳扫描在快照锁外执行，保留单个后台工作线程与有界队列；没有把整片城市构网搬进主线程。
- `LostCityHints` 收到失败响应后保留已有成功几何，不缓存失败空区域；未知区域进入 30 秒能力级退避后重试。成功查询的空城市区域仍可缓存，也能正常清除旧建筑。
- 保持现有网络消息格式。30 秒退避也覆盖未安装模组或维度不适用的服务端响应，避免逐区域连续探测。

## 验证结果与边界

隔离源码快照位于 `C:/Users/Administrator/.codex/tmp/vss-city-compat-20261006/{neo,forge}`，不会与其他对话共用构建输出。构建开始时 Forge 渲染接口曾处于同步中；最终使用接口已同步完整的当前源码快照，两端均成功 assemble，并非回退到旧渲染基线。

- NeoForge：37 项测试通过，0 失败、0 错误、0 跳过；安装包构建成功，类文件目标 Java 21。
- Forge：32 项测试通过，0 失败、0 错误、0 跳过；reobf/assemble 成功，类文件目标 Java 17。
- 两边均检查七个 JAR 的规划锁、模板、材质、注册表定义及 API 字节码契约。
- 真实发行版 API 接口 + 测试楼层模板经生产 `LostCityPlanningReader` 转换，验证 64 区块区域、负坐标、非空墙面/屋顶/轮廓、层高、材质和重复模板共享。NeoForge 测试加载七套接口；Java 17 的 Forge 测试加载四个 1.20.1 接口。Java 21 的 1.21 接口仍由 NeoForge 执行和两边的字节码契约检查覆盖。
- NeoForge 额外运行实际 8.2.6 / 8.3.8 的旧式 Palette / CompiledPalette 代码，验证本轮私有材质转换的直接方块、别名和加权随机映射。Forge 发行 JAR 使用 SRG 名字，未在 Mojmap 单测环境直接执行整套 7.4.11 世界生成代码。
- 缓存测试覆盖失败 29 秒不请求、30 秒重新请求、失败不擦除已有建筑、成功空区域不重试。还覆盖网络往返与模型共享、消息上限、建筑深度和网格缓存身份、地基/外墙入网格。

这些验证确认兼容契约和建筑数据转换恢复，不等同于已在每个模组版本中启动真实世界，也不等同于已验证整合包所有其他模组的建筑。现场画面、连续跑图时的额外 CPU 成本仍需换包后确认。

## 交付包

- Forge 1.20.1：`C:/Users/Administrator/Desktop/voxyserverside/lib/lostcities-compat-20261006/vss-0.3.5-forge-1.20.1.jar`
  - SHA-256：`73b931c53f9048805f08cb7c55832bc4707864514798ed93fa0ca985f73cfc2e`
- NeoForge 1.21.1：`C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/lostcities-compat-20261006/vss-0.3.5-neoforge-1.21.1.jar`
  - SHA-256：`1b9a99a7a5f6f2bb999389d6a54f6dd4c4e0d337d95111b20e1343516ee73963`

各交付目录包含校验值、构建日志和源码 SHA-256 清单。本轮相关源文件与已测试快照逐文件核对一致。安装时替换同平台旧 VSS JAR，避免 mods 内同时保留两份；重启客户端后进入世界。多人服务器的服务端也需要相应更新 VSS，单人游戏的集成服务端随客户端更新。无需升级当前 Lost Cities 7.4.11，也无需删除世界或全量 LOD 缓存。

## 复现命令

从相应项目根目录运行，目录内放入上述七个发行 JAR：

```powershell
.\gradlew.bat --offline --max-workers=2 -I tools/prediction/city-compat-tests.gradle cityCompatTest assemble '-PvssLostCitiesMatrix=C:/Users/Administrator/.codex/tmp/vss-city-compat-20261006'
```

Forge 构建所用 Gradle JVM 显式设置 `-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21`，Java 源码和测试仍使用项目的 Java 17 toolchain。
