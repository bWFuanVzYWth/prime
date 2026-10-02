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

实时采用 K1主表面/delta/guides → K2主要输运 → post 三段。RR的K1写入独立主表面guides，K2只可能完成一个已移交的实际反射次段距离；`realtime_rr.slang` 合成FP32 prefix+tail、执行原相机空气段aerial与清洗，然后写入noisy color。原始实时不分配RR图像，Offline不进入这两个实时PT kernel。阶段布局见[PT设计](pt-state-design.md)。

RR主射线在像素中心加CPU Halton帧采样偏移；Streamline接收投影位移，XY均为采样偏移的相反数，单位是输入像素。相机矩阵不含抖动；coverage与照明的其余随机域继续由Z-Sobol产生。guide从同一主射线和coverage开始，之后按确定的几何事件选择独立终点，不随照明的首透明0.5分支或roulette改变。

| Binding | 图像/常量 | 格式与语义 |
| --- | --- | --- |
| 10 | noisy color | RGBA16F，场景线性BT.709，包含完整prefix+tail与aerial，无曝光和显示变换 |
| 11 | depth | R32F，选定主表面/PSR代理的正向view-Z，世界单位；真实escape使用无表面哨兵 |
| 12 | motion | RG16F，未抖动投影的previous UV − current UV，左上原点；未知对应为-65504 |
| 13 | normal/roughness | RGBA16F，选定表面的世界空间单位着色法线或PSR变换法线，以及有效粗糙度 |
| 14 / 15 | diffuse/specular albedo | RGBA16F，原BSDF方向能量转线性BT.709；首透明接口specular例外见下文 |
| 16 | specular hit distance | R16F，实际采中的反射续接次段物理距离；环境miss为65504，无对应样本为0 |
| 17 | camera history | 80B uniform，每个完成槽独占；前相机转换到当前scene anchor |
| 18 | RR output | 输出分辨率RGBA16F，线性BT.709 |
| 19 | 引擎内部guide完成状态 | R8_UNORM，正常解析0，unresolved/invalid为1；不作为SDK tag |

working Rec.2020只在RR边界转线性BT.709，结果转回working后执行primeDRT。半浮点颜色有限化并限于0–65504；这是重建边界的动态范围限制，prefix/tail与离线输运保留FP32合同。R8完成图需要设备同时支持storage与sampling；能力不足时RR不可用，不悄悄改变状态语义。

## 主表面提升与完成状态

K1穿过整闭包为纯delta的前缀，在第一个非delta表面发布稳定主guide，真实escape单独发布方向guide。粗糙首面只需要一次主查询，不进入delta或0.5策略。规范guide优先实际IOR可透射方向；真实TIR与conductor使用反射。方向接口不读取Fresnel抽样概率、response/PDF、吸收或roulette。与照明选择同一事件时共享查询；分歧或照明提前终止之后的guide后缀仍在同一K1中完成，没有独立第三个光追dispatch。

guide使用独立深度及同值最大顶点预算N。照明零beta、强吸收或roulette终止不能结束guide；第N次查询得到非delta或escape仍算完成，仍需下一跳才标记预算耗尽。无效几何和退化代理也标invalid，不能用最后透明接口、旧帧值或照明终止点充当正常表面。失效像素给所有必需SDK输入写有限合法占位，并另写内部完成状态；占位本身不表示观察到了表面或天空。

PSR累计真实物理交点间长度和有限反射变换，不将安全偏移长度用作代理几何。depth、normal/roughness、diffuse albedo与motion描述同一个选定终点/代理；原相机空气段长度另存给post，不能由promoted depth覆盖。可几何透射的首纯delta透明接口保留可见接口的specular albedo，终点guide不覆盖它，也不乘首面抽选权重或路径beta。

静态直视表面与静态反射链代理使用相机重投影；动态链、缺前姿态/顶点对应时写invalid motion。厚折射链目前采用路径长度代理，缺少准确前帧折射对应，即使几何静态也写invalid motion。平面镜链可以建立一致虚拟表面，不能把该性质外推为任意曲面/折射链的精确成像Jacobian。反射天空仍是方向终点，不伪造有限表面。

specular hit distance优先复用实际所采反射的下一次查询，可在K1或K2产生，不为未选反射另追一条射线。可见透明接口与提升后主表面的距离生产职责分开：K2仅在明确移交时写入，不能清掉K1已有的正确起点距离。这是有噪声、可能缺样本的可选guide，不是独立确定性反射距离。

`rr_display.slang` 在输出分辨率显示。只有SDK录制成功且当前双线性输入足迹的四个完成状态均为0时才消费RR输出；否则使用该足迹的本帧noisy color。一般evaluate失败时本帧恢复raw，下一帧在完成证明后切回原生管线；异常导致命令录制状态不安全时直接走帧失败/退休契约，不读取可能处于未知布局的输入。诊断色/深度/法线显示实际内部数据，期间不消费RR历史，回到最终输出时重置；仅写宿主目标时翻转Y。

内部完成图保证的是未解析像素的输出退路，不保证SDK内部历史隔离、邻域空间滤波隔离或恢复有效后的历史清除。当前不接入 `BiasCurrentColorHint`，不把通用tag注释外推为锁定RR preset F的逐像素历史拒绝保证。严格SDK reset仍是整个viewport/frame级别；不为按像素条件增加CPU读回或整帧等待。

## 历史、同步与成本

首次帧、尺寸/质量/模式/开关变化、源 owner 替换、采样序列中断、camera cut 和输运设置改变使历史失效。正常相机运动使用重投影；scene anchor 变化先对前相机精确重定位，再计算相对运动。曝光/primeDRT 改变不重置场景线性历史。

输入/输出图像跨帧复用，同一宿主队列的写后读、读后写依赖覆盖 K1 → K2 → compose → RR → 显示及下一帧；K1的prefix/guide写入也必须对后续消费可见。可变描述符和80B常量按宿主 timeline 完成槽复用；稳态没有像素读回、额外队列提交或逐帧 CPU 等待。重新配置/释放 SDK 私有资源前等待其最后真实使用 serial；SDK 调用失败也可能已经录制命令，不能立即释放。无法取得完成证明时保留 SDK owner、DLL 和 GPU 资源。

七个SDK输入共42 B/内部像素，内部完成图另为1 B/内部像素，输出8 B/输出像素。默认Performance在1920×1080输出下，显式图像约38.9 MB；DLAA原生1080p约105.8 MB，均不含176B/内部像素的实时scratch、分配对齐、SDK内部历史/暂存、原有场景与宿主目标。新增全图写读、重建和显示成本必须计入；降低分辨率会减少PT射线数量，不能把性能档帧率称为原生1080p性能。正式对比先用DLAA保持原生1920×1080、场景、种子和预算一致，再单独报告超分档位。

CPU/ABI/shader/桥接行为检查不证明实际模型质量、窗口呈现或整帧速度。两版实际游戏与驱动画面由用户按 [CONTRIBUTING](../CONTRIBUTING.md) 验收，重点检查运动、遮挡显露、细叶、多层透明/镜面、guide预算耗尽率、恢复有效时的历史行为、切换、窗口变化和退出重进。
