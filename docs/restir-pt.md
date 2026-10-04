# ReSTIR PT Enhanced 渲染器

`restir_pt` 是独立的世界渲染器选择，与 `vanilla`、`path_trace` 互斥。Java 仅负责选择和已有批量源入口，Rust 复用 Prime 的场景、材质、BLAS/TLAS、大气和显示资源；积分、重放、shift、reservoir 与调度属于独立的 Slang/Vulkan 实现。选择普通 PT 时不创建 ReSTIR 的管线或屏幕状态。

算法来源为 Falcor 9.0 的 `Source/Modules/ReSTIRPathTracing`，源提交 `759aad033ff610fb0d82c74f7e0a508d0096d5f2`。它包含 Enhanced 的双 footprint 判定、混合 PSS/立体角参数化和双向 pairwise MIS。来源及 BSD-3-Clause 许可见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 固定配置与支持边界

当前固定为上游默认的 point reservoir、Hybrid shift、Compact retrace、3 个互反配对邻居、标准差 16 的原邻域表、1 轮空间重采样、20 的时间历史 M 上限。保留原 TinyUniform RNG、path flags、初始 RIS 与 reservoir 合并归一化规则。未启用 area reservoir、DoF、splatting、robust temporal、压缩、decoupled shading、duplicate map 或动态场景 temporal update。这些配置没有额外的常驻资源或运行时分支。

材质适配使用 Prime 已接入的 OpenPBR/LabPBR、真实源色、发光、覆盖、介质与透射契约。自定义材质走上游不支持 BSDF component indexing 的 PDF roughness 判定；没有用单一粗糙度值代替混合 BSDF 的采样 PDF。上游默认的显式材质 LOD 0 保留，因此本渲染器没有采用普通 PT 的传播 ray-cone 纹理过滤。覆盖随机域使用固定的路径 seed 与位置哈希；重放不会再次执行宿主回调。命中身份保存实际复合表面的材质选择，避免重建浮点误差改变涂层来源。

Prime 的局部光源 proposal 可能依赖接收点和法线。连接到光源时重新计算目的点的 Grid/Tree/TreeSphere PDF；不能照搬位置无关光源选择的简化。天空与太阳具有不同 NEE 竞争规则，初始 RIS 分开保存它们。吸收介质属于 prefix replay 的显式数据，连接可见性继续使用 Prime 的透射查询。

TreeSphere 下，带法线贴图的非光学接收面的采样法线会随入射方向修正。该情形沿用上游强制终端光源连接，把接收面保留在重放 prefix 中，避免复用过期的接收点 proposal PDF；生成和目的路径判定一致。代价是这类候选多一个 prefix 顶点和最近命中查询，可能多进入一个 Compact job，不增加常驻字段。

实时固定使用原生分辨率原始输出，当前没有接入 PSR、DLSS RR 或帧生成。相关请求记录实际不可用原因；不会以普通 PT 的 guides 冒充 ReSTIR 路径数据。曝光、primeDRT、HDR 与深度/法线诊断仍通过共享显示路径。离线冻结每个样本执行初始生成与空间重采样，并在线累积最终线性 radiance；不把同一冻结历史反复计作独立样本。

## GPU 数据流

每个实时帧依次执行生成、时间 workload/retrace/merge、空间 workload/retrace/shift/merge、resolve。无有效历史时跳过时间阶段。时间 merge 与原版一样内联顺序计算双向 shift，不增加全屏 shift 中间写读。workload 通过 wave prefix sum 在 GPU 上生成紧凑队列，GPU 写入 `DispatchIndirect` 参数；CPU 不读回队列长度。只有需要 replay prefix 的候选进入队列。空间反向 shift 使用配对邻居已生成的相反方向记录，不重复追踪；无效配对没有消费者，不清零其 shift 槽。

现有 GPU 诊断的 primary、transport、post 区间分别覆盖完整初始生成、重采样和 resolve；不能把这些同名区间当作普通 PT 的 K1/K2 阶段直接比较。性能比较使用完整 GPU 帧区间，并按实际后端解释子阶段。

