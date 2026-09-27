//! Renderer values contain no Minecraft objects, borrowed FFM memory or Vulkan handles.
use crate::instances::InstanceContext;
use crate::spatial::{Cell, TerrainAvailability};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::Arc,
};

#[derive(Clone, Copy, Debug)]
pub struct Triangle {
    pub positions: [[f32; 3]; 3],
    /// Source encoded RGBA tint. Decode RGB only after texture × tint at the hit.
    pub colors: [[f32; 4]; 3],
    pub uvs: [[f32; 2]; 3],
    pub texture_id: u32,
    pub flags: u32,
}

#[derive(Clone, Debug)]
pub struct Texture {
    pub width: u32,
    pub height: u32,
    pub pixels: Arc<[u8]>,
}

pub type MeshKey = (u64, u32);

#[derive(Clone)]
pub struct SceneMesh {
    pub revision: u64,
    pub flags: u32,
    /// Absolute source origin. Grid ownership is resolved before camera-relative narrowing.
    pub origin: [f64; 3],
    pub triangles: Arc<[Triangle]>,
}

/// One complete dynamic frame; its identity is independent of static scene changes.
#[derive(Clone, Default)]
pub struct DynamicScene {
    pub revision: u64,
    /// Absolute source origin; camera rebasing does not change spatial membership.
    pub origin: [f64; 3],
    pub triangles: Arc<[Triangle]>,
}

/// Immutable local geometry, shared by any number of persistent instances.
#[derive(Clone, Debug)]
pub struct Prototype {
    pub revision: u64,
    pub triangles: Arc<[Triangle]>,
    pub bounds: [[f32; 3]; 2],
}

/// Actual source transform and material values. The camera is never part of this identity.
#[derive(Clone, Debug, PartialEq)]
pub struct Instance {
    pub revision: u64,
    pub prototype_id: u64,
    pub origin: [f64; 3],
    /// Row-major 3x4 affine: world = origin + transform * [local, 1].
    pub transform: [f32; 12],
    /// u32::MAX inherits the prototype triangle's texture; zero means white.
    pub texture_id: u32,
    /// u32::MAX inherits the prototype triangle's material flags.
    pub flags: u32,
    /// Source RGBA bytes, multiplied with each vertex using integer floor(a*b/255).
    pub tint: [u8; 4],
    /// [scale_u, scale_v, offset_u, offset_v], applied to prototype UVs.
    pub uv_transform: [f32; 4],
}

/// Borrowed by the renderer; maps stay in their CPU owner and are never cloned per frame.
#[derive(Default, Debug)]
pub struct InstanceScene {
    pub epoch: u64,
    pub resource_revision: u64,
    pub instance_revision: u64,
    pub prototypes: BTreeMap<u64, Prototype>,
    pub instances: BTreeMap<u64, Instance>,
}

#[derive(Default)]
pub struct Scene {
    pub revision: u64,
    pub epoch: u64,
    pub anchor: [f64; 3],
    pub meshes: BTreeMap<MeshKey, SceneMesh>,
    /// Only cells with all 64 complete source sections may publish static geometry.
    /// Direct renderer fixtures must explicitly declare their complete cells too.
    pub ready_terrain: BTreeSet<Cell>,
    pub textures: BTreeMap<u32, Texture>,
    pub dynamic: DynamicScene,
}

impl Scene {
    pub fn triangle_count(&self) -> usize {
        self.meshes
            .values()
            .map(|mesh| mesh.triangles.len())
            .sum::<usize>()
            + self.dynamic.triangles.len()
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Camera {
    pub position: [f32; 3],
    pub forward: [f32; 3],
    pub right: [f32; 3],
    pub up: [f32; 3],
    pub vertical_fov_radians: f32,
}

/// CPU source mesh retains section-local positions until frame-space translation.
#[derive(Clone)]
pub(crate) struct Mesh {
    pub revision: u64,
    pub origin: [f64; 3],
    pub triangles: Arc<[Triangle]>,
    pub bounds: [[f32; 3]; 2],
    pub texture_id: u32,
    pub flags: u32,
}

#[derive(Default)]
pub(crate) struct DynamicMesh {
    pub revision: u64,
    pub origin: [f64; 3],
    pub triangles: Arc<[Triangle]>,
    pub bounds: [[f32; 3]; 2],
}

#[derive(Default)]
pub struct SourceScene {
    pub epoch: u64,
    pub revision: u64,
    pub(crate) meshes: BTreeMap<(u64, u32), Mesh>,
    /// Complete source snapshots, including observed empty sections; absence is unknown.
    pub(crate) sections: TerrainAvailability,
    /// Latest complete replacement/removal sequence, independent of content revisions.
    pub(crate) removed: BTreeMap<u64, u64>,
    pub(crate) textures: BTreeMap<u32, Texture>,
    pub(crate) triangle_count: usize,
    pub(crate) texture_bytes: usize,
    pub(crate) dynamic: DynamicMesh,
    pub(crate) instances: InstanceContext,
    pub(crate) section_scratch: crate::protocol::SectionScratch,
}

impl SourceScene {
    pub fn instances(&self) -> &InstanceScene {
        self.instances.scene()
    }

    pub fn instance_sequence(&self) -> u64 {
        self.instances.sequence()
    }

    pub fn translate(&self, anchor: [f64; 3]) -> Result<Scene, String> {
        let mut meshes = BTreeMap::new();
        for (key, mesh) in &self.meshes {
            if mesh.triangles.is_empty() {
                continue;
            }
            if mesh.texture_id != 0 && !self.textures.contains_key(&mesh.texture_id) {
                return Err("mesh references a texture that has not been captured".into());
            }
            let offset = std::array::from_fn::<_, 3, _>(|i| mesh.origin[i] - anchor[i]);
            for bound in mesh.bounds {
                for i in 0..3 {
                    if (offset[i] + f64::from(bound[i])).abs() > 1_048_576.0 {
                        return Err(
                            "scene exceeds the supported camera-relative coordinate range".into(),
                        );
                    }
                }
            }
            meshes.insert(
                *key,
                SceneMesh {
                    revision: mesh.revision,
                    flags: mesh.flags,
                    origin: mesh.origin,
                    triangles: mesh.triangles.clone(),
                },
            );
        }
        Ok(Scene {
            revision: self.revision,
            epoch: self.epoch,
            anchor,
            meshes,
            ready_terrain: self.sections.ready.clone(),
            textures: self.textures.clone(),
            dynamic: self.translate_dynamic(anchor)?,
        })
    }

    pub fn dynamic_revision(&self) -> u64 {
        self.dynamic.revision
    }

    /// Constant work: vertices and texture references were validated when the frame was submitted.
    pub fn translate_dynamic(&self, anchor: [f64; 3]) -> Result<DynamicScene, String> {
        let offset = std::array::from_fn::<_, 3, _>(|i| self.dynamic.origin[i] - anchor[i]);
        if !self.dynamic.triangles.is_empty() {
            for bound in self.dynamic.bounds {
                for i in 0..3 {
                    if (offset[i] + f64::from(bound[i])).abs() > 1_048_576.0 {
                        return Err(
                            "dynamic scene exceeds the supported camera-relative coordinate range"
                                .into(),
                        );
                    }
                }
            }
        }
        Ok(DynamicScene {
            revision: self.dynamic.revision,
            origin: self.dynamic.origin,
            triangles: self.dynamic.triangles.clone(),
        })
    }
}
