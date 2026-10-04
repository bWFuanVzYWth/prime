# 诊断与原始性能数据

诊断是可按需启用的基础设施，开关与 shader 的颜色、法线、深度预览分开。游戏内“视频设置 → Prime PT 诊断”只提供“录制性能 JSON”开关：开启时同时启用 CPU/GPU 统计与原始事件采集，关闭时停止诊断并后台导出，不保留没有完整数据的独立即时统计状态。退出世界或关闭 native owner 同样结束当前会话，在对应版本的 `run/artifacts/performance/` 导出一个 JSON。采集开关不跨世界自动续录。

慢帧不再逐帧向终端输出长日志；尖峰、失败和离群值保留在原始事件里，不平均、去重或只保存超过阈值的帧。终端只提示采集开始、导出位置和失败。旧 JVM CSV 属性是显式兼容入口，不能替代此原始事件协议。

## 关闭路径与职责

`prime_diagnostics` 负责独立的会话、单调时钟、任务上下文、线程缓冲和紧凑序列化，不依赖 Minecraft 或 Vulkan。`prime_engine` 管理采集生命周期与 owner-thread FFI；`prime_minecraft` 和 `prime_scene` 在实际任务边界记录，私有 Rayon 工作池显式传播 frame/parent 上下文，同步任务结束后 flush。Java 公共层提供开关、宿主 scope、时钟对齐和后台导出；版本适配器绑定实际帧与源准备入口。GPU 查询由 `prime_vulkan` 依队列和完成证明管理。

全部诊断关闭时，生产宿主路径不创建性能查询池、不录制查询 reset/timestamp，不读取性能查询，也不增加 shader pass、诊断 plane 或 shader 状态。功能性的 AS 压缩查询、DLSS/FG 同步、曝光时间和正常资源退休仍是渲染工作。关闭过程中已有查询池依最后使用 serial 退休；这属于先前已启用工作的清理。

CPU 未采集时的 scope 只检查空上下文，不读事件时钟、不分配事件或摘要、不注册线程、不获取采集锁。粗 CPU 快照和额外上传/OMM 统计由诊断开关控制；已有生产计数和生命周期判断仍有少量成本。显式的 legacy 开发属性可能单独开启其对应统计，比较关闭开销时需同时关闭这些属性。这里描述代码路径和行为测试契约，不据此承诺实际整帧速度。

采集开始和配置事件保存实际 `ls`：0为Grid、1为功率距离Tree、2为TreeSphere。它来自帧边界应用后的有效设置；游戏内切换会记录新的配置值。Grid更新范围为 `lg.*`，两种树的世界表为 `lt.*`，球界局部树为 `ls.local`；共同的 `geom.lights` 是父范围，不能与子任务重复相加。一次切换的已发布灯页重建记录为 `lights.switch`，其摘要保存目标 `ls` 和页数 `pg`；完整切换还包括宿主等待、管线和输出重建，应与稳态分开分析。

## 数据、线程与时钟

配置摘要 `integrator` 为0时选择普通 PT，为1时选择 ReSTIR PT Enhanced。ReSTIR 的 `gpu.k1`、`gpu.k2`、`gpu.post` 分别覆盖完整初始路径生成、时间/空间重采样和 resolve；它们与普通 PT 的同名区间有不同内容，比较时按实际后端解释，不能只比较某个同名阶段。

每个 CPU scope 保存实际起始时刻、时长、frame/task/parent/thread 身份、状态和结构化摘要。摘要使用短任务名与整数计数，记录已有输入和结果，不为摘要另做全场景扫描。任务在完成时进入队列，因此文件顺序是完成/排空顺序，分析须按 `s` 和身份重建时间线。多线程任务可重叠；子任务时长之和不是父任务墙钟时长，父/子与粗/细 GPU 阶段也不能直接相加。

Java 异步派发时使用 `Diagnostics.captureContext()`，工作线程通过 `Context.enter()` 安装派发时的 frame/parent，并在 scope/guard 结束时恢复原上下文；不会因为执行跨到下一帧就改标签。Rust 私有工作池自动做同样的传播。所有 scope 必须在所属线程按嵌套顺序关闭。

