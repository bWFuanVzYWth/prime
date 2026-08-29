# Prime 测试与 Benchmark 建设计划

## 目标与当前阶段

Prime 接下来会建立 Java 与 Vulkan benchmark，但 benchmark 只回答“有多快”，不能证明优化后
“仍然正确”。当前阶段只建设正确性门禁，不实现 benchmark：先把高风险行为、状态转换、ABI、
资源生命周期和数学性质锁定，再允许热点进入测量和优化。

本轮改造后的原则是：

- JUnit Jupiter 是唯一测试引擎；
- CPU、构建产物、原生桥和 Vulkan Shader 有独立任务及明确环境契约；
- 普通 JVM 测试不编译生产 Shader、不创建 Vulkan instance、不加载原生库；
- 覆盖率只用于发现空白，不能代替行为正确性；
- 测试重构不得扩大生产 API，也不得复制第二套 OpenPBR 实现；
- benchmark 不得成为 `test`、`check` 或生产构建的隐式依赖。

## 清单与基线

改造前基线为 147 个测试源文件、573 个 JUnit 测试：529 个 JVM 测试和 44 个 Vulkan Shader
测试。当前仓库有 167 个测试/测试设施 Java 源文件和 597 个 JUnit 测试方法。除三个 JetCheck
性质测试和翻译入口错误边界外，P0/P1 补强增加了真实 RT 生命周期、提交事务、纹理/terrain
generation、Renderer 生命周期、动态捕获会话和确定性并发测试。原有行为断言全部保留，其中
8 个不需要 GPU 的 ZSobol 映射/分层测试从 Shader 层移入 JVM 层。错误边界测试有三个参数化
case，因此所有分层任务合计执行 599 个 invocation。

`src/test/slang/programs.json` 是测试 Shader 的权威清单：

- 42 个 Slang entry；
- 35 个 `runtime` entry：31 个 compute entry 由 `shaderTest` dispatch，raygen、miss、any-hit
  和 closest-hit 由 `rayTracingTest` 组成真实 trace pipeline；
- 7 个 `compile-only` entry，只验证独立编译闭包；
- entry 源文件、声明的 SPIR-V 名称和实际编译产物必须一一对应。

`verifyShaderTestManifest` 在不编译 Shader 的情况下检查源码与清单，
`verifyCompiledShaderTestManifest` 在编译后检查产物与清单。报告写入
`build/reports/prime-shaders/`。

## 四层测试任务

| 任务 | 观察对象 | 环境契约 | 是否属于默认门禁 |
| --- | --- | --- | --- |
| `test` | 548 个纯 Java 行为、数学性质和状态机测试 | 不编译 Shader、不加载原生库、不需要 Vulkan；排除 `artifact`、`native`、`gpu-shader`、`gpu-ray-tracing` 标签 | 是 |
| `artifactTest` | 11 个生产 SPIR-V、manifest、descriptor/payload ABI、资源和桥接 DLL 打包测试 | 允许编译生产 Shader；无运行环境跳过 | 是，由 `check` 调用 |
| `nativeTest` | 3 个 NRD、FSR、DLSS Windows x64 原生桥执行测试 | 只支持 Windows x64；显式运行于其他平台会直接失败 | 否，由 Windows CI 显式调用 |
| `shaderTest` | 36 个 Vulkan compute/Shader 行为、数学性质和资源生命周期测试 | 必须有 Vulkan 1.2 compute device 和 `VK_LAYER_KHRONOS_validation`；缺失时直接失败 | 否，由 Linux GPU/Lavapipe CI 显式调用 |
| `rayTracingTest` | 1 个真实 BLAS/TLAS、SBT、raygen/miss/any-hit/closest-hit、readback 和释放测试 | 必须有 Vulkan 1.2 RT device、acceleration structure/ray tracing pipeline 扩展和 validation layer；缺失时直接失败 | 否，由 RT 硬件环境显式调用 |

`check` 依赖 `test`、`artifactTest`、生产 Shader 编译、Shader ABI、ray payload、架构和
发行物检查，但不隐式执行 GPU 或 Windows 原生测试。`jacocoTestReport` 聚合 `test` 与
`artifactTest` 的 Java 执行数据。

常用命令：

```text
./gradlew clean test
./gradlew artifactTest
./gradlew nativeTest
./gradlew shaderTest
./gradlew rayTracingTest
./gradlew clean check shaderTest jacocoTestReport
```

`clean test` 的任务图不得包含 `compileSlangShaders` 或 `compileSlangTestShaders`，不得产生
assumption skip，热运行目标不超过 15 秒。

## 框架与工具

### JUnit Jupiter

JUnit 负责发现测试、生命周期、标签、参数化测试、断言和 XML/HTML 报告。它仍是项目唯一的
测试引擎。小型数据表使用参数化测试；大批量 GPU case 继续一次 dispatch 后在 Java 中校验，
避免把几万个 case 展开成 JUnit invocation。

### JetCheck

