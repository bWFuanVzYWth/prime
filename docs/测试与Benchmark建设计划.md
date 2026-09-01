# Prime 测试与 Benchmark 建设计划

## 目标与当前阶段

Prime 已进入 renderer data IR 阶段 1。benchmark 只回答“有多快”，不能证明优化后“仍然正确”；
因此 Java JMH 与 Vulkan timestamp 测量都必须先经过行为、编码摘要、ABI 和资源生命周期门禁。
当前已经为翻译层建立典型/极端 JMH corpus，并为规范坐标/色彩叶建立首个 Vulkan timestamp
基线；完整生产 frame/pass 的长期门槛仍需在对应数据迁移前逐项接入。

本轮改造后的原则是：

- JUnit Jupiter 是唯一测试引擎；
- CPU、构建产物、原生桥和 Vulkan Shader 有独立任务及明确环境契约；
- 普通 JVM 测试不编译生产 Shader、不创建 Vulkan instance、不加载原生库；
- 覆盖率只用于发现空白，不能代替行为正确性；
- 测试重构不得扩大生产 API，也不得复制第二套 OpenPBR 实现；
- benchmark 不得成为 `test`、`check` 或生产构建的隐式依赖。
- `testSupport` 只承载 test/benchmark 共用的确定性 corpus，不进入生产 jar；`benchmark` 是独立
  source set，不进入 `test` 或 `check`。

## 清单与基线

改造前基线为 147 个测试源文件、573 个 JUnit 测试：529 个 JVM 测试和 44 个 Vulkan Shader
测试。当前仓库有 177 个测试/测试设施 Java 源文件；五个分层任务合计执行 662 个 invocation：
610 个 JVM、12 个 artifact、3 个 Windows native、36 个 Vulkan compute 和 1 个 Vulkan RT。
除四个 JetCheck
性质测试和翻译入口错误边界外，P0/P1 补强增加了真实 RT 生命周期、提交事务、纹理/terrain
generation、Renderer 生命周期、动态捕获会话和确定性并发测试。原有行为断言全部保留，其中
8 个不需要 GPU 的 ZSobol 映射/分层测试从 Shader 层移入 JVM 层。错误边界测试有三个参数化
case。最近的 Streamline 补强增加 common constants、运行时门禁、native 发布、发行资源和
生产 Shader 输入转换测试；各任务仍为零跳过。

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
| `test` | 610 个纯 Java 行为、数学性质和状态机测试 | 不编译 Shader、不加载原生库、不需要 Vulkan；排除 `artifact`、`native`、`gpu-shader`、`gpu-ray-tracing` 标签 | 是 |
| `artifactTest` | 12 个生产 SPIR-V、manifest、descriptor/payload ABI、资源和桥接 DLL 打包测试 | 允许编译生产 Shader；无运行环境跳过 | 是，由 `check` 调用 |
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

当前试点只有四个高价值纯 Java 目标：

- `RectangleDecomposition64`：合法 64×64 标签层的坐标合法、无重叠、无遗漏、标签保持和确定性；
- `TexturePageLayout`：随机 sprite catalog 的排列无关性、mip 对齐、页内边界、无重叠、
  descriptor 页数上限和缺失通道；
- `BoundedDirtyClusters`：add/range/invalidate/drain/clear 命令序列与简单参考状态机逐步一致，
  包含负坐标、容量溢出和 drain 后复用。
- cluster translation cell semantics：每个 case 含 1–24 个边界矩形并执行四种排列；测试侧独立
  原子 cell model 比较覆盖、owner、介质、关系、拓扑、纹理、颜色和裁剪后的四角 UV。

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
| Streamline / DLSS-G 边界 | `test` + `artifactTest` + `shaderTest` | row-major common constants、相机历史重投影、运行时 status/min-size、真实 reversed depth、top-left motion、窄 descriptor 闭包与发行 DLL；NVIDIA fake-swapchain device-lost 仍按高风险上游缺陷隔离 |
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

### 翻译层 P0 回放与语义门禁

`ClusterTranslationInput` 是一次翻译的不可变输入边界，统一携带 fixed-slot `CapturedCluster`、
资源 epoch 内的 `LabPbrMaterialSet` 和全部 `ClusterTranslationSettings`。原有三参数入口保留；
新入口接受 `ClusterTranslationControl`。取消检查位于阶段边界、每个 section/group 和 hot loop，
连续处理不超过 1024 个 work item 就会再次检查。取消异常不包装、不返回部分 mesh，调用方可以
立即用同一 input 重试；overlay pairing、boundary cell、coalesce 和 mesh build 分别有中止与
重放测试。