Java 使用 `System.nanoTime()` 的整数纳秒；native CPU 使用本次 recorder 的 `Instant` 起点相对纳秒。二者保留独立时钟域。`sync` 保存 Java 调用前 `jb`、调用后 `ja` 与返回的 native `n`，只给出原始校准区间；不能把中点近似当作精确共同起点。重新 attach native 后可能产生新的 recorder 身份 `r`。

GPU 保存队列原始 ticks，不改成浮点毫秒；同时保存队列身份、真实 submission serial、timestamp valid bits 和 period。GPU `obs` 是 CPU 观察查询结果的 native 时刻，不是 GPU 执行起点。当前没有 CPU/GPU calibrated timestamp 对齐，GPU ticks 不能直接减去 CPU 纳秒。时间差需要按有效位取模，再乘 period；长跨度可能超过一次回绕，不能仅凭两端推断回绕次数。

停止时只读取已经证明完成的提交。尚未完成或未提交的 GPU 阶段记录 `pending` / `unsubmitted`；部分写入、查询不可用也保留状态和已有端点。缺失端点使用 `null`，不伪装成实测零。导出不会为未提交工作额外等待；资源仍按实际提交/取消协议退休。

## 紧凑 JSON v1

输出不缩进、不截断纳秒、不舍弃原始事件。名称和摘要键通过字典 ID 复用，整数保留原值。JavaScript 默认 Number 不能精确表示所有64位整数，分析工具应使用支持精确整数的 JSON 解析器；GPU queue handle、ticks、Java 时钟和 submission 身份也适用。

顶层 `v` 为协议版本，`id` 为采集 UUID，`utc` 是开始墙钟时间，仅作标签；`why` 为停止原因，`partial` / `err` / `reject` 描述完整性，`waits` / `wait_ns` 记录写入背压。`clk` 声明时钟域，`sync` 保存原始校准样本，`j` 保存 Java 名称/线程字典及 `ev`，`n.chunks` 保存 native 排空块。

native 块的 `r` 为 recorder 身份，`dict` 使用本会话稳定 ID；`nb` / `kb` 是本块新增名称/摘要键的起始 ID，`n` / `k` 为新增值，`t` 为新增线程元数据。按块顺序累计字典，不把每块当作独立完整字典。线程字典行的 `r` 保存 Rust 线程身份字符串，用于区分线程；它不声称是操作系统 TID，也不能直接与 Java threadId 合并。

| CPU 字段 | 含义 |
| --- | --- |
| `i`, `p` | 任务 ID、父任务 ID；无父任务为 `null`，Java/native ID 分域 |
| `f`, `t`, `n` | 逻辑宿主帧、线程、任务名称 ID |
| `s`, `d` | 所属 CPU 时钟域的起始纳秒与时长纳秒 |
| `ok` | Java 为成功布尔值；native 为1完成、0失败、2 panic |
| `a` | 简短摘要；native 为 `[keyId,value]` 列表，Java 为短键计数对象 |

| GPU 字段 | 含义 |
| --- | --- |
| `f`, `p`, `n` | 录制时绑定的帧、CPU 父任务与名称 ID，不是收回结果时的帧 |
| `b`, `e` | 起止原始 ticks，缺失为 `null` |
| `v`, `h` | timestamp valid bits、每 tick 纳秒数 |
| `q`, `x` | 队列身份、真实 submission serial |
| `st`, `obs` | 完成/缺失状态、CPU 观察时刻 |

阶段覆盖源准备、native 调用、场景翻译、工作池批次、资源准备与上传、AS/描述符/命令录制；GPU 区分 preparation、total、K1、K2、post、离线、RR、星图、曝光与显示。只有实际执行的阶段有事件，未走某分支不写虚假零样本。阶段和摘要覆盖随实现扩充，`v` 只约束记录结构，不保证所有版本都含同样任务。

静态更新和采集自身的 CPU 细分保留实际父子关系，名称在字典中复用：

