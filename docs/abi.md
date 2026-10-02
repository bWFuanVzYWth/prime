# FFM ABI v7

Java 适配器与 Rust 核心作为同一构建产物配套使用。ABI 版本号用于边界校验，不承诺不同发布之间的二进制兼容；不可仅凭版本号相同混用新旧 JAR 和引擎。双 Minecraft 适配器共享该构建的同一核心，不意味着共享不同发布的内部协议。

导出原型以 [`prime.h`](../crates/prime-engine/include/prime.h) 为准。库名为 `prime_engine`，C 导出 `prime_*` 不变；26.2/26.3 适配器使用同一引擎。全部整数 little-endian，浮点 IEEE754，结构通过字节读取而非 C 对齐结构读取。长度为字节数，所有保留字段必须为零。`prime_create(7)` 返回非零 handle；status=0 成功，-1 失败。`prime_last_error` 返回 UTF-8 完整长度（不含 NUL），输出容量允许时写入终止 NUL。

## 公共头（24 字节）

| Offset | 类型 | 语义 |
| --- | --- | --- |
| 0 | u32 | magic `0x54505250`，字节 `PRPT` |
| 4 | u32 | ABI version=7 |
| 8 | u32 | operation |
| 12 | u32 | reserved=0 |
| 16 | u64 | epoch；0 无效 |

## 场景操作

- **1 reset**：仅头部，epoch 必须严格增加；清空几何、纹理与 tombstone。
- **3 remove section**：头后 `section:u64, revision:u64`；删除全部层并撤销该段可用性，记录 tombstone。revision 必须晚于该段已有操作与完成水位。
- **4 texture**：头后 `id:u32, width:u32, height:u32, reserved:u32`，然后恰好 `width*height*4` 字节源编码 RGBA8。id=0 保留给白纹理，UINT32_MAX 保留给实例继承标记；两者均不可上传。id=1 是当前 block atlas。
- **6 dynamic snapshot**：原子替换显式动态网格与参数粒子，格式见下文；序号独立于静态区块 revision。
- **7 instance delta**：原子发布局部几何原型及实例增量，格式见下文；与 op6 分别维护序号和场景。
- **8 replace section**：一次原子替换 section 全部层；源操作顺序与内容 revision 分离，格式见下文。
- **9 retire textures**：源 owner 释放纹理，实际回收还须等待场景引用消失。
- **10 section completion**：生产者完成水位，允许回收已不可能被迟到工作引用的历史。
- **11 remove sections**：同一包批量撤销多个 section，完整验证后发布。

ABI v7 提供下面的 MC 原始源批次接口，保留场景协议、参数粒子与诊断查询。当前生产地形走 `prime_mc_plan` / `prime_mc_sections`；op8/10/11 保留给封闭网格输入和诊断夹具；op12/13 仅在 Rust cfg(test) 与 Java testFixtures 中存在，生产库拒绝，不与新生产者混用。op2 无生产消费者，未知操作及历史 ABI 直接拒绝。

op4 的完整包预算是 `40 + width*height*4 ≤ 256 MiB`，尺寸与乘法先校验，不能只限制像素数组。Java 图集与动态纹理直接将40字节头和源 RGBA 写入 `NativeBridge` 复用的 confined native 存储，不再生成同尺寸的临时 heap 包。该存储只借用到同步 `prime_submit` 返回，扩容或下次提交可覆盖；Rust 在返回前拥有需要保留的像素。源捕获数组和 GPU 上传仍有各自的复制与寿命，这一改动不表示纹理链路零复制。

## MC 源批次（source version 6）

该入口只由 `prime_minecraft` 解释，不能将 Minecraft 字段枚举、坐标规则或 palette 布局扩散到 `prime_scene` / GPU。当前识别 MC version 262、263；其他版本明确拒绝。临时语义替代见独立的 [原型 hack 清单](../PROTOTYPE_HACKS.md)。

`prime_mc_plan(handle, pages, count, output)` 接收相机、半径输入、宿主实际来源范围和增量事件，返回 Rust 所有的 section 请求表。Java 在宿主 owner 线程按表封装，调用 `prime_mc_sections(handle, pages, count, output)`。该入口的 output 同样是 `prime_source_page`：长度为0表示本批已发布；非零表示一批颜色源需求。Java 在同一源帧按表返回 kind=3 的源字段/结果；标准群系源缓存未命中时，再按表返回 kind=4 的实际 quart 群系字段。最多两个后续颜色批次，不按方块反复往返。没有逐段/逐 quad FFM、worker 回调或跨帧配额；冻结时不执行源请求。空闲帧仅交换 section 批次头，不读取已知 palette、不编译、不产生 tint 批次。

输入 `prime_source_page` 是 16 字节 `{const uint8_t* data; uint64_t length;}`，描述表和每页均只借用到调用返回。页串接成一个逻辑流，字段允许跨页；每页最多 256 MiB，Java 复用 1 MiB native 页，禁止为合批再次拼接成巨型数组。必须保留的数据在返回前成为 Rust 所有；编译 worker 返回前全部汇合。响应错误使引擎失败，不重放源回调补画。

