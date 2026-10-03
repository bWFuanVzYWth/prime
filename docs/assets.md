# 固定资产格式与分发规范

新增或重新打包固定资产时，GPU 纹理优先使用 **KTX2**；命名数组与物理参数使用带元数据的 **Safetensors**。分发压缩优先高等级 **Zstd**。容器迁移只改变存储和加载，不能隐式改动颜色、精度、采样、维度、通道或物理语义。

星图、OpenPBR transmission 表和五份大气纹理 LUT 使用 KTX2 内部 Zstd 22；物理介质数组使用 `.safetensors.zst`。容器、解压 payload 和源身份由[资产 manifest](../crates/prime-vulkan/assets/packed-assets.json)记录。当前数据与资源生命周期分别见[显示](display.md)、[大气](atmosphere.md)和[材质](materials.md)。

## 容器选择

| 数据 | 首选容器 | 必须保留的信息 |
| --- | --- | --- |
| 星图、普通纹理、纹理形式的 2D/3D LUT | KTX2，原生 Vulkan 格式与 Zstd 超压缩 | 尺寸、层/面/深度、原有 mip、通道布局、颜色空间与传递函数、方向和必要语义元数据 |
| 命名介质数组、异形张量及物理参数集合 | Safetensors，分发文件为 `.safetensors.zst` | 张量名、dtype、shape、schema、单位、坐标映射、归一化与端点/插值约定 |

KTX2 是纹理容器，不把没有纹理意义的命名参数强行解释为图像。Safetensors 自身不提供压缩；`.safetensors.zst` 解压后的内容必须是标准 Safetensors。shader 不解析任何容器，运行时解析和上传由 Rust 资源所有者处理。

星图保留现有 BC6H_UFLOAT、D65 线性 Rec.2020 与球面立体角加权 mip；容器变化不能触发再编码、sRGB 解码或再次色域转换。数值 LUT 保留其 FP16/FP32 位模式与已有通道/参数轴，不使用图像有损编码、自动生成 mip 或隐式颜色变换。缺少源 primaries 等信息时明确记录解释依据，不把假设写成源文件声明。

## Zstd 与加载成本

这些固定资产很少变化，离线编码优先尝试最高常规等级 **22 级**（Zstd CLI 使用 `--ultra -22`），编码成本留在资产生成阶段；19级作为压缩体积和解压成本的对照。等级越高不保证文件越小；实际选择须比较压缩体积、解压耗时和内存，而非仅依据等级。解压不重复编码器的匹配搜索，但窗口大小仍影响解压内存。编码器版本、等级、窗口及影响输出的参数进入生成记录，输出应可复现。

KTX2 使用标准 Zstd 超压缩方案，保留原 `vkFormat` 和 Data Format Descriptor。各 mip 独立压缩并由标准索引定位，不能用覆盖整个 KTX2 的外层压缩取代 mip 索引。逐 mip 索引不等于逐行随机访问。当前固定纹理直接解压到 mapped upload staging，避免额外完整 decoded Vec；16K BC6H 基层需单个128 MiB staging，全链170.67 MiB可在提交前共存。该单次分配粒度、解码器内部成本与GPU完成前的全部在途资源需要分别计入峰值，不能把压缩文件体积当作 CPU/GPU 峰值。

KTX-Software 可用 `ktx deflate --zstd 22 input.ktx2 output.ktx2` 设置22级内部压缩，库接口为 `ktxTexture2_DeflateZstd(texture, 22)`。读取端不需要选择压缩等级；按容器的 Zstd 流解压即可。工具和参数依据见[官方 deflate 文档](https://github.khronos.org/KTX-Software/ktxtools/ktx_deflate.html)。

Safetensors 解压后按标准容器读取元数据和数组。解压输出长度按固定资源限制，长度、shape和GPU格式在资源边界验证；当前使用whole-frame bulk解码，不另设stream decoder窗口上限，解码器内存成本单独核算。验证后内部依赖已建立的不变量。压缩只在资产初始化时解码，不引入稳态解压、转码或每帧容器查询。上传资源继续依据其最后消费者的完成证明回收。

JAR 仍是发行容器。已做 Zstd 压缩的内容不依赖外层 Deflate 获得主要收益；比较必须测量最终 native 库和完整 JAR。当前资产编入 DLL，因此资产文件的压缩差额不能直接作为 JAR 缩减量。

## 身份、来源与验收

资产保留 schema、源版本/哈希、生成工具及参数、原始与解压内容的哈希、许可证和 credit。容器迁移须逐资源或逐 mip 证明解压后的 GPU payload 与既有资产逐字节一致；在数值、颜色或 GPU 编码也需要变化时，另行定义和验证该语义变化。

独立作者交付的锁定参考文件保持原字节与来源身份，重新封装产物置于参考目录之外。加载验证覆盖尺寸/长度、mip 完整性、metadata、压缩流损坏与不支持的格式；上传验证覆盖正确布局、颜色解释和完成回收。临时压缩比较、耗时、日志和候选产物放在被 Git 忽略的 `artifacts/`，长期文档不维护单次测量流水账。

格式依据：[KTX2](https://github.khronos.org/KTX-Specification/ktxspec.v2.html)、[Safetensors](https://github.com/safetensors/safetensors#format)、[Zstandard](https://facebook.github.io/zstd/)。
