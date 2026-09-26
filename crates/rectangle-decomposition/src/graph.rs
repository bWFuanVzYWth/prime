//! 稀疏冲突图构建：将 chord 列表转为 CSR 邻接。

use crate::matching::{
    ConflictFinalizeScratch, ConflictScratch, IMAGE64_AXIS_LEN, IMAGE64_AXIS_LIMIT,
    IMAGE64_MAX_CONFLICT_EDGES, SparseAdjacencyRef, SparseEdge, UNMATCHED_U16,
};
use crate::types::ChordAccess;
use crate::{copy, get_mut, slice, u16_index, u32_index};

const IMAGE64_INTERNAL_MIN: usize = 1;
const IMAGE64_INTERNAL_MAX: usize = IMAGE64_AXIS_LIMIT - 1;

const fn grid_index(x: usize, y: usize) -> usize {
    y * IMAGE64_AXIS_LEN + x
}

/// 利用按右顶点连续分组的 `edge_buffer`，在线性时间内构建有序左 CSR。
/// 每个左邻接表仍按 (右顶点度数, 右顶点编号) 排序。
pub fn build_sparse_adjacency_in_scratch(
    left_size: usize,
    right_size: usize,
    scratch: &mut ConflictFinalizeScratch,
) -> (SparseAdjacencyRef<'_>, &[u8]) {
    let edges = slice(&scratch.edge_buffer, 0..scratch.edge_count);
    let right_degrees = scratch.right_degrees.as_slice();
    debug_assert_eq!(right_degrees.len(), right_size);
    scratch.adjacency_offsets.clear();
    scratch.adjacency_offsets.resize(left_size + 1, 0);
    let offsets = &mut scratch.adjacency_offsets;
    for edge in edges {
        *get_mut(offsets, usize::from(edge.left) + 1) += 1;
    }
    for index in 1..offsets.len() {
        *get_mut(offsets, index) += copy(offsets, index - 1);
    }

    scratch.next_offsets.clear();
    scratch
        .next_offsets
        .extend_from_slice(slice(offsets, 0..left_size));
    scratch.adjacency_edges.clear();
    scratch.adjacency_edges.resize(edges.len(), 0);
    let compact_edges = &mut scratch.adjacency_edges;
    scratch.right_layout.clear();
    scratch.right_layout.resize(2 * right_size, 0);
    let layout = &mut scratch.right_layout;
    let mut degree_offsets = [0usize; IMAGE64_AXIS_LIMIT];
    let mut edge_start = 0usize;
    for (right, &degree) in right_degrees.iter().enumerate() {
        *get_mut(layout, right) = u32_index(edge_start);
        edge_start += usize::from(degree);
        *get_mut(&mut degree_offsets, usize::from(degree)) += 1;
    }
    debug_assert_eq!(edge_start, edges.len());

    let mut order_start = 0usize;
    for offset in &mut degree_offsets {
        let count = *offset;
        *offset = order_start;
        order_start += count;
    }
    for (right, &degree) in right_degrees.iter().enumerate() {
        let slot = get_mut(&mut degree_offsets, usize::from(degree));
        *get_mut(layout, right_size + *slot) = u32_index(right);
        *slot += 1;
    }

    // 稳定计数排序给出全局右顶点顺序；散布后，各左邻接表自然有序。
    for order in 0..right_size {
        let right = copy(layout, right_size + order) as usize;
        let start = copy(layout, right) as usize;
        let end = start + usize::from(copy(right_degrees, right));
        for edge in slice(edges, start..end) {
            debug_assert_eq!(usize::from(edge.right), right);
            let left = usize::from(edge.left);
            let slot = get_mut(&mut scratch.next_offsets, left);
            *get_mut(compact_edges, *slot) = edge.right;
            *slot += 1;
        }
    }

    (
        SparseAdjacencyRef {
            offsets: scratch.adjacency_offsets.as_slice(),
            edges: scratch.adjacency_edges.as_slice(),
        },
        scratch.right_degrees.as_slice(),
    )
}

