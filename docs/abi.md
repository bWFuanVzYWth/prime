# FFM ABI v12

Java 适配器与 Rust 核心作为同一构建产物配套使用。公共 ABI 为11，Minecraft 源 schema 为7，设置文件 schema 为7；版本用于边界拒绝，不承诺不同发布之间的二进制兼容。两版适配器共享同次构建的核心，JAR 和引擎不能混用。

## 唯一结构契约与生成

[`prime.h`](../crates/prime-engine/include/prime.h) 及其包含的 [`prime_mc.h`](../crates/prime-engine/include/prime_mc.h) 是唯一手写布局。它们使用64位宿主、自然对齐 C POD、固定宽度整数、IEEE754 浮点、定长数组，以及指针加 `u64 count` 的批量输入。没有 packed struct、C bitfield、union 或编译器枚举宽度。`scripts/generate-abi.py` 生成 `prime_abi` 的 `repr(C)` DTO/导出函数类型，以及 Java `PrimeAbi` 的 FFM layout、具名字段访问器和函数描述符；业务代码不手写另一套字段偏移。

生成校验独立比较 C 编译器的 `sizeof/alignof/offsetof`，Rust 编译断言检查布局和实际导出函数签名。Java 使用自然对齐的 native arena 存储。修改接口先改头文件再生成，命令见 [开发流程](../CONTRIBUTING.md)。生成器只接受当前有限的 POD 语法；新增语法必须扩充生成和独立验证，不能静默猜测。

固定输入以 `PrimeHeader { struct_size, abi_version }` 开始，两者精确匹配当前根结构。MC 根输入以 `PrimeMcIdentity` 开始，前两字段相同，另外携带 source/game version、resource generation、world epoch 和 batch。自然 padding 不传递语义，不要求清零，也不参与内容比较；显式 reserved 字段须为零。

`prime_create(11)` 返回非零 handle，普通 status=0 成功、-1 失败；`prime_last_error` 返回 UTF-8 完整长度（不含 NUL），容量允许时补 NUL。除不使用 session 的真实 Present 转发外，所有 handle 调用都在创建它的 OS 线程执行。库名仍为 `prime_engine`。

## 诊断控制与排空

`prime_diagnostics_configure(handle, flags)` 控制独立于 `PrimeSettings` 的诊断；bit0 启用诊断，bit1 启用原始采集且隐含诊断，其他位拒绝。`prime_diagnostics_frame(handle, frame_id)` 绑定后续同步调用及工作池任务的逻辑帧。`prime_diagnostics_clock(handle)` 返回当前采集 origin 相对整数纳秒，停止后保留至最终块消费；无会话返回0，错误返回 `u64::MAX`。以上调用均限 owner thread。

`prime_diagnostics_read(handle, output, capacity)` 排空紧凑 UTF-8 JSON 块，返回完整字节数（不含 NUL）；错误为 `u64::MAX`。capacity=0 可传空指针，会准备并缓存块；短缓冲只复制能容纳的前缀并补 NUL，缓存保留。capacity>长度时复制完整块并补 NUL，然后消费；输出指针仅借用到返回。停止后先消费已有缓存，再排空最终尾块；所有旧块消费完才能开启新会话。没有采集数据返回0。排空 API 自身不产生事件，避免不断录制自己的读取。

原生通常逐帧排空一次；关闭时导出已完成事件，并标记未完成/未提交 GPU 阶段，不等待未提交工作。JSON 的任务关系、字典、线程、独立时钟与完整性语义见 [诊断契约](diagnostics.md)。

## 源输入、输出与所有权

根结构、数组描述符和原始 payload 只借用到同步调用返回。Rust 边界先核验版本、结构大小、对齐、非空地址、乘法和 span 长度，然后将安全的具名视图交给 `prime_scene` / `prime_minecraft`；业务 crate 不解引用外部裸指针。调用方仍须保证分配真实可读且调用期间不可变，长度检查不能验证任意地址。

