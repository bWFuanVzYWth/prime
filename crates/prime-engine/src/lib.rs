//! Native engine: source sessions, GPU lifetime orchestration and a stable C ABI.
//! Minecraft version adapters live outside this workspace's native crates.

// Reduce the measured cost of large geometry allocations and cross-worker retirement.
// No malloc override: only Rust-owned allocations use this allocator; FFM borrows stay borrowed.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

mod cpu_profile;
mod engine;
mod ffi;

pub use ffi::*;
