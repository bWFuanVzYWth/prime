# Slang 数学基础、求交与显示策略

## 模块边界

| 文件 | 职责与依赖 |
| --- | --- |
| `math/color.slang` | sRGB 传递函数、线性 BT.709 ↔ Rec.2020（D65）、工作空间亮度；无资源依赖 |
| `math/z_sobol.slang` | Z-order + 二维 Sobol + FastOwen；无纹理、表、64 位整数算术或隐式随机状态 |
| `math/ray_offset.slang` | 三角形交点重建、误差界与双侧安全起点；无资源依赖 |
| `display/prime_drt.slang` | 当前可替换的显示策略；显式显示变换与艺术调整，只依赖颜色数学库 |
| `ray_query.slang` | 硬件 Ray Query、实际材质/覆盖率、交点；只依赖起点数学库 |
| `frame.slang` | 入口共享的显式帧参数类型，无全局绑定 |
| `transport.slang` | 共同的漫反射积分、采样域与首次命中 guides，显式接收资源与帧 |
| `path_trace.slang` | 离线累积及显示入口 |
| `realtime.slang` / `realtime_display.slang` | 实时噪声/深度/法线写入与 GPU 显示/诊断入口 |

库不声明描述符、push constant 或全局可变状态，不通过 DCE 消除不需要的资源。入口显式传入 `TraceScene` 与显示参数；私有辅助函数保持模块可见，只公开跨模块所需类型、字段和函数。最长生产依赖链为入口 → transport → ray_query → ray_offset，不按单函数拆文件。构建跟踪整个 shader 目录，修改被导入模块也必须重新编译。

`primeDRT` 不属于基础库，也不承诺稳定算法或参数接口。它同时负责显式显示变换与艺术调整，之后可以修改或被其他显示策略替代；`math/` 不依赖它。当前入口直接选择 primeDRT，不为尚不存在的替代方案引入注册表或额外抽象。策略专用 Rust 参数命名为 `PrimeDrtSettings`，与通用颜色变换分开；测试入口也与数学基础测试分离。

## 颜色与当前显示策略

源纹理和 tint 仍先按 Minecraft 编码域语义组合；随后执行 sRGB EOTF，转到线性 Rec.2020（D65）。路径 throughput、天空/太阳与累积历史都使用这个工作空间。RGB 乘法不是光谱渲染。显示变换只作用于实时辐射亮度或离线累积结果，不改变物理输运或源材质。

`primeDRT` 沿用旧 Prime 的 RGB Reinhard DRT：曝光、Rec.2020 → BT.709、虚拟 RGB 色域压缩、分段有理曲线、逆压缩、保留负 outset 的 sRGB 编码、HSV 色相与饱和补偿、输出峰值裁剪。不是逐通道 `x/(1+x)`。

保留旧代码中实际变化的输入：曝光乘数（可含未来自动曝光结果）、输出 headroom、色相补偿 `[0,1]`、饱和度补偿 `[0,0.5]`。默认分别为 `1 / 1 / 0.75 / 0.08`。Rust `PrimeDrtSettings::prepare` 根据 headroom 推导曲线渐近峰与编码输出峰；这些派生值不作为独立美术旋钮，在配置变化时计算。`Renderer::set_prime_drt` 调整当前 SDR 显示参数而不清空线性历史。

固定常量保留旧值：neutral 权重 `(0.2120053547549465, 0.3921825078090138, 0.3958121374360396)`、虚拟色域压缩 `0.04`、曲线起点 `0.18`、单位起始斜率和 `+8 EV` 高光范围。旧固定曝光系数是 `1`，直接消去。倒数形式保留，避免极亮输入产生大的最终除数。

当前宿主目标仍为 `RGBA8_UNORM`，输出已编码 sRGB、alpha=1；后续宿主手部/HUD 合成沿用现有契约，不执行第二次 sRGB 编码。生产固定 SDR headroom=1；保留并测试 HDR 数学参数不表示已实现 HDR surface、校准或呈现。游戏内设置与 FFM 控制见 [渲染模式](renderers.md)；自动曝光尚未接入。NaN/Inf 输入按旧入口置黑；负颜色在旧算法规定的位置处理。

## 采样域

