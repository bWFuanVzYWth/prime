# 架构与所有权

```text
Minecraft 26.2 / 26.3 / 各版本 Fabric Mixins
  实际 quad + tint / 模型局部几何与实例 / staged raw mesh / CPU texture source
          │ 原始不可变字节、源身份、相机
Java CaptureInbox + InstanceCapture + DynamicFrame → NativeBridge（Java 25 FFM）
          │ 调用期间借用；返回前所有输入完成复制
prime_engine → prime_scene: protocol → SourceScene / InstanceContext → Scene / Camera
          │ Rust 解码、三角化、坐标重定位、纹理身份绑定
prime_vulkan → Slang SPIR-V
          │ BLAS / TLAS / RayQuery / progressive path tracing
imageStore → Minecraft 主 RGBA8 图像 → hand / HUD → 宿主提交与呈现
```

## 边界

Java 保留实际接受的 quad 几何、源颜色/tint、拓扑和图集字节；封包属于捕获边界，不产生渲染材质或 GPU ABI。两个版本共享 `common` 的 24 字节顶点编码与 FFM。Rust `prime_scene::protocol` 是不信任数据到可信值的边界，验证完成后才发布更新；该 crate 不依赖 ash、Fabric 或 Minecraft。`prime_engine` 拥有会话、源状态、翻译缓存和 renderer 生命周期；`prime_vulkan` 管理具体 PT descriptor、GPU 数据布局、分配、barrier 与退休。宿主拥有 device、queue、命令池、主图像及提交。Java 的宿主适配仅协商设备特性并转交原版句柄，不翻译场景或录制 PT 渲染命令。完整目录、依赖图与矩形算法边界见 [structure.md](structure.md)。

源身份是 `(section_key:u64, layer:u32)`，不转换为浮点。世界位置保持 `f64 section origin + f32 local vertex`，在 Rust 减去相机附近的 256 格网格原点后再收窄为 f32。每个 mesh 保存不可变 `Arc<[Triangle]>`；场景快照只计算各 mesh 的相对原点并共享顶点和纹理，不重新遍历/复制全部顶点。容量计数随源操作更新；删除不存在的区块仍记录 tombstone，但不使渲染缓存失效。

实体输入分为持久原型/实例和原始几何回退两条通路。版本层在实际标准 `ModelPart.Cube.compile` 调用中观察局部几何、姿态、纹理、颜色与 UV；不可变顶点的引用发生变化时才读取并发布几何原型。实体/方块实体自身持有源上下文，实际模型提交与叶节点确定实例身份。模型、动画和渲染回调仍执行原来的一次。PT 独占世界且精确源、consumer 与纯叶节点契约成立时，发布实例后省去机械顶点展开；未知路径保留实际输出，不重播回调来猜测画面。

独立的 Fabric wrapper 几何缓存按正式 geometry-key 契约复用不可变 Mesh，命中时省去该契约允许缓存的 emit 和重复复制；本次 tint、材质标记与变换仍参与实际提交。实际 Indigo 提交的受支持单层不可变 Mesh 按 tint 分组建立局部原型；实际材质回调只执行一次，实例承载本次仿射和颜色。多层、特殊输出或未知实现局部回退。

普通 item 提交在真实 quad 输出叶节点读取本次局部位置和 UV，按材质、统一 tint 与姿态分组；内容精确相同时跨实例共享几何原型。可变源值仍每次观察，不能只凭容器身份复用。组改变或 submit 不再出现时，帧生命周期显式解除几何引用；无须等待 GC。ExtendedItem 和未识别的输出继续原始回退，详细接管范围见 [捕获边界与批量数据流](capture-boundaries.md)。

公共 `InstanceCapture` 显式持有资源、世界实例与帧封包上下文：原型按资源变化发布，实例只在出现、消失或有效值变化时写入 op7。没有变化时不产生包、不调用 FFM；一个变化帧最多一个实例增量调用。世界准备结束即闭合捕获帧，暂未进入 native hook 的脏记录保留到成功提交；只有 FFM 成功返回才确认序号和清理源字节。原型释放须等所有实例引用消失，可以在同一批中迁移实例并删除旧原型。

