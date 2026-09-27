# FFM ABI v2

Java 适配器与 Rust 核心作为同一构建产物配套使用。ABI 版本号用于边界校验，不承诺不同发布之间的二进制兼容；不可仅凭版本号相同混用新旧 JAR 和引擎。双 Minecraft 适配器共享该构建的同一核心，不意味着共享不同发布的内部协议。

导出原型以 [`prime.h`](../crates/prime-engine/include/prime.h) 为准。库名为 `prime_engine`，C 导出 `prime_*` 不变；26.2/26.3 适配器使用同一引擎。全部整数 little-endian，浮点 IEEE754，结构通过字节读取而非 C 对齐结构读取。长度为字节数，所有保留字段必须为零。`prime_create(2)` 返回非零 handle；status=0 成功，-1 失败。`prime_last_error` 返回 UTF-8 完整长度（不含 NUL），输出容量允许时写入终止 NUL。

## 公共头（24 字节）

| Offset | 类型 | 语义 |
| --- | --- | --- |
| 0 | u32 | magic `0x54505250`，字节 `PRPT` |
| 4 | u32 | ABI version=2 |
| 8 | u32 | operation |
| 12 | u32 | reserved=0 |
| 16 | u64 | epoch；0 无效 |

## 场景操作

- **1 reset**：仅头部，epoch 必须严格增加；清空几何、纹理与 tombstone。
- **3 remove section**：头后 `section:u64, revision:u64`；删除全部层并撤销该段可用性，记录 tombstone。revision 必须晚于该段已有操作与完成水位。
- **4 texture**：头后 `id:u32, width:u32, height:u32, reserved:u32`，然后恰好 `width*height*4` 字节源编码 RGBA8。id=0 保留给白纹理，UINT32_MAX 保留给实例继承标记；两者均不可上传。id=1 是当前 block atlas。
- **6 dynamic snapshot**：原子替换原始动态回退几何，格式见下文；序号独立于静态区块 revision。
- **7 instance delta**：原子发布局部几何原型及实例增量，格式见下文；与 op6 分别维护序号和场景。
- **8 replace section**：一次原子替换 section 全部层；源操作顺序与内容 revision 分离，格式见下文。
- **9 retire textures**：源 owner 释放纹理，实际回收还须等待场景引用消失。
- **10 section completion**：生产者完成水位，允许回收已不可能被迟到工作引用的历史。
- **11 remove sections**：同一包批量撤销多个 section，完整验证后发布。

ABI v2 删除无生产消费者的 op2，未知操作及历史 ABI 直接拒绝。

## 公共顶点与材质语义

各源 span 显式给出顶点数量、stride 和字段 offset；数据为恰好 `vertex_count*stride` 字节。topology=3 表示三角形、4 表示四边形；material=0 opaque、1 alpha cutout、2 stochastic alpha coverage，其他值拒绝。

Position 为 f32×3，UV 为 f32×2。当前两个 Java 适配器的静态地形和流体都输出 **stride24、position0、color12、uv16**；RGBA8 按 R/G/B/A 字节顺序保存作者颜色与源 tint 的编码域组合，排除原版 AO/方向明暗和 UV2 光照。source layer 使用公共协议常量 opaque=0、cutout=1、translucent=2，不传 MC enum ordinal。显式 stride/offset 解码器也支持 BLOCK28 等合法源布局，并有行为回归测试；这不表示兼容旧 ABI。quads 在 Rust 展开为 `(0,1,2), (2,3,0)`。

## 原子 section 替换

op=8 固定头共 **72 字节**：公共头后为 `section:u64`（24）、`sequence:u64`（32）、世界原点 `f64×3`（40/48/56）、`layer_count:u32`（64）、`reserved:u32=0`（68）。sequence 必须非零，并晚于该 section 的全部已有层、完整操作屏障以及 op10 完成水位。

每层为 **40 字节描述 + 紧接的顶点字节**，无 padding。十个 u32 依次是 `layer_id, texture_id, material, topology, vertex_count, stride, position_offset, color_offset, uv_offset, reserved=0`。顶点及材质遵循上述公共语义，layer ID 不可重复。`layer_count=0` 清空几何并发布一个已完成的空 section，不能代替 op3 卸载；零顶点层等价于该层缺失。

