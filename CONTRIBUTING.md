# 构建、测试与开发

玩家安装和使用见 [README](README.md)。模块、数据流和接口契约见 [架构索引](docs/README.md)，协作约定见 [AGENTS.md](AGENTS.md)。以下命令从项目根目录执行，示例使用 Windows PowerShell。

源码格式与行尾遵循[统一规范](docs/guides/git-line-endings.md)，使用 `.\scripts\format.ps1` 应用、`.\scripts\format.ps1 -Check` 检查。当前 CPU 优化属于 [TODO](TODO.md) 中的非阻塞待办。

首次克隆后运行 `.\scripts\install-hooks.ps1`，为本仓库启用 pre-commit 格式检查。钩子只检查暂存快照，不自动格式化或暂存文件；已有其他 hooksPath 时停止，避免覆盖现有钩子。

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

本节命令供用户手动执行。自动化默认只做格式、编译和无窗口单元测试；没有用户新的明确请求，不运行 `runClient`、启动游戏验收或占用前台。

```powershell
.\gradlew.bat buildNative :mc-26.2:runClient -PprimeptEnabled=true -PprimeptGeometryCache=true -PprimeptValidation=true -PprimeptProfile=true
.\gradlew.bat buildNative :mc-26.3:runClient -PprimeptEnabled=true -PprimeptGeometryCache=true -PprimeptValidation=true -PprimeptProfile=true
```

按版本单独运行。各适配器的 `run/` 保存日志、选项、截图和存档。使用独立测试存档或副本，不让较新版本直接升级旧版验证存档。启动默认请求 Vulkan 和 1920×1080；仍需从实际设备日志和主 target 尺寸确认后端与分辨率。

根 `runClient` 是 26.2 的别名，另有 `runClient26_2` 和 `runClient26_3`。常用属性如下：

| Gradle 属性 | 用途 |
| --- | --- |
| `-PprimeptEnabled=true` | 启用 PT，默认关闭 |
| `-PprimeptRenderer=vanilla` | 启动时选择原版；配合 enabled=true 保留之后切换 Prime 所需设备能力，未指定时使用保存的设置（初始为 path_trace） |
| `-PnativeLibrary=绝对路径` | 指定引擎库，适合同一不可变 DLL 的双版本验证 |
| `-PprimeptValidation=true` | 启用宿主 Vulkan validation |
| `-PprimeptProfile=true` | 开启 Java 窗口汇总；粗计时和慢帧警告常驻 |
| `-PprimeptProfileLeaves=true` | 配合 profile 开启逐 Cube 细计时，默认关闭；只用于成本归因 |
| `-PprimeptProfileCsv=绝对路径` | 保存逐帧 CPU 节奏、阶段及源计数，保留离群值 |
| `-PprimeptCaptureAudit=true` | 记录源 quad、实际 tint 与被排除的原版明暗；用于正确性检查 |
| `-PprimeptGeometryCache=true` | 试行 Fabric wrapper 的 geometry-key 缓存；用 false 做同构建对照，保持其他配置一致 |
| `-PprimeptWorld=存档目录名` | 使用 quick play 进入该版本 run 目录下的测试世界 |
| `-PprimeptUuid=玩家UUID` | 在测试副本中读取指定已有玩家的位置和状态 |

外部启动器对应的 JVM 参数见 README；不要将 Gradle 的 `-P` 属性直接交给 Java。

客户端命令 `/primept renderer vanilla` 与 `/primept renderer path_trace` 只请求切换，实际资源移交在下一外层帧边界执行。验证时覆盖 Prime→原版→Prime、世界退出/重进、标题界面资源重载及退休失败；等待资源加载完成再采样，不把切换暂停计入稳态。首个 Prime 后端使用宿主事件驱动的源路由，加载完成的判据包括待路由地形事件清空，不能仅等待首帧输出。新增后端通过惰性工厂注册，并遵守公共 `RendererSlot` 的完成/失败契约。

