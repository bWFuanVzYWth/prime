# Prime PT

Prime PT 是用于 **Minecraft Java 版 26.2 和 26.3** 的 Fabric 客户端渲染 Mod，也是旧 Prime 的重写项目。它使用显卡的 Vulkan 硬件光线追踪绘制地形和常见动态物体，提供基础纹理、太阳阴影和天空间接光照，并保留原版的手部与游戏界面。

**当前仍是早期实验原型，功能少于旧 Prime。** 已接入水、玻璃、常规实体、箱子等方块实体和粒子的基础绘制，但透明效果仍较简化，部分物体和特殊效果可能缺失，画面会有明显噪点。它适合体验和反馈，尚不适合作为日常游玩的完整渲染方案。

**本分支正在测试新的地形数据通路。** 已补入标准连接部件、液体表面、水淹方块、红石强度颜色与群系草木/水色；特殊模型和可见性规则仍有缺口，未知地形模型可能显示为洋红色方块；具体替代单独记录在 [原型 hack 清单](PROTOTYPE_HACKS.md)。当前优先补齐影响玩法、连接关系和形状的缺口，仍不保证与原版画面一致。

Prime PT 以 Mod 的形式安装到 `mods` 文件夹。它不是 Shader Pack，不要放进 `shaderpacks` 文件夹。当前默认关闭，需要按下文添加启动参数才能启用。

## 运行要求

本说明面向已验证的 **Windows 64 位（x86-64）** 原生包。

| 项目 | 要求 |
| --- | --- |
| Java | 25；请检查启动器为当前实例选用的 Java 版本 |
| 游戏图形后端 | Vulkan；OpenGL 模式不能启用 Prime PT |
| 显卡与驱动 | 支持 Vulkan 1.2 及所需的硬件光线查询（Ray Query）功能 |
| 已实机验证的显卡 | NVIDIA GeForce RTX 4090；其他显卡尚未完成实机兼容性验证 |

Minecraft、Fabric API 和 Prime PT 的版本必须匹配。当前验证组合如下：

| Minecraft | Fabric Loader（已验证） | Fabric API（必须精确匹配） | Prime PT 文件 |
| --- | --- | --- | --- |
| 26.2 | 0.19.5 | `0.154.2+26.2` | `prime-pt-mc-26.2-0.1.0-native.jar` |
| 26.3 | 0.19.5 | `0.160.6+26.3` | `prime-pt-mc-26.3-0.1.0-native.jar` |

请先使用表中的组合。其他 Loader 版本尚未验证；当前安装包会限制 Fabric API 为表中的精确版本，不能直接换成任意新版。

玩家安装带有 `-native.jar` 后缀的 Windows 包时，不需要安装 Rust、Vulkan SDK 或着色器编译器，也不需要手动提取 DLL。

## 安装与启用

