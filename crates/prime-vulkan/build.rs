use std::{env, path::PathBuf, process::Command};

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
    // Cargo tracks directory contents recursively, including imported modules.
    println!("cargo:rerun-if-changed=shaders");
    println!("cargo:rerun-if-changed=tests/shaders");
    println!("cargo:rerun-if-env-changed=SLANGC");
    println!("cargo:rerun-if-env-changed=VULKAN_SDK");
    let compiler = env::var_os("SLANGC")
        .map(PathBuf::from)
        .or_else(|| {
            env::var_os("VULKAN_SDK").map(|sdk| {
                PathBuf::from(sdk).join(if cfg!(windows) {
                    "Bin/slangc.exe"
                } else {
                    "bin/slangc"
                })
            })
        })
        .unwrap_or_else(|| PathBuf::from("slangc"));
    compile(&compiler, "shaders/path_trace.slang", "path_trace.spv");
    for name in [
        "realtime_primary",
        "realtime_primary_rr",
        "realtime_transport",
        "realtime_transport_rr",
    ] {
        compile(
            &compiler,
            &format!("shaders/{name}.slang"),
            &format!("{name}.spv"),
        );
    }
    compile(&compiler, "shaders/realtime.slang", "realtime.spv");
    compile(&compiler, "shaders/realtime_rr.slang", "realtime_rr.spv");
    compile(&compiler, "shaders/rr_display.slang", "rr_display.spv");
    if env::var_os("CARGO_FEATURE_LIGHT_SAMPLING_BENCH").is_some() {
        compile(
            &compiler,
            "tests/shaders/sampling_experiment.slang",
            "light_sampling.spv",
        );
    }
    for name in [
        "prepare",
        "sky_update",
        "transmittance_update",
        "aerial_update",
        "aerial_transmittance_update",
        "shadow_demand",
        "shadow_resolve",
    ] {
        compile(
            &compiler,
            &format!("shaders/atmosphere/{name}.slang"),
            &format!("atmosphere_{name}.spv"),
        );
    }
    if env::var_os("CARGO_FEATURE_ATMOSPHERE_BAKE").is_some() {
        for name in [
            "transmittance",
            "directions",
            "incident",
            "moments",
            "multi_scattering",
            "ground",
        ] {
            compile(
                &compiler,
                &format!("shaders/atmosphere/bake/{name}.slang"),
                &format!("atmosphere_bake_{name}.spv"),
            );
        }
    }
    if env::var_os("CARGO_FEATURE_SHADER_TESTS").is_some() {
        for name in [
            "foundations",
            "intersection",
            "display",
            "lights",
            "optics",
            "texture",
            "roulette",
            "pbr",
            "pbr_delta",
            "full_openpbr",
            "pbr_texture",
            "primary",
            "primary_rr",
        ] {
            compile(
                &compiler,
                &format!("tests/shaders/{name}.slang"),
                &format!("{name}.spv"),
            );
        }
        compile(
            &compiler,
            "tests/shaders/atmosphere.slang",
            "atmosphere_test.spv",
        );
    }
}

fn compile(compiler: &std::path::Path, source: &str, name: &str) {
    let output = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo OUT_DIR")).join(name);
    let result = Command::new(compiler)
        .args([
            source,
            "-I",
            "shaders",
            "-entry",
            "main",
            "-stage",
            "compute",
            "-target",
            "spirv",
            "-profile",
            "sm_6_6",
            "-emit-spirv-directly",
            "-fvk-use-entrypoint-name",
            "-O3",
            "-g3",
            "-o",
        ])
        .arg(&output)
        .output()
        .unwrap_or_else(|error| {
            panic!(
                "Cannot run {}: {error}. Install Slang and set SLANGC or VULKAN_SDK.",
                compiler.display()
            )
        });
    if !result.status.success() {
        panic!(
            "Slang compilation failed for {source}:\n{}\n{}",
            String::from_utf8_lossy(&result.stdout),
            String::from_utf8_lossy(&result.stderr)
        );
    }
}
