# ReSTIR PT Enhanced 渲染器

`restir_pt` 是独立的世界渲染器选择，与 `vanilla`、`path_trace` 互斥。Java 仅负责选择和已有批量源入口，Rust 复用 Prime 的场景、材质、BLAS/TLAS、大气和显示资源；积分、重放、shift、reservoir 与调度属于独立的 Slang/Vulkan 实现。选择普通 PT 时不创建 ReSTIR 的管线或屏幕状态。

算法来源为 Falcor 9.0 的 `Source/Modules/ReSTIRPathTracing`，源提交 `759aad033ff610fb0d82c74f7e0a508d0096d5f2`。它包含 Enhanced 的双 footprint 判定、混合 PSS/立体角参数化和双向 pairwise MIS。来源及 BSD-3-Clause 许可见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 固定配置与支持边界

当前固定为上游默认的 point reservoir、Hybrid shift、Compact retrace、3 个互反配对邻居、标准差 16 的原邻域表、1 轮空间重采样、20 的时间历史 M 上限。保留原 TinyUniform RNG、path flags、初始 RIS 与 reservoir 合并归一化规则。场景或光照变化时启用上游 current-scene temporal suffix update；没有旧场景副本。未启用 area reservoir、DoF、splatting、robust temporal、压缩、decoupled shading 或 duplicate map。

材质适配使用 Prime 已接入的 OpenPBR/LabPBR、真实源色、发光、覆盖、介质与透射契约。自定义材质走上游不支持 BSDF component indexing 的 PDF roughness 判定；没有用单一粗糙度值代替混合 BSDF 的采样 PDF。上游默认的显式材质 LOD 0 保留，因此本渲染器没有采用普通 PT 的传播 ray-cone 纹理过滤。覆盖随机域使用固定的路径 seed 与位置哈希；重放不会再次执行宿主回调。命中身份保存实际复合表面的材质选择，避免重建浮点误差改变涂层来源。

Prime 的局部光源 proposal 依赖接收点，TreeSphere 另依赖法线。两种树都在世界与页内逐层评分，逆向 PDF 也按实际接收点重放；TreeSphere 另计算角度界及 quad 两半的法线。连接到光源时重新计算目的点的 PDF，不能缓存旧接收点的 PMF；静止历史不保证跳过这些求值。天空与太阳具有不同 NEE 竞争规则，初始 RIS 分开保存它们。吸收介质属于 prefix replay 的显式数据，连接可见性继续使用 Prime 的透射查询。

求交起点复用 Prime 的重心/仿射误差界：静态命中和发光端点用 `reconstructStaticSurface`，动态命中用 `reconstructSurface`；初始路径与 prefix/suffix 重放的下一跳调用 `spawnRay`。有限 NEE 和 shift 重连接按实际方向在两端分别偏移后构造可见性线段，PDF/Jacobian 使用原物理点。`RESTIR_DISTANCE_THRESHOLD` 是重连接分类阈值，不是求交 epsilon。硬件误差常量仍基于 NVIDIA RTX，其他硬件与极端几何验证边界见 [PT-006](../HACK.md)。

TreeSphere 下，带法线贴图的非光学接收面的采样法线会随入射方向修正。该情形沿用上游强制终端光源连接，把接收面保留在重放 prefix 中，避免复用过期的接收点 proposal PDF；生成和目的路径判定一致。代价是这类候选多一个 prefix 顶点和最近命中查询，可能多进入一个 Compact job，不增加常驻字段。

实时可使用 DLSS RR 或原生分辨率原始输出，质量、线性颜色、PSR、完成状态、显示与 SDK 资源寿命遵循[重建合同](reconstruction.md)。RR 的 Halton 抖动同时用于 ReSTIR 生成、重放、shift 和主 guide；时间重投影扣除已接受前帧的抖动。普通粗糙首面共享生成时的真实交点；规范 guide 与照明随机分支独立，纯 delta 前缀才追加 guide 后缀遍历，不执行普通 PT 的照明管线。ReSTIR 的主交点与全部 guide 后缀都使用 LOD 0，保持与实际输运相同的材质语义。离线冻结每个样本执行初始生成与空间重采样，并在线累积最终线性 radiance；不把同一冻结历史反复计作独立样本。

## GPU 数据流

每个实时帧依次执行生成、时间 workload/retrace/merge、空间 workload/retrace/shift/merge、resolve。无有效历史时跳过时间阶段。时间 merge 与原版一样内联顺序计算双向 shift，不增加全屏 shift 中间写读。workload 通过 wave prefix sum 在 GPU 上生成紧凑队列，GPU 写入 `DispatchIndirect` 参数；CPU 不读回队列长度。只有需要 replay prefix 的候选进入队列。空间反向 shift 使用配对邻居已生成的相反方向记录，不重复追踪；无效配对没有消费者，不清零其 shift 槽。