### 用户手动检查重点

1. 两版分别使用对应版本的测试存档或副本，确认实际 Vulkan 后端与原生 `1920×1080` 主图像，等待地形加载完成。较新版本保存过的世界不要交给旧版本验证。
2. 观察地形、透明表面、实体/方块实体、框内物品、掉落物和粒子是否缺失或重复；检查旋转/移动、物品内容变化与持续增删，以及手部/HUD 是否正常。特殊文字、glint、outline 和折射仍按当前支持范围判断。
3. 依次切换到 `vanilla` 和 `path_trace`，等待各自就绪；检查原版地形恢复、PT 重新加载，以及皮肤、地图等动态纹理。再检查世界退出/重进与资源重载后是否正常。
4. 检查对应 `adapters/mc-*/run/logs/latest.log`，记录异常、Vulkan `VUID` / `SYNC-HAZARD`、缺失纹理或后端恢复失败。反馈版本、操作步骤、场景与日志，截图/日志副本放 `artifacts/`。
5. 在两版分别拖动窗口、切换全屏、最小化/恢复；覆盖横/竖/奇数尺寸并回到 1920×1080。检查宽高比、边缘覆盖、历史残影和 HUD 方位；实时应保持新噪声输出；离线调整尺寸时重建累积，稳定后应继续收敛。观察高饱和材质、灰阶和亮部的 primeDRT 输出，以及细缝/斜面是否自遮挡或漏光。HDR 呈现和自动曝光仍未接入。

6. 在“视频设置 → Prime 渲染设置”检查四组控件、默认恢复与关闭后持久化；重新启动确认配置生效。退出客户端后将 `config/primept.properties` 的 `version` 改成不匹配的值，再启动应整份回退默认，日志说明原因。
7. 世界加载完成后用 Ctrl+Alt+F2 进入离线，确认视角/实体/粒子固定、噪点持续减少；按 Esc 打开菜单仍保持离线。曝光、primeDRT 和每帧采样数可以修改，路径/光照固定。调整尺寸后重新累积；再次按快捷键应重新捕获当前世界，地图/动态纹理不能过期。覆盖冻结时 F3+T 重载、切原版、退出/重进世界。
8. 实时诊断依次查看原始噪声色、线性深度、世界法线；检查物体边缘、alpha 表面和天空（深度/法线预览为黑）。修改深度范围只改变预览；回到最终输出后 primeDRT 正常。深度/法线尚不代表完整 DLSS RR 接入。

上述启动命令开启 validation 用于正确性检查，不用于性能结论。需要性能采样时，将 `-PprimeptValidation=false`，保持细叶计时和 capture audit 关闭，另加 `-PprimeptProfileCsv=绝对路径` 保存逐帧数据；固定场景、相机、画质、射线预算与分辨率。原型稳态应只交换请求/响应头，`requested/compiled` 为0；覆盖边缘可能仍不完整，pending=0 不能证明全部64段单元齐备。再分别记录稳态及更新阶段；CPU 优化目前非阻塞，不以即时 FPS 达标作为本轮检查的前提。

## 按改动选择验证

纯文档修改核对事实、命令和链接即可。代码修改按受影响的契约选择以下入口，记录实际执行结果及未覆盖范围。

```powershell
# 无 GPU / Slang 依赖的协议、引擎边界与算法测试
cargo test -p prime_minecraft -p prime_scene -p prime_engine --no-default-features --locked
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
cargo test -p prime_vulkan --features shader-tests --lib --locked -- --ignored --skip packing_cost_matrix --nocapture --test-threads=1
```

这些测试覆盖 cutout、累积、尺寸变化、增量场景和宿主资源退休；小尺寸/奇数尺寸用于边界检查，不是性能数据。改变宿主集成或捕获时，还需在对应 MC 适配器实际运行，检查主图像与 HUD、资源重载、世界退出等相关生命周期。更新公共接口时验证受影响的两个适配器；编译通过不证明 Mixin 注入或实际 GPU 功能正常。

