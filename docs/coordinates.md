# 图像、相机与重投影坐标契约

渲染核心图像统一采用左上原点：`x` 向右、`y` 向下，整数 `(x,y)` 指向 texel，像素中心是 `(x+0.5,y+0.5)`。射线、场景线性辐射亮度、重建 guides、时间复用和测光在该空间工作。宿主目标与界面图属于呈现适配空间，不能将其方向反向传播进主射线、运动向量或材质 UV。

这延续旧 Prime 的 top-left 核心约定。当前实现使用显式相机基向量与独立 SDK 矩阵 ABI；不沿用旧项目的矩阵内存布局或 reversed depth 设置。本文维护方向、单位与转换位置；颜色与数据身份见[显示契约](display.md)，资源同步和 SDK 参数见[重建契约](reconstruction.md)。

## 图像空间与采样位置

| 数据空间 | 原点与正方向 | 尺寸、像素中心与采样 |
| --- | --- | --- |
| 普通 PT / ReSTIR 主射线、路径状态、Offline 累积 | 左上，`+x` 右、`+y` 下；线性下标为 `y*width+x` | 实际内部渲染尺寸；主射线经过像素内的实际采样位置 |
| realtime prefix/tail、raw 线性输出、aerial 屏幕 UV | 同一左上方向 | prefix/tail 与 aerial 对应实际主射线采样 UV；直接输出/线性输出不改变射线方向 |
| RR noisy color、depth、normal、albedo、主/specular motion、完成状态 | 左上 | 全部使用 RR 实际输入尺寸；相机射线采样位置为 `pixel+0.5+SampleJitterPixels` |
| RR SDK output、selector、星图后合成、曝光输入 | 左上 | 最终输出尺寸；selector 的 noisy 双线性位置为 `(outputPixel+0.5)*inputExtent/outputExtent-0.5`；星图按未抖动的输出中心生成相机方向 |
| HDR world 与 SDR baseline 快照 | 左上 | 输出尺寸；保存世界结果，不随之后的手部/HUD 绘制改变 |
| 宿主 world/UI composite、最终呈现目标 | 由宿主接口的 `bottom_up` 显式声明；为真时是左下原点、`+y` 上 | 输出尺寸；界面采样按宿主 texel，不套用 PT jitter |
| FG depth / motion（binding 21 / 22） | 左上 | RR 输入尺寸；真实首可见物理表面与方向天空，独立于 RR 提升后的 PSR guide |
| FG HUDless / UI mask、最终 swapchain | 左上，与 FG depth/motion 及 SDK 相机同向 | 输出尺寸；world 快照保持 canonical，UI mask 从宿主 texel 反向索引。SDR 宿主 surface blit 与 HDR 直接输出遵守同一最终方向 |

像素边界是整数坐标，归一化 UV 的 `(0,0)` 与 `(1,1)` 分别是图像左上、右下边界。采样偏移 `q` 相对于像素左上角，`SampleJitterPixels=q-0.5` 相对于中心；两者不能混用。

```text
SampleUv = (pixel + 0.5 + SampleJitterPixels) / inputExtent
clip.xy = (2*SampleUv.x - 1, 1 - 2*SampleUv.y)
uv = (0.5*clip.x + 0.5, 0.5 - 0.5*clip.y)
```

`clip.xy` 的 `+y` 向上，UV 的 `+y` 向下；公式中的符号就是空间转换，不是额外翻转一次图像。普通 PT 的 raw/Offline 主采样使用 Z-Sobol 的像素内偏移。开启 RR 时，普通 PT 和 ReSTIR 均使用 CPU Halton 的中心偏移；ReSTIR 无 RR 时当前主射线取像素中心。积分器各自的照明与 coverage 随机域保持各自合同。

## 世界、相机与矩阵

世界几何保留宿主轴定义，`+Y` 向上。CPU 绝对位置可用 f64；GPU 的位置是在显式 `scene.anchor` 下的 f32 相对坐标，满足 `absolutePosition=anchor+relativePosition`，不要求相机始终位于相对原点。历史相机先按前后 anchor 差重定位到当前 anchor，再做投影；方向不受平移影响。

相机提供单位 `right`、`up`、`forward` 和垂直 FOV。相机视图坐标定义为沿这三个基向量的点积，`viewZ=dot(position-cameraPosition,forward)` 对相机前方为正。令 `t=tan(verticalFov/2)`，相机主方向为：

```text
screen = 2*SampleUv - 1
ray = normalize(forward + right*(screen.x*aspect*t) - up*(screen.y*t))
aspect = outputWidth/outputHeight
```

即使超分的输入尺寸取整使其宽高比略有差别，相机仍使用最终输出的 aspect，射线 UV 和运动像素单位则使用实际输入尺寸。相机/FOV 变化不通过另一套符号补偿。

Streamline 的固定 C ABI 使用 row-major 存储和 row-vector 变换：`world_to_view`、`view_to_world`、`view_to_clip`、`clip_to_view`、`clip_to_previous` 与其逆均按此合同构造。矩阵不含 temporal jitter。Slang 的几何矩阵与 `mul(matrix,vector)` 按各自结构 ABI 消费；不能把 SDK 的矩阵字节直接当作几何矩阵，或仅依据语言默认布局猜一次 transpose。

## 抖动、运动与深度

RR/FG 的密集运动是 `previousUv-currentSampleUv`，表示当前采样位置向最近一次实际接受提交的前帧位置的位移。它包含已知相机与物体运动，不包含帧采样 jitter。静态相机和静态点投影回同一实际 SampleUv，因此运动应为零；不能减去像素中心而留下当前 jitter。

