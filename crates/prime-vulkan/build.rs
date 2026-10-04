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
    for name in ["generate", "retrace", "shift", "temporal"] {
        let source = format!("shaders/restir_{name}.slang");
        let binary = format!("restir_{name}");
        compile(&compiler, &source, &format!("{binary}.spv"));
        compile_defines(
            &compiler,
            &source,
            &format!("{binary}_tree.spv"),
            &["PRIME_LIGHT_TREE=1"],
        );
        compile_defines(
            &compiler,
            &source,
            &format!("{binary}_tree_sphere.spv"),
            &["PRIME_LIGHT_TREE=1", "PRIME_LIGHT_TREE_SPHERE=1"],
        );
    }
    for name in ["workload", "resolve"] {
        compile_defines(
            &compiler,
            &format!("shaders/restir_{name}.slang"),
            &format!("restir_{name}.spv"),
            &["PRIME_RESTIR_MATERIAL_ONLY=1"],
        );
    }
    for (suffix, defines) in [
        ("", vec!["PRIME_RESTIR_RR=1"]),
        ("_tree", vec!["PRIME_RESTIR_RR=1", "PRIME_LIGHT_TREE=1"]),
        (
            "_tree_sphere",
            vec![
                "PRIME_RESTIR_RR=1",
                "PRIME_LIGHT_TREE=1",
                "PRIME_LIGHT_TREE_SPHERE=1",
            ],
        ),
    ] {
        compile_defines(
            &compiler,
            "shaders/restir_generate_rr.slang",
            &format!("restir_generate_rr{suffix}.spv"),
            &defines,
        );
    }
    compile_defines(
        &compiler,
        "shaders/restir_resolve.slang",
        "restir_resolve_rr.spv",
        &["PRIME_RESTIR_MATERIAL_ONLY=1", "PRIME_RESTIR_RR=1"],
    );
    for name in ["indirect", "spatial"] {
        compile(
            &compiler,
            &format!("shaders/restir_{name}.slang"),
            &format!("restir_{name}.spv"),
        );
    }
    for name in ["path_trace", "realtime_transport", "realtime_transport_rr"] {
        compile_defines(
            &compiler,
            &format!("shaders/{name}.slang"),
            &format!("{name}_tree.spv"),
            &["PRIME_LIGHT_TREE=1"],
        );
        compile_defines(
            &compiler,
            &format!("shaders/{name}.slang"),
            &format!("{name}_tree_sphere.spv"),
            &["PRIME_LIGHT_TREE=1", "PRIME_LIGHT_TREE_SPHERE=1"],
        );
    }
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
    compile(
        &compiler,
        "shaders/realtime_linear.slang",
        "realtime_linear.spv",
    );
    compile(&compiler, "shaders/rr_linear.slang", "rr_linear.spv");
    compile(
        &compiler,
        "shaders/display/from_linear.slang",
        "display_from_linear.spv",
    );
    compile(&compiler, "shaders/display/stars.slang", "stars.spv");
    for name in [
        "exposure_histogram",
        "exposure_update",
        "hdr_present",
        "frame_generation_present",
    ] {
        compile(
            &compiler,
            &format!("shaders/display/{name}.slang"),
            &format!("{name}.spv"),
        );
    }
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
            "light_tree",
            "sphere_tree",
            "optics",
            "texture",
            "roulette",
            "pbr",
            "pbr_delta",
            "full_openpbr",
            "pbr_texture",
            "primary",
            "primary_rr",
            "restir_adapter",
            "restir_history",
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
        compile_defines(
            &compiler,
            "tests/shaders/restir_adapter.slang",
            "restir_adapter_tree.spv",
            &["PRIME_LIGHT_TREE=1"],
        );
        compile_defines(
            &compiler,
            "tests/shaders/restir_adapter.slang",
            "restir_adapter_tree_sphere.spv",
            &["PRIME_LIGHT_TREE=1", "PRIME_LIGHT_TREE_SPHERE=1"],
        );
        for (suffix, defines) in [
            ("_tree", vec!["PRIME_LIGHT_TREE=1"]),
            (
                "_tree_sphere",
                vec!["PRIME_LIGHT_TREE=1", "PRIME_LIGHT_TREE_SPHERE=1"],
            ),
        ] {
            compile_defines(
                &compiler,
                "tests/shaders/restir_history.slang",
                &format!("restir_history{suffix}.spv"),
                &defines,
            );
        }
    }
}

fn compile(compiler: &std::path::Path, source: &str, name: &str) {
    compile_defines(compiler, source, name, &[]);
}

fn compile_defines(compiler: &std::path::Path, source: &str, name: &str, defines: &[&str]) {
    let output = PathBuf::from(env::var_os("OUT_DIR").expect("Cargo OUT_DIR")).join(name);
    let mut command = Command::new(compiler);
    for define in defines {
        command.arg(format!("-D{define}"));
    }
    let result = command
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
