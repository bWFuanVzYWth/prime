# Prime

[简体中文](README.md) | [English](README.en.md)

Prime 是一个用于 Minecraft 26.2 的客户端渲染 Mod。它使用显卡提供的 Vulkan 硬件光线追踪
重新绘制游戏世界，让阳光、天空、发光方块、玻璃、水和普通材质共同参与光线传播。

Prime 首要支持兼容的 NVIDIA RTX 显卡，并通过 DLSS Ray Reconstruction 提供最佳体验。
其他满足 Vulkan 光线追踪要求的显卡作为兼容性支持，使用 NRD 与 FSR 完成实时降噪和重建。

> Prime 仍处于早期开发阶段。当前版本可能出现画面错误、缺失效果、性能问题、崩溃以及
> Mod 或资源包不兼容。Prime 是仅客户端 Mod，不以修改存档为目的，但仍建议为重要世界
> 保留备份。

[下载发行版](https://github.com/bWFuanVzYWth/prime/releases) ·
[报告问题](https://github.com/bWFuanVzYWth/prime/issues) ·
[已知问题](docs/FIXME.md)

## Prime 是什么

Prime 会接管 Minecraft 的世界渲染，在实际方块、实体和材质几何上追踪光线。发光方块可以
照亮周围，阳光和天空能够产生直接与间接照明，玻璃和水会折射并按传播距离吸收光线。

Prime 不是 Iris 或 OptiFine Shader Pack，也不能放入 `shaderpacks` 文件夹使用。许多现有
Shader Pack 使用光栅化、屏幕空间效果或软件体素光线追踪，并且通常拥有更广泛的平台、版本
和效果支持。Prime 选择的是 Vulkan 硬件光线追踪路线，两者具有不同的兼容性、性能和画面取舍。

## 主要特点

- **硬件路径追踪照明**：阳光、天空和发光方块能够直接或间接照亮场景，材质之间会传递
  颜色和亮度；
- **天文天空**：太阳轨迹由观测纬度和季节控制，并提供大气、昼夜变化和 Gaia 星空；
- **透明材质与体积吸收**：玻璃和水能够折射光线，染色介质会按传播距离吸收不同颜色；
- **PBR 材质支持**：支持部分 LabPBR 1.3 数据，包括分布感知过滤的法线、粗糙度、金属、
  介电反射和发光信息；
- **动态场景**：路径追踪场景会接收地形、实体、方块实体、移动方块和文字等动态内容；
- **实时降噪与重建**：首要支持 NVIDIA DLSS Ray Reconstruction，并为其他兼容显卡提供
  NRD + FSR；
- **SDR 与 HDR 输出**：使用统一的 Reinhard-Gamut 显示变换；HDR 会查询当前屏幕的亮度能力，
  以线性 scRGB 延伸高光，并支持自动或手动标定参考白；
- **离线渲染模式**：冻结当前场景并在原生分辨率累积未经降噪的原始样本。它能保留更多
  细节并避免实时重建伪影，适合追求质量更高的最终结果；但通常比实时画面噪点更多，需要
  更长时间才能收敛；
- **纹理表面细节**：可互斥选择资源包法线或实验性的纹理几何位移；
- **随时恢复原版渲染**：可以在游戏内关闭 Prime，遇到兼容性或性能问题时无需退出游戏。

## 运行要求

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 64 位（x86-64） |
| Minecraft | 26.2 |
| Mod Loader | Fabric Loader 0.19.3 或更高版本 |
| 依赖 | 与 Minecraft 26.2 对应的 Fabric API |
| Java | 25 |
| 首要支持 GPU | 支持 Vulkan 光线追踪和 DLSS RR 的 NVIDIA RTX 显卡 |
| 兼容性支持 GPU | 支持 Vulkan KHR ray tracing pipeline 与 acceleration structure 的其他显卡 |

建议使用兼容的 NVIDIA RTX 显卡和最新稳定驱动，以获得 Prime 当前验证最充分的路径和最佳
体验。其他兼容显卡使用 NRD + FSR，功能和画面稳定性可能因设备与驱动而异。

玩家安装发行版时不需要 Vulkan SDK、编译器或其他开发工具。发行 JAR 已包含运行所需的
Windows 原生库。Prime 对 GPU、显存和 CPU 场景流送的要求明显高于原版。

## 安装

1. 安装并至少启动一次 Minecraft 26.2。
2. 使用 [Fabric 官方安装器](https://fabricmc.net/use/installer/) 为 Minecraft 26.2 安装
   Fabric Loader 0.19.3 或更高版本。
3. 下载与 Minecraft 26.2 对应的 [Fabric API](https://modrinth.com/mod/fabric-api/versions)
   和 [Prime 发行版](https://github.com/bWFuanVzYWth/prime/releases)。下载的两个文件都应以
   `.jar` 结尾，不要解压。
4. 打开启动器中当前 Fabric 实例的游戏文件夹。没有 `mods` 文件夹时，在该目录中新建一个。
5. 将 Fabric API 和 Prime 的 JAR 一起放入 `mods` 文件夹。
6. 从启动器选择刚安装的 Fabric 实例并启动游戏。
7. 在图形设置中选择 Vulkan 后端，然后按提示重启游戏。
8. 打开“视频设置”，找到带有 `Prime：` 前缀的设置。

如果 Prime 设置没有出现，优先检查是否启动了正确的 Fabric 实例、Minecraft 版本是否为
26.2，以及 Fabric API 和 Prime 是否位于同一个实例的 `mods` 文件夹。

如果 Vulkan 或所需光线追踪功能不可用，Prime 会停止接管并保留原版世界渲染。日志会记录
无法启用的原因。致命错误提示会在本次游戏会话中持续显示；标题直接包含已安装的 Prime
版本和运行状态，正文最多四行，保留根异常与关键场景上下文，便于在日志缺失时用截图诊断。

### 首次启动

首次启用 Prime，或者更新显卡驱动和 Prime 着色器后，显卡驱动可能需要数分钟编译渲染管线。
期间游戏窗口可能暂时无响应。除非日志已经明确报告失败，否则请等待编译完成；后续启动通常
会复用驱动缓存。

## 常用设置

- **降噪与图像重建**：兼容的 RTX 显卡可使用 DLSS RR；不可用时会回退到 NRD + FSR；
- **重建质量预设**：默认“性能”。画面流畅时可以逐步提高，帧率不足时降低；
- **额外镜面反弹次数**：控制实时主表面前的 Delta 反射/折射链，范围 1–64，默认 16；
- **最小反弹次数**：控制实时固定无 RR Wavefront 的轮数，范围 1–8，默认 2；
- **最大反弹次数**：控制常规传输上限，范围 1–64，默认 16；实时中低于最小值时不会缩短固定
  Wavefront，离线模式直接使用该总上限；
- **地形后台线程比例**：默认使用 Minecraft 后台线程上限的 50%，降低可减轻地图加载时的
  CPU 争抢，提高则让 Prime 几何更快出现；无论比例多低都会保留至少一个工作线程；
- **自动曝光强度**：默认 60%，决定画面在室内外明暗变化时自动调节曝光的程度；
- **曝光补偿**：整个画面偏亮或偏暗时优先调整；
- **HDR 输出与参考白**：只在当前屏幕和 Windows 已启用可用 HDR 时开放。参考白默认使用
  系统查询值，也可按 nit 手动标定，以保持 SDR 与 HDR 模式下的主体亮度一致；
- **离线渲染模式**：冻结场景并累积原始样本。画面稳定后按 `F2` 保存，按 `Esc` 或
  `Ctrl+Alt+F2` 退出。

将鼠标停留在 Prime 设置上可以查看用途、调节方向和建议。完整的显示、材质、天文和诊断
选项不在 README 中重复列举。

## 材质包、Mod 与 Shader Pack

Prime 使用 Minecraft 的模型和资源系统构建光线追踪场景，因此普通资源包可以直接使用。
按 `format=lab-pbr/1.3` 声明的资源包可以提供 Prime 当前支持的 PBR 材质信息。

依赖特殊世界渲染、后处理、非标准模型回调或自定义 GPU 管线的 Mod 不能假定与 Prime 兼容。
只修改界面、物品管理或其他非世界渲染功能的 Mod 通常更容易兼容。Prime 当前不能与普通
Shader Pack 同时用于世界渲染。

## 明确接受的限制

以下限制是当前设计或资源边界的一部分，不作为待修 Bug 隐藏：

- NRD + FSR 无法可靠降噪彩色玻璃后的传输信号，可能保留明显噪点或时间性不稳定；
- 超高分辨率的镂空纹理会在有限精度内近似，极细透明边缘可能缺失或产生多余覆盖；
- 透明介质最多可靠嵌套两个非空气区域。更深嵌套、非封闭模型和无法判断内外的边界不保证
  正确吸收或折射；
- 实时透明照明在首接口采用固定条件双分支和直线阴影滤光，后续采用有界单分支；离散事件
  沿各辐射分支实际选择的 continuation 累积 guide，运动、链溢出、上限或非法状态回退真实
  可见接口，不执行独立 guide 回放，但仍不完整求解任意多层折射光路；
- 连接光源的阴影光线不会随透明界面折射，只会沿原方向累计介质吸收；
- 太阳体积阴影只近似单方向太阳可见性，不表示天空、局部灯光或参与介质多次散射。

完整技术边界见[已知问题中的“明确接受的限制”](docs/FIXME.md#明确接受的限制)。仍计划修复的
缺陷也记录在同一文档，但不与上述限制混为一谈。

## 报告问题

请在 [GitHub Issues](https://github.com/bWFuanVzYWth/prime/issues) 提供：

- Prime、Minecraft、Fabric Loader 和 Fabric API 版本；
- GPU 型号与驱动版本；
- 使用的资源包、相关 Mod 和后处理模式；
- 可重复的操作步骤；
- 完整日志；画面问题最好附截图或短视频。

若问题只在 Prime 中出现，请同时确认切换回原版渲染后是否消失。游戏内“Prime：诊断”小节
提供渲染诊断信息和 GitHub 仓库入口。若无法找到日志，请至少完整截取持续显示的 Prime
错误提示；其中直接携带版本和精简错误信息，不需要另行解释错误码。

## 从源码构建

普通玩家应直接下载发行 JAR。只有需要修改代码、验证最新提交或制作自己的构建时，才需要
下面的开发工具。

构建环境固定使用 JDK 25 和完整的 Vulkan SDK 1.4.357.0。Slang 与 SPIR-V Tools 均使用该
SDK 的配套版本，不需要单独安装。
下载并解压 Prime 源码后，在源码文件夹中打开 PowerShell，按实际安装位置设置路径并运行：

```powershell
$env:JAVA_HOME = 'C:\WorkSpace\_tools\temurin-25.0.4\jdk-25.0.4+7'
$env:VULKAN_SDK = 'C:\VulkanSDK\1.4.357.0'
$env:Path = "$env:JAVA_HOME\bin;$env:VULKAN_SDK\Bin;$env:Path"
.\gradlew.bat build
```

构建成功后，唯一的发行 JAR 位于 `build\libs`。环境检查、测试、开发客户端和着色器调试
见[构建与验证](docs/构建与验证.md)。

## 项目文档

### 使用与总览

- [已知问题与明确接受的限制](docs/FIXME.md)
- [构建与验证](docs/构建与验证.md)
- [渲染实现](docs/渲染实现.md)
- [架构与数据流](docs/纯函数式架构.md)

### 场景、资源与材质

- [区块簇场景翻译架构](docs/区块簇场景翻译架构.md)
- [纹理翻译架构](docs/纹理翻译架构.md)
- [统一材质 IR 与闭包](docs/统一材质IR与闭包.md)
- [OpenPBR 紧凑实现](docs/OpenPBR紧凑模块.md)

### 光传输与显示

- [离线光传输契约](docs/离线光传输契约.md)
- [透明渲染与实时重建](docs/透明渲染与实时重建.md)
- [HDR 输出](docs/HDR输出.md)

### 工程契约

- [GPU 几何追踪精度契约](docs/GPU几何追踪精度契约.md)
- [生产 Shader 编译边界](docs/生产Shader编译边界契约.md)
- [生产 Shader 零诊断成本](docs/生产Shader零诊断成本契约.md)
- [着色器属性测试与数值诊断](docs/着色器属性测试与数值诊断架构.md)

### 维护记录

- [TODO](docs/TODO.md)
- [斜水面细密黑纹排查与修复记录](docs/斜水面细密黑纹排查报告.md)

## 相关开源项目

Prime 并不是唯一探索 Minecraft 硬件光线追踪的开源项目。以下项目采用各自的架构、功能范围
和硬件支持策略，也值得关注：

- [Caustica](https://github.com/ComfyFluffy/Caustica)：面向 Minecraft Vulkan 后端的硬件
  光线追踪渲染器；
- [Radiance / MCVR](https://github.com/Minecraft-Radiance/MCVR)：由 Radiance 使用的 C++
  Vulkan 渲染框架。

传统 Shader Pack 生态也提供了大量实时光照和路径追踪方案，其中一些使用软件体素光线追踪。
它们和 Prime 面向的兼容性、硬件范围及使用方式并不相同。

## 许可与归属

Prime 自有代码使用 [MIT License](LICENSE)。NRD、DLSS、FidelityFX 和 RoboCute 相关组件
保留各自许可；完整文本见 `THIRD_PARTY_LICENSES`。

夜空资源来自 [NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/)：
NASA/Goddard Space Flight Center Scientific Visualization Studio。Gaia DR2：
[ESA/Gaia/DPAC](https://gea.esac.esa.int/archive/documentation/GDR2/Miscellaneous/sec_credit_and_citation_instructions/)。
星座图形基于 Alan MacRobert 为 IAU 制作并发表于 *Sky and Telescope* 的版本
（Roger Sinnott 与 Rick Fienberg）。完整归属与无损重打包说明见
`THIRD_PARTY_LICENSES/NASA-DEEP-STAR-MAPS-2020-NOTICE.md`。
