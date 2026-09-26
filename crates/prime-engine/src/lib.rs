//! Native engine: source sessions, GPU lifetime orchestration and a stable C ABI.
//! Minecraft version adapters live outside this workspace's native crates.

mod cpu_profile;
mod engine;
mod ffi;

pub use ffi::*;