四个输入流都有 32 字节头：`magic:u32=0x53434d50, source_version:u32=6, minecraft_version:u32, kind:u32, epoch:u64, batch:u64`。epoch 和 batch 非零；epoch 必须匹配场景，batch 在 epoch 内严格增加。请求期间不可再次 plan，响应必须恰好匹配该请求的身份和全部 section。

kind=1 的头后为相机 `x/z:f64`、半径 `i32`、世界 `min_section_y/max_section_y:i32`（含端点）、宿主实际来源范围 `min_x/max_x/min_z/max_z:i32`、实际 `game_time:u64`，随后为事件流。每条非零事件为 `kind:u32, x/y/z:i32`；1 加载列、2 卸载列、3 段脏、4 全量资源失效、5 清空旧来源列清单（随后用1重建）、6 宿主颜色列失效（x/z及其相邻八列）、7 全部宿主颜色缓存失效。6/7只重编译实际消费过 tint 的活跃段，不重新请求其 palette；普通 dirty 与颜色失效不可互相代替。单个 `u32=0` 终止。Java 转发原始通知；Rust 合并、过滤并维护窗口、活跃段和完整一格邻域依赖。完整清单仅在 owner 建立/实际源范围变化时重发。

输出请求流为 `batch:u64, request_count:u64, column_edit_count:u64, active_count:u64`，后接 request_count 条 `{x/y/z:i32, active:i32}`（16 字节），再接 column_edit_count 条 `{x/z:i32, active:i32}`（12 字节）。active 为0或1；请求中的0表示仅供邻接依赖，不能发布为渲染段；列编辑只用于 Java 镜像 Rust 选择结果，以支持现有方块实体提取。输出借用指针在下一次 plan、accept 或销毁时失效，Java 必须在提交响应之前读完，不得保留。

kind=2 的头后是以下记录流，单个 `u32=0` 终止。所有字符串为 `byte_count:u32 + UTF-8 + 补零到4字节`。

| 记录标签 | 字段 |
| --- | --- |
| 1 state | `state_id:u32, flags:u32, model_id:u32, block_name:string, face_id:u32×6, support_bits:u32, fluid_name:string, flow_level:u32, falling:u32, fluid_material:u32, light_emission:u32`，随后16字节 placement 声明（见下文）；flags 位0=air、1=存在 offset 函数、2=缓存 solidRender、4=非 MODEL、5=legacySolid、6=HalfTransparentBlock/LeavesBlock、7=IceBlock、8=实际玻璃块类型、9=实际玻璃板类型、10=LeavesBlock；位3保留 |
| 2 model | `id:u32, type:u32`；id非零，type0未知、1直接 quad、2权重选择、3multipart 子项、4别名 |
| 3 section | `x/y/z:i32, available:u32`；0无源，1后跟压缩 palette 数据，不能以无源代替空段 |
| 4 face profile | `id:u32, u_count:u32, v_count:u32, word_count:u32`，随后 U/V 坐标 f64 列表及占据 u64 words；id≥2，0/1为内置空/全面 |
| 5 fluid material | `id:u32, raw_layer:u32, flags:u32`，后接 still/flowing/overlay 三条 `{sprite_id:u32, bounds:f32×4}`；flags位0=存在 tint source、1=存在 overlay；缺 overlay 时写 flowing 来源 |
| 6 sprite | `id:u32, name:string, bounds:f32×4, frame_width/height:u32, mip_count:u32`；每级为 `width/height:u32, pixel_count:u32, RGBA8[pixel_count]`；最后 `interpolate:u32, frame_count:u32` 和 `{frame_index:u32, duration:u32}` 列表 |
| 9 LabPBR source | `sprite_id:u32`，随后 normal/specular 两个图像字段：`present:u32`（0缺图、1有图）；有图紧接 `width/height:u32, pixel_count:u32, RGBA8[pixel_count]` |

state 的 placement 尾部为 `{offset_kind:u32, horizontal_limit:f32, vertical_scale:f32, seed_kind:u32}`。offset_kind 为0无偏移、1标准XZ、2标准XYZ、3未知；flags 位1必须与 kind 非零一致。参数须有限且非负；kind0/3参数均为0，kind1的 vertical_scale 为0。seed_kind 为0当前位置、1下方一格、2北方一格、3南方一格、4西方一格、5东方一格、6未知。种子邻格位移先按 i32 wrapping 求坐标，随后执行相应版本的 MC 位置哈希；x 的乘法先 i32 wrapping 再转 i64。偏移保持源 f32 除法后转 f64、水平 clamp 和 XYZ 竖向缩放的求值顺序。

Java 仅在首次 state 定义的批量源准备阶段，绑定实际 offset 函数类、方法声明类、状态属性及已知常量返回值，不按位置调用 getOffset/getSeed。未知 lambda 或方法覆写不被执行以猜测规则，保持旧默认近似并单独诊断。对标准方法体或 lambda 的第三方 Mixin 原地修改不在该声明识别的兼容保证内。placement 参与邻接依赖比较，资源代次重置后重新准备。布局或参数不合法时整批不发布；source version 5 与6明确互拒，公共 FFM ABI仍为7，JVM与DLL须来自同一构建。

