# 生产 Shader 编译边界契约

Prime 把生产 Shader 的源码可见性视为性能契约。模块边界按实际依赖图、entry point
可见代码和独立编译能力划分，不要求服从 BSDF、积分器、wavefront 等语义目录。每个生产
entry 从源头只能依赖完成自身工作所需的最小代码；不能以 Slang 后端最终会内联、常量传播或
删除不可达代码为前提维持宽依赖。

这项约束同时服务三类成本：全量冷编译、命中项目缓存后的增量编译，以及用户启动时驱动创建
管线。任何编译收益都不得改变 OpenPBR 数学、随机数到事件的映射、可见性、ABI 或历史状态，
也不得以增加帧时间、寄存器压力、显存或无依据的额外 GPU 提交换取。

## 边界模型

生产 Shader 依赖分为四类：

1. **数据叶**：ABI、常量、位域、枚举和只描述数据的类型。数据叶不得导入 BSDF、追踪、灯光、
   wavefront 状态更新或输出行为。
2. **数学与能力叶**：一个可独立验证的窄行为，例如公共微表面数学、opaque 求值、dielectric
   采样、light-tree 查询或状态槽读写。它只导入完成该行为必需的数据叶和更小能力叶。
3. **编译岛**：一个 entry 或一组具有完全相同依赖闭包的 entry 所需的能力集合。材质拓扑、
   测度、光源模式、普通/透明分支和 guide/output 阶段均可成为分区轴。
4. **entry root**：只负责资源声明、线程索引和少量阶段编排。root 显式导入所属编译岛，禁止
   通过全功能 adapter、integrator 或 `common` 聚合整个渲染器。

语义目录仍可用于导航，但不是复用理由。一个函数若会把无关的大型闭包带入多个 entry，允许
复制其小型 glue，或下沉真正共享的纯数学叶。这里优先减少可见闭包和失效扇出；重复代码必须
保持小、无独立业务状态，并由同一行为/性质测试约束。

## 强制依赖规则

- import/include 必须构成从 entry root 到能力叶再到数据叶的单向无环图。
- [渲染核心数据 IR](渲染核心数据IR.md)的 schema 与生成 accessor 必须按 semantic 生成独立数据
  叶；禁止通过 `renderer_ir`、全量 record 或资源合同 umbrella 把无关字段和能力带入 entry。
- 相同物理 encoding、image 或 buffer alias 不构成源码复用理由。entry 只导入所消费 semantic 的
  typed accessor；binding/offset 的共享由生成合同解决。
- `common` 只允许窄数据或基础数学，不得成为“暂时方便”的算法聚合入口。
- 数据叶不能反向依赖行为；分类器不能依赖采样、NEE 或路径推进；resolve/output 不能依赖
  BSDF、灯光遍历或 TraceRay。
- opaque 编译岛不得看见 foliage 或完整 dielectric 拓扑；dielectric 与 foliage 同理。确实
  共用的 Fresnel、微表面或能量数学必须下沉为明确的窄叶，不能通过导入另一拓扑复用。
- discrete-only 路径不得看见有限立体角求值、NEE、foliage 或粗糙采样。运行时已经证明的
  family、measure、light mode 和分支身份必须进入队列、entry 类型或其他源码级特化边界，
  不能在全功能函数中重新按控制字分派。
- NRD、RR 和原生含噪输出是不同产品能力，但只允许各自的准备/输出 entry 看见对应代码；
  光传输 entry 不因输出后端选择扩大依赖闭包。
- 生产 entry 不得依赖测试、数值观察或图像诊断实现；完整规则见
  [生产 Shader 零诊断成本契约](生产Shader零诊断成本契约.md)。
- 小模块不自动等于良好边界。拆分只有在减少 entry 可见闭包、降低修改扇出或形成可并行编译
  单元时才有价值。

访问修饰符、泛型、常量参数和链接期 DCE 只能作为边界内优化，不能替代物理依赖切断。Slang
预编译 module 可以在依赖图稳定后减少重复前端工作，但最终链接与代码生成仍以最小编译岛为
输入；不得用一个预编译的全功能 module 恢复宽可见性。

## 变种与运行时约束

不生成理论上的笛卡尔积。只有运行时状态机实际发布、并能消除高成本依赖的组合才拥有独立
entry。优先级由“减少的可见代码与编译扇出 / 新增的队列、dispatch、显存和维护成本”决定。

首选的源码级特化轴是：

