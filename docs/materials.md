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
| `_s.B` | CPU将porosity/保留码0..65清洗为0，66..255翻译为SSS强度1..190；规范B只表达强度，GPU以B/190消费，不再分类或清洗源码 |
| `_s.A` | 0..254 为发光强度，255 为未 authored 的 sentinel |

规范 dielectric F0 为 `clamp((code-1)/255,0.02,0.17)`；默认、保留码和需要 dielectric 退路的金属码使用 0.04。不同源身份即使清洗后 F0 相同也保留不同分类码，不能据此消除空气边界。标准金属保留源 eta/k 常量、BT.709/Rec.2020 颜色转换和 F82 tint；自定义金属使用规范基色。

## 过滤与动画

Rust 为每个辅助图生成与基色帧尺寸对应的规范 mip。法线 footprint 内逐 texel 重建并归一化方向，平均后得到方向和平均长度；平均长度按 GGX macro-normal-length 表量化为页 A 的分布粗糙度，AO 作整数平均。光学页 R 作整数平均，G/B 取 footprint 中心；发光 sentinel 在混合 footprint 中贡献0，只有全 sentinel 时保留255。

辅助图按实际 Minecraft frame 序列、时长和千分位插值进度推进；进度沿用源 f32 比例向零截断到千分位。法线动画混合已过滤方向后归一化，AO/分布粗糙度按整数规则混合；光学动画只混合 R/A，G/B 保持当前帧直到离散切帧。两端都为发光 sentinel 时保持255，否则 sentinel 按0混合。这些动画结果由 CPU 发布，辅助图 GPU 描述符的 blend 为0；基色继续使用宿主编码域 RGBA→UNORM8 插值语义。冻结期间保留当前结果。

生产连续通道采用层内线性、层间线性过滤。法线线性过滤使用方向与 GGX 平均长度，过滤新增的分布粗糙度与材质粗糙度沿用源 squared-alpha-space 近似组合。光学 R/A 连续过滤后量化；分类 G/B 始终从当前帧 **mip0 点采样**，不随射线足迹选择分类 mip。各级 CPU categorical mip 仍保留完整源翻译语义。射线锥当前只估计主像素足迹，未包含粗糙反弹扩散。

静态基色视图共享 atlas backing；辅助图、所有动画帧和 mip 按实际 Arc backing 计入纹理预算。64B 描述符带独立 normal/specular 视图及归一化 atlas bounds。atlas UV 消费先读静态 lookup，取得稳定 sprite descriptor，再转为局部 UV；动画更新 sprite 辅助图，不重建整张材质 atlas 或 BLAS。lookup 不替代基色跨 sprite UV 的原有兼容边界。纹理元数据变化仍发布描述符；完整 backing 序列相同则不构造引用差分集合，保留现有像素所有权。序列不同或重新分配时仍按去重集合精确增减引用，不能按相同字节猜测同一 owner。资源按源引用、CPU 最后消费者和 GPU 完成证明退休。

## 闭包与数值契约

生产与测试只使用 `bsdf/full/` 的 OpenPBR 支持子域；LitePBR、旧通用材质 facade 及厚SSS近似已删除。`pbr.slang` 只适配实际源到Full窄构造，离线增加采样并冻结场景，不另选材质模型。Full框架、事件、sample/evaluate/完整PDF、介质和实际结果清洗保留；源清洗由CPU负责，不能由源合法性推断BSDF求值结果必然有限。

Full来自锁定旧Prime `bsdf/compact`，使用精确介电Fresnel、F82 conductor、GGX方向能量、作者transmission-GGX LUT的多次散射补偿、thin-wall几何级数和完整marginal PDF。支持opaque/conductor、证明为薄表面的SSS及solid/thin dielectric；不声明任意coat/fuzz/thin-film等完整OpenPBR参数API。旧预设及独立轻量PT不移植。

**LabPBR厚SSS不支持。** 厚体和无法证明薄壁的来源忽略SSS强度，回到常规Full opaque漫反射/反射，保留基色、F0、粗糙度、法线与发光；不再白色漫透射，不做厚介质random walk。cutout、双面重复、树叶/草名称、体积证明失败均不能作为薄壁证据。

CPU在资源准备时检查完整确定性model source：全部quad须有限、精确affine且非退化，完整来源仅处于一个平面或两个相交平面才接受。当前可证明轴平面和两坐标的±和，使用精确和而非epsilon；两平行壳、闭体、未知/非确定性/更广曲面来源回退。该证明在可见性剔除前完成，资源/模型失效后重新准备；SSS能力覆盖全部源动画帧，并复用已有发光扫描。

