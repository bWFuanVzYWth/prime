# 生产 Shader 零诊断成本契约

## 目标

诊断不得以寄存器压力、payload/队列字段、descriptor、原子操作、额外分支或额外访存的形式进入
生产光传输、命中、重建和显示 shader。诊断图像由独立的后处理 compute entry point 生成；启用的
数值观察状态只存在于专用测试 shader entry point。关闭图像诊断时不创建诊断图像资源，也不提交
诊断 GPU 工作。

这项约束首先由源码可达性保证，再以编译后的生产 SPIR-V 验收；不以运行时开关或 `-O2` 删除
某个分支视为零成本。数值观察类型只存在于 `src/test/slang`，生产 adapter 不创建、传递或提交
任何观察值。除独立 `image_diagnostic_*` 显示 entry 外，生产 entry 的传递闭包不得触达诊断或
测试模块；descriptor/push-constant、ray payload 和 wavefront stride 也不得出现诊断字段。

## 图像诊断域

运行时只保留三个互斥域：

1. 渲染器中间图像；
2. 实际提交给 DLSS Ray Reconstruction 的输入；
3. 实际提交给 NRD 的主 REBLUR、反射 REBLUR 与 SIGMA 的全部原始输入。

每个域都只有关闭、全屏视图和分区总览；NRD 的三个 denoiser 各有独立分区总览。三个域的
滑动条共享一个选择状态，启用任一域会关闭另外两个域，不存在点击轮换、屏幕叠加或全屏开关。
原生分辨率裸输出属于渲染器域，RR/NRD 的噪声输入属于各自输入域；前者用于评估采样质量，
后两者用于与同一重建路径的其他输入做像素级对照，不能合并。

分区总览采用固定 `n × n` 正方网格。每格按源图像宽高比居中缩放，空白区域为黑色，不拉伸；
第一格始终为最终降噪输出，未使用格保持黑色。切换全屏或分区视图时，目标图像的每个像素都在
本帧被覆盖：全屏 pass 写入画面与黑边，分区 pass 先清黑再写各格。因此不会混入上一诊断视图的
图像内容。

这里的“清空历史”只指清空诊断目标图像。诊断选择不得重置或改变 DLSS RR、NRD、FSR、自动曝光、
实时采样或离线累积历史，也不得改变输入图像的布局寿命和语义。

## 资源与执行成本

- 关闭三个图像诊断域时，RR/NRD/渲染器诊断 pass 均保持未创建状态；生产 shader、descriptor
  布局和 wavefront 状态没有诊断成员。
- 启用渲染器中间图像时，才按渲染分辨率创建一个 RGBA16F 临时图集。原始 wavefront 输出在被
  重建准备阶段复用前复制到该图集，随后由独立显示 pass 呈现。
- 启用 RR 或 NRD 输入诊断时，不创建输入快照；独立显示 pass 在准备完成后直接读取本帧将提交给
  对应 SDK 的精确资源。诊断 pass 只读，不修改输入或 SDK 历史。
- HUD 的曝光状态每帧请求采集。16-byte buffer copy 记录在主帧命令缓冲中，通过队列完成回调
  发布 CPU 快照；不额外提交命令缓冲、不等待 GPU。已有读回尚未完成时沿用最后完整快照。

## HUD 信息契约

“渲染诊断信息”开启后固定显示 14 行。运行路径或异步状态变化只改变行内值，不增加、删除或重排
行；暂不可用的数据明确显示 `n/a` 或状态原因。这避免快速切换时文字上下跳动。

所有整数和字节数使用完整十进制值与千位分隔符，不使用 `k`、`M` 或只显示 MiB 的缩写。单精度
浮点量显示 9 位有效数字，足以保留可回读的 float 信息；NaN 和无穷显式显示，不伪造成零。

## 验证

- `generateShaderAbi` 锁定生产 descriptor、push constant、ray payload 和 wavefront ABI；
- `verifyGeneratedSlangAbi` 与 `verifySlangArtifactAbi` 检查生成 ABI 和生产 SPIR-V；
- 图像诊断 shader 的 descriptor set 仅含一个 sampled source 和一个 storage target；
- 选择状态测试验证三个域互斥；画面 pass 通过全覆盖写入和固定网格算法保证无陈旧像素；
- OpenPBR、积分器、NRD 与重建的行为测试继续验证诊断重构没有改变生产数学或历史状态。
