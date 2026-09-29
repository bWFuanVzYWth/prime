//! Bridge from the current closed terrain inputs. Rich producers can already publish
//! MeshGeometry::Surfaces; closed inputs explicitly have no optical or emission declarations.
use super::*;
use crate::{geometry::TriangleView, translation::TerrainGeometry};

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
                    TriangleView::Surfaces(_) => rich = true,
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
            let offset = |mut t: Triangle| {
                for p in &mut t.positions {
                    for (a, x) in p.iter_mut().enumerate() {
                        *x += member.offset[a];
                    }
                }
                t
            };
            let closed_triangle = |t| SurfaceTriangle {
                geometry: offset(t),
                repeat: None,
                emission: Emission::default(),
                media: [0; 2],
                emitter: None,
                emitter_area_weight: 0.0,
            };
            for part in member.triangles.view(member.range.clone()).contiguous() {
                match part {
                    TriangleView::Triangles(triangles) => {
                        for &t in triangles {
                            other.push(closed_triangle(t));
                        }
                    }
                    TriangleView::Surfaces(triangles) => {
                        for t in triangles {
                            let mut t = t.clone();
                            t.geometry = offset(t.geometry);
                            t.emitter = None; // assigned from this final batch's numbering
                            other.push(t);
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
        let (mut triangles, retained, stats) = self.compile_rectangles(&quads)?;
        // Closed bridge rules only merge; equal counts prove the original shared input
        // can remain the final product. Rich inputs keep their explicit semantic fields.
        if !rich
            && triangles.len() + retained.len() * 2 + other.len()
                == geometry.triangle_count as usize
        {
            return Ok(None);
        }
        for index in retained {
            rectangles::append(&mut triangles, &quads[index], None);
        }
        triangles.extend(other);
        let lights = LightTree::build(&mut triangles)?;
        Ok(Some(SurfaceMesh {
            revision,
            triangles,
            lights,
            stats,
        }))
    }
}
