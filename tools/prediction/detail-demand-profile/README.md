# VSS 统一显示需求与验收采样

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

本工具用于核对固定视角、缩放往返、移动、转身和进档表现。它记录计数与相机上下文，不读取 GPU 缓冲，不抓取材质、世界种子或完整世界数据。默认关闭。

## 启用

在启动器的 JVM 参数中添加下面一项，然后自行启动游戏：

```text
-Dvss.detailProfileDirectory=C:/Users/Administrator/Desktop/vss-profile
```

每秒最多记录一行 JSONL，文件名为 `vss-detail-<run UUID>.jsonl`。最多记录 1,800 行；背景写入队列为 16 行，写入跟不上时丢弃并记录 `droppedRows`。写入失败会关闭本次采样。渲染线程组装固定大小的标量与数组快照，背景线程执行 JSON 编码和磁盘写入。启用此参数后，现有分通道 GPU 计时按每 30 帧抽样；不强制同步查询结果。

记录包含维度、相机、完整投影／视图矩阵、窗口尺寸、屏幕选择尺度、望远镜状态、质量设置、生成版本、有效目标与完成／取消计数、CPU 完成任务计数、不透明和水面的源／地形／显示／实际选择范围、四个距离带、GPU live／committed／源／显示驻留，以及分阶段 CPU/GPU 的累计时间和样本数。

`frameWindow` 是最近 90 帧的即时诊断。`frameCounters` 是逐帧累计计数与 1 ms 桶的直方图；对照脚本使用后者的区间差值计算 FPS、P95／P99 和长帧次数，不平均重叠窗口，不重复累计同一长帧。501 桶表示超过 500 ms；桶值是上界。

暂停菜单在任意一帧出现时会标记本采样区间；脚本排除这类区间、数据丢失、计数重置、维度切换和超过 2.5 秒的采样断档。

## 同条件测试

旧包与新包使用同一存档、资源包、窗口、画质、视距与着色器状态，各自记录一次。先保留一组冷进入存档记录，再做完成生成后的固定位置测试，二者分开比较。

1. 固定位置、朝向，普通视野静止至少 30 秒。
2. 放大同一目标，等待细化完成，再静止至少 30 秒。
3. 缩小，等待过渡结束，静止至少 30 秒；核对显示／选择面是否下降，源／CPU 缓存可保留。
4. 再放大原目标；核对细范围恢复、生成是否复用、上传和长帧是否突然增加。
5. 按固定节奏转身、沿固定路线移动；核对旧任务取消、缺失覆盖、接缝、上传和帧尾。
6. 退出并重新进入同一存档，记录前 60 秒；区分冷生成、暖缓存与后台恢复。

自动匹配只接受相同维度、窗口、质量、距离、着色器、游戏版本、相机与投影的普通／放大静态区间。至少匹配 10 秒才标为固定画面可比。相同视角仍需要人工核对世界内容、生成进度、材质和覆盖。移动／转身／进档需要外部重复路线，脚本不会自动认定 FPS 收益。

## 对照

```powershell
python tools/prediction/detail-demand-profile/analyze.py --before C:/Users/Administrator/Desktop/vss-profile/old.jsonl --after C:/Users/Administrator/Desktop/vss-profile/new.jsonl --output C:/Users/Administrator/Desktop/vss-profile/comparison.json
```

输出完整 JSON 与同名 Markdown。生成／计划／上传等累计计数作区间差值；面数、显存是时点值；各 CPU/GPU 阶段按新增样本数加权，互相包含的区间不能相加。没有新 GPU 样本的阶段不输出旧均值。

## 保留标准

能稳定减少完成工作量、累计分配、实际提交、驻留或同条件帧尾，且覆盖、接缝、材质、水面和缩放返回通过回归，才保留生产改动。单个组件时间改善不能换算为游戏 FPS。仅最粗档收益而中距离变重、改变图像或增加补传抖动的方案不保留。

Java 测试：`-I tools/prediction/demand-tests.gradle` 下的 `detailDemandCpuTest`、`detailDemandProfileTest`、`detailDemandRegressionTest`、`detailDemandProductionGpuTest`。Python 统计测试：`python tools/prediction/detail-demand-profile/test_analyze.py`。
