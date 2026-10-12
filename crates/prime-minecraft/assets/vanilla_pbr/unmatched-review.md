# 未匹配原版 PBR：逐项证据与可选补法

只分析现有 eligible 未匹配项，不新增匹配。名字仅用于寻找调查候选；裁切证明只覆盖明确列出的区域，不代表整张材质或模型已兼容。完整 UV、指标、原始文件和逐版本证据见 [unmatched-review.json](unmatched-review.json)。

| 原因 | 26.2 | 26.3 |
| --- | ---: | ---: |
| 源声明缺失：有基色/孤立 MERS，未声明 TextureSet | 10 | 10 |
| 新版本 Java 独有纹理，固定 Bedrock 源无对应项 | 0 | 32 |
| 固定 Bedrock 源缺对应的材质或图标 | 4 | 4 |
| 共享 atlas / 拆分、重排、裁剪 UV | 177 | 177 |
| 颜色、染色层或图案结构差异 | 7 | 6 |
| 调试、光级图标或破坏/UI 覆盖层 | 35 | 44 |
| 相近源存在，空间指标未达到可信阈值 | 6 | 24 |

水面两项属于用户主动艺术排除，列在 matching-report/unintegrated 中，不计入本表的原有239/297缺口。

可选推进顺序：先审查有整图/可见区精确 crop 的床顶和盆栽侧面，再做木牌/床侧的完整 UV 重排。硫磺尖刺有10对孤立 RGB24 MER文件（需明确 MER+S0规则和来源声明）；整图RGB一致数量为26.2 10/10、26.3 9/10。26.3的up_tip_merge仅在可见像素(6,0)有一处RGB差异，alpha一致。调试/覆盖图和仅particle角色无需为了数字覆盖强配；新杨木/坐垫等需要新来源或用户接受材质代理。

地图/烟花图标还要保留真实染色层；烟花星覆盖层与 Bedrock fireworks_charge 的可见 RGB 为0误差，却因可见覆盖不足10%且别名不同被保守拒绝。红石火把轮廓相关性高，但RGB亮暗变化超过现有门限。它们可单独批准艺术映射，不能据此放宽所有纹理的门限。

源绑定依据：固定 resource_pack/blocks.json 明确将干枯恶魂 north/south/east/west 对应 back/front/left/right；hydration0..3对应state1..4。锁链原版同时引用chain1/chain2，而Java模板将两组面装进同一纹理的0..3与3..6列；需验证对应重排，并非可直接复用整图。潮涌核心JSON模型没有实体UV，除已验证的整图可见RGB外仍需查特殊渲染器布局。

## 源声明缺失：有基色/孤立 MERS，未声明 TextureSet

### 硫磺尖刺

先核验孤立通道文件的编码并补显式来源声明；没有 MERS 时需手绘或选择近似材料。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/sulfur_spike_down_base` | 26.2, 26.3 | textures/blocks/sulfur_spike_down_base.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_down_frustum` | 26.2, 26.3 | textures/blocks/sulfur_spike_down_frustum.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_down_middle` | 26.2, 26.3 | textures/blocks/sulfur_spike_down_middle.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_down_tip` | 26.2, 26.3 | textures/blocks/sulfur_spike_down_tip.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_down_tip_merge` | 26.2, 26.3 | textures/blocks/sulfur_spike_down_tip_merge.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_base` | 26.2, 26.3 | textures/blocks/sulfur_spike_up_base.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_frustum` | 26.2, 26.3 | textures/blocks/sulfur_spike_up_frustum.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_middle` | 26.2, 26.3 | textures/blocks/sulfur_spike_up_middle.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_tip` | 26.2, 26.3 | textures/blocks/sulfur_spike_up_tip.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_tip_merge` | 26.2 | textures/blocks/sulfur_spike_up_tip_merge.png; RGB整图一致=True/可见一致=True; MERS=RGB[16, 16]; 无TextureSet |
| `minecraft:block/sulfur_spike_up_tip_merge` | 26.3 | textures/blocks/sulfur_spike_up_tip_merge.png; RGB整图一致=False/可见一致=False; MERS=RGB[16, 16]; 无TextureSet; RGB差异1像素(可见1) [[6, 0]] |

## 新版本 Java 独有纹理，固定 Bedrock 源无对应项

