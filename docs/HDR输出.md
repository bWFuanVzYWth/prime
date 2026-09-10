# HDR 输出

Prime 的 HDR 选项只在当前窗口所在屏幕同时满足以下条件时可用：Windows 已为该屏幕启用
HDR，DXGI 能报告有效的峰值亮度与 SDR 白电平，并且 Vulkan surface 提供
`R16G16B16A16_SFLOAT + EXTENDED_SRGB_LINEAR_EXT`。窗口或交换链重建时会重新检测；任一
条件不满足都会保留 SDR 交换链并禁用选项。

## 显示变换契约

RGB Reinhard 来自 `C:\WorkSpace\drt` 的 `shaders/rgb_reinhard.wgsl` 与
`src/gpu.rs`，固定版本为 `a15e90947965f93c71e943fe27caf2ea56e08c10`。
Prime 保留自身的线性 Rec.2020 输入边界，转换为线性 Rec.709 后进入 DRT，因此不会移植
参考程序面向 ACES AP0 图像的输入矩阵。使用固定的 4% 虚拟色域压缩、`0.18` 线性段接点、
单位线性斜率和 50% HSV 色相保持；highlight reach 按用户选择设为 `8.0` EV，覆盖参考默认的
`10.0`。逐通道曲线及虚拟色域逆变换在线性域执行，然后进行扩展 sRGB 编码和 HSV 色相修复。
原始色相也取自 sRGB 编码后的 RGB，修复阶段保留参考的饱和度，最后才按输出峰值钳制。
中性输入到 `0.18` 为止保持线性，以上接入值和一阶导数连续的 Reinhard 有理式 shoulder。
零输入精确映射为零。
Windows 查询保留峰值亮度 `P` 与系统 SDR 白电平 `W_system` 的绝对 nit 值，不能只保留两者比值。

“HDR 参考白”以 nit 标定 HDR 模式下的 SDR 白：配置值 `0` 表示自动使用 `W_system`，手动
值可从 1 nit 调到当前屏幕报告的 HDR 峰值；跨屏幕后若旧配置高于新屏峰值，运行时只在该屏
钳制到峰值，不改写持久配置。显示变换使用 `P / W` 推导 HDR shoulder，场景线性 18% 灰仍
固定映射为参考白的 18%，因此该选项不移动 SDR 中灰或场景曝光。令 headroom `H = P / W`，
高光 reach 为 `8.0 + log2(H)` EV，对应输入 `R = 0.18 * 2^8 * H`。肩部余量
`A = (H - 0.18) * (R - 0.18) / (R - H)`，肩部为 `0.18 + d / (1 + d / A)`，
其中 `d = x - 0.18`。因此中性输入 `R` 映射到线性输出 `H`，渐近峰值 `0.18 + A` 略高于 `H`；
最终编码输出钳制到 `OETF(H)`。运行时渐近峰值使用 double 推导后再提交为 float。
GPU 使用等价的 `0.18 + A / (1 + A / d)`，避免极大输入下 `d / A` 溢出或最终除法的 GPU
倒数下溢，保持有限输入到渐近峰值的单调过渡。
Prime 延续自身 `1..10000` 的合法 headroom 范围，使用同一解析公式，不采用测试台的 `64` 上限。

显示回归包含原始 WGSL 在 reach `8.0`、`1/4/64` headroom 下生成的 348 个 RGB 样本，以及覆盖
`1..10000` headroom 的 Slang 暗部线性、接点连续性和峰值行为测试。参考样本位于
`src/test/resources/prime/rgb_reinhard_reference.txt`；安装 `numpy` 与 `wgpu` 后，可运行
`python tools/generate_rgb_reinhard_reference.py C:/WorkSpace/drt` 从固定版本重新生成。
常规构建直接使用已捕获样本，不依赖外部仓库或 Python GPU 工具。

显示变换同时生成两个结果：

- RGBA8 中保留 SDR RGB Reinhard 基线，供截图、原版 UI 和不支持 HDR 的交换链使用；
- RGBA16F 中保留 display-encoded extended-sRGB HDR 结果。

HDR 交换链最终接收线性 scRGB，scRGB 的 `1.0` 按 Windows 契约表示 80 nit，而不是当前
SDR 参考白。最终合成阶段先对 HDR 世界和原版 RGBA8 UI 分别执行扩展 sRGB EOTF，再在线性
域合成，并把完整结果乘以 `W / 80`。因此世界主体、原版 UI 和 SDR 白使用同一物理亮度标定，
而 HDR 峰值成为 `P / 80`。HDR 模式下世界基线的 alpha 固定为零，原版 alpha 混合由此累积
准确的 UI coverage；这避免把整条 Minecraft UI 管线和资源格式提升为浮点格式。全屏调试
视图写入 alpha=1，仍使用相同的参考白标定。该换算依据 Microsoft 的
[HDR 与 WCG DirectX 文档](https://learn.microsoft.com/windows/win32/direct3darticles/high-dynamic-range)
以及 [`DISPLAYCONFIG_SDR_WHITE_LEVEL`](https://learn.microsoft.com/windows/win32/api/wingdi/ns-wingdi-displayconfig_sdr_white_level)
契约。

原版 UI 先在 RGBA8 目标中完成后再按最终 alpha 与 HDR 世界合成。普通 source-over coverage
可由该结果正确恢复；原版管线中的非 source-over 混合模式已经丢失中间操作，不能由最终颜色
与 alpha 精确重建。Prime 明确接受这项 HDR UI 近似，不修改 UI shader 或提升整条 UI 管线。

## 不变量

- 不得让 RGBA8 中间结果成为 HDR 世界颜色的来源，高于 1.0 的信息会在此处不可逆丢失。
- 不得把 display-encoded RGB Reinhard 输出直接提交给线性 scRGB 交换链。
- 不得在编码域把 HDR 世界与 UI 直接相加或插值。
- 不得把 scRGB `1.0` 当成可变的 SDR 参考白；绝对输出必须统一乘以 `W / 80`。
- 参考白只改变物理亮度标定和 `P / W` 高光余量，不得移动 SDR 中灰或场景曝光。
- 描述符所引用的同尺寸图像发生重建时，必须先结束旧的 GPU 使用；正常稳定帧不得为此等待。
- 屏幕能力或 Windows HDR 状态未知时必须回退 SDR，不能猜测峰值亮度。
