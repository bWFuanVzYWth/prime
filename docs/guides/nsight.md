# Nsight Graphics 抓帧与版本对照

本手册维护 Windows 下从 Nsight Graphics 启动 Minecraft + Prime、抓取 GPU 工作和保存对照证据的流程。游戏启动、进入存档和触发抓取由用户手动执行；准备脚本只构建和导出参数。构建环境见 [CONTRIBUTING](../../CONTRIBUTING.md#开发环境)，测量原则见[通用测量约定](../../CONTRIBUTING.md#通用测量约定)。

## 选择抓取活动

| 目的 | Nsight Graphics 活动 | 主要产物 |
| --- | --- | --- |
| 找到整帧 GPU 成本、繁忙区间和空洞 | GPU Trace Profiler | GPU 时间线、硬件利用率、shader 采样 |
| 核对 Vulkan 调用、绑定、图像和加速结构 | Graphics Capture | 可保存的帧、API 状态和资源 |

先用 GPU Trace 确定时间花在哪里，再按需抓 Graphics Capture 检查资源。Graphics Capture 的回放可用于定位问题，回放速度不作为实际游戏帧率。活动能力和控件随 Nsight 版本调整，以本机界面和[官方活动说明](https://docs.nvidia.com/nsight-graphics/UserGuide/graphics-capture-overview.html)为准。

## 准备当前工作区

从仓库根目录执行：

```powershell
# 默认准备两个 Minecraft 版本；不会启动游戏。
.\scripts\prepare-nsight.ps1

# 只准备一个版本。
.\scripts\prepare-nsight.ps1 -MinecraftVersion 26.3
```

脚本执行所选 `runClient` 的前置依赖，构建 release 引擎、Java 产物和启动资源，导出直接启动游戏 JVM 的参数，不执行 `runClient`。依赖已经缓存在本机时可加 `-Offline`；缺少依赖时准备失败，不把跳过准备当作可运行。

输出位于 `artifacts/nsight/current/`：

| 文件 | 用途 |
| --- | --- |
| `mc-26.2.args` / `mc-26.3.args` | Java 参数文件，包含 classpath、Loom/Fabric 主类、VM 和程序参数 |
| `mc-26.2.json` / `mc-26.3.json` | 对应版本的 Nsight Launch 字段、路径和环境 |
| `build.json` | Git 状态、原生库 hash、工具链和准备边界 |
| `worktree-status.txt` / `worktree.patch` | 准备时的源码状态及差异证据 |

当前模式引用工作区 `target/release/prime_engine.dll`、adapter 输出、公共 JAR 和 Loom 配置。切换提交、分支、编译参数或改完代码后重新准备；只切换 Git 不会自动更新已生成的产物。下一次准备会更新 `current`，抓取前将元数据与本次报告一起另存。抓取会话期间固定这些产物。

只准备单版时，以本次 `build.json` 的 `launchFiles` 为准；目录中其他版本的旧文件不代表本次已准备。`worktree.patch` 包含已跟踪文件的暂存/未暂存差异，不含未跟踪文件正文；这部分边界由 Git 状态和实际二进制 hash 标识，需要复现未跟踪源码时另行保留。

参数启用 Prime 的 `path_trace` 和 geometry cache，请求 Vulkan、原生 1920×1080，关闭 validation、capture audit 和额外细计时。游戏保存的画质、反弹数、显示设置和存档仍需手动核对，不能把启动参数当作所有设置都已固定。

## 准备固定版本

需要在继续修改代码后保留同一构建，使用新的快照名：

```powershell
.\scripts\prepare-nsight.ps1 -MinecraftVersion 26.3 -SnapshotName baseline-a
```

输出为 `artifacts/nsight/baseline-a/`。脚本拒绝覆盖已有快照；为下一次构建选择新名字。快照固定 DLL、所选版本的工作区 classpath 目录/JAR、启动配置及相关 shader 源码，参数映射到快照路径。单独复制 DLL 会遗漏宿主绑定和 ABI 的版本，不能作为完整构建对照。

固定版本不等于完整场景快照：外部依赖及 assets 仍可能引用 Gradle 缓存，存档、选项和资源包仍来自该版本的 `run` 目录。搬到另一台机器前核对 `build.json` 和版本 JSON 的外部路径，重新准备必要依赖；不宣称它是独立发行包。对照世界使用对应版本的副本，避免跨版本保存改变输入。

历史提交没有这些脚本时，可保留当前仓库作为工具入口，在独立 checkout 中构建历史版本：

```powershell
$comparisonCommit = '填写要比较的提交SHA'
$comparisonWorkspace = 'C:\WorkSpace\prime-compare'
git worktree add --detach $comparisonWorkspace $comparisonCommit
.\scripts\prepare-nsight.ps1 -ProjectDirectory $comparisonWorkspace `
    -MinecraftVersion 26.3 -SnapshotName baseline-a
```

输出位于所选 checkout 的 `artifacts/nsight/`，Launch 字段也指向那里。`-ProjectDirectory` 使用当前工具导出历史项目的实际 Loom 参数，不切换原工作区。历史项目必须仍提供所选 adapter 的 `runClient`；接口或支持版本已改变时，按历史构建入口处理并记录差异。

## 填写 Nsight Launch

打开 Nsight Graphics 的 Launch 配置，从生成的 `mc-26.x.json` 复制字段。Java 路径取 Gradle 实际选择的工具链，不固定某位开发者的 JDK 安装目录；工作目录必须是对应 adapter 的 `run`，以保留正确的存档与设置路径。[官方 Launch 字段说明](https://docs.nvidia.com/nsight-graphics/UserGuide/ui-launch-application.html)。

下表以仓库位于 `C:\WorkSpace\prime_pt`、当前模式和 MC 26.3 为例：

| Nsight 字段 | 值 |
| --- | --- |
| Application Executable | JSON 的 `executable`，即游戏使用的 `java.exe` |
| Working Directory | `C:\WorkSpace\prime_pt\adapters\mc-26.3\run` |
| Command Line Arguments | `@"C:\WorkSpace\prime_pt\artifacts\nsight\current\mc-26.3.args"` |
| Environment | 复制 JSON 的 `environment` |
| Automatically Connect | `Yes`，直接连接游戏 JVM |

26.2 使用其 JSON 和参数文件。固定模式将 `current` 换成快照标签，并复制快照 JSON 的字段。不要只更换参数文件名却沿用另一个 Minecraft 版本的工作目录。

这里直接启动 Java，不把 Gradle daemon 当作游戏进程。参数文件由实际 `runClient` 配置生成，包括 `allJvmArgs`、classpath、主类和程序参数提供者；Loom 原有 classpath `@file` 被展开，避免 Java 参数文件嵌套。Gradle 的 `-P` 属性不会直接交给 Java。

默认环境关闭 `PRIME_PROFILE`、`PRIME_PROFILE_CPU`、`PRIME_PROFILE_TRACE` 和 `PRIME_VK_VALIDATION`，清除强制注入的 Vulkan validation 层设置。已有粗计时仍保留。需要 CPU CSV 或正确性 validation 时另建配置，记录开启的诊断项，不与默认性能抓取混作同一条件。

## 抓 GPU Trace

1. Activity 选择 **GPU Trace Profiler**，Start After 选择 **Manual Trigger**。先用约 1000 ms 的短窗口；需要更多连续帧时再增加，并保持对照窗口一致。
2. 要看 shader 的 pipeline 和源码，启用 **Collect Shader Pipelines**；需要函数/源码采样时，再启用硬件支持的 **Real-Time Shader Profiler**。这些选项和采样配置保存在 Nsight 项目中，随报告记录。[GPU Trace 设置](https://docs.nvidia.com/nsight-graphics/UserGuide/gpu-trace-overview.html)。
3. 从 Nsight 点击 Launch。进入测试存档，确认 Prime 已启用、实际后端为 Vulkan、主 target 是原生 1920×1080。等待地形、资源和管线加载稳定；将加载过程单独作为更新场景抓取。
4. 固定视角、画质和路径预算，使用 Nsight 的手动抓取按钮或界面显示的目标快捷键。快捷键若与 Minecraft 全屏等操作冲突，改用按钮或重新绑定，避免抓取同时改变窗口尺寸。
5. 保存原始 trace，并保存本次启动 JSON、`build.json`、源码差异和场景说明。保留多帧与慢帧，不能只选最快的一帧代表稳态。

记录 Nsight 的锁时钟设置；GPU Trace 的时钟控制会影响实际运行速度。不同版本比较保持相同设置，不能把锁基础频率的抓取 FPS 与未锁时钟的普通游戏 FPS 直接比较。[GPU Trace 时钟设置](https://docs.nvidia.com/nsight-graphics/UserGuide/gpu-trace-ui.html#additional-capture-options)。

实时、离线累积及进入/退出离线分别抓取。离线冻结还会省去部分源准备和场景更新，不能把整帧变化全部归到 BSDF；同时核对每帧 SPP 和已累积样本范围。离线的模式与快照语义见[渲染模式契约](../renderers.md)。

## 抓 Graphics Capture

使用同一 Launch 配置，Activity 改为 **Graphics Capture**，选择手动抓取并从 Nsight 启动。场景稳定后先抓一帧；需要检查前后帧依赖时再选择多帧。保存完整捕获，再打开回放查看事件、状态和资源。

在事件列表选择 Prime 的 Dispatch 或 TraceRays 调用，打开 **API Inspector**，核对所用 shader/pipeline、描述符、push constants、输出图像尺寸和格式、AS 及间接参数。需要进一步检查时沿资源链接进入图像/缓冲区或 Ray Tracing Inspector。将相邻事件的状态导出用于对照。[Graphics Capture 检查视图](https://docs.nvidia.com/nsight-graphics/UserGuide/graphics-capture-ui.html)。

有 GPU 标记时用标记缩小范围；没有标记时按调用、绑定与 shader 入口定位。不要把 Minecraft 的 HUD、手部和呈现调用混作 Prime 输运成本。捕获成功、能够回放和能够查看目标资源分别验证；任一步失败都记录限制，不算游戏正确性验收通过。

## 读时间线和 shader 数据

先判断增加的是 GPU 工作时间、CPU 提交等待还是呈现限速，再比较相同输入的对应区间。

| 观察对象 | 检查重点 |
| --- | --- |
| Compute inline ray query 版本 | 主 Dispatch 时长、线程尺寸、实际 shader 变体、射线/材质路径 |
| 硬件 RT 版本 | Path/Shadow 的 TraceRays、命中阶段、实际间接射线数量 |
| Wavefront 版本 | Init、Prepare、Shade、Advance、Resolve 及有效队列数量；空队列仍录制的轮次 |
| 更新帧 | 几何上传、BLAS/TLAS 构建、纹理/大气更新与 CPU 源准备 |
| 同步与呈现 | queue 提交间隙、依赖、barrier、GPU idle、VSync 和帧率上限 |

API 在时间线上出现 barrier 不足以证明它是瓶颈；结合两端工作、队列依赖和利用率判断。Shader Profiler 中的寄存器数、理论 occupancy、实际 stall/采样和内存流量也分开解释，不把一个局部指标直接换算为整帧收益。RT 分支可进一步看 **Ray Tracing Live State**，检查跨 TraceRay 保存的值；逻辑 payload 字节数不代表驱动实际栈或寄存器成本。[Shader Profiler 指标与 RT live state](https://docs.nvidia.com/nsight-graphics/UserGuide/shader-profiler.html)。

shader 默认编译参数为 `-O3 -g3`。保留优化，检查采样对象的实际 shader hash、源码关联和 compiler 版本；旧提交的参数可能不同，比较前统一并记录。没有源码关联时先检查实际加载的 DLL、调试信息、Collect Shader Pipelines 和源搜索路径，再使用反汇编分析。`-g3` 产物存在并不保证每个优化后指令都有唯一源码行。

## 对照记录与证据

每次抓取在 `artifacts/nsight/reports/<案例标签>/` 保存原始报告、启动配置、构建记录、日志、截图及导出表格。`docs/` 维护流程和长期契约，具体提交列表、单次设备结果和待验证推测留在 artifacts。

至少记录：

- Git 提交、未提交差异、DLL SHA256；实际 Java/classpath、Rust/Slang、Nsight 和驱动版本。
- GPU、实际原生 target 尺寸、存档/资源包、相机/FOV、天气/太阳时间、视距、几何与灯光规模。
- 渲染模式、画质、总反弹预算、SPP、种子/样本序列、VSync、帧率上限及窗口焦点状态；无法固定的项明确标出。
- validation、CPU/GPU 统计、采样和锁时钟设置，预热判据、抓取范围、重复次数和全部离群帧。
- CPU 与 GPU、稳态与更新各自的结果；看见的事实、可能的原因和未覆盖范围。

先比较输入与配置，再比较性能。用相邻版本或可控变体定位灯光、BSDF、几何、调度等变化；一个提交同时改变多条路径时不能单独归因。隔离算法成本可配合[光源采样测试](light-sampling.md)等无窗口夹具，但夹具结果不能外推为完整游戏保证。

保存 Nsight 项目便于重复使用。Current 项目的路径保持不变，但每次准备都会更新其构建；Baseline 项目使用独立标签。比较时使用相同活动和采样设置，并重复检查实际加载版本。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 连接到 Gradle 或错误的 Java | 从生成 JSON 取 `java.exe` 和参数，直接启动游戏 JVM；核对目标进程 |
| 切版本后画面或 shader 没变 | 结束旧游戏，重新准备；核对参数中的 DLL/classpath 与 SHA256 |
| 世界、资源包或设置不对 | 核对 Working Directory、所选 MC 版本和 `run` 目录 |
| 只有反汇编，没有源码 | 核对 `-g3` 构建、shader 关联、采集选项及源路径；快照不沿用其他版本源码解释 |
| `ERR_NVGPUCTRPERM` 或性能计数器不可用 | 按[NVIDIA 计数器权限说明](https://developer.nvidia.com/nvidia-development-tools-solutions-err_nvgpuctrperm-permission-issue-performance-counters)配置本机权限后重试 |
| 抓取缓冲区不足 | 缩短窗口或按 Nsight 提示调整事件/采样容量，记录变更；保留失败日志 |
| 抓取期间 FPS 与平时不同 | 核对采样、锁时钟、validation、VSync 和呈现设置，分别记录两种条件 |
| 历史版本导出失败 | 核对该提交的 adapter、Loom 和构建入口；不将部分输出冒充可用参数 |

## 维护约定

改变 Minecraft 适配器、Loom、Java 启动参数、native 路径、shader 默认参数或 Nsight 操作入口时，同步更新本手册与[准备脚本](../../scripts/prepare-nsight.ps1)。参数从实际运行配置导出，不长期维护手抄的完整 classpath。

Loom 的 JVM argfile 格式改变时，导出工具应明确失败并更新展开逻辑，不能静默丢弃未知 VM 参数或留下嵌套 `@file`。

修改导出工具后验证所支持版本的无窗口准备、Current 与 Snapshot 路径映射、元数据和 Java `--dry-run`；`--dry-run` 不执行 main，只验证 JVM 启动配置。实际 Nsight 连接、抓取、回放与游戏行为仍由用户手动验收。日志放 artifacts，不在手册中积累某次运行的“已通过”记录。
