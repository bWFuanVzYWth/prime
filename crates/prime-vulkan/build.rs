use std::{env, path::PathBuf, process::Command};

fn main() {
    println!("cargo:rerun-if-changed=shaders/path_trace.slang");
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
    let output =
        PathBuf::from(env::var_os("OUT_DIR").expect("Cargo OUT_DIR")).join("path_trace.spv");
    let result = Command::new(&compiler)
        .args([
            "shaders/path_trace.slang",
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
            "Slang compilation failed:\n{}\n{}",
            String::from_utf8_lossy(&result.stdout),
            String::from_utf8_lossy(&result.stderr)
        );
    }
}