`ClusterTranslationReplay` v1 是诊断格式，不是长期生产序列化 API。它使用 GZIP 二进制，带固定
magic、version 和 translation ABI ID，保存 64 个 section slot、quad/peer/surface/block/fluid
facts、去重 sprite 与可用像素、实际引用的 LabPBR 子集以及所有 settings。解码后的数据硬限制为
256 MiB；错误 magic、旧版本、截断、非法枚举、越界计数和尾随解码数据都会明确失败。仓库内的
最小 v1 固定资源保证兼容读取，codec round-trip 还要求输入行为和重新编码一致。

现场 recorder 默认完全关闭。只有 JVM 参数 `-Dprime.translation.replay=true` 才启用；关闭时不会
查询 game directory，也不会访问文件系统。启用后的设置为：

```powershell
$env:JAVA_TOOL_OPTIONS = "-Dprime.translation.replay=true -Dprime.translation.replay.minMillis=250 -Dprime.translation.replay.maxFiles=32"
.\gradlew.bat runClient
```

`minMillis` 只筛选成功翻译；失败和取消不受慢例阈值影响。`maxFiles` 每次 compiler 生命周期最多
保留 8 个，允许调整但硬上限为 32。上面的现场采样命令显式使用 32；采样结束后可用
`Remove-Item Env:JAVA_TOOL_OPTIONS` 清除当前 PowerShell 会话中的设置。文件写入
`${gameDir}/prime-translation-replays/`，先完成临时 GZIP，再原子发布为
`<outcome>-<sequence>-<sha256>.ptr.gz`；文件名不含 cluster 坐标。导出错误只警告一次，不改变
渲染结果。

名额不再由所有结果先到先得，而是按结果隔离并在各自范围内循环替换。`maxFiles=32` 时保留最近
20 个慢成功、4 个取消和 8 个失败；普通慢例不会占用失败名额，后遇到的异常也不会因启动阶段
已经写满而丢失。默认 `maxFiles=8` 时对应 5/1/2。很小的自定义上限优先保留失败：上限 1 只
记录失败，上限 2 记录一个慢成功和一个失败。文件序号表示结果类别内的循环槽位，不表示全局
发生顺序。

自动 recorder 只能识别抛出的失败、取消和耗时异常，无法自行判断“画面看起来不对”。遇到视觉
错误时，应尽量停在问题附近，将 `minMillis` 临时改为 `0` 后重新启动并短距离复现；看到问题后
及时退出客户端并复制整个 replay 目录，避免继续跑图让同类别的新输入轮换掉现场。一般性能采样
仍使用 250 ms，避免初始化附近的大量普通输入淹没有价值的样本。

Replay 内容包含真实场景几何、block 坐标、sprite resource ID、纹理像素和使用到的材质通道，
可能暴露服务器建筑、资源包或私有资源信息。提交 issue 或加入仓库前必须由用户检查、按需裁剪
或取得分享授权；“文件名不含坐标”不等于内容匿名。

导入经人工确认的用户 replay 时：

1. 将原始 `.ptr.gz` 放入 `src/test/resources/replays/`，保留格式版本和来源说明，但不要把用户身份、
   服务器地址或绝对路径写入文件名；
2. 用 `ClusterTranslationReplay.read` 解码，先重现原失败，再为期望行为增加普通 JUnit 回归断言；
3. 能由独立 cell oracle 表达的场景同时加入语义比较；最小化后的反例优先保留，原始大文件只在
   它证明额外风险时保留；
4. 验证同一 input 重复翻译字节一致，并运行完整 `test`。

P0 本身未改变 `resolveExactOverlays`、boundary partition、`coalesce` 或 mesh builder 算法。完成上述
门禁后，首轮 P1 使用一次性的 32 个默认世界 replay 定位并改造了常见 CPU 热点：overlay 先按
边长为两倍 epsilon 的三轴空间格筛选、最终仍执行原精确谓词并保留最早输入匹配；`coalesce` 只比较
candidate/definition 身份相同的独立组；64×64 merge grid 用行 bitset 跳过空 cell；发光纹理分布在
单次 cluster 翻译内复用。所有状态仍为 invocation-local，primitive、surface relation、TextureId、
payload 和 SPIR-V ABI 均未改变。

同一 JVM 预热后的临时 corpus 测量中，32 个输入的累计翻译时间由约 4.88 s 降至 1.75 s，单输入
中位数由 135 ms 降至 53 ms，p95 由 348 ms 降至 89 ms，最慢输入由 526 ms 降至 92 ms。该数据
只证明本次优化方向，既不是 JMH 结果也不是回归阈值；现场 replay 不纳入仓库，可随时用新采样
替换。保留优化仍以独立语义 canonicalizer、epsilon 边界、取消检查和完整 mesh 确定性为门禁。

