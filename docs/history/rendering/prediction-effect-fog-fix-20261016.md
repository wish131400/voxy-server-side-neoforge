# 失明、黑暗与环境雾的 LOD 交接修复

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

日期：2026-10-16。两个仓库已同步修复并生成独立目录内的 JAR。没有替换游戏实例，没有启动客户端，没有提交或推送 GitHub。

## 问题与证据

用户截图中天空与近景变黑，远处树木、地形和地下切面仍然可见。源码及实际 Voxy JAR 检查确认了两条可导致远景外露的路径：

1. VSS 预测渲染的 `bindFrame` 始终使用 `normalFog`，没有服从失明、黑暗或相机所在流体的地形雾参数。即使原版地形已被近距离黑雾遮住，预测地形、植被及水面仍可显示。
2. `PredictionFogBridge.bindVoxy` 原先在失明、黑暗、流体状态下退出共享雾接管。实际 NeoForge Voxy 0.2.15 的 `MixinFogRenderer.voxy$overrideFog` 在 `getShaderFogEnd() < 10` 时提前返回，而 final blit 从 `VoxyRenderSystem.getCapturedFog*` 读取此前保存的参数。这可能保留上一帧远景雾，并将失明状态的远景继续合成。

第二项来自已安装 JAR 的 `javap -c -p` 检查。`voxy-compare/neoforge-voxy` 的源码是另一份实现，不能把那份源码中“禁用环境雾”的注释直接当成本次安装包行为。

实际检查的 Voxy：

| 平台 | JAR | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `voxy-0.2.15-beta.jar` | `802d49edc07c4e4a7af15ffd59337a82af383dfd551b67c278233098737e2f52` |
| Forge 1.20.1 | `voxy-0.2.14-alpha-51ea1a841fff1717b261d2fbd3b3f6dafd470663.jar` | `f4cdee485ab8031682673068e65ff2fbe325d7214c6cfff430449058e13a63fa` |

## 修改

- 新增 `PredictionFogCaptureMixin`，在原版 `FogRenderer.setupFog(FOG_TERRAIN)` 内，loader 的 `onFogRender` 事件返回后立即保存环境雾。注入位置早于 Voxy 的 RETURN 钩子，不依赖 Voxy 可能陈旧或扩展过的雾参数。
- NeoForge 注入 `ClientHooks.onFogRender`；Forge 注入 `ForgeHooksClient.onFogRender`。两个目标都使用各端实际 Minecraft 字节码验证过完整描述符。
- 判断效果时读取相机观察的实体，兼容旁观其他实体；同时识别水、岩浆、细雪与原版 `foggy` 标记。
- 保存本帧已计算的 start、end、shape 和 color，复用原版的失明退场、黑暗渐变与流体视距规则，不另写药水持续时间算法。
- 预测不透明地形、植被和水面使用同一套效果雾参数；Voxy final blit 同样优先使用它们。即使普通预测功能或远景雾开关关闭，已识别的效果雾也不会被它们关闭。
- 普通远景仍使用水平距离与原有 haze；效果雾使用球形或圆柱形三维距离及原版 smoothstep 过渡。保持已有高空水平雾修复，保留水面的 alpha。
- 每帧开始清空环境雾状态，正常地形雾计算也会释放旧状态。效果消失、切换相机或新一帧无环境雾时不会继续使用旧快照。
- Iris shader 分支继续由光影包的 `voxy_emitFragment` 处理。该分支不调用 VSS 的普通颜色雾函数；本次未向光影输出重复添加效果雾。

## 验证

两端均使用 LIVE 生产源码，通过 `tools/prediction/effect-fog-tests.gradle` 的独立 source set 编译，未使用历史冻结源码。GPU 为 NVIDIA GeForce RTX 4070 Ti SUPER。

| 验证 | NeoForge | Forge |
| --- | --- | --- |
| CPU 规则／注入目标／已有远景雾回归 | 6 通过 | 6 通过 |
| GPU 回归 | 3 通过 | 3 通过 |
| 失败／错误／跳过 | 0／0／0 | 0／0／0 |
| 构建 | `jar` 成功 | `reobfJar` 成功 |

GPU 回归包含：

1. 直接绘制和启用 SSBO 固定槽位的批次着色器，分别绘制真实生产地形、植被和水面。验证 5、15、48、256 格效果雾，覆盖近处、渐变区、终点、更远区域、4096 格远景及效果解除。120 组效果几何像素检查全部通过，水面 alpha 保留。
2. 负对照用原水平雾路径复现垂直方向的远景外露。修复后的三维效果雾遮住该几何；退出效果并设置回普通参数后像素恢复。
3. 模拟 Voxy 在捕获后改写全局雾参数，验证捕获值和颜色保持本帧原版结果。天空雾不会覆盖地形快照；帧重置清除旧快照。
4. 调用生产 `bindVoxy`，在预测功能关闭时验证效果雾仍优先，检查球形与圆柱形距离、原 alpha，以及 0／4700／20000 格高度处的远景遮挡。普通水平雾保持高空行为。
5. 两端各自实际 Voxy JAR 的 final-blit GLSL，经生产 patch 后编译、链接成功。
6. 现有 `PredictionDeferredWaterGpuTest` 与 `PredictionVoxyDepthConventionGpuTest` 通过：原河床／透明水面交接、前景保持及深度约定不回退。

本次是源码、字节码和独立 OpenGL 验证，未在用户整合包内施加失明药水后实测，不能据此宣称完成了游戏现场画面对照或光影包效果验证。

报告位于各仓库 `build-effect-fog-20261016/test-results/effect-fog-cpu`、`effect-fog-gpu` 和对应 HTML reports 目录。

## 复现验证命令

NeoForge：

```powershell
.\gradlew.bat --offline -I tools/prediction/effect-fog-tests.gradle effectFogTest effectFogGpuTest jar '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' --console=plain
```

Forge：

```powershell
.\gradlew.bat --offline '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/effect-fog-tests.gradle effectFogTest effectFogGpuTest reobfJar '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/0502-1/mods/voxy-0.2.14-alpha-51ea1a841fff1717b261d2fbd3b3f6dafd470663.jar' --console=plain
```

## 新产物

两个 JAR 均确认包含并注册 `PredictionFogCaptureMixin`，不包含本轮测试类。产物包含构建时各仓库现有生产改动；不含双遍包围盒离线实验测试。

| 平台 | 路径（相对各仓库） | 字节数 | SHA256 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | `lib/effect-fog-20261016/vss-0.3.5-neoforge-1.21.1.jar` | 6,185,722 | `eadf128b63f12225bcdbebef0907da9ea54600528387427328351e3241faa73d` |
| Forge 1.20.1 | `lib/effect-fog-20261016/vss-0.3.5-forge-1.20.1.jar` | 6,856,004 | `dc57cc8409f235d3736bc1c5f8e299fb0f6e26a560c38d1cc17e7b9ecc866d2d` |

用户自行替换对应平台的 VSS JAR。进游戏后可先在原截图位置施加失明，确认近景逐渐变黑且远景不外露，再清除效果确认远景恢复；同位置水面也应遵守该变化。
