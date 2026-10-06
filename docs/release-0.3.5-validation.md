# 0.3.5 提交验收记录（2026-10-06）

本记录整理 0.3.4 之后的累计改动，以及对本次提交代码快照运行的验证。Forge 1.20.1 与 NeoForge 1.21.1 分别构建和检查，保留 Minecraft、加载器、网络与缓存接口差异。功能更新见 [更新日志](../CHANGELOG.md)，使用方法见 [README](../README.md)。

本次验收结束后，另一个进行中的开发任务开始修改 10 GiB 磁盘缓存、最长 7 天保留、单人预生成直达和容量提示。该任务的新增工作区改动继续保留，另行验证和提交；本记录的测试统计与 JAR 指纹对应下列四批提交，不覆盖这些后续改动。

## 版本与升级

| 平台 | 仓库 | 0.3.4 基线 | 0.3.4 源码协议 | 当前版本 | 当前协议 |
| --- | --- | --- | ---: | --- | ---: |
| NeoForge / Minecraft 1.21.1 | [voxy-server-side-neoforge](https://github.com/wish131400/voxy-server-side-neoforge) | [bbc4f3d](https://github.com/wish131400/voxy-server-side-neoforge/commit/bbc4f3d) | 48 | 0.3.5-neoforge-1.21.1 | 50 |
| Forge / Minecraft 1.20.1 | [voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge) | [da9725a](https://github.com/wish131400/voxy-server-side-forge/commit/da9725a) | 47 | 0.3.5-forge-1.20.1 | 49 |

服务端和客户端应一起升级，使用对应平台、同一次构建的 JAR。城市规划元数据和列排队确认已改变，旧协议以及此前同名 0.3.5 测试包不能按版本字符串混用。预测缓存校验和迁移按记录执行，无需清空世界或 Voxy 缓存。

## 分批提交范围

| 批次 | 范围 | NeoForge | Forge |
| --- | --- | --- | --- |
| 1 | Rust 密度查询复用、地形兼容、随包原生库及许可证 | [c688156](https://github.com/wish131400/voxy-server-side-neoforge/commit/c68815695493d9fc8c8a681d70c31a55e074924d) | [2c96f31](https://github.com/wish131400/voxy-server-side-forge/commit/2c96f319f453d773fe049e55a02b7c848737b103) |
| 2 | 预测渲染、归属与交接、CPU 准入、缓存与显存、城市适配、LOD 网络同步、区域预生成运行时及回归测试 | [da91318](https://github.com/wish131400/voxy-server-side-neoforge/commit/da913183eebbcdb8915376f05e845822e242f15d) | [d1e0d5d](https://github.com/wish131400/voxy-server-side-forge/commit/d1e0d5dbdd8984023181190d5db917a80450b084) |
| 3 | 指令汉化、主题帮助、中文别名、chunky 入口与翻译／命令树测试 | [95b2fa1](https://github.com/wish131400/voxy-server-side-neoforge/commit/95b2fa165d80fd7cf391d2c37d0d5e80539464d0) | [1dc342f](https://github.com/wish131400/voxy-server-side-forge/commit/1dc342f3bd9668b1c604722b68ebed674a37ac64) |
| 4 | 0.3.5 版本、累计更新日志、README、专项报告、验证工具、忽略规则与本验收记录 | 与本文件同批提交 | 与本文件同批提交 |

相互依赖的预测实现集中在第二批；第三批开放对应命令。第一批随包原生库在后续代码之前提交，整组提交后的最终状态完成构建与验证。

## Java 回归与打包

| 平台 | 完整套件总数 | 通过 | 条件跳过 | 失败 | 错误 | 构建 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| NeoForge | 1,561 | 1,476 | 85 | 0 | 0 | `build` 成功 |
| Forge | 1,565 | 1,479 | 86 | 0 | 0 | `build` 成功，完成 `reobfJar` |

完整套件提供了随包 Windows 原生库，并使用 2 GiB 测试堆。条件跳过包括默认关闭的 GPU 测试、需显式启用的性能基准，以及需要发布版模组 JAR、运行时快照、回放语料或其他外部输入的兼容测试。跳过项未计入通过数；本次不将这些兼容场景列为重新验收通过。

完整回归期间修正了旧测试夹具与当前缓存资源身份、独立恢复线程池、显示 LOD 及诊断字段的差异，保持相应语义断言。生产代码补充 `LodRequestManager.isIntegratedServer()` 的空客户端判断，避免初始化或测试环境的空指针。NeoForge 最后两处夹具更新后，另行运行 `PredictionDiskIntegrationTest`、`PredictionMemoryLifecycleTest` 和 `jar`，均通过；上表保留完整套件的统计，不将重复测试相加。

在各仓库根目录可复现完整套件与构建；`--offline` 需要已有依赖缓存：

```powershell
.\gradlew.bat build -I tools/prediction/regression-tests.gradle `
  "-PvssTestNativeLibrary=$((Get-Location).Path)/src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll" `
  --offline --no-daemon --console=plain
```

## 实际 OpenGL 回归

两端分别在真实 OpenGL 上下文中运行以下 11 项测试，每端 **11 通过、0 跳过、0 失败、0 错误**。使用独立测试任务和报告目录，避免覆盖完整 Java 套件的 XML 结果。

| 测试类 | 检查范围 |
| --- | --- |
| `PredictionProductionBatchGpuTest` | 生产批次与静态提交数据复用 |
| `PredictionRenderTargetGpuTest` | 深度顺序、流体阶段重置和渲染目标调整 |
| `PredictionSegmentedMeshGpuTest` | 分段网格的完整上传与绘制 |
| `PredictionCompactGpuTest` | 紧凑格式与通用格式的 GPU 行为 |
| `PredictionCompressedUploadGpuTest` | 压缩网格恢复后的上传 |
| `PredictionDepthSeedGpuTest` | 深度种子与预测遮挡输入 |
| `PredictionRawStateHandoffGpuTest` | 外部 OpenGL 状态交接 |
| `PredictionOcclusionGpuTest` | Hi-Z 与剔除结果 |
| `PredictionVoxyBoundaryGpuTest` | Voxy 替代边界遮罩 |
| `PredictionDisplayLodGpuTest` | 显示 LOD 与几何表示 |
| `PredictionUploadStagingGpuTest` | CPU 数据驱逐后的冷上传、压缩／非压缩及共享／独立存储 |

这是一组针对本次改动的 GPU 回归，不等于所有 GPU 测试、所有显卡或光影组合均通过。完整套件内其他默认关闭的 GPU 测试仍按条件跳过记录。

## Rust 回归

两端分别执行以下 release 测试：

```powershell
cargo test --release --offline -j 2 `
  --manifest-path tools/rust/vss-native-core/Cargo.toml `
  --lib --test biome_colors --test display_surface
```

每端 **55 通过、4 忽略、0 失败**：库测试 36 通过／4 忽略，生物群系颜色 5 通过，显示地表 14 通过。该结果限定于上述库及两个集成测试目标，不代表本次运行了全部 Rust 集成测试或所有平台的原生二进制。

## JAR 审计

最终产物保存在各自仓库的本机 `lib/` 中；该目录由 Git 忽略。

| 平台 | 文件名 | 字节数 | Java class major | 随包原生平台数 |
| --- | --- | ---: | ---: | ---: |
| NeoForge | `vss-0.3.5-neoforge-1.21.1.jar` | 5,727,843 | 65（Java 21） | 5 |
| Forge | `vss-0.3.5-forge-1.20.1.jar` | 6,401,389 | 61（Java 17） | 5 |

SHA256：

```text
NeoForge: 76eaee7d6f6e54d4d900efab6ac575509602f5a55839b0539783a32aef0e9a5f
Forge:    0e90be69ed867bad375b25cd9cc6e6c5dfc6ebe8257cb2b5397886dba4d3289e
```

检查了 ZIP 可读性、重复条目、关键生产类、测试类未打入、五个平台原生资源与源码资源一致，以及 chunky 已删除的并发策略类／文案和 60 秒进度说明。Windows 原生库参加了 Java／Rust 验证；其余平台的资源一致性不表示本次已在那些系统运行。

## 测量边界与历史记录

专项报告保留各自日期、采样版本、场景与限制。更新日志按最终实现归纳，历史采样数据不能直接视为当前产物的 FPS 或显存实测结果。本次没有启动 Minecraft、替换实例 JAR，或运行同位置进档、跑图与转身的 FPS 对照；不承诺固定帧数提升。

本次发布整理以普通 Git 提交和推送完成。JAR 保留本机，GitHub Release、标签和客户端安装由后续发布或测试安排处理。