model type1 后为 `quad_count:u32` 和每个100字节 quad：`face:u32, tint_index:i32, raw_layer:u32, sprite_id:u32, light_emission:u32`，四个 `{position:f32×3, packed_uv:u64}`；MC 的 U 在高32位、V 在低32位。face0..5为下/上/北/南/西/东，6无剔除面。raw_layer 是版本层的实际字段值，Rust 适配器解释后生成公共材质语义。type2 为 `count:u32` 和 `{weight:u32, child_id:u32}`；type3 为 `count:u32` 和**实际选中**的 child_id 列表；type4 为单个 child_id。定义身份限定于 epoch/资源失效代次，已观察定义不逐帧重发。

sprite id 为 1..0x3fffffff，映射到 generic texture id `0x40000000+id`；动态纹理限制在该保留区间以下。quad sprite=0 是封闭 atlas 来源。静态 mip0 的 pixel_count=0 表示共享已捕获的 atlas，其他 mip 必须有完整图像；动画必须有完整 mip0 帧图。mip_count 为1..15，帧尺寸/图像≤16384，逐级尺寸及所有帧索引/正时长完整验证。显式资源准备批次发布已捕获方块图集的全部 sprite，不依赖 LabPBR 存在或当前区块引用；同一源 owner 仅准备一次，后来遇到的其他 atlas 来源仍可增量定义。scene owner/epoch 重置不等于真实图集重载，数字身份不得跨 owner 复用。资源字典不传 Rust 配方、关系、平面、合并标签或 GPU 布局。读取与 UV 解释、采样边界见[表面编译](surface-compiler.md)。

LabPBR record 必须跟在同批已定义的 sprite 后，单个 sprite 不可重复定义材料。只有有效的 `format=lab-pbr/1.3` 声明与实际辅助图才生成该记录；传输的是原始 RGBA8，源 G/B 清洗、sheet 布局、mip、动画和 height 解码归 Rust。缺图不补伪像素，源尺寸、像素数量和所有权在发布前验证。该 source record 与场景 op9 的纹理退休不同；公共 ABI v7 和 source version 6 不改变新旧发布不可混用的要求。具体通道见[材质契约](materials.md)。

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

state/quad 的 light_emission 为实际0..15字段。不传没有消费者的完整碰撞形状布尔值；占据从源模型闭合关系证明，不能从碰撞或 sturdy 推断渲染实体。实际源类型标志只由 Java 绑定，光学分类与系数属于 Rust MC 适配。

state 面顺序为下/上/北/南/西/东。face profile 的 U/V 轴在 X 法向时为 Z/Y、Y 法向时为 X/Z、Z 法向时为 X/Y；格索引 `u*(v_count-1)+v`，低位先行，word 尾部省略的位为零。转录初始化后缓存的离散面数据，由 Rust 判定覆盖，不在 Java 做 shape join。两版 support_bits 是宿主 faceSturdy 数组按索引展开的位（direction ordinal×3 + SupportType ordinal，FULL=0）；缺失动态支撑缓存时仅置bit31。flow_level 是实际 LEVEL 属性，缺属性写0；falling为0/1，流体 registry name 的源/流动态解释留在 Rust。无流体 material=0，其余引用定义过的非零资源。资源 ID 在失效前不可原地改变内容；source version 不匹配立即拒绝，不读历史布局。

section 压缩数据为 `bits:u32, palette_count:u32, word_count:u32`、palette_count 个 registry state ID、word_count 个 u64。局部 palette 的存储值索引该列表；palette_count=0 表示全局 registry ID。零位存储恰好一个 palette 项且零 word；其余每个 word 存放 `floor(64/bits)` 个状态，不跨 word 拼接，顺序 `y*256+z*16+x`，恰好4096项。Java 只复制源列表/word，Rust 解包、比较与编译。

完整验证响应后，Rust 更新活跃源缓存，只有实际源变化、颜色依赖失效、资源失效或邻接/成员变化才进入编译。普通变化影响自身，边界依赖语义变化才传播至对应活跃面/棱/角邻段；每个非空段分4个Y slab，由私有同步池完成。归并和精确内容比较也同步并行，最终向场景提交绑定当前 owner/epoch/revision/完成水位的版本无关 `CompiledSection`，发布前验证证明仍有效。源 batch 是唯一完成水位，静态仍等待64段完整后发布整格BLAS。资源重载强制重新解释相同 palette；不变压缩输入不重编译，重新编译但最终图层相同则保留 Arc 和内容版本，只推进源完成水位。

### 颜色源批次

颜色请求共用头 `batch:u64, count:u64, epoch:u64, minecraft_version:u32, phase:u32`。phase=0/2 后接 count 条20字节 `{world_x/y/z:i32, state_id:u32, slot:i32}`；slot≥0表示实际 block tint index，-1表示实际 FluidModel tint source。phase=2 另要求在出现群系源时提供尚未绑定的群系计算定义。只请求本轮实际产出的几何，同一位置/源 slot 在本次编译中去重；缺失 slot 按原版返回白色。模型选择和剔除仍属于 Rust，Java 不扫描 section 或模型图。

