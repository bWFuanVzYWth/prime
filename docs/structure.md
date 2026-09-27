# 多版本工程结构

`prime_engine` 负责场景、渲染会话和资源生命周期，C ABI 使用 `prime_*` 导出。Minecraft 版本差异留在 Java 适配器，Rust 不按 MC 版本编译。

```text
adapters/
  common/                 纯 Java 25：设置、FFM、封包、源顶点编码、共同测试
  mc-26.2/                26.2 Fabric / Blaze3D 捕获与 Vulkan 宿主适配
  mc-26.3/                26.3 Fabric / RenderPearl 捕获与 Vulkan 宿主适配
gradle/minecraft-adapter.gradle   共用构建、打包和开发运行约定
crates/
  prime-scene/            无 GPU 依赖的输入验证、源身份、场景编译与快照
  prime-engine/           FFM 导出、线程受限会话、场景/renderer 生命周期
  prime-vulkan/           Vulkan 资源、AS、命令、同步、退休与 Slang
  prime-tools/            原生 1080p 诊断和宿主路径性能夹具
  rectangle-decomposition/ 体素引擎矩形分解，独立 CPU 算法
```

## 依赖与所有权

```mermaid
flowchart TD
    J2[mc-26.2] --> J[common: FFM + source protocol]
    J3[mc-26.3] --> J
    J -->|同一 prime_engine 动态库| E[prime_engine]
    E --> S[prime_scene]
    E --> V[prime_vulkan]
    V --> S
    T[prime_tools] --> V
    T --> S
    R[rectangle_decomposition: 独立可复用算法]
```

`prime_scene` 不认识 Minecraft、FFM 指针或 Vulkan。它验证输入，再发布带 epoch/revision 的源快照，进行三角化与大坐标重定位。当前规模适合把协议和 CPU 编译放在一个 crate，模块边界已经分开；不为少量类型再加多层转发 crate。

`prime_engine` 是 native 入口和会话所有者，协调源状态、翻译缓存和 renderer。`prime_vulkan` 拥有具体 GPU 资源与其退休规则；宿主 instance/device/queue/image 始终由 Minecraft 拥有。引擎默认启用 `vulkan` feature；只测试协议和引擎错误边界时可关闭它，完全不编译 Slang。单独选中 `prime_vulkan` 则必然需要 GPU 构建工具，但其普通 CPU 单测无需实际创建 GPU 设备。

实例通路明确分离状态和副作用：`prime_scene::instances::InstanceContext` 拥有版本无关的原型、实例和引用计数，先准备验证计划再应用；`prime_scene::spatial` 定义统一空间网格，`prime_scene::translation` 的两个显式上下文分别计算地形合批与对象分桶/放置。`prime_vulkan` 的 `geometry` 和 `context::objects` 持有 GPU 资源并执行计划；`plan` 保留局部范围分配与元数据编码，`packing` 写入复用的材质记录，`arena` 依据完成值管理 storage/scratch/上传页。每个会话显式借用这些上下文，未引入异步任务或内部可变的全局缓存。此边界不表示旧有全部 renderer 模块已经完成相同拆分。

`prime_scene::workers::CpuWorkers` 封装私有同步 Rayon 池，由当前 renderer 持有并显式借给打包工作；worker 仅获得只读源数据与互不重叠的输出切片。`adapters/common::SynchronousWorkers` 提供同步汇合与私有工作区的通用机制；MC compiler、邻接快照和回调线程规则仍留在版本层。`ColumnWindow` 只表达整数列矩形，方块实体能力索引不进入 common。

`prime_tools` 持有离线 PNG 输出与性能夹具入口，图像编码库不进入引擎 DLL 的依赖。诊断用 C 导出 `prime_render` 仍存在，实际游戏只调用宿主录制接口，不回读输出。

`adapters/common` 的生产代码不依赖 Minecraft、Fabric 或 LWJGL。它持有版本无关的设置和源描述，并封装 FFM ABI，不能增加 MC enum ordinal、宿主私有类或 shader buffer 布局。版本模块负责实际 MC 模型/tint 调用的观察、区块任务/epoch、纹理来源、相机和宿主 Vulkan 特性与句柄。复制少量版本适配代码比把变化的私有签名装进反射层更容易编译检查；新版本通过增加模块验证，不能更改 Rust 使其识别版本号。

