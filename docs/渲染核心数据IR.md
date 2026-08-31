# 渲染核心数据 IR

本文是 Prime 常用渲染数据的规范来源。它把既有材质 IR 扩展到场景、帧、传输、重建、显示和
外部 SDK 边界，规定数据的语义、坐标、单位、最低信息要求、所有者和允许的转换。物理
`VkFormat`、buffer stride、descriptor 编号和 native wire layout 只有在本文明确冻结时才属于
核心契约；否则它们是可替换编码，不得反向定义语义。

本文先于实现和测试。阶段 0 只定稿规范与迁移映射，不改变生产 Shader、Java API、GPU ABI 或
画面行为。后续测试必须证明本文，而不是把当前实现的偶然布局固化成新规范。

文中的“必须”“不得”“应”分别表示强制条件、禁止条件和默认选择。偏离“应”需要在数据合同中
记录原因、成本和验证方式。

## 1. 目标与边界

核心 IR 的目标是：

- 同一事实只拥有一个规范语义，不因 NRD、FSR、DLSS RR、Streamline 或 debug view 改名；
- 语义、数值编码、资源绑定和生命周期分别表达，允许共用编码但禁止混淆语义；
- 离散、可比较和不参与数值计算的事实优先保持整数、枚举、bit set 或稳定 ID；
- 连续值只保存完成消费行为所需的信息，任何有损编码都有可审计误差合同；
- 以 NVIDIA 实时主路径为核心坐标与重建规范，使 RR 与 Streamline 尽量直接消费；
- NRD、FSR、noisy 和其他兼容路径在 adapter 承担必要差异，不能定义全局规范；
- 静态且与帧、路径无关的正确性转换尽量在 CPU 捕获/翻译或离线资源构建阶段完成；
- 通过 schema、类型、生成 accessor、资源计划和构建门禁约束连接，而不是靠通道记忆。

核心 IR 不要求所有数据使用同一物理精度，不要求复制所有图像，也不把 NVIDIA SDK 的 opaque
handle、私有结构体或临时 wire layout 引入 renderer core。OpenPBR 数学、可见性拓扑和随机事件
映射仍由各自契约维护。

## 2. 四个正交维度

每个持久字段、跨阶段字段或图像必须同时声明以下四个维度。

| 维度 | 回答的问题 | 示例 |
| --- | --- | --- |
| semantic | 它代表什么、在哪个空间、单位和有效域是什么 | `VisibleMotionUv`、`LinearViewZ` |
| encoding | 如何存储、分量如何排列、误差是多少 | `RG32F`、`UQ0.16×2`、`uint24` |
| binding | 哪个 set/binding、buffer offset 或 alias view 访问它 | set 1 binding 13 |
| lifetime | 谁创建、何时有效、最后消费者和退休条件 | frame candidate 到 accepted history |

相同 encoding 可以服务多个 semantic，例如两张 `RGBA16F` 图；它们仍是不同类型。相同 semantic
可以在边界使用不同 encoding，例如核心 `VisibleMotionUv` 在 GPU 内为 normalized UV，交给
NVIDIA 时通过 scale 解释为像素运动。未经声明不得把格式相同视为可互换。

合同还必须给出：生产者、消费者、extent 来源、采样/加载方式、无效值、允许的 alias、转换
owner、精度等级和验证项。缺少其中任何一项的数据不能新增到 renderer core。

## 3. IR 分层与依赖

数据只能沿以下方向进入更靠后的层；反馈历史通过显式 accepted-state 输入下一帧，不能形成隐藏
反向依赖。

```text
Source IR
  → Scene IR
  → Frame IR
  → Transport IR
  → Reconstruction IR
  → Presentation IR
                    ↘ Interop encoding
```

### 3.1 Source IR

保存 Minecraft、资源包、LabPBR、外部动态纹理和配置的经过验证的输入事实。它允许保留源编码、
源命名和借用像素，但只能由资源 generation 持有，不能进入生产 Shader、场景记录或长期路径
状态。源对象的合法性、尺寸、枚举、颜色编码和所有权在此边界验证。

### 3.2 Scene IR

保存与相机帧无关的规范场景事实：稳定身份、拓扑、几何、材质配方、纹理 catalog、介质、灯光、
动画计划和外部动态资源声明。Minecraft atlas 坐标、LabPBR 原始 code、BlockState 对象和来源
字符串不得越过本层入口。

### 3.3 Frame IR

保存一次候选帧的规范相机、extent、jitter、时间、revision、动态实例和资源选择。它只引用
Scene IR 的稳定 ID/revision，不引用 native feature handle。candidate 只有在提交被 host 接受后
才能推进 previous-frame history。

### 3.4 Transport IR

保存求交、BSDF、介质、光源选择、路径状态、队列和原始重建信号。它可按 phase 使用不同物理
record，但语义字段必须来自统一合同。phase alias 只能在资源计划证明最后消费者完成后发生。

### 3.5 Reconstruction IR

保存可见表面、虚拟重建表面、motion、depth、normal、roughness、albedo、radiance、exposure
和有效性。核心形态以 NVIDIA 的图像方向和运动语义为主；RR/Streamline 只做 SDK 必需的轻量
封送，NRD/FSR/noisy adapter 负责其差异。

### 3.6 Presentation IR

保存 scene-referred linear Rec.2020 到显示输出的曝光、tone/gamut mapping、display encoding、
UI 合成和 swapchain 事实。encoded sRGB、scRGB 或设备 HDR code 只存在于本层或外部边界。