kind=3 的头后为 `count:u64, biome_blend_radius:i32`，随后 count 个 `{source_kind:u32, value:u32}`。source_kind=0是未知源实际回调的 ARGB；1/2/3/4是原版 grass/foliage/dry foliage/water，value必须0；5是 double tall grass，value为实际 upper-half 属性（0/1）；6是红石强度（0..15），7是茎年龄（0..7），8是实际常量 ARGB（包括缺失 slot 和 waterParticles 的世界白色）。Rust 解释状态规则和颜色算式，半径为0..7。Java 通过与已核验原版工厂完全相同的实际 source 类绑定字段，不按方块名猜测、不执行回调后推断规则；其余实际 source 执行一次 `colorInWorld`，保留全部 ARGB 通道。对这些内置类/静态颜色算法的 Mixin 修改不在当前第三方兼容保证内。

记录末尾为 `definitions_present:u32`（0/1）。1只允许出现在请求定义的响应，随后为 `zoom_seed:u64, permutation:u32×256, offset_x/y:f64, input_scale/value_scale:f64`，再接 grass/foliage/dry foliage 三张表，各为 `count:u32 + ARGB:u32×count`，count≤65536。排列必须恰好包含0..255。Java 读取实际 BiomeManager seed、资源色表和噪声实例字段；26.2 绑定单 octave PerlinSimplexNoise，26.3 绑定 SimplexNoise。Rust 保留各版浮点求值与返回精度、色表索引、缺项默认色、覆盖色和修色语义；未知噪声布局拒绝，不静默替换。仅有常量/未知源的批次无需传表。

Rust 按解析器、实际 Y 和局部平面查询范围组织带 halo 的小格；行位掩码保留每个查询混合方形的精确并集，重叠样本通过16×16源块索引去重。Rust 先用源 seed 计算实际选中的 quart 坐标，只向宿主请求未缓存的单元。phase=3 请求 count 条20字节 `{section_x/y/z:i32, needed_mask:u64}`；位索引为 quart 的 `x | z<<2 | y<<4`，每轴0..3。Java 按页顺序、置位从低到高读取实际 `BiomeManager.getNoiseBiomeAtQuart`，不调用 `getBiome`、颜色 resolver 或混色函数。

kind=4 的头后为 `count:u64, biome_count:u32`，随后 biome_count 条32字节字段：`temperature/downfall:f32, water_argb:u32, grass/foliage/dry_override:u32, override_flags:u32, grass_modifier:u32`。flags位0/1/2分别表示三个覆盖色存在；modifier为0 NONE、1 DARK_FOREST、2 SWAMP。最后按请求页及置位顺序给出 `biome_id:u32`，引用本响应内从0开始的定义，数量恰好等于所有 mask 的 popcount 之和。Java 每个响应按实际 Biome 身份转录一次字段。Rust 计算原始颜色，再通过整数前缀和计算与原版逐点累加相同的 box filter、整数除法和 alpha；半径0保留原始 ARGB。请求顺序不依赖哈希表遍历。

标准混合结果按查询所在 section/局部位置/解析器保存在显式 Rust 上下文中；相同群系依赖下的几何编辑直接复用。原始颜色另按 section、解析器和实际 Y 分配16×16平面；源 quart 页每64个单元共享有效位，不同解析器共用源字段。三层缓存只发布完整验证后的值，未知与黑色分开。混合半径变化撤销混合结果，保留半径无关的原始颜色；原始颜色或所需 quart 全部命中时在 kind=3 内完成计算和发布，无需 kind=4 往返。

两版 BiomeManager 从 `(block-2)>>2` 及其 +1 的八个 quart 角点选取群系。因此颜色列更新或列卸载清除该列 quart 页，并使该列水平方向外扩2格内的原始颜色失效，混合结果再外扩当前混合半径；相邻列的其他位置保持有效。全局颜色失效、资源代次和世界切换清空三层缓存及源计算定义。移出活动/依赖范围回收对应条目，包括双高植物、水平混合 halo 和 zoom 的上下相邻 quart 页；共享页允许保守回收，不能随跑图无界累积。常量及未知源结果不跨批缓存，不能假定任意外部回调纯净。

所有响应按请求顺序对应，没有终止记录，精确校验 epoch/version/batch/count、截断与多余数据。源指针仅借用到各次调用返回，待着色片段由 Rust 显式阶段所有者保存；全部颜色闭合前不能发布、推进完成水位或再次 plan。颜色回填与几何归并处于同一同步 worker 阶段。宿主在 owner 线程使用同一源帧的状态和世界；内核没有宿主回调或 MC 对象。

zoom 的八个角点扰动按 quart cube 复用，每个 cube 的64个位置只计算实际需求，已选角点只依赖 seed。普通群系/色表失效可复用这些选择；实际 seed 改变、资源/世界重建清空，消费者退休按 halo 回收依赖页。它不缓存某个位置应属于哪个群系，quart 源变化仍重新取色。

两版 `ClientLevel.onChunkLoaded`（包括 biome packet）及 `clearTintCaches` 转发颜色失效；Rust 先合并列，再过滤实际消费者。标准原版状态、群系、混合半径、资源代次及双高植物位置依赖已覆盖；未知回调依赖任意邻块、光照、时间或外部状态的失效尚无声明契约，不承诺第三方完整兼容。

## 公共顶点与材质语义

各源 span 显式给出顶点数量、stride 和字段 offset；数据为恰好 `vertex_count*stride` 字节。topology=3 表示三角形、4 表示四边形；material=0 opaque、1 alpha cutout、2 stochastic alpha coverage，其他值拒绝。

