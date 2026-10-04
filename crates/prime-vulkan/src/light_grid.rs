//! Device snapshots for the world-aligned light grid. Immutable cells share arena pages;
//! stable emitter ranges are copied only when their source light page changes.
use crate::{
    arena::{Arena, Lease},
    light_grid_cpu::{Alias, AliasRepairs, LightGrid as CpuGrid, PageInput},
    resources::{Buffer, Context},
    surface::LightPage,
};
use ash::vk;
use std::{collections::BTreeMap, ops::Range, sync::Arc};

pub(crate) struct LightGrid {
    cpu: CpuGrid,
    refs: Option<Buffer>,
    emitter_alias: Option<Buffer>,
    pages: Option<Buffer>,
    world: Option<Buffer>,
    cells: Option<Buffer>,
    header: Option<Buffer>,
    entries: Arena,
    allocations: BTreeMap<[i32; 3], Lease>,
    anchor: Option<[f64; 3]>,
    repairs: AliasRepairs,
}

struct Copy {
    source: Lease,
    destination: vk::Buffer,
    offset: u64,
}

impl LightGrid {
    pub fn new(context: &Context) -> Self {
        Self {
            cpu: CpuGrid::default(),
            refs: None,
            emitter_alias: None,
            pages: None,
            world: None,
            cells: None,
            header: None,
            entries: Arena::new(context, false),
            allocations: BTreeMap::new(),
            anchor: None,
            repairs: AliasRepairs::default(),
        }
    }

    pub fn begin_frame(&mut self, completed: u64, serial: u64) {
        self.entries.begin(completed, serial);
    }

    pub fn has_lights(&self) -> bool {
        self.header.is_some()
    }

    pub fn header_address(&self) -> u64 {
        self.header.as_ref().map_or(0, Buffer::address)
    }

    pub fn world_count(&self) -> u32 {
        self.cpu.world.len() as u32
    }

    pub fn first_emitter(&self, key: u64) -> u32 {
        self.cpu.page(key).unwrap().first
    }

