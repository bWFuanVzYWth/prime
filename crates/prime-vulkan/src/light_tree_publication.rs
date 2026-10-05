//! Pure publication layout and recovery gate; shared by production uploads and CPU tests.
use crate::light_tree_cpu::Tree as CpuTree;
use std::ops::Range;

#[derive(Clone, Copy)]
pub(super) struct PageBinding {
    pub nodes: u64,
    pub emitters: u64,
    pub format: u32,
    pub static_page: Option<u32>,
}

pub(super) fn needs_full_snapshot(
    previous: Option<u64>,
    current: u64,
    explicit: bool,
    failed: bool,
) -> bool {
    explicit || failed || current.checked_sub(1) != previous
}

pub(super) fn page_ranges(
    cpu: &CpuTree,
    anchor: [f64; 3],
    indices: &[u32],
    mut binding: impl FnMut(u64) -> PageBinding,
) -> Result<Vec<(Range<u32>, Vec<u8>)>, String> {
    let mut ranges: Vec<(Range<u32>, Vec<u8>)> = Vec::new();
    for &index in indices {
        if ranges.last().is_none_or(|(range, _)| range.end != index) {
            ranges.push((index..index, Vec::new()));
        }
        let (range, bytes) = ranges.last_mut().unwrap();
        range.end = index + 1;
        let page = &cpu.pages[index as usize];
        if let Some(page) = page {
            let source = binding(page.key);
            bytes.extend_from_slice(&source.nodes.to_le_bytes());
            bytes.extend_from_slice(&source.emitters.to_le_bytes());
            for (origin, anchor) in page.origin.into_iter().zip(anchor) {
                let relative = (origin - anchor) as f32;
                if !relative.is_finite() {
                    return Err("Light tree page exceeds shader coordinate range".into());
                }
                bytes.extend_from_slice(&relative.to_le_bytes());
            }
            for word in [
                source.format,
                page.first,
                page.count,
                page.path,
                source.static_page.map_or(0, |page| page + 1),
            ] {
                bytes.extend_from_slice(&word.to_le_bytes());
            }
        } else {
            bytes.extend_from_slice(&[0; 48]);
        }
    }
    Ok(ranges)
}

#[cfg(test)]
pub(super) fn page_bytes(
    cpu: &CpuTree,
    anchor: [f64; 3],
    binding: impl FnMut(u64) -> PageBinding,
) -> Result<Vec<u8>, String> {
    Ok(page_ranges(
        cpu,
        anchor,
        &(0..cpu.pages.len() as u32).collect::<Vec<_>>(),
        binding,
    )?
    .into_iter()
    .flat_map(|(_, bytes)| bytes)
    .collect())
}

pub(super) fn header_bytes(addresses: [u64; 3], power: f32, pages: u32) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(32);
    for address in addresses {
        bytes.extend_from_slice(&address.to_le_bytes());
    }
    bytes.extend_from_slice(&power.to_le_bytes());
    bytes.extend_from_slice(&pages.to_le_bytes());
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::light_tree_cpu::Input;
    #[test]
    fn failed_missed_initial_and_explicit_publications_require_full_recovery() {
        assert!(needs_full_snapshot(None, 1, false, false));
        assert!(!needs_full_snapshot(Some(7), 8, false, false));
        assert!(needs_full_snapshot(Some(7), 9, false, false));
        assert!(needs_full_snapshot(Some(7), 8, true, false));
        assert!(needs_full_snapshot(Some(7), 8, false, true));
    }
    #[test]
    fn same_root_owner_replacement_uploads_only_its_current_binding() {
        let input = Input {
            key: 0,
            origin: [0.; 3],
            root: prime_scene::surface::LightNode {
                bounds: [[0.; 3], [1.; 3]],
                power: 1.,
                child: 1 << 31,
            },
            inverse_areas: &[1.],
            paths: &[0],
        };
        let inputs: Vec<_> = (0..128).map(|key| Input { key, ..input }).collect();
        let mut tree = CpuTree::default();
        tree.update(&inputs, [0.; 3]).unwrap();
        let change = tree.update_delta(&[inputs[63]], &[], [0.; 3]).unwrap();
        assert!(!change.changed && !change.world_changed);
        let current = PageBinding {
            nodes: 0xabcdef,
            emitters: 0x123456,
            format: 3,
            static_page: Some(91),
        };
        let mut calls = 0;
        let rows = page_ranges(&tree, [0.; 3], &change.pages, |key| {
            assert_eq!(key, 63);
            calls += 1;
            current
        })
        .unwrap();
        assert_eq!(calls, 1);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].0, 63..64);
        assert_eq!(rows[0].1.len(), 48);
        assert_eq!(
            u64::from_le_bytes(rows[0].1[0..8].try_into().unwrap()),
            current.nodes
        );
        assert_eq!(
            u64::from_le_bytes(rows[0].1[8..16].try_into().unwrap()),
            current.emitters
        );
        assert_eq!(
            u32::from_le_bytes(rows[0].1[44..48].try_into().unwrap()),
            92
        );
    }
}