Position 为 f32×3，UV 为 f32×2。局部原型和显式原始网格可输出 **stride24、position0、color12、uv16**；当前地形/流体生产通路使用上文 MC 源批次。RGBA8 按 R/G/B/A 字节顺序保存作者颜色与源 tint 的编码域组合，排除原版 AO/方向明暗和 UV2 光照。source layer 使用公共协议常量 opaque=0、cutout=1、translucent=2，不传 MC enum ordinal。显式 stride/offset 解码器也支持 BLOCK28 等合法源布局，并有行为回归测试；这不表示兼容旧 ABI。quads 在 Rust 展开为 `(0,1,2), (2,3,0)`。

## 原子 section 替换

op=8 固定头共 **72 字节**：公共头后为 `section:u64`（24）、`sequence:u64`（32）、世界原点 `f64×3`（40/48/56）、`layer_count:u32`（64）、`reserved:u32=0`（68）。sequence 必须非零，并晚于该 section 的全部已有层、完整操作屏障以及 op10 完成水位。

每层为 **40 字节描述 + 紧接的顶点字节**，无 padding。十个 u32 依次是 `layer_id, texture_id, material, topology, vertex_count, stride, position_offset, color_offset, uv_offset, reserved=0`。顶点及材质遵循上述公共语义，layer ID 不可重复。`layer_count=0` 清空几何并发布一个已完成的空 section，不能代替 op3 卸载；零顶点层等价于该层缺失。

完整包、最终容量及引用验证成功后才原子发布。源序列始终推进；三角形、源 RGBA/UV、纹理/材质与原点逐字段相同的层保留原 Arc 和内容 revision，在可用性不变时不使渲染 scene 失效。首次完成的空段也会推进 scene revision，因为它可能使 64 段单元完整。忽略的布局 padding 与层顺序不参与内容身份。移除的层被清除，只有新增或变化层发布新内容。这个序列屏障也约束 op3/op11，不能混用旧序列复活遗漏层。op8 保留为显式网格输入与诊断对照；生产地形由 MC 适配 crate 发布封闭网格，同一 renderer/epoch 只能有一个地形生产者。CPU 持有逐段快照；64 段就绪门槛和后续整格替换见 [空间合批](spatial-batching.md)。

## 历史路由夹具

旧局部模型/流体 op12/13 已退出生产 ABI，只在 `prime_scene::routing::legacy` 的 cfg(test) 参照和 Java testFixtures 保留。发行 JAR 不包含旧地形队列、RouteBuffer 或 SourceQuads；生产 CaptureInbox 仅管理资源世代、atlas 与失败状态。不得重新以这些夹具恢复另一套生产解释器。

## 批量撤销与生产者完成证明

op=11 公共头后为 `count:u32, reserved:u32=0`，再跟 count 条 `section:u64, sequence:u64`，每条 16 字节。同包 section 不得重复；每条序列必须晚于该段已有操作和完成水位。完整校验后一起撤销可用性与几何。跨包不提供事务；按 256 MiB 协议包上限分片不是逐帧工作配额。

op=10 公共头后仅为 `completed_sequence:u64`。水位单调不减，表示此 epoch 内所有不晚于该值的生产者都已完成或取消，而且这些生产者被接受的结果已全部提交 native。Java 先提交封闭批次，再提交水位；失败不能确认。新回调进入下一批。水位不根据时间、渲染帧数或最大已见 sequence 猜测，也允许覆盖只产生撤销/取消的序列。

Rust 丢弃不晚于水位的删除历史，后续对应旧包仍被全局水位拒绝；存活段保留其当前内容和顺序。历史存储取决于尚未完成的源前缀，不依赖累计流送过的身份总量。生产者 token、输入顺序、内容 revision、CPU 发布 cursor 与 GPU serial 各自独立。

## 纹理 owner 与引用

op=9 公共头后为 `count:u32, reserved:u32=0`，紧接 count 个 `id:u32`。0、1、UINT32_MAX 为保留身份，不可退休；不存在的普通 ID 忽略。整个包通过校验后才撤销 owner，重复退休无额外效果。

纹理同时由静态层、原型、实例覆盖和 raw 快照持有引用。只有 owner 已退休且当前源引用为零，下一次 CPU 发布才删除纹理并输出显式 removal；op4 重新上传会恢复 owner。冻结/旧 CPU 快照继续持有不可变像素所有权。GPU 元数据与像素槽在同队列的最后读取依赖之后复用，被替换的缓冲按实际提交完成证明回收。FFM 返回和 op9 均不是 GPU 完成证明。

## 动态完整快照

op=6 的固定头共 **64 字节**：

| Offset | 类型 | 语义 |
| --- | --- | --- |
| 0–23 | 公共头 | 当前资源 epoch，operation=6 |
| 24 | u64 | sequence；同一 epoch 内严格递增且非零 |
| 32 / 40 / 48 | f64 | 此批几何的世界原点 XYZ |
| 56 | u32 | span_count |
| 60 | u32 | reserved=0 |

每个 span 是 **32 字节描述 + 紧接的顶点字节**，span 之间不填充。描述按顺序为八个 u32：`texture_id, material, topology, vertex_count, stride, position_offset, color_offset, uv_offset`。topology=3/4 的布局约束与静态 mesh 相同；material 只接受 0/1/2，不能按位组合。适配器保留实际源格式和拓扑，不在 Java 展开三角形。当前动态原点为相机世界位置，顶点为已执行模型变换的相机相对世界坐标，尚未应用视图旋转。

