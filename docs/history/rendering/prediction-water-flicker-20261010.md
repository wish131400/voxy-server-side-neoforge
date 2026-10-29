# 海面闪烁：整通道缓存漏绘独立缓冲海底（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮已经定位并修复一个可以用现场对照和生产 GPU 回归重复验证的漏绘错误：**不透明通道同时包含共享页网格和独立缓冲网格时，缓存只保存了共享页的间接命令，却被当成完整通道重放。独立缓冲中的海底在缓存复用帧消失，透明水面随后混合到天空背景，形成大片蓝色斑块和闪烁。**

修复已同步到 NeoForge 1.21.1 和 Forge 1.20.1 两个仓库，完整相关回归、打包和 Forge 重映射均成功。当前游戏仍运行旧包，本报告中的现场截图属于旧包的短时缓存诊断对照；新包尚未进行用户存档中的实际游戏复测。

## 现场对象与诊断范围

- 客户端 PID：33012，Java 25。
- 实例：`F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249`。
- 已安装 VSS：`mods/vss-0.3.5-neoforge-1.21.1.jar`。
- 已安装包 SHA256：`1a7c3f03b10795d7fa7e5a4a1e804d48b8e91df7de68370a9c4d552a5a85d74d`，为上一轮内存修复包。
- Voxy：0.2.15-beta，经 Connector 加载，使用 `NormalRenderPipeline`。
- 有效取样处于游戏内，`paused=false`。固定相机约 `(1040.458, 310.303, 393.460)`，朝向约 `(-0.9778, -0.1960, -0.07378)`。
- 游戏视口 3840×2066；缓存对照读回相同位置的 960×540 中央区域。

本轮读取绘制阶段的颜色和深度、相关 CPU 网格和部分 GPU 数据，并进行了有时限的 Hi-Z 和整通道缓存对照。诊断已结束，没有替换实例 JAR。诊断过程中世界从白天进入夜晚，因此没有跨时间比较画面亮度或帧率，也没有声称测得新包的 FPS 改善。

## 根因与触发条件

`PredictionRenderer.drawNormalPass()` 对不透明几何按距离桶和共享页分组：

1. 共享页内的网格经过 `PredictionIndirectBatch.add()`，记录进 MDI 提交计划。
2. `bucket.fallback` 内没有共享页的网格直接经过 `fallback()` 和 `drawSingle()`，不调用 `add()`。
3. 旧 `fallback()` 只更新计数，没有撤销完整通道的缓存 token。
4. `end()` 因而把只包含 MDI 部分的计划标成完整通道可重放。
5. 下一帧 `replayPass()` 命中后跳过常规遍历，只提交共享页；独立缓冲中的海底被漏画。
6. 上传、归属或网格变化使计划重建时，独立缓冲又正常绘制。两种帧之间切换，水面背景随之改变。

`add()` 遇到没有共享页的网格时原本已有失效保护，但分组后的独立缓冲路径绕开了它。

现场不透明共享池已经达到 **32 页、2 GiB 的自身上限**，水体池为 2 页、128 MiB。共享池无法继续容纳时使用独立缓冲是正常回退；这个上限不表示整张显卡显存已满。此次错误是缓存没有涵盖这条正常绘制路径。

## 因果验证

先对同一帧的水面前后附件进行读回。问题区域在水面绘制前已经是天空背景，预测深度为 0、主深度为 1；水面确实在后续阶段提交并参与混合。水面排序 bridge 正常执行。

随后取出缺口射线关联的 20 个网格，其中 14 个使用独立缓冲。相关海底面在 CPU 数据和 GPU 数据中存在，网格处于可见且选中的状态，归属掩码允许其绘制。该视角的自适应剔除策略已选择直绘；临时禁用 Hi-Z 也没有消除缺口。

最后仅短时清空旧客户端 `opaquePlan` / `culledPlan` 的 `wholeInput`，强制回到常规逐项提交。保留同样的几何、着色器、归属和 GPU 数据，取得以下结果：

| 同一个 960×540 取样区 | 结果 |
| --- | ---: |
| 原缓存与临时撤销整通道重放之间改变的 RGBA 像素 | 124,817 |
| 原缓存下仅有水面混合的 alpha=180 像素 | 86,096 |
| 临时撤销整通道重放后的 alpha=180 像素 | 6,915 |
| 从上述缺底背景恢复的像素 | **79,181** |
| 恢复旧缓存后，与最初基线不同的像素 | **0** |

alpha 统计结合前面的深度和网格数据用于验证漏底恢复；它不是 FPS、GPU 耗时或所有海面像素的统计。恢复旧缓存后，原图逐像素重现，使该缓存错误与缺口之间的关系得到直接验证。

