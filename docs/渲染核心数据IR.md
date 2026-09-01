# 渲染核心数据 IR

本文是 Prime 常用渲染数据的规范来源，只记录长期约束和已冻结的编码。未完成工作见
[TODO](TODO.md)；测量数字、旧实现和迁移过程见[阶段 2 编码精度初始预算](阶段2编码精度初始预算.md)与
[渲染数据标准化调查报告](渲染数据标准化调查报告.md)。历史记录不能反向定义当前规范。

## 1. 核心原则

- 每个持久或跨阶段数据同时声明 `semantic`、`encoding`、`binding` 和 `lifetime`。
- 语义可以共用编码，但不能因格式相同而合并；编码变更不得改变语义。
- 身份、枚举、拓扑和控制位优先保持整数或 bit set，不提前转为浮点或从连续值反推。
- 连续值只需保留消费者所需的最低信息量；任何有损编码都必须有可审计误差合同。
- GPU scene color 与颜色纹理使用 D65、线性 Rec.2020。encoded sRGB 只存在于资源输入和
  SDR 显示边界。
- NVIDIA RR/Streamline 是实时主路形态。NRD、FSR 等兼容后端在 adapter 承担差异，
  不能定义 core 的坐标或图像语义。
- 与帧和路径无关的变换尽量前移到 CPU 捕获、场景翻译或资源构建边界。
- 约束优先由类型、schema、生成 accessor、所有权和状态机表达，不依赖通道位置的人工记忆。
- 阶段编号不再构成实施门槛；规范、正确性、测试、布局和性能改造可以组合，但每项仍须独立
  声明不变量、oracle、收益假设和回退边界。

物理 `VkFormat`、stride、descriptor 编号和 native wire layout 只有在本文或专项子规范中
明确冻结时才是契约。SDK handle、GPU address 和 descriptor index 始终只是 binding/interop，
不是稳定身份。

## 2. 分层与数据合同

数据只沿以下方向流动；历史通过显式 accepted state 输入下一帧：

```text
Source → Scene → Frame → Transport → Reconstruction → Presentation
                                                     ⇘ Interop
```

- **Source**：已验证的 Minecraft、资源包、LabPBR 和配置事实；可保留源编码，不进入生产 Shader。
- **Scene**：与相机无关的稳定 ID、几何、拓扑、材质、纹理、介质、灯光和动画计划。
- **Frame**：一次候选帧的 extent、相机、jitter、revision、动态实例、功能选择和 history validity。
- **Transport**：求交、BSDF、介质、路径状态、队列、随机地址和原始重建信号。
- **Reconstruction**：可见/虚拟表面、motion、depth、normal、albedo、radiance、exposure 和有效性。
- **Presentation**：scene-referred linear Rec.2020 到 tone/gamut mapping、显示编码、UI 合成和 swapchain。
- **Interop**：封送 Vulkan/NRD/FSR/NGX/Streamline 所需的 layout、matrix storage、scale 和 handle；
  它不是新语义层。

每个合同至少声明：名称、值类型、域、空间/单位/色彩、有效性、编码与误差、生产者、消费者、
extent、访问方式、所有者、最后读取、alias、转换 owner 和验证项。不适用项显式为 `none`。
新 API 不得暴露需要调用者猜测的 `float4 data`、`image0`、`metadata.w` 或 `normalPacked`。

schema 必须是数据叶。生成的单个 semantic accessor 只引入自身所需的常量和 ABI，不能通过
umbrella module 扩大生产 Shader 编译闭包。

## 3. 信息与精度

| 等级 | 示例 | 最低要求 |
| --- | --- | --- |
| exact identity | TextureId、MaterialId、MediumId、TriangleId、event、valid bit | 逐位保持，无碰撞，不用浮点比较 |
| exact topology | source/target、front/back、thin/solid、surface relation、介质栈 | 不得因量化或 epsilon 改变离散结果 |
| source-faithful | UQ UV、源 RG8 切线法线、authored byte code | 无损表达源信息，转换唯一 |
| bounded continuous | roughness、IOR、extinction、jitter、motion、用途专属方向 | 有数值上界和消费误差分析 |
| accumulated transport | position、throughput、radiance、PDF、optical depth | 覆盖范围、累计误差、分布及 NaN/Inf |
| presentation | display color、exposure、UI alpha | 端到端图像或显示误差 |

有损编码变更必须同时给出合法域、encode/decode、最大绝对/相对/角度/ULP 误差、最坏输入、
累计次数、受影响的分支或分布、可执行 oracle、GPU benchmark 和回退格式。“通常看不见”、
“期望无偏”或“源数据原本很粗”不能单独准入。

