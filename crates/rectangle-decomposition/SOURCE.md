# 来源与集成边界

本目录复用自用户指定的本地仓库 `C:/WorkSpace/voxel_engine/crates/rectangle_decomposition`，参考仓库保持只读。

- 来源 workspace 提交：`9c80d226a0b3c714af1eac27f57db3e3d15f8a20`。
- 导入日期：2026-09-26。
- 来源 crate 在导入时没有未提交修改。
- 包名与公开 API 保留 `rectangle_decomposition`，新 workspace 目录名为 `crates/rectangle-decomposition`。
- `src/`、`tests/`、`benches/`、`examples/`、README 和算法文档按来源复制；本次不修改分解算法和输出语义。唯一源码集成改动是在 lib.rs 明确 `#![forbid(unsafe_code)]`，保留原 workspace 禁止 unsafe 的边界。
- 开发依赖沿用 workspace 声明的 Criterion 0.8.2、test-case 3.3.1 兼容范围；实际解析版本以新 workspace 的 Cargo.lock 为准。生产库没有第三方依赖和 GPU 依赖。
- 文档整理时修正 `docs/research.md` 指向原 workspace 性能指南的失效链接，改为本项目开发指南，并说明当前尚未接入 Minecraft 网格；没有修改算法源码、测试或 API。
- crate README 已改为当前 workspace 的介绍与可用 path 依赖示例。原 README 中关于 `2c9d1ac` 拆分和 `334d078e6db2f0aa85898437714161190c4d50f2` Git 依赖的说明属于体素引擎更早的导入历史；本项目的直接来源仍是上列 `9c80d226...` 工作树。

论文和算法出处见 [docs/references.md](docs/references.md)。在导入时检查的来源 crate 与 workspace 根目录中未发现独立 LICENSE/COPYING 文件，来源 Cargo.toml 也没有 license 字段。保留已有声明，不据此推定或新增许可证。

本库只接收 64×64 平面上经过验证、对齐且不重叠的带标签 sparse quad。调用方负责按平面、方向和完整合并语义分组；材质、UV、透明度、tint、发光或其他表面属性不一致时不能仅按 block ID 合并。本次只把算法作为可独立调用和测试的 workspace 成员，不将任意 Minecraft quad 强行映射为矩形输入。

当前 workspace 仅登记成员，没有无消费者的依赖别名或转发 API。消费者可显式使用 README 的本地 path 依赖；需要 `{ workspace = true }` 用法时再登记根依赖。

可复用 `SparseOptimalScratch64` 为较大的内联对象，调用方需遵循原 [scratch 合同](docs/scratch.md)，并在任务循环外准备足够的 worker 栈；不能把每次创建 scratch 隐藏进渲染线程热路径。