跨调用数据在返回前成为 native 所有，同步 worker 返回前 join；没有逐 section/quad/粒子 FFM，也没有跨帧借用或隐式宿主回调。Java 复用同类数组和 payload 存储，根结构只引用它们，不为合批再次拼接旧 wire stream。raw 顶点、像素和 UTF-8 仍是原始字节 span，typed batch 不是旧字节包的外壳。

旧字节协议仅保留为离线 oracle/诊断夹具。`prime_scene` 的 `legacy-fixtures` feature 默认关闭，生产 native 依赖不启用；`prime_tools` 与需要旧协议的测试依赖显式启用。Minecraft 的旧输入解析仅在单元测试编译，Java 编码器归 test fixtures。单独运行 scene 的完整协议行为测试使用 `cargo test -p prime_scene --features legacy-fixtures --locked`；默认构建保留共享的 raw payload 解码与 typed 事务。

`PrimeMcRequests` 由 native 写入调用方提供的根结构，数组归 session 所有，有效至下一次 MC plan/accept/resources/destroy。Java 必须先读完再响应，不能保留其中地址。无变化实例帧不调用 FFM；空动态快照则是有意义的清空。

## 固定控制与帧

| 根结构 | 大小 | 消费者与语义 |
| --- | --- | --- |
| `PrimeReset` | 16 B | `prime_reset`；world epoch严格增加，清理世界几何/动态状态，保留当前常驻资源代和同一CPU池 |
| `PrimeFrame` | 96 B | record/render；epoch、f64世界位置、forward/right/up、FOV、输出尺寸、sample index、实际太阳时角 |
| `PrimeSettings` | 104 B | configure；具名渲染、调度、星图、自动曝光、HDR、帧生成与帧边界光源采样方式、积分器 |
| `PrimeVulkanHost` | 56 B | attach；instance/physical device/device/queue/timeline/family/实际启用capabilities |
| `PrimeRecordTarget` | 40 B | record；活动command、目标image/view、实际提交serial |
| `PrimePrepareResources` | 24 B | prepare_resources；活动command与真实提交serial，仅准备设备/全局资源 |
| `PrimeDisplayOutput` | 24 B | 实际HDR surface启用状态、绝对峰值及系统参考白，reserved=0 |
| `PrimeHdrTarget` | 64 B | 最终呈现的活动command、UI及输出image/view、serial和尺寸；UI为sampled RGBA8、HDR输出为storage RGBA16F，均为GENERAL |

相机基须单位正交，位置、FOV和太阳时角须有限且在支持范围内。累积在相机、场景、尺寸变化或sample index=0时重置，其余样本计数由renderer维护。冻结期间帧仍须合法，但仅采用新宽高/序号，姿态、时角和光输运来自已显示快照。

`prime_reset` 是世界切换边界。借用宿主设备时，调用前提交并等待已录制工作完成；离线先通过 configure 正式解冻。成功 reset 立即撤销 CPU 世界缓存及 GPU 几何/历史，即使之后停留在标题界面也不等待下一次 plan/record 才清理。全局资源代、固定设备资产及共享工作池保留。

## 场景纹理与所有权

`prime_textures` 接受 `PrimeTextureBatch` 的 `PrimeTextureSource[]`，每项具名给出id、尺寸和RGBA8 span。id=0是白纹理，UINT32_MAX是实例继承标记，均不能上传；id=1是block atlas。普通动态纹理入口拒绝覆盖已登记的常驻资源。N 张纹理的每批完整预算为 `32 + 32*N + sum(width*height*4)` 字节，必须≤256 MiB；单纹理也是同一公式。Java将变化纹理组织为有界批次，像素直接复制到复用native storage一次，仅在该批同步提交成功后确认其成员；源捕获、native副本和GPU上传仍有各自复制及寿命。

方块atlas和全部已捕获sprite通过 `prime_mc_resources` 同一资源事务登记常驻，不能先用动态纹理入口覆盖atlas。资源代次不等于world epoch；同代增量/动画保留resource owner，新代成功替换后立即撤旧几何，后续按现有格预算补回。初始化、真实资源重载或renderer重建时准备资源，普通区块加载/卸载不决定地形纹理寿命。

