# 完整实时 Wavefront 调度

2026-09-06。用户要求完整实时删除 tail，主体全程采用 wavefront；前置无 RR 的 delta 窄
megakernel 保留。轻量版灯光与透明扩展已在 `ddc888a0` 提交。本次不改变 OpenPBR 数学，
离线保持原有调度与线性 RR，只随公共设置降低默认最大轮数。

## 阶段和计数

camera、visible direct、surface split、串行透明 guide / delta walk、landing 选灯 / direct /
scatter 沿用原来的 11 次前置 dispatch。landing 完成首次常规散射后，将局部次级计数清零。
之后每轮固定执行 `secondary_trace → secondary_light_select → secondary_direct → secondary_scatter`。
最后 branch resolve 与 noisy output resolve 各一次。不再有 tail admission、tail raygen、
tail 的寄存器内传输循环或只供该循环使用的即时 NEE adapter。

| 设置 | 默认 | 语义 |
| --- | --- | --- |
| delta 上限 D | 12 | 前置透明链事件数，独立于主体常规计数，无 RR |
| 最小轮数 M | 2 | 常规路径在 RR 之前至少完成的轮数，范围仍为 1–8 |
| 最大轮数 B | 12 | 主体配置上限，范围仍为 1–64；实时采用 K=max(M,B) |

共享默认 D / B 从 16 降为 12；已有配置里的显式值原样保留，恢复默认后采用新值。
默认完整实时记录 `11 + 4×(K−1) + 2 = 57` 条 RT dispatch；K=1 为 13，K=64 为 265。
旧默认 M=2 的调度为 19 条。轻量版按自己的粗粒度阶段记录 `2K+4` 条，默认改为 28。
空队列不启动 raygen，但命令解析和 barrier 仍有成本，不做逐轮 CPU 读回。

完整实时保持原来的截断位置：累计 K 次常规散射后不再追踪端点；不会额外加入 Lambert
实现用于有限阶端点发光的 terminal trace。前置 delta 达到 D 时的 guide fallback 也沿用原策略。

## RR 与队列生命周期

RR 移到 scatter 写回之后、发布后继之前；独立窄函数只加载控制字、throughput、etaScale。
仍用普通线性存活率 `min(max(throughput)×etaScale,1)`，存活权重除以该概率；不做首表面
去调制补偿。完成轮数达到 M 且尚未达到 K 才考虑 RR。保留已有首个次级 guide 查询规则：
即使 M=1，仍不会在首次常规散射后执行 RR；K=1 则直接截断，不为 guide 多算光照。
待定透明 guide 的 RR 阻塞条件保留，landing 在发布前先完成 guide 封口和本地计数重置。

用写回后的局部次级计数选择 RR 随机地址：首次常规散射后为 0，下一次为 1。它对应旧
admission 的当前计数和旧 tail 散射的 `旧计数+1`。不再在 admission 或循环内重复执行 RR。
深层 direct 复用现有 staged 采样域，不承诺与旧即时 tail 逐样本图像一致。

两条主体队列交替使用，scatter 是源队列的最后一个消费者，首个 invocation 只清零源命令的
宽度字，再向另一队列追加存活路径。不能依赖下一轮的求交 invocation 清空目标：当后继
全灭时那次 dispatch 不启动，旧队列计数会遗留并重新处理旧路径。沿用 subgroup 合并发布。
队列元数据使用已有命令第四字，低 8 位 D、次低 8 位 M，最高位 overflow；不增加 buffer。

路径和 phase scratch 大小不变，精确几何身份、介质编码、反射 / 透射重建槽和历史接口沿用
现有合同。area queue 仍与串行 guide 阶段复用，transparent resolve 队列跨主体轮次保持存活。

## 验证与性能边界

GPU 行为测试穷举 M=1..8、B=1..64 与边界次级计数，检查 RR 起点和停止条件；统计测试
以多个预算验证线性 RR 的有限阶能量期望，并包含 B<M、K=1、K=64。既有 subgroup 测试
覆盖全灭、全活、混合存活与非整组发布；host 契约测试检查新阶段数、产物集合与命令元数据。

预期收益来自消除 tail 同一 raygen 内求交、材质、灯光、BSDF、RR 状态的重叠；代价是更多
dispatch、barrier 与中间记录读写。不能从源码或 SPIR-V 大小推断物理寄存器数和帧率。
应同场景实测暗处深反弹、转头后大量 miss、透明面与全灭队列，比较 K=12 / 64 的 GPU
时间、secondary scatter 的 live / allocated registers、spill 和重建稳定性。

本轮 `gradlew build shaderTest` 通过：586 项常规、64 项 GPU、1 项产物测试，零失败、零
跳过；生产编译边界、SPIR-V payload / descriptor / subgroup 合同和发布包检查均通过。
用户随后确认游戏画面正常；本轮尚无新的帧时间或 Nsight 捕获。产物与生命周期风险追加在
[寄存器记录](轻量渲染器寄存器记录.md)末尾。