op6 另外接受 **topology=1 的参数 billboard**，该变体不用于 op7 原型。此时 count 是粒子数，固定 stride=52、position_offset=0、color_offset=48、uv_offset=32。每条记录为中心 f32×3、四元数 xyzw f32×4、scale:f32、u0/u1/v0/v1:f32×4、RGBA8。四元数可非单位但必须可归一化；Rust 保持 q*v*q⁻¹ 的旋转语义，再乘 scale、加中心。局部角依次为 (1,-1)、(1,1)、(-1,1)、(-1,-1)，UV 对应 (u1,v1)、(u1,v0)、(u0,v0)、(u0,v1)。原版光栅 light 不传递。相邻同材质参数 span 合并，不逐粒子 FFM。

整个包验证成功后才替换旧快照；尾随字节、缺失纹理、旧 epoch/sequence 或中间 span 无效均不改变已有场景。`span_count=0` 是有效的清空操作，防止对象消失后留下旧几何。texture_id=0 仅表示明确的无纹理白色，非零引用必须在提交快照前上传。静态区块与动态快照不共用对象 ID；动态更新不递增静态 revision，也不重新翻译静态 mesh 表。

观察 sequence 与内容 revision 分开：Rust 自持最后一次成功验证的源 payload（原点、描述及源字节，不含 sequence），逐字节证明相同的局部输入可复用解码后的 Arc。原点也相同时只消费新 sequence，不重新翻译或分桶；仅原点变化时推进内容 revision，复用局部几何并重新进行位置相关规划。仍拒绝重复/倒退 sequence；失败包不更新证明或序号，epoch 重置清除两者。比较包括源 padding 等全部 payload 字节，可能保守地重算，不使用哈希碰撞或近似相等。持续变化的输入仍完整解码，并复制新的源证明，缓存容量保留历史最大单包大小。

Java `DynamicFrame` 保留一个按需增长的 confined native arena，相邻同描述 span 可合并。FFM 每帧借用一次 sealed segment；Rust 在返回前完成解码并拥有结果；raw 使用可复用 Vec 工作区，发布后通过 Arc 保持只读。仅在真实消费者全部释放后才重新借用工作区，失败包不覆盖旧帧。借用只覆盖该次同步调用，不能跨下一次 `begin`、扩容或 `close`；GPU 完成与这段源字节的寿命无关。源纹理变化仍通过独立 op=4 增量提交。

material=2 表示随机 alpha 覆盖：alpha=0 不遮挡，alpha=1 完全覆盖，中间值按覆盖率接受交点；接受后仍使用当前表面材质。它不是折射、介质吸收或物理透射率。

## 原型与实例增量

op=7 的固定头共 **48 字节**，公共头后为 `sequence:u64`，以及四个 u32 计数：`prototype_upserts, prototype_removes, instance_upserts, instance_removes`。四组记录紧接在后，顺序与计数一致，无 padding。sequence 在当前 epoch 内严格递增、非零，**本批每条记录的 revision 必须等于 sequence**。同一类身份不能在一批中重复或同时 upsert/remove；原型与实例各有自己的非零 u64 身份空间。

原型仅接受 topology=3/4 的局部网格。原型 upsert 先写 `id:u64, revision:u64, span_count:u32, reserved:u32=0`，再跟 op6 格式的 span 和局部顶点字节；原型必须非空。移除记录统一为 `id:u64, revision:u64`，共 16 字节。

实例 upsert 共 **128 字节**：

| Offset | 类型 | 语义 |
| --- | --- | --- |
| 0 / 8 / 16 | u64 | 实例 id / revision / 原型 id |
| 24 / 32 / 40 | f64 | 世界原点 XYZ |
| 48 | f32×12 | row-major 3×4 仿射矩阵；局部点先乘矩阵，再加世界原点 |
| 96 / 100 | u32 | texture / material 覆盖；各自 UINT32_MAX 表示继承原型 |
| 104 | RGBA8 | 实例 tint，按 R/G/B/A 字节顺序 |
| 108 | u32 | reserved=0 |
| 112 | f32×4 | UV 的 scaleU、scaleV、offsetU、offsetV |

texture=0 是明确的白纹理；material=0/1/2 与静态语义一致。仿射矩阵和 UV 必须有限，仿射线性部分须可逆，支持镜像、非均匀缩放和剪切。UV 按 `sourceUV*scale+offset` 求值。源顶点 RGBA 与 tint 在编码域按 `floor(source*tint/255)` 组合，再进入插值与后续颜色处理；不能先将两者各自线性化相乘。

Rust 先完整验证，再发布整个批次。实例引用以最终批状态为准，允许同批新增原型并引用，或迁移/删除实例后移除旧原型；仍被存活实例引用的原型不可移除。所有实际非零纹理引用须已上传。失败不改变序号、引用计数、原型或实例。删除不存在的身份只推进输入序号，不使渲染状态失效。整体有序和批 revision 约束防止旧包复活对象，无需永久保存已删除实例的 tombstone；同一身份重新可见时须使用新的批序号。

