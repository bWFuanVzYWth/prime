//! Test shader bytes are available only through the explicit compiled feature.
#[cfg(feature = "compiled")]
include!(concat!(env!("OUT_DIR"), "/shaders.rs"));
