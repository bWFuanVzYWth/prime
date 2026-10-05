# LabPBR 源适配与 OpenPBR 支持子域

Java 读取资源包的实际声明、RGBA8 图像和 Minecraft sprite 字段；`prime_minecraft` 负责源布局、分类码、过滤、动画及发光支持域；`prime_scene` 持有规范纹理和光学端点；Vulkan 与 shader 消费这些自持数据。生产消费不再查询 Minecraft 或资源文件。源格式与 GPU 描述符见 [ABI](abi.md)，几何与介质证明见[表面编译](surface-compiler.md)。

## 资源声明与规范通道

仅接受 `minecraft:optifine/texture.properties` 中的 `format=lab-pbr/1.3`，比较忽略大小写与首尾空白。辅助图位置为 `textures/<sprite path>_n.png` 和 `_s.png`，沿用 sprite namespace。缺少声明、格式不支持、缺图或非法图像均不伪装成已提供材质；非法资源记录诊断，该 sprite 不发布部分材料。当前读取通道位深不超过8 bit、可无损表示为 RGBA8 的 PNG，Java 不对像素做 PBR 数值清洗。

辅助图可以是单帧图，也可以匹配基色 sheet 的列/行布局；尺寸不同的有效图由 Rust 按源布局映射到基色帧。无法匹配 sheet 布局的图沿用源实现的单帧解释。资源定义在失效代次内不可原地改变；重新加载重新准备。当前每个 `SourceSprites` 实例一次准备完整 block atlas sprite 字典，无论是否存在有效 LabPBR 辅助图，使地形和使用 atlas UV 的物品共享同一份材料来源。独立资源owner在初始化/真实重载准备此字典，通过typed资源事务连同atlas原子登记；world/几何重置保留该资源代，renderer重建重新准备native/GPU所有者。

| 源通道 | 规范通道与消费 |
| --- | --- |
| `_n.RG` | 切线法线 XY；重建非负 Z 并归一化 |
| `_n.B` | AO 保留于法线页 B，当前不直接乘着色或可见性 |
| `_n.A` | 原始 height 单独保留，按每帧 minimum 解码 `(A-min)/255`；未接入旧 voxel displacement 几何管线 |
| 法线页 A | 过滤法线分布对应的 GGX 感知粗糙度，替代原 height |
| `_s.R` | 光滑度字节；光学解码在过滤与字节量化后取 `clamp(1-R/255,0,1)` |
| `_s.G` | 源 `0..237 → +1`，`255 → 239`，保留码 `238..254 → 0`；规范码 0 为默认 dielectric，1..230 为 dielectric 身份，231..238 为八种标准金属，239 为自定义金属 |
| `_s.B` | `0..64` 保留 porosity；65 清洗为0；66..255 减1，规范 65..254 对应 SSS 1..190；porosity 当前不直接参与着色 |
| `_s.A` | 0..254 为发光强度，255 为未 authored 的 sentinel |

规范 dielectric F0 为 `clamp((code-1)/255,0.02,0.17)`；默认、保留码和需要 dielectric 退路的金属码使用 0.04。不同源身份即使清洗后 F0 相同也保留不同分类码，不能据此消除空气边界。标准金属保留源 eta/k 常量、BT.709/Rec.2020 颜色转换和 F82 tint；自定义金属使用规范基色。

## 过滤与动画

Rust 为每个辅助图生成与基色帧尺寸对应的规范 mip。法线 footprint 内逐 texel 重建并归一化方向，平均后得到方向和平均长度；平均长度按 GGX macro-normal-length 表量化为页 A 的分布粗糙度，AO 作整数平均。光学页 R 作整数平均，G/B 取 footprint 中心；发光 sentinel 在混合 footprint 中贡献0，只有全 sentinel 时保留255。

