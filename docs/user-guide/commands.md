# 命令与区域预生成

[文档目录](../README.md)

服务端 `/vss` 命令需要 OP 2，可使用游戏内自动补全查看参数。直接输入 `/vss` 或 `/vssclient` 显示帮助；中文别名和英文命令使用同一逻辑。

```text
/vss help
/vss 帮助
/vss help generation
/vss help chunky
/vss stats
/vss bandwidth get
/vss bandwidth set_mbps <mbps>
/vss queue get
/vss request_limits get
/vss distance get
/vss generation get
/vss generation stats
/vss generation set_player_concurrency <数量>
/vss generation set_global_concurrency <数量>
/vss storage get
/vss dirty get
/vss farplayers get
```

普通生成并发命令立即保存配置并刷新玩家会话限制。`set_player_concurrency` 在单人游戏中修改单人额度，在独立服务器中修改服务器的每玩家额度；它们控制普通玩家请求，显式 chunky 任务使用独立的速度优先路径。

## /vss chunky

VSS 自带区域预生成，无需安装 Chunky 模组。任务生成真实 Minecraft FULL 区块，并保存供 Voxy 使用的真实远景列缓存。

坐标和半径全部使用方块单位，范围包含边界并向外取整到完整区块。

| 指令 | 行为 |
| --- | --- |
| `/vss chunky <x> <z> <radius>` | 指定中心和半径，生成正方形区域 |
| `/vss chunky start <x> <z> <radius>` | 同上 |
| `/vss chunky here <radius>` | 使用执行位置作为中心 |
| `/vss chunky rect <x1> <z1> <x2> <z2>` | 指定矩形角点，顺序不限 |
| `/vss chunky status` | 查看进度、可用/复用/失败/在途数量和耗时 |
| `/vss chunky pause` | 停止扫描新位置和提交新生成，已在途工作可完成 |
| `/vss chunky resume` | 继续任务 |
| `/vss chunky cancel` | 取消并释放生成回调、票据和工作记录，已有数据保留 |
| `/vss chunky limit [get]` | 查看预生成队列上限，无需存在活动任务 |
| `/vss chunky limit [set] <数量>` | 在线调整未完成列数上限并保存；0 为不限 |
| `/vss chunky limit unlimited` | 恢复不限队列并保存 |
| `/vss help chunky` | 查看完整说明 |

中文根命令为 `/vss 预生成`，支持 `开始`、`当前位置`、`矩形`、`状态`、`查看`、`暂停`、`继续`、`取消`。队列设置支持 `限速` 或 `队列上限`，例如 `/vss 预生成 限速 查看`、`/vss 预生成 限速 设置 32`、`/vss 预生成 限速 32`、`/vss 预生成 限速 不限`；英文和中文可以混合使用。

示例：

```text
/vss chunky 0 0 512
/vss 预生成 当前位置 256
/vss chunky rect -256 -256 255 255
/vss 预生成 状态
/vss chunky pause
/vss chunky resume
/execute in minecraft:the_nether run vss chunky 0 0 128
```

`0 0 512` 覆盖区块 -32 到 32，共 4,225 列；矩形 `-256 -256 255 255` 覆盖 32 × 32，共 1,024 列。负坐标向下取整，-1 方块属于 -1 区块。

## 预生成队列上限

可用 `/vss chunky limit 32` 或 `/vss 预生成 限速 32` 在线设置 `chunkyQueueLimit`，立即生效并保存到服务端 `config/vss-server-config.json`，不需要暂停任务或重启。默认 `0` 表示不限队列，正数表示扫描、生成、打包、写盘或本地导入中尚未完成的区块列总数上限。降低上限不取消已有工作，在途数降到新上限以下后自动补位；提高上限或设为 `0` 后后续调度继续领取位置。没有活动任务时也可以设置，供后续任务使用。直接编辑配置文件仍需关闭服务器或单人游戏后修改并重新启动，文件本身没有热重载。

该配置只作用于显式 chunky 任务，按列数控制积压，数值不代表线程数、CPU 百分比或每秒速度。老配置缺少字段时保持 0，升级不改变原默认行为。

chunky 使用独立的生成路径，豁免普通自动生成的并发与每 tick 吞吐预算。实际并行度取决于 Minecraft 和整合包的生成器；`chunkyQueueLimit` 是在途列数，不是线程数。没有 `concurrency` 子命令。

## 权限与生命周期

- 服务端 `/vss` 需要管理员权限 2；`/vssclient` 本机指令不需要管理员权限。
- 在命令源维度生成，控制台可使用 `execute in <维度> run vss ...`。
- 全服同时一个活动任务；半径 0–8,192 方块，矩形最多 4,194,304 列，完整范围必须在世界边界内。
- 要求开启 VSS、区块生成和持久远景缓存。
- 玩家移动、切换维度或离线不会取消任务；服务器停止时终止任务。
- 每 60 秒向发起者报告进度，任务结束时立即报告结果；状态指令可随时查看。
- 重启后可重新执行相同范围，复用已保存的有效缓存。
- 已保存数据按现有缓存保留/清理策略维护，客户端通过正常远景同步范围接收。

## 客户端命令

客户端命令不需要管理员权限：

```text
/vssclient help
/vssclient stats
/vssclient stats details
/vssclient xaero disable
/vssclient xaero enable
/vssclient xaero reload
/vssclient prediction capture
```

`stats` 显示简明状态，`stats details` 显示会话、预测布局、瓦片、样本、feature／structure 与渲染统计。`prediction capture` 显式导出参考数据；`prediction` 根节点显示对应帮助。