pub fn build_sparse_conflict_graph_csr<'a, H: ChordAccess, V: ChordAccess>(
    horizontal_edges: &[H],
    vertical_edges: &[V],
    scratch: &'a mut ConflictScratch,
) -> (SparseAdjacencyRef<'a>, &'a [u8]) {
    build_sparse_conflict_graph_grid_csr(horizontal_edges, vertical_edges, &mut scratch.finalize);
    build_sparse_adjacency_in_scratch(
        horizontal_edges.len(),
        vertical_edges.len(),
        &mut scratch.finalize,
    )
}

fn build_sparse_conflict_graph_grid_csr<H: ChordAccess, V: ChordAccess>(
    horizontal_edges: &[H],
    vertical_edges: &[V],
    scratch: &mut ConflictFinalizeScratch,
) {
    reset_grid_scratch(scratch);
    scratch.edge_count = 0;
    scratch.right_degrees.clear();
    scratch.right_degrees.resize(vertical_edges.len(), 0);

    for (index, horizontal) in horizontal_edges.iter().enumerate() {
        let horizontal = horizontal.chord();
        debug_assert_eq!(horizontal.y1, horizontal.y2);
        let y = usize::from(horizontal.y1);
        debug_assert!((IMAGE64_INTERNAL_MIN..=IMAGE64_INTERNAL_MAX).contains(&y));
        for x in internal_range(horizontal.x1, horizontal.x2) {
            let slot = grid_index(x, y);
            debug_assert!(
                copy(&scratch.horizontal_x_marks, x) != scratch.grid_mark
                    || copy(&scratch.horizontal_y_masks, x) & (1u64 << y) == 0
            );
            *get_mut(&mut scratch.horizontal_grid, slot) = u16_index(index);
            if copy(&scratch.horizontal_x_marks, x) != scratch.grid_mark {
                *get_mut(&mut scratch.horizontal_x_marks, x) = scratch.grid_mark;
                *get_mut(&mut scratch.horizontal_y_masks, x) = 0;
            }
            *get_mut(&mut scratch.horizontal_y_masks, x) |= 1u64 << y;
        }
    }

    // 保持右顶点顺序和连续分组，供有序 CSR 构建直接定位各段。
    for (index, vertical) in vertical_edges.iter().enumerate() {
        let vertical = vertical.chord();
        debug_assert_eq!(vertical.x1, vertical.x2);
        let x = usize::from(vertical.x1);
        debug_assert!((IMAGE64_INTERNAL_MIN..=IMAGE64_INTERNAL_MAX).contains(&x));
        let mut active = if copy(&scratch.horizontal_x_marks, x) == scratch.grid_mark {
            copy(&scratch.horizontal_y_masks, x) & internal_mask(vertical.y1, vertical.y2)
        } else {
            0
        };
        while active != 0 {
            let y =
                usize::try_from(active.trailing_zeros()).unwrap_or_else(|_| std::process::abort());
            let slot = grid_index(x, y);
            let left = copy(&scratch.horizontal_grid, slot);
            debug_assert_ne!(left, UNMATCHED_U16);
            push_conflict_edge(
                &mut scratch.edge_buffer,
                &mut scratch.edge_count,
                SparseEdge {
                    left,
                    right: u16_index(index),
                },
            );
            *get_mut(&mut scratch.right_degrees, index) += 1;
            active &= active - 1;
        }
    }
}

fn push_conflict_edge(
    edge_buffer: &mut [SparseEdge; IMAGE64_MAX_CONFLICT_EDGES],
    edge_count: &mut usize,
    edge: SparseEdge,
) {
    // 每条冲突边注入到唯一内部格点，64x64 输入最多 63*63 条。
    *get_mut(edge_buffer, *edge_count) = edge;
    *edge_count += 1;
}

fn reset_grid_scratch(scratch: &mut ConflictFinalizeScratch) {
    scratch.grid_mark = scratch.grid_mark.wrapping_add(1);
    if scratch.grid_mark != 0 {
        return;
    }

    scratch.grid_mark = 1;
    scratch.horizontal_x_marks.fill(0);
}

