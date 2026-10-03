# 光源采样的成本、噪声与收敛测试

`prime_tools` 的 `light-sampling` 是显式启用的无窗口实验，不改变游戏的采样方法、设置或 ABI。它回答两个问题：选灯实现本身有多贵；包含概率补偿后，单位 GPU 时间能减少多少误差。运行方法见 [CONTRIBUTING](../../CONTRIBUTING.md)。

## 测量范围

每个 dispatch 在原生 1920×1080 上，对每个像素执行一次面光源选择、quad 上均匀面积采样、Lambert 直接光照和线性累积。画面由 8×4 个已知接收点的等面积区域组成，同一区域的像素使用不同采样序列。这有意去掉相机、间接反弹、材质解码、阴影射线、大气、显示和游戏调度的混杂成本。GPU 时间是这个直接光照 pass 的时间，**不能转换成完整游戏 FPS**。`batches.csv` 的 CPU 时间含同步提交和等待，也不是生产 CPU 帧时。

白色水平矩形面光源朝向接收平面，反照率为 1；线性标量值同时是灰色辐射亮度与亮度。`uniform` 是均匀灯阵，`near_far` 是少量近灯和大量高功率远灯，`occluded` 有无限不透明隔墙 x=0，`many` 有 200704 个发光 quad / 784 个局部页。隔墙可见性解析判断，灯不会跨墙；空间提议不知道遮挡。夹具不是 Minecraft 存档，不包含彩色、纹理发光、复杂法线、透射或 MIS。

`many` 的每页是 16×16 的紧凑区域。可选 `many_strips` 保留完全相同的灯位置、面积和辐射亮度，但每页沿行收纳256灯，包围盒变成长条。这个控制组用于检查页布局对空间提议和缓存的影响；不得将两者不同的方差直接归因于光场改变。

## 三种方法与两个采样序列

| 方法 | 提议与实现 |
| --- | --- |
| `power_tree` | 实验的历史功率树基线；直接调用保留的 Slang `selectPowerLight`，复用 Rust 森林构建和32B浮点节点ABI；与生产可选Tree的8B GPU整数CDF选择不同，生产CPU量化节点仍为16B |
| `power_alias` | 世界页、局部页各一次 alias 查找；同样按功率，条件残余随机量传到下一层 |
| `spatial_tree` | 实验性接收点相关树；分支重要性为 power / max(中心距离平方, 包围球半径平方)，沿途相乘得到所用 PDF |

空间树仅为可核验的候选，没有移植旧项目的整套树，也没有 PBRT 的法线/发射锥界限；不能用它代替对最终空间算法的验收。当前生产默认路径使用单级局部 alias 与全局功率 alias，契约见[表面编译](../surface-compiler.md)；这里的三种方法是独立实验的固定对照。实验不使用发光命中 MIS，因此尚未实现候选空间树在生产中的反向命中 PDF；GPU 检查将前向选灯 PDF 与独立 CPU 遍历概率比较，不能冒充完整 MIS 检查。

IID 使用独立哈希域的伪随机数；Z-Sobol 调用生产模块、固定 S=8 和独立灯域，超过 256 spp 换 scramble。各方法固定相同场景、面积映射、样本数和种子。**同一个功率 PDF 的树与 alias 具有相同的连续 IID 方差；不同的随机数映射仍可能改变 QMC 表现。** 不以数据结构名称推断采样质量。

## 参考值与理论

设第 i 个灯上的均匀面积参数为 u，`f_i(u)` 已包含面积、辐射亮度、几何项、Lambert BRDF 和可见性，选灯概率为 `p_i`，单样本为 `X=f_i(u)/p_i`。

- 真值 `I = Σ ∫ f_i(u) du`：f64 矩形照度边界积分，与 GPU 估计器及随机序列独立，无 Monte Carlo 参考图噪声。
- 单样本方差 `σ² = Σ ∫ f_i(u)² du / p_i − I²`：二阶矩由 4×4 Gauss–Legendre 积分计算，并记录与 2×2 的相对差。超过 1e-4 拒绝生成结论；此差不是严格误差上界。
- 独立无偏采样 `MSE(N)=σ²/N`，RMSE 为 `σ/√N`；理论 log(MSE) 斜率为 −1。理论列用于 IID，也作为 QMC 的对照，**不作为 QMC 的收敛保证**。
- `oracle_discrete_variance` 采用 `p_i∝sqrt(∫f_i²)`，是保持灯内均匀面积采样时最优离散提议的参考下界；不计计算这些概率的成本，也不作为可用的渲染实现。

误差直接在线性 HDR 上计算，不做 tone mapping、裁剪、去噪或逐像素相对误差除法。`relative_rmse=sqrt(mean(error²)/mean(reference²))`，暗点不引入除零。每个 spp 使用多个独立 seed/scramble，保留每次 MSE；跨种子的样本方差估计噪声，`bias_squared=MSE−variance` 是可为负的无偏估计，不人为夹到零。MSE 标准误按种子级统计，不能把 QMC 的相关像素当成独立重复来缩小误差条。

数学背景：[PBRT Monte Carlo Basics](https://pbr-book.org/4ed/Monte_Carlo_Integration/Monte_Carlo_Basics) 与 [Light Sampling](https://pbr-book.org/4ed/Light_Sources/Light_Sampling)。当前空间启发式与解析积分由本测试独立实现。

## 公平比较与输出

每个变体单独预热，32 帧为一批循环轮换顺序，每批用不同但跨方法一致的 seed，硬件 timestamp 包围 dispatch；上传、管线创建、参考计算和像素读回在计时外。没有丢弃离群值。理论、误差与时间分开采集，`gpu_budget_ms = spp × 稳态平均 pass 时间` 是累计 GPU 工作量估计，不是端到端墙钟到达时间。

优先比较 MSE 对累计 GPU 毫秒的曲线、`MSE×GPU毫秒` 和相同误差所需时间，同时报告 p50/p95/max 与实际方差。`iid_predicted_ms_to_5pct_rmse` 是 IID 渐近预测，超过已测样本范围须标记外推。Z-Sobol 的时间收益应从实测曲线读取，不用 IID 预测代替。有限 seed 的 slope 是经验拟合，不是证明。

输出 `run.txt`（配置、设备、工具链、提交、脏工作区、完成标记）、`frames.csv`（全部预热和稳态 GPU 时间）、`batches.csv`（同步 CPU 批次耗时）、`timings.csv`、`theory.csv`、`trials.csv`、`metrics.csv`、`slopes.csv`，以及每场景参考和第一 seed 各 spp 的线性 PFM。`sampler_bytes` 仅是该方法逻辑节点/alias 大小，发光记录另列；实验同时驻留三种方法，不把这些数当成实际游戏 VRAM。

`ErrorAccumulator` 与 `convergence_slope` 不依赖夹具采样器，可用于之后的真实线性渲染读回。进入游戏可开关诊断前仍需接入真实帧、固定场景和独立参考，补齐真实材质、遮挡、BSDF/MIS 和反向 PDF 验证；当前不增加 Java 捕获字段、运行时开关或新的生产调度层。
