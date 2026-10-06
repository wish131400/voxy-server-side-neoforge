# BOP 高草纯色柱修复（2026-10-06）

## 已确认原因

用户跑近后确认截图中的绿色柱状物是草。本次只读采样当前客户端 PID 78908：材质表 rows=254、ready=true、broken=false；biomesoplenty:high_grass 多种 age 状态与 high_grass_plant 的 0–4 面均为 row=255（FLAT）。采样记录保存在 C:/Users/Administrator/.codex/tmp/vss-grass-cutout-20261006/materials.txt。

检查实际安装的 BiomesOPlenty-forge-1.20.1-19.0.0.96.jar：HighGrassBlock 继承 GrowingPlantHeadBlock；HighGrassPlantBlock 继承 GrowingPlantBodyBlock。两者模型 JSON 都使用 block/tinted_cross。原分类覆盖 BushBlock 和若干原版植物白名单，却没有通用 GrowingPlantBlock，因此 BOP 高草被分类为 solid，走选择框盒子几何路径。再叠加纹理表已满的纯色回退，产生绿色实体柱。上一条把这些柱子推断为树木并不准确。

## 修复

- 将 GrowingPlantBlock 纳入非实体植被分类；高草顶段和茎段使用现有交叉草片，不生成实体顶盖，不作为邻块/地形的实心遮挡。
- 将这类生长型植物接入密集地被的降采样，按 X/Z 保留整根，不放大成粗体素，不拆散上下段。远处遵循现有非实体地被显示距离，避免把高草当成远距离实体建筑持续绘制。
- 材质准备阶段优先登记短草、高草上下半、蕨类和注册表中的生长型植物、草/蕨 BushBlock，先于地形/城市材质。按状态登记，纹理仍按 sprite 去重。启动扫描最多 512 个状态且到 64 个材质行即停止，保留大部分表容量给其他材质。
- 成品几何签名 VERSION 10→11，使已保存的错误实体草柱重新构建；原始地形、植被来源缓存和城市缓存格式兼容逻辑不变。不需要手动清缓存。

材质表上限仍为 254；此次保证优先登记的常见草类在后续城市材质填满时保留透明纹理，并非扩容全局材质格式，也未声称解决所有模组材质溢出。

## 验证

新增未在旧白名单中的 GrowingPlantHead/Body 植物回归：验证非实体分类、两张草片加地面恰好 18 个顶点、没有盒子顶面或实体占用、密集场景保留整根且不放大。使用 CAVE_VINES / CAVE_VINES_PLANT 作为相同基类且绕过旧白名单的测试对象；真实 BOP 继承关系及模型另由实际模组 JAR 验证。

新增透明材质容量回归：草材质先登记，再用 300 个建筑材质填表到 254，检查预留草纹理、透明标记及磁盘材质恢复均保持有效。另执行现有水草、植被占用、简易植被、设置和成品/城市缓存回归。

其他对话正在变更服务端调度接口，整库测试源编译曾因 ChunkyWorkQueueTest、GenerationSchedulingPolicyTest、Forge DiskTaskRuntimeTest 与相应接口不匹配中断。本次临时 Gradle 测试脚本排除这些无关测试源；未修改这些测试来迁就构建。本轮只声明草渲染相关回归通过，不声明全部仓库测试通过。

测试脚本、完整日志、修改前快照与构建校验结果位于 C:/Users/Administrator/.codex/tmp/vss-grass-cutout-20261006/。独立构建放在两仓库 lib/grass-cutout-20261006/。

尚未向运行中的整合包安装或热替换本次构建；当前客户端仅做了只读材质采样。修复后的实际画面仍需更换构建并重启验证。首次加载旧区域会触发成品网格重建一次，之后可继续使用成品缓存。