### 3.7 Interop encoding

Interop 不是新的语义层。它把上述规范数据封送为 NRD、FSR、NGX、Streamline、Vulkan 和宿主
需要的 layout、matrix storage、scale、handle 和同步对象。native 类型不得渗回 core API。

## 4. 约束层级

约束按以下优先级实现：

1. 不可构造的类型、枚举、稳定 ID、所有权对象和状态机；
2. 单一 schema 生成的 Java/Slang 常量、record accessor、semantic image view 和 artifact 合同；
3. build-time ABI、依赖闭包、binding、format、alias 和资源计划检查；
4. 边界 validation 与可执行行为/数值测试；
5. 只有无法结构化表达时才使用命名和注释。

不得向通用 API 暴露 `float4 data`、`image0`、`metadata.w`、`normalPacked` 等需要调用者记忆语义的
接口。过渡期物理布局仍可复用 lane，但只能由具名 accessor 访问，例如
`loadPathMediumId`、`storeVisibleMotionUv`；生产调用方不得直接解释 offset 或 bit。

schema 必须是数据叶。生成的某个 semantic accessor 只依赖自身所需的 ABI/常量，不得通过一个
`renderer_ir` umbrella 扩大无关 Shader entry 的编译闭包。

阶段 1 的机器 schema 必须至少能表达以下模型；实际 JSON/YAML 语法可以调整，但字段不得依靠
自由文本补全：

```text
semantic:
  id, valueKind, components, domain, space, unit, colorimetry, validity
encoding:
  scalar/format, channelMap, encode/decode, exactCodes, errorContract
binding:
  resourceKind, extentSource, usage, access, sampling, descriptor/offset
lifetime:
  owner, generation, firstWrite, consumers, lastRead, aliasGroup, retire
conversion:
  producer, sourceSemantic, owner, targetSemantic, backendBoundary
verification:
  artifactChecks, behaviorOracle, numeric/statistical/imageThreshold, benchmark
```

不适用项必须显式为 `none`，例如 data texture 的 `colorimetry=none`；不能省略后由调用者猜测。
生成 Java 名称使用 semantic 名称与单位，例如 `VisibleMotionUv`、`LinearViewZ`；生成 Slang API
使用 `primeLoadVisibleMotionUv` 等窄 accessor。物理 binding 名只出现在生成层。

## 5. 信息与精度合同

精度由“消费行为至少需要什么信息”决定，而不是由统一的 f32/FP16 偏好决定。当前 f32 可以是
迁移期间的保守基线，但不是永久格式要求。

### 5.1 信息等级

| 等级 | 典型数据 | 最低要求 |
| --- | --- | --- |
| exact identity | TextureId、triangle ID、medium ID、material class、event、valid bit | 逐位保持，无碰撞、无浮点比较 |
| exact topology | source/target identity、介质入退栈、front/back、surface relation | 不得因量化或 epsilon 改变离散结果 |
| source-faithful | UQ UV、源 RG8 tangent normal、authored byte code | 至少无损表达源信息，转换规则确定 |
| bounded continuous | roughness、IOR、extinction、方向、jitter、motion | 误差上限及对消费行为的传播已审计 |
| accumulated transport | position、throughput、radiance、PDF、光学厚度 | 覆盖范围、累计误差、NaN/Inf 和分布影响 |
| presentation | display color、exposure、UI alpha | 以端到端图像/显示误差验收 |

整数/枚举能精确表达的事实不得提前转换为 float。连续参数与身份必须分开；例如“同一介质”由
`MediumId` 判断，`extinction` 只参与 Beer-Lambert 计算，不能通过量化参数加 epsilon 推断身份。

### 5.2 有损编码准入

任何新的或变化的有损 encoding 必须在 schema 中声明：

- 原始域、合法范围、特殊值和 decode 公式；
- 最大绝对、相对、角度或 ULP 误差，以及误差发生的位置；
- 最坏输入和累计次数；
- 会受影响的分支、比较、随机存活率、重投影、滤波或颜色结果；
- 可执行 oracle、统计/图像阈值和 GPU benchmark；
- 不满足时的无损或更高精度回退。

“期望仍无偏”“肉眼通常看不见”或“源格式本来较低”都不是单独的准入证明。若量化改变 RR
存活率、TIR/Fresnel 分支、历史坐标或可见性，即使数学期望可重加权，也必须验证方差、分布和
重建影响。

### 5.3 法线与方向

- BLAS 几何法线由权威顶点三角面计算；任意合法斜面必须被完整支持。
- 世界/物体空间几何法线、shading normal 和 guide normal 是不同 semantic，不得因都是单位向量
  而互换。
- 源纹理若只表达 LabPBR/切线空间 RG8 法线，可以在 Source/Scene IR 使用等价或更高信息量的
  紧凑 encoding；这不授权压缩任意世界空间法线。
- 世界空间 normal encoding 只有在覆盖轴对齐、任意斜面、掠角 Fresnel、TIR、BSDF 半球、动态
  重投影和重建稳定性的最坏角误差合同后才能收窄。否则保留 f32 xyz。
- 不参与几何、BSDF 半球或重建身份的方向可拥有用途专属的紧凑合同，不得复用成通用 normal
  pack/unpack API。

### 5.4 几何追踪不可放宽项

以下规则保护可见性拓扑，不是可用误差预算交换的普通连续精度：

