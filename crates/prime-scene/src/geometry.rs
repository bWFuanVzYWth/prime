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

/// Both variants own immutable, shareable data. Lengths and indices are always in triangles;
/// quads are expanded by value only at an actual triangle consumer, never cached a second time.
#[derive(Clone, Debug)]
pub enum MeshGeometry {
    Triangles(Arc<[Triangle]>),
    Quads(Arc<[CompiledQuad]>),
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
        }
    }
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
    pub fn byte_len(&self) -> usize {
        match self {
            Self::Triangles(values) => std::mem::size_of_val(&**values),
            Self::Quads(values) => std::mem::size_of_val(&**values),
        }
    }
    pub fn triangle(&self, index: usize) -> Triangle {
        match self {
            Self::Triangles(values) => values[index],
            Self::Quads(values) => values[index / 2].triangle(index % 2),
        }
    }
    pub fn iter(&self) -> impl ExactSizeIterator<Item = Triangle> + DoubleEndedIterator + Clone {
        (0..self.len()).map(|i| self.triangle(i))
    }
    pub fn ptr_eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Triangles(a), Self::Triangles(b)) => Arc::ptr_eq(a, b),
            (Self::Quads(a), Self::Quads(b)) => Arc::ptr_eq(a, b),
            _ => false,
        }
    }
    pub fn view(&self, range: Range<usize>) -> TriangleView<'_> {
        assert!(range.start <= range.end && range.end <= self.len());
        match self {
            Self::Triangles(values) => TriangleView::Triangles(&values[range]),
            Self::Quads(values) => TriangleView::Quads {
                values: &values[range.start / 2..range.end.div_ceil(2)],
                first: range.start % 2,
                count: range.len(),
            },
        }
    }
}

/// Synchronous borrowed triangle range, including capacity splits through the middle of a quad.
#[derive(Clone, Copy)]
pub enum TriangleView<'a> {
    Triangles(&'a [Triangle]),
    Quads {
        values: &'a [CompiledQuad],
        first: usize,
        count: usize,
    },
}
impl<'a> From<&'a [Triangle]> for TriangleView<'a> {
    fn from(value: &'a [Triangle]) -> Self {
        Self::Triangles(value)
    }
}
impl TriangleView<'_> {
    pub fn len(self) -> usize {
        match self {
            Self::Triangles(values) => values.len(),
            Self::Quads { count, .. } => count,
        }
    }
    pub fn is_empty(self) -> bool {
        self.len() == 0
    }
    pub fn triangle(self, index: usize) -> Triangle {
        match self {
            Self::Triangles(values) => values[index],
            Self::Quads { values, first, .. } => {
                values[(first + index) / 2].triangle((first + index) % 2)
            }
        }
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
        let compact = MeshGeometry::Quads(quads.into());
        let expanded = MeshGeometry::from(expected.clone());
        assert_eq!(compact.byte_len(), 3 * 104);
        assert_eq!(expanded.byte_len(), 6 * 116);
        for geometry in [compact, expanded] {
            assert!(geometry.ptr_eq(&geometry.clone()));
            for start in 0..=expected.len() {
                for end in start..=expected.len() {
                    let view = geometry.view(start..end);
                    assert_eq!(view.len(), end - start);
                    assert_eq!(view.is_empty(), start == end);
                    for i in 0..view.len() {
                        let a = view.triangle(i);
                        let b = expected[start + i];
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
