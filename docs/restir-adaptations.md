# ReSTIR PT 适配登记

本文统一登记 Prime 相对 Falcor ReSTIR PT Enhanced 的接口适配、内部语义调整、保留的近似与已撤回分支。源码中的 `RA-xxx` 注释引用本文条目；维护内部行为时同步更新对应条目，不把单次测量与调查流水写入这里。管线、固定配置和资源寿命见 [ReSTIR PT](restir-pt.md)，性能敏感修改仍须回答 [PT 性能问题](pt-state-design.md#修改前必须回答的性能问题)。

参考版本为 Falcor 9.0，提交 `759aad033ff610fb0d82c74f7e0a508d0096d5f2` 的 `Source/Modules/ReSTIRPathTracing`。许可与来源见 [第三方声明](../THIRD_PARTY_NOTICES.md)。与上游一致仅说明移植来源，不代替几何测度、支持域与估计量的独立验证；当前不声明任意动态场景或 Prime 材质扩展下严格无偏。

## 登记表

| ID | 类别与状态 | 当前适配及必要边界 | 实现与验证入口 |
| --- | --- | --- | --- |
| RA-001 | 保留：接口适配 | Prime 自持场景、OpenPBR/LabPBR 材质、TLAS、纹理和大气；上游 reservoir/replay 数学通过窄接口消费这些资源。464 B 参数块、80 B reservoir 和 60 B replay 的自然 BDA 布局需在真实 SPIR-V 核验；指针依提交完成或取消证明存活。接口替换不引入逐顶点宿主回调。 | `restir/scene.slang`、`parameters.slang`、`bindings.slang`；`scripts/test-restir-layout.py`、借用历史接受测试 |
| RA-002 | 保留：坐标适配 | 核心使用 top-left 像素中心 `pixel+.5`；RR 当前 input-pixel jitter 加入主采样；时间 donor 选择遵循上游去 jitter 的运动：`floor(pixel+.5+previousProjection-currentProjection)`。当前 pinhole 交点满足 `currentProjection=pixel+.5+currentJitter`，所以 previous 投影扣当前 jitter；源重放仍使用已接受前帧 jitter。旧 donor 扣前帧 jitter 会将静止 Halton 取整误差持续搬运，此处只修复该接口差异。Native 含噪输出使用零 jitter。不同 bank、相机和 jitter 只在接受提交后推进；不保证任意运动下整数地址周期归零。 | `scene.slang`、`restir_workload.slang`、`src/restir.rs`；原生地址与非零 jitter 测试 |
| RA-003 | 保留：源与目的连接 chart 一致性修复 | 有限 BSDF 到达以原 `spawnRay` 安全起点到物理端点的 query 向量定义方向、距离和 G；shift 的 first BSDF、RC 入射 Wo、G/J 和逆 footprint 使用同一向量。只有终端 NEE 使用物理 receiver chart，RC 后续的 NEE 不改变到达段分类。物理/query 连接保留源事件及相同偏移侧，含 `>=0` 的切线边界。后缀查询结束后再计算可见性 begin，不长期保存 early begin。原始生成与偏移算法不变。 | `path.slang:restirSegment`、`shift.slang`；强法线/近接触 self-shift、近光源 query 分解 |
| RA-004 | 保留：面积测度修复 | 源生成、重放与 shift 逆 footprint 的面积项均用几何 ng；BSDF 响应继续使用着色法线 ns。强法线贴图不应使同一源连接因错误面积法线失去支持。混合测度 J 仍为目的/source G 之比，不额外乘 BSDF PDF 比。 | `path.slang`、`shift.slang`；独立端点/ng/安全偏移面积 oracle、Slang CPU reservoir 数学 |
| RA-005 | 保留：Prime 光源 proposal | TREE 功率距离 PMF 依赖物理 receiver 位置，不依赖法线或 view。世界/页内选光及 inverse PDF 使用同一 receiver；有限 emitter shift 在竞争存在时重算目的 receiver 的 PMF。旧接收点 PMF 不能代替新值。终端 NEE 的半三角形、barycentrics 和物理 endpoint 与实际初始提议保持一致。 | `scene.slang:restirEmitterPdf/restirSampleLight`、`path.slang`、`shift.slang`；距离树 forward/reverse、NEE 物理点测试 |
| RA-006 | 保留：Prime 环境扩展 | 天空和有限太阳是不同 RIS 域；天空仅有 BSDF 命中，太阳可与 NEE 竞争。各域保存自身 light type/radiance/PDF，不伪造环境旋转，不把有限 emitter 与环境端点互换。 | `path.slang:restirAddEnvironment`、`scene.slang`、`shift.slang`；环境/发光/NEE 后缀测试、普通 PT 均值对照 |
| RA-007 | 保留：Prime SSS 续接扩展与竞争 PDF 修复 | authored 非 optical 薄 SSS 可由源 BSDF 实际采样透射；续接求值接受相同的几何/着色事件，直接 NEE 保留 opaque reflection 支持。actual PDF 用于 G、roughness 与 footprint；非 optical transmission 的直接光照竞争 PDF 为零，末段 emitter/environment/相邻 RC MIS 对应为一。没有竞争时有限 emitter 不执行 inverse light PDF；不把这个优化泛化成环境 PDF 也必须为零。物理 thin 与材质 thin 为不同源字段。 | `scene.slang:restirEvaluate/restirDirectCompetitionPdf`、`path.slang`、`shift.slang`；真实 plain/mapped SSS 源路径 witness |
| RA-008 | 保留：直接 NEE 支持修复 | opaque 局部灯与太阳只接受朝当前 view 的几何前侧，optical 保留透射支持。普通 PT 的表面已朝 view，ReSTIR 保留存储 ng，因此 helper 显式按 view 判断前侧，与 local shadowReceiver 的翻转一致。太阳新提议消耗固定随机维度后在阴影查询前拒绝；已有错误终端 Sun NEE 在当前 shift 消费处也拒绝，不依赖全局 reset。续接求值不受直接 NEE gate 限制。 | `scene.slang`、`shift.slang`；rear-Sun 真实源 witness、synthetic stale-Sun consumer、SSS 续接回归 |
| RA-009 | 保留：身份与支持适配 | HitInfo 保存实际 quad/half/barycentric 和复合材质选择，终端 NEE 指向真实稳定 material quad slot。历史访问先检查 monotonic slot 修订、边界与 half 支持；局部替换不能把旧 primitive 数值重解释为新几何。coating 的源材质选择及底层物理边界 media 分开保留。 | `identity.slang`、`scene.slang`、`restir/history_support.slang`、静态 quad 发布；topology/slot/support/资源重载测试 |
| RA-010 | 保留：上游 RNG、重放与 LOD | TinyUniform、原配对邻域、RIS/merge 顺序和 roulette 概率规则保留；重放消耗 roulette 随机数但不重做决定或 proposal 重权。每次 NEE 固定消耗 `1+4+2` 维，coverage 是独立 position-hash/path-seed 域。显式材质 LOD 0 保留，上游未支持 BSDF component indexing 的接口走 PDF roughness。Offline `sampleOffset` 在 scene binding 加一次，不能在入口重复加。 | `rng.slang`、`pairing.slang`、`path.slang`、`bindings.slang`；`test-restir-math.py`、`test-restir-temporal.py`、Offline batch/sequence 对照 |
| RA-011 | 保留且有近似边界：动态更新与介质 | 当前场景 temporal suffix update 重放缓存后缀，source integrand/weight 与源 MIS 顺序保留；不保存旧场景副本。prefix 显式携带入射介质，thick optical 由物理边界侧选 medium，opaque/physical-thin 沿源模型保留 incoming。连接使用 Prime 直线透射查询及两端安全偏移。任意动态 scene/proposal 更新、偏移裁短的吸收距离和物理 light PDF/query BSDF PDF 竞争不声明严格无偏。 | `bindings.slang`、`path.slang:restirUpdateSuffix`、`shift.slang`；mixed 自映射、cached-suffix 动态测试；开放边界见下文 |
| RA-012 | 已退役：Sphere 专属适配与实验 | Sphere 未达到其声明的性能目标，生产采样器、法线状态、变体与专属测试退役。其 view-dependent NEE forced-emitter 分类和 mapped-RC 最后一跳竞争 PDF 强制刷新随之删除；TREE 没有这个 PMF 依赖，不保留额外后缀 Ray Query。Sphere C4 的 1-ULP 非等价候选及其他未验收实验撤回，不作为当前实现。 | 当前生产仅 TREE；历史性能、失败/通过 banks 与候选复盘保留在 ignored `artifacts`，不计为退役后验证 |
| RA-013 | 保留：Prime 颜色顺序 | 真实源色与发光遵循 Prime working RGB/linear BT.709 合同。有限 NEE 的源顺序先在发光域乘 RGB 可见性再转换；BSDF emitter hit 延续原输运顺序。非对角颜色转换与彩色 Beer 不能随意交换。显示/曝光只在既定输出阶段执行。 | `scene.slang`、`shift.slang`、`restir_resolve.slang`；彩色介质、emitter/NEE、自映射与 RR guide 图像测试 |

## 保留的上游结构

Point reservoir、Hybrid shift、Compact retrace、双 footprint 分类、双向 pairwise MIS、三个互反配对邻居、一次空间轮次和时间 M 上限沿用固定上游配置。未加入 area reservoir、DoF、splatting、robust temporal、压缩或 duplicate map。初始源概率、RIS 归一化和合并公式见 [管线说明](restir-pt.md#gpu-数据流)。源码注释必须明确是在替换数据接口、修复 Prime 源/目的合同，还是扩展 Prime 材质/光照域，不能笼统写成对上游算法的优化。

全局失效策略是 Prime 的宿主/资源适配。只由已证明的全局事件请求重置，局部支持变化由身份表拒绝；真实存储重建仍冷启动。诊断开关不会把未初始化存储伪装为有效历史。具体事件、pending/commit/cancel 与完成证明见 [历史合同](restir-pt.md#历史与完成证明)，不在登记表重复维护事件清单。

## 开放边界

静态缓存并不等于所有重连视角/介质变化已被证明正确。固定 thick optical RC 与存储 Wi 通常锁定物理出射侧介质，但 opaque/physical-thin 使用 incoming medium。当前连接透射查询积分经过的界面，未把终端 medium 返回给 RC 求值；连接跨静态介质边界及 authored SSS 跨 RC 入射侧的缓存一致性仍需独立实际源路径 witness。盲目刷新可能使用错误的 starting medium，不能由人工增加 extinction 的测试推出真实相机跨界面已覆盖。直线连接绕过厚介质的折射顶点也属于单独的支持/测度边界。

完整 Halton 周期地址测试记录连续 jitter 的周期和与整数重投影地址和；当前去 jitter donor 合同要求静止时全部 x/y 地址保持同一像素、无边界拒绝、每周期地址和为零，不保留半像素边界例外。修复前带前帧 jitter 的实际地址与边界诊断仍保留在历史 artifacts。整数地址搬运与被选中 reservoir 家族的实际搬运不是同一个保证，种子匹配仅作诊断。数学均值、地址与自映射回归不能证明视觉相关性消失。正反相机运动测试按完整历史链估计不确定度，连续帧/像素不能冒充独立样本；跨像素版本改变了 FOV 和 footprint，不能把方向差的变化单独归因于像素搬运。局部运动、完整游戏的蠕动和 RR 内部含噪预览滚动仍按 [技术债](../HACK.md) 跟踪。

## 验证入口与证据范围

编译与真实布局入口为 `scripts/test-restir-layout.py`；生产 Slang 数学 CPU 行为入口为 `scripts/test-restir-math.py` 和 `scripts/test-restir-temporal.py`。Vulkan 测试通过 `cargo test -p prime_vulkan --features shader-tests -- --ignored --test-threads=1` 显式运行，可按下面的完整模块/函数名过滤。它们使用无窗口实际 Ray Query，不启动游戏。

| 合同 | GPU 测试入口 |
| --- | --- |
| source/replay 与介质 | `restir_adapter_tests::gpu_restir_generate_replay_self_shift_preserves_mixed_integral_and_media` |
| ns/ng、近接触与 query chart | `restir_adapter_tests::gpu_restir_self_shift_preserves_strong_normal_and_grazing_measures`；`restir_adapter_tests::gpu_restir_near_emitter_residual_matches_ray_origin_and_bsdf_measures` |
| SSS actual/competition 支持 | `restir_adapter_tests::gpu_restir_authored_thin_sss_self_shift_preserves_source_measure` |
| 新/旧 Sun NEE 支持 | `restir_adapter_tests::gpu_restir_authored_thin_sss_rear_sun_has_no_direct_nee_competitor`；`restir_adapter_tests::gpu_restir_stale_rear_sun_nee_is_rejected_by_current_consumer` |
| TREE PMF 与 NEE 物理点 | `shader_tests::gpu_distance_tree_integer_support_forward_reverse_and_geometry_boundaries`；`restir_history_tests::gpu_restir_nee_sampled_points_match_physical_endpoints` |
| 稳定 quad 与局部失效 | `restir_history_tests::gpu_restir_topology_edits_preserve_unmodified_quads_and_nee_endpoints`；`restir_support_tests::gpu_restir_support_changes_reject_only_affected_endpoints_without_ghosts` |
| 动态 suffix 与非全局更新 | `restir_history_tests::gpu_restir_temporal_update_replays_all_cached_suffix_cases`；`restir_history_tests::gpu_restir_retains_history_without_a_proved_global_change` |
| 真实 temporal 地址/jitter | `restir_reprojection_tests::gpu_restir_native_temporal_pixels_stay_fixed_and_follow_camera_projection`；`restir_reprojection_tests::gpu_restir_temporal_nonzero_jitter_uses_current_and_accepted_previous_samples`；`restir_reprojection_tests::gpu_restir_production_halton_cycles_record_static_temporal_address_transport` |
| 持续复用与运动链均值 | `restir_tests::gpu_restir_continuous_reuse_chain_means_agree_with_path_trace`；`restir_tests::gpu_restir_opposite_camera_motion_preserves_transport_mean`；`restir_tests::gpu_restir_cross_pixel_opposite_camera_motion_preserves_transport_mean` |
| RR guide 与运动合同 | `restir_rr_tests::gpu_restir_rr_production_guides_and_resolve_images`；`restir_rr_tests::gpu_restir_rr_motion_matches_independent_world_projection` |

验收必须对应当前源码构建，不能把退役前 Sphere 或旧 shader bank 的通过数当作当前 Tree 结果。严格阈值与 actual source/branch 覆盖条件保留；源码文字匹配、编译成功、未执行、空 raw 目录或跳过都不算行为通过。Synthetic stale-Sun 只证明当前消费者会局部拒绝指定无效缓存，不是旧 reservoir 的无损重放。Mixed/self-shift 与动态 suffix 的介质覆盖也不代替静态相机跨界面验证。正式性能数据固定原生 1920×1080 场景/seed/预算，留在 `artifacts`；小夹具数学结果不能外推游戏画面或帧率。
