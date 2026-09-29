//! Immutable static geometry. Logical triangle ranges need not be expanded resident arrays.
use crate::Triangle;
use std::ops::Range;
use std::sync::Arc;

/// Uniform source color, with the exact established 0-1-2 / 2-3-0 triangulation.
#[derive(Clone, Copy, Debug)]
pub struct CompiledQuad {
    pub positions: [[f32; 3]; 4],
    pub uvs: [[f32; 2]; 4],
    pub color: [f32; 4],
    pub texture_id: u32,
    pub flags: u32,
}
impl CompiledQuad {
    pub(crate) fn same_bits(&self, other: &Self) -> bool {
        self.texture_id == other.texture_id
            && self.flags == other.flags
            && self.positions.map(|p| p.map(f32::to_bits))
                == other.positions.map(|p| p.map(f32::to_bits))
            && self.uvs.map(|p| p.map(f32::to_bits)) == other.uvs.map(|p| p.map(f32::to_bits))
            && self.color.map(f32::to_bits) == other.color.map(f32::to_bits)
    }
    pub fn triangle(&self, half: usize) -> Triangle {
        let corners = [[0, 1, 2], [2, 3, 0]][half];
        Triangle {
            positions: corners.map(|i| self.positions[i]),
            colors: [self.color; 3],
            uvs: corners.map(|i| self.uvs[i]),
            texture_id: self.texture_id,
            flags: self.flags,
        }
    }
    pub fn triangles(&self) -> [Triangle; 2] {
        [self.triangle(0), self.triangle(1)]
    }
}

/// Owned producer fragments: moving a Vec here preserves its vertex allocation. Each block
/// carries exact bounds so unchanged fragments need neither another bounds pass nor a copy.
#[derive(Debug)]
pub struct QuadFragments {
    pub(crate) blocks: Vec<Arc<QuadBlock>>,
    ends: Vec<usize>,
}
#[derive(Debug)]
pub(crate) struct QuadBlock {
    pub(crate) values: Vec<CompiledQuad>,
    pub(crate) bounds: [[f32; 3]; 2],
}
impl QuadFragments {
    pub fn new(parts: impl IntoIterator<Item = Vec<CompiledQuad>>) -> Self {
        Self::reuse(parts, None)
    }
    pub(crate) fn reuse(
        parts: impl IntoIterator<Item = Vec<CompiledQuad>>,
        old: Option<&Self>,
    ) -> Self {
        let mut count = 0;
        let mut ends = Vec::new();
        let blocks = parts
            .into_iter()
            .enumerate()
            .map(|(i, values)| {
                count += values.len();
                ends.push(count);
                if let Some(previous) = old.and_then(|old| old.blocks.get(i))
                    && previous.values.len() == values.len()
                    && previous
                        .values
                        .iter()
                        .zip(&values)
                        .all(|(a, b)| a.same_bits(b))
                {
                    return previous.clone();
                }
                let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
                for q in &values {
                    for p in q.positions {
                        for (a, value) in p.into_iter().enumerate() {
                            bounds[0][a] = bounds[0][a].min(value);
                            bounds[1][a] = bounds[1][a].max(value);
                        }
                    }
                }
                Arc::new(QuadBlock { values, bounds })
            })
            .collect();
        Self { blocks, ends }
    }
    pub(crate) fn bounds(&self) -> [[f32; 3]; 2] {
        let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
        for block in &self.blocks {
            if block.values.is_empty() {
                continue;
            }
            for a in 0..3 {
                bounds[0][a] = bounds[0][a].min(block.bounds[0][a]);
                bounds[1][a] = bounds[1][a].max(block.bounds[1][a]);
            }
        }
        bounds
    }
    fn len(&self) -> usize {
        self.ends.last().copied().unwrap_or(0)
    }
    pub(crate) fn iter(&self) -> impl Iterator<Item = &CompiledQuad> {
        self.blocks.iter().flat_map(|b| &b.values)
    }
    fn quad(&self, index: usize) -> &CompiledQuad {
        let block = self.ends.partition_point(|&end| end <= index);
        let first = if block == 0 { 0 } else { self.ends[block - 1] };
        &self.blocks[block].values[index - first]
    }
}

