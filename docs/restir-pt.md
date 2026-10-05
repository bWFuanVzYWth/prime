# ReSTIR PT Enhanced 渲染器

`restir_pt` 是独立的世界渲染器选择，与 `vanilla`、`path_trace` 互斥。Java 仅负责选择和已有批量源入口，Rust 复用 Prime 的场景、材质、BLAS/TLAS、大气和显示资源；积分、重放、shift、reservoir 与调度属于独立的 Slang/Vulkan 实现。选择普通 PT 时不创建 ReSTIR 的管线或屏幕状态。

算法来源为 Falcor 9.0 的 `Source/Modules/ReSTIRPathTracing`，源提交 `759aad033ff610fb0d82c74f7e0a508d0096d5f2`。它包含 Enhanced 的双 footprint 判定、混合 PSS/立体角参数化和双向 pairwise MIS。来源及 BSD-3-Clause 许可见 [第三方声明](../THIRD_PARTY_NOTICES.md)。 相对上游的接口、数学与历史适配集中维护在[统一登记](restir-adaptations.md)，本文件维护生产管线与资源合同。

## 默认配置、设置与支持边界

默认保持 point reservoir、Hybrid shift、Compact retrace、3 个互反配对邻居、标准差 16 的原邻域表、1 轮空间重采样、20 的时间历史 M 上限和每像素 1 条新路径。保留 TinyUniform RNG、path flags、初始 RIS 与 reservoir 合并归一化规则。场景或光照变化时启用上游 current-scene temporal suffix update；没有旧场景副本。支持范围仍不包含 area reservoir、DoF、splatting、MCMC、robust temporal 或压缩。

视频设置的独立“Prime ReSTIR PT”栏保存下列参数；“仅空间复用”从诊断栏迁移到这里，旧配置自动保留。新增机制的主开关默认关闭，启用时使用参考实现的默认数值。

| 参数 | 默认 / 范围 | 消费语义 |
| --- | --- | --- |
| 仅空间复用 | 关 | 跳过全部时间阶段，RR 历史继续推进 |
| 初始路径数 | 1 / 1–16 | 多条新路径先 RIS 聚合，再将 M 归一为 1；共享真实 primary |
| 时间历史 M 上限 | 20 / 0–100 | 有效候选数上限，不是实际独立样本数或寿命 |
| 空间复用、轮数 | 开、1 / 0–8 | 零轮直接消费时间/初始结果；多轮复用两个 reservoir bank |
| 配对邻居数、平均尺度 | 3、30 / 1–5、5–50（步长 5） | 选择原版完整互反 LUT；标准差为尺度乘 `sqrt(8/(9π))` |
| 随机重投影 | 关 | 时间 donor 随机像素舍入；不改变源 ray 的 jitter |
| 重复样本降权、指数 | 关、0.1 / 0–10 | 17×17 重复 seed 统计，将历史 cap 从 H 向 1 插值；指数越小，非零重复率的降权越强 |
| 解耦着色 | 关 | 最后空间轮累计各候选的加权颜色，历史仍只保存被选中的路径 |
| 距离阈值、相对随机幅度 | 0.02、0.2 / 0–10000、0–1 | UI 距离除以 100 后进入 footprint chart |
| PDF roughness 阈值、相对随机幅度 | 0.2、0 / 0–1、0–1 | 以实际 BSDF PDF 分类，非单一材质粗糙度 |
| 空间法线、相对深度门限 | 0.5、0.1 / −1–1、0–1 | 配对候选的对称几何筛选 |
| 随机种子 | 0 / uint32 | 新路径随机域；不清空全局 ReSTIR/RR 历史 |
| 统计视图 | 关 / 重复率、存活统计 | 仅覆盖显示；不写入 reservoir、离线物理均值或 RR 含噪输入 |

