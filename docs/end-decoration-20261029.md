# VSS 0.3.6：末地装饰生成修复

构建标识：`end-decoration-20261029`

- 修复安装 Fabric/Forgified Fabric 生物群系接口时，加速采样器丢失世界种子，导致末地装饰生成报错的问题。保留气候采样优化、Rust 地形计算和 Voxy 统一渲染。
- 生物群系查询失败时，本次装饰生成会停止并交回现有重试流程，不再把半成品或空白结果存成完整缓存。原有局部放置失败和不支持特性的处理继续使用独立规则。
- 更新末地装饰缓存与成品 LOD 的标识，自动绕开旧版保存的缺失结果并重新生成。原始地形缓存及主世界、下界缓存继续复用，无须手动清空缓存。
- 验证包括实际紫颂植物放置、末地城原版模板放置、黑曜石柱与铁笼，以及失败恢复、缓存重进和植被跨 LOD 交接。

同时提供 NeoForge 1.21.1 和 Forge 1.20.1，文件使用标准版本名。关闭游戏后替换对应版本的 JAR，再进入末地检查。第一次读取旧末地区域时需要重新生成装饰，之后可复用新缓存。

本构建经过自动回归验证；交付时尚未在用户游戏中换包复测。

## 开发回归

在对应平台项目根目录使用 Java 21（NeoForge）或 Java 17（Forge）执行：

```text
./gradlew -I tools/verification/end-decoration.gradle endDecorationTest
```

若要同时执行真实 Fabric / Forgified Fabric 生物群系接口的种子与噪声回归，追加 `-PvssFabricBiomeJar=/path/to/fabric-biome-api-v1.jar`，使用对应 Minecraft 版本的独立生物群系接口 JAR。未提供时仅跳过该项外部接口测试，其余末地生成、失败恢复及缓存测试照常运行。
