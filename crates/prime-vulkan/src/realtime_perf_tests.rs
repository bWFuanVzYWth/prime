//! Explicit frozen-SPIR-V A/B experiment. Raw output only; not RR or guide cost evidence.
use super::*;
use crate::realtime_tests::{camera, face, scene, texture};
use prime_scene::{
    scene::TextureMaterial,
    surface::{Emission, Medium, Optics, SurfaceFace},
};
use std::{collections::BTreeMap, fs::File, io::Write, path::PathBuf};

fn boundary(z: f32, reverse: bool, medium: Medium) -> SurfaceFace {
    let mut face = face(z);
    face.geometry.colors = [[1.; 4]; 4];
    if reverse {
        face.geometry.positions.reverse();
        face.geometry.uvs.reverse();
    }
    face.media = [7, 0];
    face.optics = Some(Optics {
        negative: medium,
        positive: Medium::default(),
        ior_textures: [None; 2],
        transmit: true,
        thin: false,
    });
    face
}

fn fixtures() -> Vec<(&'static str, Scene)> {
    let mut receiver = face(-2.);
    receiver.emission = Emission {
        radiance: [0.1, 0.2, 0.3],
        two_sided: true,
        textured: false,
    };
    let glass = Medium {
        ior: 1.5,
        extinction: [0.07, 0.03, 0.01],
    };
    let mut layers = Vec::new();
    for front in [2.5, 1.5, 0.5, -0.5] {
        layers.push(boundary(front, false, glass));
        layers.push(boundary(front - 0.3, true, glass));
    }
    layers.push(receiver.clone());
    let water = Medium {
        ior: 1.333,
        extinction: [0.1, 0.03, 0.015],
    };
    let mut mirror = face(6.);
    mirror.geometry.positions.reverse();
    mirror.geometry.texture_id = 7;
    let mut floor = face(-3.);
    floor.optics = Some(Optics {
        negative: water,
        positive: water,
        ior_textures: [None; 2],
        transmit: false,
        thin: false,
    });
    floor.media = [7; 2];
    let mut water_scene = scene(3, vec![boundary(0., false, water), mirror, floor]);
    let mut conductor = texture(1, 1, vec![255; 4]);
    conductor.material = Some(Arc::new(TextureMaterial {
        specular: Some(texture(1, 1, vec![255, 231, 0, 255])),
        ..Default::default()
    }));
    water_scene.textures.insert(7, conductor);
    let mut rear = face(6.);
    rear.geometry.positions.reverse();
    vec![
        ("opaque", scene(1, vec![face(0.), rear, receiver])),
        ("four_glass_slabs", scene(2, layers)),
        ("water_under_mirror", water_scene),
    ]
}

fn fingerprint(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf29ce484222325, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x100000001b3)
    })
}

fn tool_version(program: impl AsRef<std::ffi::OsStr>, args: &[&str]) -> String {
    match std::process::Command::new(program).args(args).output() {
        Ok(output) => format!(
            "status={} stdout={} stderr={}",
            output.status,
            String::from_utf8_lossy(&output.stdout).trim(),
            String::from_utf8_lossy(&output.stderr).trim(),
        ),
        Err(error) => format!("unavailable: {error}"),
    }
}