- 以提交给 BLAS 的顶点和硬件重心坐标重建权威命中点；
- source/target identity 逐位精确；
- 普通路径与阴影从物理端点以 `tMin = 0` 发射，仅忽略精确语义端点；
- 不使用固定/距离相关 ray epsilon、宽泛 primitive ignore、截断身份或面积阈值删除有效几何；
- CPU BLAS 前的几何吸附是显式场景语义翻译，与 GPU 自交保护分离。

当前 f32 顶点和硬件 f32 重心是权威实现基线。未来存储优化必须证明提交给 BLAS 的最终顶点、
命中身份和上述行为不变。

## 6. 坐标、矩阵、jitter、motion 与 depth

本节冻结 renderer core 的 NVIDIA-aligned 图像规范。迁移前现有 pass 可以使用 adapter，但不得
新增第三套约定。

### 6.1 图像与 clip

- 图像 texel 原点在左上，`x` 向右、`y` 向下。
- 整数像素 `(x, y)` 表示从左上起第 `y` 行；无 jitter 的像素中心为 `(x + 0.5, y + 0.5)`。
- `ImageUv = pixel / extent`，左上为 `(0,0)`，右下为 `(1,1)`。
- top-left UV 到 Vulkan/NVIDIA core clip 的规范映射为
  `clip.x = 2u - 1`、`clip.y = 1 - 2v`。
- Vulkan depth range 为 `[0,1]`。任何使用 `2z-1` 的 OpenGL 约定必须是具名 adapter。

`PixelPosition`、`ImageUv`、`ClipPosition`、`WorldPosition` 和 `ViewPosition` 是不同类型。变量名
必须包含空间或由类型唯一确定；不得以裸 `position` 跨边界。

### 6.2 Jitter

`SampleJitterPixels` 是相对像素中心的采样位移，单位是 render pixel，`+x` 向右、`+y` 向下：

```text
currentSamplePixel = pixelCenter + sampleJitterPixels
currentSampleUv    = currentSamplePixel / renderExtent
```

`ProjectionJitterPixels` 表示把未抖动几何投影到上述采样栅格所需的相机投影偏移，规范上为
`-SampleJitterPixels`（逐分量）。两者不得共用一个裸 `jitter` 名称。SDK 若定义不同单位或符号，
只由 interop adapter 转换；核心采样和历史矩阵不改变定义。

需要 clip-space 数值时，唯一转换为：

```text
ProjectionJitterClip = (
    2 * projectionJitterPixels.x / renderWidth,
   -2 * projectionJitterPixels.y / renderHeight)
```

DLSS RR 与 Streamline `sl::Constants` 的 jitter 输入均使用 render-pixel space，因此在完成 top-left
迁移后直接提交 `ProjectionJitterPixels`，即 `(-sample.x, -sample.y)`；不得把 raw NGX DLSS-G
header 中同名 clip-space 字段的单位套到 Streamline API。FSR/其他 SDK 的符号与单位仍由各自
adapter 从这两个规范值转换。

历史矩阵不包含 temporal jitter。camera cut/reset 是显式布尔/枚举，不用异常矩阵或零 jitter
猜测。

### 6.3 Motion

核心可见运动定义为 normalized UV 中的 current-to-previous：

```text
VisibleMotionUv = previousUv - currentSampleUv
```

它包含相机和物体运动，不包含 temporal jitter。静态场景与静止相机应为零；camera cut 或没有
合法 previous 对应时由独立 `HistoryValidity` 标记无效，不能伪造为巨大/NaN motion。方向型
反射 guide 的 motion 是单独 semantic，不能冒充真实可见表面 motion。

NVIDIA interop 使用 `(renderWidth, renderHeight)` 作为 scale 把 normalized motion 解释为像素
运动。NRD 若需要 `old = new + MV` 的像素 XY 和 view-Z delta，由 NRD adapter 生成。FSR 若契约
不同，也在其 adapter 处理。

### 6.4 矩阵

核心数学使用列向量，JOML/Slang 的规范含义是 `clip = worldToClip * world`。内存中的核心矩阵
为 column-major。矩阵名称必须给出方向，例如 `currentWorldToClip`、`previousClipToWorld`；
不得用 `viewProjection` 让乘法方向依赖调用者记忆。

Streamline 当前需要的 row-major serialization 只在 CPU/native interop 边界执行。它是固定大小
封送，不是让全 renderer 改用 native storage 的理由。所有提交给重建 SDK 的 current/previous
矩阵都不含 temporal jitter。

### 6.5 Depth

Prime 不强制一种 depth 数值服务所有消费者，而是区分语义：

- `LinearViewZ`：从相机沿 forward 的正线性距离，天空/无表面由显式 validity 表示；
- `ReversedInfiniteDeviceDepth`：Vulkan `[0,1]` reversed-Z 投影结果；
- `HitDistance`：沿当前射线的物理参数距离；
- `OpticalPathLength`：用于吸收的实际介质段长。

这些值不得互换。RR 优先直接消费 `LinearViewZ`；Streamline 需要的 device depth 应由最近的
producer 直接写出或在同一 prepare 中派生，不应通过独立全屏翻转 pass。NRD view-Z delta 由
NRD adapter 计算。无效值优先使用单独 mask；若外部 SDK 强制 sentinel，sentinel 只存在于
interop encoding。

### 6.6 世界与局部空间

CPU 可用 double 保存绝对世界位置。GPU 当前以 camera-relative f32 位置和局部 instance 变换
保持可用精度；这是保守基线。任何进一步收窄必须按最大 cluster extent、动态位移、三角形尺度、
重建投影和介质段长给出误差合同。空间原点/rebase revision 是显式帧事实。

