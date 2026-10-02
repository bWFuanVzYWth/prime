# Streamline 与 DLSS Ray Reconstruction

实时重建使用 NVIDIA Streamline 2.14.1 的 `kFeatureDLSS_RR`（DLSS-D），搭配 DLSS 310.9.1，所有档位显式指定 preset F。来源为 [Streamline 官方 release](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.1) 与 [DLSS 官方 release](https://github.com/NVIDIA/DLSS/releases/tag/v310.9.1)；SDK 文件、许可和校验信息保存在 `third_party/`。不调用旧项目的直接 NGX 后端。

## 控制和职责

默认请求开启 RR，默认 Performance：输入宽高各为输出的一半，例如 960×540 → 1920×1080。可在设置中选择 DLAA、Quality、Balanced、Performance、Ultra Performance，尺寸以 `slDLSSDGetOptimalSettings` 的实际结果为准。RR 不参与离线累积。无兼容设备能力或 SDK 初始化失败时使用原生分辨率原始输出，并记录原因；设置开关不是实际启用证明。

Java 仅负责两版宿主逻辑设备能力、同目录 runtime 提取、真实 Present 路由和设置字段。Rust 负责尺寸、抖动、相机历史、GPU 输入/输出、调度、描述符与完成证明。`native/streamline` 的 C++ 静态桥接封装 SDK C++ 类型，以固定 C POD ABI 接收借用句柄和常量；指针只在调用期间有效，图像对象保留到 GPU 完成。

Vulkan 1.2 宿主还须启用 `shaderStorageImageWriteWithoutFormat`：Streamline 自带的 clear shader 声明了该能力。仅启用 Prime guide 格式需要的 `shaderStorageImageExtendedFormats` 不足以运行 SDK。`shaderStorageImageReadWithoutFormat` 不属于此处已核实的要求。

RR 能力还要求实际启用 `VK_KHR_synchronization2` 和 `synchronization2` feature，复用宿主相同的 feature 结构。NGX 的部分同队列、同布局屏障依赖该 feature 忽略布局字段的语义；物理设备 API 版本达到 1.3 不代表 Vulkan 1.2 宿主已启用此特性。设备 feature 与同步验证开关是不同概念。

现有 native CPU 诊断区分 `rr_requested`（实时设置）、`rr_capable`（宿主已启用能力）、`rr_ready`（运行时初始化且未失败）及 `rr_evaluation_succeeded`（当前历史最近一次 SDK 录制成功）；`rr_input`/`rr_output` 给出实际内部与输出尺寸。录制成功仍不等于 GPU 完成或画质验收。`rr_error` 保留初始化、配置或 evaluate 的失败原因，切回 raw 后仍可查询；诊断/历史重置期间不沿用成功标记。

Streamline 是进程级状态，一次只允许一个 Prime RR owner。桥接采用手动 Vulkan 集成；每帧世界 PT、RR 和显示仍在宿主同一 command buffer/queue 中录制。宿主真实 `vkQueuePresentKHR` 经过桥接通知 Streamline，返回实际 Vulkan 呈现结果，不伪造 Present，也不吞掉窗口尺寸变化的错误。固定 SDK 版本的 Present hook 兼容关系必须在升级时一起复核。

## 图像和坐标合同

实时采用 K1主表面/delta/guides → K2主要输运 → post 三段。RR的K1写入主表面guides及首纯delta透明面的独立反射运动；K2只可能完成一个已移交的实际反射次段距离。`realtime_rr.slang` 合成FP32 prefix+tail、执行原相机空气段aerial与清洗，写入noisy color，并补全其余像素的反射运动。原始实时不分配RR图像，Offline不进入这两个实时PT kernel。阶段布局见[PT设计](pt-state-design.md)。

RR主射线在像素中心加CPU Halton帧采样偏移；Streamline接收投影位移，XY均为采样偏移的相反数，单位是输入像素。相机矩阵不含抖动；coverage与照明的其余随机域继续由Z-Sobol产生。guide从同一主射线和coverage开始，之后按确定的几何事件选择独立终点，不随照明的首透明0.5分支或roulette改变。

| Binding | 图像/常量 | 格式与语义 |
| --- | --- | --- |
| 10 | noisy color | RGBA16F，场景线性BT.709，包含完整prefix+tail与aerial，无曝光和显示变换 |
| 11 | depth | R32F，选定主表面/PSR代理的正向view-Z，世界单位；真实escape为FLT_MAX |
| 12 | motion | RG16F，未抖动投影的previous pixel − current pixel，输入像素单位、左上原点；未知对应写有限零并标记失败 |
| 13 | normal/roughness | RGBA16F，选定表面的世界空间单位着色法线或PSR变换法线，以及有效粗糙度 |
| 14 / 15 | diffuse/specular albedo | RGBA16F，原BSDF方向能量转线性BT.709；首透明接口specular例外见下文 |
| 16 | 内部specular hit distance | R16F，实际反射次段物理距离，供post粗糙反射代理使用；环境miss为65504，无对应样本及K1显式R guide为0；不作为SDK tag |
| 17 | camera history | 144B uniform，每个完成槽独占；当前/前相机、采样抖动与历史有效性，前相机转换到当前scene anchor |
| 18 | RR output | 输出分辨率RGBA16F，线性BT.709 |
| 19 | 引擎内部guide状态 | R8_UNORM，整数flags除以255存储：值1为主guide失败/待完成，2为反射guide失败/待完成，4为K1显式反射运动；不作为SDK tag |
| 20 | specular motion | 全图RG16F，与主motion同为输入像素位移；K1稳定反射终点或post局部距离代理；未知对应写有限零并标记失败 |

桥接将binding20标为 `kBufferTypeSpecularMotionVectors`，由DLSS-D传入NGX的specular motion输入；不使用 `kBufferTypeReflectionMotionVectors`，也不再标记 `SpecularHitDistance`。因此不存在依赖SDK未声明优先级的双输入选择。两张motion图均使用输入像素位移，桥接的 `mvecScale=(1/inputWidth, 1/inputHeight)` 经Streamline乘输入尺寸后使NGX scale为1；抖动不混入motion。内部投影helper的无效哨兵不进入图像，不依赖 `motionVectorsInvalidValue` 被DLSS-D识别。历史重置时运动为零。

working Rec.2020只在RR边界转线性BT.709，结果转回working后执行primeDRT。半浮点颜色有限化并限于0–65504；这是重建边界的动态范围限制，prefix/tail与离线输运保留FP32合同。R8完成图需要设备同时支持storage与sampling；能力不足时RR不可用，不悄悄改变状态语义。

## 主表面提升与完成状态

K1穿过整闭包为纯delta的前缀，在第一个非delta表面发布主guide，真实escape单独发布方向guide。粗糙首面只需要一次主查询，不进入delta或0.5策略。规范主guide优先实际IOR可透射方向；真实TIR与conductor使用反射。首纯delta透明面可透射时同时建立独立R guide，用于可见接口的反射运动。两条guide的方向接口不读取Fresnel抽样概率、response/PDF、吸收或roulette。

照明选中的R或T事件共享对应guide生产者给出的方向、安全起点和后续公共查询；另一条guide保存为64B companion seed。后续分歧或照明提前终止时保存当前guide，照明结束后在同一K1中依次完成独立后缀。两条guide从同一主交点和材质开始，终点不随首面0.5照明分支或roulette改变；没有独立第三个光追dispatch。

guide使用独立深度及同值最大顶点预算N。照明零beta、强吸收或roulette终止不能结束guide；第N次查询得到非delta或escape仍算完成，仍需下一跳才标记预算耗尽。无效几何和退化代理也标invalid，不能用最后透明接口、旧帧值或照明终止点充当正常表面。失效像素给所有必需SDK输入写有限合法占位，并另写内部完成状态；占位本身不表示观察到了表面或天空。

PSR以32B quaternion/parity、仿射平移和flags表示 `F(x)=A*x+t`，按真实物理交点与光学法线合成反射平面，不把射线安全偏移计入代理几何。终点平面与法线经相同变换后，与实际带采样抖动的相机射线相交，得到一致的depth、normal与motion锚点；diffuse albedo取该终点的方向能量。原相机空气段长度另存给post，不能由promoted depth覆盖。可几何透射的首纯delta透明接口保留可见接口的specular albedo，终点guide不覆盖它，也不乘首面抽选权重或路径beta。独立R guide只写反射运动与完成状态，不重复写主guide或读取albedo能量LUT。

静态直视表面与静态反射链代理使用相机重投影。固定平面镜链可建立一致虚拟表面；厚折射使用终点切平面与相机射线相交的局部代理，静态时可提供近似motion，但不是逆Snell对应或精确成像Jacobian。K1主guide与独立R guide遇到任一动态接口、动态终点或缺失前帧几何对应时标记未知运动；非法投影、退化平面也失败。该检测不覆盖后述K2粗糙反射次段。不能把平面镜性质外推为任意曲面、移动镜面或一般折射链。

真实escape使用当前相机射线的方向代理（D0）：只产生相机旋转运动，不产生平移视差，也不伪造有限表面。经过反射/折射链的天空同样使用该近似，未声称恢复实际光学链的环境方向对应。静态相机的帧采样抖动不应产生运动。

普通不透明/粗糙像素不增加反射查询。K2仅在明确移交时记录实际所采首反射续接次段距离；post由主view depth恢复相机射线上的主点，再沿该射线增加此距离，构造局部虚拟点并重投影。它只估计相机运动，不检测或补偿次段目标的物体运动，也不是GGX反射终点的精确光流。roulette或事件选择导致缺样本时距离为0，退化为主点运动；粗糙反射次段miss写距离65504，仍按有限远点投影，不能视为严格无平移视差的天空。只有主depth为天空或K1显式guide真实escape时使用D0方向代理。K1显式R guide仅输出反射运动，其距离保持初始化的0；状态值4使post保留该运动，不再计算或存储无消费者的R距离。SDK始终消费完整的specular motion图，而非仅透明像素有效的稀疏输入。

`rr_display.slang` 在输出分辨率显示。只有SDK录制成功且当前双线性输入足迹的四个状态均满足 `(flags & 3)==0` 时才消费RR输出，正常状态0和显式反射状态4均可用；否则使用该足迹的本帧noisy color。一般evaluate失败时本帧恢复raw，下一帧在完成证明后切回原生管线；异常导致命令录制状态不安全时直接走帧失败/退休契约，不读取可能处于未知布局的输入。诊断色/深度/法线显示实际内部数据，期间不消费RR历史，回到最终输出时重置；仅写宿主目标时翻转Y。

内部完成图保证的是未解析像素的输出退路，不保证SDK内部历史隔离、邻域空间滤波隔离或恢复有效后的历史清除。当前不接入 `BiasCurrentColorHint`，不把通用tag注释外推为锁定RR preset F的逐像素历史拒绝保证。严格SDK reset仍是整个viewport/frame级别；不为按像素条件增加CPU读回或整帧等待。

## 历史、同步与成本

首次帧、尺寸/质量/模式/开关变化、源 owner 替换、采样序列中断、camera cut 和输运设置改变使历史失效。正常相机运动使用重投影；scene anchor 变化先对前相机精确重定位，再计算相对运动。曝光/primeDRT 改变不重置场景线性历史。

输入/输出图像跨帧复用，同一宿主队列的写后读、读后写依赖覆盖 K1 → K2 → compose → RR → 显示及下一帧；K1的prefix/guide写入、K2距离写入和post完成状态/反射运动写入都必须对后续消费可见。可变描述符和144B常量按宿主 timeline 完成槽复用；稳态没有像素读回、额外队列提交或逐帧 CPU 等待。重新配置/释放 SDK 私有资源前等待其最后真实使用 serial；SDK 调用失败也可能已经录制命令，不能立即释放。无法取得完成证明时保留 SDK owner、DLL 和 GPU 资源。

七个SDK输入共44 B/内部像素；内部距离2 B与状态1 B另计，合计47 B/内部像素，输出8 B/输出像素。新增specular motion图为4 B/内部像素，约2.07 MB/960×540或8.29 MB/1920×1080。默认Performance在1920×1080输出下，显式图像约40.95 MB；DLAA原生1080p约114.05 MB，均为十进制MB，不含240B/内部像素的RR scratch、分配对齐、SDK内部历史/暂存、原有场景与宿主目标。

全图写读、独立guide后缀、仿射投影、重建和显示都是真实成本；物理寄存器、保存流量与整帧收益仍须测量。降低分辨率会减少PT射线数量，不能把性能档帧率称为原生1080p性能。正式对比先用DLAA保持原生1920×1080、场景、种子和预算一致，再单独报告超分档位。

CPU/ABI/shader/桥接行为检查不证明实际模型质量、窗口呈现或整帧速度。两版实际游戏与驱动画面由用户按 [CONTRIBUTING](../CONTRIBUTING.md) 验收，重点检查运动、遮挡显露、细叶、多层透明/镜面、guide预算耗尽率、恢复有效时的历史行为、切换、窗口变化和退出重进。
