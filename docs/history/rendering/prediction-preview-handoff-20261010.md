# 预览植被在 Voxy 区域残留修复（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本次同步 Forge 1.20.1 和 NeoForge 1.21.1，模组版本保持 0.3.5。目标是清除真实 LOD 已接管区域里的方盒预览小树和预览草，同时保留完整预测装饰的边界回退。

## 原因

简化植被按生物群系和坐标生成代表树，不能证明真实世界同一位置存在树。原实现将它和完整生成的树草、城市模板统一标记为 feature；CPU Voxy 列覆盖过滤跳过所有 feature，shader 为防止提前消失也保留其正常深度回退。真实地面位于错误预览树后方时，深度测试不会删除这棵树。

## 实现

- 在工作线程记录每个 cell 的简化植被起点，给代表树、代表草和代表菌类单独增加 PREVIEW 标记。完整生成植被、城市模板和普通地形不使用这个标记。
- PREVIEW 位保存在 canonical word 10 的第 26 位；与 FEATURE、第 25 位显示代理标记、颜色和高四位光照不冲突。三角形转换、分段合并、GPU dictionary、rectangle encoding、压缩、finished mesh、同 footprint 装饰继承保留标记。预览不参与会跨源 cell 的贪心合并或显示几何位移。
- 工作线程将同一代表植被源 cell 的所有树干、树冠和朝向面汇成完整 bounds；CPU 提交使用同一完整 footprint/高度证明，避免只移除树干或部分树冠。一个 cell 中的多种代表物共享较保守的范围。
- 只有 footprint 全部处于 settled 索引内部（255），完整高度已有上传的 Voxy node 或祖先 node，并且完整包围盒在当前 Voxy 三维交接距离内部时，提交过滤才清掉 PREVIEW。单独的网络确认、数据库数据或两秒等待都不能替代上传证明。显式上传空几何也能接管，从而清掉真实空地上的错误代表树。
- 上传证明来自已有 StrictLodVisibility/StrictVoxyNodeIndex 和上传回调维护的 metadata。它表示区域驻留/接管条件，不表示本帧每一片真实叶子已经画出；只用于清除不准确的临时代表物，不用于删除完整预测装饰。
- 未上传、索引边缘（128）、coverage 缺口、高度未覆盖、距离外和错误维度保留预览。覆盖撤销、节点撤回和 renderer reset 会使相关 proof 失效，保留的预览几何可在当前提交恢复。
- 上传区域 revision 只使相交的缓存条目重算；远处无关上传与区域内部的相机移动复用 proof。正常、MDI 和 Iris 共用同一提交过滤，不需要额外渲染层或逐帧网格重建。原 shader 对完整 feature 的深度回退继续有效。

## 缓存和使用

finished mesh 的输入身份版本从 12 升到 13。缺少 PREVIEW 标记的旧 finished mesh 不能沿用，会按需重建。基础地形采样、placement 缓存、存档和 Voxy 数据库保留，不需要手动清缓存。首次进入旧缓存区域可能发生一次预测网格重建。

新包使用独立目录 lib/preview-handoff-20261010，保持原 CPU-only 调度修复和各项现有设置。未替换客户端、启动游戏或推送 GitHub。

## 验证

执行命令（两个仓库相同）：

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21' --project-cache-dir .gradle-preview-handoff-20261010 -I tools/prediction/feature-handoff-tests.gradle -I tools/prediction/preview-handoff-tests.gradle previewHandoffTest previewHandoffGpuTest jar --console=plain
```

CPU 回归包含真实空地接管、延迟上传、索引边缘、树冠邻列和完整高度、范围与维度、撤销/reset、局部 revision 缓存、真实上传镜像、字典/矩形 GPU 编码、显示副本、分段/压缩/磁盘恢复/装饰继承，以及既有完整装饰交接和锁 30 FPS 的 CPU 调度回归。GPU 回归在 NVIDIA GeForce RTX 4070 Ti SUPER 上运行生产 shader，检查普通、MDI、Iris 标准深度和 reversed-Z 的预览接管与完整植被回退，并检查压缩、显示 LOD 和分段上传。

最终计数、JAR SHA256、原生库核验与构建字节码信息保存在对应产物目录的 build-verification.json。CPU 的外部 corpus benchmark 若无输入则跳过，单独记录，不计为通过。自动化回归不等同于截图原世界的现场复测；本轮不提供 FPS 改善数值。

最终结果：

| 版本 | CPU 通过／跳过／失败 | GPU 通过／跳过／失败 | 打包 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | 171 / 1 / 0 | 5 / 0 / 0 | Java 21，855 个生产 class 与编译输出逐字节一致 |
| Forge 1.20.1 | 171 / 1 / 0 | 5 / 0 / 0 | Java 17 目标字节码，reobfJar 完成 |

CPU 跳过项为 PredictionGpuEncodingTest.realCorpusRoundTripAndEncodingCost，因未提供外部网格 corpus；七项新的预览交接回归全部通过。五项 GPU 回归均在真实 OpenGL 上下文运行，两端没有 GPU 跳过项。两个包均包含五项与上一 CPU-only 包相同的原生库，并已核验包内 PREVIEW 常量和 identity version。