    pub fn update(
        &mut self,
        context: &Arc<Context>,
        anchor: [f64; 3],
        sources: &BTreeMap<u64, ([f64; 3], &LightPage)>,
        uploads: &mut Arena,
    ) -> Result<(), String> {
        let mut trace = prime_diagnostics::scope("lg.update");
        trace.fail();
        trace.count("pg", sources.len() as u64);
        let input_scope = prime_diagnostics::scope("lg.input");
        let input: Vec<_> = sources
            .iter()
            .map(|(&key, &(origin, source))| PageInput {
                key,
                origin,
                lights: &source.lights,
            })
            .collect();
        drop(input_scope);
        let changes = self.cpu.update(&input)?;
        trace.count("dc", changes.cells.len() as u64);
        trace.count("ranges", changes.emitter_ranges.len() as u64);
        let previous = self.repairs.tables;
        self.repairs.add(changes.repairs);
        // Aggregate updates; log first occurrence and each power-of-two crossing,
        // not every light/cell or every frame during chunk streaming.
        if self.repairs.tables != 0
            && (previous == 0 || previous.ilog2() != self.repairs.tables.ilog2())
        {
            eprintln!(
                "[Prime PT] Warning PT-010: light alias support repaired; rendering continues; cumulative tables={} columns={} max_table_probability_shift={:.3e}",
                self.repairs.tables, self.repairs.columns, self.repairs.max_table_mass
            );
        }
        if self.cpu.world.is_empty() {
            // A world without lights retains no sampler high-water allocation. Buffer drop
            // still retires against the last submitted/recording serial in Context.
            *self = Self::new(context);
            trace.succeed();
            return Ok(());
        }
        if !changes.world
            && changes.emitter_ranges.is_empty()
            && changes.cells.is_empty()
            && self.anchor == Some(anchor)
        {
            trace.succeed();
            return Ok(());
        }

        let mut staging = prime_diagnostics::scope("lg.stage");
        staging.fail();
        let mut copies = Vec::new();
        let mut refs = prime_diagnostics::scope("lg.refs");
        refs.fail();
        let grew_refs = grow(context, &mut self.refs, self.cpu.refs.len() * 16)?;
        let grew_alias = grow(
            context,
            &mut self.emitter_alias,
            self.cpu.emitter_alias.len() * 8,
        )?;
        let ranges = copy_ranges(
            &changes.emitter_ranges,
            self.cpu.refs.len(),
            grew_refs || grew_alias,
        )?;
        refs.count("ranges", ranges.len() as u64);
        let mut refs_bytes = 0_u64;
        for range in ranges {
            if refs.enabled() {
                refs_bytes += range.len() as u64 * 24;
            }
            copies.push(stage_with(
                context,
                uploads,
                range.len() * 16,
                self.refs.as_ref().unwrap().buffer,
                range.start as u64 * 16,
                |output| {
                    for (out, index) in output.as_chunks_mut::<16>().0.iter_mut().zip(range.clone())
                    {
                        let reference = &self.cpu.refs[index];
                        write_words(
                            out,
                            &[
                                reference.page,
                                reference.emitter,
                                reference.pmf.to_bits(),
                                reference.inv_area.to_bits(),
                            ],
                        );
                    }
                    Ok(())
                },
            )?);
            copies.push(stage_with(
                context,
                uploads,
                range.len() * 8,
                self.emitter_alias.as_ref().unwrap().buffer,
                range.start as u64 * 8,
                |output| {
                    for (out, index) in output.as_chunks_mut::<8>().0.iter_mut().zip(range.clone())
                    {
                        let alias = &self.cpu.emitter_alias[index];
                        write_words(out, &[alias.cut.to_bits(), alias.alias]);
                    }
                    Ok(())
                },
            )?);
        }
        refs.count("bytes", refs_bytes);
        refs.succeed();
        drop(refs);
        let mut local = prime_diagnostics::scope("lg.local");
        local.fail();
        local.count("dc", changes.cells.len() as u64);
        let mut local_bytes = 0_u64;
        for key in &changes.cells {
            if let Some(old) = self.allocations.remove(key) {
                self.entries.retire(old);
            }
            if let Some(aliases) = self.cpu.cells.get(key) {
                let bytes = aliases.len() * 16;
                if local.enabled() {
                    local_bytes += bytes as u64;
                }
                let destination = self.entries.allocate(context, bytes as u64, 16)?;
                copies.push(stage_with(
                    context,
                    uploads,
                    bytes,
                    destination.buffer.buffer,
                    destination.offset,
                    |out| {
                        write_aliases(out, aliases);
                        Ok(())
                    },
                )?);
                self.allocations.insert(*key, destination);
            }
        }
        local.count("bytes", local_bytes);
        local.succeed();
        drop(local);
        if changes.world || self.world.is_none() {
            let mut world = prime_diagnostics::scope("lg.world_stage");
            world.fail();
            world.count("bytes", self.cpu.world.len() as u64 * 16);
            stage_table_with(
                context,
                uploads,
                self.cpu.world.len() * 16,
                &mut self.world,
                &mut copies,
                |out| {
                    write_aliases(out, &self.cpu.world);
                    Ok(())
                },
            )?;
            world.succeed();
        }
        if !changes.cells.is_empty() || self.cells.is_none() {
            let mut hash = prime_diagnostics::scope("lg.hash");
            hash.fail();
            hash.count("cells", self.allocations.len() as u64);
            let capacity = cell_capacity(self.allocations.len())?;
            stage_table_with(
                context,
                uploads,
                capacity
                    .checked_mul(32)
                    .ok_or("Light cell hash size overflow")?,
                &mut self.cells,
                &mut copies,
                |output| {
                    for out in output.iter_mut() {
                        out.write(0);
                    }
                    // Every byte was initialized above; hash probes read only this private upload lease.
                    let bytes = unsafe {
                        std::slice::from_raw_parts_mut(
                            output.as_mut_ptr().cast::<u8>(),
                            output.len(),
                        )
                    };
                    write_cells(
                        self.allocations
                            .iter()
                            .map(|(&key, lease)| (key, (lease.size / 16) as u32, lease.address())),
                        bytes,
                    )
                },
            )?;
            hash.count("bytes", capacity as u64 * 32);
            hash.succeed();
        }

        let mut pages = prime_diagnostics::scope("lg.pages");
        pages.fail();
        pages.count("pg", self.cpu.pages.len() as u64);
        stage_table_with(
            context,
            uploads,
            self.cpu.pages.len() * 48,
            &mut self.pages,
            &mut copies,
            |output| {
                for (out, page) in output
                    .as_chunks_mut::<48>()
                    .0
                    .iter_mut()
                    .zip(&self.cpu.pages)
                {
                    let mut row = [0u32; 12];
                    if let Some(page) = page {
                        let source = sources[&page.key].1;
                        let emitter = source.emitters.address();
                        let alias = self.emitter_alias.as_ref().unwrap().address()
                            + u64::from(page.first) * 8;
                        row[..4].copy_from_slice(&[
                            emitter as u32,
                            (emitter >> 32) as u32,
                            alias as u32,
                            (alias >> 32) as u32,
                        ]);
                        for (i, (position, anchor)) in
                            page.origin.into_iter().zip(anchor).enumerate()
                        {
                            row[4 + i] = ((position - anchor) as f32).to_bits();
                        }
                        row[7..11].copy_from_slice(&[
                            source.format,
                            page.first,
                            page.count,
                            page.pdf.to_bits(),
                        ]);
                    }
                    write_words(out, &row);
                }
                Ok(())
            },
        )?;
        let header = header_bytes(
            [
                self.pages.as_ref().unwrap().address(),
                self.refs.as_ref().unwrap().address(),
                self.world.as_ref().unwrap().address(),
                self.cells.as_ref().unwrap().address(),
            ],
            anchor,
            (cell_capacity(self.allocations.len())? - 1) as u32,
            u32::try_from(self.cpu.world.len()).map_err(|_| "Too many light pages")?,
        )?;
        stage_table(context, uploads, &header, &mut self.header, &mut copies)?;
        pages.succeed();
        drop(pages);
        staging.count("copies", copies.len() as u64);
        staging.succeed();
        drop(staging);
        let mut publish = prime_diagnostics::scope("lg.publish");
        publish.fail();
        publish.count("copies", copies.len() as u64);
        context.submit_named("light_grid_ranges", |command| unsafe {
            // Reused table/range addresses may still have readers in earlier submissions.
            // Serialize those reads before this batch's writes, then publish all tables together.
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
        })?;
        for copy in copies {
            uploads.retire(copy.source);
        }
        self.anchor = Some(anchor);
        publish.succeed();
        drop(publish);
        trace.succeed();
        Ok(())
    }
}