## 7. 颜色与纹理

### 7.1 GPU 工作色彩

所有 scene-referred RGB radiance、reflectance/base color、emission、灯光颜色、天空 RGB
资产和重建颜色在 GPU 核心中统一为 D65、线性 Rec.2020。该规定是滤波、插值、混合和光传输的
正确性边界，不是可用运行时省事替代的性能偏好。

tint 是颜色调制操作数而不是普通 scene-referred RGB。其连续 RGBA sample 必须携带明确的
modulation-domain tag；material adapter 负责执行与源语义等价的色域转换，输出立即回到线性
Rec.2020。禁止把已转为 Rec.2020 的 base 和 tint 直接逐通道相乘，也禁止为了维持工作空间规范
而省略这项不可交换的转换。

encoded sRGB 只允许存在于 Source IR 和 SDR presentation/interop 边界。在 encoded sRGB 上做
线性过滤、mip、颜色混合或光传输均不合法。alpha/coverage 是非颜色语义，不执行 EOTF 或色域
转换；颜色和 coverage 即使共用 RGBA encoding，也必须有独立 accessor。

光谱 atmosphere LUT、深度、normal、optical code、motion 和 material 数据不是 RGB，不得套用
Rec.2020 转换。

### 7.2 资源翻译

静态源纹理在资源捕获/页面构建阶段完成：

1. 验证源颜色编码和 alpha 语义；
2. 对 RGB 执行源 EOTF；
3. 从源 primaries 转为线性 Rec.2020 D65；
4. 在线性 Rec.2020 中生成 gutter、mip、缩放和动画所需派生；
5. 以满足颜色误差合同的 page encoding 上传。

阶段 2 已把 bounded base color 和连续 tint field 锁定为 `RGBA16F`；这不外推到 radiance、
emission、starmap 或其他可能超白/为负/累计的 scene color。后者的最终物理格式仍必须分别覆盖
暗部、动态范围、mip 累计、过滤和消费误差。

顶点/实例 tint 在捕获边界完成 EOTF，作为具名 `LinearTintModulation` 的 `RGBA16F` 连续 field
与 albedo binding 关联；四顶点、biome blend、资源包 colormap 和 alpha 均不得平均成单个 RGB8
身份。每 hit 不再解码 encoded sRGB，但 material adapter 仍必须执行 tint modulation 所需的显式
色域转换。starmap 等普通静态 RGB 资产应离线或加载时转为 Rec.2020，生产采样不得每次执行
sRGB EOTF。

源资产缺少 primaries、white point 或 transfer metadata 时不得在生产 Shader 中猜测。资源必须
通过经审查的资产元数据明确其 colorimetry，或在构建/加载边界清晰拒绝；该决定和源 hash 一同
进入 artifact 合同。

### 7.3 纹理语义

`TextureId`、sprite-local UV、完整 catalog、按实际矩形分页、mip-safe gutter、generation
prepare/publish 和禁止可见性驱逐沿用[纹理翻译架构](纹理翻译架构.md)。目标 catalog 至少区分：

- `BaseColorLinearRec2020` 与 `Coverage`；
- `TangentNormalSource`、法线分布粗糙度和 AO；
- 连续 roughness/Fresnel/emission 参数；
- 离散 Fresnel/material/subsurface/porosity code；
- height/OMM/emission distribution 等 CPU/GPU 派生。

离散 code 在语义上是整数/枚举。若物理上暂存于 UNORM，generated accessor 必须对每个合法 code
精确往返，过滤和动画不得跨类别。连续通道和离散通道可以共享 image storage，但不能共享采样
规则或 semantic view。

动画 slot 同步驱动同一逻辑纹理的全部通道。CPU 只翻译实际变化 rect，并在线性 Rec.2020 中
更新颜色 mip；不得上传完整 sprite sheet 或回退到 encoded-domain 插值。

## 8. Scene 与材质 IR

### 8.1 身份和几何

Scene IR 使用稳定、非零、具名 ID 表达 texture、section、primitive、surface relation、medium、
material recipe、emitter 和动态 instance。ID 的 bit width 是 encoding；不复用、generation 和
有效范围属于 semantic/lifetime。

资源 catalog identity 与按场景生成的 identity 分域：`TextureId`、`MediumId` 和 `MaterialId`
使用 exact u16；triangle 与 emitter 最坏可达到同一数量级，使用 exact u32。
Vulkan instance custom index 等 API carrier 可以有更窄的硬件上限，但 core semantic 仍由具名类型
和 adapter 的范围验证约束，不能把硬件 bit field 当成通用身份。

轴对齐事实、front/back、thin/solid、positive-only、overlay/bilateral/boundary 和介质端点是
离散语义。不要把它们从 normal 符号、颜色、roughness 或浮点相等关系重新推断。合法退化输入
在 CPU 翻译边界明确拒绝或删除；有效斜面不能因 Minecraft 中少见而降精度。

### 8.2 材质

[统一材质 IR 与闭包](统一材质IR与闭包.md)是 Scene/Transport IR 的材质子规范：

```text
validated source facts
  → MaterialRecipe + exact semantic controls
  → canonical material parameters
  → PrimeMaterialSample
  → compact OpenPBR topology/state
```

