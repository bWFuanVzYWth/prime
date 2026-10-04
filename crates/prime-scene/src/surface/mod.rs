//! Closed, version-neutral inputs to the ray tracing scene compiler.
//!
//! Source interpretation and the final GPU ABI are separate from this representation. In
//! particular atlas coordinates are not stretched when adjacent unit faces become a rectangle.
//! Unknown source relationships remain separate; proximity alone is never a repair proof.
mod clip;
mod lights;
pub use clip::{Rectangle, affine_rectangle, clip_rectangle, subtract_box};
pub use clip::{intersect_surfaces, partition_surface, subtract_surface};
mod rectangles;
mod resolved;
mod terrain;

use crate::geometry::{CompiledQuad, Quad};
pub use lights::{Emitter, LightNode, LightRoot, LightTree, build_light_forest};
use std::sync::Arc;

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
    /// Multiply the declared radiance by decoded texture × tint at the sampled point.
    pub textured: bool,
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

/// UV = origin + du * s + dv * t; only axes selected by `axes` repeat.
/// The transform is copied directly into resident records; there is no template lookup.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RepeatUv {
    pub origin: [f32; 2],
    pub du: [f32; 2],
    pub dv: [f32; 2],
    /// Bit 0 repeats s, bit 1 repeats t. A cropped edge must not wrap at its endpoint.
    pub axes: u32,
}
impl RepeatUv {
    pub fn evaluate(self, uv: [f32; 2]) -> [f32; 2] {
        let [u, v] = std::array::from_fn(|i| {
            if self.axes & (1 << i) != 0 {
                uv[i] - uv[i].floor()
            } else {
                uv[i]
            }
        });
        std::array::from_fn(|i| self.origin[i] + self.du[i] * u + self.dv[i] * v)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct SurfaceFace {
    pub geometry: Quad,
    pub repeat: Option<RepeatUv>,
    pub emission: Emission,
    /// Negative/positive relative to the actual triangle winding, never the viewing ray.
    pub media: [u32; 2],
    pub emitter: Option<u32>,
    /// Represented proposal power / area, shared by forward sampling and emissive-hit MIS.
    pub emitter_area_weight: f32,
    /// Only resolved multi-material sheets allocate this uncommon binding.
    pub detail: Option<Arc<SurfaceDetail>>,
    pub optics: Option<Optics>,
}

/// Homogeneous linear Rec.2020 absorption per metre and the absolute refractive index.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Medium {
    pub ior: f32,
    pub extinction: [f32; 3],
}
impl Default for Medium {
    fn default() -> Self {
        Self {
            ior: 1.,
            extinction: [0.; 3],
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Optics {
    pub negative: Medium,
    pub positive: Medium,
    /// Current-frame LabPBR G owners. The positive side uses the source midpoint;
    /// retaining this identity lets animation update IOR without recompiling geometry.
    pub ior_textures: [Option<u32>; 2],
    /// False is an opaque coating with known incident media (e.g. submerged terrain).
    pub transmit: bool,
    pub thin: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum LayerMode {
    Bilateral = 1,
    OverlayFront = 2,
    OverlayBoth = 3,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SurfaceLayer {
    pub colors: [[f32; 4]; 4],
    pub uvs: [[f32; 2]; 4],
    pub texture_id: u32,
    pub flags: u32,
    pub repeat: Option<RepeatUv>,
    pub emission: Emission,
}

#[derive(Clone, Debug, PartialEq)]
pub struct SurfaceDetail {
    pub mode: LayerMode,
    pub layer: SurfaceLayer,
}

impl SurfaceFace {
    pub fn keep_half(&mut self, half: usize) {
        self.geometry = Quad::from_triangle(self.geometry.triangle(half));
        if let Some(detail) = &mut self.detail {
            let layer = &mut Arc::make_mut(detail).layer;
            let corners = [[0, 1, 2, 2], [2, 3, 0, 0]][half];
            layer.uvs = corners.map(|i| layer.uvs[i]);
            layer.colors = corners.map(|i| layer.colors[i]);
        }
    }
    pub fn from_quad(geometry: CompiledQuad) -> Self {
        Self {
            geometry: geometry.into(),
            repeat: None,
            emission: Emission::default(),
            media: [0; 2],
            emitter: None,
            emitter_area_weight: 0.,
            detail: None,
            optics: None,
        }
    }
    /// Coverage of the whole physical sheet, independent of which material shades the hit.
    pub fn flags(&self) -> u32 {
        if self.optics.is_some_and(|o| o.transmit) {
            return 2;
        }
        match &self.detail {
            Some(d) if d.mode == LayerMode::Bilateral => self.geometry.flags.max(d.layer.flags),
            _ => self.geometry.flags,
        }
    }
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
    pub quads: Vec<SurfaceFace>,
    pub lights: Arc<LightTree>,
    pub stats: CompileStats,
}
impl SurfaceMesh {
    pub(crate) fn ior_textures(&self) -> impl Iterator<Item = u32> + '_ {
        self.quads.iter().flat_map(|face| {
            face.optics
                .into_iter()
                .flat_map(|optics| optics.ior_textures.into_iter().flatten())
        })
    }

    pub fn from_resolved(revision: u64, mut quads: Vec<SurfaceFace>) -> Result<Self, String> {
        let lights = Arc::new(LightTree::build(&mut quads)?);
        Ok(Self {
            revision,
            quads,
            lights,
            stats: CompileStats::default(),
        })
    }
    pub fn byte_len(&self) -> usize {
        self.quads.capacity() * std::mem::size_of::<SurfaceFace>()
            + self.lights.nodes.capacity() * std::mem::size_of::<LightNode>()
            + self.lights.emitters.capacity() * std::mem::size_of::<Emitter>()
    }
}

/// Reuse one compiler scratch per synchronous worker; construction is not per plane/job.
pub struct SurfaceCompiler {
    rectangles: Box<rectangle_decomposition::SparseOptimalScratch64>,
    cutout_squares: bool,
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
            cutout_squares: false,
        }
    }

    /// Bound repeated cutout faces to the finite 1/2/4 square resource templates.
    /// Opaque and transmissive surfaces retain their ordinary rectangle merging.
    pub fn set_cutout_squares(&mut self, enabled: bool) {
        self.cutout_squares = enabled;
    }

    pub fn compile(&mut self, revision: u64, quads: &[SurfaceQuad]) -> Result<SurfaceMesh, String> {
        let (mut faces, retained, stats) = self.compile_rectangles(quads)?;
        for index in retained {
            rectangles::append(&mut faces, &quads[index], None);
        }
        let lights = Arc::new(LightTree::build(&mut faces)?);
        Ok(SurfaceMesh {
            revision,
            quads: faces,
            lights,
            stats,
        })
    }
}

#[cfg(test)]
mod tests;