几何求交另有不可用普通误差预算交换的约束：

- BLAS 使用 f32 顶点，命中点以提交给 BLAS 的顶点与硬件重心坐标重建；
- 源/目标身份逐位精确，普通和阴影射线从物理端点以 `tMin=0` 发射；
- 不使用固定或距离相关 epsilon、宽泛 primitive ignore、截断身份或面积阈值删除有效几何；
- CPU 在 BLAS 前执行的几何吸附是独立的场景语义翻译，不是 GPU 自交保护。

几何法线、shading normal、guide normal 和源切线法线是不同语义。源 RG8 切线法线可使用
不丢失源信息的紧凑编码；任意斜面、世界空间法线或 guide 不因此获得八面体/低精度准入。

## 4. 坐标、相机与深度

- 图像原点在左上，`x` 向右、`y` 向下；像素中心为 `(x+0.5,y+0.5)`。
- `ImageUv = pixel / extent`；`clip.x = 2u-1`，`clip.y = 1-2v`；Vulkan depth range 为 `[0,1]`。
- `SampleJitterPixels` 是相对像素中心的采样位移，`ProjectionJitterPixels = -SampleJitterPixels`。
- 核心矩阵使用列向量和 column-major，名称必须表达方向，如 `currentWorldToClip`。
- current/previous 重建矩阵不含 temporal jitter；camera cut/reset 由显式控制位表达。
- `VisibleMotionUv = previousUv - currentSampleUv`，是 normalized UV 中的 current-to-previous，不含 jitter。
- NVIDIA interop 用 render extent 把 `VisibleMotionUv` 解释为像素运动；其他符号/单位只在各自 adapter 转换。

`LinearViewZ`、`ReversedInfiniteDeviceDepth`、`HitDistance` 和 `OpticalPathLength` 是四个不可互换的语义。
无效性优先使用独立 mask/control；SDK 强制的 sentinel 只存在于 interop。CPU 可用 double 保存绝对
世界坐标；GPU 使用 camera-relative f32 与显式 rebase revision。

## 5. 颜色、纹理与 tint

颜色纹理在资源翻译边界一次完成源 EOTF 和源 primaries → D65 线性 Rec.2020，随后在
规范空间生成 gutter、mip、缩放与动画更新。生产 Shader 不在 encoded sRGB 上过滤，不按 hit
重复 EOTF/色域转换。缺少 colorimetry 的资源必须由经审查的资产规则确定，否则拒绝。

- base color 页使用线性 Rec.2020 `RGBA16F`。RGB 是 bounded reflectance；A 保存精确 `0..255`
  coverage code，读取后除以 255。mip 从高精度源独立生成后只量化一次。
- tint 是调制操作数，不是普通 Rec.2020 scene color。`TintId:u16` 指向全局 source-linear sRGB
  `RGBA16F` sample；RGB 已做 EOTF，alpha 是归一化非颜色语义。
- material adapter 执行与 `M * (M^-1 * base * tint)` 等价的显式色域往返，并调制 coverage。
  禁止在 Rec.2020 中直接逐通道相乘。
- Vanilla biome tint 保留每 quad/block position 完成 biome blend 后的 sample；mod 四顶点差异和
  alpha 不得被平均。常量 quad 共用全局 sample，只为实际变化面分配 sparse field。
- alpha/coverage、normal、optical code、motion 和 depth 不是颜色，不执行 Rec.2020 转换。

`TextureId` 稳定指向完整 catalog，几何只保存 sprite-local UV。物理页按实际矩形分页；不建立
atlas 等大镜像、每纹理 image/descriptor，也不按可见性裁剪或驱逐合法资源。同一逻辑纹理的
base、normal、optical 和动画通道共享 generation/slot，但使用独立 semantic view 和采样规则。

## 6. Scene、材质和全局表

| 身份 | 编码 | 约束 |
| --- | --- | --- |
| TextureId | exact u16 | 按 SpriteId 单调分配，reload 不重编号或复用 |
| MediumId | exact u16 | 0 为 vacuum；连续参数独立 |
| MaterialId | exact u16 | 0 保留给 dynamic/baked inline 兼容路径 |
| TintId | exact u16 | 指向 8 B `RGBA16F` modulation sample |
| TriangleId / EmitterId | exact u32 | 最坏与三角形数量同阶 |

可全局唯一的资源在 renderer generation 内全局去重，不建立 section/cluster-local 材质、纹理、
tint、medium 或 OMM 副本。随机世界几乎不会整体重复的 terrain BLAS 保持 cluster 唯一。

