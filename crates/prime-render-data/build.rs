use std::{env, fs::File, io::Read, path::PathBuf};

fn main() {
    let assets =
        PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("../prime-vulkan/assets");
    for name in [
        "starmap/starmap_2020_16k.ktx2",
        "openpbr/trans_ggx.ktx2",
        "atmosphere/medium.safetensors.zst",
        "atmosphere/optical_depth.ktx2",
        "atmosphere/scattering_source.ktx2",
        "atmosphere/incident_mean.ktx2",
        "atmosphere/ground_radiance.ktx2",
        "atmosphere/rayleigh_source.ktx2",
        "restir/paired-neighbors-3-16.bytes",
        "restir/paired-neighbors.bank.bytes",
    ] {
        let path = assets.join(name);
        println!("cargo:rerun-if-changed={}", path.display());
        let mut prefix = [0; 43];
        let length = File::open(&path)
            .and_then(|mut file| file.read(&mut prefix))
            .unwrap_or_else(|error| panic!("Cannot read fixed asset {}: {error}", path.display()));
        assert!(
            !prefix[..length].starts_with(b"version https://git-lfs.github.com/spec/v1"),
            "Fixed asset {} is a Git LFS pointer; run git lfs pull before building",
            path.display()
        );
    }
}
