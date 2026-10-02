# 镂空表面的 Opacity Micromap

OMM 在资源准备阶段编码已证明的 mip0 coverage，静态 BLAS 通过索引引用同一资源代次的共享 micromap。硬件据此丢弃透明区域或接受不透明区域；最近交点、局部/太阳阴影及大气遮挡查询共同消费这些数据。默认自动检测兼容性后优先启用，设置可关闭；设置改变在帧边界使镂空格子重新构建绑定。未知微三角形继续执行现有 shader coverage，材质和采样模型沿用原路径；共享像素边界的数值归属采用下文明确的性能取舍。

## 语义与支持证明

生产 cutout 条件是 `saturate(textureAlpha × interpolatedVertexAlpha) >= 0.1`，使用 mip0 点采样。普通完整纹理采用 `frac(UV)`；sprite 视图采用局部 UV clamp、backing row stride 和实际 region。不能沿用旧项目的 alpha 阈值，也不能将颜色的射线足迹 LOD 用作 coverage。原几何、UV、顶点 alpha、双侧材质和 `RepeatUv` 均是证明输入。

效率优先级遵循仓库约定：稳态帧数 > 显存节省 > 加载效率。生产模板族有限，运行时未匹配的来源直接使用 shader，不临时扫描像素或烘焙新模板。二值表示最终 coverage 分类，不要求源 alpha 字节只能是 0/255；除已接受的共享边界数值归属外，不以误分类增加二值数量。

| 输入或区域 | 证明与输出 |
| --- | --- |
| 已声明 opaque 来源 | 直接不透明；无需 alpha 扫描 |
| 完整 sprite、顶点 alpha=1、1/2/4 tile 方形及 D4 旋转/镜像 | 资源准备时为八种 UV 方向和两半三角形生成有限模板；MC 的 POT 像素格与微格对齐时优先 2-state |
| 同一有限 UV 模板中的非 POT 像素或跨帧 coverage 差异 | 仅在资源准备时做范围/像素交叠证明，不能证明的微三角形保留四值 unknown |
| 裁切、斜向 UV、其他尺寸或不匹配的顶点 alpha | 运行时不烘焙；除整面均一特殊索引外为 unknown，执行原 shader coverage |
| 整个 sprite 的 alpha 范围与顶点 alpha 上下界足以证明均一 coverage | 使用 opaque/transparent 特殊索引，无需模板；可用于不匹配模板的 UV 或 tint，阈值附近仍为 unknown |
| 双侧来源 | 两侧必须选择相同的模板或相同特殊状态才允许硬件跳过方向选择；不一致时整源三角形为 unknown |
| 非透射的已知介质涂层 | 可按普通 coverage 证明，介质与着色仍从原记录重建 |
| 透射光学边界、未证明的多层组合或随机 alpha blend | 保留 shader 路径；透射边界的阴影吸收与介质访问不能被 OMM 自动接受所跳过 |

资源模板中每个微三角形的所有有效像素都接受时记 opaque，都拒绝时记 transparent，其余记 unknown opaque。四值中的 unknown 不会强制变成已知状态；不设置 `FORCE_OPACITY_MICROMAP_2_STATE`。一块没有 unknown 且设备支持对应层级时压为二值；整三角形均一时使用 opaque/transparent 特殊索引，整块未知时使用 unknown 特殊索引。全部未知的几何不创建 OMM attachment；只有特殊索引的 attachment 不需要共享 micromap storage。

四值是有限资源模板内证明未完成处的局部退路。格对齐模板先利用单 texel 证明；其余模板在准备阶段检查实际像素交叠，保留 clamp 尾部、repeat seam 和完整动画范围。运行时不为任意裁切或其它映射扩展模板族。

### 共享像素边界的性能取舍

格对齐二值证明覆盖微三角形内部。射线精确落在共享 texel 边界时，硬件 bird curve 的微三角形归属和 shader 的 f32 UV 重构、`floor` 取像素可以选择边界两侧不同的 texel。相邻 texel coverage 不同时，开关 OMM 可产生不同结果；这是已经由无窗口 GPU 对照复现的范围，不宣称全域或跨驱动逐 bit 等价。普通 UV 的三角形循环重排只能修复部分方向，不能作为镜像、旋转等映射的通用证明。

此处明确接受这些实际不可见的边界数值差异，性能优先，不为其增加细分、四值 unknown 边缘、三角形重排或每射线补查。数学边界为零面积；实际浮点重构的影响范围依映射、射线和驱动而定，不能从一次相邻 ULP 对照推导所有设备的误差界。涉及像素内部、动画、顶点 alpha、双侧孔洞或光学边界的未知仍须回退，不能将本项取舍推广为其它可见性近似。

维护时保留两类检查：主要 GPU 验收比较普通覆盖与边界邻近射线的实际命中/阴影；独立的精确共享边界诊断记录开关差异，允许本项差异，不能将其测试完成称为位级等价通过。若未来提出严格边界一致要求，需要另行评估四值边缘或几何分割的稳态与构建成本。

## 资源准备与运行时绑定

