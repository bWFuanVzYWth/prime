# rectangle_decomposition

Prime PT workspace 中的独立 CPU 算法 crate，复用自体素引擎。来源、集成改动与使用边界见 [SOURCE.md](SOURCE.md)。目前没有 Minecraft 网格运行期消费者。

把 **64×64 彩色像素区域分解为数量最少的同色矩形**。适用于体素 mesh 中同一方向、同一平面上的面合并，也可用于一般像素区域分解。

每个非背景像素恰好被覆盖一次；矩形内部没有空洞、没有混色，矩形之间没有面积重叠。支持含孔区域和多个不相连的区域。不同最优解可以采用不同切法，输出允许 T 形接缝。

算法先找出能同时处理两个凹角的内部线段（**chord**），通过二分图最大匹配选出最多条互不冲突的 chord，再扫描生成矩形。具体解释见 [从像素到最少矩形](docs/algorithm.md)。

## 输入、输出和边界

- 输入为 `QuadLeaf64` 列表：`(u, v)` 是左上角，边长为 `2^lod`，`lod` 在 `0..=6`；坐标必须按边长对齐，叶子不得越界或重叠。
- `value: NonZeroU16` 是调用方定义的颜色／材质标签；未提供的像素是背景。普通像素直接用 `lod = 0`，不需要先构建四叉树。
- 输出为带相同标签的 `Rectangle`，范围 `x.start..x.end`、`y.start..y.end` 左闭右开。矩形尺寸不受输入叶子的 LOD 限制。
- 体素调用方负责可见面筛选、方向分层和合并条件；需要区分的属性应反映在输入标签中。本库处理的是二维区域划分。

目标是最少矩形数量。输入不要求预排序或预合并；含孔区域使用同一套精确算法。

## 最小用法

同级 workspace crate 可使用本地路径依赖；有实际消费者后，也可将此路径登记到根 `workspace.dependencies`：

```toml
[dependencies]
rectangle_decomposition = { path = "../rectangle-decomposition" }
```

然后使用可复用的 scratch：

```rust
use std::num::NonZeroU16;
use rectangle_decomposition::{QuadLeaf64, SparseOptimalScratch64, SparseQuadError};

fn main() -> Result<(), SparseQuadError> {
    // 三个同色像素构成 L 形：AA / A.，最少需要两个矩形。
    let value = NonZeroU16::MIN;
    let leaves = [
        QuadLeaf64 { u: 0, v: 0, lod: 0, value },
        QuadLeaf64 { u: 1, v: 0, lod: 0, value },
        QuadLeaf64 { u: 0, v: 1, lod: 0, value },
    ];

    // 每个 worker 在任务循环外构造一次，后续通过 &mut 复用。
    let mut scratch = SparseOptimalScratch64::new();
    let rectangles = scratch.decompose_borrowed(&leaves)?;
    assert_eq!(rectangles.len(), 2);
    for rectangle in rectangles {
        println!("{rectangle:?}");
    }
    Ok(())
}
```

初始化、借用分解和销毁不调用堆分配器；返回的切片属于 scratch，需在下一次可变使用前消费完毕。scratch 为固定容量内联对象，在 x86_64 上约 **366 KiB**，调用方需为 worker 配置足够的栈并避免按值搬运。资源契约与容量证明见 [scratch 与接口](docs/scratch.md)。

也提供拥有输入的 `SparseQuadImage64`、增量输入的 `SparseLayerBuilder64`，以及返回 `Vec<Rectangle>` 的便捷接口；这些拥有型容器可以分配堆内存。库本身没有第三方运行时依赖，库代码禁止 `unsafe`。

## 接手研究的阅读顺序

| 文档 | 回答的问题 |
| --- | --- |
| [算法原理](docs/algorithm.md) | 凹角、chord、冲突图是什么，为什么最大匹配能得到最少矩形？ |
| [实现与优化](docs/matching.md) | 数学步骤对应哪些代码，整数网格和 HKDW 如何减少工作？ |
| [scratch 与接口](docs/scratch.md) | 哪些操作分配内存，固定容量上界和线程栈怎样核算？ |
| [研究与验证](docs/research.md) | O(n³) 如何得到，已知下界是什么，修改算法怎样验证？ |
| [文献与出处](docs/references.md) | 先读哪些论文，文献算法与当前实现有哪些对应关系？ |

## 开发入口

```sh
cargo test -p rectangle_decomposition --all-features
cargo clippy -p rectangle_decomposition --all-targets --all-features -- -D warnings
cargo fmt -p rectangle_decomposition --check
cargo bench -p rectangle_decomposition --bench worst_case
cargo run -p rectangle_decomposition --release --features profile --example worst_case_profile
```

`profile` feature 提供分阶段计时；Criterion 仅为开发依赖。基准的范围和正确使用方式见 [研究与验证](docs/research.md)。
