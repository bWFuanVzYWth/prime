use std::{env, path::PathBuf};

fn main() {
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("../..");
        let native = root.join("native/streamline");
        let sdk = root.join("third_party/streamline/include");
        for name in ["prime_streamline.cpp", "prime_streamline.h"] {
            println!("cargo:rerun-if-changed={}", native.join(name).display());
        }
        println!("cargo:rerun-if-changed={}", sdk.display());
        let vulkan = root.join("third_party/streamline/vulkan-headers/include");
        println!("cargo:rerun-if-changed={}", vulkan.display());
        cc::Build::new()
            .cpp(true)
            .std("c++17")
            .flag_if_supported("/EHsc")
            .include(sdk)
            .include(vulkan)
            .file(native.join("prime_streamline.cpp"))
            .compile("prime_streamline");
    }
}
