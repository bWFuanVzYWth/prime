# 多版本工程结构

`prime_engine` 负责线程受限会话、资源与 renderer 生命周期，导出 `prime_*` C ABI。渲染相关的 Minecraft 源语义适配位于 `prime_minecraft`；Java 版本模块绑定真实宿主类、字段和事件，批量转发 Rust 请求的源输入。`prime_scene` 与 `prime_vulkan` 不识别 MC 版本或类布局。

```text
adapters/
  common/                 Java 25：设置、FFM、复用 native 源页与动态封包
  mc-26.2/                26.2 宿主字段/事件路由、动态源与 Vulkan 句柄绑定
  mc-26.3/                26.3 宿主字段/事件路由、动态源与 Vulkan 句柄绑定
crates/
  prime-minecraft/         MC 源协议、渲染范围、dirty/邻域调度、模型解释与同步编译
  prime-scene/             版本无关的场景、输入验证、增量与空间翻译
  prime-engine/            FFM、源适配上下文、场景和 renderer 生命周期
  prime-vulkan/            Vulkan 资源、AS、命令、同步、退休与 Slang
  prime-tools/             诊断与性能夹具
  rectangle-decomposition/ 体素引擎矩形分解
```

## 依赖与所有权

`prime_engine → prime_minecraft → prime_scene` 构成地形源输入链，`prime_engine → prime_vulkan → prime_scene` 承担渲染。MC 适配 crate 不依赖 JVM、Fabric 或 Vulkan，只解释由宿主转录的版本化字段；它向场景核心交付闭合的 `CompiledSection`。GPU 不认识 section 或 MC 枚举。

一个 `TerrainContext` 管理一个 renderer 的世界/资源世代、活动窗口、压缩源页缓存、资源表和私有同步 CPU 池。没有全局异步队列、Java compiler 池或逐体素反向调用。每帧一批请求和一批响应；响应借用结束前全部 worker join。静态地形4³合批、动态分桶和 GPU 生命周期继续由原来的场景/渲染上下文负责。

Java `ExclusiveTerrainCapture` 转录宿主事件并响应请求，`SectionSources` 转录 palette、bit storage 和已烘焙资源字段，不逐位置选择模型、求 tint、剔面或展开几何。它保留资源身份到协议 ID 的关联，实际模型字典与源缓存由 Rust 持有。原先的 `TerrainRouter` / `FluidRouter` 只保留在 CPU 测试源集中，作为旧协议的参考产物生成器，不进入生产 JAR。

`adapters/common` 不依赖 Minecraft、Fabric 或 LWJGL；`SourcePages` 只负责 native 页编码。两版仍共享同一个引擎 DLL；MC 源协议显式带262/263版本身份，在 Rust 适配层校验。固定私有字段绑定由双版本无窗口夹具验证，未知来源与当前原型替代见独立的 [PROTOTYPE_HACKS](../PROTOTYPE_HACKS.md)。

## 动态源与宿主

本轮原型接管地形。实体/方块实体、普通 item、Fabric Mesh 与粒子保留各自的源入口和实际姿态回调，使用 op7 / op6；它们不能从 section 状态页还原。纹理源、相机和宿主 Vulkan 特性/句柄继续由对应 Java 版本绑定。

两个安装包包含公共层的同一编译产物和同一 `prime_engine`；`verifyNativeJars` 验证字节和精确版本约束。每版使用独立 `run/` 和存档。协议与宿主集成分别见 [ABI](abi.md) 和 [架构](architecture.md)。

## 矩形分解的接入范围

矩形分解复用体素引擎的 API、算法、测试和文档，来源见 [SOURCE.md](../crates/rectangle-decomposition/SOURCE.md)。它是可独立调用的 workspace 成员，由统一的 `prime_scene::surface` 编译流程消费；规则面资格、完整材质标签和 UV 重复由调用者证明，库仍只处理二维标签。合并条件和限制见 [自定义表面编译](surface-compiler.md)。

后续调用点应在 `prime_scene` 的受约束几何编译阶段：证明共面、方向、网格对齐、材质、UV 映射、tint/alpha 等完整语义可合并后，才转换到算法要求的 64×64 带标签 sparse quad。任意模型面、斜面、不同 UV 或 tint 不能仅按 block ID 合并。较大的 scratch 在 worker 生命周期复用，不能每个任务在渲染线程分配。

构建、双版本验证与诊断入口见 [CONTRIBUTING](../CONTRIBUTING.md)。