### red_shrub

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/red_shrub` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### shelf_mushroom_particle

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/shelf_mushroom_particle` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### shelf_mushroom_stage0

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/shelf_mushroom_stage0` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### shelf_mushroom_stage1

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/shelf_mushroom_stage1` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### 告示牌

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/poplar_hanging_sign` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_sign` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### 坐垫

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/black_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/blue_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/cyan_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/gray_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/green_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_blue_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_gray_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/lime_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/magenta_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/orange_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/pink_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/purple_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/white_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/yellow_cushion` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### 床面

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/straw_bed` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/straw_bed_particle` | 26.3 | 无已声明对应源；最近邻被拒绝 |

### 杨木

更新固定样例，或明确批准借用同类材料/手绘 PNG；不凭名字声明像素映射成功。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/orange_poplar_leaves` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_door_bottom` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_door_top` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_log` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_sapling` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/poplar_trapdoor` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/red_poplar_leaves` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/stripped_poplar_log` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/yellow_poplar_leaves` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/poplar_door` | 26.3 | 无已声明对应源；最近邻被拒绝 |

## 固定 Bedrock 源缺对应的材质或图标

### knowledge_book

寻找新的官方源，或由用户选择手绘/同类材质近似；继续保留默认材质也可。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/knowledge_book` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |

### pointed_dripstone

寻找新的官方源，或由用户选择手绘/同类材质近似；继续保留默认材质也可。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/pointed_dripstone` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |

### spectral_arrow

寻找新的官方源，或由用户选择手绘/同类材质近似；继续保留默认材质也可。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/spectral_arrow` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |

### 硫磺尖刺

寻找新的官方源，或由用户选择手绘/同类材质近似；继续保留默认材质也可。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/sulfur_spike` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |

## 共享 atlas / 拆分、重排、裁剪 UV

### bamboo_fence_gate

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/bamboo_fence_gate` | 26.2, 26.3 | textures/blocks/bamboo_fence_gate; RGB=0裁切 [0, 2, 2, 13]→[0, 2, 2, 13] identity |

### cocoa_stage2

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/cocoa_stage2` | 26.2, 26.3 | textures/blocks/cocoa_stage_2; RGB=0裁切 [8, 4, 16, 13]→[7, 4, 15, 13] identity |

### loom_top

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/loom_top` | 26.2, 26.3 | textures/blocks/loom_top; RGB=0裁切 [0, 0, 16, 16]→[0, 0, 16, 16] rotate180 |

### potted_azalea_bush_side

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/potted_azalea_bush_side` | 26.2, 26.3 | textures/blocks/potted_azalea_bush_side; RGB=0裁切 [4, 5, 12, 13]→[4, 4, 12, 12] identity |

### potted_flowering_azalea_bush_side

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/potted_flowering_azalea_bush_side` | 26.2, 26.3 | textures/blocks/potted_flowering_azalea_bush_side; RGB=0裁切 [4, 5, 12, 13]→[4, 4, 12, 12] identity |