| 范围 | 事件与测量内容 |
| --- | --- |
| 静态几何 | `geom.plan/compile/material/pack/omm/as/cmd/publish/retire/compact/lights/dir` 分开规划、surface 编译、材料分配、打包、OMM 绑定、AS 准备、命令准备、cluster 发布、退休、压缩、光页收集/网格更新与目录发布；现有 `cpu.batch/chunk` 属于对应工作阶段 |
| 发光面上传 | `light.page/encode/summary` 测量整页、发光面编码与 CPU 光源摘要；`buf.dev/dst/stage/write` 分开 device-local 上传总范围、目标分配、staging 分配与 CPU 写入；`upload` 仍只测命令录制 |
| LightGrid CPU | `lg.cpu/diff/world/world_alias/ids/apply/cell_alias` 分开页差异及新页表准备、全局页规划与 alias、ID 租用、光源/cell 引用更新、dirty cell 排序/权重/alias |
| LightGrid 资源 | `lg.update/input/stage/refs/local/world_stage/hash/pages/grow/publish` 分开输入收集、各类表 staging、实际 buffer 扩容与命令发布；`light_grid_ranges` 是其命令录制子事件 |
| 采集传输 | `diag.flush/read/drain/alloc/copy/array/utf8/queue/stop` 分开逐帧排空、native 读取总范围、原生排空/序列化、FFM 分配、缓存块复制、byte[] 复制、UTF8 解码、后台队列写入及最终收尾 |
| 全局时间历史失效 | `restir.history.reset` / `rr.history.reset` 仅在实际丢弃已接受历史时记录 `reason`；用于对齐闪烁与失效事件，不证明闪烁由该事件造成 |

新摘要取已有长度或仅在启用时于原有循环中累计：例如 `emit/pg/dc/ent` 表示发光面、光页、dirty cell、alias 项数，`bytes/ranges/rows/copies` 表示字节、范围、目录行和复制项。不为摘要新增全场景扫描，也不对每个光源或 cell 生成独立事件。

采集传输事件只写 Java 流，不能再产生 native 记录，避免对排空自身递归排空；最终收尾显式绑定已停止请求的旧会话。`diag.drain` 的长度查询可能复用已缓存块，因此它描述本次调用实际成本。scope 时长在提交该事件前结束，不包含自己的事件编码/入队全部成本；其他未覆盖宿主工作、GC/safepoint 和实际 Present 仍不能从这些阶段推断。

`frame` 摘要的 `interval` 是 PT hook 开始到开始间隔，包含上一帧 hook 和当前源提取；当前 static_prepare 主要影响下一次间隔。首帧可能沿用采集前的粗时钟起点，分析会话内间隔应优先用相邻 `frame.s` 的差，避免把采集边界空窗当作某个阶段耗时。

GPU preparation 从 PT 帧的起始 marker 开始，独立的早期 `prepare_host_resources` 提交目前只有 CPU 任务事件。宿主原版绘制、HDR 最终合成、Present 与实际 FG 执行不在此 GPU 阶段覆盖内；不能把 `gpu.total` 当作整个游戏或显示帧时间。

## 内存、导出与失败

native 逐帧排空已完成事件，Java 通过有界队列交给本次采集专用后台 writer，使用 NDJSON spool 保存再流式组装最终 JSON，不把整场会话加载到内存。队列满时背压，保留样本并累计等待时间；采集负载本身是测量条件，不能从任务时长中猜测扣除。停止时仍在运行的 Java scope 完成后进入同一会话，writer 等待这些 scope 后结束。

队列按记录条数限额，单个 native 块仍随该帧任务量增长，因此不是固定字节上限。停止原因与 writer 损坏状态分开；异常结束的已有 scope 仍保存失败尾部，不因为 `partial` 说明而拒收。

正常结束后保留最终 `.json` 并删除中间 spool。I/O 失败明确报告，尽可能保留 `.partial.json` 与可恢复的 spool；进程被强制终止时最终 JSON 可能尚未生成。`partial=false` 说明采集写入完整，不表示 GPU 所有任务都已完成；GPU 状态仍须逐项检查。

诊断 configure/frame/clock/read 控制失败会清除采集请求、尽力排空并标记 `partial`，独立通知失败，不单因诊断错误退役正常 renderer。真实渲染的设备丢失仍按渲染错误处理。默认恢复按钮结束本次会话的采集。

FFI 使用 ABI v12，owner-thread 控制和排空缓冲协议见 [ABI](abi.md)。固定场景、原生1920×1080、种子、画质、预算、硬件和工具链；分开 CPU/GPU、实时/离线、稳态/更新以及采集开销。游戏开关、world exit、后端切换、重新采集和实际 GPU 查询由用户手动验收，命令见 [CONTRIBUTING](../CONTRIBUTING.md)。
