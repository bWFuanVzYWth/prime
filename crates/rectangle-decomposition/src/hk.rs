//! 稀疏 HKDW：反向 BFS、最短增广路径及同轮追加的不相交 DFS。
//!
//! 追加搜索沿用本轮访问标记，每个左顶点至多展开一次，保持
//! O((C + E) sqrt(C)) 的匹配搜索上界。参见 Duff、Kaya、Uçar (2011)
//! "Design, Implementation, and Analysis of Maximum Transversal Algorithms", §3.3.2。

#[cfg(feature = "profile")]
use std::time::Instant;

use crate::greedy::{
    greedy_augment_len3_sparse_csr_u16, greedy_initialize_sparse_matching_csr_u16,
};
use crate::matching::{HkScratch, SparseAdjacencyRef, UNMATCHED_U16};
#[cfg(feature = "profile")]
use crate::matching::{MatchingCounts, MatchingTimings};
use crate::{copy, get_mut, slice, u16_index};

pub fn hopcroft_karp_dw_sparse_csr_u16(
    adjacency: &SparseAdjacencyRef<'_>,
    right_size: usize,
    right_degrees: &[u8],
    scratch: &mut HkScratch,
) {
    initialize_matching(adjacency, right_size, right_degrees, scratch);
    initialize_search(scratch);
    while !scratch.unmatched_lefts.is_empty() {
        let Some(shortest_depth) = build_reverse_levels(scratch) else {
            break;
        };
        augment_phase(adjacency, scratch, shortest_depth);
    }
    collect_reachable(adjacency, scratch);
}

#[cfg(feature = "profile")]
pub fn hopcroft_karp_dw_sparse_csr_u16_profile(
    adjacency: &SparseAdjacencyRef<'_>,
    right_size: usize,
    right_degrees: &[u8],
    scratch: &mut HkScratch,
) -> (MatchingTimings, MatchingCounts) {
    let total_start = Instant::now();
    let mut timings = MatchingTimings::default();
    let mut counts = MatchingCounts::default();

    let greedy_start = Instant::now();
    initialize_matching(adjacency, right_size, right_degrees, scratch);
    timings.greedy = greedy_start.elapsed();
    counts.greedy_matches = scratch
        .pair_left
        .iter()
        .filter(|&&right| right != UNMATCHED_U16)
        .count();

    let build_start = Instant::now();
    initialize_search(scratch);
    timings.dfs_build = build_start.elapsed();
    while !scratch.unmatched_lefts.is_empty() {
        counts.phases += 1;
        let bfs_start = Instant::now();
        let shortest_depth = build_reverse_levels(scratch);
        timings.bfs += bfs_start.elapsed();
        let Some(shortest_depth) = shortest_depth else {
            break;
        };
        let dfs_start = Instant::now();
        counts.augmentations += augment_phase(adjacency, scratch, shortest_depth);
        timings.dfs_search += dfs_start.elapsed();
    }
    let cover_start = Instant::now();
    collect_reachable(adjacency, scratch);
    timings.cover = cover_start.elapsed();
    timings.total = total_start.elapsed();
    (timings, counts)
}

fn initialize_matching(
    adjacency: &SparseAdjacencyRef<'_>,
    right_size: usize,
    right_degrees: &[u8],
    scratch: &mut HkScratch,
) {
    let left_size = adjacency.offsets.len().saturating_sub(1);
    scratch.pair_left.clear();
    scratch.pair_left.resize(left_size, UNMATCHED_U16);
    scratch.pair_right.clear();
    scratch.pair_right.resize(right_size, UNMATCHED_U16);
    // 初始化同时构造转置 CSR，反向 BFS 直接复用它。
    greedy_initialize_sparse_matching_csr_u16(adjacency, right_degrees, scratch);
    greedy_augment_len3_sparse_csr_u16(adjacency, &mut scratch.pair_left, &mut scratch.pair_right);
}