/// All variants own immutable, shareable data. Lengths and indices are always in triangles;
/// quads are expanded by value only at an actual triangle consumer, never cached a second time.
#[derive(Clone, Debug)]
pub enum MeshGeometry {
    Triangles(Arc<[Triangle]>),
    Quads(Arc<[CompiledQuad]>),
    QuadFragments(Arc<QuadFragments>),
    /// Self-contained custom-compiler output, including sampling fields and light identity.
    Surfaces(Arc<crate::surface::SurfaceMesh>),
}
impl Default for MeshGeometry {
    fn default() -> Self {
        Self::Triangles(Arc::default())
    }
}
impl From<Vec<Triangle>> for MeshGeometry {
    fn from(value: Vec<Triangle>) -> Self {
        Self::Triangles(value.into())
    }
}
impl<const N: usize> From<[Triangle; N]> for MeshGeometry {
    fn from(value: [Triangle; N]) -> Self {
        Self::Triangles(value.into())
    }
}
impl From<&[Triangle]> for MeshGeometry {
    fn from(value: &[Triangle]) -> Self {
        Self::Triangles(value.into())
    }
}
impl From<Arc<[Triangle]>> for MeshGeometry {
    fn from(value: Arc<[Triangle]>) -> Self {
        Self::Triangles(value)
    }
}
impl MeshGeometry {
    pub fn len(&self) -> usize {
        match self {
            Self::Triangles(values) => values.len(),
            Self::Quads(values) => values.len() * 2,
            Self::QuadFragments(values) => values.len() * 2,
            Self::Surfaces(values) => values.triangles.len(),
        }
    }
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
    pub fn byte_len(&self) -> usize {
        match self {
            Self::Triangles(values) => std::mem::size_of_val(&**values),
            Self::Quads(values) => std::mem::size_of_val(&**values),
            Self::QuadFragments(values) => values
                .blocks
                .iter()
                .map(|b| b.values.capacity() * std::mem::size_of::<CompiledQuad>())
                .sum(),
            Self::Surfaces(values) => values.byte_len(),
        }
    }
    pub fn triangle(&self, index: usize) -> Triangle {
        match self {
            Self::Triangles(values) => values[index],
            Self::Quads(values) => values[index / 2].triangle(index % 2),
            Self::QuadFragments(values) => values.quad(index / 2).triangle(index % 2),
            Self::Surfaces(values) => values.triangles[index].geometry,
        }
    }
    pub fn iter(&self) -> impl ExactSizeIterator<Item = Triangle> + DoubleEndedIterator + Clone {
        (0..self.len()).map(|i| self.triangle(i))
    }
    pub fn ptr_eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Triangles(a), Self::Triangles(b)) => Arc::ptr_eq(a, b),
            (Self::Quads(a), Self::Quads(b)) => Arc::ptr_eq(a, b),
            (Self::QuadFragments(a), Self::QuadFragments(b)) => Arc::ptr_eq(a, b),
            (Self::Surfaces(a), Self::Surfaces(b)) => Arc::ptr_eq(a, b),
            _ => false,
        }
    }
    pub fn view(&self, range: Range<usize>) -> TriangleView<'_> {
        assert!(range.start <= range.end && range.end <= self.len());
        match self {
            Self::Triangles(values) => TriangleView::Triangles(&values[range]),
            Self::Surfaces(values) => TriangleView::Surfaces(&values.triangles[range]),
            Self::Quads(values) => TriangleView::Quads {
                values: &values[range.start / 2..range.end.div_ceil(2)],
                first: range.start % 2,
                count: range.len(),
            },
            Self::QuadFragments(values) => TriangleView::QuadFragments {
                values,
                first: range.start,
                count: range.len(),
            },
        }
    }
}

