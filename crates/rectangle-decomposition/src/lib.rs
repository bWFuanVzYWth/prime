//! 将 64x64 带标签像素区域分解为最少同色矩形，输入使用 sparse quad 列表。
//!
//! 几何归约参考 Ferrari、Sankar、Sklansky (1984)，匹配使用 HKDW。
//! 算法原理、实现地图与完整出处见仓库 README 和 docs/。

#![forbid(unsafe_code)]

#[cfg(test)]
mod corners;
#[cfg(test)]
mod corpus;
mod fixed;
mod graph;
mod greedy;
mod hk;
mod matching;
mod morton;
mod sparse;
mod types;

pub use sparse::{
    QuadLeaf64, SparseLayerBuilder64, SparseOptimalScratch64, SparseQuadError, SparseQuadImage64,
};
#[cfg(feature = "profile")]
pub use sparse::{SparseDecomposeCounts, SparseDecomposeProfile, SparseDecomposeTimings};
pub use types::Rectangle;

pub(crate) fn get<T>(slice: &[T], index: usize) -> &T {
    debug_assert!(index < slice.len());
    slice.get(index).unwrap_or_else(|| std::process::abort())
}

pub(crate) fn get_mut<T>(slice: &mut [T], index: usize) -> &mut T {
    debug_assert!(index < slice.len());
    slice
        .get_mut(index)
        .unwrap_or_else(|| std::process::abort())
}

pub(crate) fn copy<T: Copy>(slice: &[T], index: usize) -> T {
    *get(slice, index)
}

pub(crate) fn slice<T>(slice: &[T], range: std::ops::Range<usize>) -> &[T] {
    debug_assert!(range.start <= range.end);
    debug_assert!(range.end <= slice.len());
    slice.get(range).unwrap_or_else(|| std::process::abort())
}

pub(crate) fn slice_mut<T>(slice: &mut [T], range: std::ops::Range<usize>) -> &mut [T] {
    debug_assert!(range.start <= range.end);
    debug_assert!(range.end <= slice.len());
    slice
        .get_mut(range)
        .unwrap_or_else(|| std::process::abort())
}

pub(crate) fn u16_index(index: usize) -> u16 {
    u16::try_from(index).unwrap_or_else(|_| std::process::abort())
}

pub(crate) fn u32_index(index: usize) -> u32 {
    u32::try_from(index).unwrap_or_else(|_| std::process::abort())
}
