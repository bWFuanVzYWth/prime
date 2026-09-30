# Section 路由原型的临时替代

仅管理 `prototype/rust-section-routing` 这一轮原型引入的近似和缺口，不合并进主 `HACK.md` / `TODO.md`。当前保持 Rust 管理范围、一次 section 批量请求/响应、按需颜色源/群系样本批次、原生编译和 Vulkan 呈现；按玩法、拓扑、形状、其余外观的顺序补齐，**未覆盖项仍不声称与原版等价**。已有实时/离线渲染、动态实例、粒子及无回读合成继续使用原有通路。

| 编号 | 当前替代及可见影响 | 撤销条件 |
| --- | --- | --- |
| P001 | 无法解析的自定义地形模型与缺失定义由 Rust 生成洋红色方块。Java 不执行或试探 `emitQuads` / geometry-key 回调；模组自定义局部变形及作者色暂不保留。 | 接入受控的必要源回调，或提供可解析的真实模型定义。 |
| P004 | 所有模型使用默认 MC 位置种子；有 offset function 的状态使用默认 XZ 抖动。不执行自定义 seed/offset，未实现额外 Y 抖动和各块特定振幅。 | 接入精确声明规则或实际源结果。 |
| P005 | 标准水/岩浆的液面、流向 UV、实际材质、overlay、背面与水淹部件已进入原生编译。自定义 fluid 类型仍用洋红代理；缺少宿主静态支撑缓存时，下落流体的动态支撑查询暂按不支撑处理并计数。标准岩浆已按源发光进入光源树；标准 block/fluid tint 已使用实际源声明/结果，标准群系颜色在 Rust 精确混合。 | 接入自定义规则、必要动态支撑回调。 |
| P006 | 已按实际缓存的 face shape 在 Rust 执行覆盖测试，保留宿主坐标合并容差；玻璃仍按同名规则处理。特殊 skipRendering（如树叶设置、透明相邻规则和模组覆写）未完整接入。 | 声明原生标准规则并补齐必要谓词回调。 |
| P007 | 从实际 palette 读取 raw section，暂不经过 `RenderSectionRegion`、调试世界合成和自定义 SectionCopy；也不使用原版首次编译的光照就绪门槛。加载/缺失与空段仍区分，未收到的段不伪装成空段。 | 源上下文兼容范围明确，所需宿主依赖进入协议。 |
| P008 | Java 对两版明确的私有资源字段作缓存反射读取。除 state-bound multipart 首次资源准备允许实际 selector 求值外，仅转录字段，不做邻域剔面或逐位置模型求值；两版均有真实类型和 FFM 夹具。未识别资源结构走 P001，不声称任意 Mixin 修改均兼容。 | 固化版本绑定方式与源布局测试，按后续版本需要替换绑定。 |
| P009 | 每段4个slab及同步颜色回填/发布仍有输出分配和精确比较成本。普通quad片段保留独立所有权，复杂接触需裁切/分层；旧快照最后引用释放归实际消费者，没有跨帧配额。 | 按原生1080p实际更新数据优化分配、复制和退休，不能牺牲语义。 |
| P010 | 源定义随 renderer/world/resource epoch 保留；global palette 首次出现时一次转录全局 state 字典，普通 local palette 按需转录。未做活跃资源细粒度退休。 | 增加明确的 native 资源需求/退休契约，并测量首次成本。 |
| P011 | 标准水与可证明闭合/共面薄片的玻璃已接入 LitePBR 与 LabPBR 法线/粗糙度/IOR。未知 tint、复杂开放/随机玻璃模型仍按源 coverage，计入 optics；消光仍为 sprite 首帧中心颜色的 homogeneous 参考，自定义裁切 UV 的局部消光未独立建立。空间/动画 G 的当前负侧和邻接正侧按各自固定采样语义更新 IOR。直线NEE不解算折射焦散，大气遮挡列仍为opaque深度。 | 按真实源契约扩展光学模型与初始介质证明，保留稳态性能证据。 |
| P012 | Sprite动画/mip已接入；明显跨sprite的UV保留原atlas采样并计入sprite，不保证该回退的动画/mip。射线锥仅估计主像素足迹，不含粗糙散射扩散。源资源仍按epoch整体保留。 | 为特殊UV、过滤和资源驱逐提供明确且有收益的契约。 |

LabPBR 的源声明、canonical G/B、法线分布过滤、辅助动画和发光已接入，不再作为材质缺失替代；具体边界见[材质契约](docs/materials.md)。AO/porosity 保留但不直接乘着色，height 已解码逐帧 minimum，未移植旧 voxel displacement 几何；非金属 authored SSS 保留 LitePBR 有色 thin 双半球/白色 thick diffuse transmission 近似，不执行体积 random walk；foliage 底层 API 尚无 MC 生产拓扑自动选择。旧 PBR presets 不移植。


标准 block/fluid tint 已保留实际 slot、ARGB、红石强度和群系混合结果，不再使用名称代表色。已识别原版源使用实际状态/常量、资源色表、seed 和 quart 群系字段，由 Rust 直接计算；未知源保留实际回调。对内置求色/群系选择算法的 Mixin 修改，以及自定义颜色回调的任意外部依赖失效尚未声明，第三方兼容不作保证。

标准 multipart 已转录实际选中的全部部件，并在 Rust 复现共享随机种子与逐部件重置；不再列为临时替代。两个版本的实际 SectionCompiler 对照只覆盖受控模型/光照，未加载完整资源包，已覆盖实际原版颜色/colormap/群系混合的受控场景，仍不代表全部原版和模组外观均等价。

`prime_minecraft` 是这些替代的解释者，`prime_scene` 和 GPU 不识别 MC 类、枚举或版本。Java 每帧至多取得一个 section 请求列表，并一次返回资源与源页；Rust 按实际产出收集颜色需求，非空时返回颜色源批次，并按需取得去重的 quart 群系字段；Rust 执行群系选择、原版求色、精确整数混合及缓存失效。颜色回填并入归并任务，不增加额外 worker 阶段。

日志开头明确标记 Section prototype。慢帧 native 诊断中的 `mc_source[...]` 给出请求、解码、编译、发布、字节和 `hacks(model,tint,offset,fluid,optics,sprite)` 计数；`compile` 内另分 `kernel` 与 `finalize`，`retire` 单列发布后的 CPU 旧引用释放，并记录实际替换/删除及保留的非空图层数。hack 计数是本次展开或资源准备的触发次数，不是完整世界中不支持对象的总数。P006/P007 等静态策略限制以本清单为准，不能通过某一帧计数为零宣称未使用近似。

实际游戏由用户手动按原生1920×1080验收；编译和无窗口场景/协议测试不等于画面或帧率已经验证。