`prime_retire_textures` 的 `PrimeTextureRetire` 携带epoch和u32 identity span。保留ID与常驻资源不可退休，整批验证后才撤销动态owner。当前源引用为零且owner已退休时，CPU发布输出removal；旧/冻结CPU快照仍持有不可变像素。GPU资源依最后实际读取和timeline退休，FFM返回和owner退休都不是GPU完成证明。

## 动态完整快照

`prime_dynamic` 接受64 B `PrimeDynamicBatch`，包含epoch、严格递增sequence、f64世界原点及 `PrimeMeshSpan[]`。每个48 B span给出纹理、flags、topology、count、stride、位置/颜色/UV字段偏移和原始顶点span，描述数组与payload分离。topology=3/4保留真实源布局，由Rust展开三角形。当前动态原点为相机世界位置，源顶点已完成模型变换，尚未应用视图旋转。

参数粒子使用topology=1和52 B `PrimeBillboard`，不用于实例原型。字段为中心、四元数xyzw、scale、u0/u1/v0/v1、RGBA；stride=52、position_offset=0、color_offset=48、uv_offset=32。Rust保持可归一化四元数的q*v*q⁻¹，再乘scale、加中心；角依次(1,-1)、(1,1)、(-1,1)、(-1,-1)，UV为(u1,v1)、(u1,v0)、(u0,v0)、(u0,v1)。不传原版光栅light，相邻同描述span合并。

全部span验证后原子替换；旧epoch/sequence、缺失纹理或末尾span错误均不改变已有场景。count=0清空动态几何。观察sequence与内容revision分开：native自持规范的具名标量值和authored顶点字节，逐字节证明局部内容相同即可复用Arc；C padding和指针地址不参与比较，顶点stride内的源padding仍保守地参与。仅原点变化复用局部几何并更新位置计划，失败不消费序号。

`DynamicFrame` 分别复用描述与payload arena，sealed根及其指针只借用到同步返回，下一次begin/扩容/close结束借用。Rust在无CPU读者时才重用raw工作区，发布期间Arc保持只读；动态更新不重译静态mesh表。

## 原型与实例增量

`prime_instances` 接受88 B `PrimeInstanceBatch`，分组引用原型upsert/removal、实例upsert/removal四个typed数组。每条revision等于本批sequence，同类ID非零、不重复，不可同时更新和删除。无变化不提交空批。

`PrimePrototypeSource` 为32 B，含id/revision和 `PrimeMeshSpan[]`，仅接受非空topology=3/4。统一removal为16 B。128 B `PrimeInstanceSource` 包含id/revision/prototype、f64原点、row-major 3×4仿射、texture/flags覆盖、RGBA tint及UV scale/offset；UINT32_MAX表示继承原型。矩阵/UV有限，线性部分可逆，支持镜像、非均匀缩放和剪切。位置为 `origin + transform * local`，UV为 `sourceUV*scale+offset`；颜色在编码域按 `floor(source*tint/255)` 组合，再进入插值和颜色转换。

Rust先验证最终批状态，允许同批新增原型并引用，或迁移/删除实例后移除旧原型；存活实例仍引用的原型不可移除。失败不改变顺序、引用、几何或实例。相同有效pose不更新内容版本，删除不存在身份只推进观察序号。`InstanceCapture` 仅在FFM成功后acknowledge，跳过的hook不丢脏记录；动态清空不清除独立实例，实例通过removal或world reset清理。

## MC 源批次（source version 7）

字段类型和数组分组以 `prime_mc.h` 为准。MC 262/263的类型、枚举、palette和颜色语义只由 `prime_minecraft` 解释，场景/GPU不识别它们。未知game/source version明确拒绝；原型支持范围见 [hack清单](../PROTOTYPE_HACKS.md)。

`prime_mc_resources(handle,&resources)` 接收独立资源事务：state/model/quad/child/face/fluid/sprite/image/animation-frame数组及共享coordinates/words/bytes payload。`PrimeMcRange` 是对应数组内的元素offset/count；字符串/像素引用bytes区间，不含旧标签、终止符或内嵌变长记录。REPLACE表示整代原子替换并携带atlas；同代增量不得原地改变已定义ID内容。资源生成、世界epoch和源batch分别核验。