`shader-tests` 另编译无窗口测试入口，直接验证生产 Slang 的 Z-Sobol、颜色、primeDRT 与安全起点；正常发行构建不包含测试入口。所有 imported shader 的改动都会触发重编译。同步验证日志出现 `Prime Vulkan ERROR`、`VUID` 或 hazard 时，即使 Rust test harness 返回通过也不能视为 GPU 检查通过。Slang 模块/数学支持边界及可替换的显示策略见 [模块说明](docs/shaders.md)。

实例相关 GPU 测试覆盖局部原型、仿射/颜色/UV、增删及在途资源退休。Java 的 `cpuSmoke` 在真实 Fabric/Mixin 类上验证源路由、标准模型、下游截断与 target 创建/缩放。测试启动器在 preLaunch 退出，不调用游戏 main、不创建窗口或设备。地形原型通过真实 MC palette/模型字段、FFM 和独立 CPU native 库验证64段完整性、重复 dirty 合并、相同输入不重编译、空段清除与未知模型默认值；opaque 模型回调会主动抛错以证明未被调用。该入口需要 Rust，构建库放在 `build/source-cpu-native`，不会覆盖游戏使用的 release DLL。人工窗口不能代替实际视距或游戏验证。

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke --no-parallel

# 仅验证宿主 target 生命周期；CPU 记录设备替代图像分配，不调用 Vulkan
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeTargetResize=true --no-parallel
```

路由改动先执行双版本 `cpuSmoke`，生成 `build/routing-fixtures/mc-section-source.bin`，再执行下面的原生回放测试；fixture 缺失时报错。旧 TerrainRouter/FluidRouter 只保留在测试源集，用于静态 tint/geometry key、流体和参数粒子的独立参考，不代表当前生产地形的语义。

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke --no-parallel
cargo test -p prime_minecraft --locked -- --include-ignored --skip source_cost_matrix
# 旧封闭几何路径的独立对照
cargo test -p prime_scene --lib --locked java_routing_matches_both_versions_actual_source_and_fluid_particle_oracles -- --ignored --nocapture
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeForeign=true --no-parallel
```

手动重点检查地形可以加载和显示、跨区块移动后持续补入与卸载、放置/破坏、资源重载、原版↔PT和离线切换；实体、HUD与粒子应继续可见。未知模型可显示紫色方块、流体暂为整格颜色代理，multipart/tint 可能不同，按 [PROTOTYPE_HACKS](PROTOTYPE_HACKS.md) 验收。启动前重建 `buildNative`；ABI v5 不能混用旧 DLL。原生1080p画面与实际性能由用户检查。

独立图像诊断入口：

```powershell
cargo run -p prime_tools --bin prime-pt-smoke -- smoke artifacts/smoke.png 32
```

需要直接调试 FFM 时，可 `cargo build -p prime_engine` 后运行 `.\gradlew.bat nativeSmoke`；也可通过 `-PnativeLibrary=绝对路径` 选择库。

## 同步工作池

Prime 自有 CPU 工作池全部在 Rust。`PRIME_CPU_THREADS` 控制源编译与后端打包私有同步池的线程数，默认取可用 CPU 数与 8 的较小值；设为 1 可验证串行路径，非法非正值拒绝。小批直接运行，多线程仅写各自独占输出，返回前全部 join。Java 不再有 `primeptCompilerThreads` 参数或 Prime section compiler，源回调留在宿主 owner。