可选 RR 输出去相关使用 Prime 独立实现，数学定义参考 [RTXDI ReSTIR PT 文档](https://github.com/NVIDIA-RTX/RTXDI/blob/a6efab966b7c3b272da0461578eb56ac61c7cbff/Doc/RestirPT.md)。主开关默认关闭；模式默认 Stagnancy，基础概率 0.4、停滞指数 0.5、EMA 0.2、亮点强度 0.7、新样本权重限制开启且倍数上限 15。Uniform 直接使用基础概率；Stagnancy 使用 `min(1, 4 × factor × smooth^exponent)`，亮点标记可强制概率为 1。它只在实时 RR 且存在时间复用时，用 Bernoulli 选择初始或重采样颜色，不改历史 reservoir。颜色相关的自适应选择与权重限制不保证严格无偏；Prime 使用 Falcor 的存活计数，不声称与另一引擎逐位等价，见 [RA-016](restir-adaptations.md)。

材质适配使用 Prime 已接入的 OpenPBR/LabPBR、真实源色、发光、覆盖、介质与透射契约。自定义材质走上游不支持 BSDF component indexing 的 PDF roughness 判定；没有用单一粗糙度值代替混合 BSDF 的采样 PDF。上游默认的显式材质 LOD 0 保留，因此本渲染器没有采用普通 PT 的传播 ray-cone 纹理过滤。覆盖随机域使用固定的路径 seed 与位置哈希；重放不会再次执行宿主回调。命中身份保存实际复合表面的材质选择，避免重建浮点误差改变涂层来源。

Prime的功率距离Tree proposal依赖接收点，在世界与页内逐层评分，逆向PDF按同一物理接收点重放。连接到光源时重新计算目的点的PDF，不能缓存旧接收点的PMF；静止历史不保证跳过这些求值。天空与太阳具有不同NEE竞争规则，初始RIS分开保存它们。吸收介质属于prefix replay的显式数据，连接可见性继续使用Prime的透射查询。

BSDF 续接与直接光照具有不同支持域。非光学 authored 薄 SSS 可以通过实际 BSDF 采样透射；重连和后缀重放保留这一事件，并要求带法线贴图的几何/着色事件一致。其实际散射 PDF 继续用于 footprint 和路径度量，但直接光照的竞争 PDF 为零，发光命中/环境端点的 MIS 因而为一。局部灯和太阳 NEE 使用朝向当前 view 的几何前半球，光学接收面保留透射支持；新太阳提议在阴影查询前检查，已有终端 NEE 在当前消费处检查，不依赖全局重置撤销错误端点。

求交起点复用 Prime 的重心/仿射误差界：静态命中和发光端点用 `reconstructStaticSurface`，动态命中用 `reconstructSurface`；初始路径与 prefix/suffix 重放的下一跳调用 `spawnRay`。有限 NEE 和 shift 可见性线段继续在两端使用同一偏移算法。BSDF 到达连接点的段以偏移后的 query 起点定义方向与几何项，shift 的第一 BSDF、连接点入射方向和 Jacobian 使用同一测度；只有终端 NEE 使用物理接收点测度，连接点之后的 NEE 不改变到达段的分类。物理连接与 query 连接必须保留源反射/透射事件及同一偏移侧。`RESTIR_DISTANCE_THRESHOLD` 是重连接分类阈值，不是求交 epsilon。现有生成器直接竞争物理光源角 PDF 与 query BSDF 角 PDF，以及两端可见性裁短的吸收距离，仍属于数值近似边界；不据此声明完整传输无偏。硬件误差常量仍基于 NVIDIA RTX，其他硬件与极端几何验证边界见 [PT-006](../HACK.md)，蠕动继续按 [PT-018](../HACK.md) 排查。

实时可使用 DLSS RR 或原生分辨率原始输出，质量、线性颜色、PSR、完成状态、显示与 SDK 资源寿命遵循[重建合同](reconstruction.md)。RR 的 Halton 抖动同时用于 ReSTIR 生成、重放、shift 和主 guide；时间 donor 选择使用去抖动的运动，当前 pinhole 交点的前帧投影扣当前抖动；源重放继续使用已接受前帧的抖动，见[适配登记 RA-002](restir-adaptations.md)。普通粗糙首面共享生成时的真实交点；规范 guide 与照明随机分支独立，纯 delta 前缀才追加 guide 后缀遍历，不执行普通 PT 的照明管线。ReSTIR 的主交点与全部 guide 后缀都使用 LOD 0，保持与实际输运相同的材质语义。离线冻结每个样本执行初始生成与空间重采样，并在线累积最终线性 radiance；不把同一冻结历史反复计作独立样本。

## GPU 数据流

每个实时帧依次执行生成、时间 workload/retrace/merge、配置轮数的空间 workload/retrace/shift/merge、可选统计、resolve。无有效历史时跳过时间阶段。时间 merge 与原版一样内联顺序计算双向 shift，不增加全屏 shift 中间写读。workload 通过 wave prefix sum 在 GPU 上生成紧凑队列，GPU 写入 `DispatchIndirect` 参数；CPU 不读回队列长度。只有需要 replay prefix 的候选进入队列。空间反向 shift 使用配对邻居已生成的相反方向记录，不重复追踪；无效配对没有消费者，不清零其 shift 槽。

ReSTIR PT 设置“仅空间复用”默认关闭。开启时宿主直接跳过整组时间阶段及其队列清零、indirect 调度和同步，仅保留初始生成、空间阶段与 resolve；空间输出使用现有独立 reservoir bank，不增加历史复制或 shader 变体。既有 scratch 和管线保持复用，身份表及接受提交的相机、jitter、primary bank、水位继续推进，关闭开关后可立即使用最近的真实空间结果恢复时间复用。不受“忽略所有全局重置”开关覆盖，不重置 DLSS RR 历史，也不改变原本仅空间复用的离线累积。内部适配见 [RA-014](restir-adaptations.md)。

现有 GPU 诊断的 primary、transport、post 区间分别覆盖完整初始生成、重采样和 resolve；不能把这些同名区间当作普通 PT 的 K1/K2 阶段直接比较。性能比较使用完整 GPU 帧区间，并按实际后端解释子阶段。

初始 RIS 的 reservoir 权重除以 `M * pHat` 并将 M 设为 1；之后合并使用 `pHat(dst) * Jacobian * source.weight * pairwiseMIS`，最终仅除以所选样本的 pHat。连接 Jacobian 仅为两端几何项之比；不得再乘 BSDF PDF 比。连接点相邻的真实 BSDF 响应在目的路径重新求值。生成、重放与 shift 的面积 footprint 使用几何法线，BSDF 响应继续使用着色法线，避免强法线贴图改变逆向面积判定的支持域。

所有阶段处于同一 GPU 提交，阶段间使用真实 compute/transfer/indirect 依赖。没有逐 bounce 的宿主调度、CPU 队列搬运或新增稳态完成等待。TLAS 实例矩阵直接读取当前已完成 frame slot 的输入 buffer；不复制第二套变换表。未重建 TLAS 的帧也只补该 slot 的脏范围，以保证 shader 读取期间不会被其他 slot 的 CPU 更新覆盖。

仅重建命中材质的 workload/resolve 在编译时排除 AS 依赖；真正的追踪阶段使用 Ray Query。不要求 Ray Tracing Pipeline 或 shaderInt64 功能。

屏幕 reservoir 使用 16×16 Morton tile，tile 间为行序；工作队列使用紧凑线性索引。自然 BDA 布局为 reservoir 80 B、primary 20 B、replay 60 B（含介质）、shift 20 B，要求 Vulkan 1.2 `scalarBlockLayout` 在逻辑设备创建时实际启用。宿主和无窗口设备均显式协商，物理设备支持查询不能代替启用证明。720 B frame uniform 保留原 464 B 前缀，并追加运行时配置、按需辅助地址与源 chart 身份；只有已完成的描述符 slot 能写它。布局通过实际 SPIR-V 验证，不依赖默认 SSBO 的填充规则。

屏幕 scratch 将持久状态和候选工作区分开：每 padded pixel `232 + 92 × max(2, N)` B，默认 N=3 时保持 508 B，加 16 B indirect queue control；1920×1080 的 padded extent 为1920×1088，约1012 MiB，另有786432 B配对邻域表和共享场景/显示资源。该成本包含两份 reservoir、两份 primary 和最多3个候选的重放/shift/队列空间。关闭的新增机制不预留屏幕状态或调度统计 pass。重复 ID/count 占 8 B/像素，存活计数双 bank 占 8 B/像素，解耦输出占 16 B/像素；RR 去相关额外 initial RGB/UCW 16 B、可选 EMA 双 bank 8 B 和亮点标记 4 B，并共享存活计数。关闭对应机制时资源按实际最后消费者退休，已创建的小型 pipeline 保留至后端销毁。实际帧时间和与 Falcor 的性能差距需要固定场景的原生1920×1080测量；布局与成本分析不构成性能相等的证明。

## 历史与完成证明

局部源发布、动态快照、纹理/动画、太阳、大气眼高和 scene anchor 改变保留时间阶段。相机历史先转换到当前 anchor；普通相机运动由重投影处理。历史主交点和连接点在任何几何或矩阵读取前检查 slot 存活、索引范围与已接受水位。替换、回收复用、dense 重排和未知对应只拒绝相关路径，不把旧索引解释成新几何。

ReSTIR 静态地形在脏更新时按实际编码记录精确匹配面，哈希只用于查找候选。未变 quad 保留物理槽；删除槽写退化几何并清除两半有效位，新面复用空槽时取得新修订。页顺序保持稳定，新格式或容量分片追加页。目录扩容保留旧页号的别名，旧历史仍读取同一面槽和当前记录；别名随所属 Cell 卸载撤回。不会因为挖掘导致重新打包就修订同页的全部未变面。CPU 为 ReSTIR 保留已解析面和槽状态，普通 PT 不建立这份历史缓存。

这里的粒度是已编译 physical quad。表面编译器在64×64平面 tile 内合并矩形；挖掉一格可能重分割旧大面，原 primitive/bary 无法直接表达新多个三角，因此旧大面上的路径仍会失效。当前方案不提供未变子区域的点对应，也不将此边界认定为已解决的局部历史问题。动态原型/实例仍按原实例身份更新；真实 Cell 撤回和依赖纹理删除仍撤回相应几何域。

静态页的8 B header 保存 quad 表起点和 primitive 容量，高位标记逐 quad 模式；每个 quad 另占8 B，保存修订和两半存活位。动态 placement 与回退 emitter 页继续用8 B页记录。NEE 的生产静态端点保存实际地形页/quad/半面身份，发光记录第二个顶点的 `w` padding 保存源 quad 序号，TreePage 的 byte44 标记对应静态页；不能把紧凑光源序号当作几何 quad 序号。这些字段不改变光页、发光记录或80 B reservoir 的 stride。uniform 的 byte440 保持 quad 表地址。

身份表仅为 ReSTIR 延迟创建；首次构建扫描现有记录，之后消费真实变更范围，稳态不扫描或上传。一般更新合并脏范围上传，表容量增长全量上传对应表。目录别名共享同一 quad 表，不复制逐面身份；扩容保留旧目录范围增加CPU/GPU目录驻留和脏更新写入。旧几何、表和上传资源仍按最后消费者与完成证明退休，不保存旧 TLAS/材质/纹理副本。

稳定光页身份不代表稳定GPU地址。实际灯源发布时重写当前节点、发光记录及静态面映射地址，并比较当前面积倒数、重放路径和世界根；相同数量的光源更新不能跳过这些绑定，仅地址变化保留已有世界路径。未变帧沿几何层的无灯源变化早退，不增加稳态扫描或提交；发布失败后的补发覆盖全部未完成范围，资源仍按完成证明退休。

纹理的 mip0 backing、尺寸、当前 region/next/blend 或删除事件累积实际采样支持变化，直到静态和动态消费者均处理。不可变动画族若每个局部 texel 的 alpha 在全体帧中相同，即可保留其非均匀 cutout/coating 支持；首发时扫描完整族的 mip0 sprite 窗口，后续相位变化复用缓存证明，不逐帧扫描像素。恒定 alpha 字节还可证明等价替换族，非均匀 mask 只能在同一已验证族内复用，不能据“各自不变”认定两个未知 backing 相同。RGB 动画仍更新当前场景后缀。未知族、采样尺寸或 alpha 实际变化只修订依赖该支持的静态面；动态 placement 和回退页保持各自身份边界。IOR-only 资源不参与 coverage/coating 依赖。动画全帧 OMM 覆盖不能证明当前材质叶相同；支持修订在排队重编译前生效。事件帧消费静态依赖、动态 placement 和相关光源身份，无事件时不扫描；材质或 mip-only 更新继续走后缀求值。

身份仍有效不代表旧光照有效。变化帧按上游动态分支重新求值环境端点、发光端点、连接点NEE或固定随机种子的后缀，包含当前可见性、材质、PDF/MIS和介质吸收。源integrand/weight保留原MIS源项的顺序，合并选择更新后的cache并最终归一化。Prime分离天空/太阳proposal，失去原端点支持时拒绝，不伪造环境旋转。Tree的选灯PMF不依赖接收法线或view；BSDF响应及反射/透射支持仍按当前连接求值。持续太阳运动会增加后缀重放查询，尚无整帧收益测量。此处采用原版无旧场景的更新模式，不声明任意动态场景下的精确旧场景MIS；适配与数学边界见[统一登记](restir-adaptations.md)。

首次使用为无历史冷启动；世界/场景owner或epoch更换、实际内部尺寸变化及实时/离线或积分器域切换仍是全局失效边界。不能证明整个复用域失效时继续接收历史。采样编号归零、回绕、跳号、seed、暂时跳帧、纹理资源owner/generation、顶点预算或FG切换继续使用最近一次已接受历史。纹理换代保留单调身份表，并按真实变更日志更新相关static/dynamic/emitter slot。整张atlas重装通过 `resourceGeneration` 撤销旧资源引用和重新编译，不推进世界epoch；同epoch的资源目录替换保留 `SourceScene` 的source owner，真实世界重置才更换该身份。预算和场景灯proposal改变按已有当前场景后缀更新处理，不保存旧proposal/场景；维持当前动态模式的近似边界，不声明任意场景变化下的严格无偏性。预算降低仅拒绝超过新支持的路径：NEE端点的 `pathLength < budget`，BSDF-hit/escape的 `pathLength+1 < budget`。

全局重置采用共享封闭黑名单，入口只接受预定义事件类型：世界替换、世界 epoch、场景身份域替换、渲染域、内部尺寸及 RR feature 配置/求值失败。普通 tick、内容修订、动画和采样编号没有全局重置事件。每次请求直接写日志中的 `event/action/valid`，性能录制另保存 `restir.history.reset` / `rr.history.reset` 的 `reason/ignored/valid`；不依赖开启录制才知道原因。首次没有历史是冷启动，实际 scratch/SDK feature 重建另记 `*.history.storage`，不能把未初始化存储标成有效历史。

诊断组的“忽略所有全局重置”默认关闭；开启时忽略上述显式请求，继续执行局部支持拒绝、当前场景更新和提交接受合同。存储实际重建仍冷启动。保留跨世界 reservoir 时，新身份域从已接受修订水位后继开始，避免相同数值 ID 错配旧几何。`restir.history.frame` 的 `temporal/update/revision/accepted` 和 `restir.identity.frame` 的脏范围计数可区分全局重置、局部支持变化与正常后缀更新，不增加逐像素读回或等待。RR 的当前 guide、motion 和完成状态由其[重建合同](reconstruction.md)负责，图像转换见[坐标契约](coordinates.md)；保留历史不构成 SDK 内部逐像素拒绝的保证。显示参数只影响 resolve/display。

前帧 reservoir 在时间阶段完成读取后才被空间轮次覆写；接受时记录实际最终 reservoir bank，支持零轮与任意轮次奇偶。邻居数变化只重建临时候选区，保留持久 reservoir/primary；改变邻域 LUT 不清空历史。距离/PDF 阈值运行中变化后才按需附加两份 source-profile uint 平面（8 B/像素），旧 accepted bank 初次隐式使用 profile 0；每个被选中路径继承原 chart ID，双向重放/shift 各用源路径生成时的四个阈值。不可变表每个不同 chart 占 16 B，保留至屏幕状态重建，以免仍存活样本引用失效；参数改变仅上传这张小表，默认没有这份状态。primary 使用两个 bank，前帧相机和 bank 交换只在宿主接受提交后提交；取消录制不会推进历史。resize、后端切换和关闭沿用已有 GPU 完成或取消证明后回收的规则，不按经过的帧数猜测资源寿命。

## 验证入口

`scripts/test-restir-math.py` 将生产数学模块编译为 Slang CPU 目标并执行原 RNG 对照、RIS/合并统计、flags、Jacobian、pairwise MIS 和全部原配对邻域的互反性质；同时检查实际 BDA SPIR-V 布局。Vulkan 集成测试使用无窗口的真实 ray-query GPU，并与普通 PT 的线性结果对照。编译、数学测试及小场景 GPU 测试不能替代完整游戏画面或正式性能基线。

游戏启动和验收由用户执行，命令见 [CONTRIBUTING](../CONTRIBUTING.md)。应固定路径预算、原生1920×1080、场景和seed，分别记录稳态与内容更新、CPU/GPU时间。检查静止收敛、相机重投影、移动/编辑与纹理动画的失效、透明介质、镂空和模式/尺寸切换。
