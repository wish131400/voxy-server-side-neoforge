# VSS 0.3.5 模组适配核对（2026-10-06）

本记录分开说明本轮实际发行包检查、已有专项验证和仅有适配代码的范围。玩家用法见 [玩家版更新说明](release-0.3.5-player-guide.md)。

本轮使用已归档的 0.3.5 产物与已提交的契约测试源码，没有重新构建工作区里的后续开发，也没有启动 Minecraft 或替换游戏文件。接口和字节码检查可以确认适配依赖的成员及调用顺序，完整整合包的启动、跑图、预设和光影效果仍需实机验收。

## 本轮重新检查的实际发行包

| 模组 | 平台与版本 | 检查内容 | 结果 |
| --- | --- | --- | --- |
| Voxy | Forge 1.20.1，`0.2.7-alpha` 移植版 | 上传、摄入、绘制 API；不透明与透明阶段的实际回调顺序 | 3 项通过 |
| Voxy | NeoForge 1.21.1，`0.2.15-beta` | 同上 | 3 项通过 |
| Xaero's World Map | NeoForge 1.21.1，`1.45.0` | 反射写入／保存成员、地形标记传递与 region 保存条件 | 2 项通过 |
| ByePregen | Forge 1.20.1，`1.1.2.4-all` | 可选 `FastBlockPredicateOptimizer.getState` 查询接口 | 1 项通过 |
| ByePregen | NeoForge 1.21.1，`1.1.2.3` | 同上 | 1 项通过 |
| Lost Cities | Forge 1.20.1，`7.3.6`、`7.4.11`、`7.4.13`、`7.5.7` | 规划锁、楼层与地表模板字段、旧／新缓存契约 | 4 个发行包通过 |
| Lost Cities | NeoForge 1.21.1，`8.2.6`、`8.3.8`、`8.4.6` | 同上 | 3 个发行包通过 |

合计 **17 项检查通过，0 失败、0 跳过**。Lost Cities 七个发行包另已逐个核对 SHA-512，与发布元数据一致；校验不重复计入上面的接口检查数。

Voxy 检查覆盖 [StrictVoxyContractTest](../src/test/java/dev/xantha/vss/compat/StrictVoxyContractTest.java) 和 [PredictionIrisHookTest](../src/test/java/dev/xantha/vss/client/prediction/PredictionIrisHookTest.java) 的实际 JAR 路径。Xaero 检查使用 [XaeroActualJarContractTest](../src/test/java/dev/xantha/vss/compat/XaeroActualJarContractTest.java)。Lost Cities 的发行包矩阵与此前规划、调色板专项验证见 [城市兼容报告](prediction-lostcities-compat-20261006.md) 和 [发行包校验清单](lostcities-release-matrix-20261006.json)。

### 本地 Forge Xaero 版本需要留意

本地 Forge 1.20.1 实例装的是 `XaerosWorldMap_1.39.12_Forge_1.20.jar`，低于当前 README 声明的 `1.40.0` 支持下限。本轮没有把它计作兼容通过，也没有将“版本低于范围”写成已复现的运行故障。

建议更新到对应 Minecraft 1.20.1 / Forge 的已适配版本。已有记录核对了 Xaero `1.40.16`、`1.45.0` 及 NeoForge／Forge 对应的 `1.46.0` 接口和保存流程，见 [网络与地图报告](lod-network-backpressure.md)。反射接口不兼容时 VSS 会停用 Xaero 桥接，基础 VSS/Voxy 远景同步仍可继续使用。

## 已有专项验证，本轮未重复运行