1. 在启动器中准备一个 Minecraft **26.2 或 26.3** 的独立游戏实例，为它安装 Fabric Loader **0.19.5**，并选择 **Java 25**。启动器支持安装 Fabric 时可使用其内置功能；使用 Minecraft 官方启动器时，可从 [Fabric 官方安装器](https://fabricmc.net/use/installer/) 开始。
2. 从 [Fabric API 版本列表](https://modrinth.com/mod/fabric-api/versions) 获取上表中对应的精确版本，并准备匹配的 Prime PT **`-native.jar`**。本项目目前没有在此提供公开发行下载地址，请向提供者索取匹配的安装包，或按文末入口自行构建。
3. 在启动器里打开这个实例的游戏文件夹，找到 `mods` 文件夹；没有时新建一个。将 Fabric API 和 Prime PT 两个 JAR 放进去，**不要解压**。同一实例只放一个对应版本的 Prime PT 包。
4. 找到启动器为该实例提供的 **JVM 参数／Java 参数**，追加下面两项，保留已有参数：

   ```text
   --enable-native-access=ALL-UNNAMED -Dprimept.enabled=true
   ```

5. 找到该实例的 **游戏参数／Minecraft 参数**，追加：

   ```text
   --graphicsBackend VULKAN
   ```

   这是游戏参数，**不要填进 JVM 参数栏**。不同启动器的栏目名称可能不同。

6. 启动这个 Fabric 实例并进入世界。首次加载地形时可能出现短暂停顿；当前实时画面保留原始噪点，可按下文启用离线累积。

游戏内可在聊天栏输入 `/primept renderer vanilla` 切回原版，输入 `/primept renderer path_trace` 切回路径追踪。切回 Prime 时会重新加载资源，期间需要等待。也可以在下述设置页切换；仅把 JAR 放入 `mods`，但没有添加启用参数时，仍使用原版渲染，游戏内命令也不能补开启动时未启用的显卡功能。

## 设置与离线累积

打开 **选项 → 视频设置 → Prime 渲染设置**。页面分为渲染、光照、显示和诊断，可切换原版/路径追踪，调整路径预算、离线每帧采样数、太阳/天空强度、曝光及 primeDRT 色相/饱和补偿。关闭页面时保存；配置版本过旧、过新或内容无效时，整份恢复默认值。

在世界加载完成、选好视角后按 **Ctrl+Alt+F2**，冻结当前捕获到的场景和相机，持续累积以减少噪点。再次按这个组合键返回实时，重新捕获当前世界。**Esc 只打开原版菜单，不退出离线模式**；也可以在设置页开关。冻结不暂停服务器或世界逻辑，HUD 和菜单继续可用，手部隐藏。窗口缩放会重新开始累积；资源重载、退出世界或切回原版会结束冻结。

离线时仍能调整曝光、primeDRT 和每帧采样数；光照和路径预算固定。诊断栏可在实时模式查看噪声色、线性深度、世界法线。当前尚未接入降噪器，这些视图用于检查渲染数据。

## 关闭 Prime PT

在设置页关闭“路径追踪”并退出页面即可保存选择；也可用 `/primept renderer vanilla` 临时切回原版。要完全关闭 Prime 的启动能力，退出游戏，把 JVM 参数中的 `-Dprimept.enabled=true` 改为 `-Dprimept.enabled=false`，或直接删除这一项，再重新启动。也可以退出游戏后从 `mods` 中移走 Prime PT 的 JAR。

关闭 Prime PT 后可以继续使用原版 Vulkan 渲染。若还要恢复之前的图形后端，删除游戏参数 `--graphicsBackend VULKAN`，再按启动器或游戏的图形设置选择。

## 画面与兼容性限制

- **世界内容不完整**：已接入常规模型、方块实体、物品和粒子；下落方块、移动中的活塞方块、拴绳、世界内文字及附魔闪光等特殊效果仍未接入，其他模组的自定义渲染不保证完整。
- **透明效果简化**：水和玻璃已进入场景，目前按纹理透明度显示，没有真实折射或水下吸收；运动画面的噪点仍较明显。
- **照明仍很基础**：太阳与天空光照目前固定，不会完整跟随游戏昼夜变化；发光方块照亮周围、玻璃折射、水面效果和复杂材质尚未实现。
- **实时噪点明显**：尚无实时降噪、DLSS 或其他图像重建。实时每帧产生新样本；只有离线模式冻结场景后累积降噪。
- **纹理支持有限**：动画纹理目前只使用首帧；高分辨率资源包、特殊模型和高级材质效果不保证正确显示。
- **加载与远处光照可能有问题**：进入世界、快速移动或大量修改方块时可能卡顿；未及时加载的区域可能造成阴影或间接光缺失，部分边缘还可能出现漏光。
- **其他渲染 Mod 尚未验证兼容性**：排查问题时，先用只有 Fabric API 和 Prime PT 的实例复现。不要假定现有 Shader Pack、其他世界渲染器或旧 Prime 的功能能与本项目同时使用。

更完整的当前缺口见 [HACK.md](HACK.md)。

## 没有生效或遇到问题

先检查是否启动了正确的 Fabric 实例，Minecraft、Fabric API 和 JAR 是否与上表一致，Java 是否为 25，以及两组参数是否分别放在了正确的栏目。

在**当前实例的游戏文件夹**中打开 `logs/latest.log`，搜索 `Prime PT`：

| 日志内容 | 含义与下一步 |
| --- | --- |
| `enabled=false` | 没有启用；检查 JVM 参数 `-Dprimept.enabled=true` |
| `Prime PT world frame completed on GPU` | 已有一帧路径追踪世界在显卡上完成 |
| `Prime renderer failed` 或 `integration unavailable` | 本次运行未能继续使用路径追踪；查看紧随其后的原因和异常，常见原因包括图形后端、显卡能力或安装包不匹配 |
| `retirement is unresolved` 或 `replacement remains blocked` | 旧渲染资源未能安全释放，无法继续切换；请保留日志并重启游戏 |
| `Native library missing` | 确认使用的是包含原生库的 `-native.jar`，而非普通 Java JAR |

遇到被捕获的初始化或渲染错误时，Prime PT 会尝试释放当前渲染器，再恢复原版；出错当帧可能不完整。若资源释放失败，切换会停止，需要重新启动游戏。若游戏崩溃，请同时保留实例目录里的 `crash-reports` 报告（如果有）。

反馈时请向项目维护者或安装包提供者附上：Minecraft、Prime PT、Fabric Loader、Fabric API 和 Java 版本，显卡型号与驱动版本，使用的其他 Mod／资源包，复现步骤，以及完整的 `latest.log`。画面问题最好附截图，并说明关闭 Prime PT 后是否仍然出现。

## 从源码构建

需要制作自己的安装包时，按 [开发与构建指南](CONTRIBUTING.md) 准备环境，在项目根目录运行：

```powershell
.\gradlew.bat verifyNativeJars
```

两个版本的安装包分别生成在 `adapters/mc-26.2/build/libs/` 和 `adapters/mc-26.3/build/libs/`，选择以 `-native.jar` 结尾的文件。开发启动、测试和调试方法统一见 [CONTRIBUTING.md](CONTRIBUTING.md)；实现原理见 [架构文档](docs/README.md)。

本项目使用 [GPL-3.0-only](LICENSE)，附加许可见 [LICENSE-EXCEPTIONS](LICENSE-EXCEPTIONS)。第三方代码保留各自许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