Z-Sobol 配置为 `R≤16`、`S≤20`、`2R+S≤52`，像素坐标 `<2^R`、样本索引 `<2^S`。一个完整样本集固定 R、S、全局 seed 和 domain；不能每帧把累计样本数重新当作 S。整数结果与用户 `z_sobol` 的标量参考一致，float 输出由高 24 位映射到 `[0,1)`。

生产 `R=ceil(log2(max(width,height)))`，`S=8`（原生 1080p 的 Morton 索引共 30 位，使用单字快路径）。相机 jitter 使用 domain 0；每个反弹从 `1+4*bounce` 起依次分配表面 coverage、阴影 coverage、BSDF 二维样本、roulette。分支不会推进共享 RNG。超过 256 个样本时切换全局 scramble，开始新的完整样本集；不声称它是无限延长的同一个 Sobol net。完整样本集/对齐像素邻域的均匀性不等于早期任意前缀的质量保证。

[表面编译原型](surface-compiler.md)的显式灯使用独立的 `512+4*bounce` domain，依次用于世界/局部树选择、三角形二维采样、采样点 coverage 和有限阴影 coverage。静态灯 NEE 与发光命中使用同版本 proposal 的 MIS；既有太阳/天空路径保持原 domain。

实现复用 domain hash，以固定双字移位代替通用 64 位移位分支，将 Sobol Y 变换移到反向位序，抵消紧邻的 Sobol/Owen bit reverse。没有额外采样纹理或跨帧随机状态。上述是运算路径变化，不代表已经测得整帧提速。

## 求交与安全起点

使用 Vulkan 硬件 Ray Query，最近交点与阴影查询共享实际材质和随机 coverage 语义。透明 coverage 仍不是折射/介质；不增加全场景软件三角形求交器。

安全起点移植 NVIDIA `SelfIntersectionAvoidance.hlsl`：局部边与 barycentric 重建将基顶点最后相加，显式仿射变换将平移最后相加，逆转置法线及 object/world 两侧误差投影得到偏移。由出射方向选择表面正/反侧，`TMin=0`，取消固定世界单位 epsilon，减少小间隙漏遮挡。输入必须为有限、非退化三角形及互逆非奇异仿射变换。

实验表面路径的静态单元由生产者证明只有平移，`reconstructStaticSurface` 特化对应的单位矩阵运算，保留相同误差常量和两侧投影；通用动态实例仍使用实际仿射矩阵。有限灯阴影从接收面安全起点到灯面朝向接收方的安全起点，不引入固定距离 epsilon。

交点硬件误差常量采用参考中的 NVIDIA RTX 上界；其他厂商必须另外验证，不能由移植直接保证。算法来源与适用条件见 [NVIDIA 说明](https://developer.nvidia.com/blog/solving-self-intersection-artifacts-in-directx-raytracing/)，许可与修改范围见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 尺寸与历史

Java 使用实际 render target 尺寸，零尺寸/未初始化相机暂停发布并重置样本序列；恢复及尺寸变化从新样本集开始。Rust 同步更新所选模式的 guides 或累积存储、相机宽高比、输出绑定与 Z-Sobol R。离线冻结姿态保持不变。旧输出按最后使用的 timeline 完成值回收，不等待当前帧、不回读像素。相同尺寸重建 image view 仍刷新宿主描述符。

协议尺寸边界为每轴 `1..65536`，像素总数与字节计算为宽整数；实际还须满足设备 image dimension、compute dispatch 和累积 storage-buffer range。协议接受不代表设备可分配。原固定 4096 边长限制不再使用。历史到 `2^24` 样本前重启，避免 f32 样本权重失去单位精度及整数加一溢出。

## 验证范围

`shader-tests` feature 构建专用入口，GPU 直接执行生产数学模块。独立 u64/top-down/标量 Sobol oracle、f64 颜色与几何参考、冻结的旧 DRT shader 分别检查整数一致性、数值误差与移植等价。实际 AS 查询覆盖仿射、镜像、非均匀缩放、平移、掠射及邻近遮挡；图像测试检查 resize、历史、显示参数与宿主在途资源。

测试入口的读回只用于无窗口行为验证，不进入游戏流水线。它们不替代两版 Minecraft 的窗口/全屏/HUD 验收。操作入口见 [CONTRIBUTING](../CONTRIBUTING.md)。