`BuiltinMaterialClass`、family、medium、thin-walled、coverage mode、availability 和 optical category
是枚举/bit。base color 和 emission 是 `LinearRec2020Color`；roughness、IOR、extinction 等是连续
参数，各自拥有域和精度合同。物理 control word 可以复用现有 ABI，但只有 generated accessor
解释 bit。

Scene IR 的目标布局以全局材质耦合查表为默认：一个 u16 `MaterialId` 关联 `TextureId`、
`MediumId`、recipe、source codes、channel availability、coverage/emission/animation facts 和规范
texture-record references。triangle 只保存 UV、relation、tint-field addressing 等确实随几何变化的
数据。surface relation 和 `EmitterId u32` 复用同一 binding，不复制整份 secondary material。

GPU 物理 relation table 是按 cluster 定址的 tail-only 存储，不为无 relation 的 primitive 分配
header word。table-backed 普通静态 primitive 可复用已经由 `MaterialId` 取代的旧 TextureId payload
保存受 24-bit 上限约束的 relation word offset+1；emitter 通过具名 `LightEmitter.relationOffset`
保存同一 offset，dynamic/baked 不参与。该局部 offset 不是 `SurfaceRelationId` 或 `EmitterId`，不得
跨 cluster 保存或被解释为缩窄全局身份。

table-backed GPU primitive 的 immutable recipe/builtin 只存在于 material core；inline control 仅保留
tangent handedness/front-face 等 geometry-varying 事实。hit resolver 返回显式 recipe override 与
可复用的单个 core word，不通过改写 primitive 编码传语义。CPU/replay relation 保留可独立审计的
完整记录；GPU boundary 为 4 words，overlay/bilateral secondary 为 8 words，后者不再复制可由
`MaterialId` 恢复的 flags/texture/recipe word。

`TextureId` 是 `MaterialId` 去重键的首要组成；只有 medium、recipe、coverage 和其他会改变行为的
离散事实才扩展该 key。同一 `TextureId` 不能因为 section 不同而产生副本。常规 terrain 的主要
变化量收敛为 exact orientation、命中导出的 world position 和连续 tint；世界位置本身不重复写入
triangle record。不能由这些量推出的少数例外必须成为具名字段或 exact availability 控制的
companion data，不得反过来要求所有 triangle 携带完整材质状态。

该表是 renderer-generation 全局 dense table，不是 section/cluster-local palette。`TextureId` 是资源
翻译和去重的关键键，`MaterialId` 是 GPU 热路径的一跳寻址入口。material table 使用固定 schema，
但不规定每个阶段必须整份加载一个 AoS `MaterialRecord`：generated accessor 同时提供窄字段访问和
需要多个相邻事实时的合并加载能力，物理布局可按测量选择 AoS、SoA 或混合形式。wavefront 阶段只
在消费点加载所需字段，不把完整材质状态写入 path state 或延长到后续阶段。只有 exact availability
证明不会访问的冷数据允许一次 companion lookup。禁止变长 record、hash probing、链表和多级指针
追逐；少量全局 table entry 重复优于在数千万 triangle 上增加不规则 load。

纹理、material record、OMM block/pattern、emission distribution 和其他有稳定内容键的不可变资源
在全局 generation 内唯一化。普通随机 terrain BLAS 由其 cluster 独占，不为几乎不存在的整 BLAS
重复支付全局比较和索引成本；只有纹理体素或模板 geometry 等已有稳定复用键的 BLAS 可共享。

derived roughness、IOR 等在消费寄存器中从权威 u8 source code/table 展开为 f32；f32 运算精度不
构成把派生值逐 triangle 持久化的理由。若缓存有性能收益，缓存粒度也是 material binding/table
entry，而不是 triangle。该表耦合必须进入 schema 和生成 accessor，不能依赖调用方记忆字段关联。

### 8.3 介质

介质实例由 `MediumId`、拓扑 role 和参数引用组成。相同身份的边界以 ID/关系匹配；extinction、
IOR、phase 参数只参与数值计算。参数表或路径内联是后续可选 encoding，不得再次用 FP16
归一值加 epsilon 代替身份。

medium stack 的 push/pop、嵌套顺序和边界绕数必须逐位确定。参数收窄必须验证 Beer-Lambert
累计、临界角/TIR、Fresnel、长距离和跨 dispatch 稳定性。

### 8.4 灯光

Emitter identity、light class、two-sided、sampling strategy 和 leaf/tree role 是离散语义；
radiance/power/cone bound 是连续参数。`SectionRecord.lightAddress` 等复用 lane 的现状必须迁移为
tagged view，不能由调用方根据 cluster 类型猜测。灯光树保守 bounds 可以使用用途专属紧凑方向，
但不能被 shading/guide normal API 消费。

### 8.5 固定表、随机源和派生数据

BSDF energy、transmission、atmosphere、sun-shadow、STBN 和其他固定资源也必须有合同。数学表声明
参数域、插值方式、生成版本、误差与边界行为；随机源声明维度、寻址、周期和随机映射身份；
spectral LUT 不得因为最终输出 RGB 而改标为颜色纹理。生成资源以 artifact hash/版本保护同步，
但 hash 不能替代数值与统计行为测试。

OMM、emission distribution、height displacement 和 texture mip 是 Scene IR 的派生数据，必须记录
source generation、生成 owner 和失效条件。它们不能各自重新读取/解释源格式，也不能在 source
已退休后保留无声明借用。

## 9. Frame、Transport 与 Wavefront IR

### 9.1 Frame