现有 GPU 诊断的 primary、transport、post 区间分别覆盖完整初始生成、重采样和 resolve；不能把这些同名区间当作普通 PT 的 K1/K2 阶段直接比较。性能比较使用完整 GPU 帧区间，并按实际后端解释子阶段。

初始 RIS 的 reservoir 权重除以 `M * pHat` 并将 M 设为 1；之后合并使用 `pHat(dst) * Jacobian * source.weight * pairwiseMIS`，最终仅除以所选样本的 pHat。连接 Jacobian 仅为两端几何项之比；不得再乘 BSDF PDF 比。连接点相邻的真实 BSDF 响应在目的路径重新求值。

所有阶段处于同一 GPU 提交，阶段间使用真实 compute/transfer/indirect 依赖。没有逐 bounce 的宿主调度、CPU 队列搬运或新增稳态完成等待。TLAS 实例矩阵直接读取当前已完成 frame slot 的输入 buffer；不复制第二套变换表。未重建 TLAS 的帧也只补该 slot 的脏范围，以保证 shader 读取期间不会被其他 slot 的 CPU 更新覆盖。

仅重建命中材质的 workload/resolve 在编译时排除 AS 依赖；真正的追踪阶段使用 Ray Query。不要求 Ray Tracing Pipeline 或 shaderInt64 功能。

屏幕 reservoir 使用 16×16 Morton tile，tile 间为行序；工作队列使用紧凑线性索引。自然 BDA 布局为 reservoir 80 B、primary 20 B、replay 60 B（含介质）、shift 20 B，要求 Vulkan 1.2 `scalarBlockLayout` 在逻辑设备创建时实际启用。宿主和无窗口设备均显式协商，物理设备支持查询不能代替启用证明。464 B frame uniform 含当前/前帧 Frame、抖动、屏幕与身份表地址和已接受修订水位，只有已完成的描述符 slot 能写它。布局通过实际 SPIR-V 验证，不依赖默认 SSBO 的填充规则。

屏幕 scratch 为每个 padded pixel 508 B，加 16 B indirect queue control；1920×1080 的 padded extent 为1920×1088，约1012 MiB，另有786432 B配对邻域表和共享场景/显示资源。该成本包含两份 reservoir、两份 primary 和最多3个候选的重放/shift/队列空间。没有为默认关闭的上游选项预留状态。实际帧时间和与 Falcor 的性能差距需要固定场景的原生1920×1080测量；布局与成本分析不构成性能相等的证明。

## 历史与完成证明

局部源发布、动态快照、纹理/动画、太阳、大气眼高和 scene anchor 改变保留时间阶段。相机历史先转换到当前 anchor；普通相机运动由重投影处理。静态页、动态 placement 和 emitter 页保存最后身份修订与有效 primitive 数；历史主交点和连接点在任何几何或矩阵读取前检查 slot 存活、索引范围与已接受水位。替换、回收复用、dense 重排和未知对应只拒绝相关路径，不把旧索引解释成新几何。每 slot 的 GPU 记录为8 B，另有 CPU 镜像、变更记录与 emitter 身份 key；不扩大80 B reservoir，也不保存旧 TLAS/材质/纹理。身份表仅为 ReSTIR 延迟创建；首次构建扫描现有记录，之后静态/动态记录消费真实变更范围，光源页列表或采样器变更时比较当前全部 emitter 页身份。一般更新合并脏范围上传，容量增长全量上传对应表。稳态不扫描或上传身份表。

纹理的 mip0 backing、尺寸、当前 region/next/blend 或删除事件累积实际采样支持变化，直到静态和动态消费者均处理。恒定 alpha 的不可变动画族例外：首发时只扫描完整族的 mip0 sprite 窗口，缓存一个 alpha 字节；后续相位变化复用已验证的族身份，不逐帧扫描像素。RGB 动画仍更新当前场景后缀，但不重复拒绝其未变的 coverage/coating 叶。族未知、alpha 非恒定、采样尺寸或 alpha 实际变化仍保留局部拒绝。动画所有帧的 OMM 覆盖证明不能证明当前采样仍选择同一透明度或涂层叶；受影响页、动态有效纹理依赖和同身份光源页立即更新身份修订，即使 OMM 重编译仍在排队。事件帧扫描现有静态依赖、动态 placement 和相关光源身份，不增加 shader 求交；无事件时不扫描。动态原型依赖集合在实际 packing 时建立。当前不增加逐 texel 透明度证明，未知 RGB backing 或动画阶段变化也保守拒绝依赖页，可能使持续动画页的历史不能累积；材质或 mip-only 更新继续走后缀求值。

