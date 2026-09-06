# 完整实时 Wavefront 调度

完整实时渲染器用无 RR 的窄 megakernel 处理前置 delta 链，主体全程使用 wavefront。
单个阶段只负责自身所需的求交、选灯、直接光或散射，不使用寄存器内的 tail 传输循环。
OpenPBR 数学与独立的[离线传输](离线光传输契约.md)不由调度层重新解释。

## 阶段与预算

前置共 11 次 dispatch：camera、visible direct、surface split、串行 missing guide / delta
walk，以及 landing 选灯、direct、scatter。未选分支的 guide 必须先于选中照明链完成，
避免共享 detached 暂存冲突。landing 完成首次常规散射后将局部次级计数清零。
主体每轮固定为：

```text
secondary_trace → secondary_light_select → secondary_direct → secondary_scatter
```

最后分别执行 branch resolve 和 noisy output resolve。

| 设置 | 默认 | 语义 |
| --- | --- | --- |
| delta 上限 D | 12 | 前置透明链事件数，范围 1–64，无 RR，独立于主体计数 |
| 最小轮数 M | 2 | 常规路径开始 RR 前的轮数下限，范围 1–8 |
| 最大轮数 B | 12 | 主体配置上限，范围 1–64；实时采用 K=max(M,B) |

完整实时的 dispatch 数为 `11 + 4×(K−1) + 2 = 4K+9`；默认 57，K=1 时 13，K=64 时
265。空队列不启动 raygen，但命令解析和 barrier 仍有成本；调度不逐轮 CPU 读回队列。
已有配置的显式数值保留，缺省与恢复默认使用上表。

累计 K 次常规散射后不再追踪端点，不增加轻量实现的 terminal 查询。前置 delta 达到 D
采用已有 guide fallback。两类渲染器的有限阶截断位置不同，比较图像必须保留这一差别。

## RR 与队列所有权

scatter 写回后、发布后继前，在窄函数中只加载控制字、throughput 和 etaScale，使用
`min(maxRGB(throughput)×etaScale,1)`，存活权重除以该概率。完成轮数达到 M 且尚未达到 K
才考虑 RR；为保留首个次级 guide 查询，即使 M=1 也不在首次常规散射后执行 RR。
K=1 直接截断。待定透明 guide 阻止提前 RR，landing 发布前先完成 guide 封口与计数重置。
随机地址由写回后的局部次级计数确定，RR 不在其他阶段重复执行。

两条主体队列交替使用。scatter 是源队列的最后消费者，首个 invocation 只清零源命令的
宽度字，再向另一队列追加存活路径。不能等下一轮求交清零：后继全灭时该 dispatch 不启动，
遗留计数会重新处理旧路径。命令第四字的低 8 位保存 D、次低 8 位保存 M、最高位表示 overflow。

subgroup 合并发布只用于允许的阶段；HitObject/SER 之后采用标量发布的边界见
[路径追踪性能约束](路径追踪性能约束.md)。area queue 与串行 guide 按阶段复用，透明
resolve 队列跨主体轮次保持存活，不能按单轮临时队列清空。

## 路径与暂存

每像素只有一个 112 B 完整运输记录，透明保留透射/反射两个逻辑信号与 guide。
另有 280 B 阶段暂存和九个物理 uint 队列索引，七条 16 B indirect command 为全帧共享。
N 个像素的 backing 为 `align256(112N) + 316N + 112` bytes。
字段与 offset 只以 `shaders/abi.json` 为准；互斥阶段复用与重建语义见
[透明渲染与实时重建](透明渲染与实时重建.md)。

## 验证边界

预算与 RR 行为测试覆盖 M=1..8、B=1..64、全灭队列、边界计数和有限阶能量期望。
host/产物检查验证阶段数量、命令元数据和 shader 集合，GPU 测试验证发布与复用。
运行时还需观察暗处深反弹、大量 miss、透明 guide 和 K=12/64 的 GPU 时间与寄存器。
窄阶段降低跨求交的状态重叠，代价是更多 dispatch、barrier 和中间读写；性能结论必须
由同条件抓帧建立，不能从 SPIR-V 大小推断。