Frame IR 至少包含 render/display extent、top-left camera matrices、current/previous transform、
sample/projection jitter、frame/sample index、time、world-origin revision、scene/material/texture/
lighting revision、reconstruction backend、history validity/reset 和动态实例 snapshot。

配置在 `beginFrame` 形成不可变 snapshot。后端 feature availability 与 native handle 属于 runtime/
interop，不写入通用相机或场景记录。

### 9.2 路径与表面

逻辑路径状态按语义拆分为：

- ray origin/direction、cone 与精确 source identity；
- throughput、radiance accumulator、PDF/MIS 和 bounce/event；
- medium stack/identity/parameters；
- RNG address；
- transparent branch/guide state；
- phase-local request/result。

逻辑表面状态区分 geometric normal、shading normal、position、UV/texture LOD、material sample、
front/back、source primitive、emitter、relation 和 dynamic previous position。字段是否持久跨
dispatch由 phase liveness 决定，不由一个最大结构体接口决定。

当前 `transportMetadata`、`throughput.w`、`metadata.w`、`flagsEmitter` 和多个 image alpha/w lane
的复用必须由 phase-specific semantic view 包裹。新代码不得直接读写裸 lane。

### 9.3 Queue 与 alias

queue identity、capacity、entry type、producer、consumer 和 indirect command 是资源计划的一部分。
不同 phase 的存储可 alias，但必须满足：

- extent/size/alignment/usage 兼容；
- 前一 semantic 的最后读已完成并有明确 barrier；
- 新 semantic 写入前旧内容不可再观察；
- backend switch、resize、reload、cancel 和 abandon 不读取旧 generation；
- debug/diagnostic view 不能延长生产 lifetime 或改变 alias plan。

`RawWavefrontFrame` 目标上拆成消费者所需的窄 typed views；是否共用底层 image 由资源计划决定，
不额外复制全尺寸图像来获得类型安全。

## 10. Reconstruction 与 NVIDIA 主路径

### 10.1 规范信号

Reconstruction IR 至少区分：

- stable/noisy `LinearRec2020Radiance`；
- diffuse/specular 或 transparent branch 的重建分类；
- `VisibleSurface` 与 transmission/reflection `VirtualSurface`；
- `LinearViewZ`、`VisibleMotionUv`、virtual motion、world normal、roughness；
- diffuse/specular albedo、hit distance、material class 与显式 validity；
- exposure、reset/history validity 和 camera constants。

“同一个位置/normal”不能同时表达真实覆盖表面和透明后方/反射虚拟表面。语义相同的编码可共用，
但身份和 history owner 必须分开。

### 10.2 后端边界

| 后端 | 对核心 IR 的关系 | 转换 owner |
| --- | --- | --- |
| DLSS RR | 主路径；直接消费 top-left、linear view-Z、normalized current-to-previous motion 与 linear Rec.2020 | RR prepare 只做 SDK packing/modulation |
| Streamline/DLSS FG | 主路径；直接消费 top-left color/depth/motion、无 jitter 矩阵和 HUD-less color | producer/FG interop，不再做全屏 Y flip |
| NRD | 兼容重建；需要其 view-Z/MV、SH、signal packing | NRD prepare adapter |
| FSR | 兼容放大；消费真实可见表面 guide | FSR adapter 或与 NRD prepare 融合 |
| noisy/direct | 不重建或最小显示 | output/display adapter |

“直接消费”不等于复制 SDK 私有结构体到核心。句柄、feature flags、native matrix storage、descriptor
和 SDK sentinel 仍由 interop 独占。

### 10.3 当前迁移基线

当前相机 raygen 使用 row 0→NDC `y=-1`，RR/NRD 通过 Y-flipped projection 补偿，Streamline 的
可见 depth/motion 已与 backend virtual guide 分离，但仍由全屏 pass 转成 top-left。迁移目标是
核心 producer 直接采用本节 top-left 规范，RR 与 Streamline 删除补偿；NRD/FSR adapter 显式承担
历史 owner 而缺少跨 adapter oracle。

## 11. Presentation

display transform 输入是 scene-referred D65 linear Rec.2020。曝光、tone mapping、gamut mapping
和输出 transfer 按固定顺序执行；SDR encoded sRGB、HDR/scRGB 和 UI blend 是 presentation/
host 边界。UI alpha 是 coverage/compositing 语义，不进入 scene color conversion。

debug view 必须声明是在 scene-linear、display-linear 还是 encoded display 域显示。normal、depth、
motion、ID 和 mask 的可视化是显式 diagnostic transform，不得通过错误的 color view 偶然显示。

## 12. 所有权与转换 owner

| 转换 | 唯一 owner |
| --- | --- |
| Minecraft/LabPBR/source 验证与语义分类 | Source adapter |
| encoded source RGB→linear Rec.2020、mip/gutter | resource capture/page builder |
| atlas UV→sprite-local UV、表面关系、几何吸附 | scene translator |
| material recipe→canonical material parameters | material translator/composer |
| absolute world→camera-relative GPU | frame/scene upload boundary |
| sample jitter→projection jitter、camera matrices | frame camera builder |
| transport state→canonical reconstruction signals | reconstruction output boundary |
| core signals→NRD/FSR/RR/Streamline wire layout | corresponding backend adapter |
| scene-linear Rec.2020→display encoding | display/presentation boundary |

转换不得在多个 pass 各自重写公式。共享公式位于纯数据/数学叶；backend-specific 符号和 packing
只位于对应 adapter。若转换可与最近 producer 融合而不扩大编译闭包或 live range，应消除独立
全屏 pass；融合仍必须保持唯一语义 owner。