身份仍有效不代表旧光照有效。变化帧按上游动态分支重新求值环境端点、发光端点、连接点 NEE 或固定随机种子的后缀，包含当前可见性、材质、PDF/MIS 和介质吸收。源 integrand/weight 保留原 MIS 源项的顺序，合并选择更新后的 cache 并最终归一化。Prime 分离天空/太阳 proposal，失去原端点支持时拒绝，不伪造环境旋转。静态帧继续复用缓存；持续太阳运动会增加后缀重放查询，尚无整帧收益测量。此处采用原版无旧场景的更新模式，不声明任意动态场景下的精确旧场景 MIS。

首次使用为无历史冷启动；世界/场景 owner 或 epoch 更换、实际内部尺寸变化及实时/离线或积分器域切换仍是全局失效边界。不能证明整个复用域失效时继续接收历史。采样编号归零、回绕、跳号、seed、暂时跳帧、纹理资源 owner/generation、顶点预算、光源采样方式或 FG 切换继续使用最近一次已接受历史。纹理换代保留单调身份表，并按真实变更日志更新相关 static/dynamic/emitter slot。整张 atlas 重装通过 `resourceGeneration` 撤销旧资源引用和重新编译，不推进世界 epoch；同 epoch 的资源目录替换保留 `SourceScene` 的 source owner，真实世界重置才更换该身份。预算和光源 proposal 改变按已有当前场景后缀更新处理，不保存旧 proposal/场景；维持当前动态模式的近似边界，不声明任意 proposal 切换下的严格无偏性。预算降低仅拒绝超过新支持的路径：NEE 端点的 `pathLength < budget`，BSDF-hit/escape 的 `pathLength+1 < budget`。

全局重置采用共享封闭黑名单，入口只接受预定义事件类型：世界替换、世界 epoch、场景身份域替换、渲染域、内部尺寸及 RR feature 配置/求值失败。普通 tick、内容修订、动画和采样编号没有全局重置事件。每次请求直接写日志中的 `event/action/valid`，性能录制另保存 `restir.history.reset` / `rr.history.reset` 的 `reason/ignored/valid`；不依赖开启录制才知道原因。首次没有历史是冷启动，实际 scratch/SDK feature 重建另记 `*.history.storage`，不能把未初始化存储标成有效历史。

诊断组的“忽略所有全局重置”默认关闭；开启时忽略上述显式请求，继续执行局部支持拒绝、当前场景更新和提交接受合同。存储实际重建仍冷启动。保留跨世界 reservoir 时，新身份域从已接受修订水位后继开始，避免相同数值 ID 错配旧几何。`restir.history.frame` 的 `temporal/update/revision/accepted` 和 `restir.identity.frame` 的脏范围计数可区分全局重置、局部支持变化与正常后缀更新，不增加逐像素读回或等待。RR 的当前 guide、motion 和完成状态由其[重建合同](reconstruction.md)负责，图像转换见[坐标契约](coordinates.md)；保留历史不构成 SDK 内部逐像素拒绝的保证。显示参数只影响 resolve/display。

前帧 reservoir 在本帧全部读取完成后才被空间 merge 覆写。primary 使用两个 bank，前帧相机和 bank 交换只在宿主接受提交后提交；取消录制不会推进历史。resize、后端切换和关闭沿用已有 GPU 完成或取消证明后回收的规则，不按经过的帧数猜测资源寿命。

## 验证入口

`scripts/test-restir-math.py` 将生产数学模块编译为 Slang CPU 目标并执行原 RNG 对照、RIS/合并统计、flags、Jacobian、pairwise MIS 和全部原配对邻域的互反性质；同时检查实际 BDA SPIR-V 布局。Vulkan 集成测试使用无窗口的真实 ray-query GPU，并与普通 PT 的线性结果对照。编译、数学测试及小场景 GPU 测试不能替代完整游戏画面或正式性能基线。

游戏启动和验收由用户执行，命令见 [CONTRIBUTING](../CONTRIBUTING.md)。应固定路径预算、光源方式、原生1920×1080、场景和 seed，分别记录稳态与内容更新、CPU/GPU 时间。检查静止收敛、相机重投影、移动/编辑与纹理动画的失效、透明介质、镂空和模式/尺寸切换。