`MaterialId` 是 GPU 热路径的一跳全局寻址，`TextureId` 是去重键的主要部分。只有 medium、recipe、
coverage 和其他会改变行为的离散事实才扩展 key；section 身份、world position、orientation 和
tint 不制造材质副本。表采用固定 schema 和有界一跳寻址；禁止变长记录、GPU hash probing、
指针链和无界间接寻址。

当前 material core 每项 8 B：

```text
word0 = TextureId:u16 | recipe:u16
word1 = MediumId:u16  | reserved:u16
```

消费者可单独读取 word0、word1，或在生命周期一致时合并 `Load2`。表物理上相邻不意味着
必须整项加载，也不允许将完整材质状态延长到后续 wavefront 阶段。冷 companion data 只在
exact availability 命中时条件读取。派生 roughness/IOR 在寄存器中从权威 source code/table 展开为
f32，不按 triangle 持久化高精度副本。

`PrimitiveRecord` 保持 32 B。table-backed identity 为 `TintId:u16 | MaterialId:u16`，只内联 UV、方向、
变化几何控制、emitter/relation payload 等 triangle-specific 事实；dynamic/baked ID 0 保留显式兼容编码。
GPU surface relation 使用 cluster-local tail-only 存储：boundary 3 words，overlay/bilateral 7 words。普通图元的
24-bit word offset+1 和 emitter 的具名 `relationOffset` 都在上传边界验证；CPU/replay 保留独立的
5/9-word 语义记录。

medium 匹配始终只比较 `MediumId`；extinction、IOR 和 phase 参数只参与数值计算。emitter class、
two-sided、sampling strategy 和 tree role 是离散事实；radiance/power/bound 是各自有误差合同的连续值。

跨 dispatch 的 surface scratch 保留 position、normal、color、LOD 等连续量的原始 f32 bit pattern。
`hitKind:u8 | materialControl:u8 | MediumId:u16` 共用一字，`opticalControl:25 bit` 与
`adjacentInterfaceControl:18 bit` 的高位共用一字，`AdjacentMediumId:u16` 与其低 16 位共用一字；
所有域由生产者类型/枚举和 GPU round-trip oracle 约束。共享 core 为 96 B，realtime 追加 4 B
texture LOD；不得把这项 exact packing 误写成连续量量化。

offline path 使用 128 B 固定记录。origin、direction、throughput、PDF、eta、previous-light normal
和两层 extinction 均逐位 f32；两项 `MediumId:u16` 共用一字，两个 9-bit IOR source code、2-bit
stack count、active、8-bit bounce 和 2-bit flags 共用一个 31-bit state word。consumer 通过
`state/offline/transport.slang` 的窄 accessor 读取，不重复位布局。

材质配方、OpenPBR compact 拓扑、采样与闭包测度见
[统一材质 IR 与闭包](统一材质IR与闭包.md)。

## 7. Transport、重建与显示

路径逻辑状态包含 ray/cone/source identity、throughput/radiance/PDF/MIS、medium stack、RNG address、
branch/guide 和 phase-local request/result。表面状态必须区分 geometric/shading/guide normal、位置、UV/LOD、
material sample、front/back、relation、emitter 和 previous position。哪些字段跨 dispatch 存活由 phase liveness
决定，不由一个最大结构体决定。

queue/alias 必须声明 capacity、条目类型、producer/consumer 和屏障。alias 只在尺寸/对齐/usage 兼容、
前一语义最后读已完成、新写入前旧内容不可观察时合法。resize、reload、backend switch、cancel 和
abandon 不得读取旧 generation。debug view 不得延长生产资源生命周期。

Reconstruction IR 至少区分：

- stable/noisy `LinearRec2020Radiance`，diffuse/specular/transparent branch；
- `VisibleSurface` 与 transmission/reflection `VirtualSurface`；
- `LinearViewZ`、`VisibleMotionUv`、virtual motion、world normal、roughness、albedo 和 hit distance；
- material class、exposure、reset/history/surface/motion/object-motion validity。

`ReconstructionControl` 当前为 `R8_UINT`：bits 0–1 是 material/reconstruction class，bits 2–5 依次是 surface、history、
motion 和 visible-object-motion validity。可见和虚拟表面不得借用同一位置/normal/history owner。

当前主路径 `VisibleMotionUv` 为 `RG32F`，`LinearViewZ` 为 `R32F`；禁止在没有新误差合同时
压回 FP16。transport radiance、throughput、PDF、optical depth 和 path distance 也未获得通用 FP16 准入。
`RayConeParameters` 是用途专属例外：只有 4 B push lane 使用 binary16x2，producer 必须保证最终
unclamped LOD 绝对误差 `<= 1/512 mip`，Shader 后续计算保持 f32。

