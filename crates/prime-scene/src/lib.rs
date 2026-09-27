//! Version-neutral source decoding and scene translation, without GPU or Minecraft APIs.
#![forbid(unsafe_code)]

pub mod extent;
pub mod incremental;
pub mod instances;
pub mod protocol;
pub mod scene;
pub mod settings;
pub mod spatial;
mod texture_lifetime;
pub mod translation;
pub mod workers;

pub use scene::{
    Camera, Instance, InstanceScene, Prototype, Scene, SceneMesh, SourceScene, Texture, Triangle,
};