每个源帧 `prime_mc_plan(handle,&plan,&requests)` 传相机、半径、真实来源范围、tick和事件。Java用 `prime_mc_sections` 一次传回 `PrimeMcSection[]` 及共享palette/packed words；实际tint由 `prime_mc_colors` 响应，缺失quart群系由 `prime_mc_biomes` 响应。`PrimeMcRequests.phase` 为0完成、1 section、2颜色、3颜色并首次定义、4群系。响应须精确匹配本次身份/数量，验证及worker join后发布；不能重放回调补画。

MC source数组不因C结构增加256 MiB逻辑整批上限，按checked count/element-size/isize地址范围校验；像素仍受独立512 MiB唯一存储预算。旧SourcePage字节页不再是生产ABI，不新增逐section FFM或第二次全批复制。旧场景、source版本明确拒绝。

事件保持原语义：1加载列、2卸载列、3段脏、4资源全失效、5清空旧列清单、6颜色列失效、7全部颜色缓存失效。6/7只重编译实际颜色消费者，不读取相同palette；普通dirty与颜色失效不能互相代替。Rust维护来源窗口、邻接、64段完整性和现有每帧格预算，ABI迁移不改变队列/轮转算法。

### 源字段语义

- state flags保持air、offset函数、solidRender、非MODEL、legacySolid和真实玻璃/玻璃板/树叶等类型声明。placement offset kind为0无、1XZ、2XYZ、3未知；seed kind为当前位置、下/北/南/西/东、未知。有限非负参数与flags一致，kind0/3参数为零、XZ竖向为零。Rust保持整数wrapping及f32/f64求值顺序，Java不按位置调用getOffset/getSeed猜规则。
- model kind为0未知、1直接quad、2权重子项、3实际选中multipart子项、4别名。quad给四角位置、原packed UV（U高32位、V低32位）、face/tint/layer/sprite/emission；face0..5为下/上/北/南/西/东，6无剔除。真实源层和0..15发光级由版本层解释。必要selector只在显式资源准备按真实顺序求值一次。
- face profile的U/V轴在X法向为Z/Y、Y法向为X/Z、Z法向为X/Y，格索引 `u*(v_count-1)+v`，word低位先行；0/1内置空/全面，其余ID≥2。support按direction×3+SupportType（FULL=0），未知动态支撑只置bit31。Java不做形状合并。
- section present=0明确无源，不等于空段。bits=0恰好一个palette项及零word；其余每word存 `floor(64/bits)` 项、不跨word，顺序 `y*256+z*16+x`，恰好4096项。空palette表示全局registry ID，非空为局部索引。Rust校验后自持数据。
- sprite ID为1..0x3fffffff，generic映射 `0x40000000+id`；动态ID不得进入此区间。静态mip0空payload共享atlas，动画mip0须完整sheet；尺寸/mip/索引/正时长和周期在资源准备验证。累计时长预计算，运行时查相位；同相位跳过新mip视图和辅助插值。变化相位保持原千分量化及分类材质插值规则，资源重载不因相位相同复用旧材质。
- 只有 `format=lab-pbr/1.3` 和真实辅助图声明normal/specular，缺图不伪造像素。原始RGBA进入Rust，G/B清洗、sheet、mip、height和辅助动画仍归Rust；通道见[材质契约](materials.md)。

### 颜色源批次

颜色请求给世界位置、state和slot（block tint index，-1实际fluid tint）；仅请求本轮实际几何，同位置/slot去重。`PrimeMcColorRecipe.kind` 为0未知源实际ARGB、1/2/3/4原版grass/foliage/dry foliage/water、5双高草upper属性、6红石强度、7茎年龄、8常量ARGB。已知源绑定真实类型，其余只执行一次真实colorInWorld，保留ARGB，不重复回调猜类别。