无变化时 Java 不提交空增量。`InstanceCapture` 持有资源/实例记录及可复用的 confined arena，`sealDelta` 后借用至同步 FFM 返回，成功后 `acknowledge` 才确认已发送状态；被跳过的 native hook 不丢失脏记录。原型只上传变更的局部源数据，实例新增/变化只发送固定记录，GPU 生命周期不依赖这些 Java 地址。op6 清空只影响回退几何，实例须通过 op7 删除或 epoch reset 清除。

## 帧操作

`prime_record` 和诊断用 `prime_render` 共用 **104 字节** op=5 包：

| Offset | 类型 | 语义 |
| --- | --- | --- |
| 24 | f64×3 | 世界相机位置 |
| 48 / 60 / 72 | f32×3 | forward / right / up；单位正交向量 |
| 84 | f32 | 垂直 FOV，弧度 |
| 88 / 92 | u32 | width / height |
| 96 | u32 | sample index，0 请求重置累积 |
| 100 | f32 | 实际太阳时角（弧度），必须有限；Rust MC 适配层生成大气太阳方向 |

原生在相机、场景、尺寸变化或 sample index=0 时重置累积；其余样本计数由 renderer 自己维护。

## 宿主 Vulkan 生产路径

创建 session 后、首次渲染前调用一次 `prime_attach_vulkan(handle, descriptor, 48)`。descriptor 不是场景操作包；布局如下：

| Offset | 类型 | 借用句柄 |
| --- | --- | --- |
| 0 / 8 | u64 | instance / physical device |
| 16 / 24 | u64 | device / graphics queue |
| 32 | u64 | 宿主提交完成 timeline semaphore |
| 40 / 44 | u32 | queue family index / enabled capability flags |

调用方须已在实际逻辑设备启用 buffer device address、acceleration structure、ray query 与所需扩展，并保证 queue family 支持 graphics+compute。仅查询物理设备支持不够。Rust 不销毁这些宿主对象，也不为 PT 调用 queue submit。

flags 的 bit0 表示已在这台逻辑设备启用 `VK_EXT_opacity_micromap` 的 `micromap` 和 synchronization2；bit1 表示实际启用 Streamline 所需的 NVX binary import、NVX image view handle、KHR push descriptor、KHR buffer device address、KHR synchronization2 及 timelineSemaphore/descriptorIndexing/BDA、synchronization2、shaderStorageImageExtendedFormats、shaderStorageImageWriteWithoutFormat 能力，其他位必须为零。宿主只在 `vkCreateDevice` 成功且实际创建集合包含扩展与特性后发布；26.2/26.3 本身要求 `VK_KHR_synchronization2`。物理支持不等于已启用，flags=0 保留原始 PT 路径，Rust 不补开借用设备的能力。OMM 和 RR 用户设置与设备能力独立；具体兼容范围见 [OMM 契约](opacity-micromaps.md)与[重建契约](reconstruction.md)。

`prime_streamline_present(queue:u64, present_info:u64)->i32` 同步包住真实 `vkQueuePresentKHR`，原样返回 VkResult。结构体指针及其引用数组只借用到返回；未初始化 RR 或非目标队列直接调用原 Vulkan。这个入口不消费场景 session，不把一次 Present 等同于 GPU 完成证明。

每帧调用 `prime_record(handle, frame, 104, command, image, image_view, serial)`。command 是已开始录制、尚未结束的宿主 primary command buffer；目标为带 STORAGE 用途、GENERAL layout 的 RGBA8_UNORM 主颜色图像及其 view，尺寸必须等于 frame。serial 是将包含此 command 的实际提交完成值，同一 session 每个 serial 最多录制一次。宿主在同队列依次提交，并在所有命令完成后 signal timeline 到该 serial；Rust 自己的描述符槽与退休资源依赖此保证。

`prime_record` 同步完成输入复制与命令录制，正常返回时 GPU 可以尚未执行；没有像素返回。只在所有描述符槽仍在途时等待最旧 serial，这是 GPU 资源复用的必要完成等待，不是向 MC 上游施加工作配额或主动背压。shader 在 GPU 上处理宿主输出行方向。`prime_gpu_time(handle)` 返回最近一个已收集的完成帧 GPU 纳秒数；宿主队列不支持时间戳或尚无结果时为 0；有能力的宿主常驻三个阶段时间戳，仅在完成证明后读取，不增加 GPU 等待。它不是当前 CPU 调用耗时或窗口平均。

`prime_cpu_diagnostics(handle, output, capacity)` 在 owner 线程按需将最近一次 CPU 准备/录制快照格式化为 UTF-8。成功返回不含 NUL 的完整字节数；非零容量最多写入 `capacity-1` 字节并补 NUL，容量零时允许空指针查询长度；错误返回 `u64::MAX` 并设置 last error。输出指针只借用至调用返回。未发生准备/录制时明确输出 `available=false`，不使用零时间冒充已测量结果。

此查询不访问游戏对象，不等待 GPU、不回读图像。正常帧只更新固定大小的 CPU 阶段与已有工作量计数；慢帧才查询和格式化。录制快照携带实际 host serial，Java 将它与自己的 serial 一起写入警告，可核对是否同次录制。附带的 GPU 区间明确标注最近完成的 serial，与当前 CPU 录制可能不同。诊断文本用于人工归因，不作为额外场景命令或稳定机器解析协议；Java 预留 8192 字节，超长或查询失败须明确报告，不能默默截断或令诊断失败触发渲染器回退。