[JetCheck](https://github.com/JetBrains/jetCheck) 0.3.0 只在普通 JUnit `@Test` 方法内部负责
生成输入、自动缩减反例和种子重放，不接管测试发现。仓库统一通过 `PrimeProperties` 配置：

- 默认使用稳定仓库种子；
- 可用 `-Dprime.test.seed=<long>` 重放；
- 失败信息包含重放参数，JetCheck 的缩减结果保留为 cause；
- 确认的最小反例应固化为普通回归测试。

当前试点只有三个高价值纯 Java 目标：

- `RectangleDecomposition64`：合法 64×64 标签层的坐标合法、无重叠、无遗漏、标签保持和确定性；
- `TexturePageLayout`：随机 sprite catalog 的排列无关性、mip 对齐、页内边界、无重叠、
  descriptor 页数上限和缺失通道；
- `BoundedDirtyClusters`：add/range/invalidate/drain/clear 命令序列与简单参考状态机逐步一致，
  包含负坐标、容量溢出和 drain 后复用。

不采用 jqwik。当前 jqwik 发布线与项目 JUnit Platform/代理工作流不匹配。若 JetCheck 试点造成
不稳定或维护负担，回退到项目内确定性生成器，不改变四层任务边界。

### JaCoCo

JaCoCo 0.8.15 聚合 `test` 和 `artifactTest`，生成：

- `build/reports/jacoco/test/html/`；
- `build/reports/jacoco/test/jacocoTestReport.xml`。

生成的 Shader ABI/Program Java 类从统计中排除；mixin 和其他低覆盖生产包保留，以暴露真实
缺口。本阶段不设置行、分支或包级百分比门槛。

当前聚合基线为 10,555/27,050 Java 行、4,393/11,308 分支。该数字包含 mixin 等低覆盖
生产包，只用于定位后续行为测试缺口。

### Vulkan 测试设施

GPU 测试共用三层测试专用设施：

- `ShaderComputeExtension`：统一 required/IDE 语义、每测试类生命周期和字段注入；
- `ShaderTestContext`：统一测试 Shader 目录和 runner 所有权；
- `VulkanTestDevice`：集中拥有 instance、physical device、logical device、queue、command
  pool、debug callback 和释放顺序；RT 模式还显式协商 device address、acceleration structure、
  deferred host operations 和 ray tracing pipeline；
- `ShaderComputeRunner`：只负责测试 buffer/image、descriptor、pipeline、dispatch 和 readback。
- `RayTracingTestRunner`：只负责最小三角形的 BLAS/TLAS build、descriptor、四阶段 RT pipeline、
  SBT、trace、硬件重心/readback 和完整释放。

`shaderTest` 强制启用 `VK_LAYER_KHRONOS_validation`。debug callback 收到任何 ERROR 都使测试
失败；每个测试设备的结果写入 `build/reports/vulkan-validation/`。最小生命周期测试覆盖
instance/device 创建、上传、sampled/storage descriptor 绑定、dispatch、readback 和重复释放。
`rayTracingTest` 使用同一 validation/debug/report 契约，但单独要求 RT 硬件，不能在不支持 RT 的
Lavapipe 门禁上 assumption skip。

## 测试职责与风险覆盖

| 风险域 | 主要门禁 | 当前覆盖 |
| --- | --- | --- |
| Java 数学、codec、状态机 | `test` | 边界、确定性、错误处理；矩形、分页、dirty cluster 增加缩减性质测试 |
| 调度与同步契约 | `test` | trace stage 顺序、barrier、资源依赖、并发元数据边界 |
| SPIR-V 与生产闭包 | `artifactTest` | descriptor closure、payload、record stride、manifest、独立 stage 闭包 |
| 原生 ABI 与资源 | `test` + `artifactTest` | Java ABI/platform 判定与 DLL 打包独立验证 |
| 原生执行 | `nativeTest` | NRD、FSR、DLSS bridge 的真实 Windows x64 调用 |
| GPU 数学与采样 | `shaderTest` | compact OpenPBR、transport、BSDF、材质/天体、重建/曝光、ZSobol parity 与统计 |
| Vulkan host 生命周期 | `shaderTest` | validated instance/device、buffer/image、descriptor、dispatch、readback、幂等释放 |
| Vulkan RT 生命周期 | `rayTracingTest` | BLAS/TLAS、host→AS→trace→readback barrier、SBT、hit/miss/any-hit/closest-hit、硬件重心与幂等释放 |
| 发行和架构 | `check` | Shader ABI、ray payload、依赖闭包、资源和发行 JAR |

NRD、FSR 和 DLSS 测试已按 JVM contract、artifact packaging、native execution 分层。
`TracePipelinesContractTest` 只保留纯调度/同步测试，读取生产 SPIR-V 的断言由独立
`TracePipelinesArtifactTest` 执行。`PrimeProductionMathGpuTest` 按 transport、BSDF、
material/celestial、reconstruction/exposure 和 sampling parity 分组，各组拥有独立 Vulkan
生命周期。ZSobol 的纯映射/分层测试运行在 JVM 层，parity 与输出统计测试运行在 Shader 层。
`ClusterSceneTranslatorTest`、`ClusterSceneTranslatorDeterminismTest` 和
`ClusterSceneTranslatorBoundaryTest` 分别覆盖翻译语义、确定性回放和入口错误边界。

terrain fixture 继续放在被测 package 内访问 package-private API。`CompiledClusterCodec` 只表示
测试回放格式，不提升为生产序列化 API。

### P0/P1 正确性补强

- `FrameCompletion` 被实时和离线 executor 实际使用：提交前失败按协议顺序回滚所有已准备
  owner；提交被接受后禁止回滚；任一 commit 失败时其余 history 仍完成收尾；
- `PendingSubmission` 被 `MaterialTexturePages` 实际使用：一个 generation 只能有一个 outstanding
  token，foreign、重复 submitted/abandon 和 replacement-before-resolution 都明确失败，close 会
  取回遗留 token；
- `TerrainUpdateTransaction` 具有 open/submitted/published/closed 显式状态，空更新允许直接发布，
  已提交资源与未提交资源保持不同回收语义；
- `TextureIdRegistry` 固定了纹理暂时移除、后续新增和重新出现时 ID 不变且退休 ID 不复用；
- `ResourceEpochCoordinator` 与 `BoundedDirtyClusters` 覆盖 reload/worker 交错、读者 drain、旧 epoch
  拒绝和多生产者无丢失；
- `RendererLifecycle` 固定单次初始化、disabled/unavailable/failure/shutdown 边界；
- `DynamicSceneCapture` 固定新帧丢弃未完成 session、element scope 顺序、错误嵌套、空帧 origin 和
  compatibility witness。

## 测试编写与维护规则

新增或重构测试应优先证明以下内容：

1. 离散状态、事件、身份、ABI 和异常类型精确一致；
2. 连续结果 finite、nonnegative、PDF 合法，并使用有来源的 ULP/绝对/相对或统计门限；
3. 状态机在每一步与简单、独立的参考模型一致；
4. GPU 资源有单一所有者，创建失败和释放顺序可观察；
5. Shader compile closure、manifest 和发行产物通过产物本身验证。

禁止通过搜索源码函数名、注释或特定调用形式证明行为。禁止为测试扩大生产 API 可见性。需要
改善可测试性时，优先提取纯函数、不可变输入和明确状态对象。公共 test-support 只表达稳定
契约，不能隐藏被测公式或复制生产 OpenPBR 数学。

性质测试与统计测试必须使用稳定 seed，并在失败时输出可重放输入。调整容差、统计样本数、fixture
或 expected 结果属于契约变更，应与性能优化分开审查。

## CI

Linux CI 使用 Ubuntu、Lavapipe 和 Vulkan validation layers，运行：

```text
./gradlew --no-daemon --stacktrace clean check shaderTest jacocoTestReport
```

Windows x64 CI 独立运行 `nativeTest`。CI 始终保存 JUnit、JaCoCo、Shader 清单/架构和 Vulkan
validation 报告；发行构建产物继续单独保存。

`rayTracingTest` 不能由无 RT 能力的 Lavapipe 伪造，通过 RT-capable 本地或自托管环境显式运行；
显式运行缺扩展或 validation layer 时直接失败。接入稳定 RT CI runner 后应把其 JUnit 和
validation 报告纳入同一制品保留规则。

## 已知缺口

本阶段明确不建设：

- 完整 Fabric 客户端启动与资源 reload 集成测试；
- 完整生产 ray-tracing frame；当前只执行最小 BLAS/TLAS 四阶段 trace，并已覆盖 executor 的
  CPU 提交/回滚协议，尚未执行完整生产 descriptor/queue/history 组合；
- 图像回归及多 GPU/driver 差分；
- benchmark harness、JMH source set 和 Vulkan timestamp query；
- 固定硬件上的长期性能回归门槛。

这些不是默认可忽略风险。未来 benchmark 或优化若触及对应路径，必须先补该路径的行为测试。
覆盖率报告出现的大块空白也只作为风险线索，由行为和契约测试补齐。

## Benchmark 准入规则

一个热点进入 benchmark 前至少满足：

1. 有代表常见输入的确定性 case；
2. 覆盖零、空、最小、最大、溢出、非法和生命周期边界；
3. 有与风险匹配的数学性质、状态转换、ABI 或资源所有权断言；
4. 有固定 seed 的扩展 corpus 和失败重放方法；
5. 优化前 fixture/公开结果已经固定；
6. 跨模块边界有行为测试；compute GPU 热点必须通过真实 `shaderTest`，RT/AS/SBT 热点还必须
   通过 `rayTracingTest`；
7. `test`、`artifactTest` 及该路径对应的 `nativeTest`/`shaderTest`/`rayTracingTest` 全部通过。

未来 Java benchmark 使用 JMH，GPU benchmark 使用 Vulkan timestamp query，并分开报告 setup、
command recording、submission、GPU 执行、同步和 readback。benchmark 不在测量区间编译
Shader、生成随机输入、写日志或执行正确性断言。只有正确性门禁全部通过且收益超过测量噪声时，
优化才可保留。
