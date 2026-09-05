# Lambert 首版性能与旧实现结构对比

分析日期：2026-09-05。用户确认画面正常；两次捕获使用同一 GPU，但场景不同，频率不确定，分辨率可能因全屏/最大化不同而变化。因此本文比较编译结果和状态结构，只用新捕获的样本定位新实现内部热点，**不据此计算加速比或判断某阶段比旧版更慢**。

结论：新实现已经显著缩小着色与续传的状态压力。当前可见项目 shader 的最高 live 为 **87**，在 any-hit；shade 为 **62**，其太阳阴影调用仅报告 **4 B** live state。用户随后明确 Lambert 只作为性能基线，当前不推进针对它的显存编码优化；优先解释大气查询和 any-hit 的压力，并记录后续功能的寄存器增长。进一步核对实际 SPIR-V 的结果见[轻量渲染器寄存器记录](C:/Users/linlin/.codex/worktrees/0039/prime/docs/轻量渲染器寄存器记录.md)。

## 数据与口径

| 新输入 | 数据行数 | SHA-256 |
| --- | ---: | --- |
| [lambert_pipeline.csv](C:/Users/linlin/Desktop/tmp/lambert_pipeline.csv) | 42 | `0d1e3f28999f953cb05fe5117a79a199c3ffe4aafaff319111d9144aa6485783` |
| [lambert_rt_live.csv](C:/Users/linlin/Desktop/tmp/lambert_rt_live.csv) | 6 | `3200bbdbf6069c5ea1e9309fb126159ae7a0d63771d6f33ec3640e70e8781e97` |
| [lambert_topdown.csv](C:/Users/linlin/Desktop/tmp/lambert_topdown.csv) | 603 | `c92628804a1a2b14eb2a2d60788dfe28371fc5d29ba037423d4ef3816df6866d` |

旧输入的行数、哈希与解析结果已复核，见[旧实时渲染器寄存器分析](C:/Users/linlin/.codex/worktrees/0039/prime/docs/旧实时渲染器寄存器分析.md)。当前源码基线 HEAD 为 `6a114e4c8fbb293d69956b1c645801c57db6bacc` 加工作区修改；没有捕获二进制的完整构建清单。新 CSV 的主要调用行与当前源码对应。

两份 pipeline 的样本占比各自合计 100%。pipeline 为 43 列，复合列的表头不能直接作为普通列名映射；本文用稳定前部字段与末尾 18 类 stall 计数，逐行复核 top stall 比例。两份 Top-Down 均为表头 21 列、记录 19 列，含 `###ERROR###`；执行指令数和 active-thread 计数均不可用。函数父子行是 inclusive 关系，不能重复相加；导出还包含独立的 `Multiple shaders...` 归属，main 行不必等于 pipeline 行。

