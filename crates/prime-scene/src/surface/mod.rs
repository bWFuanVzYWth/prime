//! Closed, version-neutral inputs to the experimental ray tracing scene compiler.
//!
//! Source interpretation and the final GPU ABI are separate from this representation. In
//! particular atlas coordinates are not stretched when adjacent unit faces become a rectangle.
//! Unknown source relationships remain separate; proximity alone is never a repair proof.
mod lights;
mod rectangles;
mod terrain;

use crate::Triangle;
use crate::geometry::CompiledQuad;
pub use lights::{Emitter, LightNode, LightRoot, LightTree, build_light_forest};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord)]
pub struct Provenance {
    /// Geometry with different movement/lifetime domains cannot be merged.
    pub domain: u64,
    pub source: u64,
}

/// Constant, linear RGB radiance. This is an explicit material declaration, not block light.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Emission {
    pub radiance: [f32; 3],
    pub two_sided: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SurfaceRule {
    /// Preserve this source even if another surface has exactly the same position.
    Preserve,
    /// A producer-proven redundant copy. Only exact geometry AND shading equality within
    /// this group may remove a copy. The compiler never infers a group from an epsilon.
    Duplicate(u64),
    /// An interface without an additional coating/emitter. Equal media have no interface.
    /// Unequal media are retained; refraction is a separate renderer capability.
    Interface { negative: u32, positive: u32 },
}

#[derive(Clone, Debug)]
pub struct SurfaceQuad {
    pub geometry: CompiledQuad,
    pub provenance: Provenance,
    pub emission: Emission,
    pub rule: SurfaceRule,
}

impl SurfaceQuad {
    /// The existing closed-quad adapter knows geometry and tint, but no optical relationship
    /// or emission declaration. It must not synthesize those missing facts.
    pub fn from_closed(geometry: CompiledQuad, provenance: Provenance) -> Self {
        Self {
            geometry,
            provenance,
            emission: Emission::default(),
            rule: SurfaceRule::Preserve,
        }
    }
}

/// UV = origin + du * frac(s) + dv * frac(t), with (s,t) in the triangle UV fields.
/// The transform is copied directly into resident records; there is no template lookup.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RepeatUv {
    pub origin: [f32; 2],
    pub du: [f32; 2],
    pub dv: [f32; 2],
}
impl RepeatUv {
    pub fn evaluate(self, uv: [f32; 2]) -> [f32; 2] {
        let [u, v] = uv.map(|x| x - x.floor());
        std::array::from_fn(|i| self.origin[i] + self.du[i] * u + self.dv[i] * v)
    }
}

#[derive(Clone, Debug)]
pub struct SurfaceTriangle {
    pub geometry: Triangle,
    pub repeat: Option<RepeatUv>,
    pub emission: Emission,
    /// Negative/positive relative to the actual triangle winding, never the viewing ray.
    pub media: [u32; 2],
    pub emitter: Option<u32>,
    /// Represented proposal power / area, shared by forward sampling and emissive-hit MIS.
    pub emitter_area_weight: f32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct CompileStats {
    pub source_quads: usize,
    pub removed_interfaces: usize,
    pub removed_duplicates: usize,
    pub grid_quads: usize,
    pub rectangles: usize,
    pub passthrough_quads: usize,
}

/// Geometry, direct shading fields and lights have one owner and revision. Consumers cannot
/// accidentally combine a new primitive numbering with an old emitter mapping.
#[derive(Debug)]
pub struct SurfaceMesh {
    pub revision: u64,
    pub triangles: Vec<SurfaceTriangle>,
    pub lights: LightTree,
    pub stats: CompileStats,
}
impl SurfaceMesh {
    pub fn byte_len(&self) -> usize {
        self.triangles.capacity() * std::mem::size_of::<SurfaceTriangle>()
            + self.lights.nodes.capacity() * std::mem::size_of::<LightNode>()
            + self.lights.emitters.capacity() * std::mem::size_of::<Emitter>()
    }
}

/// Reuse one compiler scratch per synchronous worker; construction is not per plane/job.
pub struct SurfaceCompiler {
    rectangles: Box<rectangle_decomposition::SparseOptimalScratch64>,
}
impl Default for SurfaceCompiler {
    fn default() -> Self {
        Self::new()
    }
}
impl SurfaceCompiler {
    pub fn new() -> Self {
        Self {
            rectangles: Box::new(rectangle_decomposition::SparseOptimalScratch64::new()),
        }
    }

    pub fn compile(&mut self, revision: u64, quads: &[SurfaceQuad]) -> Result<SurfaceMesh, String> {
        let (mut triangles, retained, stats) = self.compile_rectangles(quads)?;
        for index in retained {
            rectangles::append(&mut triangles, &quads[index], None);
        }
        let lights = LightTree::build(&mut triangles)?;
        Ok(SurfaceMesh {
            revision,
            triangles,
            lights,
            stats,
        })
    }
}

#[cfg(test)]
mod tests;