完整包、最终容量及引用验证成功后才原子发布。源序列始终推进；三角形、源 RGBA/UV、纹理/材质与原点逐字段相同的层保留原 Arc 和内容 revision，在可用性不变时不使渲染 scene 失效。首次完成的空段也会推进 scene revision，因为它可能使 64 段单元完整。忽略的布局 padding 与层顺序不参与内容身份。移除的层被清除，只有新增或变化层发布新内容。这个序列屏障也约束 op3/op11，不能混用旧序列复活遗漏层。适配器用一个 op8 代替先 remove、再逐层 upsert。CPU 持有逐段快照；64 段就绪门槛和后续整格替换见 [空间合批](spatial-batching.md)。

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

每个 span 是 **32 字节描述 + 紧接的顶点字节**，span 之间不填充。描述按顺序为八个 u32：`texture_id, material, topology, vertex_count, stride, position_offset, color_offset, uv_offset`。布局约束与静态 mesh 相同；material 只接受 0/1/2，不能按位组合。适配器保留实际源格式和拓扑，不在 Java 展开三角形。当前动态原点为相机世界位置，顶点为已执行模型变换的相机相对世界坐标，尚未应用视图旋转。

整个包验证成功后才替换旧快照；尾随字节、缺失纹理、旧 epoch/sequence 或中间 span 无效均不改变已有场景。`span_count=0` 是有效的清空操作，防止对象消失后留下旧几何。texture_id=0 仅表示明确的无纹理白色，非零引用必须在提交快照前上传。静态区块与动态快照不共用对象 ID；动态更新不递增静态 revision，也不重新翻译静态 mesh 表。

Java `DynamicFrame` 保留一个按需增长的 confined native arena，相邻同描述 span 可合并。FFM 每帧借用一次 sealed segment；Rust 在返回前完成解码并拥有结果；raw 使用可复用 Vec 工作区，发布后通过 Arc 保持只读。仅在真实消费者全部释放后才重新借用工作区，失败包不覆盖旧帧。借用只覆盖该次同步调用，不能跨下一次 `begin`、扩容或 `close`；GPU 完成与这段源字节的寿命无关。源纹理变化仍通过独立 op=4 增量提交。

material=2 表示随机 alpha 覆盖：alpha=0 不遮挡，alpha=1 完全覆盖，中间值按覆盖率接受交点；接受后仍使用当前表面材质。它不是折射、介质吸收或物理透射率。

## 原型与实例增量

op=7 的固定头共 **48 字节**，公共头后为 `sequence:u64`，以及四个 u32 计数：`prototype_upserts, prototype_removes, instance_upserts, instance_removes`。四组记录紧接在后，顺序与计数一致，无 padding。sequence 在当前 epoch 内严格递增、非零，**本批每条记录的 revision 必须等于 sequence**。同一类身份不能在一批中重复或同时 upsert/remove；原型与实例各有自己的非零 u64 身份空间。

原型 upsert 先写 `id:u64, revision:u64, span_count:u32, reserved:u32=0`，再跟 op6 格式的 span 和局部顶点字节；原型必须非空。移除记录统一为 `id:u64, revision:u64`，共 16 字节。

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
| 100 | u32 | reserved |

原生在相机、场景、尺寸变化或 sample index=0 时重置累积；其余样本计数由 renderer 自己维护。

## 宿主 Vulkan 生产路径

创建 session 后、首次渲染前调用一次 `prime_attach_vulkan(handle, descriptor, 48)`。descriptor 不是场景操作包；布局如下：

| Offset | 类型 | 借用句柄 |
| --- | --- | --- |
| 0 / 8 | u64 | instance / physical device |
| 16 / 24 | u64 | device / graphics queue |
| 32 | u64 | 宿主提交完成 timeline semaphore |
| 40 / 44 | u32 | queue family index / reserved=0 |

调用方须已在实际逻辑设备启用 buffer device address、acceleration structure、ray query 与所需扩展，并保证 queue family 支持 graphics+compute。仅查询物理设备支持不够。Rust 不销毁这些宿主对象，也不为 PT 调用 queue submit。

每帧调用 `prime_record(handle, frame, 104, command, image, image_view, serial)`。command 是已开始录制、尚未结束的宿主 primary command buffer；目标为带 STORAGE 用途、GENERAL layout 的 RGBA8_UNORM 主颜色图像及其 view，尺寸必须等于 frame。serial 是将包含此 command 的实际提交完成值，同一 session 每个 serial 最多录制一次。宿主在同队列依次提交，并在所有命令完成后 signal timeline 到该 serial；Rust 自己的描述符槽与退休资源依赖此保证。

