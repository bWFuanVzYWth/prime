# 统一材质 IR 与闭包

Prime 的材质数据只能沿以下方向流动：

```text
Minecraft 表面语义 / 规范 TextureRecord / 默认值
  → MaterialRecipe
  → PrimeMaterialSample
  → opaque / dielectric / foliage 紧凑状态
  → traits / evaluate / sample / albedo operation dispatcher
```

源格式和 atlas 坐标只在输入适配层出现，完整纹理契约见[纹理翻译架构](纹理翻译架构.md)。
材质 IR 是[渲染核心数据 IR](渲染核心数据IR.md)的 Scene/Transport 子规范；颜色、坐标、精度
准入、所有权和 semantic/encoding 分离以该文为准。
命中 ABI、surface relation、闭包编译器和积分器只消费 Prime
的规范语义；它们不得根据 LabPBR 字节、纹理来源或 roughness 猜测材质类别和离散事件。
Prime 的 OpenPBR ABI、公式和专用状态位于 `shaders/bsdf/compact`，材质翻译与窄适配位于
`shaders/model/material` 和 `shaders/service/bsdf`；
固定第三方参考仅用于仓库外核对，不存在可编译的本地参考闭包。

## CPU 配方与图元编码

捕获阶段为每个面确定 `BuiltinMaterialClass`，地形翻译再以纯函数
`MaterialRecipeResolver` 把表面事实、逐 sprite 的 `_n`/`_s` 可用性和几何控制转换为：

- `MaterialRecipe`：coverage、scattering family、medium hint、detail mask 和 builtin class；
- `PrimitiveControl`：配方以及 animated、tangent handedness、front-face-only 和 raster
  composite 几何控制。

构造器拒绝非法组合：water 只能是 solid dielectric，glass 只能是 dielectric，foliage 必须
alpha cutout，negative tangent 必须确有 normal texture。albedo alpha 始终保留 coverage 或
玻璃 authored alpha 语义，不承载 Prime 元数据。

逻辑控制字固定为 15 bit：

| bit | 语义 |
| ---: | --- |
| 0 | alpha cutout |
| 1 | animated |
| 2..3 | opaque / dielectric solid / dielectric thin / foliage thin |
| 4 | water medium |
| 5 | normal texture available |
| 6 | optical texture available |
| 7 | negative tangent |
| 8 | front-face-only |
| 9 | raster composite |
| 10 | 保留，必须为 0 |
| 11..14 | builtin material class |

CPU 翻译记录不扩容：逻辑位 0..7 写入 `tint.a`，8..10 写入
`flagsEmitter[0..2]`，11..14 写入 `flagsEmitter[27..30]`。Vulkan 上传为非零 `MaterialId` 清除其中
immutable recipe/builtin，只保留 tangent handedness、front-face 与 emitter/relation payload；Shader
从 material core 恢复 recipe。其他代码不得解释这些物理位置。

材质布局以 exact u16 `MaterialId` 查 renderer-generation 全局 immutable table：
`TextureId`、`MediumId`、recipe/source codes、availability、coverage、emission 和 animation facts
每 material 只保存一次。triangle 只保留
UV、relation、连续 tint-field addressing 等几何变化量；secondary relation 与 u32 emitter 引用同一
material record，不再复制完整材质 primitive。section/cluster 不建立重复 material palette；GPU 以
`MaterialId` 一跳寻址固定 schema，各阶段通过 generated accessor 按需窄加载，只有确实消费多数
事实时才合并加载。完整 material record 不进入跨阶段 path state；冷 companion data 最多按
exact availability 再读取一次，不使用变长编码或指针链。

当前 material core 的首两个固定 word 已落实这一访问契约：word0 为
`TextureId:u16 | recipe:u16`，word1 为 `MediumId:u16`。accessor 提供单 word 与 `Load2`，调用点按
生命周期选择，不能因为物理相邻就强制整记录存活。table-backed primitive 的 identity 为
`TintId:u16 | MaterialId:u16`；TintId 寻址 renderer-lifetime 全局 source-linear sRGB `RGBA16F`
sample，常量 quad 不复制 sample。只有实际出现四角差异时才分配 companion tint field。