两个安装包都打入公共层的同一编译产物和同一 `target/release/prime_engine.dll`。`verifyNativeJars` 检查引擎字节、桥接类字节和精确 MC 版本约束。每版有独立 `run/` 与存档目录，避免新版存档升级污染旧版验证。

## 捕获边界

不同源类型的目标接管位置、geometry-key 缓存契约和后续 GPU 批量工作见 [捕获边界与批量数据流](capture-boundaries.md)。以下描述已有源通路；目标边界不代表所有路径已经完成接入。

捕获在同一个真实 `SectionCompiler` compile 作用域内观察已接受 quad。最终 BLOCK mesh 的颜色已经混入原版 AO/方向明暗，不能作为未照明源颜色：

1. vanilla 保留实际 BakedQuad，并观察本次 `getTintColor` 的返回值。
2. Indigo 保存光照修改前的作者 RGBA；在转换返回后使用被接受的几何和本次原版 tint，按 MC 的编码域 8 位乘法组合。
3. 输出稳定 opaque/cutout/translucent 标识与 24 字节顶点（position 0、RGBA 12、UV 16），不输出烘焙光照。流体使用其实际输出 consumer，观察已求值 tint 排除原版方向明暗；嵌套 Fabric 默认处理器不重复捕获。Rust 使用 stride/offset 描述解析。
4. compile 开始固定 section/epoch/revision，成功返回后完整发布；`try/finally` 清理线程作用域，迟到结果受有效 token、epoch 与已完成源水位约束。

没有第二次模型随机、面剔除或 tint 查询；Java 不把颜色转换到线性空间，也不构造 GPU 材质。发布依然是变更区块级，FFM 不按 quad 调用。PT 独占时由自己的已加载区块窗口调度真实 MC compiler，编译结果捕获后释放，不创建原版地形 GPU 网格；这仍不承诺任意模组的可见性或回调均已覆盖。

标准模型捕获观察实际 `ModelPart.Cube.compile`。版本层的 `ModelGeometryContext` 识别原版不可变 Vertex 与数组引用变化；`ModelCapture` 关联实体/方块实体源、实际 Model 提交、叶节点、局部几何和已求值姿态。共享 `InstanceCapture` 管理原型、实例和封包生命周期，不依赖 MC 类型。资源变化读取顶点，稳定帧只检查源引用及实例有效值；原版 setupAnim 和渲染回调执行原来的一次。PT 独占且满足已知纯叶节点与 consumer 契约时，省去已实例化 Cube 的机械顶点展开，未知路径继续实际输出。世界源对象通过自身附加字段持有上下文，不靠 equals/hashCode 或全局实体缓存确定身份。

只有已知标准 Cube、consumer 和 UV 变换才能进入实例通路。已知相关类存在第三方 Mixin、Cube 子类、未知 consumer 或不可逆变换时保留原始几何回退；这个检测不构成任意未注解字节码变换的兼容承诺。姿态来自实际原版矩阵，源世界原点为 f64，但原版已经计算过的相机相对 f32 平移不能无损逆推；相机运动仍可能使实例记录变化，不使用容差吞掉真实微小运动。

原始回退继续观察各版本 `StagedVertexBuffer.Draw.append` 的真实批量结果。版本层绑定 Draw 与纹理/alpha，particle 使用实际 layer 图集；已经作为实例接受的顶点区间从原始包排除。`common/DynamicFrame` 复制其余源布局和顶点至复用的 native arena，相邻同描述 span 合并。整个世界准备结束后闭合捕获，进入 native hook 时先提交资源变化，再提交一个 op7 增量和必要的 op6 回退快照，无逐对象 FFI。两条通路均不重放模型、动画或粒子回调，详见 [abi.md](abi.md) 与 [architecture.md](architecture.md)。

## 矩形分解的接入范围

矩形分解复用体素引擎的 API、算法、测试和文档，来源见 [SOURCE.md](../crates/rectangle-decomposition/SOURCE.md)。它是可独立调用的 workspace 成员，目前没有运行期消费者，也未对 MC quad 开启合并。

后续调用点应在 `prime_scene` 的受约束几何编译阶段：证明共面、方向、网格对齐、材质、UV 映射、tint/alpha 等完整语义可合并后，才转换到算法要求的 64×64 带标签 sparse quad。任意模型面、斜面、不同 UV 或 tint 不能仅按 block ID 合并。较大的 scratch 在 worker 生命周期复用，不能每个任务在渲染线程分配。

构建、双版本验证与诊断入口见 [CONTRIBUTING](../CONTRIBUTING.md)。