Java CSV 的 `terrain_plan_ns` 是请求规划 FFM 总时间，`terrain_pack_ns` 是按表读取/封装源和应用列镜像的时间，`terrain_accept_ns` 是响应提交、Rust 解码/编译/发布的同步总时间；它们包含于 `terrain_total_ns`，总时间还包括初次 epoch/atlas 准备和事件封装。`terrain_source_bytes` 是本帧请求输入与响应输入之和，包含资源定义，不含返回请求表。`terrain_selected` 是请求段数，`terrain_routed` 是有源响应数，`terrain_deferred` 是本批无源数；pending/waiting 当前为0，不是64段全部就绪证明。loaded/unloaded 是原始列事件数，entered 是 Rust 新激活且镜像成功的列数。`mc_source[...]` 另给 native plan/decode/compile/publish、请求批次数、变化/编译/活跃/驻留段数与 hack 使用计数。

`compile` 包含排序、作业建立、`kernel`（slab 解包/剔面/展开，含首次池创建）和 `finalize`（归并、精确内容比较、边界计算及不可变输出准备）；各值都是调用方墙钟时间，不是 worker CPU 时间之和。`published_layers` 是实际替换或删除的图层数，`retained_layers` 是重新编译后内容相同而保留的非空图层数。`publish` 是 owner 上的场景变更和旧引用释放，不包含 GPU 构建；并行化不会消除新输出复制和旧几何回收成本。

封闭源批次没有人为的跨帧工作配额。完整首载或大范围修改可能形成真实长帧，应记录其成本；工作池线程数、几何批次、在途 GPU 页与当前活跃内容不是同一数量。空闲池页保留历史峰值，renderer 销毁时再释放。

## 性能测量

正式性能测试使用原生 **1920×1080**，记录实际主 target 尺寸；降分辨率、动态分辨率或重建后的输出不能标为原生 1080p。固定场景、相机、种子、渲染参数、帧率上限和 VSync，记录构建、GPU/驱动、预热与采样范围。关闭 validation、capture audit 和逐调用 trace；可选聚合日志、CSV 和细粒度 profiling 是否启用也属于测量条件。测量期间避免另一游戏或 GPU 测试争用设备。

当前 section 原型的 CPU 成本夹具：

```powershell
$env:PRIME_SOURCE_BENCH_CSV = "$PWD/artifacts/source-cost.csv"
cargo test --release -p prime_minecraft --locked source_cost_matrix -- --ignored --nocapture
```

输出目录须存在。固定8个私有线程，dense/terrain/decorated 每批256段，分别测首次输入、相同 dirty 输入与各段单处内部编辑（dense 的编辑包仍相同，作为控制组）；另在约8.9万活跃空段窗口中往返移动一列。15次场景样本、24次窗口样本均保留，前三次标为预热。计时包括请求规划、响应解码、编译、输出准备和发布；排除夹具包生成、线程池初建、Java/FFM、GPU及游戏。三角形顺序/数值另外通过标量参照和1/4线程对照测试验证；此局部夹具不能换算游戏 FPS 或实际移动尾延迟。

地形源路由与 native 编译有独立的无窗口成本夹具，可用于相同硬件/工具链的版本对照。测试时串行运行，避免同时构建或测量另一路：

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeRoutingCost=local --no-parallel
$env:PRIME_ROUTING_COST_LABEL = 'local'
cargo test --release -p prime_scene --locked routing_cost_matrix -- --ignored --nocapture
```

以下成本夹具只测旧封闭路由路径，不能作为本分支生产基线。Java 输出到各适配器 `build/routing-fixtures/cost-local.csv`，覆盖完整遮挡、外露表面、交错可见和无 cull face 模型。每种场景每批 64 段，至少预热 2 秒后记录 30 批，保留预热与离群值；测实际模型/可见性路由、封批、当前 Java 线程分配及协议字节，使用已复用的 geometry key，不含 FFM/GPU 或真实世界加载。Rust 输出到 `artifacts/routing-stutter/native-local.csv`，固定私有 8 线程、每批 16 段和每段 512/4096 个放置，覆盖重复、末尾单处变化和全量变化；每种预热至少 500 ms 后记录 30 批，含解码/内容证明/编译/发布，不含生成输入、Java/FFM/GPU。这些数据只说明局部成本，不能当作原生1080p游戏帧率或尾延迟。

在独立 PowerShell 会话中运行宿主路径夹具：

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE_TRACE = '0'
cargo run --release --locked -p prime_tools --bin perf -- --frames 120 --warmup 10 --seed 324478056 --csv artifacts/perf-host.csv
```

