//! Bridge from the current closed terrain inputs. Rich producers can already publish
//! MeshGeometry::Surfaces; closed inputs explicitly have no optical or emission declarations.
use super::*;
use crate::{
    geometry::{Quad, TriangleView},
    translation::TerrainGeometry,
};

impl SurfaceCompiler {
    pub fn compile_terrain(
        &mut self,
        revision: u64,
        geometry: &TerrainGeometry,
    ) -> Result<Option<SurfaceMesh>, String> {
        // A closed input that cannot change needs no expanded CPU/GPU representation.
        // This scan borrows the producer pages and exits on the first candidate. It also
        // avoids manufacturing rich records for ordinary non-grid model geometry.
        let mut candidate = false;
        let mut rich = false;
        for member in &geometry.members {
            for part in member.triangles.view(member.range.clone()).contiguous() {
                match part {
                    TriangleView::Surfaces { .. } => rich = true,
                    TriangleView::Quads {
                        values,
                        first,
                        count,
                    } if !candidate && count > 1 => {
                        let first_quad = first.div_ceil(2);
                        let end_quad = (first + count) / 2;
                        for quad in &values[first_quad..end_quad] {
                            let mut q = *quad;
                            for p in &mut q.positions {
                                for (a, x) in p.iter_mut().enumerate() {
                                    *x += member.offset[a];
                                }
                            }
                            if Self::grid_candidate(&q) {
                                candidate = true;
                                break;
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
        if !candidate && !rich {
            return Ok(None);
        }
        let mut quads = Vec::new();
        let mut other = Vec::new();
        let mut source = 0;
        for member in &geometry.members {
            let offset = |mut t: crate::Triangle| {
                for p in &mut t.positions {
                    for (a, x) in p.iter_mut().enumerate() {
                        *x += member.offset[a];
                    }
                }
                t
            };
            let closed_triangle = |t| SurfaceFace {
                optics: None,
                geometry: Quad::from_triangle(offset(t)),
                repeat: None,
                emission: Emission::default(),
                media: [0; 2],
                emitter: None,
                emitter_area_weight: 0.0,
                detail: None,
            };
            for part in member.triangles.view(member.range.clone()).contiguous() {
                match part {
                    TriangleView::Triangles(triangles) => {
                        let mut i = 0;
                        while i < triangles.len() {
                            let mut face = closed_triangle(triangles[i]);
                            if let Some(paired) = triangles
                                .get(i + 1)
                                .and_then(|b| Quad::from_pair(&triangles[i], b))
                            {
                                face.geometry = paired;
                                for p in &mut face.geometry.positions {
                                    for (a, x) in p.iter_mut().enumerate() {
                                        *x += member.offset[a];
                                    }
                                }
                                i += 2;
                            } else {
                                i += 1;
                            }
                            other.push(face);
                        }
                    }
                    TriangleView::Surfaces {
                        values,
                        first,
                        count,
                    } => {
                        let end = first + count;
                        let mut i = first;
                        while i < end {
                            let mut face = values[i / 2].clone();
                            if i % 2 != 0 || i + 1 == end {
                                face.keep_half(i % 2);
                                i += 1;
                            } else {
                                i += 2;
                            }
                            for p in &mut face.geometry.positions {
                                for (a, x) in p.iter_mut().enumerate() {
                                    *x += member.offset[a];
                                }
                            }
                            face.emitter = None;
                            other.push(face);
                        }
                    }
                    TriangleView::Quads {
                        values,
                        first,
                        count,
                    } => {
                        let end = first + count;
                        let mut i = first;
                        while i < end {
                            if i % 2 != 0 || i + 1 == end {
                                other.push(closed_triangle(values[i / 2].triangle(i % 2)));
                                i += 1;
                            } else {
                                let mut q = values[i / 2];
                                for p in &mut q.positions {
                                    for (a, x) in p.iter_mut().enumerate() {
                                        *x += member.offset[a];
                                    }
                                }
                                quads.push(SurfaceQuad::from_closed(
                                    q,
                                    Provenance { domain: 0, source },
                                ));
                                source += 1;
                                i += 2;
                            }
                        }
                    }
                    TriangleView::QuadFragments { .. } => unreachable!("contiguous view"),
                }
            }
        }
        let (mut faces, retained, stats) = self.compile_rectangles(&quads)?;
        // Closed bridge rules only merge; equal counts prove the original shared input
        // can remain the final product. Rich inputs keep their explicit semantic fields.
        if !rich && faces.len() + retained.len() == quads.len() {
            return Ok(None);
        }
        for index in retained {
            rectangles::append(&mut faces, &quads[index], None);
        }
        faces.extend(self.merge_resolved(other)?);
        let lights = Arc::new(LightTree::build(&mut faces)?);
        Ok(Some(SurfaceMesh {
            revision,
            quads: faces,
            lights,
            stats,
        }))
    }
}
