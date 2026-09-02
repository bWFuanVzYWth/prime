# 后续工作

本页只记录非缺陷型增强；可复现的当前问题见 [FIXME](FIXME.md)。

## 渲染核心数据 IR

- 继续收敛 wavefront 存活期/布局、材质表热路径和 backend 资源 alias；
  没有误差合同的 transport 连续量不得压窄，规范只在[渲染核心数据 IR](渲染核心数据IR.md)更新。
- starmap 紧凑格式和离线转码延后；恢复前重新测量固定显存、启动 staging 与热采样成本。

## Shader 编译边界

- 按[生产 Shader 编译边界契约](生产Shader编译边界契约.md)补齐同机冷编译、驱动冷/热缓存
  pipeline 创建和 GPU frame/transport 基线；源码闭包、逐 artifact 缓存、delta-walk 离散岛，
  以及实时/离线窄 Wavefront 阶段已落地。只有实机证明无帧性能、寄存器、occupancy 或显存
  回退后，才试验 opaque/complex
  queue/group；只有前端重复工作超过冷编译 CPU 时间 10% 后，才试验 Slang module 预编译。

## 生命周期与地形性能

- 按 Vulkan timeline 完成点循环复用 descriptor set/pool，保持在途 command buffer 的资源
  所有权不变，减少 TLAS 替换时的驱动分配；
- 评估把 Section geometry 和 light record 直接写入可增长 staging/native 存储，减少
  Section→cluster 所有权转移期间的 Java 数组驻留，同时保持单 cluster 原子替换和单 base
  BLAS/TLAS instance；
- 评估地形静态更新更有效的批处理；动态捕获固定逐帧重建 BLAS/TLAS，不做 dirty check
  或 refit。新 geometry 不得在可见性结构外提前发布。
- 评估让同一 motion/lifetime domain 内的动态表面复用静态 `SurfaceDefinition` resolver；必须
  保持逐帧 resident 所有权、previous-position 对应和无法证明关系时的原几何回退。

## 渲染能力

- 重新设计 Reinhard-Gamut 的 HDR shoulder。当前 `+6.5 EV` reach 随显示峰值 headroom
  整体放宽中灰以上曲线，数学上能在有限输入命中峰值，但会过度抬升太阳等中高亮，达不到
  艺术需求；后续应明确 SDR 参考白以下的外观保持边界，并只用额外 headroom 展开 HDR 高光；
- 场景几何 LOD；
- 云渲染（细节待定）；
- 评估 LabPBR AO/porosity 的物理用途；不能把源格式字节直接泄漏到积分器或用环境遮蔽重复
  压暗已经由路径追踪求出的间接光；
- 月亮（月相）：作为夜晚的主光源；月相跟随 Minecraft 原版状态，轨迹取太阳轨迹的
  相对方向，不采用真实月球轨迹；
- 雾和局部体积光的通用体积渲染；
- 将实体及动态发光几何纳入灯光采样，并定义 emitter 捕获、增量 light tree、生命周期和
  前后向 PDF；
- NVIDIA Streamline Reflex 保持可选能力并继续补充实机兼容性验证。DLSS Frame Generation 只有
  在 [FIXME](FIXME.md) 记录的上游崩溃得到可验证修复，并重新通过现有 constants、真实 depth、
  resize、运行时状态和持久资源门禁后，才可从“高风险实验功能”移回常规设置区。

## 兼容性与产品体验

- 检测玩家手持的发光物品，并将其作为动态光源纳入场景光照；
- 检测会绕过或重复动态实体提交、替换世界渲染或破坏后处理边界的已知不兼容 Mod，并在启用
  Prime 前给出具体 Mod ID、受影响能力和禁用建议；检测必须基于可证明的加载或注入契约，
  不得按模组类别猜测，也不得用不可靠兼容层掩盖错误渲染；
- 为着色器与管线编译提供可见进度条；
- 调查机械动力飞跃版等 mod 的实体捕获、render type、动态 geometry 和 TLAS 路径；
- 改进未按 `texture.properties` 标准声明的 LabPBR 资源包检测，同时避免误判普通纹理；
- 为高分辨率纹理体素表面测量 CPU 建网、BLAS、staging/显存和 instance 增长，随后定义可诊断
  的分辨率上限、保形降采样或标准 quad 回退。降级不得改变 alpha、UV 或位移上限。