辅助图按实际 Minecraft frame 序列、时长和千分位插值进度推进；进度沿用源 f32 比例向零截断到千分位。法线动画混合已过滤方向后归一化，AO/分布粗糙度按整数规则混合；光学动画只混合 R/A，G/B 保持当前帧直到离散切帧。两端都为发光 sentinel 时保持255，否则 sentinel 按0混合。这些动画结果由 CPU 发布，辅助图 GPU 描述符的 blend 为0；基色继续使用宿主编码域 RGBA→UNORM8 插值语义。冻结期间保留当前结果。

生产连续通道采用层内线性、层间线性过滤。法线线性过滤使用方向与 GGX 平均长度，过滤新增的分布粗糙度与材质粗糙度沿用源 squared-alpha-space 近似组合。光学 R/A 连续过滤后量化；分类 G/B 始终从当前帧 **mip0 点采样**，不随射线足迹选择分类 mip。各级 CPU categorical mip 仍保留完整源翻译语义。射线锥当前只估计主像素足迹，未包含粗糙反弹扩散。

静态基色视图共享 atlas backing；辅助图、所有动画帧和 mip 按实际 Arc backing 计入纹理预算。64B 描述符带独立 normal/specular 视图及归一化 atlas bounds。atlas UV 消费先读静态 lookup，取得稳定 sprite descriptor，再转为局部 UV；动画更新 sprite 辅助图，不重建整张材质 atlas 或 BLAS。lookup 不替代基色跨 sprite UV 的原有兼容边界。纹理元数据变化仍发布描述符；完整 backing 序列相同则不构造引用差分集合，保留现有像素所有权。序列不同或重新分配时仍按去重集合精确增减引用，不能按相同字节猜测同一 owner。资源按源引用、CPU 最后消费者和 GPU 完成证明退休。

## 闭包与数值契约

`shaders/bsdf/lite/bsdf.slang` 保留旧 Prime 正式 LitePBR 的 opaque、solid/thin dielectric 和 foliage 状态、支持谓词、evaluate、sample、PDF、离散事件与体积端点。`pbr.slang` 提供自持的统一材质 API 与生产源适配入口。实时与离线的生产 opaque/dielectric 使用 `bsdf/full/` 的高质量 OpenPBR 支持子域数学；离线增加采样并冻结场景，不改变材质模型。LitePBR 通用 API 保留为历史参考及旧支持域之外的 thick-SSS 扩展。

`bsdf/common/common.slang` 与 `material.slang` 提供材质值类型、事件 flags、坐标框架、标量数学、介质栈与默认初始化；历史 `PrimeOpenPbr*` 类型名作为内部 API 保留，不表示实现完整 OpenPBR。三个 LitePBR 家族的支持谓词拒绝 coat、fuzz、thin-film 等未支持组合；生产源适配只建立已有证明的拓扑。

生产源适配当前使用 `PrimePbrVertex`，输入只消费 normal A 与 specular RGB；emission A 在交点发光阶段消费，AO 和 porosity 不进入闭包。粗糙度仍先按当前 UV/LOD 过滤、量化，再与 normal 分布组合。后续 opaque/transmission 窄构造直接消费 `bsdf/full/` 的旧完整模型支持子域数学核；完整响应、总 PDF、事件、eta、介质切换及数值检查与通用入口遵守同一契约。当前值接口、消费切面与可修改的性能策略见 [PT 依赖与性能设计](pt-state-design.md)。

生产 Full 路径来自旧 Prime 的 `bsdf/compact`，使用精确介电 Fresnel、F82 conductor 与 GGX 方向能量、作者 transmission-GGX LUT 的反射/透射分支多次散射补偿、精确 thin-wall 几何级数及完整 marginal PDF。生产支持 opaque dielectric、conductor、薄壁 subsurface 的精确零/一/分数混合，以及 solid/thin dielectric；这是完整 OpenPBR 数学在实际源拓扑上的支持子域，不是任意 coat/fuzz/thin-film/diffraction/dispersion 参数 API。普通源不自动选择 foliage，也不移植预设。LabPBR 厚壁 authored SSS 超出旧 compact 支持域，继续单独使用现有 Lite 厚壁近似，事件与介质身份保持当前契约。