Java 在显式资源准备批次发布已捕获方块图集的完整 sprite 字典，即使没有 LabPBR 贴图也包括当前地形尚未引用的 sprite。同一源 owner 后续 section 请求只检查一次性准备标志，不重复扫描图集。静态 mip0 共享 atlas 像素，动画发布实际帧图和序列；Rust 验证并持有不可变源，不在地形构建时回查 Minecraft。

兼容设备建立场景资源时，Rust 为每个已发布的 sprite 视图准备完整 sprite 的 1/2/4 tile 方形、D4 八种 UV 方向及两半三角形。均一 coverage 保留特殊索引，其余结果按层级、格式和完整块字节在资源代次内去重，跨 sprite 和 BLAS 共享。每代只有一份 CPU 模板库和一个共享 GPU micromap；空模板库不创建 GPU micromap。关闭 OMM 仍保留并准备资源库，避免之后开启时随区块重烘焙。

启用 OMM 时，表面编译器仅对完整 sprite、D4 UV 映射、alpha=1 的可合并镂空表面采用 1/2/4 tile 方形分解，使重复表面落入有限模板族。裁切、斜向映射或不匹配 tint 保留原矩形分解，不为无法匹配模板的来源增加三角形。已经声明 opaque 的几何沿用既有 OPAQUE 标志，不添加无收益的 OMM 映射。

地形更新只查找模板或均一特殊状态，并为每个实际三角形上传一个 4-byte 全局索引；每个 BLAS 的 usage 按实际映射计数。没有像素扫描、按格子烘焙、块复制或单独 micromap storage 构建。UV、着色记录与 primitive 顺序仍按原几何消费。未匹配的映射、特殊 alpha 和不一致的双侧结果为 unknown，不触发运行时模板增长。

层级随有限模板的 UV texel span 选择，受设备二值/四值上限约束；格证明二值最多 level 10，通用四值最多 level 8，单微三角形范围最多扫描 4096 texels。最终格式必须重新检查设备层级上限。异常大的像素范围保守回退；资源准备的总成本仍随 sprite 数、像素尺寸和动画全集增长，有限模板族不等于固定加载时间。

编码成本为每微三角形 1/2 bit、每唯一块 8-byte descriptor，加上共享的驱动 micromap storage/scratch 和各 BLAS 的索引上传。资源库不会因区块卸载、暂时没有引用或设置关闭而销毁；恢复引用只绑定已有模板。覆盖源改变时整代替换，旧 BLAS 可以继续持有旧代直到重新绑定和最后 GPU 消费完成。

### 诊断计数

OMM CPU 子阶段属于 `static_prepare`，不等于独立 GPU 计时。没有对应工作的帧子阶段与工作次数为零；静态准备总时间还包含编译、打包、BLAS/TLAS 资源和其它工作。

| 字段 | 含义 |
| --- | --- |
| `omm_template_ms` | 本帧 CPU 资源模板准备，包括 alpha 扫描、覆盖证明、编码和全局去重 |
| `omm_bind_ms` | 本帧几何模板选择、特殊状态选择及每 BLAS 索引上传准备 |
| `omm_resource_record_ms` | 本帧共享 micromap 的尺寸查询、资源分配、上传准备与命令录制；独立设备提交路径也可能包含等待 |
| `omm_template_preparations` | 本帧执行资源模板准备的次数 |
| `omm_bound_primitives` | 本帧生成有效 OMM attachment 的三角形索引数，包括其中的 unknown 与特殊索引 |
| `omm_resource_builds` | 当前 scene resource owner 下累计的非空 GPU micromap 构建数；更换 owner 后重新计数 |
| `omm_resource_blocks` / `omm_resource_packed_bytes` / `omm_resource_storage_bytes` | 当前共享资源代次的唯一块数、压缩块字节与 storage lease 字节，全局只计一次 |
| `omm_two_blocks` / `omm_four_blocks` | 驻留 BLAS 各自引用的唯一二值/四值块数之和，同一资源块被多个 BLAS 引用会重复计数 |
| `omm_special_triangles` / `omm_referenced_packed_bytes` | 驻留 BLAS 的特殊索引三角形数，以及按各 BLAS 引用块累计的压缩字节 |

引用压缩字节不等于显存。当前资源 storage 也不包括在途旧代、scratch、上传、BLAS 索引或 arena 保留容量；显存峰值须另测。不能仅按二值比例、资源块数或这些局部子阶段宣称整帧提速。

## 资源代次、动画与失效

纹理像素使用不可变 owner。OMM 依赖 mip0 backing、region 和有效采样证明；RGB/PBR/mip-only 元数据改变不引起覆盖失效。实际 backing 替换仍视为覆盖源改变，不能猜测未扫描的数据相同。已有模板来源的覆盖改变、删除或新 sprite 视图加入都会重建整份 CPU/GPU 资源代次，并使全部驻留镂空 BLAS 重新绑定，避免旧全局索引被解释为新模板。

方块图集全集使后来遇到的普通地形 sprite 不需要追加模板。该全集包括 opaque/translucent 使用的 sprite，资源定义没有几何图层 flags；只有实际静态 cutout 几何才绑定 OMM。后来发布的其他 atlas 或视图仍可能改变资源集合，不能把方块图集的一次准备推广为任意纹理来源永不更新。

