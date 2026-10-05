//! Device publication for the power-distance page/world proposal.
//! Local trees belong to their current source LightPage. Stable numeric page identity does not
//! preserve buffer addresses: every publication rebinds the current source owners.
use crate::{
    arena::{Arena, Lease},
    light_tree_cpu::{Input, Tree as CpuTree},
    resources::{Buffer, Context},
    surface::LightPage,
};
use ash::vk;
use std::{collections::BTreeMap, sync::Arc};

pub(crate) struct LightTree {
    cpu: CpuTree,
    refs: Option<Buffer>,
    pages: Option<Buffer>,
    world: Option<Buffer>,
    header: Option<Buffer>,
    needs_full_publication: bool,
}

#[derive(Clone, Copy)]
struct PageBinding {
    nodes: u64,
    emitters: u64,
    format: u32,
    static_page: Option<u32>,
}

struct Copy {
    source: Lease,
    destination: vk::Buffer,
    offset: u64,
}

impl LightTree {
    pub(crate) fn new(_context: &Context) -> Self {
        Self {
            cpu: CpuTree::default(),
            refs: None,
            pages: None,
            world: None,
            header: None,
            needs_full_publication: true,
        }
    }

    pub(crate) fn has_lights(&self) -> bool {
        self.header.is_some()
    }

    pub(crate) fn header_address(&self) -> u64 {
        self.header.as_ref().map_or(0, Buffer::address)
    }

    pub(crate) fn world_count(&self) -> u32 {
        self.cpu.world.len().div_ceil(2) as u32
    }

    pub(crate) fn first_emitter(&self, key: u64) -> u32 {
        self.cpu.page(key).unwrap().first
    }

    pub(crate) fn history_page_count(&self) -> usize {
        self.cpu.pages.len()
    }

    pub(crate) fn history_page(&self, index: usize) -> Option<(u64, u32)> {
        self.cpu.pages[index].map(|page| (page.key, page.count * 2))
    }

