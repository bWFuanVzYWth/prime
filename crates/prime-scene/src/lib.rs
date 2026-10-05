//! Version-neutral source decoding and scene translation, without GPU or Minecraft APIs.
#![forbid(unsafe_code)]

pub mod compiled;
pub mod environment;
pub mod extent;
pub mod geometry;
pub mod incremental;
pub mod instances;
pub mod protocol;
pub mod restir_settings;
mod routing;
pub mod scene;
pub mod settings;
pub mod spatial;
pub mod surface;
mod texture_lifetime;
pub mod translation;
pub mod workers;

pub use scene::{
    Camera, Instance, InstanceScene, Prototype, Scene, SceneMesh, SourceScene, Texture,
    TextureLevel, TextureMaterial, TextureSampling, Triangle,
};