scene owner/epoch 的重置可能来自世界切换、捕获重建或离线恢复，不等于真实资源包重载。sprite/texture 数字身份只在所属 owner 内有效；当前实现更换 scene owner 时重新准备资源域，不能仅凭 ID 相同跨 owner 复用模板。用户设置切换、同一 owner 的区块增删和冻结本身不改变资源代次。

Minecraft 源准备在解析实际动画序列时生成一次去重的完整 `coverage_frames` 窗口集合，随后各帧复用同一不可变 Arc；无 mip 的动画也携带该证明。Texture 边界核验全部窗口及 current/next 成员身份。CPU 对实际覆盖的每个像素跨完整集合计算 alpha 上下界；任意源编码域插值和 UNORM8 量化仍位于该区间，所以所有帧在同一阈值侧时可以证明，alpha 字节不必相等。覆盖变化区域为 unknown，其余区域仍可二值或四值已知。

完整帧集合的 OMM 依赖 backing、帧尺寸和全部窗口，不依赖当前 frame/blend。切帧和插值只更新原纹理描述符，不重建资源或 BLAS；backing、全集或尺寸改变则重建资源代次并重新绑定。通用 Texture 没有完整帧集合时，仅可证明静态视图；已知插值但缺少全集的动画使用 shader。后续采样视图改变必须先撤去旧证明，不能从 backing 排布猜测全集，也不能查询原版补齐未知事实。冻结消费同一已证明快照。

目前 OMM 覆盖静态地形及其编译表面。动态对象、实体原型和带实例纹理/UV/tint 覆盖的 BLAS 保留原 coverage；实例修改不能复用基于未覆盖原型的 opaque 证明。扩展到这些对象须证明覆盖设置和动态 alpha 的失效，或使用有对应构建能力的实例禁用协议。

## Vulkan 与完成证明

Java 在两版 Minecraft 创建逻辑设备时可选协商 `VK_EXT_opacity_micromap`、`micromap` 及 synchronization2 依赖。只有实际创建的设备启用了这些能力才在 attach 协议 bit0 中声明；物理显卡支持不能代替已启用证明。不兼容设备继续使用原 coverage，OMM 不增加启动的强制显卡要求。独立无窗口设备也进行可选协商。

当前采用 EXT 路径，compute Ray Query 自动消费 BLAS 的 OMM，不向 compute pipeline 添加传统 ray tracing pipeline 的 OMM flag。KHR-only 路径需要另行处理 shader execution mode，不能将 EXT 的条件直接搬过去。规范依据：[EXT 扩展](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_opacity_micromap.html)、[构建输入与对齐](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildMicromapsEXT.html)、[BLAS 映射](https://docs.vulkan.org/refpages/latest/refpages/source/VkAccelerationStructureTrianglesOpacityMicromapEXT.html)。

构建顺序为上传 → micromap build → BLAS build → TLAS/查询，OMM 边界使用 synchronization2 的 micromap stage/access。data 与 descriptor device address 分别按 256-byte 对齐，scratch 按设备 AS scratch alignment，storage offset 按 256-byte 对齐。micromap build usage 统计唯一块；BLAS attachment usage 统计映射后的实际三角形次数，排除特殊索引。

CPU 数组/pNext pointee 活到尺寸查询和命令录制结束；GPU data/descriptor/scratch 活到 micromap build 完成，index 活到 BLAS build 完成。当前资源池和引用它的 BLAS 共同持有资源代次；替换后的旧代在旧 BLAS 引用消失后才进入 handle/storage 退休，实际释放或 arena 复用仍等待真实提交完成值。CPU 引用归零和 GPU 完成分别证明，不按帧数猜测。退役先处理 BLAS 再处理 OMM；关闭借用 renderer 必须证明全部录制命令完成，失败仍遵守宿主资源隔离契约。

## 验证与结论边界

CPU 测试验证 bird curve 顺序、像素格证明、阈值/alpha 上下界、二值降级、有限模板匹配与未知退路；无窗口 Java 源测试验证无 LabPBR 的完整 atlas 字典和一次性准备。GPU 测试用生产 shader 对照 OMM 开/关的命中与阴影，并覆盖资源共享、纹理更新、动画、开关、区块引用移除及完成后回收。GPU 未支持或未执行不能算通过。测试入口与游戏手动检查见 [CONTRIBUTING](../CONTRIBUTING.md)。

正式性能比较固定原生 1920×1080、场景、seed、射线预算、硬件与工具链，分别记录 Realtime/Offline、CPU/GPU、稳态/更新及离群值。OMM 数量、局部 GPU 夹具和源结构分析都不能代替完整游戏帧时证据；共享边界的已接受差异按上文单独报告。

实现入口：[CPU 证明](../crates/prime-vulkan/src/omm_cpu.rs)、[OMM 资源](../crates/prime-vulkan/src/omm.rs)、[静态格子](../crates/prime-vulkan/src/geometry.rs)、[纹理依赖](../crates/prime-vulkan/src/textures.rs)、[设备与退休](../crates/prime-vulkan/src/resources.rs)。