初始 RIS 的 reservoir 权重除以 `M * pHat` 并将 M 设为 1；之后合并使用 `pHat(dst) * Jacobian * source.weight * pairwiseMIS`，最终仅除以所选样本的 pHat。连接 Jacobian 仅为两端几何项之比；不得再乘 BSDF PDF 比。连接点相邻的真实 BSDF 响应在目的路径重新求值。

所有阶段处于同一 GPU 提交，阶段间使用真实 compute/transfer/indirect 依赖。没有逐 bounce 的宿主调度、CPU 队列搬运或新增稳态完成等待。TLAS 实例矩阵直接读取当前已完成 frame slot 的输入 buffer；不复制第二套变换表。未重建 TLAS 的帧也只补该 slot 的脏范围，以保证 shader 读取期间不会被其他 slot 的 CPU 更新覆盖。

仅重建命中材质的 workload/resolve 在编译时排除 AS 依赖；真正的追踪阶段使用 Ray Query。不要求 Ray Tracing Pipeline 或 shaderInt64 功能。

屏幕 reservoir 使用 16×16 Morton tile，tile 间为行序；工作队列使用紧凑线性索引。自然 BDA 布局为 reservoir 80 B、primary 20 B、replay 60 B（含介质）、shift 20 B，要求 Vulkan 1.2 `scalarBlockLayout` 在逻辑设备创建时实际启用。宿主和无窗口设备均显式协商，物理设备支持查询不能代替启用证明。400 B frame uniform 含当前/前帧 Frame 和屏幕状态地址，只有已完成的描述符 slot 能写它。布局通过实际 SPIR-V 验证，不依赖默认 SSBO 的填充规则。

屏幕 scratch 为每个 padded pixel 508 B，加 16 B indirect queue control；1920×1080 的 padded extent 为1920×1088，约1012 MiB，另有786432 B配对邻域表和共享场景/显示资源。该成本包含两份 reservoir、两份 primary 和最多3个候选的重放/shift/队列空间。没有为默认关闭的上游选项预留状态。实际帧时间和与 Falcor 的性能差距需要固定场景的原生1920×1080测量；布局与成本分析不构成性能相等的证明。

## 历史与完成证明

相机运动通过前帧相机与未变场景的实际命中位置重投影，包括静止动态实例；CPU 的历史有效证明涵盖实际发布几何和 placement，不能从未知运动字段推测静止。当前不缓存前帧完整场景，源发布、动态快照、纹理/动画、锚点、尺寸、积分器或输运设置变化均使时间历史失效。显示参数只影响 resolve/display。

实际 GPU 太阳方向与大气眼高改变也会重置时间历史；原始高度变更若没有改变有效 FP32 眼高则保留。Minecraft 时间推进通常持续改变太阳方向，因此当前正常推进时间的场景主要使用空间重采样；固定太阳/环境后才连续复用时间历史。上游存在动态 suffix 重求值分支，但默认配置关闭，本适配也没有以旧 radiance 冒充更新后的光照。持续变化环境的时间复用与完整游戏性能仍待实现和验收。

前帧 reservoir 在本帧全部读取完成后才被空间 merge 覆写。primary 使用两个 bank，前帧相机和 bank 交换只在宿主接受提交后提交；取消录制不会推进历史。resize、后端切换和关闭沿用已有 GPU 完成或取消证明后回收的规则，不按经过的帧数猜测资源寿命。

## 验证入口

`scripts/test-restir-math.py` 将生产数学模块编译为 Slang CPU 目标并执行原 RNG 对照、RIS/合并统计、flags、Jacobian、pairwise MIS 和全部原配对邻域的互反性质；同时检查实际 BDA SPIR-V 布局。Vulkan 集成测试使用无窗口的真实 ray-query GPU，并与普通 PT 的线性结果对照。编译、数学测试及小场景 GPU 测试不能替代完整游戏画面或正式性能基线。

游戏启动和验收由用户执行，命令见 [CONTRIBUTING](../CONTRIBUTING.md)。应固定路径预算、光源方式、原生1920×1080、场景和 seed，分别记录稳态与内容更新、CPU/GPU 时间。检查静止收敛、相机重投影、移动/编辑与纹理动画的失效、透明介质、镂空和模式/尺寸切换。