不可变 transmission 能量表为44×32×159 HALF4，解码后1,790,976 bytes，使用归一化线性clamp过滤。发行资产为 `assets/openpbr/trans_ggx.ktx2`，内部Zstd 22，参数轴与原位模式保持；作者原始表与overlay仍锁在 [robocute.lock.json](../crates/prime-vulkan/assets/openpbr/robocute.lock.json)，不改动锁定参考目录。入口在set0/binding9绑定图像/采样器并显式传入库；资源位于device-local memory，构造时直接解入pending staging，首次实际命令录制上传，随后复用，按已有宿主完成/失败隔离契约退休。overlay不与thick-glass eta修复混淆。

保留的 LitePBR 是低阶散射模型，不符合完整 OpenPBR。opaque dielectric 使用 single-scatter anisotropic GGX，将缺失方向能量以标量闭合压回现有 GGX 瓣，并以当前入射方向剩余能量混合基底；conductor 使用 single-scatter GGX 与 generalized Schlick/F82。方向能量来自无纹理的 GGX/Schlick 解析拟合。该闭合不恢复多次散射的低频角分布；opaque dielectric 的层叠以当前入射方向为条件，不声明反射互易性。

solid dielectric 使用 Walter GGX 反射/折射、相关 Smith masking-shadowing、Snell/TIR 与 Beer-Lambert 吸收；thin-wall 保留两界面 Fresnel/吸收解析级数和轻量 single-scatter GGX。漫反射使用余弦半球，GGX 使用可见法线采样；分量选择概率与所选分量的响应/PDF 成对。没有 transmission directional-energy LUT、表驱动多次散射补偿或对应 GPU binding。高粗糙度反射/透射的角分布和能量仍需按此近似边界评估。

物理薄片的 negative side 是 authored 内部材料，positive side 是已解析外部介质；查询保留选择 coating 前的物理正反侧。inside/outside 不按 IOR 大小推断，因此材料 IOR 低于邻水时仍使用该材料的吸收及正确 TIR。直线阴影逐片使用真实外部 IOR 与 Full 相同的内部 Snell 余弦和两界面 RGB 级数：`A=exp(-sigma*t/cosInternal)`，`T=(1-F)^2*A/(1-F^2*A^2)`，有效厚度 t=1/16 m；不以外部余弦 Beer 乘无吸收级数替代。IOR 比恰为1时 Fresnel 为零并直接保留入射余弦，避免 grazing 的0/0。厚边界的终点介质取最远候选，吸收 moment 累积保持 BVH 顺序无关；直线连接仍不解算折射焦散。

LitePBR 数学保留两项支持修正：thin-wall 对外侧 IOR 大于内侧、Snell 折射余弦平方不大于0的情况显式返回 `R=1, T=0`，修正历史端点的非零透射；折射采样的反射候选检查从 `dot(wi,wi+wo)*wi.z` 去掉重复的 `wi.z` 符号因子，避免退出侧反射及 TIR 被提前丢弃。归一化且朝 +Z 的半向量和后续两侧支持检查保留；薄壁 relative eta=1 与介质身份保持规则不变，其余 LitePBR 数学按历史来源保留。

生产根据已有表面分类使用 opaque 和已证明水/玻璃的 dielectric 闭包；普通 opaque 缺图参考粗糙度为0.9，已证明的 dielectric 缺 specular 图时为光滑边界，法线分布仍可提高粗糙度。非金属且规范 specular B>64 的 authored SSS 进入 opaque 的 subsurface 分支。optical thin 或 cutout authored SSS 使用 Full thin-material 的有色余弦双半球 reflection/transmission，thin-material 与介质几何薄片标志分别表示；其余使用历史 thick 分支的白色 diffuse transmission，SSS 分量的采样概率为反射0、透射1，介质身份不变。该 thick 分支是低阶近似，不表示厚介质 random-walk 散射。已证明介质薄片保留 1/16 m 有效厚度，foliage 底层 API 不意味着 Minecraft 树叶已自动采用该拓扑或旧固定 15% 透射权重。旧 PBR presets、`MaterialRecipeResolver` 的名称配方和用户材质预设不移植。

