# 构建、测试与开发

玩家安装和使用见 [README](README.md)。模块、数据流和接口契约见 [架构索引](docs/README.md)，协作约定见 [AGENTS.md](AGENTS.md)。以下命令从项目根目录执行，示例使用 Windows PowerShell。

## 开发环境

| 工具 | 用途 |
| --- | --- |
| JDK 25 | Java 编译、Fabric 开发客户端及 FFM |
| Rust 工具链 | 满足根 `Cargo.toml` 声明的最低版本；构建 native 引擎 |
| Slang 的 `slangc` | 将 shader 编译为 SPIR-V；构建 Vulkan crate 时需要 |
| Vulkan 驱动与兼容光追显卡 | 运行 GPU 测试和实际游戏 |
| Vulkan validation layer / SPIR-V Tools | GPU 同步检查与 SPIR-V 验证 |

使用仓库的 Gradle Wrapper，不必另装 Gradle。Windows 的 Rust 工具链还需要对应的 MSVC 链接工具。仅运行 Java 编译与测试不需要 Rust 或 Vulkan SDK；纯 CPU Rust 测试可排除 Vulkan crate。

`prime_vulkan` 按以下顺序寻找 Slang：`SLANGC` 指向的可执行文件、`VULKAN_SDK` 下的 `Bin/slangc.exe`（Linux 为 `bin/slangc`）、`PATH` 中的 `slangc`。这些路径属于本机配置，不写入项目文件。当前运行支持范围见 README，不因存在 Linux 构建分支就视为已完成 Linux 验证。

## 构建与发行包

```powershell
# 公共 Java 层及两个 MC 适配器的编译和单元测试
.\gradlew.bat build

# release Rust 引擎、两个含原生库的 JAR，以及包间一致性检查
.\gradlew.bat verifyNativeJars
```

`verifyNativeJars` 自动调用 `buildNative`，再分别构建两版 `nativeJar`。检查内嵌引擎字节与公共桥接 class 相同、Minecraft 和 Fabric API 精确版本约束匹配。普通 `build` 不会生成可直接分发的 native JAR。

产物位置：

- `adapters/mc-26.2/build/libs/prime-pt-mc-26.2-<version>-native.jar`
- `adapters/mc-26.3/build/libs/prime-pt-mc-26.3-<version>-native.jar`

版本取自 `gradle.properties` 的 `mod_version`。两个 JAR 使用同一 `target/release/prime_engine.dll`，各自包含公共 Java 层。Minecraft/Fabric API 版本在各适配器的 `build.gradle` 中声明；升级时检查实际宿主和 Indigo 注入点。

## 开发客户端

```powershell
.\gradlew.bat buildNative :mc-26.2:runClient -PprimeptEnabled=true
.\gradlew.bat buildNative :mc-26.3:runClient -PprimeptEnabled=true
```

按版本单独运行。各适配器的 `run/` 保存日志、选项、截图和存档。使用独立测试存档或副本，不让较新版本直接升级旧版验证存档。启动默认请求 Vulkan 和 1920×1080；仍需从实际设备日志和主 target 尺寸确认后端与分辨率。

根 `runClient` 是 26.2 的别名，另有 `runClient26_2` 和 `runClient26_3`。常用属性如下：

| Gradle 属性 | 用途 |
| --- | --- |
| `-PprimeptEnabled=true` | 启用 PT，默认关闭 |
| `-PnativeLibrary=绝对路径` | 指定引擎库，适合同一不可变 DLL 的双版本验证 |
| `-PprimeptValidation=true` | 启用宿主 Vulkan validation |
| `-PprimeptProfile=true` | 开启 Java 汇总和 native GPU profiling |
| `-PprimeptCaptureAudit=true` | 记录源 quad、实际 tint 与被排除的原版明暗；用于正确性检查 |
| `-PprimeptWorld=存档目录名` | 使用 quick play 进入该版本 run 目录下的测试世界 |

外部启动器对应的 JVM 参数见 README；不要将 Gradle 的 `-P` 属性直接交给 Java。

## 按改动选择验证

纯文档修改核对事实、命令和链接即可。代码修改按受影响的契约选择以下入口，记录实际执行结果及未覆盖范围。

```powershell
# 无 GPU / Slang 依赖的协议、引擎边界与算法测试
cargo test -p prime_scene -p prime_engine --no-default-features --locked
cargo test -p rectangle_decomposition --all-features --locked

# 完整 workspace 需要 Slang，但实际 GPU 测试默认 ignored
cargo test --workspace --all-features --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings

# Java 公共层与两个适配器
.\gradlew.bat test

# 发行包内 DLL 提取、FFM 错误边界与真实 GPU 诊断；两版串行执行
.\gradlew.bat bundledNativeSmoke
```

`cargo test --workspace --no-default-features` 仍选中了 `prime_vulkan`，不能当成无需 SDK 的入口。选择上面的窄包命令才能验证 CPU 依赖边界。`bundledNativeSmoke` 使用同步读回的独立设备诊断接口，输出到各适配器 `build/bundled-ffm-smoke.png`，不代表实际游戏合成性能。

在单独的 PowerShell 会话中运行需要 validation 的 GPU 行为测试，避免环境变量影响后续性能测量：

```powershell
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --lib --locked -- --ignored --nocapture --test-threads=1
```