调用 `prime_destroy` 前宿主必须提交所有已录制的 PT command；native 等待最后相关 serial 后销毁 PT 资源。若无法证明完成，返回失败并保留 session/资源以隔离风险。宿主 device、timeline、图像等必须覆盖其全部使用寿命。

## 同步诊断路径

不 attach 宿主的 session 可调用 `prime_render`：创建独立设备，同步返回恰好 `width*height*4` RGBA8 字节，左上角首像素、行连续无 padding。此接口仅供离线图像与 FFM 行为测试，不能在宿主 session 上调用，也不能代表生产路径性能。游戏合成不使用该接口。

## 边界与容量

全场景三角形总量不再受 800 万或带符号 32 位上限约束。64 位宿主累计地形、op6 回退与 op7 原型的唯一几何数量及派生字节，同一原型不按实例数重复计费；超过宿主可寻址字节范围明确失败。`count:u32` 仍描述单个 span，单包仍限 256 MiB；大量独立 section/prototype 分批发布，与一个含数十亿顶点的连续包是不同契约。

当前仍明确拒绝：超过 512 MiB 的唯一像素存储总量（含完整动画帧图与 mip，共享 Arc 不重复计费）、各超过 262144 的常驻原型或实例、超出每轴 1..65536 或设备 image/dispatch/累积 storage range 的输出，以及不有限/越域位置、未知 flags、未知拓扑和尾随字节。op6 是完整动态快照，op7 是一批原子增量，op8 是完整 section 替换；它们尚未提供跨包事务，不能用重复替换包伪装无界分页。GPU 的局部 AS/实例索引、设备内存分配数量与实际内存也构成独立边界。以上不表示已经支持任意视距、任意单资源或任意驻留总量。协议验证失败不修改场景 revision 或已有数据；部分分配使用 `try_reserve`，尚不能保证所有 Rust 系统内存耗尽均可恢复。

当前 Slang 局部指针下标以32位字节偏移计算。静态范围按最宽432 B记录的保守上限 `floor(2^32/432)` 划分，局部范围保持完整 quad 的两个 primitive 槽；实际选择176/240/272/432 B格式，详见[表面记录](surface-compiler.md)。动态/原型使用176 B quad，并保守使用相同局部范围上限。各范围设备基址为64位，总量可跨页；单个共享原型超限仍明确失败。实例展开统计使用u64，帧参数只传“是否有几何”，不能收窄后误判为空。

非空静态层、raw 和原型/实例在提交前必须上传引用的纹理；缺失引用明确报错。所有调用必须在创建 handle 的 OS 线程进行。`prime_destroy` 退休身份，重复释放会报错。调用方必须保证原生指针指向有效读写区域；长度校验不能验证任意地址。

## 设置包（独立 schema v4）

`prime_configure(handle, data, length)` 借用恰好 68 字节，返回前解析，不保留指针。它没有场景命令头；设置版本独立于场景 ABI。宿主模式改变时必须先提交 encoder，再从外层帧边界调用；native 等待旧 GPU 使用完成，释放旧模式资源后创建新资源。普通显示控制变化不需要切换等待。

| Offset | 类型 | 字段 |
| --- | --- | --- |
| 0 / 4 | u32 | settings version=4 / mode（0 实时、1 离线） |
| 8 / 12 | u32 | 最大路径顶点数 / 离线每帧样本数，均为 1–64 |
| 16 / 20 / 24 | f32 | 曝光乘数 `[1/4096,4096]` / hue `[0,1]` / saturation `[0,0.5]` |
| 28 | u32 | view：0 最终输出、1 噪声色、2 线性深度、3 世界法线 |
| 32 / 36 | f32 | 太阳 / 天空乘数，均为 `[1/256,256]` |
| 40 | f32 | 深度预览范围 `[1,4096]`，不影响实际深度 |
| 44 | u32 | 采样 seed，Java 当前固定 `0x13572468` |
| 48 / 52 | i32 / u32 | 观测纬度 -90…90° / 太阳黄经 0…359°，均为整数度 |
| 56 | u32 | opacity_micromap：0 关闭、1 自动优先启用；默认 1，仍受设备与表面兼容证明限制 |
| 60 | u32 | ray_reconstruction：0 关闭、1 请求启用（默认）；固定 preset F |
| 64 | u32 | reconstruction_quality：0 DLAA、1 Quality、2 Balanced、3 Performance（默认）、4 UltraPerformance |

非法版本、长度、枚举或数值拒绝整个包。冻结要求当前场景对应最近一次成功录制帧，冻结期间拒绝全部 `prime_submit`；输入帧仍须合法，native 只采用其中宽高/序号，其余使用冻结相机。场景有效期、资源上传与可变显示参数见 [渲染模式契约](renderers.md)。

OMM 不参与 transport 比较；有限模板及共享像素边界的数值取舍见 [OMM 契约](opacity-micromaps.md)。冻结期间允许切换且保留已有线性累积；启停只使镂空加速结构在后续录制时按当前设置重新绑定已有资源模板，不重新请求宿主源或改变冻结的动画时钟。
