# 架构文档

这里维护随实现更新的架构、职责和接口契约。

| 文档 | 内容 |
| --- | --- |
| [整体架构与所有权](architecture.md) | 源数据到场景和 GPU 的数据流、增量更新、线程与资源生命周期 |
| [工程结构与捕获边界](structure.md) | Rust crate、Java 版本适配、公共层依赖、源 quad/tint 与矩形分解边界 |
| [FFM ABI](abi.md) | 字节协议、输入验证、指针借用、宿主 Vulkan 录制接口 |
| [宿主 Vulkan 流水线](pipeline.md) | 设备协商、直接输出、命令提交、同步、手部与 HUD 合成 |

安装与使用见根 [README](../README.md)；构建、测试和测量方法见 [CONTRIBUTING](../CONTRIBUTING.md)；尚未实现的能力与技术债见 [HACK](../HACK.md)。

调查、待讨论方案、单次性能测量和验收报告，以及日志、截图、CSV 等证据保存在 Git 忽略的 `artifacts/`，不进入 `docs/`。其中已经落地的长期结论整理进对应架构文档；架构文档不依赖某次本地报告才能读懂。
