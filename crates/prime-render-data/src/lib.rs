//! Immutable renderer payloads. Keep their bytes out of downstream Rust metadata.
//!
//! These non-inline accessors are called while loading resources, not per frame.
//! Parsing, source validation and GPU ownership remain with the renderer.

#[inline(never)]
pub fn starmap() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/starmap/starmap_2020_16k.ktx2")
}

#[inline(never)]
pub fn openpbr_energy() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/openpbr/trans_ggx.ktx2")
}

#[inline(never)]
pub fn atmosphere_medium() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/medium.safetensors.zst")
}

#[inline(never)]
pub fn atmosphere_optical_depth() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/optical_depth.ktx2")
}

#[inline(never)]
pub fn atmosphere_scattering_source() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/scattering_source.ktx2")
}

#[inline(never)]
pub fn atmosphere_incident_mean() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/incident_mean.ktx2")
}

#[inline(never)]
pub fn atmosphere_ground_radiance() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/ground_radiance.ktx2")
}

#[inline(never)]
pub fn atmosphere_rayleigh_source() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/atmosphere/rayleigh_source.ktx2")
}

#[inline(never)]
pub fn paired_neighbors() -> &'static [u8] {
    include_bytes!("../../prime-vulkan/assets/restir/paired-neighbors-3-16.bytes")
}
