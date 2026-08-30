# GPU 几何追踪精度契约

本文规定实时积分器、离线积分器、阴影和重建 guide 共同遵守的 GPU 几何追踪契约。它解决的
不是某一类画面伪影，而是路径端点、命中位置、图元身份和动态重建的统一正确性。违反本契约
会改变可见性拓扑、介质段长或运动向量，因此不能被归类为普通的性能近似。

## 1. 权威几何域

CPU 在 BLAS 建立前为修复 Minecraft 原始模型的重叠面、装饰层和其他渲染 trick 而执行的几何
吸附属于场景语义翻译。该步骤有独立意图和测试，不在本文的 GPU 数值保护范围内，也不得借
精度重构擅自删除。

该翻译完成后，提交给 BLAS 的 f32 顶点流就是 GPU 追踪的权威几何。closest-hit 和 any-hit
必须从同一顶点流、硬件重心坐标和 `ObjectToWorld3x4` 重建命中位置；不得以
`rayOrigin + rayDirection * RayTCurrent()` 代替。场景翻译必须在发布前依据捕获的表面语义
修正顶点顺序，使 BLAS winding 从 negative side 指向 positive side；退化三角形不得提交给
BLAS。命中后的几何法线只由同一 f32 三角形的两条边计算，不再读取、解码或按独立 authored
normal 重新定向。不得用非零阈值吞掉面积很小但有效的几何。

`RayTCurrent()` 仍表示沿追踪射线的参数距离，可用于路径长度、ray cone 和光学段长；它不再
充当权威世界空间命中点。

## 2. 普通路径端点

相机射线没有源图元。每次表面散射后，路径保存精确源身份
`(sectionIndex, globalTriangleIndex)`，并从重建出的物理命中位置以 `tMin = 0` 发射下一条射线。
存在源身份时，遍历进入 any-hit，只忽略与该身份对应的源语义图元；macro rectangle 的两个
BLAS triangle 会先映射回共享的语义 primitive。除此以外的相邻面、共面但不同身份的面、
cutout、透明边界和微小位移几何都必须正常参与遍历。

因此普通路径不得使用固定法线偏移、按方向偏移、正 `tMin` 或按场景尺度调节的 epsilon 来
消除自交。这些方法会删除真实几何段，并在纹理几何位移、薄层、近共面表面和介质边界上造成
漏光或错误路径长度。

## 3. 阴影线段端点

Area NEE 的阴影连接使用：

- 源图元身份 `(sectionIndex, globalTriangleIndex)`；
- 目标发光体身份 `(sectionIndex, emitterIndex)`；
- 未偏移的物理起点、方向和完整的表面到采样点距离。

阴影遍历使用 `tMin = 0` 和完整 `tMax`。any-hit 只忽略精确源语义图元和精确目标 emitter，
不缩短线段，也不扩大忽略范围。线段中的其他不透明面、cutout、透明界面和参与吸收的介质
边界必须保留。这一规则同时适用于实时阴影和离线 Direct 阶段的立即阴影。

太阳没有有限目标 emitter，但仍携带源图元身份并采用相同的起点规则。阴影的 Beer-Lambert
累计、边界绕数和遮挡距离都作用于真实物理线段，不能通过端点偏移“修正”结果。

## 4. 动态几何与运动重建

动态命中把 31-bit 全局 triangle id 与 motion flag 保存在 `motionZFlags`，并在动态几何不使用的
emitter/texture-LOD lane 中逐位保存两项硬件 f32 重心坐标。上一帧位置和几何法线从上一帧
权威 f32 顶点流以同一插值顺序重建。CPU 必须在发布运动地址前验证 triangle 数量和索引范围；
没有相符上一帧三角形流的实例明确回退为零物体运动。

不得把重心坐标量化为 UNORM16/FP16，不得截断 triangle id，也不得恢复为 FP16 position delta。
这些压缩会在大位移、细小几何和接口旋转中制造错误的 previous virtual position，并直接污染
NRD 与 DLSS RR 的历史重投影。

## 5. Guide 与 wavefront 一致性

实时/离线 wavefront、透明 GuideWalker、反射 probe 和 TIR 链必须复用同一命中位置与源身份
契约。guide 可以选择与辐射路径不同的离散事件序列，但不能另行引入近似端点、量化身份或
独立的自交 epsilon。跨 dispatch 保存的物理 origin、源身份、命中位置和动态重建数据必须保持
其声明的 f32/整数精度。

几何法线、材质 shading normal、光源选择/MIS 的接收面法线和重建 guide normal 在 payload、
surface scratch、path record 与中间图像中一律保存为三个独立 f32 分量，不得使用八面体、
SNORM、UNORM、FP16 或其他有损编码。normal 与 roughness 不得借用最终重建输入图像作为阶段间
scratch 后再解码。NRD 以 direct signed-component 模式编译，Prime 为 NRD 与 DLSS RR 提供的
normal/roughness 图均为 RGBA32F，其中 xyz 是直接世界法线，w 是线性粗糙度；外部适配边界也
不得重新引入八面体或低精度法线编码。

不参与几何、BSDF 半球、介质或重建身份的普通单位方向可以拥有单独注明误差边界的紧凑格式，
例如灯光树的保守发射锥轴。此类接口必须以 `UnitVector`、`Direction` 或具体用途命名，不能暴露
为通用 normal pack/unpack API。

大气坐标和太阳壳层等独立数值域有各自的尺度与边界规则；其中出现的常量不能移植为
几何追踪 epsilon。

## 6. 性能与变更规则

禁止为了降低显存、带宽、寄存器、any-hit 或调度成本而引入会牺牲上述精度的化简，包括但不限于：

- 固定或距离相关的射线起点偏移、正 `tMin`、缩短有限光源的 `tMax`；
- 用射线算术重建位置，代替权威顶点与硬件重心插值；
- 量化重心坐标、位置、法线、介质参数或身份，或截断能改变图元对应关系的索引；
- 用宽泛的 instance、geometry、材质或邻域忽略代替精确源/目标身份；
- 以非零面积阈值、近似法线或不同求值顺序改变有效几何的拓扑。

只有在保持可见性、身份、精度等级和数值边界不变，并由行为测试或测量证明等价时，才允许
优化其实现。不能以性能收益抵消精度损失；若正确方案需要更多显存或 any-hit 工作，应先保留
正确方案，再单独优化其组织与执行成本。

涉及本契约的修改至少应覆盖源/目标自交、间距小于旧 epsilon 的相邻几何、macro triangle、
动态大位移与旋转、透明介质段长、guide/TIR 链以及实时/离线一致性。测试必须观察可执行结果，
不能只搜索源码中是否存在某个常量或函数名。