这个夹具默认使用离线累积模式，不能直接与游戏的实时噪声/guide 模式比较性能。它模拟宿主提交，最多两帧在途，不回读输出；不包含 Minecraft、FFM、HUD 和窗口呈现。结果可定位 native 开销，不能直接称为游戏 FPS。游戏内可使用 `-PprimeptProfile=true`，将稳定帧、流送/修改、重定位与首次构建分开分析。

加 `--dynamic-counts 1000,10000,50000` 可测固定场景下三个动态三角形规模，每帧实际改变局部顶点，保留静态 revision。各规模独立预热、连续录制后排空；CSV 的 `fixture_update_ns` 单列测试数据修改耗时，批次墙钟包含这部分。该模式直接构造 `Scene`，`source_mode=direct_scene`、`protocol_decode_ns` 为空；它没有测 Java 或输入协议解码，不能把空值解释为零成本。

| 指标 | 含义 |
| --- | --- |
| `cpu_record_ns` | native 资源准备、命令池复用与命令录制 |
| `cpu_submit_ns` | 模拟宿主入队成本 |
| `cpu_slot_wait_ns` | 在途槽满时的 GPU 完成等待 |
| `gpu_preparation_ns` / `gpu_render_ns` | `instance-perf` 在启用 PRIME_PROFILE 时提供的上传/AS 准备区间和 PT/输出区间；关闭时留空，含阶段依赖和时间戳开销，不是孤立 AS/射线活跃时间 |
| `completed_gpu_ns` | 已完成提交的 GPU 时间戳区间，含上传、AS、依赖与 PT |
| `isolated_completion_ms` | 隔离变化事件从 CPU 开始到 GPU 完成的延迟 |
| `batch_completion_ms / batch_frames` | 阶段排空后的批次平均吞吐时间 |

异步入队耗时不等于完整帧耗时。游戏日志中的 `gpuLast` 是最近完成帧样本，不是汇总窗口均值；任务管理器 GPU 百分比也不能代替阶段计时。图像正确性由独立测试与实机检查验证，性能夹具不以读回图像计算 checksum。

粗粒度 CPU 计时与有能力宿主的 GPU 阶段时间戳常驻，无须打开 profile。当前提取＋世界源准备＋Prime hook 的 CPU 工作，或相邻 PT hook 起点间隔达到 **50 ms** 时，游戏日志输出 `Prime slow frame` 警告；首次记录也检查当前工作量。警告列出提取、地形快照/路由、世界准备的其余工作、drain/submit、动态提交、native record，以及同次 host serial 的 native 翻译、退休、槽位等待、静态准备、对象计划/执行、TLAS、描述符和命令录制。关键计数包括脏/光照通知、进入/加载/卸载列、全量失效、选中/路由/等待段、FFM 字节、实体/实例、三角形、BLAS 重建和 CPU 上传字节。

`currentWork` 不包括全部游戏 CPU 阶段；interval 包含上一帧 hook，prevHook 与 outsideInterval 标明口径，首帧 outside 为-1。新地形源流程发生于 extraction，extractionOther 是 extraction 减去 terrain 的余量，不是独立实体计时。sourcePlan/sourcePack/sourceAccept 是 terrain 子项；nativeSubmit/resourceSubmit 是后续 PT hook 的通用场景提交，不包含先前 section 批次，不能混加父子项。GPU 时间属于最近完成 serial。光照通知不是独立段数，也不证明处理成本由光照造成。冻结时保留计时，实时源计数为零；暂停/菜单/视距变化须结合动作解释。