### 告示牌

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/acacia_hanging_sign` | 26.2, 26.3 | textures/entity/acacia_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/acacia_sign` | 26.2, 26.3 | textures/entity/sign_acacia; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/bamboo_hanging_sign` | 26.2, 26.3 | textures/entity/bamboo_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/bamboo_sign` | 26.2, 26.3 | textures/entity/bamboo_sign; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/birch_hanging_sign` | 26.2, 26.3 | textures/entity/birch_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/birch_sign` | 26.2, 26.3 | textures/entity/sign_birch; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/cherry_hanging_sign` | 26.2, 26.3 | textures/entity/cherry_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/cherry_sign` | 26.2, 26.3 | textures/entity/cherry_sign; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/crimson_hanging_sign` | 26.2, 26.3 | textures/entity/crimson_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/crimson_sign` | 26.2, 26.3 | textures/entity/sign_crimson; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/dark_oak_hanging_sign` | 26.2, 26.3 | textures/entity/dark_oak_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/dark_oak_sign` | 26.2, 26.3 | textures/entity/sign_darkoak; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/jungle_hanging_sign` | 26.2, 26.3 | textures/entity/jungle_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/jungle_sign` | 26.2, 26.3 | textures/entity/sign_jungle; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/mangrove_hanging_sign` | 26.2, 26.3 | textures/entity/mangrove_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/mangrove_sign` | 26.2, 26.3 | textures/entity/mangrove_sign; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/oak_hanging_sign` | 26.2, 26.3 | textures/entity/oak_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/oak_sign` | 26.2, 26.3 | textures/entity/sign; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/pale_oak_hanging_sign` | 26.2, 26.3 | textures/entity/pale_oak_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/pale_oak_sign` | 26.2, 26.3 | textures/entity/pale_oak_sign; RGB=0裁切 [0, 16, 24, 28]→[28, 2, 52, 14] identity |
| `minecraft:block/spruce_hanging_sign` | 26.2, 26.3 | textures/entity/spruce_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/spruce_sign` | 26.2, 26.3 | textures/entity/sign_spruce; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |
| `minecraft:block/warped_hanging_sign` | 26.2, 26.3 | textures/entity/warped_hanging_sign; RGB=0裁切 [2, 16, 16, 26]→[2, 14, 16, 24] identity |
| `minecraft:block/warped_sign` | 26.2, 26.3 | textures/entity/sign_warped; RGB=0裁切 [0, 2, 24, 14]→[2, 2, 26, 14] identity |

### 干枯恶魂状态面

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/dried_ghast_hydration_0_bottom` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_bottom; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_0_east` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_left; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_0_north` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_back; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_0_south` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_front; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_0_top` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_top; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_0_west` | 26.2, 26.3 | textures/blocks/dried_ghast_state_1_right; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_1_bottom` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_bottom; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_1_east` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_left; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_1_north` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_back; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_1_south` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_front; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_1_top` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_top; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_1_west` | 26.2, 26.3 | textures/blocks/dried_ghast_state_2_right; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_2_bottom` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_bottom; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_2_east` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_left; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_2_north` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_back; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_2_south` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_front; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_2_top` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_top; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_2_west` | 26.2, 26.3 | textures/blocks/dried_ghast_state_3_right; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_3_bottom` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_bottom; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_3_east` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_left; RGB=0裁切 [0, 0, 10, 10]→[6, 0, 16, 10] flip-x |
| `minecraft:block/dried_ghast_hydration_3_north` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_back; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_3_south` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_front; near rmse, near mae, near centered |
| `minecraft:block/dried_ghast_hydration_3_top` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_top; RGB=0裁切 [0, 0, 10, 10]→[0, 0, 10, 10] identity |
| `minecraft:block/dried_ghast_hydration_3_west` | 26.2, 26.3 | textures/blocks/dried_ghast_state_4_right; RGB=0裁切 [0, 0, 10, 10]→[6, 0, 16, 10] flip-x |