#[test]
#[ignore = "release-only native 1080p raw A/B; explicit frozen SPIR-V, exclusive GPU, validation off"]
fn gpu_realtime_raw_split_cost_ab_ba() {
    let reference = PathBuf::from(
        std::env::var_os("PRIME_REALTIME_REFERENCE_SPV")
            .expect("set PRIME_REALTIME_REFERENCE_SPV to the frozen pre-split raw realtime SPIR-V"),
    )
    .canonicalize()
    .expect("frozen reference SPIR-V must exist");
    let bytes = std::fs::read(&reference).unwrap();
    assert!(
        bytes.len() >= 20 && bytes[..4] == [3, 2, 35, 7],
        "invalid SPIR-V header"
    );
    if cfg!(debug_assertions) {
        panic!("run the performance experiment with --release");
    }
    assert!(
        std::env::var_os("PRIME_VK_VALIDATION").is_none_or(|value| value == "0"),
        "run performance measurements with PRIME_VK_VALIDATION=0"
    );
    let count = |name: &str, default: u32| {
        std::env::var(name).map_or(default, |value| {
            value.parse::<u32>().expect("invalid sample count")
        })
    };
    let warmup = count("PRIME_REALTIME_COST_WARMUP", 12);
    let samples = count("PRIME_REALTIME_COST_SAMPLES", 48);
    assert!(warmup >= 8 && samples >= 30);
    let total = 1_u32
        .checked_add(warmup)
        .unwrap()
        .checked_add(samples)
        .unwrap();
    let output = std::env::var_os("PRIME_REALTIME_COST_CSV").map_or_else(
        || {
            PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                .join("../../artifacts/realtime-split/raw-cost.csv")
        },
        PathBuf::from,
    );
    if let Some(parent) = output.parent().filter(|path| !path.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent).unwrap();
    }
    let mut csv = File::create(&output).unwrap();
    let mut metadata = File::create(output.with_extension("txt")).unwrap();
    let mut host = HostBenchmark::new(1920, 1080).unwrap();
    let config = RenderSettings {
        bounces: 12,
        offline_samples: 1,
        seed: 0x1357_2468,
        ray_reconstruction: false,
        opacity_micromap: false,
        ..Default::default()
    };
    writeln!(metadata, "{}", host.device_details()).unwrap();
    writeln!(
        metadata,
        "unix_time={:?}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
    )
    .unwrap();
    writeln!(
        metadata,
        "reference={} bytes={} fnv1a64={:016x}",
        reference.display(),
        bytes.len(),
        fingerprint(&bytes)
    )
    .unwrap();
    for (name, shader) in [
        ("primary", prime_shaders::realtime_primary()),
        ("transport", prime_shaders::realtime_transport_tree()),
        ("post", prime_shaders::realtime()),
    ] {
        writeln!(
            metadata,
            "split_{name}_bytes={} fnv1a64={:016x}",
            shader.len(),
            fingerprint(shader)
        )
        .unwrap();
    }
    writeln!(metadata, "rustc={}", tool_version("rustc", &["-vV"])).unwrap();
    let slang = std::env::var_os("SLANGC")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("VULKAN_SDK").map(|sdk| {
                PathBuf::from(sdk).join(if cfg!(windows) {
                    "Bin/slangc.exe"
                } else {
                    "bin/slangc"
                })
            })
        })
        .unwrap_or_else(|| PathBuf::from("slangc"));
    writeln!(
        metadata,
        "runtime_slangc={} {}",
        slang.display(),
        tool_version(&slang, &["-version"])
    )
    .unwrap();
    writeln!(
        metadata,
        "git_head={}",
        tool_version("git", &["rev-parse", "HEAD"])
    )
    .unwrap();
    writeln!(
        metadata,
        "logical_cpus={:?} PRIME_PROFILE={:?} PRIME_CPU_PROFILE={:?}",
        std::thread::available_parallelism(),
        std::env::var_os("PRIME_PROFILE"),
        std::env::var_os("PRIME_CPU_PROFILE")
    )
    .unwrap();
    writeln!(metadata, "native=1920x1080 settings={config:?} warmup={warmup} measured_samples={samples} in_flight=2 orders=AB,BA").unwrap();
    writeln!(metadata, "A=frozen single-kernel raw; B=production split raw. No RR, guides, game, present, display compositor, or image readback. Initialization and warmup rows are retained but excluded from steady summaries. All steady samples and outliers are retained; timestamps cover the complete recorded host command.").unwrap();
    writeln!(metadata, "A uses the existing Offline 128-byte Frame dispatch ABI and descriptor superset, replaces every compute variant with the explicit frozen SPIR-V, and removes the single-sample variants. Its allocated accumulation buffer is unused by the frozen shader. Both arms use the current common host/geometry/atmosphere/LUT infrastructure; this is a shader/scheduling experiment, not a frozen whole-engine comparison.").unwrap();
    writeln!(csv, "scene,order,arm,phase,sequence,serial,width,height,bounces,seed,triangles,cpu_record_ns,cpu_submit_ns,cpu_slot_wait_ns,cpu_wall_ns,gpu_total_ns,preparation_ns,render_ns").unwrap();
    let camera = camera();
    for (name, scene) in fixtures() {
        for (order, arms) in [("AB", [false, true]), ("BA", [true, false])] {
            for split in arms {
                let arm = if split { "B_split" } else { "A_frozen" };
                host.configure(RenderSettings {
                    mode: if split {
                        RenderMode::Realtime
                    } else {
                        RenderMode::Offline
                    },
                    ..config
                })
                .unwrap();
                if !split {
                    host.install_realtime_reference(&bytes).unwrap();
                }
                let mut frames = Vec::with_capacity(total as usize);
                for sequence in 0..total {
                    frames.push(host.enqueue(&scene, &camera, sequence).unwrap());
                }
                let completed: BTreeMap<_, _> = host
                    .drain()
                    .unwrap()
                    .into_iter()
                    .map(|done| (done.serial, done))
                    .collect();
                assert_eq!(completed.len(), frames.len(), "timestamps were lost");
                let mut steady = Vec::with_capacity(samples as usize);
                for (sequence, frame) in frames.iter().enumerate() {
                    let done = completed[&frame.serial];
                    assert!(done.gpu_ns > 0, "GPU timestamp interval is empty");
                    let phase = if sequence == 0 {
                        "initialization"
                    } else if sequence <= warmup as usize {
                        "warmup"
                    } else {
                        "steady"
                    };
                    if phase == "steady" {
                        steady.push(done.gpu_ns);
                    }
                    writeln!(csv, "{name},{order},{arm},{phase},{sequence},{},1920,1080,12,{},{},{},{},{},{},{},{},{}",
                        frame.serial, config.seed, host.triangle_count(), frame.record_ns, frame.submit_ns, frame.slot_wait_ns, frame.wall_ns, done.gpu_ns,
                        done.preparation_ns.map_or_else(String::new, |value| value.to_string()),
                        done.render_ns.map_or_else(String::new, |value| value.to_string()),
                    ).unwrap();
                }
                assert_eq!(steady.len(), samples as usize);
                steady.sort_unstable();
                eprintln!(
                    "raw split cost: {name} {order} {arm} GPU min/median/p95/max={:.3}/{:.3}/{:.3}/{:.3} ms samples={samples}",
                    steady[0] as f64 / 1e6,
                    steady[steady.len() / 2] as f64 / 1e6,
                    steady[(steady.len() - 1) * 95 / 100] as f64 / 1e6,
                    steady[steady.len() - 1] as f64 / 1e6
                );
                csv.flush().unwrap();
            }
        }
    }
    metadata.flush().unwrap();
    eprintln!("raw split timings: {}", output.display());
}
