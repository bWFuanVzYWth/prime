# 内部材质格式、LabPBR 翻译与 OpenPBR 支持子域

Java读取实际资源声明、图像、sprite字段与获胜基色的来源；`prime_minecraft`在资源准备时解释外部格式并选择固定预设；`prime_scene`持有自足纹理和光学端点，GPU只消费内部参数。生产着色不查询Minecraft或资源包。布局见[ABI](abi.md)，几何和介质证明见[表面编译](surface-compiler.md)。

## 固定内部格式 v1

三个逻辑页均为非预乘RGBA8。可编辑内嵌资产统一使用8bit RGBA PNG；页可缺省，宿主基色可以共享atlas backing，固定布局不要求每个sprite分配三张完整图。

| 页 | R | G | B | A |
| --- | --- | --- | --- | --- |
| Color | 共享颜色R | G | B | coverage alpha |
| MERS | metalness | emission strength | perceptual roughness | thin SSS weight |
| Aux | tangent normal X | tangent normal Y | normal distribution perceptual roughness | specular IOR |

Color RGB采用sRGB编码，alpha为线性coverage；辅助页全部作为原始数值，不应用gamma/ICC转换。Color按既有源规则在编码域与tint相乘，再做一次EOTF和working RGB转换。MERS/Aux的A是数据，艺术导出必须保留A=0下的RGB，不可预乘或清除隐藏颜色。

MERS各通道按byte/255解码。粗糙度保存感知值，GGX alpha在执行时平方。IOR为`1 + 1.5 * byte/255`，范围[1,2.5]；85精确表示1.5，最近舍入误差上界约0.002941。超范围在准备时clamp。normal编码为`round(128+127*n)`，解码为`(byte-128)/127`；平面128/128精确表示零XY，Z重建非负半球。Aux B保存GGX方向分布对应的感知粗糙度，不保存AO或height。

缺图默认M=0、E=0、S=0、IOR=1.5；opaque roughness=0.9，已证明光学介质缺图保持光滑边界。缺MERS和显式S=0由页存在性区别，沿用植物薄壁回退。Aux存在与hasNormal分开：仅IOR贴图不触发法线映射。发光是否覆盖宿主值由metadata表达，不占用数值哨兵。8bit量化、标准金属共享颜色及mip/动画过滤引起的视觉精度损失是明确接受的格式边界。

## 外部输入与固定预设

LabPBR只接受`minecraft:optifine/texture.properties`中的`format=lab-pbr/1.3`，忽略大小写和首尾空白。辅助图位于`textures/<sprite path>_n.png`和`_s.png`，沿用namespace。Java读取可无损表示为RGBA8、位深不超过8bit的PNG；错误沿用一次诊断与整组回退。Rust解释单帧或与基色sheet匹配的列/行，无法匹配的有效图按单帧处理。

| LabPBR输入 | 准备阶段的内部输出 |
| --- | --- |
| `_n.RG` | 重建方向、归一化并转为Aux XY偏置编码；过滤产生Aux B分布粗糙度 |
| `_n.B/A` | AO和height丢弃，未消费源sheet不常驻 |
| `_s.R` | `1-R/255`转为MERS B感知粗糙度 |
| `_s.G` 0..229 | F0=`clamp(G/255,0.02,0.17)`转IOR并写Aux A；M=0 |
| `_s.G` 230..237 | 八种标准金属n/k转F0并预处理入共享Color；M=1，F82 tint固定1 |
| `_s.G` 255 | 共享Color为自定义金属F0；M=1 |
| `_s.G` 保留码 | 默认介电IOR1.5与M=0 |
| `_s.B` | 0..65丢弃；66..255的薄SSS权重转为MERS A的0..255 |
| `_s.A` | 0..254转MERS G的0..255；255转E=0，整sheet是否有authored emission另存metadata |

标准金属预转换接受与旧视角反射、tint和发光颜色的差异；一个RGB用于漫反射、薄SSS、发光和有色金属F0。GPU不读取Lab分类码、n/k表、porosity或哨兵。

有效外部Lab材质整组优先，包括normal-only。没有有效外部材质时，Java报告获胜基色是否来自实际pack ID `vanilla`；Rust按显式Java sprite ID选择内嵌预设，否则使用默认。此判断在资源代准备时完成，不作逐帧或逐像素匹配。预设使用固定Bedrock样例来源，以两版Java现有RGB为离线匹配键，优先精确匹配，再接受有界的空间颜色相似匹配；alpha不参与匹配，也不从样例覆盖宿主。金属、发光和薄SSS同样进入内部MERS，源帧按Java布局离线投影。模糊匹配与帧投影是明确接受的艺术近似，不证明两个版本的模型、UV或材质语义等价。登记、冲突规则与完整未接入清单见[资产说明](../crates/prime-minecraft/assets/vanilla_pbr/README.md)。运行时不读取Bedrock JSON、TGA、目录或MCPACK；材质包解析延期见[TODO](../TODO.md)。

