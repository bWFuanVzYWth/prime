# 架构与所有权

```text
Minecraft 26.2 / 26.3 / 各版本 Fabric Mixins
  实际 quad + tint / TextureAtlas / GameRenderer
          │ 原始不可变字节、源身份、相机
Java CaptureInbox → NativeBridge（Java 25 FFM）
          │ 调用期间借用；返回前所有输入完成复制
prime_engine → prime_scene: protocol → SourceScene → Scene / Camera
          │ Rust 解码、三角化、坐标重定位、纹理身份绑定
prime_vulkan → Slang SPIR-V
          │ BLAS / TLAS / RayQuery / progressive path tracing
imageStore → Minecraft 主 RGBA8 图像 → hand / HUD → 宿主提交与呈现
```

## 边界

Java 保留实际接受的 quad 几何、源颜色/tint、拓扑和图集字节；封包属于捕获边界，不产生渲染材质或 GPU ABI。两个版本共享 `common` 的 24 字节顶点编码与 FFM。Rust `prime_scene::protocol` 是不信任数据到可信值的边界，验证完成后才发布更新；该 crate 不依赖 ash、Fabric 或 Minecraft。`prime_engine` 拥有会话、源状态、翻译缓存和 renderer 生命周期；`prime_vulkan` 管理具体 PT descriptor、GPU 数据布局、分配、barrier 与退休。宿主拥有 device、queue、命令池、主图像及提交。Java 的宿主适配仅协商设备特性并转交原版句柄，不翻译场景或录制 PT 渲染命令。完整目录、依赖图与矩形算法边界见 [structure.md](structure.md)。

源身份是 `(section_key:u64, layer:u32)`，不转换为浮点。世界位置保持 `f64 section origin + f32 local vertex`，在 Rust 减去相机附近的 256 格网格原点后再收窄为 f32。每个 mesh 保存不可变 `Arc<[Triangle]>`；场景快照只计算各 mesh 的相对原点并共享顶点和纹理，不重新遍历/复制全部顶点。容量计数随源操作更新；删除不存在的区块仍记录 tombstone，但不使渲染缓存失效。

资源重载与世界切换推进 epoch。捕获开始时记录 epoch 和 revision；完成时再次验证。旧 epoch、已卸载 chunk 的 worker、早于已发布 revision 的结果会被丢弃。一个区块队列项包含先删除再发布其所有层，渲染只在整项提交后发生。空结果因此也能清掉旧层。native 侧维护区块 tombstone，阻止遗漏层被迟到结果复活。

## 增量 GPU 场景

后端按世界空间 64 格网格及 opaque/cutout 标志分组。每个簇拥有局部坐标 BLAS；TLAS 实例变换负责簇原点到帧 anchor 的平移。缓存签名包含源 mesh 身份、revision 与簇内偏移，epoch 变化会清空簇身份。anchor 变化只重建 TLAS，不重建 BLAS，也不上传顶点或整份材质。

材质记录保存在可增长的显存 arena 中。簇删除后回收、合并空闲区间；TLAS 的 24 位 instance custom index 是该簇材质起点，Shader 将它与 primitive index 相加，避免多实例 primitive 0 相互混淆。脏簇每批最多 32 个，将材质复制、独立 BLAS 构建和 TLAS 构建录入同一个宿主命令缓冲。批次之间不提交、不等待，依赖由 barrier 建立；arena 扩容复制与后续脏区间写入也明确建立 transfer write 依赖。创建资源前检查 PT 已知的设备内存分配数量；这不包含宿主自己的分配，当前也没有统一 AS/上传内存池。

纹理元数据与 texel 位于 device-local buffer。像素更新但纹理索引不变时仅更新纹理；增删 ID 导致打包索引改变时仍会重新生成受当前实现管理的全部簇。Opaque 几何由硬件直接接受命中，cutout 才执行候选命中的 alpha 检查；太阳遮挡使用 accept-first-hit 查询。Ray Query 仍使用硬件光追，不存在 CPU 三角形遍历路径。