fn grow(context: &Arc<Context>, buffer: &mut Option<Buffer>, bytes: usize) -> Result<bool, String> {
    if buffer
        .as_ref()
        .is_some_and(|buffer| buffer.size >= bytes as u64)
    {
        return Ok(false);
    }
    let mut trace = prime_diagnostics::scope("lg.grow");
    trace.fail();
    trace.count("bytes", bytes as u64);
    let capacity = (bytes as u64)
        .checked_next_power_of_two()
        .ok_or("Light range size overflow")?;
    trace.count("cap", capacity);
    *buffer = Some(Buffer::new(
        context,
        capacity,
        vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS
            | vk::BufferUsageFlags::TRANSFER_DST
            | vk::BufferUsageFlags::TRANSFER_SRC,
        false,
    )?);
    trace.succeed();
    Ok(true)
}

fn copy_ranges(ranges: &[Range<u32>], len: usize, full: bool) -> Result<Vec<Range<usize>>, String> {
    if full {
        return Ok(std::iter::once(0..len).collect());
    }
    let mut ranges: Vec<_> = ranges
        .iter()
        .map(|r| r.start as usize..r.end as usize)
        .collect();
    ranges.sort_unstable_by_key(|r| r.start);
    let mut merged: Vec<Range<usize>> = Vec::with_capacity(ranges.len());
    for range in ranges {
        if range.start > range.end || range.end > len {
            return Err("Dirty light range exceeds its buffer".into());
        }
        if range.is_empty() {
            continue;
        }
        if let Some(previous) = merged.last_mut()
            && range.start <= previous.end
        {
            previous.end = previous.end.max(range.end);
        } else {
            merged.push(range);
        }
    }
    Ok(merged)
}

