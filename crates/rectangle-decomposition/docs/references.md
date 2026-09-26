# 文献与出处

[返回 README](../README.md) · 配合阅读：[算法原理](algorithm.md)、[实现与优化](matching.md)

本实现组合了几何归约、二分图匹配和像素网格工程化三层思路。下面按研究用途组织文献，不将整个实现归于单篇论文。

## 先理解矩形分解的几何归约

**Leonard A. Ferrari, P. V. Sankar, Jack Sklansky.** *Minimal rectangular partitions of digitized blobs*. Computer Vision, Graphics, and Image Processing, 28(1):58–71, 1984. [出版页面](https://www.sciencedirect.com/science/article/pii/0734189X84901397)，DOI: `10.1016/0734-189X(84)90139-7`。

这是理解数字区域最少矩形划分的直接入口：用连接凹角的线段建立二分冲突图，通过最大独立集决定最优切线。读本仓库前，先理解“为什么要最大化互不相交 chord”，再看具体匹配实现。

**Hiroshi Imai, Takao Asano.** *Efficient Algorithms for Geometric Graph Search Problems*. SIAM Journal on Computing, 15(2):478–494, 1986. [论文与摘要](https://epubs.siam.org/doi/10.1137/0215033)，DOI: `10.1137/0215033`。

该文研究水平／竖直线段相交图上的搜索、匹配及独立集，并应用于正交区域最少分解。适合继续研究如何利用几何结构。当前代码显式建立稀疏 CSR，没有照搬论文的通用几何搜索数据结构；其复杂度参数也不能直接当作图像边长。

## 再理解精确匹配

**John E. Hopcroft, Richard M. Karp.** *An n^(5/2) Algorithm for Maximum Matchings in Bipartite Graphs*. SIAM Journal on Computing, 2(4):225–231, 1973. [论文与摘要](https://epubs.siam.org/doi/10.1137/0202019)，DOI: `10.1137/0202019`。

提供分层搜索、成批增广以及 `O((顶点数 + 边数)√顶点数)` 的理论基础。论文标题中的指数不是本项目图像边长的指数。

**Iain S. Duff, Kamer Kaya, Bora Uçar.** *Design, Implementation, and Analysis of Maximum Transversal Algorithms*. ACM Transactions on Mathematical Software, 38(2), Article 13, 2011. [期刊 DOI](https://doi.org/10.1145/2049673.2049677)，[作者机构预印本](https://epubs.stfc.ac.uk/manifestation/5901)。

重点阅读 §3.3.1 的 HK、§3.3.2 的 HKDW 和 §4 的匹配预热。HKDW 在每轮最短增广之后追加本轮不相交 DFS，以增加一次 BFS 的产出。当前 [hk.rs](../src/hk.rs) 采用这一调度思路；具体缓冲布局、显式栈和预热组合以代码为准。预印本年份为 2010，正式发表年份为 2011。

## 研究孔洞与更强的几何条件

**W. T. Liou, J. J. M. Tan, R. C. T. Lee.** *Minimum Rectangular Partition Problem for Simple Rectilinear Polygons*. IEEE Transactions on Computer-Aided Design of Integrated Circuits and Systems, 9(7):720–733, 1990. [机构保存的论文](https://ir.lib.nycu.edu.tw/bitstream/11536/4064/1/A1990DL35300004.pdf)。

第 II 节便于复习 chord 到最优划分的构造；后文利用无孔简单多边形的额外结构给出更快算法。其无孔前提不能从当前的像素输入契约中自动得到。

**Valeriu Soltan, Alexei Gorpinevich.** *Minimum dissection of a rectilinear polygon with arbitrary holes into rectangles*. Discrete & Computational Geometry, 9:57–79, 1993. [论文与摘要](https://link.springer.com/article/10.1007/BF02189307)，DOI: `10.1007/BF02189307`。

讨论任意孔洞、包括退化孔洞的更一般几何问题。适合在处理点接触、零宽障碍或推广输入模型时核对适用条件。不能把这类一般多边形问题与本库有限网格上的带标签像素集合不加区分地等同。

## 本项目的实现推导

最大同色区间的端点判定、内部格点对冲突边的数量约束、固定容量 scratch、有序 CSR 的计数散布和切线位图扫描，都在 [实现与优化](matching.md) 与 [容量上界](scratch.md) 中按当前代码说明。

这些是针对本项目输入模型的推导和工程选择，不应作为上述论文逐项实现的声明。继续优化时，需要分别说明：沿用了哪条已有定理、增加了什么输入前提、哪些部分由测试而非完整证明支持。