正常帧不格式化诊断文本、不增加诊断 FFM 调用，不为计数遍历场景。警告按触发帧输出，不用 120 帧均值遮蔽尖峰；计时截止后才格式化和写日志。需要窗口统计时，Java 继续用 `-PprimeptProfile=true`，原生用 `$env:PRIME_PROFILE_CPU = '1'`，各自每 120 次输出 sum/mean/max。CSV 单独由 `-PprimeptProfileCsv=绝对路径` 控制；未开启可选细计时的 capture/refs 字段为 -1，表示未测量。上述开关不关闭常驻粗计时；逐 Cube 细计时和全量 CSV 仍为可选诊断。原生窗口统计的不同阶段 max 可能来自不同帧，未满批次和失败录制不进入成功批次统计。

静态增量链可用无 GPU 夹具比较相同构建的完整快照路径与生产增量路径：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin incremental-cpu -- --samples 100 --csv artifacts/incremental-cpu.csv
```

夹具在 1/16/512 个格、每格 1/64 个非空 mesh 下固定修改一个 mesh；每格均提交 64 个真实位置，包括空段。CSV 保留预热、全部样本、提交/翻译/分组耗时、发布/访问数量和分配请求。计时与分配计数分两轮，比较耗时时过滤 `allocation_instrumentation=false`、`warmup=false`；分配轮只用于归因。包含解码、翻译、分组与计划回收，排除包生成、Java/FFM、GPU 和游戏调度。它是 CPU 微基准，既不是历史构建对照，也不代表游戏 FPS；实际渲染性能仍按原生 1080p 检查。

持久实例也可使用同一构建、相同 op7 输入，比较完整快照诊断路径与生产增量输入：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin incremental-cpu -- --instances --samples 100 --csv artifacts/instance-incremental-cpu.csv
```

固定 1k/10k/50k 常驻实例，覆盖 0、1、1% 和全量姿态变化。计时包含解码、发布和放置规划，另记实际访问/姿态数；源包构造、Java/FFM 和 GPU 不在其中。与静态夹具一样，计时轮和分配统计轮分开，完整快照路径是本构建诊断对照，不是旧提交的历史基线。

CPU 打包的线程数/批次矩阵单独执行，不混入 GPU validation 测试：

```powershell
cargo test --release -p prime_vulkan --lib --locked packing_cost_matrix -- --ignored --nocapture
```

该测试保留 1/2/4/8 线程及小、中、大批次的预热与逐样本 CSV 到 `artifacts/packing-cpu.csv`。输入分配和准备在计时之外，观察直接写入复用输出切片的成本；它没有 Vulkan 命令或游戏场景，不能外推整体吞吐量。

原生 CPU 快照同时记录三角形、实例、簇、材质页和重建数量，读取已有计数，不为统计逐帧扫描场景。`cpu_upload_bytes` 是成功的 CPU mapped-buffer 写入字节，包含 staging 和 AS 输入，不是 PCIe 带宽或 GPU copy 量。阶段探针运行用于归因，正式性能对比另记其启用状态；测量后从运行环境移除该变量。实际客户端验证采用有界动作或采样窗口，完成后及时正常退出，再分析日志，避免持续占用用户前台。

动态捕获的游戏 profile 另给出 `dynamicCapture`（额外材质绑定和 mesh 拷贝 CPU 均值，包含于 `mcBeforePT`）、`dynamicSubmit`（纹理增量与整帧 FFM 解码 CPU 均值，包含于 `hook`）。`dynamicSpansTotal`、`dynamicVerticesTotal`、`dynamicBytesTotal`、`modelMeshesTotal` 和 `particleMeshesTotal` 是该窗口总量，除以 `frames` 才是每帧均值；mesh 数是原版批次数，不是实体数。`dynamicCapacity` 为保留 packet 容量，`dynamicGrowthsTotal` 是该 writer 自创建起的累计扩容次数。原版动画/模型准备时间没有混入 `dynamicCapture`；Prime 独占世界时省去该批世界 staged 输出的原版 GPU 上传，手部与 HUD 上传仍属于宿主。