这些测试覆盖 cutout、累积、尺寸变化、增量场景和宿主资源退休；小尺寸/奇数尺寸用于边界检查，不是性能数据。改变宿主集成或捕获时，还需在对应 MC 适配器实际运行，检查主图像与 HUD、资源重载、世界退出等相关生命周期。更新公共接口时验证受影响的两个适配器；编译通过不证明 Mixin 注入或实际 GPU 功能正常。

独立图像诊断入口：

```powershell
cargo run -p prime_tools --bin prime-pt-smoke -- smoke artifacts/smoke.png 32
```

需要直接调试 FFM 时，可 `cargo build -p prime_engine` 后运行 `.\gradlew.bat nativeSmoke`；也可通过 `-PnativeLibrary=绝对路径` 选择库。

## 性能测量

正式性能测试使用原生 **1920×1080**，记录实际主 target 尺寸；降分辨率、动态分辨率或重建后的输出不能标为原生 1080p。固定场景、相机、种子、渲染参数、帧率上限和 VSync，记录构建、GPU/驱动、预热与采样范围。关闭 validation、capture audit 和逐调用 trace；计时 profiling 是否启用也属于测量条件。测量期间避免另一游戏或 GPU 测试争用设备。

在独立 PowerShell 会话中运行宿主路径夹具：

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE_TRACE = '0'
cargo run --release --locked -p prime_tools --bin perf -- --frames 120 --warmup 10 --seed 324478056 --csv artifacts/perf-host.csv
```

这个夹具模拟宿主提交，最多两帧在途，不回读输出；不包含 Minecraft、FFM、HUD 和窗口呈现。结果可定位 native 开销，不能直接称为游戏 FPS。游戏内可使用 `-PprimeptProfile=true`，将稳定帧、流送/修改、重定位与首次构建分开分析。

加 `--dynamic-counts 1000,10000,50000` 可测固定场景下三个动态三角形规模，每帧实际改变局部顶点，保留静态 revision。各规模独立预热、连续录制后排空；CSV 的 `fixture_update_ns` 单列测试数据修改耗时，批次墙钟包含这部分。该模式直接构造 `Scene`，`source_mode=direct_scene`、`protocol_decode_ns` 为空；它没有测 Java 或输入协议解码，不能把空值解释为零成本。

| 指标 | 含义 |
| --- | --- |
| `cpu_record_ns` | native 资源准备、命令池复用与命令录制 |
| `cpu_submit_ns` | 模拟宿主入队成本 |
| `cpu_slot_wait_ns` | 在途槽满时的反压等待 |
| `completed_gpu_ns` | 已完成提交的 GPU 时间戳区间，含上传、AS、依赖与 PT |
| `isolated_completion_ms` | 隔离变化事件从 CPU 开始到 GPU 完成的延迟 |
| `batch_completion_ms / batch_frames` | 阶段排空后的批次平均吞吐时间 |

异步入队耗时不等于完整帧耗时。游戏日志中的 `gpuLast` 是最近完成帧样本，不是汇总窗口均值；任务管理器 GPU 百分比也不能代替阶段计时。图像正确性由独立测试与实机检查验证，性能夹具不以读回图像计算 checksum。

动态捕获的游戏 profile 另给出 `dynamicCapture`（额外材质绑定和 mesh 拷贝 CPU 均值，包含于 `mcBeforePT`）、`dynamicSubmit`（纹理增量与整帧 FFM 解码 CPU 均值，包含于 `hook`）。`dynamicSpansTotal`、`dynamicVerticesTotal`、`dynamicBytesTotal`、`modelMeshesTotal` 和 `particleMeshesTotal` 是该窗口总量，除以 `frames` 才是每帧均值；mesh 数是原版批次数，不是实体数。`dynamicCapacity` 为保留 packet 容量，`dynamicGrowthsTotal` 是该 writer 自创建起的累计扩容次数。原版动画/模型准备时间没有混入 `dynamicCapture`；原版 GPU 上传仍需单独分析。

大量方块实体的源协议成本可先用无 GPU 依赖的局部夹具定位：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin capture-cpu -- --objects 1000,10000,20000 --strides 36 --warmup 8 --samples 40 --csv artifacts/cpu-dynamic-protocol.csv
```

每个代理对象是 24 个四边形，使用实际 op6 字节流；同材质单 span，每个样本仅改变序号和一个源顶点。分别计时 `SourceScene::submit`、`translate_dynamic` 与旧快照最后一个 Arc 的释放，模拟替换期间仍保留旧快照。CSV 保留预热、各样本、Rust 分配请求与峰值；分配统计不是进程 RSS。该工具不包含 Minecraft 模型生成、Java/FFM、GPU 打包或渲染，不能换算成游戏 FPS。CPU 局部测试没有输出分辨率；后续实际渲染验收仍须原生 1920×1080，并记录实际提交数量，不能用放置数量或少量合并 span 代替万级可见对象负载。

## 文档与本地产物

- `README.md`：玩家安装、使用和可见限制。
- `docs/`：当前稳定架构与接口契约，不保存试验日志、待讨论设计或阶段总结。
- `CONTRIBUTING.md`：可重复使用的开发入口与测量方法。
- `HACK.md`：当前技术债、影响和完成条件。
- `artifacts/`：本地调查、方案、性能/验收报告、截图、日志和 CSV；整个目录由 Git 忽略。

报告可放在 `artifacts/reports/<日期或任务>/`，保留环境、输入、结论和限制；不要从需提交的文档链接某次本地产物。设计落地后只提炼有效契约到架构文档。需要长期回归的最小测试 fixture 放到对应测试目录，注明来源与语义，不把一次运行的完整输出转成 fixture。