fn initialize_search(scratch: &mut HkScratch) {
    scratch.right_distance.clear();
    scratch
        .right_distance
        .resize(scratch.pair_right.len(), u16::MAX);
    scratch.reachable_left.clear();
    scratch
        .reachable_left
        .resize(scratch.pair_left.len(), false);
    scratch.reachable_right.clear();
    scratch
        .reachable_right
        .resize(scratch.pair_right.len(), false);
    scratch.unmatched_lefts.clear();
    scratch.unmatched_lefts.extend(
        scratch
            .pair_left
            .iter()
            .enumerate()
            .filter_map(|(left, &right)| (right == UNMATCHED_U16).then_some(u16_index(left))),
    );
}

/// 从空闲右顶点反向遍历交替路径，记录最短层上的空闲左顶点。
/// `right_distance` 以匹配边数计数，最短增广路径有 2 * depth + 1 条边。
fn build_reverse_levels(scratch: &mut HkScratch) -> Option<u16> {
    scratch.right_distance.fill(u16::MAX);
    scratch.queue.clear();
    scratch.shortest_roots.clear();
    scratch.reachable_left.fill(false);
    for (right, &matched) in scratch.pair_right.iter().enumerate() {
        if matched == UNMATCHED_U16 {
            *get_mut(&mut scratch.right_distance, right) = 0;
            scratch.queue.push(u16_index(right));
        }
    }

    let mut head = 0usize;
    let mut shortest_depth = u16::MAX;
    while head < scratch.queue.len() {
        let right = usize::from(copy(&scratch.queue, head));
        head += 1;
        let depth = copy(&scratch.right_distance, right);
        if depth > shortest_depth {
            continue;
        }
        let start = copy(&scratch.transpose_offsets, right);
        let end = copy(&scratch.transpose_offsets, right + 1);
        for &left in slice(&scratch.transpose_edges, start..end) {
            let matched = copy(&scratch.pair_left, usize::from(left));
            if matched == UNMATCHED_U16 {
                shortest_depth = depth;
                // 每条边至多贡献一个根，重复根在 DFS 时通过访问标记跳过。
                scratch.shortest_roots.push(left);
            } else if depth < shortest_depth
                && copy(&scratch.right_distance, usize::from(matched)) == u16::MAX
            {
                *get_mut(&mut scratch.right_distance, usize::from(matched)) = depth + 1;
                scratch.queue.push(matched);
            }
        }
    }
    (shortest_depth != u16::MAX).then_some(shortest_depth)
}

fn augment_phase(
    adjacency: &SparseAdjacencyRef<'_>,
    scratch: &mut HkScratch,
    shortest_depth: u16,
) -> usize {
    let mut augmentations = 0usize;
    for index in 0..scratch.shortest_roots.len() {
        let left = usize::from(copy(&scratch.shortest_roots, index));
        if !copy(&scratch.reachable_left, left)
            && copy(&scratch.pair_left, left) == UNMATCHED_U16
            && dfs(left, adjacency, scratch, Some(shortest_depth))
        {
            augmentations += 1;
        }
    }

    // 不清除本轮标记：追加路径与最短路径、其他追加路径均顶点不相交。
    // 已访问的匹配右顶点总指向一个已访问的左顶点，无需第二套访问标记。
    for index in 0..scratch.unmatched_lefts.len() {
        let left = usize::from(copy(&scratch.unmatched_lefts, index));
        if !copy(&scratch.reachable_left, left)
            && copy(&scratch.pair_left, left) == UNMATCHED_U16
            && dfs(left, adjacency, scratch, None)
        {
            augmentations += 1;
        }
    }
    let pairs = &scratch.pair_left;
    scratch
        .unmatched_lefts
        .retain(|&left| copy(pairs, usize::from(left)) == UNMATCHED_U16);
    augmentations
}

