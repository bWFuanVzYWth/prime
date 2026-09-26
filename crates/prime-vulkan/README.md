# prime_vulkan

本 crate 将 `prime_scene` 场景编译为 GPU 几何、纹理和加速结构，并拥有 Vulkan 资源生命周期与 Slang 路径追踪执行。它不依赖 FFI、Java 或 Minecraft 类型。

`Renderer::borrowed` 借用宿主 Vulkan device/queue/timeline；`record_host` 将工作写入宿主 command buffer 和 RGBA8 storage image。宿主负责按约定提交与 signal，后端按完成 serial 退休资源。`shutdown` 证明最后一次使用完成后关闭录制，不销毁宿主 device/instance。

`Renderer::new` 和 `render` 是独立设备及像素读回诊断入口。`HostBenchmark` 是模拟宿主 timeline 的 GPU 基准入口；它们不改变生产路径的直接 GPU 输出合同。

构建需要 Slang 编译器：依次使用 `SLANGC`、`VULKAN_SDK` 下的编译器或 PATH 中的 `slangc`。shader 编译在本 crate 的 build.rs 中执行；纯 CPU 场景与矩形算法 crate 不依赖此 crate，因此不需要 Vulkan 或 Slang。

资源与提交契约见 [宿主流水线](../../docs/pipeline.md)，构建与诊断命令见 [开发指南](../../CONTRIBUTING.md)。
