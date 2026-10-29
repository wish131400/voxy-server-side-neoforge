# VSS 开发约定

本约定用于 Forge 1.20.1 与 NeoForge 1.21.1。玩家安装与配置见 [README](README.md)，模块职责与现存边界见 [架构说明](docs/development/architecture.md)。

## 环境与日常命令

使用仓库自带的 Gradle Wrapper。Gradle 使用 JDK 21；NeoForge 编译和运行目标是 Java 21，Forge 编译和运行目标是 Java 17，构建 Forge 时本机还需安装或允许 toolchain 下载 JDK 17。不要修改源代码目标版本来解决本机 JDK 选择问题。Gradle 的 JDK 通过 `JAVA_HOME` 选择；个人 `org.gradle.java.home` 路径只放用户级配置，不提交到仓库。

Windows PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules --no-daemon
# 修改预测规划/调度/居民生命周期时，补跑调用方回归。
.\gradlew.bat planningConsumerTest --no-daemon
.\gradlew.bat jar --no-daemon
```

Linux/macOS 对应 `./gradlew verifyModules --no-daemon` 和 `./gradlew jar --no-daemon`。Forge 的 `jar` 会接着执行 `reobfJar`，发行物必须取重混淆完成后的 JAR。默认输出位于 `lib/`。

删除或迁移生产类后，使用 `gradlew cleanCompileJava cleanJar jar --no-daemon` 清理主源码编译输出并重新打包，避免本机旧 `.class` 混入 JAR；同时检查旧类路径已经消失。该命令保留已有测试报告。新增或修改测试后仍须执行对应回归，清理和打包本身不能替代测试。

`verifyModules` 执行生产字节码边界检查和固定范围的 CPU 行为回归，不启动 Minecraft、GPU 或真实存档。普通 `test` 是更广的历史测试集合，有些需要本机模组、原生库或图形环境；不能用跳过数代替成功数。完整 `check` 同时包含普通 `test` 与 `verifyModules`。`planningConsumerTest` 是可单独复现的预测规划调用方 CPU 回归，按类隔离 JVM，并为诊断写入测试提供独立临时目录；它不是完整普通 `test`。

## 每次变更的流程

1. 先写明要解决的行为、复现条件、涉及模块和验收结果。性能修改须记录相同场景的 CPU/GPU/分配数据，不能只比较不同位置的 FPS。
2. 查阅相关 `package-info.java`，确认唯一状态所有者、调用线程、配置来源和资源释放者。先确定接口和输入输出，再改实现。
3. 只把真实跨模块能力做成接口或注入依赖。小型无状态工具可直接调用，不为每个类建立接口，不引入全局 service locator 或第二套缓存。
4. 对共享算法在两平台同步修改；加载器、网络编解码、Minecraft API 和 Mixin 的差异留在各平台适配处。平台不适用的修改必须写明原因。
5. 修改边界、异步交接或协议时补行为回归，覆盖取消、维度切换、停止、过期版本和异常清理。注释、低影响文案变更不必增加镜像实现的测试。
6. 在两仓执行 `verifyModules`，按功能补运行对应回归；完成本地 `jar`，Forge 确认 `reobfJar` 成功。涉及图形时另做 GPU 回归和游戏复现。
7. 在 PR 描述中列出命令、通过/失败/跳过数量、未测试范围、两平台对应修改和兼容影响。先给评审可复核的结果，再执行发布流程。

CI 的 `verify.yml` 自动执行边界回归、规划调用方回归和打包，并保存测试报告与 JAR。专服启动、实际模组组合和 GPU 画面测试需要另行验证，CI 的 CPU 任务不代表这些测试已经完成。

## 自动化助手的提交与推送

自动化助手完成修改、测试和打包后，默认将改动保留在本地工作区。只有用户明确要求提交或推送时，才执行对应的 `git commit`、`git push`；“提交到 GitHub”包含本次提交和推送。一次授权仅适用于用户指定的这批改动，不延续到后续开发任务。

## 注释标准

每个生产包必须有 `package-info.java`，分别说明 **职责、依赖、线程、生命周期**。`architectureTest` 会检查缺失。新增或改变的公开边界还应说明单位、空值、失败行为和所有权；复杂分支说明保留它的原因及不变量。

工作线程输入应注明是否为不可变快照；异步结果注明 epoch/revision 和丢弃时的清理路径；GPU 资源注明创建、上传和关闭的线程。不要给每行加翻译式注释，也不要复制已与实现不符的旧性能结论。

## 依赖与线程纪律

- `common` 不读取上层配置；启动入口提供动态日志开关。`config` 不调用网络、预测或兼容实现。
- `api` 暴露契约，不能 import 这些实现。`VSSApi` 是客户端 API，含 `ClientLevel`，不得从专服初始化；服务端扩展使用 `api.worldgen` 或 `api.column`。整列过滤和覆盖语义见 [整列过滤接口](docs/development/column-filter-api.md)。
- 服务端业务在 `VSSServerNetworking` 中装配，注入 lifecycle、registry、delivery 等依赖。旧命令/广播适配器的门面调用有明确白名单，不能新增同类反向依赖。
- 预测和 Voxy 兼容层通过 `ClientLodSession` 查询唯一网络会话及稳定缓存身份，禁止依赖整个 `networking.client` 实现包。适配器必须无状态，默认断线不能声称真实覆盖成立。
- 服务端玩家/世界读写在服务端线程；客户端可变状态在客户端线程；OpenGL 生命周期在渲染线程。后台计算使用安全快照，完成后回原线程检查版本。
- 预测规划统一经过 PredictionPlanningPipeline 与 PredictionClientPreparation，保持每通道一个保留任务；纯算法接收快照，结果先过 PredictionPlanningValidity，再由原状态所有者发布。不得新增平行 future/generation 状态机。
- 获取的许可、租约、上传暂存、原生内存和 GPU 资源，在成功、取消、拒绝、异常和旧版本丢弃路径上都必须释放。不能在渲染线程等待磁盘、生成或系统 CPU 采样。

## 实验、回归与发布

生产修复和算法对照分开维护。保留的行为回归使用当前接口；历史 baseline 放在 `tools/prediction/`，不得编进主 JAR。旧独立渲染器及依赖其接口的测试已经移除，不再保留失效的默认编译排除名单；当前范围见 [实验登记](docs/development/architecture.md#实验与测试范围)。使用历史实验前须检查专用 fixture 和接口，不能用旧报告代替当前路径的验证。

不要为了让门禁通过而扩大排除、冻结未知违规或删除有效断言。新增边界例外须说明具体类型、原因和迁移计划，并同时更新测试与架构文档。不要把 `client.prediction` 的内部类批量改成 public 来迁包。

协议字段、列完整性/压缩、缓存签名/格式、原生 ABI、Mixin 目标变化必须单独说明兼容策略。版本号相同不能保证不同构建的两端兼容。JAR 不包含测试框架、实验实现和本机采样文件。