PNG是最终可编辑资产，构建读取当前文件并嵌入，不从样例自动覆盖。离线工具生成候选、记录schema/来源/生成参数与许可；已发布材质进入现有资源所有权和512 MiB唯一backing预算。标准金属转换的原始sprite图仍由资源catalog保留，用于原基色参考及同代定义比较；这部分CPU存储和转换峰值不等同于GPU纹理预算，须另计。

Minecraft翻译层统一采用发光的或规则，覆盖内嵌预设与外部Lab：完整源及全部动画帧的MERS G全零时继承宿主block/quad发光，保留其他材质通道；任意帧存在正G则保留纹理的亮暗图案，当前暗texel保持暗。此规则是用户明确接受的翻译层修复，避免零G清除岩浆等方块的固有发光；通用Scene接口仍由authored标志声明源语义。

静水/流水从内嵌PBR登记中排除，忽略原版预设的粗糙度和法线，使用几何法线与既有光滑水边界。原版Color/alpha/tint与动画保持，物理IOR及消光不变。这是用户主动选择的艺术调整；有效外部Lab水材质仍沿用作者的整组输入。

## 过滤、动画与颜色一致性

Rust在资源准备阶段把材质页生成到实际sprite的帧尺寸和mip级数。CPU mip中的normal按方向与GGX平均长度过滤并编码分布，动画也合成moment。Color/MERS/Aux统一采用每mip层内最近点、跨mip线性插值；LOD0不把相邻像素的发光、粗糙度或法线混在一起。MERS各通道仍参与CPU mip平均与动画连续插值。Aux跨mip以RGBA插值后重建法线，接受其moment近似；分布粗糙度与材质粗糙度沿用squared-alpha-space近似组合。IOR固定当前帧mip0点采，不受LOD或动画插值影响。

动画按实际Minecraft序列、时长和千分位进度推进。MERS各通道连续混合并量化到RGBA8；Aux混合方向moment后恢复方向和分布，IOR保持当前帧。辅助页的GPU blend为0；Color沿用编码域RGBA→UNORM8插值。冻结保留当前资源视图，同相位跳过新视图，真实资源换代始终重新准备。

静态Color共享atlas；标准金属必要时一次生成替代Color并释放源specular。sprite-local与atlas-UV、发光、guide均消费同一有效Color。64 B描述符沿用两个辅助引用和atlas bounds，lookup将atlas UV映射到稳定sprite；动画只局部更新sprite，不重建整张材质atlas或BLAS。未知/跨sprite UV保持既有atlas边界。

26.2/26.3的item atlas独立于block atlas。Java在资源准备收录其真实sprite、全部Color mip/帧和获胜来源，Rust作为自持Color准备，避免它们的UV覆盖block atlas lookup。真实item quad的sprite与实际绑定atlas一致、且UV在其范围内时，成功资源事务发布的canonical texture ID和既有instance UV affine将其接到同一材质消费；原顶点/packed UV不改。资源重载撤销旧映射，世界reset保留同代目录。未知、跨sprite UV、foil/outline或通用raw回退仍使用原atlas路径；独立实体贴图尚无可靠来源字段，不能由item支持外推。

连续M过滤会产生分数金属度。m=0/1使用端点核，0<m<1使用介电/金属响应混合与完整marginal PDF，sample、evaluate、MIS、guide和delta判定一致；混合闭包保留非金属漫反射/薄SSS，不能只插值最终颜色。没有新增pass、队列、闭包持久状态或记录stride。实际寄存器与整帧收益需要生产测量，容量不变不能证明更快。

## 闭包与数值契约

生产与测试只使用 `bsdf/full/` 的 OpenPBR 支持子域；LitePBR、旧通用材质 facade 及厚SSS近似已删除。`pbr.slang` 只适配实际源到Full窄构造，离线增加采样并冻结场景，不另选材质模型。Full框架、事件、sample/evaluate/完整PDF、介质和实际结果清洗保留；源清洗由CPU负责，不能由源合法性推断BSDF求值结果必然有限。

Full来自锁定旧Prime `bsdf/compact`，使用精确介电Fresnel、F82 conductor、GGX方向能量、作者transmission-GGX LUT的多次散射补偿、thin-wall几何级数和完整marginal PDF。支持opaque/conductor、证明为薄表面的SSS及solid/thin dielectric；连续M由生产窄构造支持；通用Full参数API的metalness支持gate仍限定端点，不声明任意coat/fuzz/thin-film等完整OpenPBR参数API。旧预设及独立轻量PT不移植。