`flagsEmitter` 的中段按 primitive 模式复用：table-backed 普通静态图元保存 relation word
offset+1，静态 emitter 保存 `emitterIndex + 1`，dynamic 保存 6-bit scene texture index、red-alpha
和 visible-emission。静态 emitter 与 relation offset 均受精确 24-bit 上限约束，dynamic scene
texture 上限为 64，CPU/Vulkan 编码边界负责验证。overlay/bilateral 使用通用 relation，不再占用
该 payload 保存复合颜色或第二份材质语义。

CPU scene compiler 的输出由直接语义、性质与上传边界测试验证，不再复制一套测试专用编码模型。
dynamic primitive 只存在于帧内。

## Canonical shader IR

命中阶段合成一个寄存器内 `PrimeMaterialSample`：D65 linear Rec.2020 base color、opacity、
shading normal、roughness、`materialControl` 和 `opticalControl`。roughness 的权威持久化来源是
u8 code/texture；过滤、法线分布组合和 BSDF 消费在寄存器中使用 f32，不按 triangle 保存派生 f32。
合成顺序固定为：

1. 采样 base/albedo；
2. 建立全局 roughness 与 dielectric F0 默认值；
3. 若开启原版预设，应用 builtin class；
4. 若当前 sprite 有有效 `_s`，规范 optical decoder 覆盖 roughness、Fresnel、SSS 和 porosity；
5. 若当前 sprite 有有效 `_n`，应用分布感知法线与粗糙度；
6. 应用玻璃、水、薄壁、叶片和 alpha 语义。

availability 是逐 sprite 事实；关闭时不采样 descriptor 完整性所需的 1×1 dummy image。
“无 / 资源包法线 / 几何位移”在地形翻译前解析为互斥模式；默认资源包法线，无 `_n` 时
不会生成法线依赖或命中采样。法线 page 的 RG 以不低于源切线空间 RG8 的信息量保存归一化
平均方向，B 保留 AO，A 保存从
平均法线长度反演的等效 GGX 感知粗糙度；运行时在 squared-alpha 空间与材质粗糙度合并。
原始 normal alpha 只留在 CPU 位移路径。几何法线仍唯一决定可见性、介质边界和射线原点；
法线贴图的 BSDF 方向另受几何半球约束。发光仍由 emitter 管线独立提取。

`materialControl` 的低 8 位依次表达 family、medium、thin-walled、animated、visible
emission 和 decorative interface。decorative interface 表示玻璃 alpha 规则选中的磨砂或
切割界面，与数值 roughness 分开保存，以便相邻空气微缝做语义兼容判断。

`opticalControl` 的低三个 byte 分别为 Fresnel、subsurface 和 porosity code，bit 24 表示当前
表面实际使用了法线贴图，以便积分器只对这些路径执行几何半球约束：

- Fresnel 0 精确表示 dielectric F0 0.04；1..230 表示规范化后的 LabPBR dielectric；
  231..238 是固定命名金属；239 是 base-color conductor；240..255 保留；
- subsurface 0 表示关闭，1..190 表示权重 `code / 190`；
- porosity 0..64 表示 `code / 64`，当前闭包尚不求值，但不再保留源格式字节。

规范 optical 页面使用 RGBA8：R 为补数 roughness，G 为 Fresnel 身份，B 为 SSS/porosity
联合编码（0..64 为 porosity，65..254 为 SSS code+64），A 保持发光 encoding。
资源翻译与体素烘焙统一把 LabPBR 源字节转换为该 encoding；保留码不进入 BSDF。
R/A 过滤与舍入顺序不变，R=255 的浮点端点保护属于 encoding 解码。

