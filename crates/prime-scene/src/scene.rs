//! Renderer values contain no Minecraft objects, borrowed FFM memory or Vulkan handles.
use std::{collections::BTreeMap, sync::Arc};

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
    pub origin: [f32; 3],
    pub triangles: Arc<[Triangle]>,
}

#[derive(Default)]
pub struct Scene {
    pub revision: u64,
    pub epoch: u64,
    pub anchor: [f64; 3],
    pub meshes: BTreeMap<MeshKey, SceneMesh>,
    pub textures: BTreeMap<u32, Texture>,
}

impl Scene {
    pub fn triangle_count(&self) -> usize {
        self.meshes.values().map(|mesh| mesh.triangles.len()).sum()
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
pub struct SourceScene {
    pub epoch: u64,
    pub revision: u64,
    pub(crate) meshes: BTreeMap<(u64, u32), Mesh>,
    pub(crate) removed: BTreeMap<u64, u64>,
    pub(crate) textures: BTreeMap<u32, Texture>,
    pub(crate) triangle_count: usize,
    pub(crate) texture_bytes: usize,
}

impl SourceScene {
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
                    origin: offset.map(|p| p as f32),
                    triangles: mesh.triangles.clone(),
                },
            );
        }
        Ok(Scene {
            revision: self.revision,
            epoch: self.epoch,
            anchor,
            meshes,
            textures: self.textures.clone(),
        })
    }
}
