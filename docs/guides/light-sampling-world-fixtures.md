# 存档派生的光源采样夹具

`scripts/extract-light-fixture.py` 为独立采样实验生成矩形灯、接收点与离散可见性。
它只读 Anvil 存档，不启动游戏窗口、不修改世界。它是方向筛选用的代理场景，
不是生产翻译层的场景导出器，也不提供游戏帧率基准。

## 生成

先用对应 Minecraft 版本的真实注册表导出状态、默认属性、发光等级和 outline boxes。
`cpuSmoke` 在 preLaunch 退出，不创建窗口或 GPU 设备。输出文件必须不存在。

```powershell
.\gradlew.bat :mc-26.3:cpuSmoke '-PprimeptSamplingRegistry=artifacts/sampling/registry-26.3.json' --no-parallel
# 26.2 使用 :mc-26.2:cpuSmoke，并给出另一输出路径。
```

Python 工具需要 NumPy 和 Numba；相机五个参数为眼睛世界坐标、Minecraft yaw、pitch。
FOV 固定为 70 度，截取相机周围的正方形区域，半径默认 192 方块；范围对齐 chunk。
接收点取自 128×72 outline 深度图的 8×4 分区。天空分区取最近的有效表面，
因此接收点可能重复；实际采样像素记录在 `scene.json`。

```powershell
python scripts/extract-light-fixture.py --self-test
python scripts/extract-light-fixture.py --world '对应版本的存档目录' --registry artifacts/sampling/registry-26.3.json --output artifacts/sampling/view-a --camera -279.3168 228.3305 359.895 -147.1487 31.34995 --radius 192 --threads 4
```

工具支持传统 `Name/Properties` 与 26.3 的 `id/properties` / 默认状态简写。
缺省属性来自实际宿主默认状态，未知状态、压缩格式或形状求值错误会失败。
输出目录必须不存在，避免覆盖实验记录。`scene.json` 包含源 region/注册表哈希、
相机、裁剪范围、被省略的状态、准备耗时和近似范围。

## 共同输入契约

`emitters.csv`：

```text
id,page,cx,cy,cz,ux,uy,uz,vx,vy,vz,radiance,power,two_sided
```

`id` 连续且从零开始，同页连续排列。`c` 是中心，`u/v` 为半边向量；
表面点为 `c + (2s-1)u + (2t-1)v`，`s/t` 位于 `[0,1]`。
发射法线为 `normalize(cross(u,v))`，面积为 `4*length(cross(u,v))`。
页来自源 section，`power=area*radiance`。当前导出全是单面灯。

`receivers.csv`：

```text
id,x,y,z,nx,ny,nz,reference
```

固定 32 个接收点，顺序对应 8×4 tile。位置与灯都相对裁剪区域原点，法线为单位向量。
几何和辐射值先转成 f32 再以 f64 积分，使参考使用 GPU 实际消费的坐标。
`reference` 是单位 Lambert 反照率下的出射亮度，只含局部灯，无太阳和天空。

`visibility.bin` 每字节只能是 0 或 1，不是归一化的 0/255。
索引为 `((receiver * light_count + light) * 16 + vbin * 4 + ubin)`。
每个矩形分成 4×4 参数域，bin 内可见性恒定，由 bin 中心的 outline AABB 射线决定。
参考值对每个可见子矩形做接收半球裁剪，再用 f64 球面多边形边界积分精确求和。
这避免使用中心点近似值去判断面积采样是否有偏，但并不提高原始遮挡代理的精度。

## 比较边界

- 光源几何来自源 outline boxes，包含内部面；不等同于 baked quad，也没有生产矩形合并。
- 透明/部分 cutout 几何省略，不模拟折射、透射、alpha 纹理和材质分层。
- 辐射为单色常数 `1.5*(emission/15)^2`，不含纹理与 tint；没有 outline 的发光状态会省略并记录。
- 远于裁剪范围的灯和遮挡不在输入内；缺失 chunk 视为空。工具只支持主世界的 -64..319 高度。
- 32 个相干 tile 不能代表真实全分辨率 G-buffer。空间复用会明显受益，不能直接推断真实视图的 ReSTIR 收益。
- 可见性表不是生产 shadow ray。采样 pass 时间不包含真实 BVH、材质/纹理消费、完整路径和游戏调度。

各分叉必须使用同一份输入、分辨率、种子与质量设置，并保留逐接收点误差。
比较样本数时计入每个方法实际灯求值数；比较时间时计入网格/候选池刷新、
reservoir 的全部 pass 和 barrier，并单独报告常驻状态显存。
代理输入只能帮助筛掉明显劣势的方法。进入生产前仍需实际 G-buffer、遮挡射线、
运动/开关灯失效及全帧计时验证。
