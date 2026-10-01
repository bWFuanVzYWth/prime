# Slang 数学基础、求交与显示策略

## 模块边界

| 文件 | 职责与依赖 |
| --- | --- |
| `math/color.slang` | sRGB 传递函数、线性 BT.709 ↔ Rec.2020（D65）、工作空间亮度；无资源依赖 |
| `math/z_sobol.slang` | Z-order + 二维 Sobol + FastOwen；无纹理、表、64 位整数算术或隐式随机状态 |
| `math/ray_offset.slang` | 三角形交点重建、误差界与双侧安全起点；无资源依赖 |
| `math/transport.slang` | 面积 PDF、MIS、稳定乘除、Beer、eta 补偿及 roulette；无资源依赖 |
| `bsdf/common/` | 材质值类型、事件 flags、默认初始化、坐标框架、标量数学与内部介质栈；无闭包或纹理资源 |
| `bsdf/lite/` | LitePBR opaque、solid/thin dielectric、foliage 的状态、支持域、evaluate/sample/PDF 与事件数学 |
| `model/material/` | 已规范化 LabPBR 通道、默认值、Fresnel 身份和颜色/IOR 转换 |
| `service/bsdf/`、`service/material/normal_mapping.slang` | BSDF 消费契约、数值清洗和法线有效反射修正 |
| `pbr.slang` | LitePBR 材质 API 与生产 opaque/dielectric 源适配；不含 Minecraft 名称预设 |
| `grid_sampling.slang` | 生产固定单级局部 alias、全局退路及正反向混合 PDF，无资源绑定 |
| `light_sampling.slang` | 显式采样实验保留的 32B 功率树节点与选择，无资源绑定 |
| `display/prime_drt.slang` | 当前可替换的显示策略；显式显示变换与艺术调整，只依赖颜色数学库 |
| `ray_query.slang` | 硬件 Ray Query、直接表面/纹理/光学端点、覆盖与灯采样；依赖起点和颜色数学库 |
| `frame.slang` | 入口共享的显式帧参数类型，无全局绑定 |
| `atmosphere/` | 四波长物理场、预计算 solver、天空/太阳/空气透视生产和消费；见[大气契约](atmosphere.md) |
| `transport.slang` | 共同的 LitePBR 输运、采样域与首次命中 guides，显式接收资源与帧 |
| `path_trace.slang` | 离线累积及显示入口 |
| `realtime.slang` | 单次实时积分及所选视图显示，直接写输出图像 |

库不声明描述符、push constant 或全局可变状态，不通过 DCE 消除不需要的资源。入口显式传入 `TraceScene` 与显示参数；私有辅助函数保持模块可见，只公开跨模块所需类型、字段和函数。大气物理库显式接收 `AtmModel`，消费接收 `AtmEnvironment`；绑定与极线 groupshared 工作区只存在于入口或入口专用 include。构建跟踪整个 shader 目录，修改被导入模块也必须重新编译。

实时与离线入口共同使用 LitePBR。方向能量使用无纹理的 GGX/Schlick 解析拟合，scalar closure 调整已有反射瓣；不声明或采样 transmission-GGX 能量表，也不占用其 GPU binding。生产按现有表面分类选择 opaque 和已证明水/玻璃的 dielectric；foliage API 不代表已按 MC 类型或旧 preset 自动分类。高粗糙度角分布、opaque dielectric 非互易层叠、通道、数值与过滤边界见[材质契约](materials.md)。

生产 PT 当前采用 canonical vertex 与短时 BSDF 状态，显式结束原始 hit 的消费并消除未消费的构造与假依赖。查询切面、数据依赖、CPU/GPU 边界和缓存/重算取舍统一维护在 [PT 依赖与性能设计](pt-state-design.md)；这些安排不声明最优，不锁定后续设计。

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

[表面编译](surface-compiler.md)的显式灯使用 `sample2D(512+4*bounce)` 分配局部/全局路由及局部 alias 样本，`sample2D(768+bounce)` 分配全局页及页内灯样本。两个二维域各复用一次 index permutation，不串接同一 24-bit 标量的条件残差。前一域加 1 仍用于 quad 面积加权半面及三角形二维采样，加 2/3 分别用于采样点 coverage 和有限阴影 coverage。64 次反弹内这些域互不重叠；`sample1D(d)` 与 `sample2D(d).x` 是同一值，不能视为额外随机维度。静态灯 NEE 与发光命中使用同版本、同一前一着色点的完整混合 PDF 做 MIS；太阳圆盘方向使用独立的 `1024+bounce` domain。

alias 的 PDF 对应单表实际 f32 运算及 24-bit 输入格点，上传后仍有 f32 存储舍入。CPU 将每列阈值下限设为该列首个实际残差的下一个 f32 值，并至少为最小正规数，防止极小功率区间不可达或 GPU 将次正规阈值冲零；之后按修正后的表计算 PDF。修正只改变提议概率，不改发光，生产继续渲染并按累计修正表数的倍增输出 `Warning PT-010`，包含表项数及最大单表概率转移量。不同采样域避免已知的单标量残差支持损失，但单表边际校验不证明有限多维序列的完全独立或任意有限前缀已收敛。实际支持、PDF 接线、正常 Z-Sobol 的逐灯统计和完整图像分别验证。

