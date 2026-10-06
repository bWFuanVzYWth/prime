# Streamline、DLSS Ray Reconstruction 与帧生成

实时重建使用 NVIDIA Streamline 2.14.1 的 `kFeatureDLSS_RR`（DLSS-D），搭配 DLSS 310.9.1，所有档位显式指定 preset F。来源为 [Streamline 官方 release](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.1) 与 [DLSS 官方 release](https://github.com/NVIDIA/DLSS/releases/tag/v310.9.1)；SDK 文件、许可和校验信息保存在 `third_party/`。不调用旧项目的直接 NGX 后端。

## 控制和职责

默认请求开启降噪，RR 默认 Performance：输入宽高各为输出的一半，例如 960×540 → 1920×1080。可在设置中选择 DLAA、Quality、Balanced、Performance、Ultra Performance，RR 尺寸以 `slDLSSDGetOptimalSettings` 的实际结果为准。RR 不参与离线累积。无兼容 SDK 能力、初始化或配置失败时保留 guide 管线，以原生分辨率生成当前帧颜色和 guides，使用空间降噪并记录原因；安全的 evaluate 失败从本帧起使用现有输入尺寸的空间降噪。失败 SDK 不逐帧重试，只有重新创建降噪 owner 才重新初始化。设置开关不是实际 RR 启用证明。

独立诊断 `native_noisy_output`（Java `nativeNoisyOutput`）默认false。开启时禁用降噪，主射线和输出都采用原生分辨率；关闭时允许上述 RR 配置。`DiagnosticView::NoisyColor` / `View.NOISY_COLOR` 只预览实际内部含噪输入，不改变主射线尺寸或降噪请求，Performance 下仍是半宽半高输入。两者不能复用同一布尔字段；旧设置中的 `ray_reconstruction` 仅在磁盘迁移入口解释并取反。

Java 仅负责两版宿主逻辑设备能力、同目录 runtime 提取、真实 Present 路由和设置字段。Rust 负责尺寸、抖动、相机历史、GPU 输入/输出、调度、描述符与完成证明。`native/streamline` 的 C++ 静态桥接封装 SDK C++ 类型，以固定 C POD ABI 接收借用句柄和常量；指针只在调用期间有效，图像对象保留到 GPU 完成。

Vulkan 1.2 宿主还须启用 `shaderStorageImageWriteWithoutFormat`：Streamline 自带的 clear shader 声明了该能力。仅启用 Prime guide 格式需要的 `shaderStorageImageExtendedFormats` 不足以运行 SDK。`shaderStorageImageReadWithoutFormat` 不属于此处已核实的要求。

RR 能力还要求实际启用 `VK_KHR_synchronization2` 和 `synchronization2` feature，复用宿主相同的 feature 结构。NGX 的部分同队列、同布局屏障依赖该 feature 忽略布局字段的语义；物理设备 API 版本达到 1.3 不代表 Vulkan 1.2 宿主已启用此特性。设备 feature 与同步验证开关是不同概念。

游戏的 early interposer 路径另要求实际 Vulkan 1.3 和 `privateData` feature，后者通过单独的 `VkPhysicalDevicePrivateDataFeatures` 查询/启用，不把已有 synchronization2 重复塞入一个1.3结构。SDK在创建宿主逻辑设备时就构造 private-data slot，因此这项不以当前是否选 PT/RR 为条件。仅有物理设备支持不构成实际启用证明。

现有 native CPU 诊断区分 `rr_requested`（实时降噪设置）、`rr_capable`（宿主已启用 SDK 能力）、`rr_ready`（SDK 运行时初始化且未失败）及 `rr_evaluation_succeeded`（当前历史最近一次已接受提交的 SDK 录制成功）；`rr_input`/`rr_output` 给出实际内部与输出尺寸。空间降噪继续推进已接受相机历史，但不把它报告为 SDK 成功；SDK 不可用或失败时 `rr_ready` 与成功标记均为 false。录制成功仍不等于 GPU 完成或画质验收。`rr_error` 保留初始化、配置或 evaluate 的失败原因，空间降噪时仍可查询；历史重置后不沿用成功标记。

Streamline 是进程级状态，一次只允许一个 Prime RR owner。游戏在 LWJGL 创建 Vulkan loader、instance 和 device 前加载锁定的 `sl.interposer.dll`，RR/FG/PCL/Reflex 共用这一次初始化；SDK 的自动设备接入不再重复调用仅用于 manual 模式的 `slSetVulkanInfo`。世界 PT、RR 和显示仍在宿主 command buffer/queue 中录制，真实 acquire/Present 由 interposer 接管。标题/加载帧也通过进程 native 入口调用一次 Present，不等待世界 owner；FG 插件加载但关闭时仍可能异步接管，错误由公开 SDK 回调保留，不要求同步写回 `pResults`。独立 manual RR 诊断仍保留，与 `-Interposed` 检查分别验证。固定 SDK 的设备与 Present hook 合同必须在升级时一起复核，详见[native 桥接](../native/streamline/README.md)。

两条初始化路径均启用 `eUseFrameBasedResourceTagging`，使资源标记属于真实 frame token 与 viewport；只有 late RR 启用 manual hooking。SDK 要求该标志配合按帧标记接口，不能以 `slSetTagForFrame` 返回成功代替初始化模式的证明。取消或释放当前 FG 标记只清理相应 token，其他帧的最后消费者证明仍独立有效。

采样sequence与SDK逻辑帧编号独立。manual RR 每次evaluate让 `slGetNewFrameToken` 自动推进SDK内部编号，不把重复零、间隔或采样编号回绕复用为同一SDK token；interposed路径仍使用真实宿主 `prime_sl_frame` 创建的共享RR/FG token。采样编号本身不请求全局历史重置。

## 图像和坐标合同

普通 PT 实时采用 K1主表面/delta/guides → K2主要输运 → post 三段。RR的K1写入主表面guides及首纯delta透明面的独立反射运动；K2只可能完成一个已移交的实际反射次段距离。`realtime_rr.slang` 合成FP32 prefix+tail、执行原相机空气段aerial与清洗，写入noisy color，并补全其余像素的反射运动。原始实时不分配RR图像，Offline不进入这两个实时PT kernel。阶段布局见[PT设计](pt-state-design.md)。[ReSTIR PT](restir-pt.md) 在自身生成阶段共享首交点并发布同合同的独立规范 guides，在 resolve 写入最终重采样 radiance 与 aerial；不执行普通 PT 的照明阶段。

RR主射线在像素中心加CPU Halton帧采样偏移；Streamline接收投影位移，XY均为采样偏移的相反数，单位是输入像素。相机矩阵不含抖动；普通 PT 的 coverage 与照明随机域继续由 Z-Sobol 产生。ReSTIR 保留原 TinyUniform RNG 与路径 seed/位置哈希 coverage；首 guide 共享实际首交点及所选材质，独立 guide 后缀使用既有 Z-Sobol coverage 域。guide 从同一主射线开始，之后按确定的几何事件选择独立终点，不随照明的首透明0.5分支或roulette改变。RR/FG 图像、最终 swapchain 与 SDK 相机统一为 canonical 左上；像素中心、矩阵布局及 UI 呈现转换见[坐标契约](coordinates.md)。

| Binding | 图像/常量 | 格式与语义 |
| --- | --- | --- |
| 10 | noisy color | RGBA16F，场景线性BT.709，包含完整prefix+tail与aerial，无曝光和显示变换 |
| 11 | depth | R32F，选定主表面/PSR代理的正向view-Z，世界单位；真实escape为FLT_MAX |
| 12 | motion | RG16F，未抖动投影的previous pixel − current pixel，输入像素单位、左上原点；未知物体/光学对应使用相机重投影代理，非法投影写有限零并标记近似 |
| 13 | normal/roughness | RGBA16F，选定表面的世界空间单位着色法线或PSR变换法线，以及有效粗糙度 |
| 14 / 15 | diffuse/specular albedo | RGBA16F，原BSDF方向能量转线性BT.709；首透明接口specular例外见下文 |
| 16 | 内部specular hit distance | R16F，实际反射次段物理距离，供post粗糙反射代理使用；环境miss为65504，无对应样本及K1显式R guide为0；不作为SDK tag |
| 17 | camera history | 144B uniform，每个完成槽独占；当前/前相机、采样抖动与历史有效性，前相机转换到当前scene anchor |
| 18 | RR output | 输出分辨率RGBA16F，线性BT.709 |
| 19 | 引擎内部guide状态 | R8_UNORM，整数flags除以255存储：1为主guide未完成，2为反射guide未完成，4为K1显式反射运动，8为真实首物理交点前景，16为表面代理，32为运动代理，64为预算代理，128为尚待真实终点替换的候选；不作为SDK tag |
| 20 | specular motion | 全图RG16F，与主motion同为输入像素位移；K1稳定反射终点或post局部距离代理；缺失时复用主motion并标记近似 |
| 21 / 22 | FG depth / motion | 开启 FG 时额外分配 R32F 硬件 device depth 与 RG16F 首可见物理表面运动；使用 K1 已有首交点，独立于提升后的 RR depth/PSR；真实天空深度为1并使用方向运动 |

桥接将binding20标为 `kBufferTypeSpecularMotionVectors`，由DLSS-D传入NGX的specular motion输入；不使用 `kBufferTypeReflectionMotionVectors`，也不再标记 `SpecularHitDistance`。因此不存在依赖SDK未声明优先级的双输入选择。两张motion图均使用输入像素位移，桥接的 `mvecScale=(1/inputWidth, 1/inputHeight)` 经Streamline乘输入尺寸后使NGX scale为1；抖动不混入motion。内部投影helper的无效哨兵不进入图像，不依赖 `motionVectorsInvalidValue` 被DLSS-D识别。历史重置时运动为零。

working Rec.2020只在RR边界转线性BT.709，结果转回working后执行primeDRT。半浮点颜色有限化并限于0–65504；这是重建边界的动态范围限制，prefix/tail与离线输运保留FP32合同。线性显示路径先在输出尺寸选择 RR 或本帧空间降噪，再做星图、曝光和 SDR/HDR 显示；星图不经过直接相机 RR 降噪，间接环境照明仍消费真实星图。软件路径也要求相同 guide 图像格式的 sampled/storage 能力；图像分配、完成证明或必要设备格式失败走帧失败契约，不隐式输出含噪颜色。显示合同见[display.md](display.md)。

## 主表面提升与完成状态

K1穿过整闭包为纯delta的前缀，在第一个非delta表面发布主guide，真实escape单独发布方向guide。粗糙首面只需要一次主查询，不进入delta或0.5策略。规范主guide优先实际IOR可透射方向；真实TIR与conductor使用反射。首纯delta透明面可透射时同时建立独立R guide，用于可见接口的反射运动。两条guide的方向接口不读取Fresnel抽样概率、response/PDF、吸收或roulette。

照明选中的R或T事件共享对应guide生产者给出的方向、安全起点和后续公共查询；另一条guide保存为64B companion seed。后续分歧或照明提前终止时保存当前guide，照明结束后在同一K1中依次完成独立后缀。两条guide从同一主交点和材质开始，终点不随首面0.5照明分支或roulette改变；没有独立第三个光追dispatch。

guide使用独立深度及同值最大顶点预算N。照明零beta、强吸收或roulette终止不能结束guide；第N次查询得到非delta或escape仍算完成，仍需下一跳才标记预算耗尽。预算内继续补齐真实终点，沿途纯delta接口只作备用候选。候选直接暂存于已有 guide 图像，保留第一个几何可用接口，使光学变换次数最少；不随照明随机分支重新排名，不增加候选队列。预算耗尽时保留所选候选的实际材质、法线和一致深度/运动代理，清除待完成状态并标表面与预算近似，不把它声明为真实非delta终点。反射 guide 未找到终点时保留已观察的反射候选，或使用主motion的密集代理。没有可用候选的非法几何仍保留未完成状态和有限占位，交给空间降噪；不读取旧帧 guide，也不把照明停止点当作已观察终点。

PSR以32B quaternion/parity、仿射平移和flags表示 `F(x)=A*x+t`，按真实物理交点与光学法线合成反射平面，不把射线安全偏移计入代理几何。终点平面与法线经相同变换后，与实际带采样抖动的相机射线相交，得到一致的depth、normal与motion锚点；diffuse albedo取该终点的方向能量。原相机空气段长度另存给post，不能由promoted depth覆盖。可几何透射的首纯delta透明接口保留可见接口的specular albedo，终点guide不覆盖它，也不乘首面抽选权重或路径beta。独立R guide只写反射运动与完成状态，不重复写主guide或读取albedo能量LUT。

静态直视表面与静态反射链代理使用相机重投影。固定平面镜链可建立一致虚拟表面；厚折射使用终点切平面与相机射线相交的局部代理，但不是逆Snell对应或精确成像Jacobian。持久实例以稳定 instance ID 保存最近一次实际接受提交的仿射姿态；有序原型位置逐位一致时可对应前态，纯材质/颜色变化不丢失对应。局部顶点整体平移沿用旧项目的首顶点归一化：前后减去各自首顶点后位置逐位一致，且两侧加回首顶点均逐位恢复源位置，才建立对应。位移在f64中与已接受仿射姿态和scene anchor合并，最后写入既有3×4前态；它将当前局部点映射至前帧物理点，不一定等于原封不动的旧pose。该检查只在局部几何变化时扫描，不复制或重写源顶点，也不增加GPU记录或shader字段；FP32姿态/投影仍有既有精度边界。

K1 从真实 object-to-world/world-to-object 得到前帧物理点，静态光学链上的刚性终点可经同一 PSR 变换形成前代理点。具名 CustomGeometry 用实际完整调度与单次 callback 输出范围建立稳定实例，源位置已相机相对，按捕获相机原点解读；双版本真实源产物经既有 typed 场景和 K1/FG guide 验证，详细身份和回退边界见[具名来源合同](capture-boundaries.md#具名-customgeometry-来源)。这类 baked 顶点变化仍有 CPU 比较、原型发布和 BLAS 构建成本。新身份、raw 回退、归一化不能往返、局部形变/拓扑变化、运动光学接口和未知对应缺少精确运动信息；RR保留已观察终点的深度、着色法线、粗糙度及albedo，仅将motion降为相机重投影代理并标记近似。非法PSR平面优先使用已有候选；没有候选时用真实表面到相机的距离构造相机射线上的局部代理，并保留真实材质，仍标近似。投影不能形成有限运动时输出零。匿名聚合 raw 缺少实际 owner/submission/part，不能仅凭相似几何猜跨帧身份。骨骼的各独立稳定刚性叶节点可按各自身份对应，这不代表已建立一般变形顶点历史。该检测不覆盖后述K2粗糙反射次段，不能把平面镜性质外推为任意曲面、移动镜面或一般折射链。

真实escape使用当前相机射线的方向代理（D0）：只产生相机旋转运动，不产生平移视差，也不伪造有限表面。经过反射/折射链的天空同样使用该近似，未声称恢复实际光学链的环境方向对应。静态相机的帧采样抖动不应产生运动。

普通不透明/粗糙像素不增加反射查询。K2仅在明确移交时记录实际所采首反射续接次段距离。roulette、diffuse事件或没有续接等导致缺样本时距离为0，post直接复用K1的真实主运动向量，包括已证明的物体刚性运动；不能把运动主点重新投影成静止物体。仅此分支读取既有主motion图，不增加图像或跨查询状态。

非零距离仍由post按主view depth恢复相机射线上的主点，再沿该射线增加距离，构造局部虚拟点并重投影。这个分支只估计相机运动，不检测或补偿次段目标的物体运动，也不是GGX反射终点的精确光流。粗糙反射次段miss写距离65504，仍按有限远点投影，不能视为严格无平移视差的天空。只有主depth为天空或K1显式guide真实escape时使用D0方向代理。K1显式R guide仅输出反射运动，其距离保持初始化的0；状态值4使post保留该运动，不再计算或存储无消费者的R距离。SDK始终消费完整的specular motion图，而非仅透明像素有效的稀疏输入。

`rr_display.slang` 与 `rr_linear.slang` 在输出分辨率显示。SDK录制成功且当前双线性输入足迹的状态满足 `(flags & 3)==0` 时消费RR输出；表面、运动、预算近似位不阻断RR。其余像素和不可用/安全失败的SDK使用本帧5×5空间降噪，按空间距离、相对view-Z、单位法线夹角、粗糙度、diffuse/specular albedo差距、真实首交点前景/天空类别加权；非法guide另使用同前景类别的3×3均值。它没有额外全屏图像、dispatch或时间历史，不以有噪中心颜色拒绝邻居。显式noisy诊断仍显示真实含噪输入，正常输出不再回退直接含噪颜色。异常导致命令录制状态不安全时直接走帧失败/退休契约，不读取可能处于未知布局的输入。诊断色/深度/法线显示实际内部数据，可用SDK仍正常evaluate，使模型与已接受相机历史同步推进；返回最终输出不重置。这意味着诊断显示仍有正常 RR 的 GPU 成本。仅写宿主目标时翻转Y。

低两位声明最终输入是否可用；16/32/64是主与反射guide共享的本帧保守近似提示，可靠终点替换部分分量后仍可能保留，不承诺每个最终分量的精确质量分类。内部状态不保证SDK内部历史隔离、邻域空间滤波隔离或恢复有效后的历史清除。旧项目设置中的 `[-1, 1]`、默认 `-0.25` 输入为 **RR Responsivity Mask**：输入分辨率的单通道 R16F，旧直接NGX桥接通过 `pInResponsivityMask` 提交统一的有符号值；该值不是引擎guide完成标记，也不是 `BiasCurrentColorHint`。锁定 RR 手册限定此输入用于 Preset F：正值提高响应速度但可能增加闪烁，负值降低响应速度但可能增加拖影，0无偏置。它是SDK可调的响应偏置，不声明严格的逐像素历史拒绝。`BiasCurrentColorHint` 虽在锁定 Vulkan 插件中转发，RR 手册引用的最新 SR 模型指南不建议使用该输入，不能据通用 tag 注释宣称 RR 硬拒绝保证。

锁定的 Streamline 2.14.1 官方源码读取、缓存并转换 `kBufferTypeResponsivityMask` 的资源状态，但 DLSS-D 的 Vulkan 分支缺少向 NGX 设置 `NVSDK_NGX_Parameter_DLSSD_ResponsivityMask` 的步骤；设置该资源指针只出现在 D3D 分支。公共NGX evaluate包装直接传入已有参数，不补齐这个输入。因而当前Vulkan集成不暴露此设置，不分配或标记一个没有已核实消费通路的图像。恢复该输入需要修正并验证DLSS-D插件的Vulkan参数转发，或另行评估直接NGX后端；仅新增通用tag不能证明模型收到它。SDK与来源锁定见 [SDK说明](../third_party/streamline/README.md)。严格SDK reset仍是整个viewport/frame级别；不为按像素条件增加CPU读回或整帧等待。

## 仍可能影响降噪的信息偏差

[锁定版本的官方 RR 输入合同](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/docs/ProgrammingGuideDLSS_RR.md#40-tag-all-required-resources)要求线性albedo、单位着色法线、线性roughness，以及包含相机和动态对象运动的密集motion；depth必须对应同一运动锚点。直接specular motion与hit-distance加矩阵是两种替代输入，并非缺少SDK hit-distance tag就缺少必需输入。当前表中偏差来自生产者已知边界，影响是待实际画面对照验证的风险，不是已测量画质结论。

| 情况 | 当前信息与真实信息的差距 | 可能影响 |
| --- | --- | --- |
| 有限预算的纯delta链 | 已观察接口取代尚未访问的非delta终点；其材质是真实接口材质，不能代表后方表面的normal/roughness/albedo/depth | 后方纹理、反射或折射内容可能被按接口特征重建；近似位明确保留 |
| 新身份、匿名raw、一般形变/拓扑变化 | 缺少精确前帧顶点对应；RR以相机motion代理，FG首可见motion仍有限零 | 静止相机下真实物体运动未得到完整补偿，可能拖影或纹理滑动；不能用相似几何猜身份 |
| 运动镜面或折射接口 | 缺少前帧整条光学链的接口姿态和光学映射；采用当前代理面的相机重投影 | 反射/折射内容的真实光流与代理可能不同；刚性终点运动不能补齐运动接口 |
| 厚折射、曲面和掠射PSR | 厚折射是终点切平面代理，没有逆Snell对应或成像Jacobian；退化平面使用候选/局部range代理 | depth、normal与实际屏幕光流可能失配，尤其斜视、曲率大或多层介质 |
| 粗糙反射次段 | 距离来自本帧实际照明样本；零距离只复用主motion，非零距离只估计相机运动，没有次段目标前态 | GGX随机方向、缺样本和运动反射目标可使specular motion抖动或滞后；ReSTIR最终复用路径与初始距离并非同一条路径 |
| 光学链天空及反射miss | 真escape统一用原相机D0方向代理；粗反射miss仍是距离65504的有限远点 | 未恢复光学链实际环境方向对应；有限远点还存在微小平移视差 |
| R/T混合、体吸收、发光、随机coverage | noisy含完整照明混合、吸收及发光；guide只选一条确定光学终点，首透明specular来自接口，不乘路径beta；未提供独立透明overlay/color-before-transparency | 单套guide不能代表所有辐射分量；薄粒子、界面边缘、发光或强吸收可能需要额外分层信息，不能把照明调制冒充材质albedo |
| 半浮点颜色/运动和clamp | SDK边界颜色限0–65504并由working转BT.709；运动为RG16F；albedo规范到0–1 | 极亮光源、高饱和宽色域以及大幅运动存在精度/范围差距，未声明与FP32输运等价 |
| 未解析非法guide | 必需图像有限占位不代表真实表面；内部状态不进入SDK，也没有已核实的Vulkan Responsivity Mask消费通路 | SDK内部可能仍受占位邻域/历史影响；输出空间退路不能保证SDK内部按像素隔离 |
| 软件空间降噪 | 单帧有限邻域，无时间累积、真实反射光流或大范围方差估计；材质albedo和粗糙度差距用于保护边界 | 能减少局部噪声，不能等同RR稳定性；相同guide的照明/反射细节仍可能变软，窄区域或孤立边界因可用邻居有限可剩余噪声 |

预算代理和运动代理允许RR继续消费有用数据，补齐的是已有预算和源合同能提供的信息；表中未获取的前态、光学成像或辐射分层不伪装为真实已知数据。软件路径保证正常输出执行降噪操作，不保证任何场景完全无噪或保持所有细节。

## 历史、同步与成本

无可用历史的首次帧、实际图像尺寸/质量重配、实时/离线或 RR 开关切换、世界/source owner 或 epoch 更换及实际 evaluate 失败是全局失效边界。不能确认整个复用域已失效时继续接收历史，交给 motion、完成状态和模型更新处理。采样编号归零、回绕、跳号、随机 seed、暂时跳帧、局部 atlas 更新、纹理资源 owner/generation、积分器、顶点预算及光源采样方式变化不再隐式清除 RR 历史；重建 PT 管线时重新绑定所有 RR descriptors。相机平移、旋转和 FOV 改变使用重投影；不以距离/角度阈值推断整个画面已改变。scene anchor 变化先对前相机精确重定位，再计算相对运动。局部源、纹理和实时天文/太阳/天空/星光更新保留重建历史；曝光/primeDRT 改变不重置场景线性历史。

RR与ReSTIR共用预定义全局重置事件黑名单，入口不接收任意字符串理由；每次请求直接记录日志中的 `event/action/valid`，性能录制另保存 `rr.history.reset` 的 `reason/ignored/valid`。默认关闭的“忽略所有全局重置”可保留显式请求前的历史；SDK feature或图像存储实际替换仍另记 `rr.history.storage` 并冷启动。首次无历史是冷启动，不伪装成epoch更换。没有逐像素读回或额外等待。采样 sequence 只选择随机域，SDK frame token 表示实际帧，二者不能混用。

输入/输出图像跨帧复用，同一宿主队列的写后读、读后写依赖覆盖 K1 → K2 → compose → RR → 显示及下一帧；K1的prefix/guide写入、K2距离写入和post完成状态/反射运动写入都必须对后续消费可见。可变描述符和144B常量按宿主 timeline 完成槽复用。录制返回与 `encoder.execute` 不提交时间历史：两版实际 `Submission.close` 成功接受对应 serial 后才同时提交相机与实例姿态；未提交/失败录制不推进。纯姿态变化的元数据在接受后另有一次回归当前姿态的稀疏 settle 更新，稳态不扫描全实例。

RR与软件路径稳态没有像素读回、额外应用队列提交或逐帧 CPU 等待。重新配置/释放 RR 私有资源前等待其最后真实使用 serial；SDK 调用失败也可能已经录制命令，不能立即释放。软件路径保留owner和guide图像直到它们最后一次真实消费完成。首次evaluate失败后的下一帧，在降级/移除FG输出图像之前先取得SDK Present消费者的完成/取消证明；不在尚未提交的失败录制内等待当前世界serial。本帧线性显示路由沿用evaluate之前绑定目标时的决定，SDK失败关闭FG不取消已绑定线性图像到宿主目标的显示。无法取得完成证明时保留 SDK owner、DLL 和 GPU 资源。FG 另有 SDK Present 消费者，不能用世界 serial 代替其完成证明。

七个SDK输入共44 B/内部像素；内部距离2 B与状态1 B另计，合计47 B/内部像素，输出8 B/输出像素。新增specular motion图为4 B/内部像素，约2.07 MB/960×540或8.29 MB/1920×1080。默认Performance在1920×1080输出下，显式图像约40.95 MB；DLAA原生1080p约114.05 MB，均为十进制MB，不含普通 PT 的240B/内部像素RR scratch、ReSTIR 的屏幕状态、分配对齐、SDK内部历史/暂存、原有场景与宿主目标。ReSTIR 的64B/内部像素反射 seed 在生成阶段借用尚无消费者的 replay 区，不新增常驻全屏 buffer。FG 的可见 depth/motion 另加8 B/内部像素；实例运动元数据由48B增至96B，原始/离线 shader 特化不消费前态字段。显示、HUDless 和覆盖图的完整成本另见[显示合同](display.md)。

全图写读、独立guide后缀、仿射投影、重建和显示都是真实成本；物理寄存器、保存流量与整帧收益仍须测量。预算候选借用已有图像，不新增候选buffer；首次可用候选仍增加一次guide能量计算和写入。空间路径每个输出像素最多取25个输入邻居，分别读取color/depth/normal/status/diffuse/specular六组已有图像。在1920×1080全图软件fallback时约5184万逻辑采样点，邻域缓存复用与实际带宽须测量；它没有额外pass，但不会因此成为免费操作。SDK缺失时使用原生输入及既有RR图像布局，图像/scratch显存成本接近DLAA且没有SL私有历史。降低分辨率会减少PT射线数量，不能把性能档帧率称为原生1080p性能。正式对比先用DLAA保持原生1920×1080、场景、种子和预算一致，再单独报告超分档位。

CPU/ABI/shader/桥接行为检查不证明实际模型质量、窗口呈现或整帧速度。两版实际游戏与驱动画面由用户按 [CONTRIBUTING](../CONTRIBUTING.md) 验收，重点检查运动、遮挡显露、细叶、多层透明/镜面、guide预算耗尽率、恢复有效时的历史行为、切换、窗口变化和退出重进。

## 帧生成与最后消费者

FG 默认关闭，只在实时 RR 最终输出、锁定 SDK 报告支持且实际呈现尺寸/格式满足其要求时请求一个生成帧。PCL/Reflex 使用同一真实帧 token，依实际 begin、模拟结束、渲染结束与 Present 标记推进；不虚构 acquire/Present，也不在应用里额外 evaluate 一个“生成帧”来代替 interposer。

F3在统计有效时根据SDK报告的呈现数量估算FPS，并附原始渲染FPS；`Minecraft.getFps()`及CPU帧时/限帧语义保留。能力和运行状态每个准备帧共用一次GetState，成功准备后记录其中的 `numFramesActuallyPresented`（包含真实帧，生成帧可被丢弃）。锁定SDK getter复制缓存而不清零；不能把同帧重复查询当成新的呈现，也不能按返回值相等跨帧去重。退休查询仍取得原输入完成证明，但不计入统计。遵循[官方Vulkan样例](https://github.com/nvpro-samples/vk_streamline/blob/main/main.cpp#L434)每帧只读一次的规则。

40B快照只读取CPU标量，现有API锁暂忙返回1而不等待；关闭、失败、域变更重置epoch。Java最多4Hz读取，用单调至少1s窗口算率；低帧率或长读取间隔遇到同epoch的新样本时按完整elapsed求平均。inactive/invalid下次读取撤销，持续busy或sample不进展1.5s回到原版；已观察到中断后恢复须重新暖窗口。正常消费时关闭信息最多250ms被读取，实际低帧率会延长显示更新时间。首次暖窗口、无SDK及软件空间滤波不伪造FG倍数；此统计不等于每帧显示扫描/pacing测量。

Rust 在最后的手部/HUD 合成后准备 HUDless 和真实 UI 覆盖，借用实际 swapchain 数量、格式及尺寸后向 SDK 标记。SDR HUDless 为编码 RGBA8；HDR HUDless 为以80nit等于1的线性 scRGB FP16，UI覆盖为 R8。不能将 SDR 编码图当线性 HDR 输入，也不能用恒零 UI mask 掩盖 HUD；源合成恢复的近似边界见[display.md](display.md)。

FG 的输入仍可能被 SDK 的 Present 队列使用。更换尺寸/输出/质量、关闭 FG、世界/后端切换及销毁前，先读取 `DLSSGState.inputsProcessingCompletionFence` 和对应实际值，取得完成证明后关闭选项并释放 SDK 资源，再退休应用图像。未进入 Present 的输入通过关闭/释放取消，实际 SDK 等待失败则隔离所有者。正常图像复用依同一图形队列和 SDK 的 Present 队列约束，边界才执行有界等待；应用世界 timeline 和“经过几帧”都不是该消费者的证明。进程 SDK 在真实宿主设备关闭时最终退休。

诊断提供 `fg_requested`、`fg_capable`、`fg_last_prepare_succeeded` 和 `fg_prepare_serial`，分别表示设置请求、SDK能力、最近一次输入准备及对应 serial；这些状态本身不证明已经显示生成帧。FG质量与真实呈现已按用户确认验收关闭；帧数显示已接入实际SDK计数，窗口与外部呈现工具对照、未覆盖的HDR/显示器范围由用户检查。严格 RR Vulkan 检查仍必须零验证错误，有效有限输出或 SDK 返回成功不能覆盖内部同步失败。

ReSTIR既有质量问题与FG呈现验收已按用户确认关闭；现存萤火虫及水下收敛振荡独立见[问题登记](../HACK.md)。当前5×5空间滤波是临时hack，不符合最终时域重建目标，不计作正式降噪后端完成。SDK严格同步和未支持来源边界仍独立维护。
