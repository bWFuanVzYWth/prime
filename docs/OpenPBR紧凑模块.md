# OpenPBR 紧凑模块

Prime 以 OpenPBR Surface 1.1.1 为材质语义标准，以 RoboCute
`0d982c77b3fd26c2c5a3c0852be3bd05e5860bd8` 和锁定的 author overlay 为第三方行为参考。
`shaders/bsdf/compact` 是仓库内唯一实现；本地 RoboCute Slang port 已删除，参考项目不进入
生产或测试编译闭包。

## 等价性契约

紧凑实现只允许浮点求值顺序、公共子表达式复用和编译器降级造成的舍入差异。事件类型、
delta/solid-angle measure、采样分布、PDF、eta 和介质状态转换必须精确保持；随机数到事件的
映射也必须保持，唯一例外是能量权重的允许舍入误差所形成的零测度比较边界。拓扑选择只能使用
精确的零/一权重、thin-walled 标志和离散材质类型，不使用 epsilon 裁剪有效 lobe。

每个紧凑拓扑必须同时实现 evaluate、sample、PDF 和 directional energy。支持判断是接口契约，
当前材质翻译只允许构造已覆盖拓扑；支持判断用于验证这一边界。新增 OpenPBR 能力必须先补齐
精确拓扑和行为测试，不能回退到参考库或在紧凑状态中静默近似。

## 当前覆盖

`bsdf/compact/opaque/{state,lobes,evaluate,sample,energy,discrete}.slang` 覆盖 Prime 当前 opaque 翻译可产生的四类精确
OpenPBR 退化拓扑：

- dielectric base：Lambert base 与 dielectric specular 的能量感知层叠；
- conductor：GGX conductor 与 F82 tint；
- subsurface：权重精确为一的 thin-wall subsurface 与 dielectric specular 的层叠；
- mixed subsurface：分数权重的 diffuse/subsurface 混合，再与 dielectric specular 层叠。

该子集要求 base/specular weight 为一、metalness 精确为零或一，且 transmission、coat、fuzz、
thin-film 和 diffraction 权重为零。当前材质翻译的 `diffuse_roughness` 精确为零，因此首批不携带
通用 EON 粗糙漫反射；分数 subsurface 路径仍保留 EON 在零粗糙度下的随机数到方向映射。
`primeCompactOpaqueSupports` 集中表达这些前置条件，并拒绝当前未实现的非零
`diffuse_roughness` 以及厚表面 subsurface。LabPBR 的厚材质 subsurface 输入在生产 adapter
边界清零并退化为常规漫反射；当前参考闭包不使用 radius，继续暴露会产生无法控制的错误体内
传输，且不符合实时性能预算。

旧 adapter 曾把所有非零 subsurface 权重送入“权重精确为一”的 RoboCute specialization，导致
分数权重被忽略。紧凑路径改为按精确端点和分数区间分派，恢复 OpenPBR 定义的
diffuse/subsurface 混合；这属于标准一致性修复，不是模型近似。

紧凑状态不保存完整材质、未激活的 transmission/coat/fuzz/thin-film/diffraction 状态或通用
OpenPBR 组合树；sampling flags 作为操作参数传入，分量求值不再通过复制整个状态切换 flags。

`bsdf/compact/dielectric/{state,refract,evaluate,sample,energy,discrete,thin_wall,volume}.slang` 覆盖当前 dielectric transmission 的完整生产集合：厚介质
进入/退出、thin-wall、smooth/rough GGX、全闭包与条件 reflection/transmission 采样，以及相邻介质
和 air-gap 适配。状态只保留界面 ONB、相对/原始 IOR、两个实际使用的 microfacet、Fresnel、薄壁
tint、体积参数和多次散射补偿；sampling flags 不再驻留在状态中。dispersion 仍由支持判断明确拒绝。

厚介质辐亮度透射的 BSDF response 包含 `1/eta^2`；path-state `etaScale` 只修正 Russian roulette
的存活度量，不写回 throughput。Snell 方向、TIR、Fresnel、PDF、返回的 `relativeEta` 和体积栈
转换必须由同一个离散事件决定。锁定 RoboCute 版本已经包含该缩放；author overlay 只提供单独
锁定的 dielectric highlight energy compensation，两者均只作行为参考，不进入编译闭包。