fn stage(
    context: &Arc<Context>,
    uploads: &mut Arena,
    bytes: &[u8],
    destination: vk::Buffer,
    offset: u64,
) -> Result<Copy, String> {
    let source = uploads.allocate(context, bytes.len() as u64, 16)?;
    source.write(bytes)?;
    Ok(Copy {
        source,
        destination,
        offset,
    })
}
fn write_words(output: &mut [std::mem::MaybeUninit<u8>], words: &[u32]) {
    assert_eq!(output.len(), words.len() * 4);
    for (out, word) in output.as_chunks_mut::<4>().0.iter_mut().zip(words) {
        for (out, byte) in out.iter_mut().zip(word.to_le_bytes()) {
            out.write(byte);
        }
    }
}
fn write_aliases(output: &mut [std::mem::MaybeUninit<u8>], aliases: &[Alias]) {
    assert_eq!(output.len(), aliases.len() * 16);
    for (out, alias) in output.as_chunks_mut::<16>().0.iter_mut().zip(aliases) {
        write_words(
            out,
            &[
                alias.light,
                alias.alias,
                alias.cut.to_bits(),
                alias.pdf.to_bits(),
            ],
        );
    }
}
fn stage_with(
    context: &Arc<Context>,
    uploads: &mut Arena,
    bytes: usize,
    destination: vk::Buffer,
    offset: u64,
    write: impl FnOnce(&mut [std::mem::MaybeUninit<u8>]) -> Result<(), String>,
) -> Result<Copy, String> {
    let mut source = uploads.allocate(context, bytes as u64, 16)?;
    source.write_with(write)?;
    Ok(Copy {
        source,
        destination,
        offset,
    })
}
fn stage_table_with(
    context: &Arc<Context>,
    uploads: &mut Arena,
    bytes: usize,
    destination: &mut Option<Buffer>,
    copies: &mut Vec<Copy>,
    write: impl FnOnce(&mut [std::mem::MaybeUninit<u8>]) -> Result<(), String>,
) -> Result<(), String> {
    grow(context, destination, bytes)?;
    copies.push(stage_with(
        context,
        uploads,
        bytes,
        destination.as_ref().unwrap().buffer,
        0,
        write,
    )?);
    Ok(())
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
fn alias_bytes(aliases: &[Alias]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(aliases.len() * 16);
    for alias in aliases {
        crate::uint(&mut bytes, alias.light);
        crate::uint(&mut bytes, alias.alias);
        crate::float(&mut bytes, alias.cut);
        crate::float(&mut bytes, alias.pdf);
    }
    bytes
}

fn cell_hash(key: [i32; 3]) -> u32 {
    let mut h = (key[0] as u32).wrapping_mul(73856093)
        ^ (key[1] as u32).wrapping_mul(19349663)
        ^ (key[2] as u32).wrapping_mul(83492791);
    h ^= h >> 16;
    h = h.wrapping_mul(0x7feb352d);
    h ^= h >> 15;
    h = h.wrapping_mul(0x846ca68b);
    h ^ (h >> 16)
}

#[cfg(test)]
fn cell_bytes(
    cells: impl ExactSizeIterator<Item = ([i32; 3], u32, u64)>,
) -> Result<Vec<u8>, String> {
    let capacity = cell_capacity(cells.len())?;
    let mut bytes = vec![
        0;
        capacity
            .checked_mul(32)
            .ok_or("Light cell hash size overflow")?
    ];
    write_cells(cells, &mut bytes)?;
    Ok(bytes)
}

fn write_cells(
    cells: impl ExactSizeIterator<Item = ([i32; 3], u32, u64)>,
    bytes: &mut [u8],
) -> Result<(), String> {
    let capacity = cell_capacity(cells.len())?;
    assert_eq!(bytes.len(), capacity * 32);
    for (key, count, address) in cells {
        let mut slot = cell_hash(key) as usize & (capacity - 1);
        while bytes[slot * 32 + 12..slot * 32 + 16] != [0; 4] {
            slot = (slot + 1) & (capacity - 1);
        }
        let entry = &mut bytes[slot * 32..(slot + 1) * 32];
        for (axis, coordinate) in key.into_iter().enumerate() {
            entry[axis * 4..axis * 4 + 4].copy_from_slice(&coordinate.to_le_bytes());
        }
        entry[12..16].copy_from_slice(&count.to_le_bytes());
        entry[16..24].copy_from_slice(&address.to_le_bytes());
    }
    Ok(())
}

fn cell_capacity(count: usize) -> Result<usize, String> {
    count
        .checked_mul(2)
        .and_then(usize::checked_next_power_of_two)
        .filter(|&n| n <= u32::MAX as usize)
        .ok_or("Light cell hash exceeds address capacity".into())
}

fn header_bytes(
    pointers: [u64; 4],
    anchor: [f64; 3],
    mask: u32,
    count: u32,
) -> Result<[u8; 64], String> {
    let mut bytes = [0; 64];
    for (i, pointer) in pointers.into_iter().enumerate() {
        bytes[i * 8..i * 8 + 8].copy_from_slice(&pointer.to_le_bytes());
    }
    let mut remainder = [0.; 3];
    // Production Frame::anchor is snapped to 256, so this remainder is exactly zero.
    // Diagnostics may use other anchors and retain their nearest f32 subcell offset.
    for (axis, position) in anchor.into_iter().enumerate() {
        let cell = (position / 16.).floor();
        if !cell.is_finite() || cell < f64::from(i32::MIN) || cell > f64::from(i32::MAX) {
            return Err("Scene anchor exceeds the light grid coordinate range".into());
        }
        bytes[32 + axis * 4..36 + axis * 4].copy_from_slice(&(cell as i32).to_le_bytes());
        remainder[axis] = (position - cell * 16.) as f32;
    }
    bytes[44..48].copy_from_slice(&mask.to_le_bytes());
    for (i, value) in remainder.into_iter().enumerate() {
        bytes[48 + i * 4..52 + i * 4].copy_from_slice(&value.to_le_bytes());
    }
    bytes[60..64].copy_from_slice(&count.to_le_bytes());
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mapped_alias_serialization_preserves_gpu_record_bits() {
        let aliases = [
            Alias {
                light: 0x12345678,
                alias: 3,
                cut: 0.5,
                pdf: 0.25,
            },
            Alias {
                light: 8,
                alias: 0,
                cut: 1.,
                pdf: f32::MIN_POSITIVE,
            },
        ];
        let mut mapped = vec![std::mem::MaybeUninit::uninit(); aliases.len() * 16];
        write_aliases(&mut mapped, &aliases);
        let actual = mapped
            .into_iter()
            .map(|b| unsafe { b.assume_init() })
            .collect::<Vec<_>>();
        assert_eq!(actual, alias_bytes(&aliases));
    }

    #[test]
    fn sparse_hash_preserves_negative_keys_and_collisions() {
        let cells = [
            [0, 0, 0],
            [-1, 2, -3],
            [1_875_000, -4, -1_875_000],
            [5, 0, 0],
        ];
        let bytes = cell_bytes(
            cells
                .into_iter()
                .enumerate()
                .map(|(i, key)| (key, i as u32 + 1, 256 + i as u64 * 16)),
        )
        .unwrap();
        assert_eq!(bytes.len(), 8 * 32);
        for (i, key) in cells.into_iter().enumerate() {
            let mut slot = cell_hash(key) as usize & 7;
            for probe in 0..8 {
                let record = &bytes[slot * 32..(slot + 1) * 32];
                let stored: [i32; 3] = std::array::from_fn(|a| {
                    i32::from_le_bytes(record[a * 4..a * 4 + 4].try_into().unwrap())
                });
                if stored == key {
                    assert_eq!(
                        u32::from_le_bytes(record[12..16].try_into().unwrap()),
                        i as u32 + 1
                    );
                    assert_eq!(
                        u64::from_le_bytes(record[16..24].try_into().unwrap()),
                        256 + i as u64 * 16
                    );
                    break;
                }
                assert_ne!(probe, 7);
                slot = (slot + 1) & 7;
            }
        }
        assert_eq!(cell_bytes(std::iter::empty()).unwrap(), vec![0; 32]);
    }

    #[test]
    fn header_retains_subcell_precision_at_world_border() {
        let anchor = [30_000_000.25, -30_000_000.25, -0.25];
        let header = header_bytes([11, 22, 33, 44], anchor, 127, 3).unwrap();
        assert_eq!(header.len(), 64);
        assert_eq!(u64::from_le_bytes(header[24..32].try_into().unwrap()), 44);
        for axis in 0..3 {
            let cell = i32::from_le_bytes(header[32 + axis * 4..36 + axis * 4].try_into().unwrap());
            let offset =
                f32::from_le_bytes(header[48 + axis * 4..52 + axis * 4].try_into().unwrap());
            assert!((0.0..16.0).contains(&offset));
            assert_eq!(f64::from(cell) * 16. + f64::from(offset), anchor[axis]);
            for relative in [-16.5_f32, 0., 16.5] {
                let gpu = ((relative + offset) / 16.).floor() as i32 + cell;
                let world = ((anchor[axis] + f64::from(relative)) / 16.).floor() as i32;
                assert_eq!(gpu, world);
            }
        }
        assert_eq!(u32::from_le_bytes(header[44..48].try_into().unwrap()), 127);
        assert_eq!(u32::from_le_bytes(header[60..64].try_into().unwrap()), 3);
    }

    #[test]
    fn dirty_range_copies_cover_growth_and_merge_overlapping_reuse() {
        assert_eq!(
            copy_ranges(&[5..8, 2..4, 3..6, 9..9], 12, false).unwrap(),
            vec![2..8]
        );
        let full = copy_ranges(std::slice::from_ref(&(5..8)), 12, true).unwrap();
        assert_eq!(full.len(), 1);
        assert_eq!(full[0], 0..12);
        assert!(copy_ranges(std::slice::from_ref(&(5..13)), 12, false).is_err());
    }

    #[test]
    #[ignore = "windowless light-table capacity reuse, shrink mask and actual GPU upload"]
    fn gpu_light_tables_reuse_capacity_and_publish_logical_hash_extent() {
        let context = Context::new().unwrap();
        let pages: Vec<_> = (0..16)
            .map(|key| LightPage {
                key: key + 1,
                emitters: Arc::new(
                    Buffer::new(
                        &context,
                        16,
                        vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
                        false,
                    )
                    .unwrap(),
                ),
                source: Arc::new(prime_scene::surface::LightTree::default()),
                method: prime_scene::settings::LightSampling::Grid,
                lights: vec![crate::light_grid_cpu::Light {
                    center: [8., 8., 8.],
                    power: 1.,
                    inv_area: 1.,
                    extent: 1.,
                }],
                tree: None,
                format: 1,
            })
            .collect();
        let mut grid = LightGrid::new(&context);
        let mut uploads = Arena::new(&context, true);
        let addresses = |grid: &LightGrid| {
            [&grid.world, &grid.cells, &grid.pages, &grid.header]
                .map(|buffer| buffer.as_ref().unwrap().address())
        };
        let mut first_addresses = None;
        let mut largest_mask = 0;
        for (serial, count) in [16, 1, 8, 16].into_iter().enumerate() {
            grid.begin_frame(u64::MAX, serial as u64 + 1);
            uploads.begin(u64::MAX, serial as u64 + 1);
            let sources = pages[..count]
                .iter()
                .enumerate()
                .map(|(index, page)| (page.key, ([index as f64 * 64., 0., 0.], page)))
                .collect();
            let anchor = [serial as f64 * 13., 0., -2.];
            grid.update(&context, anchor, &sources, &mut uploads)
                .unwrap();
            let current = addresses(&grid);
            if let Some(first) = first_addresses {
                assert_eq!(current, first, "bounded replacements reallocated tables");
            } else {
                first_addresses = Some(current);
            }
            let header = grid.header.as_ref().unwrap();
            let readback =
                Buffer::new(&context, 64, vk::BufferUsageFlags::TRANSFER_DST, true).unwrap();
            context
                .submit_named("read_light_header", |command| unsafe {
                    context.device.cmd_copy_buffer(
                        command,
                        header.buffer,
                        readback.buffer,
                        &[vk::BufferCopy::default().size(64)],
                    );
                })
                .unwrap();
            let bytes = readback.read(64).unwrap();
            let mask = u32::from_le_bytes(bytes[44..48].try_into().unwrap());
            assert_eq!(
                mask as usize + 1,
                cell_capacity(grid.allocations.len()).unwrap()
            );
            assert_eq!(
                u32::from_le_bytes(bytes[60..64].try_into().unwrap()),
                grid.cpu.world.len() as u32
            );
            if serial == 0 {
                largest_mask = mask;
            } else if count == 1 {
                assert!(mask < largest_mask);
                assert!(grid.cells.as_ref().unwrap().size > u64::from(mask + 1) * 32);
            }
        }
    }
}
