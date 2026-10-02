//! Direct quad records. Source triangle inputs are paired only with exact shared attributes;
//! independent triangles repeat their last corner. No resident primitive-to-record map.
use prime_scene::workers::CpuWorkers;
use prime_scene::{
    geometry::{Quad, TriangleView},
    surface::{Emission, SurfaceFace},
};
use std::{collections::BTreeMap, mem::MaybeUninit, ops::Range};

pub(crate) const FORMATS: usize = 4;
pub(crate) const MAX_RECORDS: u32 = ((1_u64 << 32) / 432) as u32;
// Four full f32 corners; format 1 adds repeat/emission/medium fields.
pub(crate) const fn stride(format: usize) -> usize {
    [176, 240, 272, 432][format]
}

pub(crate) struct Input<'a> {
    pub triangles: TriangleView<'a>,
    pub offset: Option<[f32; 3]>,
    pub flags: Option<u32>,
}
struct Source<'a> {
    input: Input<'a>,
    // Only unstructured triangle sources need a CPU packing index. Low bit marks an exact
    // pair. This temporary index never crosses the GPU boundary or scans native quad sources.
    pairs: Vec<u32>,
}
fn closed(geometry: Quad) -> SurfaceFace {
    SurfaceFace {
        optics: None,
        geometry,
        repeat: None,
        emission: Emission::default(),
        media: [0; 2],
        emitter: None,
        emitter_area_weight: 0.0,
        detail: None,
    }
}
impl Source<'_> {
    fn len(&self) -> usize {
        match self.input.triangles {
            TriangleView::Triangles(_) => self.pairs.len(),
            TriangleView::Quads { first, count, .. }
            | TriangleView::Surfaces { first, count, .. } => {
                if count == 0 {
                    0
                } else {
                    (first + count).div_ceil(2)
                }
            }
            TriangleView::QuadFragments { .. } => unreachable!("flattened source"),
        }
    }
    fn face(&self, index: usize) -> SurfaceFace {
        let (mut face, first, count) = match self.input.triangles {
            TriangleView::Triangles(values) => {
                let code = self.pairs[index];
                let at = (code >> 1) as usize;
                let q = if code & 1 != 0 {
                    Quad::from_pair(&values[at], &values[at + 1]).expect("prepared exact pair")
                } else {
                    Quad::from_triangle(values[at])
                };
                return closed(q);
            }
            TriangleView::Quads {
                values,
                first,
                count,
            } => (closed(values[index].into()), first, count),
            TriangleView::Surfaces {
                values,
                first,
                count,
            } => (values[index].clone(), first, count),
            TriangleView::QuadFragments { .. } => unreachable!("flattened source"),
        };
        let at = index * 2;
        if at < first {
            face.keep_half(1);
        } else if at + 1 >= first + count {
            face.keep_half(0);
        }
        face
    }
}
#[derive(Clone)]
struct Span {
    source: usize,
    range: Range<usize>,
    end: usize,
}
pub(crate) struct Group {
    pub format: usize,
    pub count: u32,
    spans: Vec<Span>,
}
impl Group {
    fn push(&mut self, source: usize, range: Range<usize>) -> Result<(), String> {
        self.count = self
            .count
            .checked_add(u32::try_from(range.len()).map_err(|_| "Quad count overflow")?)
            .filter(|&n| n <= MAX_RECORDS)
            .ok_or("Quad range exceeds the direct buffer limit")?;
        self.spans.push(Span {
            source,
            range,
            end: self.count as usize,
        });
        Ok(())
    }
}
pub(crate) struct Plan<'a> {
    sources: Vec<Source<'a>>,
    pub groups: Vec<Group>,
}
impl<'a> Plan<'a> {
    pub fn new(
        inputs: impl IntoIterator<Item = Input<'a>>,
        split_formats: bool,
    ) -> Result<Self, String> {
        let mut sources = Vec::new();
        for input in inputs {
            for triangles in input.triangles.contiguous() {
                let mut pairs = Vec::new();
                if let TriangleView::Triangles(values) = triangles {
                    let mut at = 0;
                    while at < values.len() {
                        let paired = at + 1 < values.len()
                            && Quad::from_pair(&values[at], &values[at + 1]).is_some();
                        let index = u32::try_from(at)
                            .ok()
                            .filter(|&n| n <= u32::MAX / 2)
                            .ok_or("Triangle source index overflow")?;
                        pairs.push((index << 1) | u32::from(paired));
                        at += if paired { 2 } else { 1 };
                    }
                }
                sources.push(Source {
                    input: Input {
                        triangles,
                        offset: input.offset,
                        flags: input.flags,
                    },
                    pairs,
                });
            }
        }
        let mut runs = Vec::new();
        let mut all_format = 0;
        for (id, source) in sources.iter().enumerate() {
            let count = source.len();
            if count == 0 {
                continue;
            }
            // Ordinary triangle and quad sources cannot carry surface properties.
            // Pairing was already established above; only surface sources need classification.
            if matches!(
                source.input.triangles,
                TriangleView::Triangles(_) | TriangleView::Quads { .. }
            ) {
                runs.push((0, id, 0..count));
                continue;
            }
            let mut start = 0;
            let mut previous = format(&source.face(0));
            for at in 1..count {
                let next = format(&source.face(at));
                if next != previous {
                    runs.push((previous, id, start..at));
                    all_format = all_format.max(previous);
                    start = at;
                    previous = next;
                }
            }
            all_format = all_format.max(previous);
            runs.push((previous, id, start..count));
        }
        let mut groups = Vec::new();
        if split_formats {
            for format in 0..FORMATS {
                let mut group = Group {
                    format,
                    count: 0,
                    spans: Vec::new(),
                };
                for (_, id, range) in runs.iter().filter(|r| r.0 == format) {
                    group.push(*id, range.clone())?;
                }
                if group.count > 0 {
                    groups.push(group);
                }
            }
        } else if !runs.is_empty() {
            let mut group = Group {
                format: all_format,
                count: 0,
                spans: Vec::new(),
            };
            for (_, id, range) in runs {
                group.push(id, range)?;
            }
            groups.push(group);
        }
        Ok(Self { sources, groups })
    }
    pub fn bytes(&self) -> usize {
        self.groups
            .iter()
            .map(|g| g.count as usize * stride(g.format))
            .sum()
    }
    /// The exact packed record order, including half-quad capacity splits. Consumers such as
    /// opacity micromaps must use this order rather than the original source triangle order.
    pub fn faces<'b>(&'b self, group: &'b Group) -> impl Iterator<Item = SurfaceFace> + 'b {
        group.spans.iter().flat_map(move |span| {
            let source = &self.sources[span.source];
            span.range.clone().map(move |index| source.face(index))
        })
    }
    pub fn pack(
        &self,
        workers: &CpuWorkers,
        group: &Group,
        output: &mut [MaybeUninit<u8>],
        textures: &BTreeMap<u32, u32>,
    ) -> Result<(), String> {
        let size = stride(group.format);
        assert_eq!(output.len(), group.count as usize * size);
        workers.chunks_mut(output, size * 4096, |first, bytes| {
            let first = first / size;
            let mut span_index = group.spans.partition_point(|s| s.end <= first);
            for (i, out) in bytes.chunks_exact_mut(size).enumerate() {
                let at = first + i;
                while group.spans[span_index].end <= at {
                    span_index += 1;
                }
                let span = &group.spans[span_index];
                let source = &self.sources[span.source];
                let index = span.range.start + at - (span.end - span.range.len());
                let face = source.face(index);
                let record = encode(
                    &face,
                    group.format,
                    source.input.offset,
                    source.input.flags,
                    textures,
                )?;
                for (out, byte) in out.iter_mut().zip(&record[..size]) {
                    out.write(*byte);
                }
            }
            Ok(())
        })
    }
    #[cfg(test)]
    pub fn pack_bytes(
        &self,
        workers: &CpuWorkers,
        output: &mut [u8],
        textures: &BTreeMap<u32, u32>,
    ) -> Result<(), String> {
        assert_eq!(output.len(), self.bytes());
        // Every byte is initialized before success; on failure the caller discards this batch.
        let mut output = unsafe {
            std::slice::from_raw_parts_mut(
                output.as_mut_ptr().cast::<MaybeUninit<u8>>(),
                output.len(),
            )
        };
        for group in &self.groups {
            let (head, tail) = output.split_at_mut(group.count as usize * stride(group.format));
            self.pack(workers, group, head, textures)?;
            output = tail;
        }
        Ok(())
    }
}
pub(crate) fn format(face: &SurfaceFace) -> usize {
    if face.detail.is_some() {
        return 3;
    }
    if face.optics.is_some() {
        return 2;
    }
    usize::from(
        face.repeat.is_some() || face.emission != Emission::default() || face.media != [0; 2],
    )
}
pub(crate) fn encode(
    face: &SurfaceFace,
    format: usize,
    offset: Option<[f32; 3]>,
    flags: Option<u32>,
    textures: &BTreeMap<u32, u32>,
) -> Result<[u8; 432], String> {
    let q = &face.geometry;
    if flags.is_some_and(|f| f != face.flags()) {
        return Err("Mesh material flags must be uniform".into());
    }
    if face.media != [0; 2] && face.optics.is_none() {
        return Err("Optical medium identity requires explicit endpoint properties".into());
    }
    let mut bytes = [0; 432];
    let mut f = |at: usize, value: f32| {
        bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
    };
    for i in 0..4 {
        let mut p = q.positions[i];
        if let Some(o) = offset {
            for a in 0..3 {
                p[a] += o[a];
            }
        }
        if p.iter().any(|x| !x.is_finite()) {
            return Err("Non-finite vertex".into());
        }
        if q.colors[i]
            .iter()
            .any(|x| !x.is_finite() || !(0.0..=1.0).contains(x))
        {
            return Err("Invalid vertex tint".into());
        }
        if q.uvs[i].iter().any(|x| !x.is_finite()) {
            return Err("Invalid texture coordinate".into());
        }
        for (a, value) in p.into_iter().enumerate() {
            f(i * 16 + a * 4, value);
        }
        for (a, value) in q.uvs[i].into_iter().enumerate() {
            f(128 + i * 8 + a * 4, value);
        }
        for (a, value) in q.colors[i].into_iter().enumerate() {
            f(64 + i * 16 + a * 4, value);
        }
    }
    let texture = textures
        .get(&q.texture_id)
        .ok_or("Texture has not been captured")?;
    bytes[160..164].copy_from_slice(&texture.to_le_bytes());
    bytes[164..168].copy_from_slice(&q.flags.to_le_bytes());
    if format != 0 {
        let at = 176;
        let mut properties =
            (u32::from(face.emission.two_sided) * 2) | (u32::from(face.emission.textured) * 16);
        if let Some(o) = face.optics {
            properties |= 32 | (u32::from(o.thin) * 64) | (u32::from(o.transmit) * 128);
        }
        if let Some(map) = face.repeat {
            if map.axes == 0
                || map.axes > 3
                || [map.origin, map.du, map.dv]
                    .as_flattened()
                    .iter()
                    .any(|x| !x.is_finite())
            {
                return Err("Invalid repeated texture mapping".into());
            }
            properties |= 1 | (map.axes << 2);
            for i in 0..2 {
                bytes[at + i * 16..at + i * 16 + 16].copy_from_slice(
                    [map.du[i], map.dv[i], map.origin[i], 0.0]
                        .map(f32::to_le_bytes)
                        .as_flattened(),
                );
            }
        }
        bytes[at + 32..at + 48].copy_from_slice(
            [
                face.emission.radiance[0],
                face.emission.radiance[1],
                face.emission.radiance[2],
                face.emitter_area_weight,
            ]
            .map(f32::to_le_bytes)
            .as_flattened(),
        );
        bytes[at + 48..at + 64].copy_from_slice(
            [
                face.media[0],
                face.media[1],
                face.emitter.unwrap_or(u32::MAX),
                properties,
            ]
            .map(u32::to_le_bytes)
            .as_flattened(),
        );
    }
    if let Some(detail) = &face.detail {
        let layer = &detail.layer;
        if format != 3 || layer.flags > 2 {
            return Err("Invalid compound surface format".into());
        }
        let mut floats = |at: usize, values: &[f32]| -> Result<(), String> {
            if values.iter().any(|v| !v.is_finite()) {
                return Err("Nonfinite surface layer".into());
            }
            for (i, value) in values.iter().enumerate() {
                bytes[at + 4 * i..at + 4 * i + 4].copy_from_slice(&value.to_le_bytes());
            }
            Ok(())
        };
        if layer
            .colors
            .as_flattened()
            .iter()
            .any(|v| !(0. ..=1.).contains(v))
        {
            return Err("Invalid layer tint".into());
        }
        floats(240, layer.colors.as_flattened())?;
        floats(304, layer.uvs.as_flattened())?;
        if let Some(map) = layer.repeat {
            if map.axes == 0 || map.axes > 3 {
                return Err("Invalid layer repeat axes".into());
            }
            floats(
                352,
                &[
                    map.du[0],
                    map.dv[0],
                    map.origin[0],
                    0.,
                    map.du[1],
                    map.dv[1],
                    map.origin[1],
                    0.,
                ],
            )?;
        }
        floats(384, &layer.emission.radiance)?;
        let tex = textures
            .get(&layer.texture_id)
            .ok_or("Layer texture has not been captured")?;
        bytes[168..172].copy_from_slice(&(detail.mode as u32).to_le_bytes());
        bytes[336..340].copy_from_slice(&tex.to_le_bytes());
        bytes[340..344].copy_from_slice(&layer.flags.to_le_bytes());
        let props = layer.repeat.map_or(0, |m| 1 | m.axes << 2)
            | ((u32::from(layer.emission.two_sided) * 2)
                | (u32::from(layer.emission.textured) * 16));
        bytes[344..348].copy_from_slice(&props.to_le_bytes());
    }
    if let Some(o) = face.optics {
        if format < 2 {
            return Err("Optical record format mismatch".into());
        }
        for texture in o.ior_textures.into_iter().flatten() {
            if texture == 0 || texture == u32::MAX || !textures.contains_key(&texture) {
                return Err("Optical IOR texture has not been captured".into());
            }
        }
        if let Some(texture) = o.ior_textures[1] {
            let index = textures[&texture];
            // Repeat mappings consume only xyz; preserve this unused lane for the current
            // positive-side material reference, without changing record size or bindings.
            bytes[188..192].copy_from_slice(&index.to_le_bytes());
        }
        let at = if format == 2 { 240 } else { 400 };
        for (side, medium) in [o.negative, o.positive].into_iter().enumerate() {
            if !medium.ior.is_finite()
                || medium.ior < 1.
                || medium.extinction.iter().any(|v| !v.is_finite() || *v < 0.)
            {
                return Err("Invalid optical endpoint".into());
            }
            bytes[at + side * 16..at + side * 16 + 16].copy_from_slice(
                [
                    medium.ior,
                    medium.extinction[0],
                    medium.extinction[1],
                    medium.extinction[2],
                ]
                .map(f32::to_le_bytes)
                .as_flattened(),
            );
        }
    }
    Ok(bytes)
}

#[cfg(test)]
mod perf;
#[cfg(test)]
mod tests;