累积位于 renderer 自己的显存 buffer；Slang 直接向宿主 RGBA8 storage image 写最终颜色。主图像保持宿主的 GENERAL layout，前后 barrier 衔接原版图像访问与手/HUD。生产路径没有输出 buffer、回读 buffer 或整帧复制。独立 `prime_render` 诊断接口仍可同步回读 PNG，Java 桥保留该诊断绑定，但游戏合成不调用它。

Java 使用宿主 transient command buffer，将 native 录制结果交还 `encoder.execute`，最终由 Minecraft 在既有提交中执行。Rust 借用宿主 timeline，三个描述符槽只在原 serial 完成后复用；槽耗尽才等待最旧提交形成有界反压。小型帧参数使用命令内 push constants，宿主 image view 的 descriptor 每帧重写；场景或累积资源变化会清掉描述符缓存，避免句柄复用误判。稳定路径无逐帧完成等待、无 PT 专用队列提交。

首个 PT 地形帧经宿主完成回调确认后，Java 才跳过原版世界 `FrameGraph.execute`。它保留之前的准备以及之后的 section 编译/上传/遮挡更新；失败或 epoch 变化时恢复原版世界绘制。原版手部与 HUD 在 PT hook 后继续绘制，当前未实现动态物体的光追捕获与世界深度等价。详细数据流见 [pipeline.md](pipeline.md)。

## 生命周期与线程

- `CaptureInbox` 以自身 monitor 序列化队列、epoch 和 revision。每次真实 compile 有独立 worker 捕获作用域，只观察该次实际 quad/tint 调用；成功返回后封包，render thread 取走不可变批次。异常与正常返回都清理线程作用域；边界不持有 native/GPU 对象。
- FFM confined arena 可重复使用：帧与错误缓冲固定分配，输入 staging 按需增长。Rust 在调用返回前复制所有输入，不保存 Java 地址；GPU 执行异步不延长输入字节借用期。生产 FFM 不传输输出像素。
- native handle 存在创建 OS 线程的 TLS 表中。全局原子计数器仅分配不可复用身份；跨线程、已释放或伪造 handle 明确失败。没有全局共享可变场景。
- native DLL 具有进程生命周期，session 仍显式销毁。这样异常隔离中的 driver/debug 回调不会跳转到已卸载的 Rust 代码。
- 被替换的 GPU buffer、AS 和上传暂存资源保留至最后关联的宿主 timeline serial 完成。场景更新可以在先前帧在途时录制；同队列 barrier 保护持久 arena 的原位更新。关闭时 Java 先提交尚未入队的宿主命令，再等待 PT 最后 serial；之后才能销毁 descriptor/pipeline 等资源，宿主设备继续有效。
- GPU 操作失败或 panic 会将 session 和发生原位更新的 renderer 标为不可继续使用。FFI 捕获 Rust unwind，错误文本在调用线程读取；非法外部指针仍是 C ABI 调用方违约，无法由 Rust 验证。
- 宿主 timeline 等待有界；若无法证明完成，则保留 native session 并隔离可能在途的 GPU 对象至进程退出。借用模式不会调用 device/queue wait-idle 或销毁宿主设备；已隔离对象不重复执行等待。此异常策略不能替代正常 timeline 回收。

## 当前渲染范围

路径追踪使用硬件 Ray Query、Lambert 材质、线性 Rec.709 工作空间、固定太阳/梯度天空、四次反弹和 Reinhard 显示映射。纹理使用图集 UV、动画首帧与基础 mip 最近点采样；源 RGBA 与 tint 先按 Minecraft 编码域语义组合，shader 再进行所需的线性化。

场景覆盖依赖原版区块编译；当前只接收 opaque/cutout 地形。实体、方块实体、粒子、半透明介质、完整动态纹理、PBR、降噪和 HDR 不属于现有能力。未完成事项见 [HACK.md](../HACK.md)。