fn dfs(
    start_left: usize,
    adjacency: &SparseAdjacencyRef<'_>,
    scratch: &mut HkScratch,
    shortest_depth: Option<u16>,
) -> bool {
    scratch.dfs_stack.clear();
    scratch
        .dfs_stack
        .push((start_left, copy(adjacency.offsets, start_left)));
    *get_mut(&mut scratch.reachable_left, start_left) = true;
    while let Some(&(left, mut edge)) = scratch.dfs_stack.last() {
        let mut descended = false;
        let end = copy(adjacency.offsets, left + 1);
        while edge < end {
            let right = usize::from(copy(adjacency.edges, edge));
            edge += 1;
            if shortest_depth.is_some_and(|depth| {
                usize::from(copy(&scratch.right_distance, right)) + scratch.dfs_stack.len()
                    != usize::from(depth) + 1
            }) {
                continue;
            }
            let matched = copy(&scratch.pair_right, right);
            if matched == UNMATCHED_U16 {
                *get_mut(&mut scratch.pair_left, left) = u16_index(right);
                *get_mut(&mut scratch.pair_right, right) = u16_index(left);
                for index in (0..scratch.dfs_stack.len() - 1).rev() {
                    let (previous_left, previous_edge) = copy(&scratch.dfs_stack, index);
                    let previous_right = copy(adjacency.edges, previous_edge - 1);
                    *get_mut(&mut scratch.pair_left, previous_left) = previous_right;
                    *get_mut(&mut scratch.pair_right, usize::from(previous_right)) =
                        u16_index(previous_left);
                }
                return true;
            }
            let next_left = usize::from(matched);
            if !copy(&scratch.reachable_left, next_left) {
                let top = scratch.dfs_stack.len() - 1;
                get_mut(&mut scratch.dfs_stack, top).1 = edge;
                *get_mut(&mut scratch.reachable_left, next_left) = true;
                scratch
                    .dfs_stack
                    .push((next_left, copy(adjacency.offsets, next_left)));
                descended = true;
                break;
            }
        }
        if !descended {
            scratch.dfs_stack.pop();
        }
    }
    false
}

/// 反向 BFS 的标记不能直接用于原有独立集公式；从空闲左顶点重新求可达性。
fn collect_reachable(adjacency: &SparseAdjacencyRef<'_>, scratch: &mut HkScratch) {
    scratch.reachable_left.fill(false);
    scratch.reachable_right.fill(false);
    scratch.queue.clear();
    for (left, &matched) in scratch.pair_left.iter().enumerate() {
        if matched == UNMATCHED_U16 {
            *get_mut(&mut scratch.reachable_left, left) = true;
            scratch.queue.push(u16_index(left));
        }
    }
    let mut head = 0usize;
    while head < scratch.queue.len() {
        let left = usize::from(copy(&scratch.queue, head));
        head += 1;
        for &right in adjacency.neighbors(left) {
            if copy(&scratch.pair_left, left) == right {
                continue;
            }
            let right = usize::from(right);
            *get_mut(&mut scratch.reachable_right, right) = true;
            let matched = copy(&scratch.pair_right, right);
            debug_assert_ne!(matched, UNMATCHED_U16, "unprocessed augmenting path");
            if matched != UNMATCHED_U16 && !copy(&scratch.reachable_left, usize::from(matched)) {
                *get_mut(&mut scratch.reachable_left, usize::from(matched)) = true;
                scratch.queue.push(matched);
            }
        }
    }
}

#[cfg(test)]
#[allow(clippy::indexing_slicing)] // 测试中的编号由各图的显式尺寸界定。
mod tests {
    use super::*;
    use crate::matching::MatchingScratch;

    struct Graph {
        offsets: Vec<usize>,
        edges: Vec<u16>,
        degrees: Vec<u8>,
    }

    impl Graph {
        fn new(rows: &[Vec<u16>], right_size: usize) -> Self {
            let mut offsets = vec![0];
            let mut edges = Vec::new();
            let mut degrees = vec![0u8; right_size];
            for row in rows {
                for &right in row {
                    edges.push(right);
                    degrees[usize::from(right)] += 1;
                }
                offsets.push(edges.len());
            }
            Self {
                offsets,
                edges,
                degrees,
            }
        }

