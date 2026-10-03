# 线性显示、曝光与呈现契约

显示消费场景线性数据，不改变几何、材质、采样域或 Offline 累积。工作空间为 D65 线性 Rec.2020；DLSS RR 的输入和重建输出为线性 BT.709。`primeDRT` 是当前显示策略，数学基础和颜色转换见 [shader 模块](shaders.md)，星图的环境消费见 [大气](atmosphere.md)。宿主、SDK 和完成协议见 [渲染模式](renderers.md)与[重建契约](reconstruction.md)。

## 顺序与数据身份

实时 raw post 生成线性 working 辐射亮度；RR selector 在输出分辨率选择本帧有效重建或 noisy fallback，保留线性 BT.709 和前景 coverage。需要自动曝光、HDR、有效帧生成或 RR 星图后合成时，结果进入 FP32 中间图，然后依次执行星图、曝光统计和最终显示。这些功能都不需要时保留原直接 SDR 显示入口。诊断视图使用原有直接预览，不把深度或法线当作辐射亮度。

Offline 的 FP32 在线均值留在原累积 buffer；曝光和显示直接借用其 BDA，不复制成全帧图像。显示读取本帧更新后的均值，手动曝光、色相、饱和度和 HDR 标定变化不清空累积。BDA 的有效性由 Rust 资源所有者证明，shader 用显式 u32 控制位选择 image/buffer 和曝光状态，不重新比较空指针，也不增加 `shaderInt64` 能力要求。

最终显示先将线性 BT.709 转 working（仅 RR），然后把手动曝光和设备端自动曝光各乘一次，再执行 primeDRT。SDR 与 HDR 分别使用自己的 headroom 参数。SDR 输出为编码 sRGB 的 RGBA8；HDR world 是 primeDRT 输出的 extended-sRGB 编码 FP16，后续呈现 pass 才做 EOTF 和 scRGB 单位换算。不能把已经编码、剪裁的 RGBA8 当作 HDR 或曝光输入。

primeDRT 色相补偿默认75%，饱和度补偿默认20%，后者仍允许0–50%。默认值只用于缺少配置、整份配置回退或显式恢复默认；当前schema中的合法保存值（包括历史8%）继续按原值显示。显示控件位于视频设置顶部的 Prime PT 显示组，RR与OMM开关位于诊断组；位置变化不改变线性输入、测光或累积合同。

## 星图

固定资产为 NASA SVS Deep Star Maps 2020 的 16384×8192 ICRF/J2000 星图，赤经 0h 居中且向左递增。源 EXR 没有声明 primaries/white point；沿旧 Prime 的线性 sRGB 解释，离线转换一次到 D65 线性 Rec.2020，再编码为 BC6H_UFLOAT。当前压缩资产已经是 working 辐射亮度，shader 不再转换。15 个 mip 层按球面 texel 立体角在线性域平均，没有预乘曝光或 tone map。来源、哈希、编码器及独立许可/credit 见[资产 manifest](../crates/prime-vulkan/assets/starmap/starmap_2020_16k.json)和[NASA 声明](../licenses/NASA-DEEP-STAR-MAPS-2020-NOTICE.md)。

天文帧沿用旧算法：世界东轴、由观测纬度得到的天极/子午基、当前太阳时角与太阳赤经组成恒星时相位；太阳赤经采用轴倾角 23.43928°和配置的太阳黄经。星光默认 scale 为 `0.025 × stars`，乘方向大气透射并受地球遮挡。天空强度只缩放大气天空，星光强度独立。

raw、Offline 和后续反射/折射的环境终点都采样星图 LOD0。RR 只省略真正第一条相机射线直接 miss 的星图；其余路径仍保留星光。RR 后合成在 native 输出分辨率使用未 jitter 的相机方向，以赤经缝 wrap 后的 Jacobian 求椭圆 footprint：短轴至少 1 texel，长短轴比上限 8，按短轴选择 mip，沿长轴至多 8 taps。这是旧 Prime 已有的滤波近似，不改变路径的方向采样或 PDF。