subsurface 的 thin/thick 策略在最终 surface 合成处完成：共享纹理本身没有唯一材质角色。
厚材质的 SSS code 在合成时清零；非金属薄壁才消费权重，闭包不再重新钳制或判断源字节。
介电 Fresnel code 的物理值由固定码表定义为 `[0.02, 0.17]` 内的 F0，默认与金属角色的介电
值为 0.04；小型算术解码保持既有浮点公式。具有相同 F0 的不同身份仍须保留，空气微缝匹配
比较的是精确身份。以上均为翻译/encoding 契约，不属于 compact OpenPBR 内部数学。

法线贴图激活时，evaluate、NEE 和 realtime/offline continuation 共用同一几何支持域：反射
方向必须与入射方向位于几何法线同侧，透射必须位于异侧。违反支持域的 sample 直接终止，不
重采样、不重新归一化，也不施加 Veach shading-normal correction；由此造成的截断能量是明确
接受的法线贴图策略。

玻璃保持既有规则：稳定 reference alpha 判定 stained，当前 alpha 与 seamless 开关判定
decorative；无色 decorative 使用 opaque rough dielectric，染色 decorative 保持透射；光滑
玻璃主体 roughness 为 0；decorative 优先使用 authored `_s`，否则使用全局默认。water 保持
IOR 1.333 和既有吸收参数。

### 不透明底色观感补偿

完整实时与离线渲染器共用可选的底色观感补偿。设置位于材质分组，名称为“底色饱和度补偿”，
配置键为 `material.base_color_compensation`，默认开启；旧配置缺少该键时也默认开启，明确
保存的 `false` 保持关闭。关闭时普通不透明电介质恢复源底色，开启时在线性 Rec.2020 中
逐通道设置 `base = pow(target, 1.04)`。

指数固定，不再依赖 F0、粗糙度或 RGB 最小值。它是用户选择的非线性观感调整，不再以
精确抵消正视高光能量为目标。compact OpenPBR 的 evaluate、sample、PDF 和 energy
继续使用原公式。映射在实数域内对每个通道严格单调；同一个输入通道不会因其他颜色通道或
光学参数变化而改变。它不保证任意不同色相之间的亮度排序、RGB 差值比例或最终受光颜色排序，
高光反射下限仍存在。黑白端点保持不变，正常颜色不需要额外钳制。

Lambert 基线继续使用源颜色。金属、透射、foliage 和具有有效薄壁 SSS 的材质保持已有翻译；
emitter 继续消费原始表面颜色，因此发光颜色与功率不受补偿影响。降噪和重建 albedo 从
当前开关对应的同一闭包导出，不能只调整照明而保留另一套 guide。

开关复用 `primePush.path.x` bit 29（`0x20000000`），位 30..31 继续保留零，不改变 push
大小、sample index、路径记录或 descriptor。设置修改沿用材质配置的 renderer revision，
实时重建据此失效历史；离线任务使用启动时捕获的同一材质快照。相同值不重复失效历史。

补偿门禁验证固定指数、逐通道单调性与通道独立性、黑白端点、关闭后的源颜色、未参与
拓扑与 emitter 不变，以及照明/guide 一致性。配置覆盖旧文件默认开启、显式关闭持久化、
非法值处理和历史失效。幂函数的 GPU 成本用实际消费者测量，不由 SPIR-V 指令数推算。

## 原版 PBR 预设

`vanillaPbrPresets` 默认开启。分类仅对 `minecraft` namespace 生效，并在捕获阶段以
`BlockState + sprite id` 逐面执行；混合纹理方块保持 DEFAULT。固定 class ABI 为：

| ID | class | roughness | Fresnel |
| ---: | --- | ---: | --- |
| 0 | DEFAULT | 全局值 | dielectric 0.04 |
| 1 | ROUGH_STONE | 0.82 | dielectric 0.04 |
| 2 | POLISHED_STONE | 0.48 | dielectric 0.04 |
| 3 | EARTH | 0.96 | dielectric 0.04 |
| 4 | WOOD | 0.72 | dielectric 0.04 |
| 5 | FIBER | 0.98 | dielectric 0.04 |
| 6 | CERAMIC | 0.62 | dielectric 0.04 |
| 7 | GLAZED_CERAMIC | 0.28 | dielectric 0.04 |
| 8 | ORGANIC | 0.90 | dielectric 0.04 |
| 9 | IRON | 0.38 | named iron |
| 10 | GOLD | 0.30 | named gold |
| 11 | COPPER | 0.34 | named copper |
| 12 | AGED_COPPER | 0.78 | dielectric 0.04 |

