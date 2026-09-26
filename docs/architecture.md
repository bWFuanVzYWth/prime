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

实体输入分为持久原型/实例和原始几何回退两条通路。版本层在实际标准 `ModelPart.Cube.compile` 调用中观察局部几何、姿态、纹理、颜色与 UV；不可变顶点的引用发生变化时才读取并发布几何原型。实体/方块实体自身持有源上下文，实际模型提交与叶节点确定实例身份。模型、动画和渲染回调仍执行原来的一次，原版顶点输出也保留；优化减少 PT 的重复捕获，不通过跳过回调猜测画面。

公共 `InstanceCapture` 显式持有资源、世界实例与帧封包上下文：原型按资源变化发布，实例只在出现、消失或有效值变化时写入 op7。没有变化时不产生包、不调用 FFM；一个变化帧最多一个实例增量调用。世界准备结束即闭合捕获帧，暂未进入 native hook 的脏记录保留到成功提交；只有 FFM 成功返回才确认序号和清理源字节。原型释放须等所有实例引用消失，可以在同一批中迁移实例并删除旧原型。

未知模型或 consumer、特殊 UV 包装及粒子保留实际 `StagedVertexBuffer.Draw.append(MeshData)` 的 op6 原始几何快照。已实例化的顶点范围从该快照排除；其余范围一次复制到复用的 native arena。非空回退仍逐帧捕获，变为空时提交一次清除，连续空帧不重复提交。两条输入的序号独立于地形 revision，更新不会重译静态 mesh。纹理按实际 GPU texture 身份关联 CPU 上传来源，并先于引用它的几何增量提交。

Rust `InstanceContext` 先借用旧状态验证整批及最终引用关系，再显式修改持久场景；只访问变化记录和受影响原型的引用计数，不复制全实例表。每条记录的 revision 等于该批 sequence，整体严格有序，因而无需永久保存死亡实体 ID。常驻容量有界，持续出生/删除不会仅因历史身份增长而耗尽容量。GPU 无关的 `plan` 计算几何和实例变化，`context::objects` 执行资源分配、上传和 AS 命令；两者通过显式借用协作，不引入后台任务或共享可变缓存。

资源重载与世界切换推进 epoch。捕获开始时记录 epoch 和 revision；完成时再次验证。旧 epoch、已卸载 chunk 的 worker、早于已发布 revision 的结果会被丢弃。一个区块队列项包含先删除再发布其所有层，渲染只在整项提交后发生。空结果因此也能清掉旧层。native 侧维护区块 tombstone，阻止遗漏层被迟到结果复活。

## 增量 GPU 场景

后端按世界空间 64 格网格及 opaque/cutout/alpha 材质分组。每个簇拥有局部坐标 BLAS；TLAS 实例变换负责簇原点到帧 anchor 的平移。缓存签名包含源 mesh 身份、revision 与簇内偏移，epoch 变化会清空簇身份。anchor 变化只重建 TLAS，不重建静态 BLAS，也不上传静态顶点或整份材质。

当前地形发布先删除 section，再发布其全部 layer，源 revision 直接参与簇签名。因此原版重新编译即使得到相同的未照明几何，也会使所属簇失效；目前未将源操作顺序和几何内容世代分离。这个地形限制与 op7 实例增量独立，不能从实例稳定帧的优化推断地形重编译同样会被去重。

材质记录保存在可增长的显存 arena 中。簇删除后回收、合并空闲区间；TLAS instance custom index 的低 23 位给静态簇编码材质起点，Shader 与 primitive index 相加。最高位 `0x00800000` 标记对象实例，低位改为索引独立实例元数据，其中保存原型材质起点及纹理、颜色、alpha 和 UV 覆盖。脏静态簇每批最多 32 个，将材质复制、独立 BLAS 构建和 TLAS 构建录入同一个宿主命令缓冲。批次之间不提交、不等待，依赖由 barrier 建立；arena 扩容复制与后续脏区间写入也明确建立 transfer write 依赖。创建资源前检查 PT 已知的设备内存分配数量；这不包含宿主自己的分配，当前也没有统一 AS/上传内存池。

每个局部几何原型拥有一个持久 BLAS，多个 TLAS 实例共享它。出现、消失、姿态或材质覆盖变化不上传原型三角形、不重建原型 BLAS。Shader 使用实际 object-to-world 仿射矩阵恢复表面顶点和法线，支持非均匀缩放、剪切与镜像；矩阵必须有限且可逆。世界原点保持 f64，GPU 实例变换相对帧 anchor；重定位仅更新放置，不重建局部几何。

原始回退按三角形世界重心所在的 16 格网格及材质分类分桶，逐字段比较内容，仅变化桶上传和重建 BLAS。长三角形不裁切，仍可能跨桶边界。回退快照每次仍需全量 CPU 解码、分桶与比较，不能等同于持久实例通路。原型和桶共享 GPU 三角形 arena；BLAS storage/scratch、TLAS 容量与在途 staging 尽量复用，旧资源依完成值退休。

场景/实例/anchor 均不变时，native 计划阶段直接返回，不扫描实例、不更新 AS。实例变化时目前仍重写全部对象元数据与 Vulkan 实例输入并 BUILD 综合 TLAS；每个对象对应 32 字节元数据和 64 字节 AS 实例输入，这一部分仍为 O(常驻实例数)。实例缓存和上传批量化不代表变化帧 GPU 成本仅与变化数相关。

纹理元数据与 texel 位于 device-local buffer，同一 epoch 内索引稳定。新增实体纹理不重排旧索引、不重传整张地形图集，也不重建静态 BLAS；只上传变化内容，容量增长时用 GPU 复制旧区域。旧 buffer 依宿主完成值退休。CPU source 缓存与 native texture 槽位的容量管理仍有界，不能据此宣称支持无限动态纹理 churn。

Opaque 几何由硬件直接接受命中；cutout 和 alpha 覆盖执行候选命中检查，实例覆盖参与有效材质选择。太阳遮挡使用 accept-first-hit 查询，遵循同一 alpha 语义。Ray Query 仍使用硬件光追，不存在 CPU 三角形遍历路径。

累积位于 renderer 自己的显存 buffer；Slang 直接向宿主 RGBA8 storage image 写最终颜色。主图像保持宿主的 GENERAL layout，前后 barrier 衔接原版图像访问与手/HUD。生产路径没有输出 buffer、回读 buffer 或整帧复制。独立 `prime_render` 诊断接口仍可同步回读 PNG，Java 桥保留该诊断绑定，但游戏合成不调用它。

Java 使用宿主 transient command buffer，将 native 录制结果交还 `encoder.execute`，最终由 Minecraft 在既有提交中执行。Rust 借用宿主 timeline，三个描述符槽只在原 serial 完成后复用；槽耗尽才等待最旧提交形成有界反压。小型帧参数使用命令内 push constants，宿主 image view 的 descriptor 每帧重写；场景或累积资源变化会清掉描述符缓存，避免句柄复用误判。稳定路径无逐帧完成等待、无 PT 专用队列提交。

首个成功录制的 PT 世界帧经宿主完成回调确认后，Java 才跳过原版世界 `FrameGraph.execute`；纯动态或空场景同样有效，不要求先出现非空地形。回调属于明确的 readiness generation，旧 epoch 的完成不能启用新 epoch。它保留之前的动态准备以及之后的 section 编译/上传/遮挡更新；失败或 epoch 变化时恢复原版世界绘制。原版手部与 HUD 在 PT hook 后继续绘制，当前未实现完整世界深度等价。详细数据流见 [pipeline.md](pipeline.md)。

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