地形 profile 记录原始通知、Rust 请求、有源/无源响应、三个调用阶段和运输字节。源处理属于 extraction；mcBeforePT 只覆盖世界 render 到 PT hook，不代表完整 Minecraft CPU 时间。窗口最大值保留源处理峰值，正常帧不额外查询 native 诊断。

双版本 CPU 入口验证原型字段与批次契约，旧 Java 模型/tint/流体夹具仅作独立参考。旧 Java compiler 调度/遮挡图矩阵不再是生产基线；原生1080p游戏对照仍需手动完成。

标准模型还记录实际 `beSources/entitySources/modelSubmits`、标准/回退叶节点、源引用检查数与几何顶点读取数，以及 op7 的原型/实例 upsert/remove 和字节数。对象数、模型提交数、Cube 叶节点数与 TLAS 实例数不是同一单位，报告中分别标注。逐帧 CSV 的 hook 间隔是 CPU 帧节奏，包含两个 hook 之间的原版工作和等待；不是 GPU 执行时间或显示器呈现时间。阶段总和与端到端间隔的差额不能无证据地归因于某一 GPU pass。

普通物品的 `item_*` CSV 字段与日志分别记录提交、分组、实际检查/省去展开的顶点数、回退 quad 数、新建原型顶点数及几何共享命中。检查源值不等于重新发布几何，回退 quad 也不等于回退实体；必须与实际 raw 顶点和 op7 字节一同判断收益。方块实体每 120 次提取记录加载候选数、提前排除的远处对象、未知语义回退、立方体幸存者及局部/全局 `tryExtract` 调用量。`beSources` 是进入模型捕获的源数，不是遍历过的方块实体候选数；方块实体提取发生在世界 render hook 之前，也不能从 `mcBeforePT` 单独推算其成本。

逐 Cube 细计时单独由 `-PprimeptProfileLeaves=true`（JVM `-Dprimept.profile.leaves=true`）开启，默认关闭，计数仍保留。关闭时 CSV `refs_pose_ns` 与日志 `refsPose` 为 -1，表示未测，不是零成本。正式比较关闭该细计时；批次 profile/CSV 本身是否开启仍需记录。必要时另用关闭可选聚合日志、CSV 和细计时的同场景游戏 FPS 对照，不能把不同指标直接相减归因为探针成本。geometry cache 的 hit/miss/null/emit 为窗口或逐帧增量；`avoidedCopyBytes` 是避免的 Mesh 编码复制字节，不是全进程分配量，也不表示下游 raw/FFM 字节已减少。

大量方块实体的源协议成本可先用无 GPU 依赖的局部夹具定位：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin capture-cpu -- --objects 1000,10000,20000 --strides 36 --warmup 8 --samples 40 --csv artifacts/cpu-dynamic-protocol.csv
```

每个代理对象是 24 个四边形，使用实际 op6 字节流；同材质单 span，每个样本仅改变序号和一个源顶点。分别计时 `SourceScene::submit`、`translate_dynamic` 与旧快照最后一个 Arc 的释放，模拟替换期间仍保留旧快照。CSV 保留预热、各样本、Rust 分配请求与峰值；分配统计不是进程 RSS。该工具不包含 Minecraft 模型生成、Java/FFM、GPU 打包或渲染，不能换算成游戏 FPS。CPU 局部测试没有输出分辨率；后续实际渲染验收仍须原生 1920×1080，并记录实际提交数量，不能用放置数量或少量合并 span 代替万级可见对象负载。

持久实例的 CPU 夹具使用真实 op7 包，分别测首次发布、静止借用、1% / 100% 姿态更新和 1% 持续增删：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin instance-cpu -- --objects 1000,10000,20000,56133 --warmup 3 --samples 21 --csv artifacts/cpu-instances.csv
```