`prime_record` 同步完成输入复制与命令录制，正常返回时 GPU 可以尚未执行；没有像素返回。只在所有描述符槽仍在途时等待最旧 serial，这是 GPU 资源复用的必要完成等待，不是向 MC 上游施加工作配额或主动背压。shader 在 GPU 上处理宿主输出行方向。`prime_gpu_time(handle)` 返回最近一个已收集的完成帧 GPU 纳秒数；未启用 `PRIME_PROFILE=1` 或没有结果时为 0，不是当前 CPU 调用耗时或窗口平均。

调用 `prime_destroy` 前宿主必须提交所有已录制的 PT command；native 等待最后相关 serial 后销毁 PT 资源。若无法证明完成，返回失败并保留 session/资源以隔离风险。宿主 device、timeline、图像等必须覆盖其全部使用寿命。

## 同步诊断路径

不 attach 宿主的 session 可调用 `prime_render`：创建独立设备，同步返回恰好 `width*height*4` RGBA8 字节，左上角首像素、行连续无 padding。此接口仅供离线图像与 FFM 行为测试，不能在宿主 session 上调用，也不能代表生产路径性能。游戏合成不使用该接口。

## 边界与容量

全场景三角形总量不再受 800 万或带符号 32 位上限约束。64 位宿主累计地形、op6 回退与 op7 原型的唯一几何数量及派生字节，同一原型不按实例数重复计费；超过宿主可寻址字节范围明确失败。`count:u32` 仍描述单个 span，单包仍限 256 MiB；大量独立 section/prototype 分批发布，与一个含数十亿顶点的连续包是不同契约。

当前仍明确拒绝：超过 512 MiB 的纹理总量、各超过 262144 的常驻原型或实例、超出每轴 1..65536 或设备 image/dispatch/累积 storage range 的输出，以及不有限/越域位置、未知 flags、未知拓扑和尾随字节。op6 是完整回退帧，op7 是一批原子增量，op8 是完整 section 替换；它们尚未提供跨包事务，不能用重复替换包伪装无界分页。GPU 的局部 AS/实例索引、设备内存分配数量与实际内存也构成独立边界。以上不表示已经支持任意视距、任意单资源或任意驻留总量。协议验证失败不修改场景 revision 或已有数据；部分分配使用 `try_reserve`，尚不能保证所有 Rust 系统内存耗尽均可恢复。

当前 Slang 局部指针下标以 32 位字节偏移计算，单个被寻址的材质范围最多 `2^25` 条 128 字节记录；各局部范围的设备基址为 64 位，总量可以跨页。翻译层按此局部上限划分寻址范围：静态增加同一 BLAS 内的 geometry，raw 回退拆成多个 BLAS；单个共享原型超限仍明确失败，不能让乘法回绕，也不能以此单资源限制代替全场景数量契约。实例展开后的三角形统计使用 u64，GPU 帧参数只传“是否有几何”，避免大计数收窄后误判为空场景。

非空静态层、raw 和原型/实例在提交前必须上传引用的纹理；缺失引用明确报错。所有调用必须在创建 handle 的 OS 线程进行。`prime_destroy` 退休身份，重复释放会报错。调用方必须保证原生指针指向有效读写区域；长度校验不能验证任意地址。

## 设置包（独立 schema v1）

`prime_configure(handle, data, length)` 借用恰好 48 字节，返回前解析，不保留指针。它没有场景命令头；设置版本独立于场景 ABI。宿主模式改变时必须先提交 encoder，再从外层帧边界调用；native 等待旧 GPU 使用完成，释放旧模式资源后创建新资源。普通显示控制变化不需要切换等待。

| Offset | 类型 | 字段 |
| --- | --- | --- |
| 0 / 4 | u32 | settings version=1 / mode（0 实时、1 离线） |
| 8 / 12 | u32 | 最大路径顶点数 / 离线每帧样本数，均为 1–64 |
| 16 / 20 / 24 | f32 | 曝光乘数 `[1/4096,4096]` / hue `[0,1]` / saturation `[0,0.5]` |
| 28 | u32 | view：0 最终输出、1 噪声色、2 线性深度、3 世界法线 |
| 32 / 36 | f32 | 太阳 / 天空乘数，均为 `[1/256,256]` |
| 40 | f32 | 深度预览范围 `[1,4096]`，不影响实际深度 |
| 44 | u32 | 采样 seed，Java 当前固定 `0x13572468` |

非法版本、长度、枚举或数值拒绝整个包。冻结要求当前场景对应最近一次成功录制帧，冻结期间拒绝全部 `prime_submit`；输入帧仍须合法，native 只采用其中宽高/序号，其余使用冻结相机。场景有效期、资源上传与可变显示参数见 [渲染模式契约](renderers.md)。
