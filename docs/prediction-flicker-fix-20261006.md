# 预测 LOD 闪烁修复 — 2026-10-06

## 现象和运行版本

用户视频 `C:/Users/Administrator/Videos/2026-10-06 10-14-41.mkv` 长 22.9 秒、60 FPS。12.0～12.3 秒相机几乎不变，远景水面与黑绿色地形反复切换。逐帧证据保存于 `C:/Users/Administrator/.codex/tmp/vss-flicker-20261006/`。

实际运行 DeceasedCraft_Beta / Forge 1.20.1，客户端 PID 67212（本轮取样时）。mods 中的 VSS SHA-256 为 `546B1FFAD21F3EE1EBECE9931AD938920C8949B8BA03F2488A982EFC4D729C35`，与上一轮加载优化交付包一致。日志已经出现 `rust=true`，因此这次不应回退 Rust 后端来处理渲染错误。

本轮使用一次性诊断 agent 在客户端执行线程读取现有批次的 CPU 缓存记录；未重定义生产类、改游戏设置、修改世界或替换运行中安装包。读取不含认证信息。诊断 agent 不包含于交付 JAR。

## 根因 1：缓存中途失效时的瓦片记录索引错误

`PredictionIndirectBatch.switchToRebuildCurrent()` 会重新编码当前批次已经匹配的前缀，但 `encodeItem()` 使用共享的 `tiles` 计数写入 indirect command 的 `baseInstance`。

例如已匹配 59 个瓦片时发生失效，前缀本应依次指向记录 0～58，却全部指向记录 59。瓦片记录包含位置、terrain payload 偏移、覆盖 mask 和材质等数据，错误索引会让前缀命令使用其他瓦片的数据。列表在末尾缩短时，错误索引还可能等于记录数，超出有效范围。

现场读取实际观察到：水面批次 399 个记录、437 条命令，第一个命令从 `-1 -> 59` 跳号；对应记录本应从 0 开始。这是运行版本中的具体错误，不只是视频推测。

修复将记录索引作为 `encodeItem` 显式参数：普通追加传 `tiles`，重建前缀传当前 `i`，不修改当前批次的计数器。

## 根因 2：生产水面入口遗漏计划初始化

生产渲染器使用 `begin(program, false, residencyRevision, ownershipRevision)`，这个四参数入口原先只重置 buffer / page / counters，没有执行 `beginPlan()`，并且没有重置 `replaying`，会继承前一个 opaque 通道的回放状态。

旧测试多使用二参数入口，该入口额外调用了 `beginPlan()`，没有覆盖生产路径。水面在 opaque 不断失效时会持续追加历史计划；前一通道的回放状态还可能错误影响水面的失效与重建。

修复把通用帧重置拆为私有 `beginFrame()`，所有普通公开入口保证调用一次 `beginPlan()`；Hi-Z 入口仍在完成 culling 初始化后调用一次计划初始化。帧起点明确重置 `replaying`。水面和 opaque 保持独立缓存，未关闭 MDI、Hi-Z、预测或批次复用。

## 修复前失败 / 修复后通过

新增的生产 GPU 回归在未修复源码上复现两个失败：

1. 中途重排导致颜色数组第 32904 字节由预期 57 变成 0，即应该存在的像素消失。
2. opaque 每帧失效、水面成员稳定，连续 120 帧后水面只有 1 个 GPU slot，却积累 121 个缓存批次。

失败 XML 与原始源码分别保存于证据目录的 `gpu-before-results/` 和 `PredictionIndirectBatch-before.java`。回归修复后覆盖：

- opaque / water 的中途重排、末尾缩短、起点变化、恢复原成员和下一帧重放。
- 与无历史缓存的重新编码结果逐像素比较。
- 从 GPU indirect buffer 回读命令，验证 record 索引从 0 开始、按瓦片顺序推进、没有越界。
- 实际四参数水面入口连续 120 帧跨通道切换，缓存批次数与 slot 数一致，稳定水面无需重新上传。
- 原有直接绘制/MDI/Hi-Z、Voxy 完整覆盖零提交、覆盖撤回恢复、水面、Iris 深度和状态恢复、分段 mesh 回归。

NeoForge 和 Forge 的 `flickerGpuTest` 各 4 项，0 失败、0 错误、0 跳过。这是实际 OpenGL 执行，不是条件跳过。

## 同时修正整合包空屋顶兼容

最新日志另有 `Unsupported Lost Cities part dimensions`。读取已加载的 8131 个 BuildingPart，发现 49 个 `16×16×0` 的空屋顶占位模板，例如 `deceasedcraft:multi_filmworkstower_1/multi_filmworkstower_1_top`。这些是合法的空几何。

上一轮适配将 `slices.length == 0` 当成非法尺寸，导致整个 8×8 区块区域查询失败。现改为空模板返回无几何，其余楼层与区域继续构建，保持楼层索引和有效尺寸上限。生产读取回归新增空屋顶场景，检查底下两层仍有墙面/屋顶/轮廓且区域不失败。

此错误独立于本轮批次闪烁。兼容套件 NeoForge 37 项、Forge 32 项全部通过，0 失败、0 跳过，包括七发行版契约和空屋顶转换。

## 构建与交付

在独立的 `.codex/tmp/vss-flicker-20261006/{neo,forge}` 构建目录验证，未与其他对话共用 build 目录。两端 assemble 成功；Forge 包完成 reobf。源码同步到两个真实项目，任务相关源码与已测试快照逐文件一致。

JAR 与上一轮 10:02 的加载优化包做字节对照，仅 `PredictionIndirectBatch` 及其内部类和 `LostCityPlanningReader` 的生产 class 改变，其余生产 class 和 Windows DLL 完全一致。保留原有加载优化、CPU 预算、Rust 颜色修复与预生成门控。版本号保持 0.3.5 及原 loader/MC 后缀。

| 平台 | 固定交付目录 | SHA-256 |
|---|---|---|
| Forge 1.20.1 | `C:/Users/Administrator/Desktop/voxyserverside/lib/flicker-fix-20261006/vss-0.3.5-forge-1.20.1.jar` | `0d3e8ec6d31b37a5fc9d587d5e0afdea78e0991ec2bf4482a52cb94f324fbce1` |
| NeoForge 1.21.1 | `C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/flicker-fix-20261006/vss-0.3.5-neoforge-1.21.1.jar` | `930e286d935005eaceb306e9b9945abe0ed0c38d881faf60b68ceb6744a541a0` |

同时更新各项目 `lib/` 根目录同名 JAR，旧包保存在本轮证据目录 `before-packages/`。校验清单及源码 SHA-256 见 `prediction-flicker-artifacts-20261006.json`。

客户端仍运行旧包，本轮未进行换包后的同地点实景对照；不能把 GPU 回归通过说成当前客户端已经不闪。退出游戏后替换对应 VSS 并重启生效，mods 中只保留一份。无需删除世界或全量 LOD 缓存。多人独立服务端也更新对应 VSS 才能应用空屋顶的服务端读取修复。

复现 GPU 回归：

```powershell
.\gradlew.bat --offline --max-workers=2 -I tools/prediction/flicker-tests.gradle flickerGpuTest
```

建筑兼容回归：

```powershell
.\gradlew.bat --offline --max-workers=2 -I tools/prediction/city-compat-tests.gradle cityCompatTest '-PvssLostCitiesMatrix=C:/Users/Administrator/.codex/tmp/vss-city-compat-20261006'
```