`# Reg` 是分配寄存器，live 是存活峰值，`# Warp` 是独立运行时的理论上限；RT live state 是跨调用保存的值，不是 payload 大小。scoreboard 停顿通常归到等待结果的消费指令，需要沿依赖回找生产指令。因此源行标在整数解码或矩阵乘法上，并不证明这些算术本身昂贵。[NVIDIA Shader Profiler](https://docs.nvidia.com/nsight-graphics/UserGuide/shader-profiler.html)

CSV 没有可用于比较的 GPU 毫秒；`Avg. Warp Latency` 也不是阶段耗时。以下所有样本百分比均以**本次捕获的总 PC 样本**为分母，stall 百分比则以对应行的 stall 计数为分母。

## 寄存器与跨调用状态：已验证的变化

| 工作范围 | 旧 live / 分配寄存器 | Lambert live / 分配寄存器 | 理论 warps：旧 → 新 |
| --- | --- | --- | --- |
| 着色与散射主体 | scatter 126 / 128；direct 118–124 / 79–128 | shade **62 / 69–70** | scatter 16 → shade **28** |
| 续传 trace 的 raygen | bridge 65 / 63–90 | trace **47 / 82** | 20–32 → 20 |
| 世界 closest-hit | 61 / 88 | **59 / 82** | 20 → 20 |
| 世界 any-hit | 84 / 124 | **87 / 96** | 16 → 20 |
| 非 opaque 阴影 any-hit | 102 / 128 | **86 / 96** | 16 → 20 |
| opaque 阴影 any-hit | 64 / 99 | **57 / 85** | 16 → 20 |
| 结果整理 | branch-resolve 115 / 120 | resolve **26 / 26** | 16 → 48 |

新 camera 为 **24 / 26，48 warps**，只负责初始化与发出索引，不能与旧 camera-trace 当作同一工作量直接比较。上表其他跨阶段行也只是工作范围的结构对照：移除了 OpenPBR、透明介质、局部灯光、多分支和 tail，不能把全部变化归功于 wavefront 调度。

有三点值得保留：

1. 着色主体不再达到 126 live，但整个新路径的最高值是 any-hit 的 **87**，不是 62。世界 any-hit 的 live 甚至比旧捕获高 3；它的分配量却从 124 降到 96，说明两个指标不能混用。
2. closest-hit 从 61 到 59，基本仍是同一量级。Lambert 数学简化不会自动消除几何、纹理与 guide 数据读取。
3. trace 只有 47 live，却分配 82；只有极少源码的 miss / shadow closest-hit 也分配 82。驱动生成的 RT 代码与调用约束需要一起看；目前不能断言减少 raygen 的几个源级变量就能跨过 occupancy 档位。

新 RT Live 导出只有两个调用点：

| 调用 | 报告的保存状态 | 可确认来源 |
| --- | ---: | --- |
| shade 的太阳阴影 trace | **4 B / 1 个值** | `bounce`，源行 31；调用行 56 |
| trace 的世界 trace | **0 B** | 由 hash `ac5ce9ae62c86424` 关联，导出没有源行 |

旧独立直接光的单个阴影 context 为 **89–186 B**，tail 为 **357 / 386 B**。新 4 B 状态非常小，说明延迟加载材质与 throughput、将续传和结果置于显式路径记录的策略确实反映在编译结果中。旧 callsite 父行 743 B 是两个 context 相加，不能与 4 B 当作单次峰值对比。

这并不表示新世界 payload 的 64 B、阴影 payload 的 12 B 或硬件 RT 栈消失了；0/4 B 也不证明重新加载和重算没有成本。当前没有必要为消除最后一个 `bounce` 再增加队列字段或 dispatch。

## 新捕获内部：当前主要成本在哪里

| 阶段 | 总样本占比 | 主要 stall | CSV 行 |
| --- | ---: | --- | ---: |
| NVIDIA SDK | 23.44% | 不展开 SDK 内部归属 | 2 |
| shade | **18.82%** | LGSB **83.43%** | 3 |
| Traversal | 16.84% | LGSB 79.01% | 4 |
| Scheduler | 8.32% | LGSB 34.95% | 5 |
| world closest-hit | 8.11% | LGSB 75.23% | 6 |
| trace raygen | 7.25% | LGSB 81.81% | 7 |
| world any-hit | 4.17% | LGSB 68.73% | 8 |
| shadow any-hit | 3.12% | LGSB 67.19% | 9 |
| camera | 2.36% | LGTHR **44.79%** | 10 |
| resolve | 1.66% | LGSB 88.40% | 13 |

shade 的 NOINST 只有 **0.48%**。本场景下，它没有呈现旧 tail 的 NOINST 77.55% 那种停顿结构；但不能从两个不同场景推导指令缓存命中率改善了多少。当前大量 LGSB 指向读取依赖，LGTHR 提示相关指令发射压力；尚不能断言显存带宽饱和、缓存容量不足或发生 spill。

SDK 占比也不能与旧 4.87% 相除来判断降噪变慢。CSV 只标记 `NVIDIA SDK`，没有单独给出 NRD/RR 各产品的毫秒；不同场景、输入 extent 和采样分母都会改变占比。

### 1. shade 的主要热点不是 Lambert BRDF 算术

Top-Down 中值得区分的范围：

| 范围 | 总样本占比 | live | 说明 |
| --- | ---: | ---: | --- |
| `[Self: main]` | 8.00% | 59 | 混合路径记录读写、控制与未独立归属代码，不能全算作内存访问 |
| `primeLambertAppend` | **3.706%** | 47 | 约占该 shade main 归属样本的 **19.8%** |
| 太阳 radiance / 大气透射 | 1.468% | **62** | shade 寄存器峰值 |
| 天空环境 | 1.466% | 48 | LUT 与方向变换 |
| STBN `primeDirectSample2D` | **1.414%** | 47 | 两处采样合并归属，约占该 main 样本的 **7.55%** |
| 星图 | 1.392% | 61 | 包含纹理采样与大气透射 |
| `primeLambertDirection` | 0.603% | 48 | 其内部 0.565% 在局部到世界方向转换 |

来源为 Top-Down 第 4、5、8、24、34、38、64 行。表内不把子函数再与父函数求和。

**队列追加是第一项容易形成小实验的热点。** [state/lambert.slang](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/state/lambert.slang:24) 每条续传路径对同一个计数器执行 `InterlockedAdd`，等待返回 slot 再写索引。其子函数 `primeLambertQueueWord` 占 2.201%，LGSB 99.43%；这里标在地址计算上的等待，可能来自 atomic 的返回值或其他前序加载，不应把 2.201% 解释成几个整数加法很慢。

值得检查最终指令是否已经聚合 atomic。若没有，可做 subgroup 内一次预留、各 lane 按 prefix 写入的 A/B：保持每条路径恰好一次追加、原队列容量与像素身份，无额外大型缓冲。充分活跃时可减少 atomic 次数，但不能承诺固定倍数帧收益。空 subgroup、部分活跃 lane、提前终止与全部存活都要验证。

### 2. 62 live 的峰值来自共享大气查表的生命周期叠加

调用链为：

`primeResolveSampledSunRadiance → primeSunRadiance → primeAtmosphereDistantTransmittance → atmSample2D`

[大气透射](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/service/atmosphere/trace.slang:26) 同时消费 low/high 光谱结果，随后转换为 Rec.2020；[atmSample2D](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/model/atmosphere/sky.slang:375) 是四次 image load 加手工双线性插值。两张表合计八次 texel load，叠加坐标、插值中间值、前一张表的结果和外层后续仍需使用的光照数据。星图的透射调用也达到 61 live，支持共享读取路径值得检查。

不能按源 struct 大小精确分摊 62 个寄存器；没有逐指令 liveness 证据证明某个 `float3` 一定跨越全部查询。优化顺序应是复用尺寸/坐标、缩短插值中间量生命周期，再评估 sampled-image 硬件过滤。后者需要格式功能、采样边界和插值误差验证，不能当作逐位等价替换。若共享 helper 的改进成立，可以惠及旧渲染器。

目前不建议仅为这个 62 live 的峰值拆出独立 sun kernel。新 shade 的 shadow live state 已经很小，再拆会增加记录、读写和调度；应先证明寄存器分配下降能抵消这些成本。

### 3. any-hit 的 86/87 live 是下一处需要缩短的几何展开

世界与阴影 any-hit 共用 [primeLambertRejectCandidate](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/service/trace/lambert_candidate.slang:10)。它先确认精确源身份，opaque class 直接接受；其他 class 载入 primitive、完整 `PrimeHitTriangle`、surface relation，再解析最终 recipe 与 alpha。

峰值位于 `primeHitTriangle` 和 `primeResolveSurfacePrimitive`，分别为世界 **87**、阴影 **86**。同一几何函数在 closest-hit 为 **59**。这说明候选解析的调用上下文也推高峰值，不能只按函数本体或 payload ABI 大小解释。

这里有一个具体且不必降低几何精度的改进方向：**按 UV 消费需求推迟 position fetch。** 当前 [primeInterpolateUv](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/service/trace/hit.slang:277) 的常量 UV 与普通三角形重心 UV 都不需要 `localPosition` 或 `geometricNormal`；只有 repeated UV 分支需要几何投影。候选接受判断也不需要生成命中点用于下一条射线。

因此可考虑先解析身份、relation 和 recipe；普通/常量 UV 的 cutout 只用其所需数据，真正需要 repeated UV 的路径才取顶点。CSV 没有按 UV 类型统计调用次数，也不能证明编译器尚未自动推迟部分加载；应先检查最终指令，再用 A/B 确认可省成本。必须同时处理 overlay 主材质与回落材质、双面关系、动态几何及实际选择的 recipe，不能简单对整个 transmissive class 跳过 coverage。closest-hit 仍需提交给 BLAS 的 f32 顶点来生成权威命中位置。

两类 any-hit 的 alpha 读取分别有 0.747% / 0.867% 总样本；世界 closest-hit 的几何获取约 1.296%，primitive 解析 1.267%。这支持先减少每个候选的无用展开，再考虑取消跨 cluster 去重或恢复每三角形属性副本；当前数据没有推翻既定显存方案的依据。

closest-hit 的 `primeLinearRec2020ToLinearBt709` 归属 1.044%，其中 LGSB 99.36%。它处在 tint 的颜色调制语义中，也可能等待上游颜色读取。不要据此删色域转换或把该样本全部归成矩阵 ALU 成本。

### 4. STBN 可以保留，优先检查读取局部性

STBN 没有进入主要峰值：采样函数 live 47，地址函数 41。解码行的 LGSB 97.67% 更符合等待 buffer load，而不是两次 u16 解包很贵。整表虽为 12 MiB，但单帧固定 frame、两个 bank 的逻辑切片共 **128 KiB**；不同反弹只改变切片内空间偏移。因此整表容量不等于这一帧需要访问的工作集，也不能据此保证缓存全命中。

续传索引的全局追加顺序会影响像素访问和 STBN 的局部性，subgroup 追加可能同时改善同组索引布局，但仍需测量。应先保持现有随机映射检查访存与调度；这份 CSV 没有理由要求换掉 STBN，也不能单独证明它的时空统计质量。

## 暂缓的显存编码设想

本节保留容量计算供未来参考，不作为当前实施顺序。方向/albedo 槽复用依赖 Lambert 的特殊性质；应先确定目标材质与状态需求。

按当前源码预算，path 128 B 加双索引队列 8 B，即 **136 B/像素 + 32 B**，另有对齐。不包含几何、纹理、重建图像和驱动私有分配。以下是固定 extent 的容量计算，**不是本次捕获测得的总显存**。

| 布局 | 1080p | 2560×1440 | 3840×2160 |
| --- | ---: | ---: | ---: |
| 当前 128 B path + 8 B queue | 268.95 MiB | 478.13 MiB | 1075.78 MiB |
| 112 B path + 8 B queue | 237.30 MiB | 421.88 MiB | 949.22 MiB |
| 96 B path + 8 B queue | 205.66 MiB | 365.63 MiB | 822.66 MiB |

[记录](C:/Users/linlin/.codex/worktrees/0039/prime/shaders/contract/lambert.slang:24) 的方向与 albedo 可按阶段共用一个 16 B 槽：

- trace 前：槽内是待追踪方向。
- hit 后：Lambert 后续只需 albedo；closest-hit 的朝向判断已完成，不再消费入射方向。
- miss 后：保留方向供环境求值，不消费 albedo。
- shade 完成：albedo 被 throughput 与直接光消费完，槽改写为下一条方向。

这可把 path 从 **128 B 降至 112 B**，4K 节省 **126.56 MiB**。之后若把四个保留的 `.w` 槽与现有标量字段重新排布，信息量允许固定 **96 B** 记录，再节省相同容量；所有 float 和整数仍可保持 32 位。应保持字段访问集中和阶段语义明确，验证实际 ABI、miss/终止/首反弹与全部重建输出后落地。这种 alias 依赖 Lambert 不使用入射方向的性质，未来非 Lambert BSDF 不能直接继承同一生命周期假设。

不要把记录缩小百分比直接当作带宽或帧耗时下降百分比：各阶段只读写部分字段，128/112/96 B stride 的事务与缓存行为也不同。若需要继续优化访存，可比较少量固定平面布局，但不必马上引入大量小缓冲或变长编码。

## 建议的实验顺序

1. **固定寄存器基线，随功能扩展记录增量。** 同时记录 live、分配量、理论 warps、RT live state 和实际耗时；先区分 shader 本体与 RT 上下文，避免把 87 当作 alpha 测试自身的局部变量需求。
2. **检查共享大气采样 helper。** 核对手工插值、坐标重复计算和外层数据生命周期；可验证的通用改进同步旧路径，数学或过滤变化另做行为与性能验收。
3. **审查 any-hit 的候选解析。** 优先让不需要投影 UV 的候选避免 position fetch / 法线展开，保留精确身份和 coverage 语义。它针对当前最高 live，并可减少逐候选成本。
4. **保留队列追加为独立性能实验。** 保持 STBN 随机映射与像素所有权，先检查编译器是否已聚合 atomic；获得逐反弹 GPU 时间后再决定调度合并。当前 28 次 RT dispatch 的全部开销不能从聚合 CSV 推算。

本次不能判断哪一跳最慢、最后几跳是否值得独立调度、12 次反弹到底运行了多少有效路径；CSV 把相同 shader 的多次调用聚合了。下一轮 A/B 固定场景/机位、精确内部 extent、资源包、重建模式与 shader 构建，记录 PT 总 GPU 时间、每跳 trace/shade 时间、有效队列长度，以及寄存器、RT state 与显存。频率不固定时交替多次取样，并同时保留频率信息。

本次仅完成分析，未修改渲染代码，也未把不同场景的占比变化当作性能验收。
