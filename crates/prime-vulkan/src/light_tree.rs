//! Device publication for the power-distance or directional sphere page/world proposal.
//! Immutable local trees belong to their source LightPage; only changed ranges and world metadata
//! are uploaded here. Sampler changes replace metadata at an explicit completion boundary.
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
        }
    }

    pub(crate) fn begin_frame(&mut self, _completed: u64, _serial: u64) {
        // Local/table Buffer drops use Context's completion retirement; the shared upload Arena
        // is owned and advanced by Geometry. There are no tree-private reusable leases.
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
        method: prime_scene::settings::LightSampling,
    ) -> Result<(), String> {
        let mut trace = prime_diagnostics::scope("lt.update");
        trace.fail();
        trace.count("pg", sources.len() as u64);
        let mut inputs = Vec::with_capacity(sources.len());
        for (&key, &(origin, source)) in sources {
            let tree = source
                .tree
                .as_ref()
                .ok_or("Tree sampler received a grid-only light page")?;
            inputs.push(Input {
                key,
                origin,
                root: tree.root,
                lights: &source.lights,
                paths: &tree.paths,
            });
        }
        let changes = self.cpu.update(&inputs, anchor)?;
        if self.cpu.world.is_empty() {
            // Buffer drops retire against the Context recording/submitted serial.
            *self = Self::new(context);
            trace.succeed();
            return Ok(());
        }
        if !changes.changed {
            trace.succeed();
            return Ok(());
        }
        let sphere = if method == prime_scene::settings::LightSampling::TreeSphere {
            let roots = self
                .cpu
                .pages
                .iter()
                .enumerate()
                .filter_map(|(slot, page)| {
                    page.as_ref().map(|page| {
                        let tree = sources[&page.key].1.tree.as_ref().unwrap();
                        let mut root = tree
                            .sphere_root
                            .ok_or("Sphere sampler received a power tree page")?;
                        // Match GPU's represented page origin + local position, then widen the
                        // endpoints by one ULP so the sphere encloses the actual translated corners.
                        let relative: [f32; 3] =
                            std::array::from_fn(|a| (page.origin[a] - anchor[a]) as f32);
                        root.bounds = [
                            std::array::from_fn(|a| (relative[a] + root.bounds[0][a]).next_down()),
                            std::array::from_fn(|a| (relative[a] + root.bounds[1][a]).next_up()),
                        ];
                        Ok((slot as u32, root))
                    })
                })
                .collect::<Result<Vec<_>, String>>()?;
            Some(crate::light_sphere_cpu::Tree::world(
                &roots,
                self.cpu.pages.len(),
            )?)
        } else {
            None
        };
        let mut copies = Vec::new();
        let mut staging = prime_diagnostics::scope("lt.stage");
        staging.fail();
        let staged = (|| {
            let mut refs = prime_diagnostics::scope("lt.refs");
            refs.fail();
            let full = grow(context, &mut self.refs, self.cpu.refs.len() * 16)?;
            let ranges = if full {
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
            let mut world = prime_diagnostics::scope("lt.world_stage");
            world.fail();
            let bytes = sphere.as_ref().map_or_else(
                || crate::light_distance_cpu::node_bytes(&self.cpu.world),
                |tree| crate::light_sphere_cpu::node_bytes(&tree.nodes),
            );
            world.count("nodes", self.cpu.world.len() as u64);
            world.count("bytes", bytes.len() as u64);
            stage_table(context, uploads, &bytes, &mut self.world, &mut copies)?;
            world.succeed();
            drop(world);
            let mut pages = prime_diagnostics::scope("lt.pages");
            pages.fail();
            let bytes = page_bytes(
                &self.cpu,
                anchor,
                sources,
                sphere.as_ref().map(|tree| tree.paths.as_slice()),
            )?;
            pages.count("bytes", bytes.len() as u64);
            stage_table(context, uploads, &bytes, &mut self.pages, &mut copies)?;
            let bytes = header_bytes(
                [
                    self.world.as_ref().unwrap().address(),
                    self.pages.as_ref().unwrap().address(),
                    self.refs.as_ref().unwrap().address(),
                ],
                self.cpu.power,
                self.world_count(),
            );
            stage_table(context, uploads, &bytes, &mut self.header, &mut copies)?;
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
        publish.succeed();
        trace.succeed();
        Ok(())
    }
}

fn page_bytes(
    cpu: &CpuTree,
    anchor: [f64; 3],
    sources: &BTreeMap<u64, ([f64; 3], &LightPage)>,
    paths: Option<&[u32]>,
) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::with_capacity(cpu.pages.len() * 48);
    for (slot, page) in cpu.pages.iter().enumerate() {
        if let Some(page) = page {
            let source = sources[&page.key].1;
            let tree = source.tree.as_ref().ok_or("Missing local light tree")?;
            bytes.extend_from_slice(&tree.nodes.address().to_le_bytes());
            bytes.extend_from_slice(&source.emitters.address().to_le_bytes());
            for (origin, anchor) in page.origin.into_iter().zip(anchor) {
                let relative = (origin - anchor) as f32;
                if !relative.is_finite() {
                    return Err("Light tree page exceeds shader coordinate range".into());
                }
                bytes.extend_from_slice(&relative.to_le_bytes());
            }
            let selection = paths.map_or(page.path, |paths| paths[slot]);
            for word in [source.format, page.first, page.count, selection, 0] {
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
