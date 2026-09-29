fn main() -> Result<(), String> {
    let path = std::env::args_os()
        .nth(1)
        .ok_or("Usage: bake-atmosphere OUTPUT.safetensors")?;
    prime_vulkan::bake_default_atmosphere(std::path::Path::new(&path))
}
