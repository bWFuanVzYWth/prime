//! Shader bytes cross this boundary only when a pipeline is created. Non-inline
//! accessors keep binary initializers out of downstream Rust metadata and codegen.
include!(concat!(env!("OUT_DIR"), "/shaders.rs"));