合成使用重建/noisy 的前景 coverage：`scene + stars × (1 − saturate(coverage))`。SDK 的非有限 coverage 在 selector 中保守变为 1。当前 3×3 真实首命中前景位全为 foreground 时，输出像素禁止星光；该位与未解析 guide、PSR landing 和 SDK 私有历史各自独立。天空后合成只处理直接相机背景，不能替代材质路径内的星光。

星图只在首次需要时解压并上传。gzip 总计 166,381,090 B（约 158.67 MiB），完整 GPU mip 链为 178,957,008 B（约 170.67 MiB，base 128 MiB）。每次只保留一个最大 32 MiB 的 decoded CPU stripe，但全部上传 staging 可共存至提交完成。返回的上传 owner 保持到 `submit_named` 返回：独立设备此时已完成 fence；借用宿主此时按未来 serial 进入退休队列。资产编入 native 库，增加分发体积，稳态不再解压或转码。已上传资产保留至 Renderer 销毁；把强度改为0停止采样，不反复卸载/上传。

## 自动曝光

算法沿用旧 Prime 的 256-bin histogram：亮度使用 working 系数 `(0.2627, 0.6780, 0.0593)`；log2 范围为 `[-16,20]`，有限零/负亮度按下限计入，非有限 RGB 不计入。每个 16×16 workgroup 分担 64×64 图像 tile，在共享内存计数后只把非空 bin 合并到全局 histogram。单线程更新按 `count/200` 从两尾各剔除 0.5%，使用剩余 bin 中心的平均 log 亮度及最小/最大值。

完整目标 EV 为 `clamp(log2(0.16) − meanLog + sceneKeyBias, −16,16)`，其中 bias 保留旧场景亮度分布规则。`auto_exposure_compensation` 是从 EV 0 到完整目标的强度，范围 `[0,1]`、默认 0.6；0 关闭统计并让显示使用单位自动曝光。它不是额外 EV 偏置或开启开关。手动曝光仍是独立调整。

当前 raw、RR 与 Offline 的生产 meter 都使用旧 `confidence=0` 分支，仅统计输出辐射亮度。未读取材质分类或 albedo，也不声称已经移植旧 diffuse/foliage 的反照率校正。对应数学 helper 保留用于独立验证和以后显式接入真实数据，不增加 guide LUT 或逐像素宿主求值。

曝光适应使用指数 lerp：目标更暗时 t90=0.5 s，目标更亮时 t90=2 s，reset/instant 首次直接落到目标。16 B 状态由 Renderer 持有，同一宿主队列在 histogram→update→display 之间建立显式依赖，没有 CPU 读回或新增提交。进入 Offline 时保留已有曝光；没有既有曝光的 Offline 首帧瞬时 meter 后冻结。返回实时或显式状态重置后重新适应。冻结期间不把墙钟经过时间积成一次曝光跳变；改变自动补偿强度时可对当前均值瞬时重算，再次冻结，不清路径累积。

## HDR 与界面

实际 HDR 需要当前 Windows 显示器开启 HDR、有效 DXGI 峰值和系统 SDR 白亮度，以及宿主 Vulkan 表面选择 FP16 extended-linear-sRGB。请求设置与实际 surface 配置分别记录；能力未知或选择失败使用 SDR。26.2 通过 GLFW 的 HWND→HMONITOR，26.3 通过 SDL display 属性取得 HMONITOR；普通帧只检查轻量显示身份，昂贵 DXGI/DisplayConfig probe 在配置刷新时执行。

参考白 `W=0` 表示自动使用 Windows SDR 白亮度，手动范围为 1–10000 nit；实际 W 不超过当前显示器峰值 P，配置值跨显示器保留。DRT headroom 为 `clamp(P/W,1,10000)`。scRGB 线性 1.0 对应 80 nit，因此整个世界与界面使用同一个 `W/80` 比例。P/W、手动曝光和自动曝光是不同作用，不重复乘曝光或丢掉绝对 nit 标定。

HDR world 与 SDR baseline 都保留为 canonical top-left snapshot。世界写宿主 RGBA8 目标时 alpha=0，后续手部/HUD 的 source-over alpha 形成 coverage `a`。设编码 HDR world 为 H、编码 SDR baseline 为 B、最终宿主界面图为 `(C,a)`，呈现为：