材质薄壁证明与物理介质薄片分开。主表面/第二层分别携带bool，裁切、合并、侧选择保留来源；普通quad的已有word43存bit0/bit1，不扩176B记录或描述符。GPU只消费CPU证明和规范强度，同sprite可同时服务薄片与厚体，不复制整套图或全局抹薄片强度。已证明的物理介质薄片继续按原Fresnel/Beer合同处理，不用SSS证明改写介质。

`PrimePbrVertex`消费normal A、specular RGB和证明，emission A在发光阶段消费；AO/porosity不参与闭包。roughness仍先按实际UV/LOD过滤并量化，再组合normal分布。G的源身份由CPU规范化，视角相关F0/IOR、方向能量、BSDF采样/求值及结果清洗仍在实际消费者处。

不可变transmission能量表为44×32×159 HALF4，解码后1,790,976 B，归一化线性clamp过滤。发行 `assets/openpbr/trans_ggx.ktx2` 使用Zstd22，作者原表与overlay锁在[来源清单](../crates/prime-vulkan/assets/openpbr/robocute.lock.json)，third_party原始参考不改。入口set0/binding9显式传入图像/采样器，初始化上传后按现有完成/失败隔离合同退休；不新增SSS查表、pass或全屏状态。

物理薄片的negative side是内部材料，positive side是外部介质，不按IOR大小猜inside/outside。直线阴影保留内部Snell余弦与两界面RGB级数：`A=exp(-sigma*t/cosInternal)`、`T=(1-F)^2*A/(1-F^2*A^2)`，有效厚度1/16m；IOR比为1时直接保留入射余弦，TIR拒绝透射。厚边界端点和吸收moment保持遍历顺序无关；直线连接不解算折射焦散。

薄SSS使用Full有色余弦双半球反射/透射及其精确零/一/分数混合，介质身份不变、relative eta=1；连续sample与NEE使用同一完整response和总方向PDF，不能把分量joint PDF当完整PDF。opaque缺图参考roughness为0.9，已证明dielectric缺图为光滑边界，normal分布仍参与。

evaluate要求response/PDF有限非负，非法时整组清零；sample要求有效事件、有限单位方向（长度平方误差≤0.001）、正PDF和正relative eta，非法结果是零贡献/无事件。guide albedo逐通道清洗至[0,1]。实际MIS、稳定乘除、eta-aware roulette和medium交接继续共用数学核；删Lite不改变这些结果边界。真实Full LUT、独立双半球oracle、厚回退逐位等价、CPU共享sprite/动画/几何负例及ReSTIR薄SSS行为分别验证，源码删除不替代行为证据。

## 发光与介质

若整张 specular source 的所有帧 A 都为255，则保留实际 block/quad emission。任意 A 小于255即为 authored emission，包括0明确关闭发光；真实强度按当前帧采样 A/254，255 解码为0，满强度沿用 1.5 的源校准并乘规范纹理×tint。CPU 静态灯 proposal 使用全部源帧的最大 authored 强度，因此动画不会使可发光支持域变得不可达，也无需逐帧重建 alias 表。命中与 NEE 的实际发光使用当前采样结果；proposal 不替代真实辐亮度。动态发光尚未注册静态灯目录。

水保持 IOR 1.333 与既有消光。玻璃消光继续由首源帧中心基色建立 homogeneous 参考，不随纹理法线或 G 改变。均匀规范 G 可在 CPU 转为 IOR 并参与介质身份；空间或动画变化的 G 保留源身份，当前表面负侧在命中 UV 取 mip0 G，邻接正侧在固定 sprite midpoint 取当前帧 G。IOR 使用 `eta=(1+sqrt(F0))/(1-sqrt(F0))`。这些引用复用已有光学记录字段和稳定 descriptor，不扩张几何步长或增加 descriptor binding。

介质占据、接触拥有者、coverage 和当前表面闭包各有独立证明。法线贴图不改变几何覆盖；opaque NEE 仅覆盖反射侧，SSS 的透射 continuation 命中发光体或太阳时不与该 NEE 竞争减权。dielectric 连续反射/透射均保留竞争 PDF，离散事件保持单独测度。直线 NEE 仍不解算折射焦散。自动化的 CPU/GPU 行为检查不等于完整资源包、双版本游戏画面或原生1080p稳态性能验收；后续工程与科学验证入口见 [TODO](../TODO.md) 和 [CONTRIBUTING](../CONTRIBUTING.md)。