静止阶段不发送空包，其访问计时仅证明该 accessor 的成本，不代表整个 Java 捕获或完整渲染帧。每条记录的 revision 必须等于当前 batch sequence；源码工具、Java writer 与协议测试共同验证这个约束。测试参数和输出单位以各工具 `--help` 为准。

公共 Java 层另有实际 `InstanceCapture → NativeBridge.submit(MemorySegment) → SourceScene` 的 CPU 夹具，用同一 Java 构建、独立 JVM 和固定 DLL 比较跨语言提交成本：

```powershell
.\gradlew.bat :common:instanceSubmitPerf -PnativeLibrary=C:\absolute\prime_engine.dll -PinstanceObjects=56133 -PinstanceWarmup=64 -PinstanceSamples=120 -PinstanceCsv=C:\absolute\instance-submit.csv
```

七个局部 quad 原型由全部对象共享，覆盖静止、1% 和 100% 姿态变化。每帧仍观察所有常驻 handle，分别记录 begin/observe/end、seal 连续写包、一次直接 FFM 调用和成功后的 acknowledge；按实际更新数核验包大小。每阶段首次发布独立保留，不能当成稳态分布。CSV 保留预热与全部样本，旁文件记录 DLL SHA256 和 JVM；文件输出与哈希在计时之外。该工具不创建 Vulkan 设备，不含 Minecraft 动画/网格生成、GPU 或帧呈现，也不代表整条管线零复制。对照时交错运行旧/新 DLL，避免与构建或游戏争用 CPU。

原生实例 GPU 夹具使用同一宿主录制接口，固定 1920×1080、相机、种子、一个 48 三角形原型及 1k/10k/20k 实例，覆盖静止、1% 姿态更新和 1% 身份增删：

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE_TRACE = '0'
cargo run --release --locked -p prime_tools --bin instance-perf -- --samples 60 --warmup 8 --rounds 3 --csv artifacts/gpu-instances.csv
```

该夹具直接构造 `InstanceScene`，不含 op7 解码、Java、游戏和呈现。身份增删在相同位置替换以隔离成员变化的成本，不代表移动或重叠几何的最坏情况；简单 opaque 代理模型也不能代表复杂 Minecraft 画面的射线成本。CSV 保留每帧 CPU 录制、提交、在途槽位完成等待、完整 GPU 区间、上传字节与实际 BLAS 重建数。需要确认输入画面时可用工具 `--diagnostic` 在计时之外输出同一夹具图像，不把读回混入生产性能数据。

常驻诊断的无 GPU FFM 检查：

```powershell
.\gradlew.bat :common:test :common:cpuDiagnosticsSmoke
```

它只创建 native CPU session、读取诊断、提交 reset 并销毁；不附着 Vulkan、不创建窗口。双版本 `cpuSmoke` 另验证真实警告格式、毫秒单位与光照通知钩子。GPU 时间戳与实际游戏慢帧仍由用户手动检查。

## 文档与本地产物

- `README.md`：玩家安装、使用和可见限制。
- `docs/`：已采用的架构、接口契约与长期工程规范，明确区分接入状态与目标；不保存试验日志、待讨论设计或阶段总结。
- `CONTRIBUTING.md`：可重复使用的开发入口与测量方法。
- `HACK.md`：当前技术债、影响和完成条件。
- `TODO.md`：后续工作与验收条件，CPU 性能优化当前非阻塞。
- `docs/guides/git-line-endings.md`：长期行尾与格式约定；具体开发操作仍由本文件索引。
- `artifacts/`：本地调查、方案、性能/验收报告、截图、日志和 CSV；整个目录由 Git 忽略。

报告可放在 `artifacts/reports/<日期或任务>/`，保留环境、输入、结论和限制；不要从需提交的文档链接某次本地产物。设计落地后只提炼有效契约到架构文档。需要长期回归的最小测试 fixture 放到对应测试目录，注明来源与语义，不把一次运行的完整输出转成 fixture。