`bsdf/compact/foliage/{state,lobes,evaluate,sample,energy}.slang` 覆盖当前固定薄壁 foliage 子集：dielectric diffuse/subsurface、
specular layer 和 15% colored thin-wall transmission，以及材质编码允许的 conductor 旁路。它复用
紧凑 opaque substrate，只增加 transmission microfacet、tint 和能量感知的 dielectric mix 状态。
普通 diffuse 也保留通用 OpenPBR EON 在零粗糙度时的随机数到方向映射。

`bsdf/compact/model/{material,volume}.slang` 承接默认 metallic 材质初始化和 outside-IOR 查询，使生产
适配器不再仅为这两个边界函数导入完整 OpenPBR 组合模块。

`bsdf/compact/contract/{flags,material,sample,volume}.slang` 定义生产自有的 `PrimeOpenPbr*`
材质、采样、事件和两层体积栈 ABI，但不定义通用组合树或完整 BSDF state。
`bsdf/compact/math/fresnel.slang` 只保留当前拓扑可达的
dielectric、conductor 和 F82 tint 公式；紧凑 Fresnel state 不携带三个 thin-film 字段，因为支持
边界精确拒绝非零 thin-film。`bsdf/compact/math/microfacet_measure.slang` 只判定离散测度，
`microfacet_energy.slang` 只实现 directional energy 与多次散射补偿，
`microfacet_distribution.slang` 才包含有限立体角 GGX 分布、采样、PDF 和反射基础操作。
因此 discrete-only entry 不会仅为分类或 albedo 看见粗糙采样代码。

## 切换策略

opaque、dielectric transmission 和 foliage 生产入口均已切换到紧凑状态。生产 ABI、Fresnel、
microfacet、材质初始化和体积栈全部由 compact 所有；仓库中不再保留第二套 RoboCute 组合树。
`verifyShaderArchitecture` 会拒绝恢复 `shaders/bsdf/core`、非 compact BSDF 或历史 reference
specialization，并检查所有现存 Slang 依赖可解析、无环且遵守层级预算。

尚未支持的 coat、fuzz、thin-film、diffraction 和非零 diffuse roughness 不进入材质翻译。是否
增加新的精确拓扑由产品需求决定；在实现、测试和性能边界完整前不得提前暴露。

## 测试

`CompactOpenPbrGpuTest` 在 Vulkan 上执行 40,960 个 opaque 用例。24,576 个端点用例扫描
dielectric、conductor 和 thin-wall subsurface；另有 16,384 个分数
subsurface 用例覆盖零附近、0.25、0.5、0.75 和一附近的权重、随机数边界、掠射角、
IOR、各向异性和粗糙度。测试直接验证 eval 分量求和、PDF、事件、能量、有限性、非负性和
状态不变量；分数路径还验证端点线性组合性质，不再把第二套参考实现编进同一测试单元。

该测试类的 transmission 组执行 36,864 个性质用例，覆盖厚介质进入/退出、thin-wall、
三组 sampling flags、smooth/rough 与 index-matched 边界，并验证状态、eval、PDF、有效事件、
方向、eta、directional energy、current medium 和退出时的 ray distance。参考公式可能生成的
无效 proposal 按生产 adapter 的接受谓词拒绝，不消费 provisional event 或介质状态。

foliage 组执行 12,288 个性质用例，覆盖 dielectric、分数 subsurface 和
conductor 三种拓扑，验证总 eval 与 diffuse/specular 分量求和、PDF、采样事件、directional
energy 和紧凑组合状态。

`OpenPbrCoreGpuTest` 验证 common、Fresnel 和反射 microfacet 的恒等式、边界与互易性质；
`OpenPbrDistributionGpuTest` 以采样直方图对 PDF，并以 Monte Carlo 能量对独立求积；
`OpenPbrTransmissionSlabGpuTest` 验证两界面 Snell、TIR、互反 eta、当前介质和 ray distance。

被拒绝且事件为 `NONE` 的 proposal payload 沿用 adapter 现有契约：payload 未定义且不会被消费，
测试不以其中的 NaN/Inf 判定失败；任何有效事件的 payload 仍必须完整通过数值检查。