材质边界保留粗糙度端点、F0 清洗和法线有效反射修正；两种后端保留 delta/连续事件区别、Fresnel/TIR、薄壁与 eta² 辐亮度权重。BSDF evaluate 的 response/PDF 必须有限且非负；任一通道非法时整组 response/PDF 清零，分量 evaluate 也整组清零。sample 还要求非零事件、有限单位方向（长度平方误差≤0.001）、正 PDF 和正 relative eta；非法结果成为零贡献无事件样本，不能携带非法介质继续传播。denoise albedo 逐通道将非有限值置零、有限值限制到 `[0,1]`。normal-mapped NEE 的反射/透射分类与 continuation 使用相同支持语义。功率启发式、乘除及 eta/roulette 数值辅助保留；生产 MIS 先计算带权 inverse PDF，再使用稳定 triple product 组合贡献。生产 facade 的有效连续 sample 与 NEE 使用相同的完整 response 和总方向 PDF：多瓣 opaque/foliage 在同一 state 对采样方向重新完整 evaluate，保留原 flags/eta；底层历史 sample 仍保留所选 component response/joint PDF，delta 保持所选离散测度。生产路径从第二次有效散射开始 RR，存活率为 clamp(maxRGB(throughput) × etaScale, 0, 1)；透射后 etaScale 乘 relativeEta²，反射/TIR 不改变它，薄壁透射的 relativeEta 为 1。存活路径按概率重加权；零概率直接终止，单位概率不生成 RR 样本。未知或缺失输入与合法零值分别表示。代码来源与许可见[第三方声明](../THIRD_PARTY_NOTICES.md)。

## 发光与介质

若整张 specular source 的所有帧 A 都为255，则保留实际 block/quad emission。任意 A 小于255即为 authored emission，包括0明确关闭发光；真实强度按当前帧采样 A/254，255 解码为0，满强度沿用 1.5 的源校准并乘规范纹理×tint。CPU 静态灯 proposal 使用全部源帧的最大 authored 强度，因此动画不会使可发光支持域变得不可达，也无需逐帧重建 alias 表。命中与 NEE 的实际发光使用当前采样结果；proposal 不替代真实辐亮度。动态发光尚未注册静态灯目录。

水保持 IOR 1.333 与既有消光。玻璃消光继续由首源帧中心基色建立 homogeneous 参考，不随纹理法线或 G 改变。均匀规范 G 可在 CPU 转为 IOR 并参与介质身份；空间或动画变化的 G 保留源身份，当前表面负侧在命中 UV 取 mip0 G，邻接正侧在固定 sprite midpoint 取当前帧 G。IOR 使用 `eta=(1+sqrt(F0))/(1-sqrt(F0))`。这些引用复用已有光学记录字段和稳定 descriptor，不扩张几何步长或增加 descriptor binding。

介质占据、接触拥有者、coverage 和当前表面闭包各有独立证明。法线贴图不改变几何覆盖；opaque NEE 仅覆盖反射侧，SSS 的透射 continuation 命中发光体或太阳时不与该 NEE 竞争减权。dielectric 连续反射/透射均保留竞争 PDF，离散事件保持单独测度。直线 NEE 仍不解算折射焦散。自动化的 CPU/GPU 行为检查不等于完整资源包、双版本游戏画面或原生1080p稳态性能验收；后续工程与科学验证入口见 [TODO](../TODO.md) 和 [CONTRIBUTING](../CONTRIBUTING.md)。