未知模型或 consumer、特殊 UV 包装及粒子保留实际 `StagedVertexBuffer.Draw.append(MeshData)` 的 op6 原始几何快照。已实例化的顶点范围从该快照排除；其余范围一次复制到复用的 native arena。非空回退仍逐帧捕获，变为空时提交一次清除，连续空帧不重复提交。两条输入的序号独立于地形 revision，更新不会重译静态 mesh。纹理按实际 GPU texture 身份关联 CPU 上传来源，并先于引用它的几何增量提交。

Rust `InstanceContext` 先借用旧状态验证整批及最终引用关系，再显式修改持久场景；只访问变化记录和受影响原型的引用计数，不复制全实例表。解码记录与引用计划使用上下文持有的连续工作区，容量跨提交复用；在工作区内排序、检查重复及更新/删除冲突，不依赖线序。相同原型的姿态更新不产生无效的引用减增，失败也清除未发布值并保留可复用容量。常驻映射依然有查找和写入成本，这不是 O(1) 的全量更新。每条记录的 revision 等于该批 sequence，整体严格有序，因而无需永久保存死亡实体 ID。常驻容量有界，持续出生/删除不会仅因历史身份增长而耗尽容量。GPU 无关的 `plan` 计算几何和实例变化，`context::objects` 执行资源分配、上传和 AS 命令；两者通过显式借用协作，不引入后台任务或共享可变缓存。

资源重载与世界切换推进 epoch。捕获开始时记录 epoch 和源序列；完成时再次验证。旧 epoch、已卸载 chunk 的 worker、早于已发布序列的结果会被丢弃。op8 一次原子替换 section 的全部层，空结果清除旧层。native 完整验证后再发布，并保留完整操作的序列屏障，阻止旧层被迟到结果复活。

## 规模与容量契约

顶点、三角形和光源的逻辑数量与标识空间至少覆盖 `2^31` 个元素。CPU 场景总量、字节数、前缀和与实例展开后的统计使用 64 位计数；当前 native 仅面向 64 位宿主，源容器的 `usize` 因而为 64 位。GPU 页内索引、单个 BLAS 的 primitive 数和单包 count 是局部字段，不能作为全场景上限，也不能把带符号 32 位计数或 TLAS custom index 当作全局三角形地址。

数量能力与实际驻留预算分别处理：按当前布局，`2^31` 条唯一三角形的 CPU 源记录约需 232 GiB，GPU 材质记录需 256 GiB，尚未计入 AS、上传、纹理和在途旧资源。按需分配与分批提交不得预分配整个逻辑空间；超出真实资源预算必须显式失败或采用已声明的驻留策略，不能静默删几何、降低视距或改变可见性。地址与计数测试不等于这些数据已在单机同时驻留或达到性能目标。