RR/Streamline 尽量直接消费规范信号；NRD 负责 view-Z/MV/SH packing，FSR 负责自身运动/深度契约。
SDK 的 matrix storage、sentinel、descriptor 和 handle 不进入 core。

Presentation 从 scene-linear Rec.2020 依次执行 exposure、tone mapping、gamut mapping 和 output transfer。
SDR encoded sRGB、HDR/scRGB 和 UI blend 只存在显示/宿主边界；UI alpha 是 coverage，不参与 scene color 转换。

## 8. 所有权与转换 owner

| 转换 | 唯一 owner |
| --- | --- |
| Minecraft/LabPBR/source 验证与分类 | source adapter |
| source RGB → linear Rec.2020、mip/gutter | resource capture/page builder |
| atlas UV → sprite-local UV、surface relation、几何吸附 | scene translator |
| recipe → canonical material parameters | material translator/composer |
| absolute world → camera-relative GPU | frame/scene upload boundary |
| sample/projection jitter 与相机矩阵 | frame camera builder |
| transport state → reconstruction signals | reconstruction output boundary |
| core signal → NRD/FSR/RR/Streamline wire layout | 各 backend adapter |
| scene-linear Rec.2020 → display encoding | presentation boundary |

资源和材质 reload 使用 prepare/publish；新 generation 只在完整 catalog、GPU upload、可选 Shader reload 和
scene epoch 同时就绪后原子发布。Frame candidate 只在 host accept 后推进 previous-frame history。
转换可与最近 producer 融合以删除全屏 pass，但唯一语义 owner、编译闭包和 live range 不得扩大。

## 9. 已冻结的物理边界

| 数据 | 当前契约 |
| --- | --- |
| sprite-local UV | `UQ0.16x2` |
| texture page 坐标 | 每轴 u16；page carrier u8，语义上限 0..63 |
| base color page | linear Rec.2020 `RGBA16F`，A 为 exact u8 coverage code |
| tint sample | source-linear sRGB `RGBA16F`，8 B/项，`TintId:u16` |
| material core | 8 B/项，u16 全域保留 512 KiB |
| PrimitiveRecord | 32 B，table-backed identity 为 TintId/MaterialId |
| GPU surface relation | tail-only；boundary 3 words，overlay/bilateral 7 words |
| BLAS vertex / barycentric | f32 vertex + hardware barycentric |
| visible motion / linear view-Z | `RG32F` / `R32F` |
| reconstruction control | `R8_UINT` exact bits |
| ray-cone push | binary16x2，LOD 误差 `<=1/512 mip` |

上表未列出的 normal/radiance/transport 连续数据、wavefront stride、SoA/AoS、medium 参数表、starmap 和
backend target alias 仍属可替换编码。在新门禁通过前保留当前较高精度基线。

## 10. 变更门禁与文档边界

修改任一语义、编码、binding 或 lifetime 时，先更新契约和 oracle，再修改生产实现。至少验证：

- 生成 Java/Slang ABI、descriptor、format、stride 和非法值拒绝；
- CPU/GPU 参考值、最坏误差、边界、NaN/Inf、分支和统计分布；
- alias/liveness、generation publish/retire、resize/reload/cancel 和 backend switch；
- 生产 Shader 源码闭包、窄加载、寄存器生存期与真实 NVIDIA GPU 性能。

当前仍缺少误差或性能证据的边界如下：transport radiance、throughput、PDF、extinction、optical
depth 和 path distance 不得有损收窄；任意斜面、掠角、Fresnel/TIR 与 guide 未建立角误差合同前，
不得恢复通用方向压缩；依赖 register、occupancy 或 cache 取舍的最终布局必须由 Nsight/逐 pass
数据决定。RR+FG 透射 motion 在拥有独立 f32 visible-history owner 前保持 invalid，不以近似 motion
伪造历史有效性。

专项规范分工如下：

- [GPU 几何追踪精度契约](GPU几何追踪精度契约.md)：命中、端点、身份与可见性；
- [纹理翻译架构](纹理翻译架构.md)：catalog、分页、动画和 generation；
- [统一材质 IR 与闭包](统一材质IR与闭包.md)：配方、采样、surface relation 和 OpenPBR compact；
- [生产 Shader 编译边界](生产Shader编译边界契约.md)：entry 源码可见闭包；
- [架构与数据流](纯函数式架构.md)：Java 依赖、prepare/publish 和 prepare/accept。