随后用一次性的 76 个默认世界 replay 审计最终提交给 BLAS 的三角形：在 3,520,272 个三角形中，
完全重合对和距离不超过 `0.0011` 的平行近重合对均为 0。生产实现不使用这个审计阈值；归并只由
精确共面事实、材质关系、完整碰撞事实，以及 vanilla 已知的 `0.002 / 16` inner-face 或 `0.001`
fluid inset 契约驱动。该 corpus 仍不纳入长期资源，后续可用新现场采样重新验证。

该审计与非平面 fluid raster-back 行为测试共同关闭了斜水面黑纹和玻璃离散闪烁两项现场问题。
最终根因、无效路线和重新出现时的取证要求见[斜水面细密黑纹排查与修复记录](斜水面细密黑纹排查报告.md)。

阶段 2 第一批数据迁移为翻译结果增加 invocation-local medium catalog，并在 Vulkan 上传边界映射为
renderer-lifetime `MediumId`。测试覆盖 family/TextureId/tint/water 身份划分、local→renderer
remap、不复用、primitive merge 保留、boundary 两端、codec v17 回放、非法 local ID 拒绝，以及
SPIR-V trace/shadow payload shape。Shader property 进一步覆盖任意 u32 ID、全部 8-bit IOR source
code、跨 dispatch f32 extinction、guide-control 位合并、offline surface round-trip 和 shadow
ID match/mismatch；identity 测试不得退回 extinction epsilon 比较。

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

## 阶段 1 数据合同与测量入口

规范的机器来源是 `shaders/renderer-data.json`。`generateRendererDataContracts` 会先验证 semantic、
encoding、binding、conversion、verification、phase lifetime、alias 和 memory plan，再生成：

- Java `RendererDataContracts`：坐标/色彩 oracle、semantic/encoding/binding 类型、资源计划和
  benchmark 元数据；
- 两个窄 Slang 叶模块 `prime_coordinate_contract.slang` 与 `prime_color_contract.slang`；它们不
  通过 umbrella import 扩大生产 entry 闭包；
- `build/reports/renderer-data/memory-ledger.csv`：render/display 每像素字节与固定开销。

当前静态账本锁定的显式下界是 realtime wavefront 600 B/render px、offline wavefront
244 B/render px、unfiltered raw images 95 B/render px、DLSS RR 147 B/render px +
8 B/display px，以及 Prime 自有 NRD images 291 B/render px。`VulkanContext.memorySnapshot()` 提供
VMA block/allocation 与 heap budget estimate 的同点快照，用于 resize、reload 和 backend switch
前后取样；完全由外部 SDK 分配的内存不伪装成 VMA 数据，仍需 SDK 专门归因。

可重复测量命令：

```powershell
.\gradlew.bat translationBenchmark
.\gradlew.bat rendererDataGpuBenchmark
```

前者运行 JMH 1.37，输出 `build/reports/benchmarks/translation.json`。典型 corpus 是 4×4×4
section 的常见 opaque/overlay/transparent/fluid 混合；极端 corpus 含 1024 个原子 boundary cell。
每次 fork 在计时前都校验 triangle/primitive/byte count 与完整编码 SHA-256。2026-08-31 的本机
初始样本（JDK 25.0.2，2 forks）为典型约 0.318 ms/op、极端约 1.665 ms/op；它只作为本机比较
起点，不是跨机器阈值。

后者在 validation layer 下对 1280×720 的规范坐标/linear Rec.2020 转换 kernel 使用 Vulkan
timestamp，输出 GPU、driver、Vulkan API、timestamp period、extent 和原始样本到
`build/reports/benchmarks/renderer-data-gpu.json`。该项证明 timestamp 基础设施和规范 Slang 叶
可测；短任务仍受 GPU 时钟状态影响，原始样本必须保留，且它不等同于完整 RR/NRD/Streamline
adapter pass 成本。

## 已知缺口

本阶段明确不建设：

- 完整 Fabric 客户端启动与资源 reload 集成测试；
- 完整生产 ray-tracing frame；当前只执行最小 BLAS/TLAS 四阶段 trace，并已覆盖 executor 的
  CPU 提交/回滚协议，尚未执行完整生产 descriptor/queue/history 组合；
- 图像回归及多 GPU/driver 差分；
- 完整生产 frame 的逐 pass timestamp、register/spill/occupancy 与 cache 指标；
- 外部 DLSS/Streamline native pool 的可靠显存归因；
- 固定 NVIDIA GPU/driver 上的长期性能回归门槛与图像回归。

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

Java benchmark 使用 JMH，GPU benchmark 使用 Vulkan timestamp query，并分开报告 setup、
command recording、submission、GPU 执行、同步和 readback。benchmark 不在测量区间编译
Shader、生成随机输入、写日志或执行正确性断言。只有正确性门禁全部通过且收益超过测量噪声时，
优化才可保留。