ReSTIR 时间复用也按无 jitter 的运动选择前帧 reservoir：`floor(pixel+.5+previousProjection(Pprev)-currentProjection(Pcur))`。投影使用已接受前相机及已证明的物体对应；当前 pinhole 主交点满足 `currentProjection(Pcur)=pixel+.5+currentJitter`，故实现只需从前投影扣除当前 jitter。前帧实际 jitter 仍用于源主射线重放，不进入 donor 地址的取整。两套合同不能混用，否则周期性的亚像素 jitter 可变为定向历史搬运；适配见 [RA-002](restir-adaptations.md)。

主/specular/FG motion 写入 RG16F 时均为输入像素单位：`MotionPixels=MotionUv*inputExtent`。SDK adapter 的 `mvecScale=(1/inputWidth,1/inputHeight)` 由锁定 Streamline 再乘输入尺寸，令 NGX 消费像素 scale 1；`cameraMotionIncluded=true`、`motionVectorsJittered=false`。SDK 的 `ProjectionJitterPixels=-SampleJitterPixels`，XY 都以输入像素计。未知对应或非法投影写有限零并带引擎独立状态，不用魔数冒充 SDK 支持的有效性标记。

| 深度/距离 | 单位与定义 |
| --- | --- |
| RR `LinearViewZ` | 正向相机 view-Z，世界单位；可能对应 PSR 提升后的表面。真实 escape 为 `FLT_MAX`，无效占位由完成状态区别 |
| FG `DeviceDepth` | 首真实可见表面的非反转 Vulkan `[0,1]` 深度，near=0.01、far=1,000,000；`saturate(a-near*a/viewZ)`，`a=far/(far-near)`；天空为1 |
| specular hit distance | 物理反射次段的路径长度，不是 view-Z；当前仅用于引擎 post 的距离代理，不作为 SDK tag |
| 相机空气段/介质路径长度 | 实际物理射线段长度，用于 aerial/Beer；不能由 PSR view-Z 或 device depth 代替 |

反射虚拟表面、厚折射局部代理、方向天空与粗糙反射距离代理的对应范围见[重建契约](reconstruction.md)。图像方向一致不意味着这些近似已成为真实光流。

## 呈现边界与 FG

核心到宿主的 Y 转换只属于呈现边界：当 `bottom_up=true` 时，目标行为 `hostY=height-1-canonicalY`，`hostX=canonicalX`。直接 raw/Offline/ReSTIR SDR、`rr_display` 和 `display/from_linear` 各自承担所选路径的这一次转换；线性中间图、RR 输入/输出、HDR world 和 baseline 仍保持 canonical 方向，不串联多次翻转。

Minecraft 26.2/26.3 的 Vulkan surface blit 将中间 UI 目标翻转到最终左上 swapchain。SDR FG 的 HUDless 直接读取 canonical baseline，mask 反向索引宿主 UI alpha；HDR pass 直接写 swapchain，按 canonical 像素读取 world/baseline，并反向索引宿主 UI。原帧与生成帧因此使用同一最终图像方向。

FG 四张输入和最终 backbuffer 均属于 canonical 空间。motion 的 Y 分量、无 jitter 矩阵、jitter 符号和共用 frame-token 常量保持核心定义，不另做一次符号翻转。此合同修正不证明 FG 果冻的全部原因已解决；实际连续帧与窗口呈现仍按 PT-017 验收。

## 纹理 UV 的范围

屏幕 UV 不定义任意第三方模型的材质 UV。当前规范 sprite-local UV 的逻辑帧左上、右下是 `(0,0)`、`(1,1)`；texel 按 `y*stride+x` 读取，无屏幕/宿主 Y flip。已经证明位于 sprite 范围内的 atlas UV 在 Rust 转为 local；明确越界来源保留原 atlas UV 的兼容退路。整张纹理采用 repeat，sprite view 采用 clamp，`RepeatUv` 的仿射/轴选择保留自身含义。

顶点 UV、atlas bounds、动态实例 UV transform 和第三方自定义姿态由源适配合同决定，不能为统一图像方向而翻转它们或猜一个固定模型切线方向。源行序、动画和 mip 的处理范围见[sprite/表面编译](surface-compiler.md#sprite动画与发光)与[材质契约](materials.md)。

## 验证边界

生产 Slang 的 CPU 合同检查覆盖实际采样位置、像素 motion、reflection proxy、非法输入、深度及呈现地址。无窗口 GPU fixtures 检查真实 RR/ReSTIR 的 FP16 guide 产物，独立世界几何投影、静止/移动相机与 FOV、前后不同 jitter、非等输入/输出 aspect、奇数尺寸边缘，以及显示/UI 各自的方向。具体执行范围以本次验证记录为准；测试产物通过不证明 SDK 私有重建、连续帧或游戏呈现正确。

实现入口是[普通 PT 射线](../crates/prime-vulkan/shaders/transport.slang)、[ReSTIR 射线](../crates/prime-vulkan/shaders/restir/scene.slang)、[RR 投影与像素运动](../crates/prime-vulkan/shaders/reconstruct/rr_guides.slang)、[CPU 相机/SDK 矩阵](../crates/prime-vulkan/src/reconstruction_history.rs)、[线性显示边界](../crates/prime-vulkan/shaders/display/from_linear.slang)、[HDR/UI 呈现](../crates/prime-vulkan/shaders/display/hdr_present.slang)和[纹理源采样](../crates/prime-vulkan/shaders/trace/closest.slang)。修改其中一处的方向或单位时，须同时检查其生产者、消费者和本契约。
