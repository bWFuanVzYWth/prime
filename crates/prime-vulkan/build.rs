use std::{env, path::PathBuf, process::Command};

fn main() {
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
    compile(&compiler, "shaders/realtime.slang", "realtime.spv");
    compile(
        &compiler,
        "shaders/realtime_display.slang",
        "realtime_display.spv",
    );
    if env::var_os("CARGO_FEATURE_SHADER_TESTS").is_some() {
        for name in ["foundations", "intersection", "display"] {
            compile(
                &compiler,
                &format!("tests/shaders/{name}.slang"),
                &format!("{name}.spv"),
            );
        }
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