### 床面

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/bed_down` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 0, 16, 16]→[20, 26, 36, 42] rotate180 |
| `minecraft:block/bed_head_north` | 26.2, 26.3 | textures/blocks/bed_head_end; near rmse, near mae, near centered |
| `minecraft:block/black_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/black_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/black_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/black_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/black_bed_head_east` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/black_bed_head_up` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/black_bed_head_west` | 26.2, 26.3 | textures/entity/bed/black; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/blue_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/blue_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/blue_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/blue_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/blue_bed_head_east` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/blue_bed_head_up` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/blue_bed_head_west` | 26.2, 26.3 | textures/entity/bed/blue; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/brown_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/brown_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/brown_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/brown_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/brown_bed_head_east` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/brown_bed_head_up` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/brown_bed_head_west` | 26.2, 26.3 | textures/entity/bed/brown; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/cyan_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/cyan_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/cyan_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/cyan_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/cyan_bed_head_east` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/cyan_bed_head_up` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/cyan_bed_head_west` | 26.2, 26.3 | textures/entity/bed/cyan; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/gray_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/gray_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/gray_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/gray_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/gray_bed_head_east` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/gray_bed_head_up` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/gray_bed_head_west` | 26.2, 26.3 | textures/entity/bed/gray; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/green_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/green_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/green_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/green_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/green_bed_head_east` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/green_bed_head_up` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/green_bed_head_west` | 26.2, 26.3 | textures/entity/bed/green; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/light_blue_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/light_blue_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/light_blue_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/light_blue_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/light_blue_bed_head_east` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/light_blue_bed_head_up` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/light_blue_bed_head_west` | 26.2, 26.3 | textures/entity/bed/light_blue; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/light_gray_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/light_gray_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/light_gray_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/light_gray_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/light_gray_bed_head_east` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/light_gray_bed_head_up` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/light_gray_bed_head_west` | 26.2, 26.3 | textures/entity/bed/silver; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/lime_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/lime_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/lime_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/lime_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/lime_bed_head_east` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/lime_bed_head_up` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/lime_bed_head_west` | 26.2, 26.3 | textures/entity/bed/lime; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/magenta_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/magenta_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/magenta_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/magenta_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/magenta_bed_head_east` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/magenta_bed_head_up` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/magenta_bed_head_west` | 26.2, 26.3 | textures/entity/bed/magenta; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/orange_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/orange_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/orange_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/orange_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/orange_bed_head_east` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/orange_bed_head_up` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/orange_bed_head_west` | 26.2, 26.3 | textures/entity/bed/orange; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/pink_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/pink_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/pink_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/pink_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/pink_bed_head_east` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/pink_bed_head_up` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/pink_bed_head_west` | 26.2, 26.3 | textures/entity/bed/pink; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/purple_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/purple_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/purple_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/purple_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/purple_bed_head_east` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/purple_bed_head_up` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/purple_bed_head_west` | 26.2, 26.3 | textures/entity/bed/purple; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/red_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/red_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/red_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/red_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/red_bed_head_east` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/red_bed_head_up` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/red_bed_head_west` | 26.2, 26.3 | textures/entity/bed/red; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/white_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/white_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/white_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/white_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/white_bed_head_east` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/white_bed_head_up` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/white_bed_head_west` | 26.2, 26.3 | textures/entity/bed/white; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |
| `minecraft:block/yellow_bed_foot_east` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 7, 16, 13]→[26, 22, 42, 28] rotate270 |
| `minecraft:block/yellow_bed_foot_south` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] flip-y |
| `minecraft:block/yellow_bed_foot_up` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 0, 16, 16]→[6, 22, 22, 38] identity |
| `minecraft:block/yellow_bed_foot_west` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 7, 16, 13]→[22, 58, 38, 64] rotate90 |
| `minecraft:block/yellow_bed_head_east` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 7, 16, 13]→[42, 22, 58, 28] rotate270 |
| `minecraft:block/yellow_bed_head_up` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 0, 16, 16]→[6, 6, 22, 22] identity |
| `minecraft:block/yellow_bed_head_west` | 26.2, 26.3 | textures/entity/bed/yellow; RGB=0裁切 [0, 7, 16, 13]→[6, 58, 22, 64] rotate90 |

### 潮涌核心

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/conduit` | 26.2, 26.3 | textures/blocks/conduit_base; aspect/layout mismatch |
| `minecraft:entity/conduit/base` | 26.2, 26.3 | textures/blocks/conduit_base; near rmse, near mae, near centered |
| `minecraft:entity/conduit/break_particle` | 26.2, 26.3 | textures/blocks/conduit_base; aspect/layout mismatch |
| `minecraft:entity/conduit/closed_eye` | 26.2, 26.3 | textures/blocks/conduit_closed; near rmse, near mae, near centered |
| `minecraft:entity/conduit/open_eye` | 26.2, 26.3 | textures/blocks/conduit_open; near rmse, near mae, near centered |

### 锁链

按实际模型 UV 建离线 crop/repack/旋转表，处理未使用区的 mip padding；完整像素/相位验证后再加入。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/copper_chain` | 26.2, 26.3 | textures/blocks/copper_chain1; near rmse, near mae, near centered |
| `minecraft:block/exposed_copper_chain` | 26.2, 26.3 | textures/blocks/exposed_copper_chain1; near rmse, near mae, near centered |
| `minecraft:block/iron_chain` | 26.2, 26.3 | textures/blocks/chain1; near rmse, near mae, near centered |
| `minecraft:block/oxidized_copper_chain` | 26.2, 26.3 | textures/blocks/oxidized_copper_chain1; near rmse, near mae, near centered |
| `minecraft:block/weathered_copper_chain` | 26.2, 26.3 | textures/blocks/weathered_copper_chain1; near rmse, near mae, near centered |

## 颜色、染色层或图案结构差异

### bamboo_small_leaves

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/bamboo_small_leaves` | 26.2, 26.3 | textures/blocks/bamboo_singleleaf; near rmse, near mae, near centered |

### firework_star_overlay

可在真实材质身份/染色层核验后单独批准 alias/forced-source；没有必要为了此例放宽所有稀疏纹理。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/firework_star_overlay` | 26.2, 26.3 | textures/items/fireworks_charge; RGB=0裁切 [0, 0, 16, 16]→[0, 0, 16, 16] identity |

### redstone_dust_line0

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/redstone_dust_line0` | 26.2, 26.3 | textures/blocks/redstone_dust_cross; near rmse, near centered, near corr |

### redstone_torch

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/redstone_torch` | 26.2, 26.3 | textures/blocks/redstone_torch_on; near rmse, near centered, recolor centered |

### water_overlay

水面已主动忽略原版 PBR；此覆盖层继续保留宿主默认，除非用户以后改变水面艺术选择。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/water_overlay` | 26.2, 26.3 | textures/blocks/water_still_grey; flat centered, flat logical name support |

