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

RR 专用 `realtime_rr.slang` 调用共同输运核。原始实时和离线入口使用空 guide sink，不分配这些图像。RR 的主射线在像素中心加上 CPU Halton 帧采样偏移；Streamline 接收的是投影位移，XY 分别为该采样偏移的相反数，单位均为输入像素。相机矩阵不含抖动；其余路径随机域继续由 Z-Sobol 产生。

| Binding | 图像/常量 | 格式与语义 |
| --- | --- | --- |
| 10 | noisy color | RGBA16F，场景线性 BT.709，无曝光与显示变换 |
| 11 | depth | R32F，主命中正向 view-Z，世界单位；天空为无表面哨兵 |
| 12 | motion | RG16F，未抖动投影的 previous UV − current UV，左上原点；未知前态为 -65504 |
| 13 | normal/roughness | RGBA16F，世界空间单位着色法线和有效粗糙度 |
| 14 / 15 | diffuse/specular albedo | RGBA16F，同一个实际 BSDF 的方向反照率，转线性 BT.709 |
| 16 | specular hit distance | R16F，实际首次 specular reflection 续接后的次命中距离；环境 miss 为 65504；未采到该续接为 0 |
| 17 | camera history | 80B uniform，每个已完成槽独占；前相机已转换到当前 scene anchor |
| 18 | RR output | 输出分辨率 RGBA16F，线性 BT.709 |

同一主射线和 alpha coverage 决定 radiance 与首表面 guides。导引在首次命中的材质作用域写回；只增加一个 specular 路径分类状态到下一次最近交点，不额外追踪一条反射射线。working Rec.2020 在 RR 边界转换为 BT.709，结果转回 working 后执行 primeDRT；半浮点颜色有限化并限于 0–65504。这是重建边界的动态范围限制，原始/离线输运精度保持原合同。

运动向量目前可靠覆盖静态几何的相机运动，以及天空方向运动。动态实例、形变和原始动态几何没有完整前姿态/顶点对应，明确写无效 motion，不伪装成静止或相机运动；这会限制移动物体的重建质量。透明以首个实际可见边界为 guide，尚未接入替代表面、PSR 或多层透射历史。specular hit distance 来自被采中的真实反射路径，是有噪声、可能缺样本的引导，不是独立确定性反射距离。

`rr_display.slang` 在输出分辨率执行：成功时消费 RR 图像；evaluate 失败的当帧双线性放大 noisy color，下一帧在完成证明后切回原生 raw 管线。诊断色/深度/法线显示实际内部采样尺寸的数据并覆盖完整输出；诊断期间不消费 RR 历史，回到最终输出时重置。只有写宿主目标时翻转 Y。

## 历史、同步与成本

首次帧、尺寸/质量/模式/开关变化、源 owner 替换、采样序列中断、camera cut 和输运设置改变使历史失效。正常相机运动使用重投影；scene anchor 变化先对前相机精确重定位，再计算相对运动。曝光/primeDRT 改变不重置场景线性历史。

输入/输出图像跨帧复用，同一宿主队列的写后读、读后写依赖覆盖 PT → RR → 显示及下一帧。可变描述符和80B常量按宿主 timeline 完成槽复用；稳态没有像素读回、额外队列提交或逐帧 CPU 等待。重新配置/释放 SDK 私有资源前等待其最后真实使用 serial；SDK 调用失败也可能已经录制命令，不能立即释放。无法取得完成证明时保留 SDK owner、DLL 和 GPU 资源。

七个输入共42 B/内部像素，输出8 B/输出像素。默认性能档在1920×1080输出下，显式图像约38.4 MB；DLAA原生1080p约103.7 MB，均不含分配对齐、SDK内部历史/暂存、原有场景与宿主目标。新增全图写读、重建和显示成本必须计入；降低分辨率会减少PT射线数量，不能把性能档帧率称为原生1080p性能。正式对比先用DLAA保持原生1920×1080、场景、种子和预算一致，再单独报告超分档位。

CPU/ABI/shader/桥接行为检查不证明实际模型质量、窗口呈现或整帧速度。两版实际游戏与驱动画面由用户按 [CONTRIBUTING](../CONTRIBUTING.md) 验收，重点检查运动、遮挡显露、细叶、透明、切换、窗口变化和退出重进。