植物预设使用明确的艺术薄壁近似：宿主 `LeavesBlock` 身份及 Rust 版本适配中的常见叶片、花、作物、藤蔓、树苗等明确植物名称可将各表面当作叶片，完整模型可以是闭合的树叶方块。缺少 specular 图时使用50%有色薄壁SSS；只有normal图仍使用此回退。有MERS图时完全采用其SSS（包括零值）及金属度，不生成伪材质图，不改变介质身份。不根据十字模型识别植物，钟乳石、蜘蛛网和未知模组名称不进入预设；普通来源继续要求完整薄片证明。该近似不声明植物体积的物理正确性；明确接受依据与撤销条件见 [HACK](../HACK.md)。

**LabPBR厚SSS不支持。** 厚体和无法证明薄壁的来源忽略SSS强度，回到常规Full opaque漫反射/反射，保留基色、F0、粗糙度、法线与发光；不再白色漫透射，不做厚介质random walk。cutout、双面重复、树叶/草名称、体积证明失败均不能作为几何薄壁证据；植物预设是上述显式近似。

CPU在资源准备时检查完整确定性model source：全部quad须有限、精确affine且非退化，完整来源仅处于一个平面或两个相交平面才接受。当前可证明轴平面和两坐标的±和，使用精确和而非epsilon；两平行壳、闭体、未知/非确定性/更广曲面来源回退。该证明在可见性剔除前完成，资源/模型失效后重新准备；SSS能力覆盖全部源动画帧，并复用已有发光扫描。

材质薄壁证明与物理介质薄片分开。主表面/第二层分别携带bool，裁切、合并、侧选择保留来源；普通quad的已有word43存bit0/bit1，不扩176B记录或描述符。GPU只消费CPU证明和规范强度，同sprite可同时服务薄片与厚体，不复制整套图或全局抹薄片强度。已证明的物理介质薄片继续按原Fresnel/Beer合同处理，不用SSS证明改写介质。

`PrimePbrVertex`保持32 B：共享颜色三float、世界法线三float、有效粗糙度一float与control一uint。control保存M8/S8/IOR8及flags；法线分布在准备顶点时并入有效粗糙度，发光在实际命中阶段消费。过滤后的粗糙度直接使用，M/S因持久记录需要各量化一次。IOR解码和实际Fresnel、方向能量、BSDF求值及结果清洗仍在消费者处。

不可变transmission能量表为44×32×159 HALF4，解码后1,790,976 B，归一化线性clamp过滤。发行 `assets/openpbr/trans_ggx.ktx2` 使用Zstd22，作者原表与overlay锁在[来源清单](../crates/prime-vulkan/assets/openpbr/robocute.lock.json)，third_party原始参考不改。入口set0/binding9显式传入图像/采样器，初始化上传后按现有完成/失败隔离合同退休；不新增SSS查表、pass或全屏状态。

物理薄片的negative side是内部材料，positive side是外部介质，不按IOR大小猜inside/outside。直线阴影保留内部Snell余弦与两界面RGB级数：`A=exp(-sigma*t/cosInternal)`、`T=(1-F)^2*A/(1-F^2*A^2)`，有效厚度1/16m；IOR比为1时直接保留入射余弦，TIR拒绝透射。厚边界端点和吸收moment保持遍历顺序无关；直线连接不解算折射焦散。

薄SSS使用Full有色余弦双半球反射/透射及其精确零/一/分数混合，介质身份不变、relative eta=1；连续sample与NEE使用同一完整response和总方向PDF，不能把分量joint PDF当完整PDF。opaque缺图参考roughness为0.9，已证明dielectric缺图为光滑边界，normal分布仍参与。

内部薄SSS统一采用用户明确接受的反射保持补偿，适用于植物缺图回退、内嵌预设和有效外部Lab材质。令`C`为当前采样、tint及颜色解码后的线性working RGB，`s`为有效薄SSS权重；仅薄SSS分支使用`gain=1.5/max(1,1.5*maxRGB(C))`、`derivedSSSColor=gain*C`及`anisotropy=-1/3`。共享Color与普通diffuse颜色不改，金属F0、发光及物理介质参数也不借此改写；`s=0`保持原响应，厚/未知来源仍按既有支持gate回退。通用Full直接参数API仍消费调用者提供的颜色与anisotropy，不隐式套用此补偿。