![同一旧客户端的缓存诊断对照；非新 JAR 实机复测](../../images/prediction-water-flicker-cache-20261010.png)

## 本轮代码修改

两仓库的生产修改均位于 `src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java`：

- `fallback()` 撤销本帧 `wholeInput`，使包含直接绘制的混合通道不能被只有 MDI 内容的计划替代。
- `replayPass()` 明确要求 `plan.wholeInput != null`，把 null 作为无完整通道证明的状态。
- 保留已有效的逐项／逐页计划。共享页仍可直接复用静态命令和包围盒；完全由共享页组成的通道仍可使用整通道重放。

本轮没有在 `fallback()` 中清空整个逐项计划，避免把正确性修复变成每帧重新编码和上传共享页命令。新增测试同时验证了这项性能约束。

## 失败到通过的 GPU 回归

`PredictionProductionBatchGpuTest` 新增真实分组回退路径：共享页经过 `add()`，独立缓冲绕开 `add()` 并直接绘制。对同一场景分别使用普通提交和当前帧 Hi-Z，比较完整颜色及深度，并连续复用 12 帧。

旧生产代码配合新回归，在第一帧缓存复用即失败：

```text
grouped standalone geometry must survive every cached frame; HiZ=false frame=0
array contents differ at index [128], expected: <111> but was: <0>
```

应用修复后，两种模式的连续缓存帧颜色和深度一致。混合通道不再命中不完整的整通道计划，逐项缓存命中继续增长，稳定帧 `uploadedBatches=0`。原有全共享页重放、透明排序、归属、morph、上传和渲染状态回归也通过。

## 构建与产物

| 平台 | CPU 回归 | GPU 回归 | 构建结果 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 / JDK 21 | 113 通过，1 条件跳过，0 失败 | 13 通过，0 跳过，0 失败 | jar 成功，1 分 34 秒 |
| Forge 1.20.1 / JDK 17 | 110 通过，1 条件跳过，0 失败 | 12 通过，1 条件跳过，0 失败 | jar + reobfJar 成功，1 分 57 秒 |

CPU 条件跳过为没有传入独立真实网格 corpus 的可选测试，不计为通过。Forge 的 GPU 条件跳过为没有提供该平台实际 Voxy JAR 的 final-blit 测试；NeoForge 使用当前安装的 Voxy 0.2.15-beta，该项通过。

构建使用 `feature-handoff-tests.gradle`、`forest-optimization-tests.gradle` 和本轮新增的 `water-flicker-build.gradle`，将构建输出及成品隔离到 `build-water-flicker-20261010` / `lib/water-flicker-20261010`。测试结果按本轮 XML 汇总。

两包已验证 ZIP CRC、无重复 ZIP 条目、没有打入测试类、五个平台 native 资源均与源码资源一致。NeoForge 的目标类与本轮编译结果完全一致；Forge 成品与 `reobfJar/output.jar` 完全一致。另用成品反汇编确认两处修复均已包含。两个仓库的生产文件、GPU 回归和隔离构建脚本在规范化换行后内容一致。

| 平台 | 文件 | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | [vss-0.3.5-neoforge-1.21.1.jar](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/water-flicker-20261010/vss-0.3.5-neoforge-1.21.1.jar) | `aa9d1288bac45061c250c3ce97ee096060035d49bccb98289b15e3c0db302eec` |
| Forge 1.20.1 | [vss-0.3.5-forge-1.20.1.jar](C:/Users/Administrator/Desktop/voxyserverside/lib/water-flicker-20261010/vss-0.3.5-forge-1.20.1.jar) | `ec86f23cbc7a9f4db7151bdcdc06e5dcfb1e10f8ba30d85194fbb9b027ee760d` |

两个产物目录均附 `SHA256SUMS.txt`。版本号仍为 0.3.5，应依据本轮目录和校验值识别新包；本轮包保留当前工作区的前序森林优化和内存修复。

## 证据与剩余验证

完整诊断材料保存在 `C:/Users/Administrator/.codex/tmp/vss-water-flicker-20261010`，包含旧代码失败日志、修复后 GPU 回归、双平台构建日志、颜色／深度读回、关联网格和成品反汇编。本报告相邻 JSON 保存本轮测试计数、包校验、现场参数和像素对照。

此次已完成源码修复、旧客户端现场因果对照和真实 OpenGL 回归。新 JAR 尚未替换到用户实例，没有新包在该存档持续移动的闪烁复测或 FPS 对比；目前结论限于已重复验证的缓存漏绘错误。用户自行退出游戏后替换所用平台的旧 JAR，再验证原海面视角及跑图过程。本轮没有提交或推送 GitHub。