首次需要的 `PrimeMcBiomeDefinitions` 提供zoom seed、0..255排列、noise offset/scale和三张≤65536项资源色表。26.2绑定单octave PerlinSimplexNoise，26.3绑定SimplexNoise；Rust保持各版求值精度及缺项语义，未知布局拒绝。`PrimeMcBiomeRequest.mask` 按 `x | z<<2 | y<<4` 标记64项；Java只读取实际quart源，返回温湿度、水色、覆盖色和modifier。indices按请求顺序及置位低到高引用本批biome定义。

Rust执行zoom、颜色规则、halo去重、整数前缀和及原版整数除法。标准混合、原始颜色和quart源分别缓存，只发布已验证值；半径变化只撤混合结果，颜色列按quart选择2格范围及混合半径传播。世界/资源全失效清理相应缓存，冻结不隐式调用宿主补积压。

### GPU 纹理描述符（内部契约）

每个视图为64B，由四个 `uint4` 构成；shader 以 `descriptor_index*4` 寻址。它由 Rust 的规范 Texture 生成，不是 Java wire record。

| Offset | 内容 |
| --- | --- |
| 0 | 基础像素偏移、视图宽、高、行步长；行步长 bit31 为 sprite 局部采样标记 |
| 16 | 下一帧像素偏移、首个 lower-mip descriptor、lower-mip 数量、动画 blend 的 f32 bits |
| 32 | normal descriptor、specular descriptor、材料 flags、coverage/atlas-lookup descriptor；0 表示缺失引用 |
| 48 | sprite 的归一化 atlas bounds `[loU,loV,hiU,hiV]` 的 f32 bits；无 bounds 时为0 |

材料 flags 的 bit0/1 表示 normal/specular，bit2 表示 authored emission，bit3 表示 atlas material lookup。lookup 的原始 CPU texel 是 source texture identity，上传时变为稳定 GPU sprite descriptor index；值0表示该处无辅助材质，采样不得将它当颜色。普通 presence coverage 使用 R 的位0/1/2。atlas 命中先解析 sprite descriptor 和 bounds，再按 local UV 消费该 sprite 的独立辅助视图。辅助图动画已由 CPU 按分类通道规则混合，其描述符 blend 固定0；基色保留源 RGBA 插值。

共享 pixel backing 只上传与计费一次，描述符身份在当前纹理生命周期内稳定。所有材质平面及其完整 mip/动画 backing 进入既有512 MiB唯一像素预算；旧资源仍依最后消费者和 GPU 完成证明回收。光学邻接的 IOR 源引用复用已有 quad UV 记录的空闲字段，传输 GPU descriptor identity；不增加 quad 步长或新的 binding。

## 公共顶点与材质语义

各源 span 显式给出顶点数量、stride 和字段 offset；数据为恰好 `vertex_count*stride` 字节。topology=3 表示三角形、4 表示四边形；material=0 opaque、1 alpha cutout、2 stochastic alpha coverage，其他值拒绝。

Position 为 f32×3，UV 为 f32×2。局部原型和显式原始网格可输出 **stride24、position0、color12、uv16**；当前地形/流体生产通路使用上文 MC 源批次。RGBA8 按 R/G/B/A 字节顺序保存作者颜色与源 tint 的编码域组合，排除原版 AO/方向明暗和 UV2 光照。source layer 使用公共协议常量 opaque=0、cutout=1、translucent=2，不传 MC enum ordinal。显式 stride/offset 解码器也支持 BLOCK28 等合法源布局，并有行为回归测试；这不表示兼容旧 ABI。quads 在 Rust 展开为 `(0,1,2), (2,3,0)`。

## 历史路由夹具

旧 `prime_scene::protocol` byte readers及Java testFixtures仅供CPU参考/回放，其magic/op/version不是当前ABI导出。生产不再提供 `prime_submit`、SourcePage或op8/10/11字节入口。版本无关CompiledSection的owner/epoch/源顺序/完成水位仍由原生事务维护，源序号和CPU完成不等于GPU完成。

## 宿主 Vulkan 生产路径

先create，在设备私有资源创建前应用初始 `PrimeSettings`，再以 `PrimeVulkanHost` attach。源资源准备后 `prime_prepare_resources(handle,&prepare)` 在真实活动encoder录制固定LUT、大气、全局纹理和OMM资源，不构建地形/TLAS、不派发PT。提交顺序、serial与完成证明同record；不另开队列或以假serial预热。