## 13. 当前字段到目标 IR 的迁移映射

下表是阶段 0 的完整迁移入口。它记录常用数据族；精确每个 offset/binding 由阶段 1 schema 导入
`shaders/abi.json` 后展开。

| 当前数据 | 目标 semantic | 当前主要问题 | 迁移 owner/阶段 |
| --- | --- | --- | --- |
| Minecraft atlas RGBA8 + sRGB companion | BaseColorLinearRec2020 + Coverage | 每 hit 色域转换、源 backing 泄漏到生产 | texture builder，阶段 2 |
| vertex/instance encoded tint | LinearTintModulation RGBA16F field | 当前面平均丢失过渡；离散 TintId 不能表达四顶点/biome 插值；混合域不显式 | capture/material adapter，阶段 2 |
| normal/optical pages | TangentNormal、AO、Roughness、OpticalCode | 连续与离散通道共采样 | texture schema/accessor，阶段 2/4 |
| TextureRecord/TextureId | CanonicalTexture + stable TextureId | albedo rect 仍指向源 atlas | texture catalog，阶段 2 |
| PrimitiveRecord 32 B | PrimitiveIdentity/Material/Texture/Emitter tagged views | offset 16 已迁移为 exact MediumId；其余 control 仍有多模式复用 | generated accessors，阶段 2 |
| SectionRecord 96 B | StaticSection/DynamicSection tagged views | `lightAddress` 语义依 cluster 猜测 | scene schema，阶段 2/4 |
| BLAS/TLAS/OMM handles 与地址 | SceneGeometryRevision + interop handles | GPU 地址易越过生命周期边界 | scene owner/Vulkan interop，阶段 1/2 |
| f32 BLAS vertices + hardware barycentrics | AuthoritativeHitGeometry | 当前正确保守基线 | 保留；任何变化按几何合同 |
| TracePayload 112 B | HitGeometry + MaterialSample + AdjacentInterface | 历史文档尺寸过期、宽状态跨阶段 | schema + liveness，阶段 1/3 |
| SurfaceInteraction 112 B | ResolvedSurface | 同一宽结构供不同 phase | phase views，阶段 2/3 |
| realtime path 144 B×2 | RealtimePathState | medium/guide/branch live range 宽 | wavefront schema/bench，阶段 3 |
| realtime area 320 B/px | phase-local surface/area/guide states | 大范围 alias 与字段过载 | liveness plan，阶段 3 |
| offline path 144 B | OfflinePathState | 文档仍写 128 B | schema/docs，阶段 0/1 |
| offline surface 108 B + stage 112 B | OfflineTraceSurface/StageState | 两个 MediumId 已进入 surface，stage stride 未增长 | schema/docs，阶段 2 第一批已完成 |
| FP16 shadow-medium recognition | MediumId + continuous parameters | 身份已改为 exact ID；连续 extinction 的 FP16 数值误差仍待审计 | transport，阶段 2 身份迁移已完成 |
| FP16 realtime etaScale | RouletteEtaScale | 分布误差未审计 | error oracle 后决定 encoding，阶段 1/2 |
| ray cone/texture LOD | RayConeWidth/TextureMipBias/TextureLod | 已用 typed f32 参数限制 binary16 push 误差；derived LOD 保持 f32 | 已完成用途专属 ABI 门禁 |
| `transportMetadata`/各 `w` lane | phase-specific typed fields | 调用方记忆语义 | generated accessors，阶段 2/3 |
| FrameCamera row0→clip -Y | TopLeftCamera | 与 NVIDIA 图像方向相反 | frame camera，阶段 2 |
| NrdCameraTransform Y flip | NrdCameraAdapter | 既承担 core 补偿又承担 NRD 差异 | NRD adapter，阶段 2 |
| RR matrices borrowed from NRD transform | RrCameraConstants | 语义 owner 错位 | RR adapter，阶段 2 |
| StreamlineInputFlipPass | FgColor/Depth/VisibleMotion | 已改读真实可见 surface；普通/RR 复用 f32 primary history，NRD 复用 f32 display history；仍承担全屏 Y flip，RR 运动透射接口明确 motion invalid | producer + FG interop，阶段 2 |
| RR/NRD/FSR raw images | typed ReconstructionViews | format 相同易错接、接口过宽 | resource schema，阶段 1/2 |
| normalized motion + backend scales | VisibleMotionUv | 名称/符号分散 | frame/reconstruction schema，阶段 1/2 |
| view-Z/depth/hit distance sentinels | typed depth + validity | 数值域和无效值混用 | backend adapters，阶段 2 |
| starmap RGBA16F | StarmapLinearRec2020 | 固定 256 MiB、每采样色域矩阵、alpha 无用 | asset build，阶段 3 |
| atmosphere spectral LUT | SpectralAtmosphereData | 易被 RGB 统一误伤 | 保留独立 spectral semantic |
| BSDF energy/LUT resources | typed mathematical tables | 格式、域和插值主要靠调用方记忆 | table artifact contract，阶段 1/4 |
| STBN/采样纹理 | SamplingRandomSource | 与颜色纹理共用 image API | sampling semantic view，阶段 1/4 |
| light/shadow records | typed emitter/tree/shadow fields | 方向和 metadata lane 过载 | light schema，阶段 4 |
| stable/noisy/display radiance images | LinearRec2020Radiance variants | semantic 主要靠接口名 | reconstruction schema，阶段 1/2 |
| auto-exposure 16 B state/histogram | ExposureHistory/MeteringHistogram | candidate/accepted history 和颜色域易混 | presentation state owner，阶段 1/3 |
| settings/push constants | FrameSettings + exact feature enums | bit、单位与 revision 分散 | frame schema，阶段 1/2 |
| history image/revision/reset | AcceptedHistory<T> + HistoryValidity | validity 常由 sentinel/资源存在猜测 | runtime/reconstruction owner，阶段 1/2 |
| HUD-less color/UI alpha | FgColor + UiCoverage | 世界颜色与合成 coverage 边界隐式 | presentation/FG interop，阶段 2 |
| display/HDR/UI intermediates | PresentationColor/UIAlpha | scene/display domain 边界不完整 | presentation schema，阶段 2/3 |
| descriptor index/GPU address/native handle | binding/interop only | 容易被误当稳定身份 | Vulkan/native owner；不得进入 core semantic |