在漫反射与薄SSS的颜色权重上，令`k=gain/1.5`，反射为`R=((1-s)+k*s)*C`，透射为`T=0.5*k*s*C`；这些不是包含Fresnel、高光及方向能量因子的总表面反射率。`maxRGB(C)<=2/3`时没有cap，`R=C`、`T=0.5*s*C`；较亮颜色按最大通道限制derivedSSSColor，反射与透射的SSS份额一起降低，普通diffuse的`(1-s)*C`不变，因此近白颜色只有部分补偿。它是有界的艺术响应定义，不是新的厚SSS或真实植物光谱模型；接受依据、支持边界及撤销条件见[H-002](../HACK.md#h-002)。PT与guide必须采用同一推导，sample/evaluate、完整PDF/MIS和零S行为由实际产物验证。

evaluate要求response/PDF有限非负，非法时整组清零；sample要求有效事件、有限单位方向（长度平方误差≤0.001）、正PDF和正relative eta，非法结果是零贡献/无事件。guide albedo逐通道清洗至[0,1]。实际MIS、稳定乘除、eta-aware roulette和medium交接继续共用数学核；删Lite不改变这些结果边界。真实Full LUT、独立双半球oracle、厚回退逐位等价、CPU共享sprite/动画/几何负例及ReSTIR薄SSS行为分别验证，源码删除不替代行为证据。

## 发光与介质

Lab全源A=255与翻译后全源G=0均保留宿主发光。材质发光基础幅度固定为1：只有纹理发光时，真实强度为当前G/255；只有方块发光时，使用`(lightLevel/15)^2`与共享Color。两者都发光时，对纹理幅度应用`lerp(1, texture_gain, emissionMix)`。功率混合默认50%，0%服从纹理，100%服从方块提议的功率；独立的方块发光EV默认0，以`2^EV`统一缩放物理局部发光，不影响太阳、天空与unlit文字。改变设置只更新renderer lighting controls及输运历史，不重新编译来源或灯表。

`texture_gain`在资源代准备时根据sRGB解码后的共享Color亮度、G、coverage与完整声明动画时长建立固定参考。两轴均不超过64的源，面编译按实际两半三角形的UV及世界面积求`native*integral(Color)/integral(Color*G)`：常见矩形域以积分表O(1)求值，一般域裁切texel面积；高分辨率、越界/repeat与非定点的rank-one UV使用全sprite参考，避免大图积分缓存和逐面裁切成本。校准保留纹理亮暗图案，没有逐帧消除闪烁，也不截断亮像素。固定动画端点/时长与未染色Color是简单艺术参考，并不宣称任意插值相位、顶点tint或后续几何裁切下精确保持通量。没有方块提议、参考积分为零及动态raw只有纹理来源时不混合。

命中、NEE与ReSTIR的实际发光统一当前帧mip0最近点域，包括Color及G；散射Color和M/R/S仍按ray-cone LOD采样。高LOD命中按需重取mip0发光，LOD0复用已有值，背面/常量回退/零强度不重取无消费者的Color。CPU灯权重使用覆盖整个混合范围的最大幅度参考，不替代当前真实辐亮度；动态发光尚未注册静态灯目录。

静态单材质、非repeat、平面affine-UV发光面可使用全动画支持域的texel alias。CPU将支持texel精确裁到两半三角形，按面积和白tint亮度包络建立提议；GPU直接采样裁切patch，保留实际半面与重心，不做拒绝重采样。NEE、BSDF命中反向PDF和ReSTIR重连共用实际面积密度；当前动画暗点仍保留提议PDF。支持包络按RGB、G和alpha的独立全帧最大值建立，确保动画插值支持可达。高于65536texel、分层、退化/非affine/越界UV、未知来源及全正低对比来源保留均匀面积提议。

源包络每texel为5B CPU数据，共享不可变owner；每面表附在既有local light-tree nodes尾部，使用发光记录padding中的偏移，不扩张GPU纹理descriptor或176/240/272/432B表面stride。动画descriptor-only更新复用包络与灯表；真实源变化先撤销受影响格，按既有预算重建，旧资源依GPU完成证明退休。alias降低采样方差，功率校准改变实际总能量，两者独立，整帧成本仍待原生1080p测量。

水保持物理IOR1.333与既有消光。玻璃吸收仍由原始首帧中心基色建立homogeneous参考，不随颜色预转换或法线改变。均匀Aux A可在CPU初始化介质，变化场保留资源身份；当前表面负侧按命中UV、邻接正侧按固定sprite midpoint读取当前帧mip0 Aux A，CPU/GPU使用同一仿射解码。光学引用继续复用既有记录，介质身份与量化数值独立。

介质占据、接触拥有者、coverage与表面闭包各有独立证明。法线贴图不改变几何覆盖；opaque NEE只覆盖反射侧，SSS透射continuation命中发光体或太阳不与该NEE竞争减权。dielectric连续反射/透射保留竞争PDF，离散事件保持单独测度；直线NEE不解算折射焦散。

验证覆盖PNG实际像素、量化端点、法线moment、Lab翻译、资源选择/换代、颜色与atlas一致性和生产BSDF response/sample/PDF。自动化无窗口验证不等于双版本游戏画面或原生1080p稳态性能验收；开发入口见[CONTRIBUTING](../CONTRIBUTING.md)。