        fn adjacency(&self) -> SparseAdjacencyRef<'_> {
            SparseAdjacencyRef {
                offsets: &self.offsets,
                edges: &self.edges,
            }
        }
    }

    // 独立穷举所有配对，不使用增广路径或贪心规则。
    fn brute_force_size(adjacency: &SparseAdjacencyRef<'_>, left: usize, used: u64) -> usize {
        if left + 1 == adjacency.offsets.len() {
            return 0;
        }
        let mut best = brute_force_size(adjacency, left + 1, used);
        for &right in adjacency.neighbors(left) {
            let bit = 1u64 << right;
            if used & bit == 0 {
                best = best.max(1 + brute_force_size(adjacency, left + 1, used | bit));
            }
        }
        best
    }

    fn assert_optimal_certificate(
        adjacency: &SparseAdjacencyRef<'_>,
        scratch: &HkScratch,
    ) -> usize {
        let mut matched = 0usize;
        for (left, &right) in scratch.pair_left.iter().enumerate() {
            if right != UNMATCHED_U16 {
                assert!(adjacency.neighbors(left).contains(&right));
                assert_eq!(scratch.pair_right[usize::from(right)], u16_index(left));
                matched += 1;
            }
            for &neighbor in adjacency.neighbors(left) {
                assert!(
                    !scratch.reachable_left[left] || scratch.reachable_right[usize::from(neighbor)]
                );
            }
        }
        for (right, &left) in scratch.pair_right.iter().enumerate() {
            if left != UNMATCHED_U16 {
                assert_eq!(scratch.pair_left[usize::from(left)], u16_index(right));
            }
        }
        let cover_size = scratch
            .reachable_left
            .iter()
            .filter(|&&reachable| !reachable)
            .count()
            + scratch
                .reachable_right
                .iter()
                .filter(|&&reachable| reachable)
                .count();
        assert_eq!(
            matched, cover_size,
            "matching and cover bounds must be equal"
        );
        matched
    }

    #[test]
    fn all_four_by_four_graphs_match_independent_oracle() {
        let mut scratch = HkScratch::default();
        for bits in 0..=u16::MAX {
            let rows: Vec<_> = (0..4)
                .map(|left| {
                    (0..4)
                        .filter(|&right| bits & (1 << (left * 4 + right)) != 0)
                        .map(u16_index)
                        .collect()
                })
                .collect();
            let graph = Graph::new(&rows, 4);
            let adjacency = graph.adjacency();
            hopcroft_karp_dw_sparse_csr_u16(&adjacency, 4, &graph.degrees, &mut scratch);
            assert_eq!(
                assert_optimal_certificate(&adjacency, &scratch),
                brute_force_size(&adjacency, 0, 0)
            );
        }
    }

    #[test]
    fn additional_paths_share_visits_and_failed_roots_reset_next_phase() {
        for bridge in [false, true] {
            let second_root = if bridge { vec![0, 2] } else { vec![2] };
            let graph = Graph::new(
                &[vec![0], vec![0, 1], second_root, vec![2, 3], vec![3, 4]],
                5,
            );
            let adjacency = graph.adjacency();
            let mut scratch = HkScratch::default();
            initialize_matching(&adjacency, 5, &graph.degrees, &mut scratch);
            scratch
                .pair_left
                .copy_from_slice(&[UNMATCHED_U16, 0, UNMATCHED_U16, 2, 3]);
            scratch
                .pair_right
                .copy_from_slice(&[1, UNMATCHED_U16, 3, 4, UNMATCHED_U16]);
            initialize_search(&mut scratch);
            assert_eq!(build_reverse_levels(&mut scratch), Some(1));
            let augmentations = augment_phase(&adjacency, &mut scratch, 1);
            if bridge {
                // L2 的最短路径与 L0 竞争；失败根本轮不再展开，下一轮须重置。
                assert_eq!(augmentations, 1);
                assert!(scratch.reachable_left[2]);
                assert_eq!(build_reverse_levels(&mut scratch), Some(2));
                assert_eq!(augment_phase(&adjacency, &mut scratch, 2), 1);
            } else {
                // 长 3 的最短路径和长 5 的追加路径在同一轮完成。
                assert_eq!(augmentations, 2);
            }
            assert_eq!(scratch.pair_left.as_slice(), &[0, 1, 2, 3, 4]);
            collect_reachable(&adjacency, &mut scratch);
            assert_eq!(assert_optimal_certificate(&adjacency, &scratch), 5);
        }
    }

    #[test]
    fn long_augmenting_path_uses_preallocated_iterative_stack() {
        let size = 1024usize;
        let rows: Vec<_> = (0..size)
            .map(|left| {
                if left == 0 {
                    vec![0]
                } else {
                    vec![u16_index(left - 1), u16_index(left)]
                }
            })
            .collect();
        let graph = Graph::new(&rows, size);
        let adjacency = graph.adjacency();
        let mut storage = MatchingScratch::default();
        let scratch = &mut storage.hk;
        let stack_capacity = scratch.dfs_stack.capacity();
        initialize_matching(&adjacency, size, &graph.degrees, scratch);
        scratch.pair_left[0] = UNMATCHED_U16;
        for left in 1..size {
            scratch.pair_left[left] = u16_index(left - 1);
        }
        for right in 0..size - 1 {
            scratch.pair_right[right] = u16_index(right + 1);
        }
        scratch.pair_right[size - 1] = UNMATCHED_U16;
        initialize_search(scratch);
        assert_eq!(build_reverse_levels(scratch), Some(u16_index(size - 1)));
        assert_eq!(augment_phase(&adjacency, scratch, u16_index(size - 1)), 1);
        collect_reachable(&adjacency, scratch);
        assert_eq!(assert_optimal_certificate(&adjacency, scratch), size);
        assert_eq!(scratch.dfs_stack.capacity(), stack_capacity);
    }

    #[test]
    fn repeated_shortest_roots_fit_preallocated_edge_capacity() {
        let rows = vec![(0..63).map(u16_index).collect(); 63];
        let graph = Graph::new(&rows, 63);
        let adjacency = graph.adjacency();
        let mut storage = MatchingScratch::default();
        let scratch = &mut storage.hk;
        let roots_capacity = scratch.shortest_roots.capacity();
        initialize_matching(&adjacency, 63, &graph.degrees, scratch);
        scratch.pair_left.fill(UNMATCHED_U16);
        scratch.pair_right.fill(UNMATCHED_U16);
        initialize_search(scratch);
        assert_eq!(build_reverse_levels(scratch), Some(0));
        assert_eq!(scratch.shortest_roots.len(), 63 * 63);
        assert_eq!(scratch.shortest_roots.capacity(), roots_capacity);
        assert_eq!(augment_phase(&adjacency, scratch, 0), 63);
        collect_reachable(&adjacency, scratch);
        assert_eq!(assert_optimal_certificate(&adjacency, scratch), 63);
    }

    #[test]
    fn asymmetric_and_empty_graphs_clear_reused_scratch() {
        let cases = [
            Graph::new(&[vec![0, 2], vec![1, 2], vec![0], vec![]], 5),
            Graph::new(&[], 3),
            Graph::new(&[vec![], vec![]], 0),
            Graph::new(&[vec![0, 1, 2]], 3),
            Graph::new(&[vec![0], vec![0], vec![0]], 1),
        ];
        let mut scratch = HkScratch::default();
        #[cfg(feature = "profile")]
        let mut profiled = HkScratch::default();
        for graph in cases.iter().cycle().take(cases.len() * 2) {
            let adjacency = graph.adjacency();
            hopcroft_karp_dw_sparse_csr_u16(
                &adjacency,
                graph.degrees.len(),
                &graph.degrees,
                &mut scratch,
            );
            let matched = assert_optimal_certificate(&adjacency, &scratch);
            assert_eq!(matched, brute_force_size(&adjacency, 0, 0));
            #[cfg(feature = "profile")]
            {
                let (_, counts) = hopcroft_karp_dw_sparse_csr_u16_profile(
                    &adjacency,
                    graph.degrees.len(),
                    &graph.degrees,
                    &mut profiled,
                );
                assert_eq!(counts.greedy_matches + counts.augmentations, matched);
                assert_eq!(profiled.pair_left.as_slice(), scratch.pair_left.as_slice());
                assert_eq!(
                    profiled.pair_right.as_slice(),
                    scratch.pair_right.as_slice()
                );
                assert_eq!(
                    profiled.reachable_left.as_slice(),
                    scratch.reachable_left.as_slice()
                );
                assert_eq!(
                    profiled.reachable_right.as_slice(),
                    scratch.reachable_right.as_slice()
                );
            }
        }
    }
}