    pub(crate) fn update(
        &mut self,
        context: &Arc<Context>,
        anchor: [f64; 3],
        sources: &BTreeMap<u64, ([f64; 3], &LightPage)>,
        uploads: &mut Arena,
    ) -> Result<(), String> {
        let mut trace = prime_diagnostics::scope("lt.update");
        trace.fail();
        trace.count("pg", sources.len() as u64);
        let mut inputs = Vec::with_capacity(sources.len());
        for (&key, &(origin, source)) in sources {
            let tree = &source.tree;
            inputs.push(Input {
                key,
                origin,
                root: tree.root,
                inverse_areas: &source.inverse_areas,
                paths: &tree.paths,
            });
        }
        // The CPU planner commits before staging. A failed allocation/copy/submission must not
        // make its next unchanged snapshot skip the GPU ranges that were never published.
        let retry = self.needs_full_publication;
        self.needs_full_publication = true;
        let changes = self.cpu.update(&inputs, anchor)?;
        if self.cpu.world.is_empty() {
            // Buffer drops retire against the Context recording/submitted serial.
            *self = Self::new(context);
            trace.succeed();
            return Ok(());
        }
        let world_changed = retry || changes.world_changed;
        let world_count = self.world_count();
        trace.count("changed", u64::from(changes.changed));
        trace.count("world", u64::from(world_changed));
        let mut copies = Vec::new();
        let mut staging = prime_diagnostics::scope("lt.stage");
        staging.fail();
        let staged = (|| {
            let mut refs = prime_diagnostics::scope("lt.refs");
            refs.fail();
            let full = grow(context, &mut self.refs, self.cpu.refs.len() * 16)?;
            let ranges = if full || retry {
                vec![0..self.cpu.refs.len() as u32]
            } else {
                changes.ranges
            };
            let mut size = 0_u64;
            for range in ranges {
                let mut bytes = Vec::with_capacity(range.len() * 16);
                for reference in &self.cpu.refs[range.start as usize..range.end as usize] {
                    for word in [
                        reference.page,
                        reference.emitter,
                        reference.path,
                        reference.inv_area.to_bits(),
                    ] {
                        bytes.extend_from_slice(&word.to_le_bytes());
                    }
                }
                size += bytes.len() as u64;
                copies.push(stage(
                    context,
                    uploads,
                    &bytes,
                    self.refs.as_ref().unwrap().buffer,
                    u64::from(range.start) * 16,
                )?);
            }
            refs.count("bytes", size);
            refs.succeed();
            drop(refs);
            if world_changed {
                let mut world = prime_diagnostics::scope("lt.world_stage");
                world.fail();
                let bytes = crate::light_distance_cpu::node_bytes(&self.cpu.world);
                world.count("nodes", self.cpu.world.len() as u64);
                world.count("bytes", bytes.len() as u64);
                stage_table(context, uploads, &bytes, &mut self.world, &mut copies)?;
                world.succeed();
            }
            let mut pages = prime_diagnostics::scope("lt.pages");
            pages.fail();
            let bytes = page_bytes(&self.cpu, anchor, |key| {
                let source = sources[&key].1;
                PageBinding {
                    nodes: source.tree.nodes.address(),
                    emitters: source.emitters.address(),
                    format: source.format,
                    static_page: source.static_page,
                }
            })?;
            pages.count("bytes", bytes.len() as u64);
            stage_table(context, uploads, &bytes, &mut self.pages, &mut copies)?;
            if world_changed {
                let bytes = header_bytes(
                    [
                        self.world.as_ref().unwrap().address(),
                        self.pages.as_ref().unwrap().address(),
                        self.refs.as_ref().unwrap().address(),
                    ],
                    self.cpu.power,
                    world_count,
                );
                stage_table(context, uploads, &bytes, &mut self.header, &mut copies)?;
            }
            pages.succeed();
            Ok::<_, String>(())
        })();
        if let Err(error) = staged {
            for copy in copies {
                uploads.retire(copy.source);
            }
            return Err(error);
        }
        staging.count("copies", copies.len() as u64);
        staging.succeed();
        drop(staging);
        let mut publish = prime_diagnostics::scope("lt.publish");
        publish.fail();
        publish.count("copies", copies.len() as u64);
        let submitted = context.submit_named("light_tree_ranges", |command| unsafe {
            crate::geometry::transfer_write_barrier(context, command);
            for copy in &copies {
                context.device.cmd_copy_buffer(
                    command,
                    copy.source.buffer.buffer,
                    copy.destination,
                    &[vk::BufferCopy::default()
                        .src_offset(copy.source.offset)
                        .dst_offset(copy.offset)
                        .size(copy.source.size)],
                );
            }
            crate::geometry::transfer_barrier(context, command);
        });
        for copy in copies {
            uploads.retire(copy.source);
        }
        submitted?;
        self.needs_full_publication = false;
        publish.succeed();
        trace.succeed();
        Ok(())
    }
}

fn page_bytes(
    cpu: &CpuTree,
    anchor: [f64; 3],
    mut binding: impl FnMut(u64) -> PageBinding,
) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::with_capacity(cpu.pages.len() * 48);
    for page in &cpu.pages {
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
    Ok(bytes)
}

fn header_bytes(addresses: [u64; 3], power: f32, pages: u32) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(32);
    for address in addresses {
        bytes.extend_from_slice(&address.to_le_bytes());
    }
    bytes.extend_from_slice(&power.to_le_bytes());
    bytes.extend_from_slice(&pages.to_le_bytes());
    bytes
}

fn grow(context: &Arc<Context>, buffer: &mut Option<Buffer>, bytes: usize) -> Result<bool, String> {
    if buffer
        .as_ref()
        .is_some_and(|buffer| buffer.size >= bytes as u64)
    {
        return Ok(false);
    }
    let mut trace = prime_diagnostics::scope("lt.grow");
    trace.fail();
    trace.count("bytes", bytes as u64);
    let capacity = (bytes as u64)
        .checked_next_power_of_two()
        .ok_or("Light tree table size overflow")?;
    *buffer = Some(Buffer::new(
        context,
        capacity,
        vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS | vk::BufferUsageFlags::TRANSFER_DST,
        false,
    )?);
    trace.count("cap", capacity);
    trace.succeed();
    Ok(true)
}