调用方须已在实际逻辑设备启用 buffer device address、scalarBlockLayout、acceleration structure、ray query、timelineSemaphore 与所需扩展，并保证 queue family 支持 graphics+compute。仅查询物理设备支持不够。Rust 不销毁这些宿主对象，也不为 PT 调用 queue submit。

flags 的 bit0 表示已在这台逻辑设备启用 `VK_EXT_opacity_micromap` 的 `micromap` 和 synchronization2；bit1 表示实际启用 Streamline 所需的 NVX binary import、NVX image view handle、KHR push descriptor、KHR buffer device address、KHR synchronization2 及 timelineSemaphore/descriptorIndexing/BDA、synchronization2、shaderStorageImageExtendedFormats、shaderStorageImageWriteWithoutFormat 能力，其他位必须为零。宿主只在 `vkCreateDevice` 成功且实际创建集合包含扩展与特性后发布；26.2/26.3 本身要求 `VK_KHR_synchronization2`。物理支持不等于已启用，flags=0 保留原始 PT 路径，Rust 不补开借用设备的能力。OMM 和 RR 用户设置与设备能力独立；具体兼容范围见 [OMM 契约](opacity-micromaps.md)与[重建契约](reconstruction.md)。

`prime_streamline_present(queue:u64, present_info:u64)->i32` 包住一次宿主 `vkQueuePresentKHR` 调用。安装 interposer 后，标题/加载帧也使用 bootstrap 绑定的进程入口，不依赖世界或 RR owner；返回 SDK 状态与公开 Present/acquire 错误回调的合并 VkResult，设备丢失优先于其他错误。加载的 FG 插件即使关闭 FG 也可能异步呈现；晚到错误在后续调用交付，成功返回不证明物理呈现完成，也不要求同步写回 `pResults`。结构体指针及引用数组按宿主调用合同借用，不为异步路径注入栈上结果指针。未安装 interposer 的 manual RR 路径仍包住一次原 Vulkan 调用与 common hooks。这个入口不消费场景 session，不把一次 Present 等同于 GPU 完成证明；具体错误及同步回退边界见[native 桥接](../native/streamline/README.md#frame-generation-and-present)。

每帧调用 `prime_record(handle, &frame, &target)`，`PrimeRecordTarget` 具名携带 command、image、view 和 serial。command 是已开始录制、尚未结束的宿主 primary command buffer；目标为带 STORAGE 用途、GENERAL layout 的 RGBA8_UNORM 主颜色图像及其 view，尺寸必须等于 frame。serial 是将包含此 command 的实际提交完成值，同一 session 每个 serial 最多录制一次。宿主在同队列依次提交，并在所有命令完成后 signal timeline 到该 serial；Rust 自己的描述符槽与退休资源依赖此保证。

`prime_record` 同步完成输入复制与命令录制，正常返回时 GPU 可以尚未执行；没有像素返回。只在所有描述符槽仍在途时等待最旧 serial，这是 GPU 资源复用的必要完成等待，不是向 MC 上游施加工作配额或主动背压。shader 在 GPU 上处理宿主输出行方向。`prime_gpu_time(handle)` 返回最近一个已收集的完成帧 GPU 纳秒数；宿主队列不支持时间戳或尚无结果时为 0；有能力的宿主常驻三个阶段时间戳，仅在完成证明后读取，不增加 GPU 等待。它不是当前 CPU 调用耗时或窗口平均。

`prime_submission_accepted(handle,serial)` 只在包含对应世界命令的真实队列提交被接受后调用一次；匹配成功才推进相机和稳定实例的前帧身份。`encoder.execute`、CPU录制返回和`vkEndCommandBuffer`不构成接受证明。错误/重复serial和前一帧未接受时的下一次record会拒绝；此回调也不证明GPU完成。

`prime_display_output` 在已接受的帧边界发布实际HDR标定；手/HUD绘制结束后，`prime_present_hdr` 将世界快照与真实UI覆盖合成到scRGB目标。同一serial只允许一次HDR录制，HDR描述符有独立完成槽，不能凭世界pass使用过该serial就重绑尚在途的HDR描述符。原版/标题画面的HDR转换由独立的 `prime_hdr_surface_create/record/destroy` owner完成，不创建PT资源；销毁前先提交全部录制工作，再取得真实timeline完成证明。

帧生成开启时，在同一呈现提交调用 `prime_prepare_frame_generation`；HDR须先完成同serial的HDR合成。返回0表示已准备、1表示不可用或关闭、-1表示错误。参数声明实际交换链buffer数量和Vulkan格式，不能把SDR编码值标为线性HDR。首次可见depth/motion、无HUD图像和UI覆盖均为native自持资源；退用、重配和释放还须证明SDK公布的输入处理完成值，世界timeline不能替代它。

`prime_streamline_bootstrap` 在宿主创建Vulkan loader/instance前安装进程interposer；`prime_streamline_frame` 的BEGIN_FRAME、RENDER_START、RENDER_END、SUSPEND、HOST_SHUTDOWN对应逻辑帧/PCL/Reflex与真实设备边界，不能使用额外RR evaluate次数推进帧号。实际 `prime_streamline_present` 可以在宿主呈现线程运行，由桥接序列化SDK访问。详细算法、颜色与完成合同见[显示](display.md)和[重建](reconstruction.md)。

`prime_cpu_diagnostics(handle, output, capacity)` 在 owner 线程按需将最近一次 CPU 准备/录制快照格式化为 UTF-8。成功返回不含 NUL 的完整字节数；非零容量最多写入 `capacity-1` 字节并补 NUL，容量零时允许空指针查询长度；错误返回 `u64::MAX` 并设置 last error。输出指针只借用至调用返回。未发生准备/录制时明确输出 `available=false`，不使用零时间冒充已测量结果。

此查询不访问游戏对象，不等待 GPU、不回读图像。粗快照仅在诊断或显式 legacy profile 开启时更新，关闭时为 `available=false`；GPU 区间属于最近完成 serial，可能与当前 CPU 录制不同。此文本入口保留按需人工检查，不再由慢帧阈值自动调用，也不是稳定机器解析协议。Java 预留8192字节，超长或查询失败明确报告；逐帧分析使用独立的原始 JSON 排空入口。

调用 `prime_destroy` 前宿主必须提交所有已录制的 PT command；native 等待最后相关 serial 后销毁 PT 资源。若无法证明完成，返回失败并保留 session/资源以隔离风险。宿主 device、timeline、图像等必须覆盖其全部使用寿命。

## 同步诊断路径

未attach宿主的session可调用 `prime_render(handle,&frame,rgba,capacity)`，创建独立设备并同步返回恰好 `width*height*4` RGBA8，左上首像素、连续行。仅供图像和FFM行为检查，不能用于宿主session或代表生产性能。

## 边界与容量

64位宿主按可寻址字节累计唯一几何，不使用800万或带符号32位全场景上限。generic texture/dynamic/instance每批完整描述及payload预算256 MiB；动态是整帧替换，实例是原子增量，不能多批替换伪装追加。MC source arrays保持独立地址范围检查，不将generic预算误用为世界上限。

当前仍拒绝超过512 MiB唯一像素（共享Arc不重复计费）、各超过262144常驻原型/实例、超出设备image/dispatch/storage范围的输出。不有限/越域位置、未知flags/topology、长度不符和缺失纹理在发布前拒绝；失败不改已有场景。部分分配try_reserve，但不保证所有内存耗尽可恢复。GPU局部索引、AS数量、设备分配和显存仍有独立边界。

当前 Slang 局部指针下标以32位字节偏移计算。静态范围按最宽432 B记录的保守上限 `floor(2^32/432)` 划分，局部范围保持完整 quad 的两个 primitive 槽；实际选择176/240/272/432 B格式，详见[表面记录](surface-compiler.md)。动态/原型使用176 B quad，并保守使用相同局部范围上限。各范围设备基址为64位，总量可跨页；单个共享原型超限仍明确失败。实例展开统计使用u64，帧参数只传“是否有几何”，不能收窄后误判为空。

非空静态层、raw 和原型/实例在提交前必须上传引用的纹理；缺失引用明确报错。所有调用必须在创建 handle 的 OS 线程进行。`prime_destroy` 退休身份，重复释放会报错。调用方必须保证原生指针指向有效读写区域；长度校验不能验证任意地址。

## 设置结构与文件 schema

`prime_configure(handle,&settings)` 借用104 B `PrimeSettings`，header使用公共ABI v12。`light_sampling` 位于96字节偏移，0为Grid、1为Tree功率距离树、2为TreeSphere球界方向树，其他值拒绝；创建时采用当前值，之后允许在外层帧边界变更。宿主先提交并证明旧命令完成，native重建所选管线，并在下一次录制中完成灯表和目录更新后才dispatch。JAR与DLL仍须配套重建，旧DLL不接受新枚举值。末字段 `integrator` 位于100字节偏移，0为PathTrace、1为RestirPt，其他值拒绝。磁盘 `primept.properties` 为schema v8，合法值为`GRID`、`TREE`、`TREE_SPHERE`；旧版本或字段不完整按既有严格规则整份回退默认，不以旧控制字节序列作为生产输入。

| 字段 | 范围/语义 |
| --- | --- |
| mode | 0实时、1离线 |
| bounces / offline_samples | 路径顶点上限 / 离线每帧样本数，各1–64 |
| exposure / hue / saturation | `[1/4096,4096]` / `[0,1]` / `[0,0.5]` |
| view | 0输出、1噪声色、2线性深度、3世界法线 |
| sun / sky / depth_range | 光强各`[1/256,256]`，预览深度`[1,4096]` |
| seed | Java固定`0x13572468` |
| latitude_degrees / solar_longitude_degrees | -90…90 / 0…359整数度 |
| opacity_micromap / ray_reconstruction | 0关、1请求启用，默认1，RR固定preset F |
| reconstruction_quality | 0 DLAA、1 Quality、2 Balanced、3 Performance默认、4 UltraPerformance |
| terrain_batches_per_frame | 1–128默认8，每批4×4×4 section |
| stars | `[0,4]`，默认1；独立于sky强度 |
| auto_exposure_compensation | `[0,1]`，默认0.6；0关闭，其余为旧算法的补偿强度，并非EV |
| hdr / hdr_reference_white | 0/1请求；0自动参考白，否则1–10000 nit；实际启用需surface及标定支持 |
| frame_generation | 0/1请求，默认0；实时RR、早期interposer及实际SDK支持全部成立才准备 |
| light_sampling | 0 Grid默认、1 Tree功率距离树、2 TreeSphere球界方向树；帧边界切换，重置离线累积，实时历史继续复用 |
| integrator | 0 PathTrace默认、1 RestirPt Enhanced；独立管线与历史，帧边界切换 |

结构尺寸/版本、枚举、有限性及范围完整验证后应用。模式、采样方式或实时RR布局改变前宿主先提交encoder并证明旧提交完成，在外层帧边界切换；native依旧资源最后consumer退休。显示控制和格预算无需模式切换等待。冻结拒绝实时源变更入口，但允许替换采样proposal；仅更新快照设置的`light_sampling`，保留姿态和其他冻结输运参数。资源及显示边界见[渲染模式](renderers.md)。

地形批次上限控制 CPU 源编译和后段静态构建两个阶段，各自每帧最多 N 格；不参与 transport 比较，调整上限不重置 RR 历史或离线累积。冻结期间可修改并继续限制已发布源的后段积压，CPU 源编译暂停至恢复实时。实际几何内容变化与等价 OMM 重建的累积处理见 [渲染模式契约](renderers.md)。

OMM 不参与 transport 比较；有限模板及共享像素边界的数值取舍见 [OMM 契约](opacity-micromaps.md)。冻结期间允许切换且保留已有线性累积；启停只使镂空加速结构在后续录制时按当前设置重新绑定已有资源模板，不重新请求宿主源或改变冻结的动画时钟。
