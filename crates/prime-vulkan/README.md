# prime_vulkan

本 crate 将 `prime_scene` 场景编译为 GPU 几何、纹理和加速结构，并拥有 Vulkan 资源生命周期与 Slang 路径追踪执行。它不依赖 Java 或 Minecraft 类型；Streamline 的本地 FFI 封装也由本 crate 管理。

`Renderer::borrowed` 借用宿主 Vulkan device/queue/timeline；`record_host` 将工作写入宿主 command buffer 和 RGBA8 storage image。宿主负责真正提交、调用 `submission_accepted` 确认对应 serial 的时间历史，并 signal 完成值；后端据完成证明退休资源。`shutdown` 证明最后一次使用完成后关闭录制，不销毁宿主 device/instance。

星图、自动曝光和 HDR 保留旧 Prime 的成熟算法，GPU 资源与调度归本 crate。HDR 的 `display_output` 提供实际峰值/SDR白标定，`present_hdr` 在手部/HUD 后录制 scRGB 输出；无 PT 世界时 `HdrSurface` 只持有轻量呈现资源。FG 使用实际可见 depth/motion、HUDless 与 UI 覆盖，SDK Present 的最后消费者拥有独立完成证明。显示和重建的近似、资源成本与支持边界见[显示合同](../../docs/display.md)和[重建合同](../../docs/reconstruction.md)。

静态 BLAS 允许压缩，查询、ready 队列、复制预算和旧 TLAS 退休由几何 owner 管理；只在真实构建完成后读取 compacted size。空 AS 页依活跃区间与在途引用归还，其他池收缩仍为独立取舍，见[空间合批](../../docs/spatial-batching.md)。

`Renderer::new` 和 `render` 是独立设备及像素读回诊断入口。`HostBenchmark` 是模拟宿主 timeline 的 GPU 基准入口；它们不改变生产路径的直接 GPU 输出合同。

构建需要 Slang 编译器：依次使用 `SLANGC`、`VULKAN_SDK` 下的编译器或 PATH 中的 `slangc`。shader 编译在本 crate 的 build.rs 中执行；纯 CPU 场景与矩形算法 crate 不依赖此 crate，因此不需要 Vulkan 或 Slang。

资源与提交契约见 [宿主流水线](../../docs/pipeline.md)，构建与诊断命令见 [开发指南](../../CONTRIBUTING.md)。
