# 已知问题

本页记录已稳定复现的缺陷、尚未稳定复现但有明确观察证据的问题，以及明确接受的限制。无法
稳定复现的条目必须写清已观察现象和待核对边界，不能把推测当成根因。已修复问题不保留历史
流水；重新出现时补充现有日志、资源包/mod 组合、已知触发条件和首次出现的渲染阶段。

## 动态实体

### 史莱姆不透明

当前实体材质不能表达史莱姆的半透明、内部散射和柔软质感。不能仅按实体类型硬编码透明度；
完整方案需要同时定义材质适配、采样和重建 guide。

## 光照与介质

### 体积光精度不足导致室内环状漏光

封闭室内在特定观察角度可能出现同心圆状的体积光亮带和漏光。该伪影与体积光计算精度不足
有关；后续调查需分别核对体积积分采样、深度重建、太阳可见性和浮点精度，不能将亮带视为
真实介质散射。

### 信标光柱遮光

信标光柱可能被 shadow/visibility 路径当成不透明遮挡。光柱应保持可见，但不能阻断照明；
需核对动态 mesh、材质分类和 shadow any-hit。

## 模型与资源包

### 告示牌文字

告示牌文字曾出现表面闪烁或覆盖错误，但尚无稳定复现条件。应先对照捕获的文字/底板几何、
源身份和命中距离证明是否为共面竞争，不能仅凭画面将根因定为 z-fighting。

### 红石火把

部分资源包的红石火把头部应为 alpha-cut 独立平面，Prime 可能将其捕获为不透明几何。后续
调查应以实际 baked quad、sprite alpha、材质标志和 OMM 输入为证据，不能只依赖方块 ID 或
Minecraft render layer。

### 纹理体素 relief 遮挡附着内容

向外生成的体素柱可能遮住发光地衣、火焰等贴近宿主表面的独立内容。需区分宿主表面、贴花层
和交叉面，再决定跟随 relief、有界外移或回退标准 quad；禁止使用无上限全局偏移。

## 性能

### 实体 / 方块实体卡顿

存在大量实体或方块实体时曾观察到 CPU 满载、GPU 利用率低和严重掉帧，但尚未固定实体组合与
数量门槛。需同时采集捕获、CPU 建网、BLAS/TLAS、提交和 GC 时间，确认瓶颈后再决定批处理或
缓存边界。

### 高视距地形流送的分配与 TLAS 成本

在较小 Java 堆、高视距且持续跑图时，Section→cluster 中间存储和全 resident TLAS 更新仍可能
造成高分配率、GC 停顿和帧率下降。当前使用紧凑 light tree、有界 CPU segment、跨世界统一
in-flight 上限和 64 MiB 上传/compaction 背压；仍需实机测量稳态分配、GC、TLAS build 与
长期帧时间。增大 `-Xmx` 只能临时延后问题。

后续优化不得丢弃 geometry、放宽无界队列、在热路径调用 `System.gc()`，也不得用增加 TLAS
instance 换取较低上传峰值。

## 平台集成

### NVIDIA DLSS Frame Generation 可能触发不可恢复的 Vulkan device lost

Windows Vulkan 下启用 Streamline DLSS-G 后已稳定观察到 fake swapchain image layout 不一致，
随后 `vkAcquireNextImageKHR` 或 `vkQueuePresentKHR` 以 `VK_ERROR_DEVICE_LOST` 失败并使客户端崩溃。
错误涉及 NVIDIA 内部的 `nv.sl.dlss_g.tex2d.fake-swapchain-buffer` 与
`nv.sl.dlss_g.clone.dlfg-output_*`；Prime 不拥有这些内部 transition，不能通过吞掉 Vulkan 返回
值恢复设备。相关上游记录为 Streamline
[#84](https://github.com/NVIDIA-RTX/Streamline/issues/84) 与
[#112](https://github.com/NVIDIA-RTX/Streamline/issues/112)。

因此 DLSS-G 只显示在“高风险实验功能”栏，选项明确警告可能立即崩溃；Prime 保留并报告真实
Vulkan 错误。NVIDIA 给出可验证修复前，不把该功能移动到常规设置，也不以关闭 validation、
忽略 acquire/present 失败或猜测内部 image layout 的方式伪装修复。

## 明确接受的限制

- NRD-FSR 无法可靠降噪彩色玻璃后的传输信号，可能保留明显噪点或时间性不稳定。当前接受该
  缺陷，不通过移除染色吸收或把透射错误归入不透明 guide 来掩盖问题。
- OMM 的静态纹理精确细分上限为每轴 256 纹素，包含宏面重复后的总细分上限为 L10；更高
  分辨率会在该上限内近似，透明边界正确性不保证，可能出现缺失或多余覆盖。该限制用于避免
  单三角形 micromap 存储按每级四倍无界增长。
- 透明介质栈最多容纳两个非空气区域；第三层嵌套、非流形边界或缺失界面的吸收/pop 顺序不受
  保证。实体玻璃与水的折射都会占用一个带 IOR 和 RGB 消光的栈项。
- 实时透明使用首接口固定双槽；首面与后续顶点使用完整条件/随机闭包，guide 沿各辐射分支
  实际选择的 continuation 累积 PSR。运动、溢出、链上限或非法状态回退真实可见接口，不执行
  独立 replay。这仍不是任意折射链的无偏连接。
- `lighting.transparent_nee_mode=straight_approximation` 是默认的有偏近似：NEE 阴影忽略透明
  界面的折射，只沿原连接线累计透明实体的体积吸收。`unbiased_bsdf_only` 在 alpha 测试后把
  首个透明界面当作遮挡，只保留起始介质内尚未跨界面的 Beer 段；结果无偏但透明折射链的直接
  光只能由 BSDF 路径命中，可能极难收敛。Prime 不实现或暴露 MNEE 模式。
- HDR 世界与原版 UI 的最终 alpha 合成不能精确恢复 UI 目标中已经执行的非 source-over 混合；
  当前接受该显示近似。
- NASA 星图保持当前 scene-linear RGB 数值，但来源未提供 primaries、white point 或 EXR
  `chromaticities`，因此色度解释未知。
- LabPBR 厚材质的 SSS 明确降级为 diffuse；介电 F0 在输入翻译边界清洗到 `[0.02, 0.17]`。
  两者都是稳定策略，不属于 compact OpenPBR 数学中的未修缺陷。
- 太阳体积阴影只近似单方向太阳可见性，不表示半球天空、局部灯光或参与介质多次散射。