fn internal_range(start: u8, end: u8) -> std::ops::RangeInclusive<usize> {
    let start = usize::from(start).max(IMAGE64_INTERNAL_MIN);
    let end = usize::from(end).min(IMAGE64_INTERNAL_MAX);
    start..=end
}

fn internal_mask(start: u8, end: u8) -> u64 {
    let start = usize::from(start).max(IMAGE64_INTERNAL_MIN);
    let end = usize::from(end).min(IMAGE64_INTERNAL_MAX);
    if start > end {
        return 0;
    }

    let start_mask = u64::MAX << start;
    let end_mask = if end >= 63 {
        u64::MAX
    } else {
        (1u64 << (end + 1)) - 1
    };
    start_mask & end_mask
}

#[cfg(test)]
#[allow(clippy::indexing_slicing)] // 列表中的编号均由 0..63 构造。
mod tests {
    use super::*;
    use crate::matching::MatchingScratch;
    use crate::types::{EffectiveChord, Orientation};

    #[test]
    fn zero_initialized_grid_ignores_stale_slots_after_mark_wrap() {
        let mut scratch = ConflictScratch::default();
        let vertical = [EffectiveChord {
            orientation: Orientation::Vertical,
            x1: 3,
            y1: 1,
            x2: 3,
            y2: 5,
        }];
        for (x, y, expected_edges, wrap) in [
            (1, 2, 1, false),
            // 回绕后不再写 x=3，旧的 mark=1 和 y=2 位必须失效。
            (4, 6, 0, true),
            (1, 2, 1, false),
            (1, 6, 0, false),
            (1, 4, 1, false),
        ] {
            if wrap {
                scratch.finalize.grid_mark = u16::MAX;
            }
            let horizontal = [EffectiveChord {
                orientation: Orientation::Horizontal,
                x1: x,
                y1: y,
                x2: 6,
                y2: y,
            }];
            let (graph, degrees) =
                build_sparse_conflict_graph_csr(&horizontal, &vertical, &mut scratch);
            assert_eq!(graph.edges.len(), expected_edges);
            assert_eq!(usize::from(degrees[0]), expected_edges);
        }
    }

    #[test]
    fn linear_scatter_matches_comparison_sort_with_ties_and_empty_vertices() {
        let mut storage = MatchingScratch::default();
        let scratch = &mut storage.conflict.finalize;
        let capacity = scratch.right_layout.capacity();
        let mut columns = [
            vec![],
            (0..63).map(u16_index).collect(),
            vec![0],
            (0..63).step_by(2).map(u16_index).collect(),
            (0..63).map(u16_index).collect(),
            vec![0, 62],
            vec![],
        ];
        for _ in 0..columns.len() {
            let mut expected = vec![Vec::<u16>::new(); 64];
            scratch.edge_count = 0;
            scratch.right_degrees.clear();
            for (right, neighbors) in columns.iter().enumerate() {
                let mut degree = 0u8;
                for &left in neighbors {
                    scratch.edge_buffer[scratch.edge_count] = SparseEdge {
                        left,
                        right: u16_index(right),
                    };
                    scratch.edge_count += 1;
                    expected[usize::from(left)].push(u16_index(right));
                    degree += 1;
                }
                scratch.right_degrees.push(degree);
            }
            for row in &mut expected {
                row.sort_unstable_by_key(|&right| {
                    (scratch.right_degrees[usize::from(right)], right)
                });
            }
            let (actual, _) = build_sparse_adjacency_in_scratch(64, columns.len(), scratch);
            for (left, row) in expected.iter().enumerate() {
                assert_eq!(actual.neighbors(left), row);
            }
            columns.rotate_left(1);
        }
        assert_eq!(scratch.right_layout.capacity(), capacity);
        scratch.edge_count = 0;
        scratch.right_degrees.clear();
        let (empty, _) = build_sparse_adjacency_in_scratch(0, 0, scratch);
        assert_eq!(empty.offsets, &[0]);
        assert_eq!(empty.edges, []);
    }
}