本表中的现有 ABI 尺寸以当前 `shaders/abi.json` 为准：`PrimitiveRecord=32`、
`SectionRecord=96`、`TracePayload=112`、`SurfaceInteraction=112`、实时/离线路径记录均为 144
bytes；实时 area record 为 320 bytes，离线 surface/stage 为 108/112 bytes。目标 IR 不承诺保持
这些宽度，迁移前仍不得擅自改变。

## 14. 已锁定与有意开放的决策

阶段 0 已锁定：

- 四维数据合同与七层 IR 边界；
- exact identity/topology 与 continuous parameter 分离；
- top-left 图像、像素中心、jitter、motion、matrix 方向和 depth semantic；
- GPU scene color 与颜色纹理统一为 D65 linear Rec.2020；
- source 色彩转换和 mip/filter 前移到资源翻译；
- NVIDIA RR/Streamline 为核心重建形态，兼容后端承担 adapter；
- 单一转换 owner、typed semantic views、schema 生成与 phase alias 规则；
- CPU 几何语义翻译与 GPU 自交精度的边界。

阶段 2 已追加锁定：

- renderer resource catalog 的 `TextureId`/`MediumId` 为 exact `u16`；当前 `u32` 只可作为高位为零
  的迁移 carrier；
- `MaterialId` 为 exact `u16`；triangle/emitter 是按场景程序化生成的身份，最坏可达到
  同一数量级，均保持 exact `u32`；
- bounded base color 使用 linear Rec.2020 `RGBA16F`，连续 tint field 使用带 modulation-domain tag
  的 `RGBA16F`，并保留四顶点/biome 插值与 alpha；
- tint 与 base 的调制必须执行数学等价的显式色域转换，不能在 Rec.2020 中直接逐通道相乘。
- sprite-local UV 使用 `UQ0.16x2`，不建立任意 f32 UV 持久化分支；材质参考点保存 texture-local
  integer identity 后按需生成坐标；
- derived material 参数不以高于权威 source code 的精度逐 triangle 持久化，f32 只用于寄存器内
  filtering、组合和 BSDF 计算。

仍有意不锁定：

- normal/radiance 等其他连续数据的最终 VkFormat；
- 连续 tint field 使用 buffer、采样页或等价 operator field，以及 conversion 的具体调度方式；
- 哪些连续字段可用 FP16、UNORM、SNORM、共享指数或压缩纹理；
- wavefront 最终 stride、SoA/AoS、queue/alias 物理布局；
- medium 参数内联还是表索引、starmap 最终压缩方式；
- backend target 的最终 alias 组合。

这些不是规范遗漏，而是由最低信息要求约束、必须经阶段 1 oracle、误差测试、显存账本和真实
NVIDIA GPU benchmark 才能选择的 encoding 决策。未经证据，保留当前较高精度基线。

## 15. 文档所有权与阶段门禁

- 本文唯一维护全 renderer 的语义、坐标、色彩、精度判据、IR 分层和转换 owner。
- [GPU 几何追踪精度契约](GPU几何追踪精度契约.md)维护求交端点、身份和可见性不可放宽项。
- [纹理翻译架构](纹理翻译架构.md)维护 catalog、分页、动画和 generation 生命周期。
- [统一材质 IR 与闭包](统一材质IR与闭包.md)维护材质配方、采样与 OpenPBR compact 状态。
- [生产 Shader 编译边界契约](生产Shader编译边界契约.md)维护源码依赖闭包；schema 不得破坏它。
- [架构与数据流](纯函数式架构.md)维护 Java 层依赖、prepare/publish 与 prepare/accept。
- [渲染数据标准化调查报告](渲染数据标准化调查报告.md)保留现状证据、成本和实施顺序，不复制规范。

阶段 0 的验收是：常用数据能从本文确定语义、空间/单位、信息等级、生产者、消费者、转换
owner、lifetime 和编码准入方式；当前冲突文档不再把全 f32、运行时 sRGB atlas 或 SDK 补偿写成
永久目标；生产代码、ABI 和测试行为保持不变。

阶段 1 已开始建立 schema/generator、artifact 合同、跨 adapter 相机 oracle、linear Rec.2020
颜色 oracle、精度/统计测试、alias/liveness 门禁、显存账本和 benchmark baseline。机器来源为
`shaders/renderer-data.json`；生成物仍是 contract skeleton，生产数据格式、descriptor 和 pass
尚未迁移。任何生产迁移必须在相应门禁之后进行。