金属建筑块使用 registry-id allowlist；ores、rails、redstone、机器、容器、书架、活塞、灯具
及其他混合纹理不猜测。逐 sprite `_s` 永远覆盖预设。运行时开关使用 `primePush.path.x` bit
27；切换只推进 renderer revision 并清除累积历史，不重建 mesh 或 atlas。

## 命中 ABI 与表面关系

当前 `PrimitiveRecord`、`TracePayload`、`SurfaceInteraction` 分别为 32、112、112 字节，唯一
尺寸来源是 `shaders/abi.json` 及其生成常量。原 raw LabPBR 字段现分别承载当前 f32 roughness、canonical `opticalControl` 与
`adjacentInterfaceControl`，offset 不变。

动态几何不能作为 light-tree emitter，因此命中时复用 emitter/texture-LOD 两个槽逐位保存
两项硬件 f32 重心坐标，并在 `motionZFlags` 的 31-bit identity lane 保存全局 triangle id；
上一帧位置和几何法线从既有 f32 顶点缓冲重建。静态命中仍保留原 emitter 与 texture LOD
语义；任何布局迁移必须先更新生成 ABI 和行为门禁。动态
cluster 中的 instanced voxel BLAS 没有对应的上一帧三角形流，因此不发布 base BLAS 的运动地址，
而是明确回退为零物体运动，避免用不相关的局部 triangle id 越界读取。

相邻界面控制字保存 adjacent material low byte、adjacent Fresnel byte、VALID 和
MICRO_GAP_ELIGIBLE。closest-hit 使用与当前面相同的 composer 求值 reference UV，relation
本身不携带源格式标记。boundary/overlay word 0 的 8..22 位只允许 material-only recipe；
front-face-only、raster-composite 等几何位必须清零。

空气微缝只有在开关开启、relation eligible、两侧都是 solid dielectric glass、两侧都不是
water/thin/decorative、Fresnel code 相同且颜色满足既有兼容条件时生效。它用现有薄壁能力表达
分辨率以下的切割反射，不增加几何、descriptor、闭包或额外光线迭代。关闭后退化为规范单
边界，而不会恢复重叠 authored faces。

## 闭包操作与测度

Prime 不保存通用 compiled-closure 对象。每个 family 直接构造窄紧凑 state；traits、evaluate、
sample 与 albedo 由相互独立的 operation dispatcher 暴露，调用方只导入所需操作。
`PrimeClosureTraits { measureMask, eventMask }` 的 measure 只有：

- `PRIME_MEASURE_SOLID_ANGLE`：可由有限立体角 PDF 求值并参与 NEE；
- `PRIME_MEASURE_DISCRETE`：离散镜面事件。

因此 mixed opaque dielectric 可同时支持 diffuse solid-angle 与 discrete specular；透射闭包
依据实际 roughness、相对 IOR 和 index matching 成为 discrete-only 或 solid-angle-only。
NEE 查询 SOLID_ANGLE 支持，guide/deterministic 分支只判断是否 discrete-only；MIS
和前一路径状态只读取实际 sample 的 DISCRETE 事件。紧凑模块的 delta 标记在 Prime 适配边界
映射为 discrete，仓库内不存在第二套事件实现。

## 验证门禁

改动材质链时至少验证 primitive 三种模式的穷举往返、当前测试编码往返、composer 的 preset 与
LabPBR 覆盖、玻璃 alpha 阈值与空气微缝矩阵、overlay 两层独立 preset、闭包 measure/sample
一致性、dummy image 不可观察性及生成 ABI。最终还需运行 shader include、
`verifyRoboCuteReference`、production/test shader 编译、Java 测试和 Vulkan shader property tests。
当前 ABI 验收读取生成合同中的 32/112/112，不在本文复制另一套可漂移 offset 表。