/// Synchronous borrowed triangle range, including capacity splits through the middle of a quad.
#[derive(Clone, Copy)]
pub enum TriangleView<'a> {
    Triangles(&'a [Triangle]),
    Surfaces(&'a [crate::surface::SurfaceTriangle]),
    Quads {
        values: &'a [CompiledQuad],
        first: usize,
        count: usize,
    },
    QuadFragments {
        values: &'a QuadFragments,
        first: usize,
        count: usize,
    },
}
impl<'a> From<&'a [Triangle]> for TriangleView<'a> {
    fn from(value: &'a [Triangle]) -> Self {
        Self::Triangles(value)
    }
}
impl<'a> TriangleView<'a> {
    pub fn len(self) -> usize {
        match self {
            Self::Triangles(values) => values.len(),
            Self::Surfaces(values) => values.len(),
            Self::Quads { count, .. } => count,
            Self::QuadFragments { count, .. } => count,
        }
    }
    pub fn is_empty(self) -> bool {
        self.len() == 0
    }
    pub fn triangle(self, index: usize) -> Triangle {
        match self {
            Self::Triangles(values) => values[index],
            Self::Surfaces(values) => values[index].geometry,
            Self::Quads { values, first, .. } => {
                values[(first + index) / 2].triangle((first + index) % 2)
            }
            Self::QuadFragments { values, first, .. } => values
                .quad((first + index) / 2)
                .triangle((first + index) % 2),
        }
    }
    /// Split once at fragment boundaries. Consumers can keep their inner loops contiguous,
    /// including ranges starting/ending halfway through a quad; no geometry is materialized.
    pub fn contiguous(self) -> impl Iterator<Item = TriangleView<'a>> {
        let mut consumed = 0;
        let mut block = match self {
            Self::QuadFragments { values, first, .. } => {
                values.ends.partition_point(|&end| end * 2 <= first)
            }
            _ => 0,
        };
        std::iter::from_fn(move || {
            if consumed == self.len() {
                return None;
            }
            match self {
                Self::QuadFragments {
                    values,
                    first,
                    count,
                } => {
                    let at = first + consumed;
                    while values.ends[block] * 2 <= at {
                        block += 1;
                    }
                    let start = if block == 0 {
                        0
                    } else {
                        values.ends[block - 1] * 2
                    };
                    let end = (first + count).min(values.ends[block] * 2);
                    consumed += end - at;
                    let local = at - start;
                    Some(Self::Quads {
                        values: &values.blocks[block].values[local / 2..(end - start).div_ceil(2)],
                        first: local % 2,
                        count: end - at,
                    })
                }
                _ => {
                    consumed = self.len();
                    Some(self)
                }
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_quad_subrange_preserves_corner_bits_including_odd_and_empty_splits() {
        let quads: Vec<_> = (0..3)
            .map(|i| CompiledQuad {
                positions: [
                    [-0., i as f32, 0.],
                    [1., -2., 0.],
                    [2., 3., 4.],
                    [-1., 2., 0.5],
                ],
                uvs: [[-0., 0.], [1., 0.25], [0.75, 1.], [-0.5, 1.25]],
                color: [0.25, -0., 0.5, i as f32 / 2.],
                texture_id: i,
                flags: i,
            })
            .collect();
        let expected: Vec<_> = quads
            .iter()
            .flat_map(|q| {
                [[0, 1, 2], [2, 3, 0]].map(|c| Triangle {
                    positions: c.map(|i| q.positions[i]),
                    colors: [q.color; 3],
                    uvs: c.map(|i| q.uvs[i]),
                    texture_id: q.texture_id,
                    flags: q.flags,
                })
            })
            .collect();
        let fragmented = MeshGeometry::QuadFragments(Arc::new(QuadFragments::new([
            vec![],
            vec![quads[0]],
            vec![],
            quads[1..].to_vec(),
            vec![],
        ])));
        let compact = MeshGeometry::Quads(quads.into());
        let expanded = MeshGeometry::from(expected.clone());
        assert_eq!(compact.byte_len(), 3 * 104);
        assert_eq!(expanded.byte_len(), 6 * 116);
        for geometry in [compact, expanded, fragmented] {
            assert!(geometry.ptr_eq(&geometry.clone()));
            for start in 0..=expected.len() {
                for end in start..=expected.len() {
                    let view = geometry.view(start..end);
                    assert_eq!(view.len(), end - start);
                    assert_eq!(view.is_empty(), start == end);
                    let split: Vec<_> = view
                        .contiguous()
                        .flat_map(|p| (0..p.len()).map(move |i| p.triangle(i)))
                        .collect();
                    assert_eq!(split.len(), view.len());
                    for i in 0..view.len() {
                        let b = expected[start + i];
                        for a in [view.triangle(i), split[i]] {
                            assert_eq!(
                                a.positions.map(|p| p.map(f32::to_bits)),
                                b.positions.map(|p| p.map(f32::to_bits))
                            );
                            assert_eq!(
                                a.colors.map(|p| p.map(f32::to_bits)),
                                b.colors.map(|p| p.map(f32::to_bits))
                            );
                            assert_eq!(
                                a.uvs.map(|p| p.map(f32::to_bits)),
                                b.uvs.map(|p| p.map(f32::to_bits))
                            );
                            assert_eq!((a.texture_id, a.flags), (b.texture_id, b.flags));
                        }
                    }
                }
            }
        }
    }
}