### 地图图标/染色层

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/filled_map_markings` | 26.2 | textures/items/map_empty; near rmse, near mae, near centered |

### 潮涌核心

可在真实材质身份/染色层核验后单独批准 alias/forced-source；没有必要为了此例放宽所有稀疏纹理。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:entity/conduit/cage` | 26.2, 26.3 | textures/blocks/conduit_cage; RGB=0裁切 [0, 0, 32, 16]→[0, 0, 32, 16] identity |

## 调试、光级图标或破坏/UI 覆盖层

### 调试/覆盖图标

优先保留默认材质；若需物品图标 PBR，应单独指定艺术参数/源，不借用无关最近颜色。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/debug` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/debug2` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_0` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_1` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_2` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_3` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_4` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_5` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_6` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_7` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_8` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_oit_stage_9` | 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/destroy_stage_0` | 26.2, 26.3 | textures/environment/destroy_stage_0.png |
| `minecraft:block/destroy_stage_1` | 26.2, 26.3 | textures/environment/destroy_stage_1.png |
| `minecraft:block/destroy_stage_2` | 26.2, 26.3 | textures/environment/destroy_stage_2.png |
| `minecraft:block/destroy_stage_3` | 26.2, 26.3 | textures/environment/destroy_stage_3.png |
| `minecraft:block/destroy_stage_4` | 26.2, 26.3 | textures/environment/destroy_stage_4.png |
| `minecraft:block/destroy_stage_5` | 26.2, 26.3 | textures/environment/destroy_stage_5.png |
| `minecraft:block/destroy_stage_6` | 26.2, 26.3 | textures/environment/destroy_stage_6.png |
| `minecraft:block/destroy_stage_7` | 26.2, 26.3 | textures/environment/destroy_stage_7.png |
| `minecraft:block/destroy_stage_8` | 26.2, 26.3 | textures/environment/destroy_stage_8.png |
| `minecraft:block/destroy_stage_9` | 26.2, 26.3 | textures/environment/destroy_stage_9.png |
| `minecraft:block/test_block_accept` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/test_block_fail` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/test_block_log` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/test_block_start` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:block/test_instance_block` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light` | 26.2 | textures/gui/newgui/buttons/borderless/light.png |
| `minecraft:item/light_00` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_01` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_02` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_03` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_04` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_05` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_06` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_07` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_08` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_09` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_10` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_11` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_12` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_13` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_14` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/light_15` | 26.2, 26.3 | 无已声明对应源；最近邻被拒绝 |
| `minecraft:item/structure_void` | 26.2, 26.3 | textures/blocks/structure_void; flat rmse, flat centered |

## 相近源存在，空间指标未达到可信阈值

### bamboo_fence_gate_particle

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/bamboo_fence_gate_particle` | 26.2, 26.3 | textures/blocks/bamboo_fence_gate; near rmse, near mae, near centered |

### bamboo_fence_particle

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/bamboo_fence_particle` | 26.2, 26.3 | textures/blocks/bamboo_fence; near rmse, near mae, near centered |

### kelp

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/kelp` | 26.2, 26.3 | textures/blocks/kelp_a; near corr, near edge, recolor centered |

### soul_fire_1

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:block/soul_fire_1` | 26.2, 26.3 | textures/blocks/soul_fire_1; near rmse, near mae, near centered |

### spyglass_model

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/spyglass_model` | 26.2, 26.3 | textures/entity/spyglass; near rmse, near mae, near centered |

### wildflowers

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/wildflowers` | 26.2, 26.3 | textures/blocks/wildflowers; near rmse, near mae, near centered |

### 地图图标/染色层

可人工批准特定候选、颜色/染色层或 UV 适配；应先验证图案和材质边界，避免直接放宽全局阈值。

| 具体 Java ID | 版本 | 相关源 / 证据 |
| --- | --- | --- |
| `minecraft:item/abandoned_camp_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/buried_ancient_city_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/buried_mineshaft_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/buried_treasure_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/buried_trial_chambers_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/desert_pyramid_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/desert_village_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/filled_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/jungle_pyramid_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/ocean_monument_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/plains_village_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/savanna_village_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/snowy_village_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/swamp_hut_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/taiga_village_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/warm_ocean_ruins_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
| `minecraft:item/woodland_mansion_map` | 26.3 | textures/items/map_empty; near rmse, near mae, near centered |