`[EOTF(max(C−B×(1−a),0)/a)×a + EOTF(H)×(1−a)] × W/80`

`a=0` 时界面项为 0。EOTF 保留 extended-sRGB 的正负与高于 1 的值；HDR 世界不从 RGBA8 恢复。world snapshot 到 UI 呈现方向的翻转显式处理。该界面恢复沿用旧 Prime 的 source-over 近似：RGBA8 量化、非 source-over blend 和主动改写 alpha 的渲染不能从最终图精确逆推。原版/菜单的 HDR fallback 只把现有编码 UI 经过 EOTF 和同一 nit 比例输出，不加载 PT 世界资产。

## 帧生成的显示输入

有效 FG 的 SDR 世界也写 alpha=0并保存 primeDRT 编码 baseline。窄 SDR pass 把 baseline 旋转到最终 UI 方向，输出 RGBA8 HUDless（alpha=1）和 R8 UI coverage；不从叠有界面的最终颜色恢复世界。HDR present 在同一 pass 追加 FP16 HUDless `EOTF(H)×W/80` 和同一 R8 coverage，保持与最终 scRGB 图相同方向。两者只消费真实 UI alpha；SDK 调用、能力/模式 gate 和最后消费者证明由宿主与重建层负责。

FG 关闭时不写 HUDless/mask，可绑定对应格式的有效 1×1 dummy。HDR 关闭时不创建全帧 HDR world；需要 SDR FG baseline 时仍保存 RGBA8。显示模块不自行 Present，不通过帧数猜资源寿命。world、HDR 和 FG 各自的 descriptor 槽依据实际完成值复用；SDK 借用输入还必须满足其最后消费者协议。

## 可核算成本与验证边界

下表是原生 1920×1080 的格式/访问账，不是 GPU 耗时或完整帧率测量；忽略 padding、缓存、压缩和隐式驱动开销。分配按实际启用路径发生。

| 项目 | 资源或逻辑流量 |
| --- | --- |
| realtime 线性中间图 | RGBA32F 33.18 MB；新增线性写入与最终显示 pass |
| AE | 1028 B histogram + 16 B state；FP32 输入读约 33.18 MB/帧，另有共享/全局计数与 256-bin 更新 |
| HDR world + baseline | FP16 16.59 MB + RGBA8 8.29 MB；宿主最终 FP16 目标另计 |
| HDR 最终合成 | 读 8+4+4、写 8 B/pixel，最低约 49.77 MB/帧 |
| FG HDR 输出 | HUDless FP16 16.59 MB + mask 2.07 MB；可与 HDR present 同 dispatch 追加写 9 B/pixel |
| FG SDR 输出 | HUDless RGBA8 8.29 MB + mask 2.07 MB；读 baseline/UI、写 HUDless/mask，13 B/pixel，约 26.96 MB/帧 |
| 星图 | 170.67 MiB 固定 GPU mip 链；环境 miss 一次 LOD0 采样，RR 后合成最多 8 taps，另有 coverage/透射读取 |

无窗口验证直接执行生产 GPU passes，检查 image/BDA meter 一致、独立 double histogram 目标、曝光仅乘一次、FP32 RR selector→真实 BC6H 星图、coverage/foreground/ground/fallback、HDR 正负及高于 1 的 EOTF、W/80、UI 合成和两种 FG 输出方向/像素。CPU oracle 覆盖天文投影、赤经缝、footprint、曝光适应和 HDR 数学；资产检查完整 gzip CRC、长度和压缩/decoded SHA。测试读回只存在于验证入口。

这些验证不代表 Windows HDR 输出链、实际亮度、连续 RR 重建、真实帧生成 Present、完整资源包或整帧性能已经完成游戏验收。游戏仍由用户手动检查双版本的窗口/全屏、跨显示器 HDR 切换、参考白、手部/HUD、昼夜星图和冻结/恢复；正式性能比较使用固定场景、种子和射线预算的原生 1920×1080，并分别记录稳态与初始化/切换成本。
