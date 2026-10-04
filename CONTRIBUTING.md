# 构建、测试与开发

玩家安装和使用见 [README](README.md)。模块、数据流和接口契约见 [架构索引](docs/README.md)，协作约定见 [AGENTS.md](AGENTS.md)。以下命令从项目根目录执行，示例使用 Windows PowerShell。

源码格式与行尾遵循[统一规范](docs/guides/git-line-endings.md)，使用 `.\scripts\format.ps1` 应用、`.\scripts\format.ps1 -Check` 检查。性能优先级为稳态帧数 > 显存节省 > 加载效率；当前 CPU 优化属于 [TODO](TODO.md) 中的非阻塞待办。

修改性能敏感的 PT 数据流、材质准备、查询、调度或输出状态前，阅读 [PT 依赖与性能设计](docs/pt-state-design.md)，在设计说明中先回答其中的[性能问题](docs/pt-state-design.md#修改前必须回答的性能问题)，列明假设与验证计划；提交说明补充相关答案和实际验证边界。当前设计不声明最优，也不锁定实现；临时方案、测量与复盘保存在 `artifacts/`，实现改变后同步维护稳定文档。

首次克隆后运行 `.\scripts\install-hooks.ps1`，为本仓库启用 pre-commit 格式检查。钩子只检查暂存快照，不自动格式化或暂存文件；已有其他 hooksPath 时停止，避免覆盖现有钩子。

## Git LFS 资产准备

克隆前安装 Git LFS，并在本机首次使用时执行 `git lfs install`。已有仓库可执行 `git lfs install --local`，仅配置当前仓库；保留已有自定义 hooks 并检查 LFS pre-push hook 已接入，不用覆盖 hooks 的选项跳过冲突。正常 `git clone` 会检出 LFS 完整文件；使用跳过 smudge 的克隆或缺少对象时，在构建前补全：

```powershell
git lfs pull
git lfs fsck --objects --pointers HEAD
git lfs status
```

固定非文本资产和锁定运行时的 LFS 范围见[资产规范](docs/assets.md#git-lfs-存储要求)。新增文件先配置 `.gitattributes` 再暂存，使用 `git check-attr filter diff merge text -- 文件路径` 确认属性，并用 `git lfs ls-files` 核对索引中的指针；工作副本应保留原始文件字节。CI 同样必须在构建前下载 LFS 对象，普通源码压缩包不能默认视为已经包含完整资产。

普通 `git push origin dev` 由 LFS pre-push hook 上传所需对象；上传失败必须处理，不能禁用 hook 只推指针。首次发布重写后的历史时，先核对目标分支与远程当前提交，再上传其历史对象，例如 `git lfs push --all origin dev`，最后推送该分支。只有远程已有被重写提交时才需要按已核对的远程提交使用显式 lease；不使用无差别 `--mirror` 或强推所有本地分支。

## 开发环境

| 工具 | 用途 |
| --- | --- |
| JDK 25 | Java 编译、Fabric 开发客户端及 FFM |
| Rust 工具链 | 使用根 `rust-toolchain.toml` 固定的 nightly，包含 rustfmt/clippy；构建 native 引擎与 `std::simd` 内核 |
| Slang 的 `slangc` | 将 shader 编译为 SPIR-V；构建 Vulkan crate 时需要 |
| Vulkan 驱动与兼容光追显卡 | 运行 GPU 测试和实际游戏 |
| Vulkan validation layer / SPIR-V Tools | GPU 同步检查与 SPIR-V 验证 |

使用仓库的 Gradle Wrapper，不必另装 Gradle。Windows 的 Rust 工具链还需要对应的 MSVC 链接及 C 编译工具，后者用于构建 native 分配器。仅运行 Java 编译与测试不需要 Rust 或 Vulkan SDK；纯 CPU Rust 测试可排除 Vulkan crate。

`std::simd` 仍是 nightly 的 `portable_simd` 接口，仓库固定 `nightly-2026-09-11`；通过 rustup 运行 cargo 会选择该工具链，不使用 `RUSTC_BOOTSTRAP`。CPU 内核按512-bit逻辑向量编写，由编译目标降为较窄向量或标量，ABI仍使用普通数组。`.cargo/config.toml` 为 x86-64 默认启用 `target-feature=+avx2`，发行包要求 AVX2；不要求 AVX-512、不启用 `target-cpu=native`，也不包含运行时指令集分派。需要验证较窄目标时可用 `RUSTFLAGS='-C target-cpu=x86-64 -C target-feature=-avx2,-avx'` 覆盖，并使用独立 target 目录保存产物。参考 [Rust portable SIMD](https://doc.rust-lang.org/nightly/std/simd/index.html)。

shader 构建按以下顺序寻找 Slang：`SLANGC` 指向的可执行文件、`VULKAN_SDK` 下的 `Bin/slangc.exe`（Linux 为 `bin/slangc`）、`PATH` 中的 `slangc`。这些路径属于本机配置，不写入项目文件。当前运行支持范围见 README，不因存在 Linux 构建分支就视为已完成 Linux 验证。

Windows 构建的 Streamline C++ 静态桥接需要 MSVC C++ 工具链；Streamline 与 Vulkan 头文件及运行时按来源/哈希锁定在 `third_party/`，构建不隐式下载最新版。升级须从官方 GitHub 重新解析 release、更新锁定文件，并一起验证 Vulkan interposer 初始化、真实 Present 与 RR/FG 完成证明。Gradle `buildNative` 将锁定的九个 runtime DLL 放到 `target/release`；指定自定义 `nativeLibrary` 时须将整套对应 DLL 放在引擎旁。发行 `nativeJar` 包含同一套 DLL、全部 `licenses/` 文件及许可声明。

所有 shader 默认使用 `-O3 -g3` 编译，保留优化并生成最高级别调试信息，供 GPU 分析工具使用。

生产 SPIR-V 由 `prime_shaders` 构建，`shader-tests` 额外启用 `prime_shader_tests`；固定资产由 `prime_render_data` 提供。它们通过非内联字节访问函数隔离大型 payload，Vulkan 宿主的普通 Rust 编辑不触发 Slang 或重新编译固定资产。`prime_vulkan/build.rs` 只构建 native bridge。

`prime_shader_build` 按入口、宏、编译参数、工具链内容和 Slang 实际报告的 import/include 依赖保存内容缓存，默认位于 `target/prime-shader-cache/v1`，可跨 check/test/release 的 OUT_DIR 复用。修改 shader 后只重新编译有效依赖受影响的变体；新增、删除或遮蔽模块会重新发现依赖。缓存损坏或缺失会重新构建，不依赖人工复制旧 SPV。`PRIME_SHADER_CACHE` 可指定缓存父目录，`PRIME_SHADER_JOBS` 可设置正整数并行上限（默认4），额外 worker 仍受 Cargo jobserver 限制。工具链升级会失效缓存；缓存是本地产物，不提交 Git。

缓存自身的 CPU 单元测试为 `cargo test -p prime_shader_build --locked`；实际 Slang 缓存/失效/并发行为测试需显式执行 `cargo test -p prime_shader_build --locked -- --ignored --test-threads=1`，只编译小型夹具，不启动 GPU 或窗口。

## 构建与发行包

```powershell
# 公共 Java 层及两个 MC 适配器的编译和单元测试
.\gradlew.bat build

# release Rust 引擎、两个含原生库的 JAR，以及包间一致性检查
.\gradlew.bat verifyNativeJars
```

`verifyNativeJars` 自动调用 `buildNative`，再分别构建两版 `nativeJar`。检查内嵌引擎、runtime DLL、公共桥接 class 和许可文件的字节，以及 Minecraft 和 Fabric API 精确版本约束。普通 `build` 不会生成可直接分发的 native JAR。

产物位置：

- `adapters/mc-26.2/build/libs/prime-pt-mc-26.2-<version>-native.jar`
- `adapters/mc-26.3/build/libs/prime-pt-mc-26.3-<version>-native.jar`

版本取自 `gradle.properties` 的 `mod_version`。两个 JAR 使用同一 `target/release/prime_engine.dll`，各自包含公共 Java 层。Minecraft/Fabric API 版本在各适配器的 `build.gradle` 中声明；升级时检查实际宿主和 Indigo 注入点。

## ABI 生成与验证

公共 C ABI 为 v13，Minecraft 源 schema 为 v7，配置文件 schema 为 v9。JAR 与 DLL 必须配套重建；旧字节入口不再导出。只修改 `crates/prime-engine/include/prime.h` / `prime_mc.h`，由头文件生成 `prime_abi/src/generated.rs` 和 Java `PrimeAbi`，不要手工维护三套布局。

```powershell
python scripts/generate-abi.py --probe clang
python scripts/generate-abi.py --check --probe clang
cargo test -p prime_scene --test typed --locked
cargo test -p prime_engine --no-default-features --lib --locked
./gradlew.bat :common:test
```

生成器使用仓库 rustfmt/clang-format 规范化产物；`--check` 不改文件。C11 编译探针核对所有 POD 的尺寸/对齐/字段偏移和函数签名，Rust 编译同时约束真实 export 类型，Java/native 行为测试验证实际具名字段与指针借用。原始顶点/像素保留 payload span，不得用 typed wrapper 封装旧 stream 或引入逐 section FFM。MC 总源数组没有 generic 单批256 MiB上限；保留 checked 地址范围和真实像素预算。运行/消费预编译产物不依赖 Python 或 C 编译器。

## 开发客户端

本节命令供用户手动执行。自动化默认只做格式、编译和无窗口单元测试；没有用户新的明确请求，不运行 `runClient`、启动游戏验收或占用前台。

```powershell
.\gradlew.bat :mc-26.2:runClient
.\gradlew.bat :mc-26.3:runClient

# ReSTIR PT Enhanced 独立后端，按对应版本手动启动
.\gradlew.bat :mc-26.2:runClient -PprimeptRenderer=restir_pt
.\gradlew.bat :mc-26.3:runClient -PprimeptRenderer=restir_pt
```

按版本单独运行。各适配器的 `run/` 保存日志、选项、截图和存档。使用独立测试存档或副本，不让较新版本直接升级旧版验证存档。启动默认请求 Vulkan 和 1920×1080；仍需从实际设备日志和主 target 尺寸确认后端与分辨率。

根 `runClient` 是 26.2 的别名，另有 `runClient26_2` 和 `runClient26_3`。常用属性如下：

未指定 `nativeLibrary` 时，适配器 `runClient` 依赖 `buildNative` 完成；并行 Gradle 也不会一边替换 DLL 一边启动游戏。指定库路径时由调用者保证产物已经构建完成，适合保存不可变二进制做对比。

| Gradle 属性 | 用途 |
| --- | --- |
| `-PprimeptEnabled=false` | 显式关闭 Prime 启动能力；未指定时与发行包一致，默认启用 |
| `-PprimeptRenderer=vanilla/path_trace/restir_pt` | 启动时选择独立的世界渲染器；默认保留之后切换 Prime 所需设备能力，未指定时使用保存的设置（初始为 path_trace） |
| `-PnativeLibrary=绝对路径` | 指定引擎库，适合同一不可变 DLL 的双版本验证 |
| `-PprimeptValidation=true` | 启用宿主 Vulkan validation |
| `-PprimeptProfile=true` | legacy Java 捕获细计时，默认关闭；新采集使用游戏内诊断设置 |
| `-PprimeptProfileLeaves=true` | 配合 profile 开启逐 Cube 细计时，默认关闭；只用于成本归因 |
| `-PprimeptProfileCsv=绝对路径` | legacy 逐帧 CSV；部分旧列没有生产赋值，新分析使用 JSON |
| `-PprimeptCaptureAudit=true` | 记录动态源捕获计数摘要；不能作为逐 quad/tint/light 对拍 |
| `-PprimeptGeometryCache=false` | 关闭默认启用的 Fabric wrapper geometry-key 缓存，用于同构建对照；无 key 或不兼容 wrapper 仍走实际源路径 |
| `-PprimeptWorld=存档目录名` | 使用 quick play 进入该版本 run 目录下的测试世界 |
| `-PprimeptUuid=玩家UUID` | 在测试副本中读取指定已有玩家的位置和状态 |

默认使用 release 原生引擎，启用 Prime 与几何缓存，关闭 validation、profile、细叶计时及 capture audit。`-P` 属性只影响开发客户端启动，不会写入 JAR；发行包本身使用相同的运行时默认值。外部启动器对应的 JVM 参数见 README；不要将 Gradle 的 `-P` 属性直接交给 Java。

性能采样使用游戏内“视频设置 → Prime PT 诊断”的“录制性能 JSON”开关。启用后逐帧记录 Java/native 任务及延迟完成的 GPU 原始 timestamps，关闭、退出世界或关闭 renderer 时后台导出到对应版本 `run/artifacts/performance/`。JSON 使用短字段与字典，保存起止、时长、线程、父任务、实际计数和缺失状态；原始时钟域与格式见[诊断契约](docs/diagnostics.md)。慢帧长日志已移除，尖峰保留在事件中。比较时分开稳态窗口与进入/退出离线的过渡，不把冻结省去的游戏模拟、源准备和资源更新误计为路径着色器差异。

显式 legacy CSV 同时记录实时与离线帧，末列 `offline` 标识模式；`drain/prune/section_batches` 等历史列没有生产赋值，旧 `section_bytes/resource_submit` 也不代表完整 section/资源开销。不要把这些零值当作实测阶段或用于新基线。

客户端命令 `/primept renderer vanilla`、`/primept renderer path_trace` 与 `/primept renderer restir_pt` 只请求切换，实际资源移交在下一外层帧边界执行。验证时覆盖 Prime→原版→Prime、世界退出/重进、标题界面资源重载及退休失败；等待资源加载完成再采样，不把切换暂停计入稳态。首个 Prime 后端使用宿主事件驱动的源路由，加载完成的判据包括待路由地形事件清空，不能仅等待首帧输出。新增后端通过惰性工厂注册，并遵守公共 `RendererSlot` 的完成/失败契约。

光源采样方式在光照组选择，默认 `TREE` 功率距离树，另可选 `TREE_SPHERE` 包围盒中心球界方向树。游戏内修改在下一外层帧边界生效，无需重启；关闭设置页保存选择。切换先完成旧宿主提交，重建所选独立shader管线和灯表，保留已发布几何/BLAS与共享GPU发光记录。实时 RR 与 ReSTIR 历史继续复用，ReSTIR 在当前场景更新后缀/PDF；离线累积重置，冻结相机与源保持。切换可能有一次暂停，不计入稳态性能。稳态没有采样方式的GPU运行时分支；为切换保留的CPU灯源有实际驻留成本，见[PT设计](docs/pt-state-design.md#光源采样特化与切换成本)。

对比时固定同一世界副本、相机/移动路线、种子、预算、原生1920×1080、RR/OMM与硬件；每种方式各录制加载/更新及停止更新后的稳态窗口，保留离群帧。JSON `cap.start`/`cfg` 的 `ls=1/2` 分别表示功率距离Tree/TreeSphere（旧记录的0表示已退役Grid）；以实际录制的配置和切换事件划分窗口。比较 `lt.*`/`ls.*`、`lights.switch`、总static/提取与GPU K2/整帧时间。球界树仍是游戏实测候选，小样板的连续PMF、选光微基准或单场景收益不能外推生产质量与帧率，也不足以决定默认方式。

诊断组新增默认关闭的“忽略所有全局重置”，用于对照活动游戏/暂停界面历史；日志明确 event/action/valid，性能 JSON 可进一步区分 temporal、suffix update 和身份表局部支持变化。实际存储重建仍冷启动。原“DLSS光线重建”诊断选项仅改显示名为“原生分辨率含噪输出”，旧开关含义保持：关闭=原生含噪，开启=RR；不要把显示文字变化误当作布尔反转。

### 用户手动检查重点

1. 两版分别使用对应版本的测试存档或副本，确认实际 Vulkan 后端与原生 `1920×1080` 主图像，等待地形加载完成。较新版本保存过的世界不要交给旧版本验证。
2. 观察地形、透明表面、实体/方块实体、框内物品、掉落物和粒子是否缺失或重复；检查旋转/移动、物品内容变化与持续增删，以及手部/HUD 是否正常。特殊文字、glint、outline 和折射仍按当前支持范围判断。
3. 依次切换到 `vanilla` 和 `path_trace`，等待各自就绪；检查原版地形恢复、PT 重新加载，以及皮肤、地图等动态纹理。再检查世界退出/重进与资源重载后是否正常。
   同资源代世界切换应复用纹理/OMM；停留标题界面时旧世界几何应已退休。覆盖离线退出世界及仅模型资源换代，确认实时源恢复、实体定义和动态纹理重新发布，不能只验正常 atlas 重载。
4. 检查对应 `adapters/mc-*/run/logs/latest.log`，记录异常、Vulkan `VUID` / `SYNC-HAZARD`、缺失纹理或后端恢复失败。反馈版本、操作步骤、场景与日志，截图/日志副本放 `artifacts/`。
5. 在两版分别拖动窗口、切换全屏、最小化/恢复；覆盖横/竖/奇数尺寸并回到 1920×1080。检查宽高比、边缘覆盖、历史残影和 HUD 方位；关闭 RR 的实时应保持新噪声输出，开启 RR 时检查重建稳定性；离线调整尺寸时重建累积，稳定后应继续收敛。观察高饱和材质、灰阶和亮部的 primeDRT 输出，以及细缝/斜面是否自遮挡或漏光。

6. 在“Esc → 选项 → 视频设置”列表顶部检查四组 Prime PT 控件、默认恢复与关闭后持久化；标题画面的视频设置也应显示同一组控件。覆盖简体中文/英语、不同 GUI 缩放，确认四个组标题都有 Prime PT 前缀，没有翻译键、截断或重复控件，原版视频选项仍可用；RR 与 OMM 开关应位于诊断组。确认新默认饱和度补偿为20%，已有合法保存值（包括8%）不被覆盖，重新启动后配置生效。退出客户端后将 `config/primept.properties` 的 `version` 改成不匹配的值，再启动应整份回退默认，日志说明原因。
7. 世界加载完成后用 Ctrl+Alt+F2 进入离线，确认视角/实体/粒子固定、噪点持续减少；按 Esc 打开菜单仍保持离线。曝光、primeDRT 和每帧采样数可以修改，路径/光照固定。调整尺寸后重新累积；再次按快捷键应重新捕获当前世界，地图/动态纹理不能过期。覆盖冻结时 F3+T 重载、切原版、退出/重进世界。
8. 实时诊断依次查看原始噪声色、线性深度、世界法线；检查物体边缘、alpha 表面和天空（深度/法线预览为黑）。修改深度范围只改变预览；回到最终输出后 primeDRT 正常。开启 RR 时这些诊断显示实际内部输入；诊断显示期间继续执行 RR 并推进已接受的相机历史，返回最终输出不触发重置；因此诊断仍包含正常 RR 的 GPU 成本。
9. 使用声明 `format=lab-pbr/1.3` 的资源包，检查 `_n` 法线与远处粗糙度、`_s` 的介质/金属分类、玻璃 IOR、发光零值和 255 哨兵；覆盖地形与 atlas UV 的物品、纹理动画、资源重载、实时/离线切换。分类通道 G/B 应保持基础层身份，动画分类采用当前帧；缺图遵循全局缺省和已有宿主源规则。高度解码与自动 foliage 材质选择的支持边界见 [材质契约](docs/materials.md)。
10. 检查星图方向、明暗、前景边缘与水面反射，覆盖星图强度零值、RR 开关、天空亮度和冻结；天空亮度与星图强度分别控制。检查暗室/明亮室外间曝光适应、手动曝光只乘一次、进入离线后的固定曝光，以及同时切换模式和曝光补偿强度时的重测。
11. 在启用 Windows HDR 的实际显示器测试 HDR 开关、SDR 白自动/指定值、峰值、跨显示器移动与 scRGB swapchain；检查 HUD/手部、标题菜单和无世界时的亮度/方位。FG 默认关闭，只有实时 RR 最终输出及 SDK 能力满足时生效；分别检查 SDR/HDR 下 HUD 稳定、真实 Present、窗口变化、后端/模式切换与关闭资源。离屏测试不证明实际生成帧、显示器标定或整帧速度，见[显示合同](docs/display.md)。
12. 两版分别检查诊断和“录制性能 JSON”：默认关闭、终端不输出慢帧长日志；开启后覆盖稳态、移动/编辑、实时/离线。关闭采集应后台导出一份可解析 JSON，再次开启产生新会话；退出世界、后端切换和关闭客户端也应收尾。检查线程/frame/parent、GPU delayed serial 与 pending/null，保留尖峰；确认关闭采集但保留诊断时仍能导出完整尾部。游戏性能比较另外固定场景和预算，区分采集开销。
13. 在已加载的同一场景依次切换网格、功率距离树、球界方向树，再切回网格，检查无需重启即可生效、世界不因采样方式消失、灯和发光命中无缺失。分别覆盖raw、RR及冻结Offline；Offline姿态、光照和种子保持，切换后从新累积开始。记录切换暂停及切换后的稳态窗口，检查JSON `cfg.ls`与设置一致；世界重进和重载后仍使用保存的方式。检查validation日志，切换失败不能继续创建或使用资源。

正确性检查时显式追加 `-PprimeptValidation=true`，此时不作性能结论。性能采样保持 validation、legacy profile、细叶计时和 capture audit 关闭，在游戏内启用性能采集，固定场景、相机、画质、射线预算与原生1920×1080分辨率，停止后保存 JSON。积压清空后的原型稳态应只交换请求/响应头，`requested/compiled/tint_requests` 为0；覆盖边缘可能仍不完整，pending=0 不能证明全部64段单元齐备。分别记录稳态及更新阶段；CPU 优化目前非阻塞，不以即时 FPS 达标作为本轮检查的前提。

## 按改动选择验证

### 光源采样

`./scripts/test-light-tree-cpu.ps1` 直接编译生产 Slang 树选择/PDF 为 C++，在 CPU 穷举世界层与局部层各 `2^24` 个输入，检查实际离散 PMF、稀有光源支持和正反面积 PDF。Rust 树更新、稳定身份、失败回滚和字节布局可用 `cargo test -p prime_vulkan --lib light_tree --locked` 验证；这些入口不创建 Vulkan 设备或窗口。

TreeSphere的CPU拓扑、叶数、路径和保守球界使用 `cargo test -p prime_vulkan --lib light_sphere --locked`；目录灯字段更新不改变实例/槽位使用 `cargo test -p prime_vulkan --lib static_directory --locked`。生产Slang整数支持、前向/反向PDF及面中心/边缘边界可用 `cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_sphere_tree_integer_support_forward_reverse_and_geometry_boundaries -- --ignored --nocapture --test-threads=1` 执行无窗口GPU夹具，正确性检查另启用Vulkan与同步validation。这些检查不能代替两版游戏的菜单切换、完整PT质量和性能验收。

完整输运的冻结shader对照使用 `register_tests::dump_transport_equivalence` 与 `register_tests::steady_transport_matrix`，显式设置 `PRIME_REGISTER_LIGHT_SAMPLING=tree|sphere` 和 `PRIME_REGISTER_REFERENCE_SPV`。后者须为同模式、采样方法及当前ABI编译的产物，测试接口不自动验证这些条件。两臂都安装各自冻结产物：图像入口统一使用Offline通用bank，避免与内置single-sample特化混作性能对照。任一变量启用时，两入口明确关闭RR/OMM，使用Native、无星图及自动曝光补偿；它们不测量DLSS模型。

图像入口以 `PRIME_REGISTER_DUMP` 指定目录，保留20夹具×4预算的原始FP32图像；预算变更只更新push constant与采样历史，冻结bank只安装一次。时间入口以 `PRIME_REGISTER_CSV` 指定文件，可设 `PRIME_REGISTER_WARMUP`、`PRIME_REGISTER_SAMPLES` 和逗号分隔的 `PRIME_REGISTER_CASES`；固定原生1920×1080、4路径顶点及种子，保留预热和全部帧。GPU时间覆盖实际整份录制，不能当成K2单段时间；夹具光源树较浅，不证明真实世界深树或RR的性能。独占GPU、验证关闭并交替运行两臂，另保留工具链、源码与产物哈希和设备配置。

可选 `PRIME_REGISTER_LIGHT_DISTRIBUTION` 指向光源分布导出目录，使用其数字CSV `emitters.csv` 与 `receivers.csv` 第一接收点，替代默认夹具。按原尺度构造outline发光quad、粗糙白色接收面及Cell灯页，不读取代理可见性表；缺少原世界遮挡与材质，不能称为完整存档渲染。入口预加载至生产几何不再有待更新工作且全部源Cell已有发布槽位，每次录制都等待GPU完成；记录源面数、实际发布三角形/灯数、输入哈希和预加载次数，未完成则失败，正式序列从零开始。生产编译器可合并共面发光面，源计数不是发布计数目标。加载成本与稳态时间分别记录。

`PRIME_REGISTER_TREE_BUILD=saoh|balanced` 仅在上述 `shader-tests` 无窗口测试中选择TreeSphere构树方式，默认沿用SAOH。balanced按最长质心轴等分真实叶子，在世界层与每个页内分别平衡，不增加虚拟灯或GPU方法分支；各层叶深为floor/ceil(log2 N)，组合深度仍随页灯数变化。metadata记录策略和实际local叶深分布，日志记录每次world构树的叶深分布；局部路径统计在预加载后、正式计时前扫描一次，不计作生产开销。拓扑改变会改变proposal及收敛，必须另测质量；该测试选项不进入游戏设置或生产构建。

生产Grid的CPU建表、GPU表与shader变体已退役；旧`GRID`保存值迁移到功率距离Tree，保留其他设置。Tree/Sphere按前文同场景步骤分别验收。`register_tests::steady_transport_matrix`可指定`PRIME_REGISTER_INTEGRATOR=path_trace|restir_pt`；ReSTIR仅支持Realtime，禁止安装PathTrace的reference SPV。相同场景/预算并不意味着两种积分器射线总量相同，结果分别解释。

### ReSTIR PT Enhanced

固定配置、资源成本和历史契约见 [ReSTIR PT](docs/restir-pt.md)。以下入口不创建窗口：

```powershell
# 执行生产 Slang 的 RNG、RIS、合并、Jacobian、pairwise MIS 与配对邻域数学行为
python scripts/test-restir-math.py --slangc "$env:VULKAN_SDK/Bin/slangc.exe"
cargo test -p prime_vulkan --features shader-tests --lib --locked restir::tests

# 真实 GPU：generation/replay/shift、三种光源方式、离线均值、历史边界与借用提交
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_restir -- --ignored --nocapture --test-threads=1
```

`scripts/test-restir-layout.py` 接收构建生成的 `restir_*.spv` 文件列表，使用实际 SPIR-V 验证 BDA stride、uniform 偏移与能力声明；需要 `spirv-val`，逻辑设备必须启用 `scalarBlockLayout`。上述小场景和数学验证不能证明完整游戏收敛或与 Falcor 的性能差距。两版游戏按前文启动命令手动检查三后端切换、RR/DLAA与超分、太阳持续推进、局部编辑/实体运动/纹理动画时的时间复用、遮挡显露和光照更新，以及材质与离线收敛。

### Streamline / DLSS RR

`./scripts/test-reconstruction-cpu.ps1` 用生产 Slang 生成的 C++ 验证 motion/jitter、天空、anchor 重定位、刚性前态与未知形变、颜色和 BSDF albedo。`cargo test -p prime_vulkan --lib reconstruction --locked` 覆盖相机矩阵、抖动周期及 C ABI；`object_motion` 测试稳定身份、几何对应和实际提交接受边界。SDK mock 测试及这些数学/图像检查均不替代真实 DLSS 模型和游戏呈现验证。

```powershell
.\scripts\fetch-streamline-sdk.ps1 -VerifyOnly -CheckLatest
.\scripts\test-streamline-bridge.ps1
.\scripts\test-streamline-gpu.ps1 -InitializationOnly
.\scripts\test-streamline-gpu.ps1
.\scripts\test-streamline-gpu.ps1 -Interposed
.\scripts\test-reconstruction-cpu.ps1
# 独立 PowerShell 会话；验证生产 K1/guide 图像和重建显示，不创建窗口或执行 DLSS 模型。
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_primary_ -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_rr_display_fallback_upscale_and_orientation -- --ignored --nocapture --test-threads=1
```

`gpu_primary_` 同时覆盖 K1 内部交接和生产 `PrimaryRrGuides` 的实际图像读回：固定几何/相机/jitter 下跨 lighting seed 的通道一致性、共享查询与分支重放、roulette/吸收后 guide 完成、反射/透射的对称预算不足、TIR、reset、动态前态缺失、奇数尺寸及 Halton/相机运动。普通粗糙表面的 post motion 检查调用生产 helper，但 K2 hit distance 是显式合成输入，不代表完整 K2→RR 验证。

`test-streamline-gpu.ps1` 在独立 Vulkan 1.2 设备上调用生产桥接和真实 SDK，不创建窗口；启用 validation 和同步验证，默认验证回调与 Minecraft 一样对 ERROR 返回 `VK_TRUE`。初始化检查不执行模型；完整检查执行 Performance / preset F 的 960×540 → 1920×1080 重建、等待完成并读回预填 NaN 的输出，检查 RGB 是否全部被有限非零值覆盖。两种检查均要求零 validation error，日志、SDK 锁定哈希和运行结果保存在独立 `artifacts/streamline-gpu/` 目录；API 返回成功或有效读回不能覆盖验证层失败。此合成输入测试不能替代实际游戏画质、呈现和性能验收。

两版各用前文 `runClient` 命令手动验收：默认开启 RR、Performance（960×540 → 1920×1080），日志应出现 `DLSS RR preset F` 和实际输入/输出尺寸；依次切换五档、开关 RR、诊断/最终视图、实时/离线、原版/PT。覆盖相机平移/旋转、快速转头、遮挡显露、运动实体/粒子、细叶、水/玻璃、F3+T、退出/重进、窗口奇数尺寸/最小化/全屏。查 `sl`/`NGX` 错误与 Vulkan VUID，尤其观察动态无完整前态、厚折射代理与 guide 预算耗尽的画质边界。关闭 RR/不可用时应回到原生 raw；正常失败当帧也应覆盖整个输出。

性能基准仍固定原生1920×1080：使用DLAA或关闭RR，固定场景、seed和射线预算记录CPU/GPU、稳态/更新与离群值；Performance等降低内部尺寸的结果另列，不称为原生1080p性能。重建同步/资源合同见[重建文档](docs/reconstruction.md)。

K1 局部成本可用以下独立实验。旧/新 SPIR-V 必须来自冻结源码、同一编译工具链和优化参数，并与测试夹具的绑定及 push ABI 兼容；两臂保留相同常量环境和 report 写入，均排除 post helper。缺少任一文件会失败，不自动选择基线。测试固定原生1920×1080、相同相机/seed/预算，覆盖 opaque、水及玻璃＋镜面，以 AB/BA 顺序各保留12次预热和48次正式采样；时间戳仅包围 K1 dispatch。CSV 保留离群值，旁置文本记录设备和输入产物；另保存源码版本、产物哈希和工具链。该结果不包含 K2、post、DLSS 模型或显示，不能外推为完整 RR 帧时。

```powershell
# 单独的性能会话，不与其他 GPU 作业并行；替换为匹配夹具的实际产物路径。
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_RR_K1_BEFORE_SPV = 'C:\path\before-k1-fixture.spv'
$env:PRIME_RR_K1_AFTER_SPV = 'C:\path\after-k1-fixture.spv'
$env:PRIME_RR_K1_COST_CSV = [IO.Path]::GetFullPath('artifacts\rr-guide-k1-cost.csv')
cargo test -p prime_vulkan --features shader-tests --release --lib --locked gpu_rr_k1_timestamp_ab_ba -- --ignored --nocapture --test-threads=1
```

纯文档修改核对事实、命令和链接即可。代码修改按受影响的契约选择以下入口，记录实际执行结果及未覆盖范围。

RR、折射 eta 与反弹预算的数学合同可以无 GPU 运行 `./scripts/test-roulette-cpu.ps1`：使用当前 Slang 实际生成的 C++ 和 clang++ 执行生产数学/BSDF 核，覆盖概率重加权、介质进出、TIR、薄壁、异常值与终端预算。需要 Slang 和 clang++；输出默认保存在忽略的 `artifacts/roulette-cpu`。这个 CPU 验证不创建 Vulkan 设备，不代替 shader SPIR-V 校验或用户实际画面与性能验收。

`./scripts/test-pbr-delta-cpu.ps1` 执行实际Slang生成的窄delta/guide数学，覆盖Full入口对拍、纯delta首透明0.5条件估计器、TIR/薄壁/IOR=1、法线分布分类、共享与分离方向一致性及guide能量；GPU对应入口为 `gpu_pbr_delta_and_guide_contracts`。`./scripts/test-primary-psr-cpu.ps1` 覆盖实际PSR数学的反射平面展开、反射顺序、静态零motion、厚折射终点切平面代理、动态前态缺失与退化拒绝。固定平面反射可精确展开；折射代理是近似对应，不是逆 Snell 映射，缺少动态前态仍不能声明有效 motion。

### 星图、曝光、HDR 与 BLAS 压缩

显示算法和资源成本见[display.md](docs/display.md)，压缩地址切换及退休见[spatial-batching.md](docs/spatial-batching.md)。资产验证检查KTX2头/DFD/metadata、压缩及解压SHA256、BC6H长度和15层mip；CPU检查实际Slang生成数学，不创建窗口。

```powershell
python scripts/verify-starmap.py
python scripts/pack_assets.py --check
python scripts/test_pack_assets.py
.\scripts\test-mature-display-cpu.ps1
.\scripts\test-starmap-cpu.ps1
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features shader-tests --lib --locked exposure::gpu_tests:: -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --lib --locked gpu_hdr_surface_records_once_reuses_completed_slots_and_retires_without_pt_assets -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --lib --locked frame::tests:: -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_static_blas_compaction_preserves_pixels_slots_and_cache_dependencies -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --lib --locked gpu_empty_pages_preserve_in_flight_leases_and_reuse_sparse_identities -- --ignored --nocapture --test-threads=1
```

`test-starmap-cpu.ps1` 执行实际 Slang 生成的天球数学和生产 stars 入口，检查两极、跨极点、赤经缝、倾斜天文帧、8点采样支持和常量辐射亮度，使用独立 double 参考；不创建窗口或GPU。游戏手动覆盖天极附近转动视角、RR开关、前景遮挡与冻结。格式、无损要求和加载成本见[固定资产规范](docs/assets.md)。`pack_assets.py --check`只读校验现有全部发行资产，需要Zstd CLI；用 `--ktx-validator PATH_TO_KTX_EXE` 指定Khronos官方工具验证容器。`test_pack_assets.py`执行结构、错类型/shape、重复字段、载荷漏段/重叠与稳定清单检查，不创建GPU或窗口。

显示 GPU 检查覆盖实际 image/BDA 计量、曝光各乘一次、RR/raw selector、星图15层BC6H/真实前景/地面遮挡、HDR绝对亮度与source-over、SDR/HDR HUDless及UI方向。轻量 HDR owner 检查三个在途描述符槽、真实完成复用与销毁；模式测试检查传输/采样等价，显式关闭曝光/星图以固定显示条件，另验证曝光强度和模式同事务变化。BLAS像素测试保持严格像素判据并检查查询/复制/取消/重定位、缓存身份及旧TLAS消费者；其他池/完整游戏性能不由这些小尺寸边界夹具保证。

### 大气资产与无窗口验证

普通构建使用仓库内KTX2和压缩Safetensors，不在启动时求解多散射。物理输入迁移工具校验旧资源SHA-256并保留原始f32位模式；离线中间产物放忽略的artifacts。bake求解当前嵌入的固定介质，输出标准Safetensors；修改介质须同步更新生产契约与求解入口。生成后统一封装，默认最高Zstd 22并比较19级：

```powershell
python scripts/import-atmosphere.py C:\WorkSpace\prime
cargo run -p prime_tools --features atmosphere-bake --bin bake-atmosphere --release -- artifacts/asset-inputs/atmosphere/default.safetensors
python scripts/pack_assets.py --source-root artifacts/asset-inputs --only atmosphere
python scripts/pack_assets.py --check
cargo test -p prime_vulkan --features shader-tests --release --lib atmosphere::tests -- --ignored --skip atmosphere_cost_matrix --nocapture --test-threads=1
```

GPU 正确性检查设置下面介绍的 validation/sync 环境变量。局部成本测试单独运行：关闭 validation，设置 `PRIME_PROFILE=1`，执行 `cargo test -p prime_vulkan --features shader-tests --release --lib atmosphere_cost_matrix -- --ignored --nocapture --test-threads=1`。该测试比较原生 1920×1080 查询域的旧/新 SkyView 消费，并计量各类 LUT 更新，不代替游戏帧率基准。

游戏手动覆盖直接以 Prime 启动（不先运行原版）、原版→Prime→原版切换、`/time set day/noon/night`、连续日出/日落圆盘、夜间、南北纬与四季黄经、极地昼夜、洞穴与顶棚增删、跑图/升降、旋转/FOV/窗口缩放、离线冻结后时间推进。固定相机改变时间时，太阳圆盘、直射光与天空应同步变化。诊断中的 `atmosphere_*_updates` 是累计重算次数：冻结后稳定不增长，太阳变化不应刷新 Camera-T 或 Aerial-T。场景几何变化刷新 Aerial-S；验证实体或地形阴影不会残留。

### 常规检查

```powershell
# 无 GPU / Slang 依赖的协议、引擎边界与算法测试
cargo test -p prime_minecraft -p prime_scene -p prime_engine --no-default-features --features prime_scene/legacy-fixtures --locked
cargo test -p rectangle_decomposition --all-features --locked

# 完整 workspace 需要 Slang，但实际 GPU 测试默认 ignored
cargo test --workspace --all-features --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings

# Java 公共层与两个适配器
.\gradlew.bat test

# 发行包内 DLL 提取、FFM 错误边界与真实 GPU 诊断；两版串行执行
.\gradlew.bat bundledNativeSmoke
```

`cargo test --workspace --no-default-features` 仍选中了 `prime_vulkan`，不能当成无需 SDK 的入口。选择上面的窄包命令才能验证 CPU 依赖边界。`bundledNativeSmoke` 使用同步读回的独立设备诊断接口，输出到各适配器 `build/bundled-ffm-smoke.png`，不代表实际游戏合成性能。

在单独的 PowerShell 会话中运行需要 validation 的 GPU 行为测试，避免环境变量影响后续性能测量：

```powershell
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features shader-tests --lib --locked -- --ignored --skip cost_matrix --skip packing::perf --skip realtime_perf_tests --skip gpu_rr_k1_timestamp_ab_ba --nocapture --test-threads=1
```

这些测试覆盖 cutout、累积、尺寸变化、增量场景和宿主资源退休；小尺寸/奇数尺寸用于边界检查，不是性能数据。改变宿主集成或捕获时，还需在对应 MC 适配器实际运行，检查主图像与 HUD、资源重载、世界退出等相关生命周期。更新公共接口时验证受影响的两个适配器；编译通过不证明 Mixin 注入或实际 GPU 功能正常。

`shader-tests` 另编译无窗口测试入口，直接验证生产 Slang 的 Z-Sobol、颜色、primeDRT 与安全起点；正常发行构建不包含测试入口。imported shader 的改动会重编有效依赖受影响的入口变体。同步验证日志出现 `Prime Vulkan ERROR`、`VUID` 或 hazard 时，即使 Rust test harness 返回通过也不能视为 GPU 检查通过。Slang 模块/数学支持边界及可替换的显示策略见 [模块说明](docs/shaders.md)。

就绪计数与压缩编码回归随普通 `prime_minecraft` 测试执行：覆盖 Single/Local/Global 的水、玻璃与岩浆输出、63/64段阈值、cached halo 迁移和大工作集内的单次编辑。上传/阴影改动可在上述 validation 会话中执行以下窄入口，实际资源收缩、在途退休及遮挡行为不能只靠编译判断：

```powershell
cargo test -p prime_vulkan --features shader-tests --lib --locked object_tests:: -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_optical_visibility -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_solar_sampled_radiance -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_cross_bilateral_uvs -- --ignored --nocapture --test-threads=1
# Full 窄构造/LUT 与历史 LitePBR 拓扑、数值清洗及采样/评价；LabPBR 上传、atlas 查询与动画
cargo test -p prime_vulkan --features shader-tests --lib --locked pbr_tests:: -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked pbr_texture_tests:: -- --ignored --nocapture --test-threads=1
```

光学测试组合水/厚薄玻璃与前、中、后方的 opaque/cutout/stochastic blocker，并反转 mesh/primitive 顺序；无遮挡时对照解析 Beer/Fresnel，遮挡使用独立 coverage 参照。太阳测试使用实际圆盘采样，覆盖昼、夜和地平线部分可见，验证同一采样方向的辐亮度与支持域。它们是行为检查，不是稳态性能基线。

十字面 GPU 测试从四个方向对照独立源三角形，使用非对称颜色与 alpha 检查两侧 UV 和透孔。CPU 配对规则随普通 `prime_minecraft` 测试验证；实际双版本 `cross` / `tinted_cross` 字段由 `cpuSmoke` 生成，再通过下面的原生回放核对。支持与参照边界见 [Section 测试设施](docs/guides/section-tests.md)。

实例相关 GPU 测试覆盖局部原型、仿射/颜色/UV、增删及在途资源退休。Java 的 `cpuSmoke` 在真实 Fabric/Mixin 类上验证源路由、标准模型、下游截断与 target 创建/缩放。测试启动器在 preLaunch 退出，不调用游戏 main、不创建窗口或设备。地形原型通过真实 MC palette/模型字段、FFM 和独立 CPU native 库验证64段完整性、重复 dirty 合并、相同输入不重编译、空段清除与未知模型默认值；opaque 模型回调会主动抛错以证明未被调用。该入口需要 Rust，构建库放在 `build/source-cpu-native`，不会覆盖游戏使用的 release DLL。人工窗口不能代替实际视距或游戏验证。

地形分帧改动需同时验证 CPU 编译和后段 planner 的每阶段 N 格上限，覆盖 1/8/128、无新源时继续清空积压、重复编辑读取最新源、公平轮转、卸载/epoch 取消、负坐标、重定位和失败不确认；最终几何需与无上限参考一致。资源目录同 epoch 整代换新和普通纹理身份撤销须立即撤旧，后续按预算恢复且不混用旧 UV/材质。后段 GPU 验证另覆盖真实几何内容变化重置离线累积、等价 OMM 重建保留累积、OMM 旧覆盖的实例禁用与在途资源寿命。设置文件使用独立 schema v7，旧版本文件按严格规则整份回退默认；FFM 使用100字节 `PrimeSettings`，公共 ABI 为 v11，JAR 与 DLL 必须同次构建。

纹理撤销用例分别验证动态owner退休和全局资源常驻：普通区块卸载不能退休terrain sprite，world reset保留资源像素及canonical目录；真实资源重载原子替换整代，旧CPU/GPU读者持有必要引用直到各自消费结束。相位未变动画复用已发布纹理；变化相位仍需验证通道插值结果。全局资源准备不等于所有初始化工作均已移出帧内，须单独测加载和重载成本。

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke --no-parallel

# 仅验证宿主 target 生命周期；CPU 记录设备替代图像分配，不调用 Vulkan
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeTargetResize=true --no-parallel
```

路由改动先执行双版本 `cpuSmoke`，生成 `build/routing-fixtures/mc-section-source.bin`，再执行下面的原生回放测试；fixture 缺失时报错。`section-oracle/` 另保存两版实际 SectionCompiler 的输出和同一场景的生产源包；差分测试覆盖受控 baked 模型、液体形状/材质/UV、multipart 和局部遮挡，另验证角点编辑失效与重复通知零编译。该参照不加载整个资源包，使用中性光照，不代表所有原版外观或模组均等价。

具名 CustomGeometry 的 `cpuSmoke` 实际执行源 callback、完整调度和 MeshData 路由，检查范围混合/排除、重复调度、刷新、未知输出、身份换代、消失与关闭、借用消费及异常阻止 FFM。可导出三帧真实 typed DTO、顶点与剩余 raw，用同一源产物重放 Rust 解码和 K1/FG guides；后者需要光追设备并显式启用同步验证。两版产物分别执行，不能把缺失或被过滤的测试算作通过。

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptNamedOutput=artifacts/named-custom --no-parallel
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
foreach ($version in @('26.2', '26.3')) {
    $env:PRIME_NAMED_CUSTOM_PRODUCT = (Resolve-Path "artifacts/named-custom/named-custom-$version.json").Path
    cargo test -p prime_vulkan --features shader-tests --lib --locked actual_custom_products_ -- --ignored --nocapture --test-threads=1
}
Remove-Item Env:PRIME_NAMED_CUSTOM_PRODUCT
Remove-Item Env:PRIME_VK_VALIDATION
Remove-Item Env:VK_LAYER_VALIDATE_SYNC
```

运动参照取真实前后原型的同一 primitive/重心坐标；首帧和形变未知，接受提交后的可证明小数平移与相机原点变化具有对应。小尺寸读回不证明整帧速度、SDK 降噪质量、宿主实际 Present 或完整自定义来源兼容。baked 顶点变化仍需原型发布与 BLAS 构建，支持范围和成本见[源合同](docs/capture-boundaries.md#具名-customgeometry-来源)。

旧 TerrainRouter/FluidRouter 只保留在测试源集，用于静态 tint/geometry key、流体和参数粒子的独立参考，不代表当前生产地形的语义。

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke --no-parallel
cargo test -p prime_minecraft --locked -- --include-ignored --skip source_cost_matrix --skip source_burst_cost --skip biome_stream_cost
# 只回放实际 cross / tinted_cross 的双版本正反 UV
cargo test -p prime_minecraft --locked actual_cross_models -- --ignored --nocapture
# 旧封闭几何路径的独立对照
cargo test -p prime_scene --lib --locked java_routing_matches_both_versions_actual_source_and_fluid_particle_oracles -- --ignored --nocapture
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeForeign=true --no-parallel
```

手动重点检查地形可以加载和显示、跨区块移动后持续补入与卸载、放置/破坏、资源重载、原版↔PT和离线切换；实体、HUD与粒子应继续可见。再检查红石强度0–15、草/叶/水的群系边界、混合设置0/2/7、`/fillbiome` 改色和资源重载；纯群系变化应 `requested=0`、`changed=0`，只重编译颜色消费者，稳态 `tint_requests/biome_samples=0`。重点对比水/岩浆的流动液面、跨段接缝、水淹半砖/围栏、连接部件和局部遮挡；未知模型仍可显示紫色方块，特殊可见性和未知回调依赖可能不同，按 [PROTOTYPE_HACKS](PROTOTYPE_HACKS.md) 验收。启动前重建 `buildNative`；ABI v11 不能混用旧 DLL。原生1080p画面与实际性能由用户检查。

在“每帧地形批次”分别选择 1、8、128，检查连续移动与编辑时最终补齐、跨格修改允许分帧生效、卸载不留旧格、持续编辑不饿死其他区域。带积压进入离线时，CPU 待编译停止，已发布源的后段可继续；实际几何变化重置累积，积压清空后持续累积。比较固定原生 1920×1080 下的 CPU/GPU 帧时与从编辑到可见的尾延迟，保留慢帧；源读取、纹理、动态对象及全局 TLAS 不计入格预算，不能只按每帧格数认定卡顿已解决。

独立图像诊断入口：

```powershell
cargo run -p prime_tools --bin prime-pt-smoke -- smoke artifacts/smoke.png 32
```

需要直接调试 FFM 时，可 `cargo build -p prime_engine` 后运行 `.\gradlew.bat nativeSmoke`；也可通过 `-PnativeLibrary=绝对路径` 选择库。

## 同步工作池

Prime 自有 CPU 工作池全部在 Rust。每个 Engine 创建一个 configured `Arc<CpuWorkers>`，section 编译/退休、粒子展开和 renderer 静态/对象准备共用该 session 池；世界与资源重置保留它。`PRIME_CPU_THREADS` 设置 session 池线程数，默认取可用并行度与 8 的较小值；设为 1 时直接在调用线程执行，非法非正值拒绝。这些阶段顺序调用，小批直接执行，多线程仅写各自独占输出，返回前全部 join；不应把池数相加当成同时工作的线程数或后台构建能力。Java 不再有 `primeptCompilerThreads` 参数或 Prime section compiler，源回调留在宿主 owner。

Java CSV 的 `terrain_plan_ns` 是请求规划 FFM 总时间，`terrain_pack_ns` 是按表读取/封装源和应用列镜像的时间，`terrain_accept_ns` 是响应提交、Rust 解码/编译/发布的同步总时间；它们包含于 `terrain_total_ns`，总时间还包括初次 epoch/atlas 准备和事件封装。`terrain_source_bytes` 是本帧请求输入与响应输入之和，包含资源定义，不含返回请求表。`terrain_requested_sources` 是本批请求段数，`terrain_available_sources` 是有源响应数，`terrain_missing_sources` 是本批无源数，`terrain_available_sources_total` 是累计有源响应数；这些字段均不表示积压量或64段就绪证明。旧的 pending/waiting/empty 常量列已移除。loaded/unloaded 是原始列事件数，entered 是 Rust 新激活且镜像成功的列数。`mc_source[...]` 另给 native plan/decode/compile/publish、请求批次数、变化/编译/活跃/驻留段数、实际 `pending_cells` 与 hack 使用计数；CPU 编译积压和后段 GPU 构建积压分别观察。

原版 tint 由 Java 批量转录源字段，Rust 直接求色；`tint_callbacks` 只统计未知源的实际回调。`tint_bytes` 统计 typed 颜色/群系请求数组及响应数组、定义和 colormap payload，不含 DTO 根结构；`request_batches` / `response_batches` 包含实际发生的颜色和群系阶段。`biome_samples` 是需要重新计算颜色的位置数，`biome_host_cells` / `biome_pages` 是实际读取的 quart 群系单元/页数，不能互相当成同一单位。`biome_source` 包含 native zoom、源请求组织和求色，`biome_filter` 为混合；历史 Java `tint_callback_ms` 字段包含整个颜色源准备，不能直接归因为回调。协议配套版本为 source v7（公共 FFM ABI为11），重建双适配器与 DLL 后再验收。`cpuSmoke` 同时生成实际 `getOffset/getSeed` 的 `placement-oracle.bin`，原生回放精确比较位置种子与偏移位模式；支持与未知回调边界见 [Section 测试设施](docs/guides/section-tests.md)。

`compile` 包含排序、作业建立、`kernel`（slab 解包/剔面/展开，含首次池创建）和 `finalize`（精确内容比较、分片边界计算及不可变输出准备）；各值都是调用方墙钟时间，不是 worker CPU 时间之和。分片直接移交其 Vec 所有权，精确相同的分片复用旧存储与包围盒，不再归并成整段连续副本。`published_layers` 是实际替换或删除的图层数，`retained_layers` 是重新编译后内容相同而保留的非空图层数。`publish` 是 owner 上的场景变更和旧引用释放，不包含 GPU 构建；新分片分配及旧几何最后引用的回收仍有成本。

源读取和校验仍在当前源事务内同步闭合，CPU 网格编译及后段静态构建分别按每帧 N 格推进。格预算不是耗时或内存硬上限，完整首载或大范围修改仍可能形成真实长帧，应记录其成本及排队到可见的延迟；工作池线程数、几何批次、在途 GPU 页与当前活跃内容不是同一数量。AS storage 无活跃区间且无在途引用的页在完成证明后归还，保留一个闲页；其他空闲池页仍保留历史峰值，renderer 销毁时释放。

## 性能测量

使用 Nsight Graphics 启动 Minecraft + Prime、抓取 GPU 时间线或 Vulkan 帧、固定构建并进行版本对照，见[Nsight 抓帧手册](docs/guides/nsight.md)。无窗口启动准备入口为 `.\scripts\prepare-nsight.ps1`；启动参数和抓取证据生成到 Git 忽略的 `artifacts/nsight/`。

### 光源采样质量与成本

显式 `light-sampling-bench` feature 构建独立的无窗口采样实验。默认比较功率树、同分布分层 alias、接收点相关实验树；每种都测 IID 和生产 Z-Sobol。数学定义、夹具支持范围和计时边界见[测试契约](docs/guides/light-sampling.md)。该 pass 时间不等于游戏帧时。

```powershell
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features 'shader-tests,light-sampling-bench' --release --lib --locked light_sampling -- --include-ignored --nocapture --test-threads=1

# 性能阶段关闭 validation，不与其他 GPU 作业同时运行；输出目录不可覆盖已有结果。
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE = '0'
cargo run -p prime_tools --bin light-sampling --features light-sampling-bench --release --locked -- --output artifacts/light-sampling-run --replicas 4 --spp 256
```

默认原生1920×1080，256帧预热、512帧计时，保留所有原始时间、逐种子误差与线性 PFM；`--help` 列出参数。较小尺寸只作正确性/快速检查。参考使用矩形解析积分，二阶矩数值积分另做收敛检查；理论 `σ²/N` 只对 IID 成立。保留构建源码 diff、GPU/驱动和 CPU/工具链信息，报告同样本误差与相同 GPU 时间误差，不能仅依据更快选灯就认定方案更好。

### 实时输出

无窗口夹具使用实际宿主录制路径，在原生1920×1080、4次反弹、固定种子下覆盖天空、平面、密集 cutout、斜面与多层透明表面。每场景预热256帧、记录512帧，CSV保留全部预热和离群值；每次记录后等待完成以明确GPU区间，不模拟游戏呈现或CPU/GPU重叠。

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_OUTPUT_CSV = "$PWD/artifacts/realtime-output.csv"
cargo test --release -p prime_vulkan --lib --locked realtime_output_cost_matrix -- --ignored --nocapture --test-threads=1
```

### 自定义表面编译原型

接口与支持范围见[表面编译](docs/surface-compiler.md)。表面编译器是唯一静态几何编译入口，无需环境开关；生产 MC source schema 为 v7、公共 FFM ABI 为 v11，双适配器与 DLL 必须配套重建。旧分页 source v5 只保留诊断/参照回放。以下游戏启动只由用户手动执行：

```powershell
.\gradlew.bat :mc-26.3:runClient -PprimeptValidation=true -PprimeptProfile=true
# 26.2 使用 :mc-26.2:runClient。
```

先检查半砖/活版门薄边/竖向栅栏的连续面与孔洞、纹理裁切/旋转/周期、草侧/红石/向日葵正反面、贴墙火焰、玻璃/水/含水部件、浅水斜坡、岩浆和火把发光。十字草与花从四个方向观察两张相交面的正反纹理及 cutout 孔洞，使用左右非对称的纹理检查各侧源 UV，并对比原版；不要用对称纹理判断正反映射。覆盖两侧与内部观察、首帧以外的动画、资源重载、负坐标及 16/64 块边界增删，确认编辑结果等价重新加载。检查 `hacks.sprite` / `hacks.optics` 的未支持来源，再关闭 validation 测量。声明 LabPBR 的资源包另按[材质契约](docs/materials.md)检查法线、金属、玻璃 IOR、cutout SSS、发光和动画。动态光学几何、复杂开放玻璃与折射焦散不在当前支持范围内。

另覆盖均一水/玻璃/岩浆段与混合段的接缝、完整64段单元边缘的加载/卸载，以及白天到夜间和太阳圆盘跨地平线时的透明阴影。持续移动/增删动态对象后缩小负载，检查材质、光源概率与资源回收；重载图集、地图和皮肤时确认像素更新正常。两版分别验收，原生1080p帧时与更新尾延迟按固定输入另行记录。

无窗口原生1080p表面编译稳态测量：

```powershell
New-Item -ItemType Directory -Force artifacts/surface-bench | Out-Null
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_SURFACE_CSV = "$PWD/artifacts/surface-bench/steady.csv"
cargo test --release -p prime_vulkan --lib --locked surface_steady_cost_matrix -- --ignored --nocapture --test-threads=1
```

默认每种形状/coverage做3轮、1024帧预热、120帧采样，固定相机和射线预算。可设 `PRIME_SURFACE_PATTERN=layers` 只测八层遮挡，`PRIME_SURFACE_ROUNDS` / `PRIME_SURFACE_SAMPLES` / `PRIME_SURFACE_WARMUP` 控制轮数/样本/预热。CSV保留原始CPU录制、GPU准备/完整渲染时间和实际三角形数，预热行也保留；跨版本比较使用固定历史构建、相同输入与配置，不在当前 renderer 内切换编译器。极短夹具应避免把GPU从闲置升频的过渡当成稳态p95。每样本等待GPU完成用于归因，不代表游戏呈现吞吐；场景是受控夹具，不是真实存档。结束后删除这些会话环境变量或使用独立PowerShell，避免改变后续比较配置。

部分矩形的同构建对照使用相同源 quad，分别输入不合并的封闭三角形和规范合并表面；夹具断言实际 primitive 数，避免对照组再次被合并：

```powershell
$env:PRIME_STRIP_CSV = "$PWD/artifacts/partial-strips.csv"
cargo test --release -p prime_vulkan --lib --locked partial_strip_cost_matrix -- --ignored --nocapture --test-threads=1
```

沿用上面的 profile/validation 配置。原生 1920×1080、4 次反弹、固定种子，三轮半砖/活版门/竖向栅栏，各 65536 源 quad；交替顺序，稳态 256 次预热/256 次正式样本，更新 4 次预热/24 次正式样本。`compile_ns` 是夹具源到 Scene 准备，`visible_ns` 包括准备、录制和实际 GPU 完成；不包括 MC 捕获/FFM，也不等于呈现延迟。记录有效记录字节、材质预留、活跃 BLAS、构建/上传池和索引；不把子分配与池容量重复相加，也不当成驱动总显存。

### 通用测量约定

正式性能测试使用原生 **1920×1080**，记录实际主 target 尺寸；降分辨率、动态分辨率或重建后的输出不能标为原生 1080p。固定场景、相机、种子、渲染参数、帧率上限和 VSync，记录构建、GPU/驱动、预热与采样范围。关闭 validation、capture audit 和逐调用 trace；可选聚合日志、CSV 和细粒度 profiling 是否启用也属于测量条件。测量期间避免另一游戏或 GPU 测试争用设备。

当前 section 原型的 CPU 成本夹具：

```powershell
$env:PRIME_SOURCE_BENCH_CSV = "$PWD/artifacts/source-cost.csv"
cargo test --release -p prime_minecraft --locked source_cost_matrix -- --ignored --nocapture
```

输出目录须存在。固定8个私有线程，dense/terrain/decorated 每批256段，分别测首次输入、相同 dirty 输入与各段单处内部编辑（dense 的编辑包仍相同，作为控制组）；另在约8.9万活跃空段窗口中往返移动一列。15次场景样本、24次窗口样本均保留，前三次标为预热。计时包括请求规划、响应解码、编译、输出准备和发布；排除夹具包生成、线程池初建、Java/FFM、GPU及游戏。三角形顺序/数值另外通过标量参照和1/4线程对照测试验证；此局部夹具不能换算游戏 FPS 或实际移动尾延迟。

较大突发输入使用以下两个入口，须串行运行，避免与编译、游戏或另一基准争用资源：

```powershell
$env:PRIME_BURST_CSV = "$PWD/artifacts/source-bursts.csv"
$env:PRIME_BURST_SIDE = '16'
$env:PRIME_BURST_MODE = 'terrain'
$env:PRIME_BURST_SAMPLES = '23'
$env:PRIME_BURST_WARMUP = '3'
cargo test --release -p prime_minecraft --locked source_burst_cost -- --ignored --nocapture

$env:PRIME_CPU_THREADS = '8'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE = '1'
$env:PRIME_UPLOAD_CSV = "$PWD/artifacts/terrain-upload.csv"
$env:PRIME_UPLOAD_CELLS = '16'
$env:PRIME_UPLOAD_QUADS = '1024'
$env:PRIME_UPLOAD_SAMPLES = '33'
$env:PRIME_UPLOAD_WARMUP = '3'
cargo test --release -p prime_vulkan --lib --locked terrain_upload_cost_matrix -- --ignored --nocapture
```

`source_burst_cost` 固定8线程、每列4段，side 支持4/8/16/32（共64/256/1024/4096段），mode 支持 terrain/decorated。每次场景分别保留首载、编辑的两个方向和相同输入的原始样本及输入 hash。计时包括生产源规划、编译/颜色回填、发布、旧翻译快照最后引用的释放及整格计划；颜色响应为显式常量夹具，其响应封包与回填包含在 `accept_ms` 内，不包括实际宿主求色、Java/FFM 和 GPU。各阶段相加排除了阶段间的 section 源包构造；场景最终销毁不计入更新时间。此夹具、下述上传夹具及 `prime_tools` 入口与 native 引擎共用 MiMalloc 策略，计数包装仍统计真实分配请求；比较时记录锁定版本和分配器，另测进程峰值/保留内存，不能只记录逻辑几何字节。

仅测 CPU 四边形上传记录打包，可设置 `PRIME_PACK_CSV` 为输出 CSV 绝对路径，运行 `cargo test --release --locked -p prime_vulkan packing::perf::quad_packing_cost -- --exact --ignored --nocapture`。固定8线程，覆盖52万和314万三角形，保留每批5次预热、40次正式样本及输出 hash；计时包含平移、校验、纹理查询和写入预分配的176字节quad记录，排除分配、GPU传输与AS构建，不创建 Vulkan 设备。`source_burst_cost` 的 `resident_geometry_bytes` 统计翻译快照持有的几何容量，不等于进程或分配器的物理占用。

`terrain_upload_cost_matrix` 在原生1920×1080的无窗口宿主录制路径上，每次替换所有指定单元，固定网格、两种交替角点位置和默认射线预算。CPU 源整理/发布/翻译、录制和 GPU 时间分列；每个样本等待本次提交真正完成，`completed_ms` 包含等待，可据总时长计算吞吐量。每格64段，quads 是每段四边形数；不含 Minecraft、Java/FFM、源编译内核或图像读回。`upload_ns` 对直接打包的静态范围包含 CPU 记录初始化时间，对其他上传仍包含字节复制，不能将该计数解释为纯 memcpy 时间。用多轮不可变可执行文件交替对照 p95、最大值与吞吐量；两种夹具都不能替代真实存档的逐帧验收。

表面编译需分别设 `PRIME_UPLOAD_PATTERN=small|grid|checker`：默认small为不可合并的0.4格网格/斜面，grid为单位面并交替改色，checker为相邻颜色不同的单位面反例。跨版本比较使用相同源输入和配置的独立构建；CSV的 `triangles` 是源三角形数，`resident_triangles` 是最终 GPU 几何数。按源工作量和真实完成耗时计算吞吐，不能把减少后的输出三角形数当成吞吐下降。

群系样本规划及 raw 回退另有固定输入夹具；先构建，再按顺序执行：

```powershell
cargo build --release --locked -p prime_tools --bin raw-perf
$env:PRIME_BIOME_CSV = "$PWD/artifacts/biome-stream.csv"
cargo test --release -p prime_minecraft --locked biome_stream_cost -- --ignored --nocapture
$env:PRIME_CPU_THREADS = '8'
$env:PRIME_RAW_SAMPLES = '100'
$env:PRIME_RAW_WARMUP = '10'
.\target\release\raw-perf.exe artifacts/raw-cpu.csv
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_RAW_WARMUP = '120'
.\target\release\raw-perf.exe artifacts/raw-gpu.csv --gpu
```

`biome_stream_cost` 每例3次预热、30次正式样本，覆盖平面、变化高度和稀疏查询的半径0/2/7，保存样本数量和颜色 checksum；只计冷缓存规划及精确混合，确定性的宿主样本生成在计时外。`raw-perf` 覆盖2千/2万/10万三角形的相同快照、首尾顶点变化及仅原点变化，计时为真实 op6 解码、增量翻译和对象规划。GPU 模式使用原生1920×1080固定相机、默认4个路径顶点预算；采用宿主夹具的离线模式，每次 sample_index=0 重置为1 spp，固定采样输入，不等于游戏实时模式。它记录 CPU 录制和 GPU 时间，并等待每次提交实际完成；`total_ms` 是单次完成延迟，样本总时长可计算串行吞吐，不能当作流水游戏 FPS。CPU 模式不测的 GPU 字段及 GPU 模式不测的几何更新数留空。两者均不含 Java/FFM、源输入构造或实际游戏，须用相同夹具、充分预热及交替进程比较，保留持续变化输入的回退成本与离群值。

地形源路由与 native 编译有独立的无窗口成本夹具，可用于相同硬件/工具链的版本对照。测试时串行运行，避免同时构建或测量另一路：

```powershell
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke -PprimeptSmokeRoutingCost=local --no-parallel
$env:PRIME_ROUTING_COST_LABEL = 'local'
cargo test --release -p prime_scene --locked routing_cost_matrix -- --ignored --nocapture
```

以下成本夹具只测旧封闭路由路径，不能作为本分支生产基线。Java 输出到各适配器 `build/routing-fixtures/cost-local.csv`，覆盖完整遮挡、外露表面、交错可见和无 cull face 模型。每种场景每批 64 段，至少预热 2 秒后记录 30 批，保留预热与离群值；测实际模型/可见性路由、封批、当前 Java 线程分配及协议字节，使用已复用的 geometry key，不含 FFM/GPU 或真实世界加载。Rust 输出到 `artifacts/routing-stutter/native-local.csv`，固定私有 8 线程、每批 16 段和每段 512/4096 个放置，覆盖重复、末尾单处变化和全量变化；每种预热至少 500 ms 后记录 30 批，含解码/内容证明/编译/发布，不含生成输入、Java/FFM/GPU。这些数据只说明局部成本，不能当作原生1080p游戏帧率或尾延迟。

在独立 PowerShell 会话中运行宿主路径夹具：

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE_TRACE = '0'
cargo run --release --locked -p prime_tools --bin perf -- --frames 120 --warmup 10 --seed 324478056 --csv artifacts/perf-host.csv
```

这个夹具默认使用离线累积模式，不能直接与游戏的实时噪声/guide 模式比较性能。它模拟宿主提交，最多两帧在途，不回读输出；不包含 Minecraft、FFM、HUD 和窗口呈现。结果可定位 native 开销，不能直接称为游戏 FPS。游戏内启用性能采集保存 JSON，将稳定帧、流送/修改、重定位与首次构建分开分析。

加 `--dynamic-counts 1000,10000,50000` 可测固定场景下三个动态三角形规模，每帧实际改变局部顶点，保留静态 revision。各规模独立预热、连续录制后排空；CSV 的 `fixture_update_ns` 单列测试数据修改耗时，批次墙钟包含这部分。该模式直接构造 `Scene`，`source_mode=direct_scene`、`protocol_decode_ns` 为空；它没有测 Java 或输入协议解码，不能把空值解释为零成本。

| 指标 | 含义 |
| --- | --- |
| `cpu_record_ns` | native 资源准备、命令池复用与命令录制 |
| `cpu_submit_ns` | 模拟宿主入队成本 |
| `cpu_slot_wait_ns` | 在途槽满时的 GPU 完成等待 |
| `gpu_preparation_ns` / `gpu_render_ns` | `instance-perf` 在启用 PRIME_PROFILE 时提供的上传/AS 准备区间和 PT/输出区间；关闭时留空，含阶段依赖和时间戳开销，不是孤立 AS/射线活跃时间 |
| `completed_gpu_ns` | 已完成提交的 GPU 时间戳区间，含上传、AS、依赖与 PT |
| `isolated_completion_ms` | 隔离变化事件从 CPU 开始到 GPU 完成的延迟 |
| `batch_completion_ms / batch_frames` | 阶段排空后的批次平均吞吐时间 |

异步入队耗时不等于完整帧耗时。GPU 事件绑定录制帧，完成观察可能延后多帧；任务管理器 GPU 百分比也不能代替阶段计时。图像正确性由独立测试与实机检查验证，性能夹具不以读回图像计算 checksum。

运行时诊断关闭后不再收集详细 CPU 时钟或 GPU timestamps，也不输出 `Prime slow frame`。在游戏内启用性能采集后，每帧原始事件、阶段起始时刻、时长、线程与摘要进入紧凑 JSON；异常帧和尖峰保留，停止时导出。数据口径、时钟对齐与未完成 GPU 状态见[诊断契约](docs/diagnostics.md)。

PT hook interval 是 CPU 阶段起点间隔，不是显示 Present 或 GPU 帧时。提取包含地形与其他源准备，source plan/pack/accept 为其子阶段，父子项不能直接混加。光照通知计数不是独立段数，也不证明处理成本由光照造成。分析冻结、暂停、菜单与视距变化时结合实际操作，区分实时与离线事件。

仅显式 `$env:PRIME_PROFILE_CPU = '1'` 保留原生每120次成功样本的兼容窗口摘要，运行时诊断/采集不会触发该文本输出。legacy CSV 由 `-PprimeptProfileCsv=绝对路径` 单独控制；未开启细计时的 capture/refs 字段为-1，部分历史列未生产赋值，不用于新性能分析。新采集应关闭 legacy 属性，单独记录采集成本。

静态增量链可用无 GPU 夹具比较相同构建的完整快照路径与生产增量路径：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin incremental-cpu -- --samples 100 --csv artifacts/incremental-cpu.csv
```

夹具在 1/16/512 个格、每格 1/64 个非空 mesh 下固定修改一个 mesh；每格均提交 64 个真实位置，包括空段。CSV 保留预热、全部样本、提交/翻译/分组耗时、发布/访问数量和分配请求。计时与分配计数分两轮，比较耗时时过滤 `allocation_instrumentation=false`、`warmup=false`；分配轮只用于归因。包含解码、翻译、分组与计划回收，排除包生成、Java/FFM、GPU 和游戏调度。它是 CPU 微基准，既不是历史构建对照，也不代表游戏 FPS；实际渲染性能仍按原生 1080p 检查。

持久实例也可使用同一构建、相同 op7 输入，比较完整快照诊断路径与生产增量输入：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin incremental-cpu -- --instances --samples 100 --csv artifacts/instance-incremental-cpu.csv
```

固定 1k/10k/50k 常驻实例，覆盖 0、1、1% 和全量姿态变化。计时包含解码、发布和放置规划，另记实际访问/姿态数；源包构造、Java/FFM 和 GPU 不在其中。与静态夹具一样，计时轮和分配统计轮分开，完整快照路径是本构建诊断对照，不是旧提交的历史基线。

CPU 打包的线程数/批次矩阵单独执行，不混入 GPU validation 测试：

```powershell
cargo test --release -p prime_vulkan --lib --locked packing_cost_matrix -- --ignored --nocapture
```

该测试保留 1/2/4/8 线程及小、中、大批次的预热与逐样本 CSV 到 `artifacts/packing-cpu.csv`。输入分配和准备在计时之外，观察直接写入复用输出切片的成本；它没有 Vulkan 命令或游戏场景，不能外推整体吞吐量。

原生 CPU 快照同时记录三角形、实例、簇、材质页和重建数量，读取已有计数，不为统计逐帧扫描场景。`cpu_upload_bytes` 是成功的 CPU mapped-buffer 写入字节，包含 staging 和 AS 输入，不是 PCIe 带宽或 GPU copy 量。阶段探针运行用于归因，正式性能对比另记其启用状态；测量后从运行环境移除该变量。实际客户端验证采用有界动作或采样窗口，完成后及时正常退出，再分析日志，避免持续占用用户前台。

动态捕获的游戏 profile 另给出 `dynamicCapture`（额外材质绑定和 mesh 拷贝 CPU 均值，包含于 `mcBeforePT`）、`dynamicSubmit`（纹理增量与整帧 FFM 解码 CPU 均值，包含于 `hook`）。`dynamicSpansTotal`、`dynamicVerticesTotal`、`dynamicBytesTotal`、`modelMeshesTotal` 和 `particleMeshesTotal` 是该窗口总量，除以 `frames` 才是每帧均值；mesh 数是原版批次数，不是实体数。`dynamicCapacity` 为保留 packet 容量，`dynamicGrowthsTotal` 是该 writer 自创建起的累计扩容次数。原版动画/模型准备时间没有混入 `dynamicCapture`；Prime 独占世界时省去该批世界 staged 输出的原版 GPU 上传，手部与 HUD 上传仍属于宿主。

地形 profile 记录原始通知、Rust 请求、有源/无源响应、三个调用阶段和运输字节。源处理属于 extraction；mcBeforePT 只覆盖世界 render 到 PT hook，不代表完整 Minecraft CPU 时间。窗口最大值保留源处理峰值，正常帧不额外查询 native 诊断。

双版本 CPU 入口验证原型字段与批次契约，旧 Java 模型/tint/流体夹具仅作独立参考。旧 Java compiler 调度/遮挡图矩阵不再是生产基线；原生1080p游戏对照仍需手动完成。

标准模型还记录实际 `beSources/entitySources/modelSubmits`、标准/回退叶节点、源引用检查数与几何顶点读取数，以及 op7 的原型/实例 upsert/remove 和字节数。对象数、模型提交数、Cube 叶节点数与 TLAS 实例数不是同一单位，报告中分别标注。逐帧 CSV 的 hook 间隔是 CPU 帧节奏，包含两个 hook 之间的原版工作和等待；不是 GPU 执行时间或显示器呈现时间。阶段总和与端到端间隔的差额不能无证据地归因于某一 GPU pass。

普通物品的 `item_*` CSV 字段与日志分别记录提交、分组、实际检查/省去展开的顶点数、回退 quad 数、新建原型顶点数及几何共享命中。检查源值不等于重新发布几何，回退 quad 也不等于回退实体；必须与实际 raw 顶点和 op7 字节一同判断收益。方块实体每 120 次提取记录加载候选数、提前排除的远处对象、未知语义回退、立方体幸存者及局部/全局 `tryExtract` 调用量。`beSources` 是进入模型捕获的源数，不是遍历过的方块实体候选数；方块实体提取发生在世界 render hook 之前，也不能从 `mcBeforePT` 单独推算其成本。

逐 Cube 细计时单独由 `-PprimeptProfileLeaves=true`（JVM `-Dprimept.profile.leaves=true`）开启，默认关闭，计数仍保留。关闭时 CSV `refs_pose_ns` 与日志 `refsPose` 为 -1，表示未测，不是零成本。正式比较关闭该细计时；批次 profile/CSV 本身是否开启仍需记录。必要时另用关闭可选聚合日志、CSV 和细计时的同场景游戏 FPS 对照，不能把不同指标直接相减归因为探针成本。geometry cache 的 hit/miss/null/emit 为窗口或逐帧增量；`avoidedCopyBytes` 是避免的 Mesh 编码复制字节，不是全进程分配量，也不表示下游 raw/FFM 字节已减少。

大量方块实体的源协议成本可先用无 GPU 依赖的局部夹具定位：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin capture-cpu -- --objects 1000,10000,20000 --strides 36 --warmup 8 --samples 40 --csv artifacts/cpu-dynamic-protocol.csv
```

每个代理对象是 24 个四边形，使用实际 op6 字节流；同材质单 span，每个样本仅改变序号和一个源顶点。分别计时 `SourceScene::submit`、`translate_dynamic` 与旧快照最后一个 Arc 的释放，模拟替换期间仍保留旧快照。CSV 保留预热、各样本、Rust 分配请求与峰值；分配统计不是进程 RSS。该工具不包含 Minecraft 模型生成、Java/FFM、GPU 打包或渲染，不能换算成游戏 FPS。CPU 局部测试没有输出分辨率；后续实际渲染验收仍须原生 1920×1080，并记录实际提交数量，不能用放置数量或少量合并 span 代替万级可见对象负载。

持久实例的 CPU 夹具使用真实 op7 包，分别测首次发布、静止借用、1% / 100% 姿态更新和 1% 持续增删：

```powershell
cargo run --release --no-default-features --locked -p prime_tools --bin instance-cpu -- --objects 1000,10000,20000,56133 --warmup 3 --samples 21 --csv artifacts/cpu-instances.csv
```

静止阶段不发送空包，其访问计时仅证明该 accessor 的成本，不代表整个 Java 捕获或完整渲染帧。每条记录的 revision 必须等于当前 batch sequence；源码工具、Java writer 与协议测试共同验证这个约束。测试参数和输出单位以各工具 `--help` 为准。

公共 Java 层另有实际 `InstanceCapture → NativeBridge.submit(MemorySegment) → SourceScene` 的 CPU 夹具，用同一 Java 构建、独立 JVM 和固定 DLL 比较跨语言提交成本：

```powershell
.\gradlew.bat :common:instanceSubmitPerf -PnativeLibrary=C:\absolute\prime_engine.dll -PinstanceObjects=56133 -PinstanceWarmup=64 -PinstanceSamples=120 -PinstanceCsv=C:\absolute\instance-submit.csv
```

七个局部 quad 原型由全部对象共享，覆盖静止、1% 和 100% 姿态变化。每帧仍观察所有常驻 handle，分别记录 begin/observe/end、seal 连续写包、一次直接 FFM 调用和成功后的 acknowledge；按实际更新数核验包大小。每阶段首次发布独立保留，不能当成稳态分布。CSV 保留预热与全部样本，旁文件记录 DLL SHA256 和 JVM；文件输出与哈希在计时之外。该工具不创建 Vulkan 设备，不含 Minecraft 动画/网格生成、GPU 或帧呈现，也不代表整条管线零复制。对照时交错运行旧/新 DLL，避免与构建或游戏争用 CPU。

原生实例 GPU 夹具使用同一宿主录制接口，固定 1920×1080、相机、种子、一个 48 三角形原型及 1k/10k/20k 实例，覆盖静止、1% 姿态更新和 1% 身份增删：

```powershell
$env:PRIME_PROFILE = '1'
$env:PRIME_VK_VALIDATION = '0'
$env:VK_LAYER_VALIDATE_SYNC = '0'
$env:PRIME_PROFILE_TRACE = '0'
cargo run --release --locked -p prime_tools --bin instance-perf -- --samples 60 --warmup 8 --rounds 3 --csv artifacts/gpu-instances.csv
```

该夹具直接构造 `InstanceScene`，不含 op7 解码、Java、游戏和呈现。身份增删在相同位置替换以隔离成员变化的成本，不代表移动或重叠几何的最坏情况；简单 opaque 代理模型也不能代表复杂 Minecraft 画面的射线成本。CSV 保留每帧 CPU 录制、提交、在途槽位完成等待、完整 GPU 区间、上传字节与实际 BLAS 重建数。需要确认输入画面时可用工具 `--diagnostic` 在计时之外输出同一夹具图像，不把读回混入生产性能数据。

按需诊断的无 GPU FFM 检查：

```powershell
.\gradlew.bat :common:test :common:cpuDiagnosticsSmoke
```

它只创建 native CPU session、读取诊断、提交 reset 并销毁；不附着 Vulkan、不创建窗口。双版本 `cpuSmoke` 验证实际计时事件与光照通知钩子；公共测试覆盖 JSON 精度、多线程父任务、停止尾部、背压与 I/O 失败。GPU 时间戳及游戏内开关/导出由用户手动检查。

## Section 对拍与 CPU 基准

双版本场景定义、参照和测量代码在 `adapters/section-tests` 测试源集维护。长期比较与计时契约见 [Section 测试设施](docs/guides/section-tests.md)。推荐使用统一入口，输出目录必须为空：

```powershell
# 生成两版实际 SectionCompiler 参照，并回放 Rust 生产编译器
.\scripts\test-sections.ps1

# 同线程数、同工作集：原版编译与 Java → FFM → Rust 的完整 CPU 路径
.\scripts\test-sections.ps1 -Bench -Threads 8 -Warmup 12 -Samples 40 -Rounds 3
# 单线程用于区分算法成本与并行调度收益
.\scripts\test-sections.ps1 -Bench -Threads 1 -Warmup 12 -Samples 40 -Rounds 3

# 大批量端到端 CPU：8×2×8 个编辑段，优先看 route p95/max 和 sections/s
.\scripts\test-sections.ps1 -Bench -Side 8 -TintSide 8 -Threads 8 -Warmup 12 -Samples 40 -Rounds 3
# 基准汇总的 p95、吞吐量、工作集隔离检查
python -B -m unittest discover -s scripts -p 'test_*.py'
```

需要 JDK 25、Rust release 工具链、Python 3（仅标准库生成摘要）和现有 Gradle 依赖；不需要 GPU/Slang，不启动游戏。默认结果写入 `artifacts/section-suite/<时间>/`，可用 `-Output` 指定新目录。`run.json` 标记整轮通过/失败，`summary.md/json` 提供分场景 p50/p95/max，CSV 保留冷启动、预热、所有正式样本与细阶段。默认使用三轮独立 JVM，汇总保留轮次和两个编辑方向；用 `-Rounds` 调整。统一入口在测量前及每轮测量后均执行对拍，并保存 native/输入哈希；避免与编译、游戏或其他基准同时运行。

`-Side` 默认2，扩大五个非群系基准的水平段数；编辑段数为 `2×Side²`，首次输入还包含显式空 halo。`-TintSide` 独立控制着色/群系基准，默认2（8段），同样支持2..16；8对应128段，CSV 按实际工作集分别汇总。Gradle 直接入口对应 `-PprimeptSectionSide` / `-PprimeptSectionTintSide`。优先比较同工作集、同线程数的完整 `route` p95，吞吐量按正式样本的编译段数总和除以 route 总时间计算；同时保留两个编辑方向、每轮分布、p50/max、三角形吞吐量和阶段计时。首次输入、静止、同内容以及几何/群系编辑分别比较，不把零编译控制组当作几何吞吐提升。

该入口覆盖受控模型与中性光照，不能证明完整原版资源包、真实存档或模组等价。原版参照包含其光栅专属工作，CPU 局部结果不代表游戏 FPS。维护时将新行为加入共用场景；确有版本差异的绑定留在版本测试目录，不复制两份用例。

## 已有 Nsight Trace 的 CPU 离线诊断

维护入口为 [scripts/nsight-trace.py](scripts/nsight-trace.py)，格式、输出 schema、partial 退出码和统计边界见 [Nsight 手册](docs/guides/nsight.md#cpu-离线读取已有-gpu-trace)。此工具只读取已有文件，不启动 Nsight、回放、游戏或 GPU：

```powershell
python -m pip install -r scripts/nsight-requirements.txt
python scripts/nsight-trace.py artifacts/captures/example.ngfx-gputrace --out artifacts/nsight-analysis/example --nsight-host '<Nsight安装目录>/host/windows-desktop-nomad-x64'
python -B -m unittest discover -s scripts -p nsight_diagnostics_tests.py
```

输出目录新建或为空。修改工具须执行字节结构、损坏边界、单位/加权与地址归一化测试，并用已有真实 capture 做 CPU 回归；未知格式、缺失 counter、无法独立确认的模块和缺少运行设置都明确标记，不用零或源码默认值补齐。原件 SHA256 前后相等是完整性证据，编译/测试或成功解包不代表游戏正确性与性能结论。

## 文档与本地产物

- `README.md`：玩家安装、使用和可见限制。
- `docs/`：已采用的架构、接口契约与长期工程规范，明确区分接入状态与目标；不保存试验日志、待讨论设计或阶段总结。
- `CONTRIBUTING.md`：可重复使用的开发入口与测量方法。
- `HACK.md`：当前技术债、影响和完成条件。
- `TODO.md`：后续工作与验收条件，CPU 性能优化当前非阻塞。
- `docs/guides/git-line-endings.md`：长期行尾与格式约定；具体开发操作仍由本文件索引。
- `artifacts/`：本地调查、方案、性能/验收报告、截图、日志和 CSV；整个目录由 Git 忽略。

报告可放在 `artifacts/reports/<日期或任务>/`，保留环境、输入、结论和限制；不要从需提交的文档链接某次本地产物。设计落地后只提炼有效契约到架构文档。需要长期回归的最小测试 fixture 放到对应测试目录，注明来源与语义，不把一次运行的完整输出转成 fixture。实验保留、冻结依赖和可再生成产物清理遵循[性能证据规范](docs/guides/performance-evidence.md)。

### 镂空表面 OMM

稳定的证明规则、成本策略与所有权契约见 [OMM](docs/opacity-micromaps.md)。无窗口测试使用生产 shader 比较 OMM 开/关的实际命中与阴影；设备不支持 OMM 时测试会明确失败，不能标为通过：

```powershell
cargo test -p prime_vulkan --lib --locked omm_cpu
.\gradlew.bat :mc-26.2:cpuSmoke :mc-26.3:cpuSmoke
$env:PRIME_VK_VALIDATION = '1'
$env:VK_LAYER_VALIDATE_SYNC = '1'
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_omm_pool -- --ignored --nocapture --test-threads=1
cargo test -p prime_vulkan --features shader-tests --lib --locked omm_tests -- --ignored --nocapture --test-threads=1 --skip gpu_omm_exact_shared_edge_diagnostic
# 独立边界诊断：记录已接受的数值归属差异，不作为位级等价验收。
cargo test -p prime_vulkan --features shader-tests --lib --locked gpu_omm_exact_shared_edge_diagnostic -- --ignored --nocapture --test-threads=1
```

游戏由用户按前文两版 `runClient` 命令手动执行。在“视频设置 → Prime PT · 诊断 · 实时视图”切换 OMM，覆盖树叶、十字草/花、完整 sprite 的旋转/镜像与 1/2/4 tile 重复、非 POT 高分辨率资源包和完整动画。裁切、斜向 UV、特殊顶点 alpha、双侧不同孔洞另检查 shader unknown 退路，确认没有随区块烘焙新模板；观察从两侧的孔洞、主命中深度/法线、太阳/局部光阴影、大气遮挡。覆盖静态编辑、区块卸载/重入、F3+T、冻结时切换 OMM、恢复实时及退出/重进；查设备日志 `OMM enabled` 和 native diagnostics `omm_capable/omm_enabled`，不能仅依据开关已选中认定实际使用。

性能比较关闭 validation，固定原生1920×1080、相机/seed/预算，分别记录开/关的稳态 CPU/GPU 帧时与更新尾延迟。记录 `omm_template_ms/omm_bind_ms/omm_resource_record_ms` 与 `omm_template_preparations/omm_bound_primitives`；同一覆盖资源代次内，移动、区块卸载/重入和设置切换不应增加模板准备或 `omm_resource_builds`。后者是当前 scene resource owner 的累计非空 GPU 构建数，owner 重建后重计，不能只根据 scene epoch 将变化归为真实资源重载。

保留当前资源唯一计数 `omm_resource_blocks/omm_resource_packed_bytes/omm_resource_storage_bytes`，以及 BLAS 引用计数 `omm_two_blocks/omm_four_blocks/omm_special_triangles/omm_referenced_packed_bytes`。同一块的多个 BLAS 引用会重复累计，引用压缩字节不等于显存；当前资源 storage 不含在途旧代、scratch、上传、索引和 arena 容量。保留设备/驱动、配置、全部离群值和实际显存峰值。首批地形检查大视距、多格重复和不匹配模板的源负载，资源重载检查整代构建与全部 cutout 重绑定，分别记录 `static_prepare` 总耗时与 OMM 子阶段；无窗口夹具不能代替实际游戏加载及移动验收。
