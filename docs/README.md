# 架构文档

这里维护随实现更新的架构、职责、接口契约与长期工程规范。

| 文档 | 内容 |
| --- | --- |
| [整体架构与所有权](architecture.md) | 源数据到场景和 GPU 的数据流、增量更新、线程与资源生命周期 |
| [自定义表面编译](surface-compiler.md) | 源关系与局部接触、部分矩形合并、直接 GPU 记录、介质、sprite 与局部灯网格 |
| [LabPBR 与 OpenPBR 支持子域](materials.md) | 源声明、规范通道、数值清洗、法线分布过滤、动画、完整模型的支持边界、保留的 LitePBR 近似与发光 |
| [镂空表面 OMM](opacity-micromaps.md) | 二值优先的有限资源模板、共享资源代次与运行时绑定、未知退路、Vulkan 能力和完成证明 |
| [大气资产与缓存](atmosphere.md) | 固定物理场、天空/太阳/空气透视、GPU 遮挡列与更新依赖 |
| [固定资产格式与分发](assets.md) | KTX2/Safetensors 选择、Zstd 压缩、Git LFS 存储、语义与来源身份、加载峰值及迁移验收 |
| [星图、曝光与HDR显示](display.md) | 旧算法移植、测光与冻结、scRGB标定、UI覆盖及帧生成显示输入 |
| [工程结构与路由边界](structure.md) | Rust crate、Java 版本适配、公共层依赖、Rust 版本适配、分页路由与矩形分解边界 |
| [源路由边界与批量数据流](capture-boundaries.md) | 外观描述与下游计算的分界、接管后的 Java 截断、兼容范围、缓存与接入状态 |
| [全局空间网格与几何合批](spatial-batching.md) | 4×4×4 区块段对齐、翻译层归属、静态/动态分离及光源采样归属边界 |
| [设置与渲染模式](renderers.md) | 当前配置版本、冻结快照、互斥资源、实时噪声与深度/法线诊断 |
| [ReSTIR PT Enhanced](restir-pt.md) | 独立积分器、上游默认配置、重放与双向 MIS、状态成本及支持边界 |
| [Streamline、DLSS RR与帧生成](reconstruction.md) | preset F、超分档位、动态前态、重建与插帧输入、Present及完成证明 |
| [FFM ABI](abi.md) | 生成的 C/FFM 结构、批量数组、输入验证与借用、宿主 Vulkan 接口 |
| [宿主 Vulkan 流水线](pipeline.md) | 设备协商、直接输出、命令提交、同步、手部与 HUD 合成 |
| [Slang 数学基础与显示策略](shaders.md) | 模块边界、OpenPBR 支持子域与 LitePBR 参考、可替换的 primeDRT、颜色空间、Z-Sobol、求交误差与尺寸历史 |
| [PT 依赖与性能设计](pt-state-design.md) | 数据依赖图、查询切面、CPU/GPU 边界、当前状态策略与修改前必须回答的性能问题；不声明最优，不锁定设计 |
| [诊断与原始性能数据](diagnostics.md) | 按需诊断、关闭开销、跨线程任务、时钟域、紧凑 JSON 与后台导出 |
| [行尾与源码格式](guides/git-line-endings.md) | 仓库级换行、格式工具与规范化边界 |
| [Nsight Graphics 抓帧](guides/nsight.md) | 当前/固定版本启动、GPU Trace 与 Graphics Capture、性能归因和证据保存 |
| [Section 对拍与基准契约](guides/section-tests.md) | 实际原版参照、数值等价、增量回归、计时边界与证据范围 |
| [性能证据与实验清理](guides/performance-evidence.md) | 稳定结论提炼、实验保留、冻结依赖与可再生成产物清理 |
| [光源采样测试](guides/light-sampling.md) | 无窗口选灯成本、线性噪声、独立参考与理论/经验收敛边界 |

安装与使用见根 [README](../README.md)；构建、测试和测量方法见 [CONTRIBUTING](../CONTRIBUTING.md)；尚未实现的能力与技术债见 [HACK](../HACK.md)，后续工作见 [TODO](../TODO.md)。section 原型的临时默认值单独维护在 [PROTOTYPE_HACKS](../PROTOTYPE_HACKS.md)，不混入主清单。

调查、待讨论方案、单次性能测量和验收报告，以及日志、截图、CSV 等证据保存在 Git 忽略的 `artifacts/`，不进入 `docs/`。已经采用的架构与长期契约整理进对应文档，并明确区分接入状态与目标；架构文档不依赖某次本地报告才能读懂。