fn stage(
    context: &Arc<Context>,
    uploads: &mut Arena,
    bytes: &[u8],
    destination: vk::Buffer,
    offset: u64,
) -> Result<Copy, String> {
    let source = uploads.allocate(context, bytes.len() as u64, 16)?;
    if let Err(error) = source.write(bytes) {
        uploads.retire(source);
        return Err(error);
    }
    Ok(Copy {
        source,
        destination,
        offset,
    })
}

fn stage_table(
    context: &Arc<Context>,
    uploads: &mut Arena,
    bytes: &[u8],
    destination: &mut Option<Buffer>,
    copies: &mut Vec<Copy>,
) -> Result<(), String> {
    grow(context, destination, bytes.len())?;
    copies.push(stage(
        context,
        uploads,
        bytes,
        destination.as_ref().unwrap().buffer,
        0,
    )?);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input() -> Input<'static> {
        Input {
            key: 31,
            origin: [2.0, 4.0, 6.0],
            root: prime_scene::surface::LightNode {
                bounds: [[0.0; 3], [1.0; 3]],
                power: 1.0,
                child: 1 << 31,
            },
            inverse_areas: &[1.0],
            paths: &[0],
        }
    }

    fn word(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    }

    #[test]
    fn stable_page_rebinds_current_gpu_owners_and_material_mapping() {
        let mut cpu = CpuTree::default();
        let input = input();
        cpu.update(&[input], [1.0; 3]).unwrap();
        let old = PageBinding {
            nodes: 0x1020304050607080,
            emitters: 0x8070605040302010,
            format: 1,
            static_page: Some(11),
        };
        let before = page_bytes(&cpu, [1.0; 3], |_| old).unwrap();
        let changes = cpu.update(&[input], [1.0; 3]).unwrap();
        assert!(!changes.changed);
        assert!(!changes.world_changed);
        let current = PageBinding {
            nodes: 0x1122334455667788,
            emitters: 0x8877665544332211,
            format: 3,
            static_page: Some(42),
        };
        let bytes = page_bytes(&cpu, [1.0; 3], |key| {
            assert_eq!(key, input.key);
            current
        })
        .unwrap();
        assert_eq!(bytes.len(), 48);
        assert_eq!(
            u64::from_le_bytes(bytes[0..8].try_into().unwrap()),
            current.nodes
        );
        assert_eq!(
            u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            current.emitters
        );
        assert_eq!(&bytes[16..28], &before[16..28]);
        assert_eq!(
            (word(&bytes, 16), word(&bytes, 20), word(&bytes, 24)),
            (1.0f32.to_bits(), 3.0f32.to_bits(), 5.0f32.to_bits())
        );
        assert_eq!(word(&bytes, 28), current.format);
        assert_eq!(&bytes[32..44], &before[32..44]);
        assert_eq!(word(&bytes, 32), cpu.page(input.key).unwrap().first);
        assert_eq!(word(&bytes, 36), 1);
        assert_eq!(word(&bytes, 40), cpu.page(input.key).unwrap().path);
        assert_eq!(word(&bytes, 44), 43);
        let dynamic = PageBinding {
            static_page: None,
            ..current
        };
        let bytes = page_bytes(&cpu, [1.0; 3], |_| dynamic).unwrap();
        assert_eq!(word(&bytes, 44), 0);
    }

    #[test]
    fn gpu_header_has_explicit_little_endian_layout() {
        let header = header_bytes(
            [0x1020304050607080, 0x9080706050403020, 0x0102030405060708],
            1.25,
            23,
        );
        assert_eq!(header.len(), 32);
        assert_eq!(
            u64::from_le_bytes(header[..8].try_into().unwrap()),
            0x1020304050607080
        );
        assert_eq!(
            u64::from_le_bytes(header[16..24].try_into().unwrap()),
            0x0102030405060708
        );
        assert_eq!(f32::from_le_bytes(header[24..28].try_into().unwrap()), 1.25);
        assert_eq!(u32::from_le_bytes(header[28..32].try_into().unwrap()), 23);
    }
}
