use prime_shader_build::{BuildConfig, ShaderSpec};
use std::{env, ffi::OsString, fs, path::PathBuf};

pub fn add(shaders: &mut Vec<ShaderSpec>, source: &str, name: &str, defines: &[&str]) {
    let mut shader = ShaderSpec::new(source, format!("{name}.spv"));
    shader.defines = defines.iter().map(|value| (*value).to_owned()).collect();
    shaders.push(shader);
}

pub fn samplers(shaders: &mut Vec<ShaderSpec>, source: &str, name: &str, common: &[&str]) {
    add(shaders, source, name, common);
    let mut defines = common.to_vec();
    defines.push("PRIME_LIGHT_TREE=1");
    add(shaders, source, &format!("{name}_tree"), &defines);
    defines.push("PRIME_LIGHT_TREE_SPHERE=1");
    add(shaders, source, &format!("{name}_tree_sphere"), &defines);
}

pub fn build(shaders: &[ShaderSpec]) {
    for variable in ["SLANGC", "PRIME_SHADER_CACHE", "PRIME_SHADER_JOBS"] {
        println!("cargo:rerun-if-env-changed={variable}");
    }
    // Cargo augments PATH with profile/build directories. Watch it only when
    // compiler selection actually depends on PATH, not for an explicit SDK.
    if env::var_os("SLANGC").is_none() {
        println!("cargo:rerun-if-env-changed=VULKAN_SDK");
        if env::var_os("VULKAN_SDK").is_none() {
            println!("cargo:rerun-if-env-changed=PATH");
            if cfg!(windows) {
                println!("cargo:rerun-if-env-changed=PATHEXT");
            }
        }
    }
    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let workspace = manifest.parent().unwrap().parent().unwrap();
    let source_root = workspace.join("crates/prime-vulkan");
    let output_dir = PathBuf::from(env::var_os("OUT_DIR").unwrap());
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
    let cache = env::var_os("PRIME_SHADER_CACHE")
        .map(PathBuf::from)
        .unwrap_or_else(|| workspace.join("target/prime-shader-cache"));
    let mut config = BuildConfig::new(compiler, &source_root, &output_dir, cache);
    config.include_dirs = vec![PathBuf::from("shaders")];
    config.dependency_roots = vec![PathBuf::from("shaders"), PathBuf::from("tests/shaders")];
    config.args = [
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
    ]
    .into_iter()
    .map(OsString::from)
    .collect();
    if let Some(jobs) = env::var_os("PRIME_SHADER_JOBS") {
        config.max_parallel = jobs
            .to_str()
            .and_then(|value| value.parse::<usize>().ok())
            .filter(|&jobs| jobs != 0)
            .expect("PRIME_SHADER_JOBS must be a positive integer");
    }
    let report = prime_shader_build::build(&config, shaders)
        .unwrap_or_else(|error| panic!("Shader build failed: {error}"));
    // Namespace changes can alter Slang import resolution. Ordinary edits outside
    // shader roots must not invalidate this leaf or invoke the shader compiler.
    for root in &config.dependency_roots {
        println!(
            "cargo:rerun-if-changed={}",
            source_root.join(root).display()
        );
    }
    for dependency in report
        .dependencies
        .iter()
        .chain(&report.toolchain_files)
        .chain(&report.toolchain_dirs)
    {
        println!("cargo:rerun-if-changed={}", dependency.display());
    }
    eprintln!(
        "Prime shaders: {} cache hits, {} compiled, {} frontend refreshes ({} namespace), {:.3}s",
        report.cache_hits,
        report.compiled,
        report.frontend_refreshes,
        report.namespace_refreshes,
        report.elapsed.as_secs_f64()
    );
    let mut accessors =
        String::from("// Generated shader accessors; binary data does not cross Rust metadata.\n");
    let mut names = std::collections::BTreeSet::new();
    for shader in shaders {
        let file = shader.output.file_name().unwrap().to_str().unwrap();
        let name = shader.output.file_stem().unwrap().to_str().unwrap();
        assert!(names.insert(name), "Duplicate shader output: {name}");
        accessors.push_str(&format!(
            "#[inline(never)]\npub fn {name}() -> &'static [u8] {{\n    include_bytes!(concat!(env!(\"OUT_DIR\"), \"/{file}\"))\n}}\n"
        ));
    }
    let generated = output_dir.join("shaders.rs");
    if fs::read(&generated).ok().as_deref() != Some(accessors.as_bytes()) {
        fs::write(generated, accessors).expect("Write shader accessors");
    }
}