实现复用 domain hash，以固定双字移位代替通用 64 位移位分支，将 Sobol Y 变换移到反向位序，抵消紧邻的 Sobol/Owen bit reverse。没有额外采样纹理或跨帧随机状态。上述是运算路径变化，不代表已经测得整帧提速。

## 求交与安全起点

使用 Vulkan 硬件 Ray Query，最近交点与阴影查询共享实际材质和随机 coverage 语义。未知透明源的 coverage 与已证明的光学边界分别表示；后者使用 Fresnel、折射和 Beer 吸收，首命中确定已知初始介质，直线 NEE 使用端点透射。具体支持范围和近似见[表面编译](surface-compiler.md)，不增加软件三角形求交器。

阴影查询在接受首个实心 coverage 遮挡后终止：此时透射严格为零，不需要最近距离或剩余介质 moments。光学边界本身不提交实心命中；无遮挡时仍完整累积所有边界，保持遍历顺序独立的吸收。太阳 NEE 先按本次圆盘采样方向求 radiance，地球遮挡使它为零时省去阴影查询；随机域与 MIS 不变，不能按圆盘中心统一裁掉地平线附近的可见部分。

太阳 radiance 前置也使白天被几何遮挡的样本先读取大气透射；夜间省去查询的成本分析不能外推所有场景。固定昼夜、洞穴和地平线输入比较完整 GPU 帧时，再决定后续调整，不为局部查询数量引入额外缓存或状态。

安全起点移植 NVIDIA `SelfIntersectionAvoidance.hlsl`：局部边与 barycentric 重建将基顶点最后相加，显式仿射变换将平移最后相加，逆转置法线及 object/world 两侧误差投影得到偏移。由出射方向选择表面正/反侧，`TMin=0`，取消固定世界单位 epsilon，减少小间隙漏遮挡。输入必须为有限、非退化三角形及互逆非奇异仿射变换。

所有静态单元由生产者证明只有平移，普通记录与扩展表面记录均使用 `reconstructStaticSurface` 特化对应的单位矩阵运算，保留相同误差常量和两侧投影；通用动态实例仍使用实际仿射矩阵。有限灯阴影从接收面安全起点到灯面朝向接收方的安全起点，不引入固定距离 epsilon。

交点硬件误差常量采用参考中的 NVIDIA RTX 上界；其他厂商必须另外验证，不能由移植直接保证。算法来源与适用条件见 [NVIDIA 说明](https://developer.nvidia.com/blog/solving-self-intersection-artifacts-in-directx-raytracing/)，许可与修改范围见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 尺寸与历史

Java 使用实际 render target 尺寸，零尺寸/未初始化相机暂停发布并重置样本序列；恢复及尺寸变化从新样本集开始。Rust 同步更新离线累积存储（实时无需中间图）、相机宽高比、输出绑定与 Z-Sobol R。离线冻结姿态保持不变。旧输出按最后使用的 timeline 完成值回收，不等待当前帧、不回读像素。相同尺寸重建 image view 仍刷新宿主描述符。

协议尺寸边界为每轴 `1..65536`，像素总数与字节计算为宽整数；实际还须满足设备 image dimension、compute dispatch 和累积 storage-buffer range。协议接受不代表设备可分配。原固定 4096 边长限制不再使用。历史到 `2^24` 样本前重启，避免 f32 样本权重失去单位精度及整数加一溢出。

Offline 保持逐样本在线均值、原 sequence 与随机域。当前单样本 profile 延后读取历史，host 的采样数同时决定 profile 与 push 参数；状态生命周期及有限管线变体的成本见 [PT 设计](pt-state-design.md#pt-之外的状态与当前特化)。

## 验证范围

`shader-tests` feature 构建专用入口，GPU 直接执行生产数学模块。独立 u64/top-down/标量 Sobol oracle、f64 颜色与几何参考、冻结的旧 DRT shader 分别检查整数一致性、数值误差与移植等价。实际 AS 查询覆盖仿射、镜像、非均匀缩放、平移、掠射及邻近遮挡；图像测试检查 resize、历史、显示参数与宿主在途资源。

PBR 检查分别覆盖 LitePBR sample/evaluate/PDF、delta/TIR/薄壁与数值清洗，以及实际纹理描述符、规范通道、法线分布、动画、atlas lookup 和发光消费。底层闭包数学、资源翻译和生产输运是不同验证层；单个数值域或无窗口夹具不能外推完整游戏材质和帧率。

测试入口的读回只用于无窗口行为验证，不进入游戏流水线。它们不替代两版 Minecraft 的窗口/全屏/HUD 验收。操作入口见 [CONTRIBUTING](../CONTRIBUTING.md)。