当前单包、完整动态快照、常驻身份数、源覆盖调度与单个 AS 仍有各自限制，具体见 [ABI 容量](abi.md#边界与容量)。不能将多个“替换快照”包当成追加包绕开单资源限制，扩展时需要明确分块与原子发布语义。显式光源集合尚未实现；其未来接口遵守同一数量契约，目前固定太阳/天空不构成海量光源支持。

## 互斥的世界渲染器

原版与 Prime 各后端都是可选的世界渲染器。可选实现可以有多个，存活资源所有者最多一个。当前注册项为 `vanilla` 和 `path_trace`；新增后端注册惰性工厂，禁止为了切换速度预先创建第二套场景或 GPU 资源。公共 `RendererSlot` 管理 EMPTY、STARTING、ACTIVE、STOPPING、BLOCKED，工厂只构造轻量所有者，资源在 `start` 中创建。

切换在 `GameRenderer.extract` 开始、宿主提取本帧世界状态之前执行：停止旧后端接收工作，证明 CPU 消费者结束和 GPU 提交完成，关闭旧后端专属资源，再启动新后端。同一帧的提取与绘制必须属于同一后端；若等到 `render` 开始才切换，新原版后端将缺少当帧提取准备的 ViewArea 和可见状态。任何退休失败都会保留旧所有者并阻断后续创建；不能把捕获异常、Java 方法返回或经过几帧当成销毁证明。渲染中途出错只申请下一帧切换，不在活跃 render pass 内提交，也不重放该帧回调补画。

共享宿主设备、队列、窗口、主输出、手部/HUD 资源及仍被真实源对象引用的不可变数据。原版的地形 dispatcher、ViewArea 与世界 GPU 网格归原版；Prime 的源编译上下文、FFM 场景、BLAS/TLAS、材质与累积资源归选定后端。Prime 之间可共享明确的规范源资源上下文，不能共享未声明的后端私有翻译缓存。进入原版时释放 PT 自持的纹理副本和 geometry lookup；宿主仍借用的不可变 Mesh 按最后源消费者释放，不为清缓存复制或破坏宿主结果。

PT 自己按已加载 chunk 和相机覆盖窗口调度真实 `SectionCompiler`，复用 builder，使用有界批次及软时间预算。原版 dispatcher/世界网格上传停止。世界动态准备仍执行真实 feature 回调；staged 输出最后一个 builder 完成并被同步捕获后，释放 CPU 结果，省去这批世界顶点的原版 GPU 上传。手部和 HUD 不使用该省略规则。等待资源或失败阻断期间仍由原所有者占据世界输出，不能调用已经释放的原版 ViewArea。

恢复原版时，在新 ViewArea 和遮挡图建立后，从实际 `ClientChunkCache` 一次性补入已加载列与空 section，再继续正常增量通知。旧遮挡图会清除这些状态，PT 期间又可能消费源更新日志，因此不能仅依赖切换之后的新事件恢复已有地形；此快照不增加稳态逐帧扫描。

纹理来源来自真实 CPU 上传。资源上下文与世界实例的有效期分开；标题界面的资源重载也更新仍保留的源图集。进入原版释放源副本后，再切回 Prime 会请求一次真实资源重载，完成前不消费旧像素或从 GPU 回读。

宿主保留的 `DynamicTexture` 不一定随资源重载重新上传。对已核验的皮肤、缺失纹理和地图来源，版本层可保留宿主所有者的弱关联、来源资格与真实上传证明；在 PT 缺少副本时，借其已证明有效的 CPU 源重新建立副本。地图在实际更新回调完成后才满足该条件。外部取得可变像素别名或替换像素会撤销恢复资格，后续一次上传不能证明别名已消失。原版模式下不保留 PT 像素数组；通用 `getPixels()`、同一图像引用或未关闭状态均不足以证明最后上传内容，未知来源不得据此恢复。

## 增量 GPU 场景

后端按世界空间 64 格网格及 opaque/cutout/alpha 材质分组。每个簇拥有局部坐标 BLAS；TLAS 实例变换负责簇原点到帧 anchor 的平移。缓存签名包含源 mesh 身份、revision 与簇内偏移，epoch 变化会清空簇身份。anchor 变化只重建 TLAS，不重建静态 BLAS，也不上传静态顶点或整份材质。

源操作序列与几何内容世代分离。op8 将新解码内容与原层逐字段、按浮点位模式比较；相同几何、材质、原点和属性保留旧 Arc 与内容 revision，只有真实变化层使所属簇失效。不用哈希相等代替内容证明；重复操作也不请求清空采样历史。比较本身仍需解码和遍历本次源数据，不能把避免 BLAS 重建称为零 CPU 成本。

材质记录保存在按需分配的设备地址页中，普通页为 64 MiB，较大的单个局部几何可使用较大页。页内区间删除后合并复用；区间碎片不足时另开页，不搬迁全部保留材质或重建无关 BLAS。各 owner 显式持有页与分配表，当前空页保留供复用，随 owner 退休，因此仍有历史峰值占用。

TLAS custom index 的低 23 位仅索引簇/实例元数据，不再编码全局三角形起点；`0x00800000` 区分对象实例。静态表每簇保存 8 字节设备地址，对象元数据保存 64 位材质地址及纹理、颜色、alpha 和 UV 覆盖。Slang 通过 `PhysicalStorageBuffer` 指针及局部 primitive index 读取 128 字节三角形记录。材质总量可跨多个 buffer，不受一个 storage descriptor 的范围限制；表项数、单个 AS 和实际设备内存仍有限制。

脏静态簇每批最多 32 个，将材质复制、独立 BLAS 构建和 TLAS 构建录入同一个宿主命令缓冲。批次之间不提交、不等待；barrier 保护旧帧读取与后续区间覆写，地址页按实际 GPU 完成值退休。创建资源前检查 PT 已知的设备内存分配数量；这不包含宿主自己的分配，当前也没有统一 AS/上传内存池。

每个局部几何原型拥有一个持久 BLAS，多个 TLAS 实例共享它。出现、消失、姿态或材质覆盖变化不上传原型三角形、不重建原型 BLAS。Shader 使用实际 object-to-world 仿射矩阵恢复表面顶点和法线，支持非均匀缩放、剪切与镜像；矩阵必须有限且可逆。世界原点保持 f64，GPU 实例变换相对帧 anchor；重定位仅更新放置，不重建局部几何。

原始回退按三角形世界重心所在的 16 格网格及材质分类分桶，逐字段比较内容，仅变化桶上传和重建 BLAS。CPU 计划上下文持有活跃桶的连续数组，按实际桶内顺序比较、覆写或截短，消失的桶立即移除；不为每次快照重建整份桶数组或复制成新的 Arc。执行阶段同步借用这些数组，打包并复制到所属上传槽后结束借用，GPU 不引用 CPU 桶内存。长三角形不裁切，仍可能跨桶边界。回退快照每次仍需全量 CPU 解码、分桶与比较，不能等同于持久实例通路。原型和桶共享 GPU 三角形 arena；BLAS storage/scratch、TLAS 容量与在途 staging 尽量复用，旧资源依完成值退休。

场景/实例/anchor 均不变时，native 计划阶段直接返回，不扫描实例、不更新 AS。只有 raw 桶变化时复用已验证的原型实例姿态前缀，不重新计算它们的仿射和包围盒；原型资源、实例或 anchor 变化仍更新该前缀。对象放置变化时目前仍重写全部对象元数据与 Vulkan 实例输入并 BUILD 综合 TLAS；每个对象对应 48 字节元数据和 64 字节 AS 实例输入，这一部分仍为 O(常驻实例数)。实例缓存和上传批量化不代表变化帧 GPU 成本仅与变化数相关。

Buffer 完成绑定、AS 完成创建后由资源所有者保存对应设备地址；当前资源没有重绑或内部搬迁，读取地址不再逐实例调用驱动。新建或替换资源取得新地址，销毁后不保留可用地址引用。帧槽选择复用本次已查询的真实 timeline 完成值，槽不足时再执行已有的有界等待，不能以缓存的完成值推断尚未完成的提交。

纹理元数据与 texel 位于 device-local buffer，同一 epoch 内索引稳定。新增实体纹理不重排旧索引、不重传整张地形图集，也不重建静态 BLAS；只上传变化内容，容量增长时用 GPU 复制旧区域。旧 buffer 依宿主完成值退休。CPU source 缓存与 native texture 槽位的容量管理仍有界，不能据此宣称支持无限动态纹理 churn。

Opaque 几何由硬件直接接受命中；cutout 和 alpha 覆盖执行候选命中检查，实例覆盖参与有效材质选择。太阳遮挡使用 accept-first-hit 查询，遵循同一 alpha 语义。Ray Query 仍使用硬件光追，不存在 CPU 三角形遍历路径。

累积位于 renderer 自己的显存 buffer；Slang 直接向宿主 RGBA8 storage image 写最终颜色。主图像保持宿主的 GENERAL layout，前后 barrier 衔接原版图像访问与手/HUD。生产路径没有输出 buffer、回读 buffer 或整帧复制。独立 `prime_render` 诊断接口仍可同步回读 PNG，Java 桥保留该诊断绑定，但游戏合成不调用它。

Java 使用宿主 transient command buffer，将 native 录制结果交还 `encoder.execute`，最终由 Minecraft 在既有提交中执行。Rust 借用宿主 timeline，三个描述符槽只在原 serial 完成后复用；槽耗尽才等待最旧提交形成有界反压。小型帧参数使用命令内 push constants，宿主 image view 的 descriptor 每帧重写；场景或累积资源变化会清掉描述符缓存，避免句柄复用误判。稳定路径无逐帧完成等待、无 PT 专用队列提交。

世界输出由选定所有者决定，不以首帧完成为条件同时维持两个后端。PT 接管时原版世界 FrameGraph、网格上传与其地形调度已退出；动态源准备与后续手部/HUD 继续使用宿主流程。首帧 GPU 完成回调用于诊断，不能作为资源回收以外的“两个后端可共存”许可。当前未实现完整世界深度等价。详细数据流见 [pipeline.md](pipeline.md)。

## 生命周期与线程

- `CaptureInbox` 以自身 monitor 序列化队列、epoch 和 revision。每次真实 compile 有独立 worker 捕获作用域，只观察该次实际 quad/tint 调用；成功返回后封包，render thread 取走不可变批次。异常与正常返回都清理线程作用域；边界不持有 native/GPU 对象。
- 标准模型资源缓存以 Cube 弱身份关联已观察几何，在帧边界显式处理 ReferenceQueue，避免临时 Cube 永久积累；该操作只释放 CPU 源所有权。存活实例引用仍保留原型，GPU 资源另依 timeline 退休，不能以 GC 通知充当 GPU 完成证明。
- FFM confined arena 可重复使用：帧与错误缓冲固定分配，输入 staging 按需增长。Rust 在调用返回前复制所有输入，不保存 Java 地址；GPU 执行异步不延长输入字节借用期。生产 FFM 不传输输出像素。
- native handle 存在创建 OS 线程的 TLS 表中。全局原子计数器仅分配不可复用身份；跨线程、已释放或伪造 handle 明确失败。没有全局共享可变场景。
- native DLL 具有进程生命周期，session 仍显式销毁。这样异常隔离中的 driver/debug 回调不会跳转到已卸载的 Rust 代码。
- 被替换的 GPU buffer、AS 和上传暂存资源保留至最后关联的宿主 timeline serial 完成。场景更新可以在先前帧在途时录制；同队列 barrier 保护持久 arena 的原位更新。关闭时 Java 先提交尚未入队的宿主命令，再等待 PT 最后 serial；之后才能销毁 descriptor/pipeline 等资源，宿主设备继续有效。
- GPU 操作失败或 panic 会将 session 和发生原位更新的 renderer 标为不可继续使用。FFI 捕获 Rust unwind，错误文本在调用线程读取；非法外部指针仍是 C ABI 调用方违约，无法由 Rust 验证。
- 宿主 timeline 等待有界；若无法证明完成，则保留 native session 并隔离可能在途的 GPU 对象至进程退出。借用模式不会调用 device/queue wait-idle 或销毁宿主设备；已隔离对象不重复执行等待。此异常策略不能替代正常 timeline 回收。

## 当前渲染范围

路径追踪使用硬件 Ray Query、Lambert 材质、线性 Rec.709 工作空间、固定太阳/梯度天空、四次反弹和 Reinhard 显示映射。纹理使用图集 UV、动画首帧与基础 mip 最近点采样；源 RGBA 与 tint 先按 Minecraft 编码域语义组合，shader 再进行所需的线性化。

场景覆盖依赖原版可见性和准备过程。地形接收 opaque/cutout/translucent 与流体几何；动态接收常规模型、方块实体、物品、自定义几何的支持布局及 quad 粒子。已混入 CPU 光照的 moving/falling block、leash，以及文字、glint、outline 等特殊路径尚不作为普通表面材质接收；不能将批量入口等同于所有模组渲染器兼容。

cutout 当前使用固定 0.1 阈值；alpha 材质按源 alpha 随机覆盖，接受后仍是 Lambert 表面，没有水/玻璃折射或介质吸收。主射线和阴影使用同样覆盖语义，同射线的量化交点哈希使流体重合正反面共享判定；这也会关联几何重合而语义不同的透明面，是当前近似边界。动态更新重置累积但继续改变采样随机种子，避免拖影与冻结噪声；尚无动态重投影、降噪。完整动态纹理、PBR 和 HDR 仍待实现。未完成事项见 [HACK.md](../HACK.md)。
