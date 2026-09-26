//! 贪心匹配初始化：度 1 匹配、按右部度数排序、3 跳增广。

use crate::fixed::FixedVec;
use crate::matching::{
    ChordBuffer, HkScratch, IMAGE64_AXIS_LIMIT, SparseAdjacencyRef, UNMATCHED_U16,
};
use crate::{copy, get_mut, slice, u16_index};

pub fn greedy_augment_len3_sparse_csr_u16(
    adjacency: &SparseAdjacencyRef<'_>,
    pair_left: &mut [u16],
    pair_right: &mut [u16],
) {
    'lefts: for left in 0..pair_left.len() {
        if copy(pair_left, left) != UNMATCHED_U16 {
            continue;
        }

        for &right in adjacency.neighbors(left) {
            let right = usize::from(right);
            let matched_left = copy(pair_right, right);
            if matched_left == UNMATCHED_U16 {
                *get_mut(pair_left, left) = u16_index(right);
                *get_mut(pair_right, right) = u16_index(left);
                break;
            }

            let matched_left = usize::from(matched_left);
            for &alternate_right in adjacency.neighbors(matched_left) {
                let alternate_right = usize::from(alternate_right);
                if alternate_right == right || copy(pair_right, alternate_right) != UNMATCHED_U16 {
                    continue;
                }

                *get_mut(pair_left, matched_left) = u16_index(alternate_right);
                *get_mut(pair_right, alternate_right) = u16_index(matched_left);
                *get_mut(pair_left, left) = u16_index(right);
                *get_mut(pair_right, right) = u16_index(left);
                continue 'lefts;
            }
        }
    }
}

pub fn greedy_initialize_sparse_matching_csr_u16(
    adjacency: &SparseAdjacencyRef<'_>,
    right_degrees: &[u8],
    scratch: &mut HkScratch,
) {
    let pair_left = &mut scratch.pair_left;
    let pair_right = &mut scratch.pair_right;
    for left in 0..pair_left.len() {
        let start = copy(adjacency.offsets, left);
        if copy(adjacency.offsets, left + 1) - start != 1 {
            continue;
        }

        let right = usize::from(copy(adjacency.edges, start));
        if copy(pair_right, right) == UNMATCHED_U16 {
            *get_mut(pair_left, left) = u16_index(right);
            *get_mut(pair_right, right) = u16_index(left);
        }
    }

    scratch.transpose_offsets.clear();
    scratch.transpose_offsets.resize(right_degrees.len() + 1, 0);
    for (right, &degree) in right_degrees.iter().enumerate() {
        let offset = copy(&scratch.transpose_offsets, right) + usize::from(degree);
        *get_mut(&mut scratch.transpose_offsets, right + 1) = offset;
    }
    scratch.transpose_edges.clear();
    scratch.transpose_edges.resize(adjacency.edges.len(), 0);

    scratch.write_offsets.clear();
    scratch
        .write_offsets
        .extend_from_slice(slice(&scratch.transpose_offsets, 0..right_degrees.len()));
    for left in 0..pair_left.len() {
        for &right in adjacency.neighbors(left) {
            let right = usize::from(right);
            let slot = copy(&scratch.write_offsets, right);
            *get_mut(&mut scratch.transpose_edges, slot) = u16_index(left);
            *get_mut(&mut scratch.write_offsets, right) += 1;
        }
    }

    counting_sort_right_order_u16(
        right_degrees,
        &mut scratch.right_order,
        &mut scratch.right_degree_counts,
    );

    for &right in &scratch.right_order {
        let right = usize::from(right);
        if copy(pair_right, right) != UNMATCHED_U16 {
            continue;
        }
        let start = copy(&scratch.transpose_offsets, right);
        let end = copy(&scratch.transpose_offsets, right + 1);
        for &left in slice(&scratch.transpose_edges, start..end) {
            let left = usize::from(left);
            if copy(pair_left, left) == UNMATCHED_U16 {
                *get_mut(pair_left, left) = u16_index(right);
                *get_mut(pair_right, right) = u16_index(left);
                break;
            }
        }
    }
}

fn counting_sort_right_order_u16(
    right_degrees: &[u8],
    right_order: &mut ChordBuffer<u16>,
    degree_counts: &mut FixedVec<usize, IMAGE64_AXIS_LIMIT>,
) {
    let max_degree = usize::from(right_degrees.iter().copied().max().unwrap_or(0));
    degree_counts.clear();
    degree_counts.resize(max_degree + 1, 0);
    for &degree in right_degrees {
        *get_mut(degree_counts, usize::from(degree)) += 1;
    }

    let mut offset = 0usize;
    for count in degree_counts.iter_mut() {
        let current = *count;
        *count = offset;
        offset += current;
    }

    right_order.clear();
    right_order.resize(right_degrees.len(), 0);
    for (right, &degree) in right_degrees.iter().enumerate() {
        let slot = get_mut(degree_counts, usize::from(degree));
        *get_mut(right_order, *slot) = u16_index(right);
        *slot += 1;
    }
}