| 模组 | 对应平台与具体版本 | 已有验证依据 | 使用范围 |
| --- | --- | --- | --- |
| FreeTerraForged | NeoForge 1.21.1，`1.0.0` | 实际发行 JAR 的上下文、预设／噪声注册表、水文高度及 Java／JNI 数值对照 | 针对该发行版和所测输入，不代表所有整合包配置 |
| ReTerraForged | NeoForge 1.21.1，`0.0.6005` | 同一适配中的旧命名空间、种子和地形过滤规则验证 | 其他 RTF 分支需另行验证 |
| Tectonic / Lithostitched | NeoForge 1.21.1，`3.0.26-neoforge-21.1` / `1.8.0+beta6-neoforge-21.1` | 注册表、密度图及对应专项兼容资料 | 不把整个 Tectonic 3.x 系列列为已验证 |
| Epic Terrain | NeoForge 1.21.1，`epicterrain-0.1.4.jar` | 发布数据的密度缓存、基础柱、扩展高度与 Minecraft 采样对照 | 发布名为 `v0.1.4b-Beta-1.20.5~1.21.1` |
| Epic Terrain Compatible | NeoForge 1.21.1，`1.0.3+mod` | 三维缓存、插值与基础柱液体结果对照 | 不包含仅标注 Forge 的 `1.0.3b-1.21.1` |
| WWOO | Forge 1.20.1，`2.0.0` | 发行包的生物群系资源、放置阶段与地表修改检查 | 未完成所有整合包的画面对照 |
| Tectonic / Lithostitched | Forge 1.20.1，`3.0.17` / `1.4.11` | 对应 1.20.1 资源、生成图检查及兼容回退 | 不套用 NeoForge 1.21.1 的类级测试结论 |
| Biomes O' Plenty | Forge 1.20.1，`19.0.0.96` | 高草被误判为实心柱的调查与修复 | 不扩展成全部 BOP 版本和植物模型的验收 |

FreeTerraForged / ReTerraForged 的公共适配同步到了 Forge 源码，**没有对应 Forge 1.20.1 发行包的本轮实测结论**。上述 NeoForge JAR 不能跨平台使用。细节见 [FreeTerraForged 验证范围](freeterraforged-compatibility.md)。史诗地形验证资料见 [专项工具说明](../tools/prediction/EPIC_TERRAIN_2026-09-09.md)。Forge 的 WWOO、Tectonic、Lithostitched 资料位于 Forge 仓库的 `docs/wwoo-tectonic-lithostitched-1.20.1-compat-20260923.md`。

此前完整 Java 回归里，需要外部模组 JAR 的测试若未传入输入，会按条件跳过。已有专项验证与本轮接口审计分别记录，不能把这些跳过项算作最新整套模组回归通过。完整套件口径见 [0.3.5 验收记录](release-0.3.5-validation.md)。

## 渲染环境与其他可选模组

本地版本已核对为：

| 平台 | 渲染模组 | 光影模组 |
| --- | --- | --- |
| Forge 1.20.1 | Embeddium `0.3.31+mc1.20.1` | Oculus `1.8.0` |
| NeoForge 1.21.1 | Sodium `0.8.12-beta.2+mc1.21.1` | Iris `1.8.14-beta.1+mc1.21.1` |

本轮 Voxy 不透明／透明回调检查已通过；没有重新运行这些模组与各类光影包的完整游戏组合。Iris / Oculus 为可选适配，接口不适用时相关预测光影桥接会停用，不能据此保证任意 shader pack 都正常。

| 模组或功能 | 源码中的适配用途 | 本轮覆盖情况 |
| --- | --- | --- |
| Roxy | NeoForge 的可选渲染回调路径，代码目标为 `>= 0.2.0` | 本地没有实际 Roxy JAR，不列为具体版本已验证 |
| BandwidthOptimizer | 观察已有网络会话及背压，不创建会话、不改变压缩方式 | 未建立新的逐版本发行包矩阵 |
| Curios | 远处玩家附加装备显示 | 同上 |
| FTB Chunks | 强制加载票据兼容 | 同上 |
| Create / Northstar | 远处载具与火箭相关状态 | 同上 |
| BetterEnd | 可选地形适配 | 同上 |
| Blueprint / TerraBlender | 世界生成快照与生物群系区域／切片 | 保留已有适配，未验收任意混合生成配置 |
| C2ME | 生成缓存及查询兼容 | 保留已有路径，未建立完整组合矩阵 |

以上可选功能按各自接口和能力选择接入或回退，具体整合包仍需使用对应平台版本实际启动验证。地形模组各自的适配结果，也不代表可以把多个生成器任意叠加使用。

## 本轮文档修正

- 两个 README 增加玩家说明与本记录入口，并列出对应平台实际核对的 Voxy 版本。
- Forge README 的地形表改列 Forge 1.20.1 的 WWOO、Tectonic、Lithostitched；NeoForge 资料通过说明链接单独查看。
- 明确 FreeTerraForged 的发行包验证平台，并补充 Forge 本地 Xaero 低于支持下限的说明。
- 更新日志加入玩家版摘要，完整技术记录保留供查证。

本记录描述文档核对时的验证范围；同期后续源码与测试的验证另见专项报告。2026-10-08 整理提交时，将这些文档与已验证的后续修复一并同步到两个 GitHub 仓库。
