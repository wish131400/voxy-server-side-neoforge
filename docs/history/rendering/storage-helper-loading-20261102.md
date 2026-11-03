# 修复存储接管辅助类导致的启动崩溃

版本：0.3.7。Java 构建标识：`20261102-storage-helper-loading`。

错误报告 `错误报告-2026-11-2_15.21.18.zip` 显示，游戏在初始化 Voxy 配置时，`StrictVoxyIngestPlugin.postApply` 普通调用 `PredictionVoxyStoragePlugin`，触发 `IllegalClassLoadError`。该辅助类位于配置保留的 `dev.xantha.vss.mixin.*` 包中，却没有作为正式配置插件注册，Mixin 拒绝按普通类加载它。

这是上一轮新增存储接管代码的类位置错误。报告中的 Connector 提示是运行环境信息，当前致命异常直接来自 VSS 调用链。

## 改动

- 将存储字节码转换工具移至普通兼容包 `dev.xantha.vss.compat.transform.VoxyStorageTransformer`。
- 移除辅助类不需要的 `IMixinConfigPlugin` 实现和生命周期方法；由正式配置注册的 `StrictVoxyIngestPlugin` 调用转换工具。
- 保留真实区段接管、空气覆盖、保存记录和恢复校验的现有实现。
- 成品检查验证新转换工具存在，并拒绝包含旧 `mixin/voxy/PredictionVoxyStoragePlugin.class` 的包，防止增量构建残留。

## 回归与边界

上一轮存储 Mixin 测试把辅助类直接注册为测试配置插件，改变了它在正式运行时的加载身份；测试类加载器还绕过了普通类的 Mixin 包限制。因此此前测试证明了存储转换行为，未覆盖本次启动失败。

本次测试读取实际构建输出中的正式 `vss.strict.mixins.json`，通过正式插件应用存储与身份钩子，再将正式插件调用的辅助类交给真实 Sponge transformer 检查普通类加载。原代码在这条测试中复现相同 `IllegalClassLoadError`；移动辅助类后通过。Fabric 测试还保留各版本原有的 MixinExtras 启动适配。

NeoForge、Forge 和 Fabric 代表版本运行存储、原生写入与正式配置加载检查，实际依赖矩阵覆盖 13 个 NeoForge / Forge Voxy 包和 5 个较新 Fabric Voxy 包。所有十个维护目标重新构建，最终包检查核对类路径、功能配置、构建标识与源码快照。

本次只调整 Java 辅助类加载位置，不修改原生库及其元数据；原生库保留已验证的 `20261102-climate-height-authority` 标识。无需清理预测缓存或存档。没有在用户的原整合包中执行完整游戏启动，正式整合包复测仍由实际运行确认。