1. 已有 Trace、Light Select、Direct、Scatter 等窄 Wavefront 阶段及其队列身份；
2. discrete-only 与一般 solid-angle 路径；
3. opaque 与需要完整透射/foliage 能力的复杂材质；
4. 普通路径与首接口条件分支；
5. 仅在测量证明有收益时拆分 NRD、RR 和其他输出准备。

拆出新 queue 或 dispatch 前必须测量真实命中比例、GPU frame time、每阶段寄存器/occupancy 和
显存流量。运行时性能不能回退；无法在测量噪声内证明不回退的拆分不进入生产。不得为了跨
TraceRay 复用而长期保存宽 BSDF 聚合状态；若这会延长 live range，应选择窄状态重建或独立
阶段。

## 构建与门禁

每个生产 entry 必须拥有独立编译与缓存身份，其输入是该 entry 的精确传递依赖闭包，而不是
整个 `shaders` 目录。共享叶变化只使实际消费者失效；互不依赖的编译岛允许并行编译。

构建报告至少应按 entry 记录：

- 可见文件、源码字节数和传递依赖边；
- 冷编译 wall time、CPU time 与峰值内存；
- 默认 `-O2 -g2` 产物大小，以及排除非语义调试指令后的函数体、分支、switch 和 Phi 规模；
- 缓存 key 与因一次源修改而失效的 entry 集合；
- 用户启动阶段的管线创建 wall time 和驱动缓存命中状态。

依赖门禁必须拒绝禁止边、环和已经消除的聚合入口。规模采用已审查基线控制：新增功能可以在
证据支持下提高局部预算，但无关 entry 的闭包不得增长，现存宽依赖只能缩小。源码图门禁只
证明架构边界，不能代替 OpenPBR、数值、状态、ABI 和图像行为测试。

## 已落地结构

生产源码只允许位于 `contract`、`math`、`model`、`bsdf/compact`、`service`、`transport`、
`state`、`policy`、`phase` 与 `entry`。每个 entry 各自只导入一个 phase；phase 不互相导入，
state 不依赖 transport。旧 `core`、`material`、`lighting`、`integrator`、`realtime`、`rt`、
`post` 顶层以及全功能 BSDF/integrator/wavefront umbrella 均已删除，门禁拒绝恢复。

`shaders/programs.json` 是实际 artifact、资源名及 realtime/offline schedule 的唯一清单。
每个 artifact 是独立的 `@CacheableTask`，以精确传递闭包、编译参数、manifest 项、Slang
版本和 canonical workspace path 为输入；共享 Build Service 限制 slangc 并发。构建产物只保留
实际 scalar/SER artifact，不复制 `_ser` alias。

`shaders/closure-budgets.json` 保存逐 entry 的已审查文件数和源码字节上限。架构任务同时校验
module/path、允许层级、禁止边、环、生产 include 禁令、manifest 完整性、诊断不可达性和
resolved-state 边界，并在报告中列出 fanout 与源修改的 artifact 失效集合。预算变化必须与真实
依赖变化一同审查，不能用提高全局上限掩盖无关闭包增长。

delta-walk 只导入 opaque/dielectric 的专用离散状态、能量与采样叶。架构门禁明确拒绝该
entry 到达 foliage、NEE、通用 operation dispatcher、有限立体角 microfacet 分布以及一般
evaluate/sample 模块；通用采样器的 delta 分支反向复用同一离散实现，避免数学与随机映射分叉。

材质类别不形成独立运行时 queue/group。引入这类编译岛会改变 GPU command 与队列流量，只有
在用户实机上证明 frame/transport time 的 95% 置信上界、寄存器、occupancy 和显存均不回退时
才可进入生产；编译收益不能抵偿帧性能。Slang module 预编译也只在稳定图上、且前端重复工作
超过冷编译 CPU 时间 10% 后考虑。

## 文档所有权

本文唯一维护生产 Shader 的依赖、可见性、编译岛和变种选择规则；数据语义、坐标、颜色和精度
准入由[渲染核心数据 IR](渲染核心数据IR.md)维护。`渲染实现.md` 只描述已经
落地的运行时调度，`构建与验证.md` 只描述已经可用的任务和门禁，开放迁移只在 `TODO.md`
保留一个入口。其他文档应链接本文，不复制一套容易漂移的规则。改变本契约必须在同一变更中
提供编译与 GPU 运行证据；只改变实现状态时更新对应实现文档，不改写契约。
