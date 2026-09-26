# 架构文档

这里维护随实现更新的架构、职责、接口契约与长期工程规范。

| 文档 | 内容 |
| --- | --- |
| [整体架构与所有权](architecture.md) | 源数据到场景和 GPU 的数据流、增量更新、线程与资源生命周期 |
| [工程结构与捕获边界](structure.md) | Rust crate、Java 版本适配、公共层依赖、源 quad/tint 与矩形分解边界 |
| [捕获边界与批量数据流](capture-boundaries.md) | 已采用的分类边界、缓存契约、更新频率、GPU 工作范围与分步接入状态 |
| [FFM ABI](abi.md) | 字节协议、输入验证、指针借用、宿主 Vulkan 录制接口 |
| [宿主 Vulkan 流水线](pipeline.md) | 设备协商、直接输出、命令提交、同步、手部与 HUD 合成 |
| [Slang 数学基础与显示策略](shaders.md) | 模块边界、可替换的 primeDRT、颜色空间、Z-Sobol、求交误差与尺寸历史 |
| [行尾与源码格式](guides/git-line-endings.md) | 仓库级换行、格式工具与规范化边界 |

安装与使用见根 [README](../README.md)；构建、测试和测量方法见 [CONTRIBUTING](../CONTRIBUTING.md)；尚未实现的能力与技术债见 [HACK](../HACK.md)，后续工作见 [TODO](../TODO.md)。

调查、待讨论方案、单次性能测量和验收报告，以及日志、截图、CSV 等证据保存在 Git 忽略的 `artifacts/`，不进入 `docs/`。已经采用的架构与长期契约整理进对应文档，并明确区分接入状态与目标；架构文档不依赖某次本地报告才能读懂。
