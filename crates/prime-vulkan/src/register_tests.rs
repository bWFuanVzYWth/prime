//! Branch-independent output evidence and native 1080p transport cost fixtures.
//! Run exclusively on the GPU. Readback belongs only to the equivalence test.
use super::*;
use crate::register_fixtures::{Case, fixture};
use prime_scene::Texture;
use prime_scene::geometry::{CompiledQuad, MeshGeometry};
use prime_scene::scene::SceneMesh;
use prime_scene::settings::ReconstructionQuality;
use prime_scene::spatial::Cell;
use prime_scene::surface::{Emission, SurfaceFace, SurfaceMesh};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, fs::File, io::Write, path::PathBuf};

fn distribution_values<const N: usize>(line: &str) -> [f64; N] {
    let mut fields = line.split(',');
    let values = std::array::from_fn(|_| {
        fields
            .next()
            .expect("CSV field")
            .parse::<f64>()
            .expect("numeric CSV")
    });
    assert!(fields.next().is_none() && values.iter().all(|v| v.is_finite()));
    values
}

/// The original outline-box light distribution, not a complete saved world.
fn distribution_fixture(details: &mut File) -> Option<(Scene, Camera)> {
    let path = std::env::var_os("PRIME_REGISTER_LIGHT_DISTRIBUTION")?;
    let directory = std::fs::canonicalize(path).expect("distribution directory");
    writeln!(details, "distribution_path={}", directory.display()).unwrap();
    let load = |name: &str, details: &mut File| {
        let text = std::fs::read_to_string(directory.join(name)).expect("distribution CSV");
        writeln!(
            details,
            "{name}_sha256={:x}",
            Sha256::digest(text.as_bytes())
        )
        .unwrap();
        text
    };
    let emitters = load("emitters.csv", details);
    let receivers = load("receivers.csv", details);
    assert_eq!(
        emitters.lines().next(),
        Some("id,page,cx,cy,cz,ux,uy,uz,vx,vy,vz,radiance,power,two_sided")
    );
    assert_eq!(
        receivers.lines().next(),
        Some("id,x,y,z,nx,ny,nz,reference")
    );
    let receiver = distribution_values::<8>(receivers.lines().nth(1).expect("first receiver"));
    let position = [receiver[1], receiver[2], receiver[3]];
    let mut normal = [receiver[4] as f32, receiver[5] as f32, receiver[6] as f32];
    let length = normal.iter().map(|v| v * v).sum::<f32>().sqrt();
    assert!(length.is_finite() && length > 0.0);
    normal = normal.map(|v| v / length);
    let cross = |a: [f32; 3], b: [f32; 3]| {
        [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ]
    };
    let axis = if normal[1].abs() < 0.9 {
        [0., 1., 0.]
    } else {
        [1., 0., 0.]
    };
    let right = cross(axis, normal);
    let length = right.iter().map(|v| v * v).sum::<f32>().sqrt();
    let right = right.map(|v| v / length);
    let up = cross(normal, right);
    let mut groups = BTreeMap::<Cell, Vec<SurfaceFace>>::new();
    let mut quad = |center: [f64; 3], u: [f64; 3], v: [f64; 3], emission| {
        let cell = Cell::containing(center).unwrap();
        let origin = cell.origin();
        // The exporter front is cross(u,v); keep its winding and original scale.
        let positions = [[-1., -1.], [1., -1.], [1., 1.], [-1., 1.]].map(|s| {
            std::array::from_fn(|i| (center[i] + s[0] * u[i] + s[1] * v[i] - origin[i]) as f32)
        });
        let mut face = SurfaceFace::from_quad(CompiledQuad {
            positions,
            uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
            color: [1.; 4],
            texture_id: 1,
            flags: 0,
        });
        face.emission = emission;
        groups.entry(cell).or_default().push(face);
    };
    let mut count = 0;
    for line in emitters.lines().skip(1) {
        let row = distribution_values::<14>(line);
        assert!(row[11] >= 0. && (row[13] == 0. || row[13] == 1.));
        quad(
            [row[2], row[3], row[4]],
            [row[5], row[6], row[7]],
            [row[8], row[9], row[10]],
            Emission {
                radiance: [row[11] as f32; 3],
                two_sided: row[13] == 1.,
                textured: false,
            },
        );
        count += 1;
    }
    assert!(count > 0);
    quad(
        position,
        right.map(|v| f64::from(v) * 20.),
        up.map(|v| f64::from(v) * 20.),
        Emission::default(),
    );
    drop(emitters);
    drop(receivers);
    let mut scene = Scene {
        epoch: 1,
        revision: 1,
        ..Default::default()
    };
    scene.textures.insert(
        1,
        Texture {
            width: 1,
            height: 1,
            pixels: Arc::from([255_u8; 4]),
            region: None,
            sampling: None,
            material: None,
        },
    );
    for (index, (cell, faces)) in groups.into_iter().enumerate() {
        scene.ready_terrain.insert(cell);
        scene.meshes.insert(
            (index as u64 + 1, 0),
            SceneMesh {
                revision: 1,
                flags: 0,
                origin: cell.origin(),
                triangles: MeshGeometry::Surfaces(Arc::new(
                    SurfaceMesh::from_resolved(1, faces).unwrap(),
                )),
            },
        );
    }
    let camera = Camera {
        position: std::array::from_fn(|i| (position[i] + 2. * f64::from(normal[i])) as f32),
        forward: normal.map(|v| -v),
        right,
        up,
        vertical_fov_radians: 0.45,
    };
    let lights: usize = scene
        .meshes
        .values()
        .map(|m| match &m.triangles {
            MeshGeometry::Surfaces(s) => s.lights.emitters.len(),
            _ => unreachable!(),
        })
        .sum();
    writeln!(details, "distribution_emitters={count} compiled_source_lights={lights} cells={} expected_triangles={} receiver={position:?} normal={normal:?}",
        scene.ready_terrain.len(), scene.triangle_count()).unwrap();
    writeln!(details, "distribution_scope=outline-quads+white-diffuse-plane; original occluders/materials/visibility.bin absent; original scale; CSV pages/power recomputed; PRIME_REGISTER_CASES overridden").unwrap();
    Some((scene, camera))
}

struct RegisterOptions {
    light_sampling: LightSampling,
    integrator: Integrator,
    explicit_raw: bool,
    reference: Option<(PathBuf, Vec<u8>, String)>,
}

impl RegisterOptions {
    fn from_environment() -> Self {
        let method = std::env::var("PRIME_REGISTER_LIGHT_SAMPLING").ok();
        let integrator = match std::env::var("PRIME_REGISTER_INTEGRATOR").as_deref() {
            Err(_) | Ok("path_trace") => Integrator::PathTrace,
            Ok("restir_pt") => Integrator::RestirPt,
            Ok(other) => panic!("Unknown PRIME_REGISTER_INTEGRATOR: {other}"),
        };
        let reference = std::env::var_os("PRIME_REGISTER_REFERENCE_SPV").map(|path| {
            let path = std::fs::canonicalize(path).expect("canonical reference SPIR-V path");
            let bytes = std::fs::read(&path).expect("read reference SPIR-V");
            let hash = format!("{:x}", Sha256::digest(&bytes));
            (path, bytes, hash)
        });
        assert!(
            reference.is_none() || integrator == Integrator::PathTrace,
            "Transport reference SPIR-V is only compatible with PathTrace"
        );
        Self {
            integrator,
            light_sampling: match method.as_deref() {
                None => RenderSettings::default().light_sampling,
                Some("tree") => LightSampling::Tree,
                Some("sphere") => LightSampling::TreeSphere,
                Some(other) => panic!("Unknown PRIME_REGISTER_LIGHT_SAMPLING: {other}"),
            },
            explicit_raw: method.is_some()
                || reference.is_some()
                || integrator == Integrator::RestirPt,
            reference,
        }
    }

    fn settings(&self, mode: RenderMode, bounces: u32) -> RenderSettings {
        assert!(
            mode == RenderMode::Realtime || self.integrator == Integrator::PathTrace,
            "ReSTIR benchmark requires realtime mode"
        );
        let mut settings = RenderSettings {
            mode,
            integrator: self.integrator,
            bounces,
            seed: 0x1357_2468,
            light_sampling: self.light_sampling,
            ..Default::default()
        };
        if self.explicit_raw {
            settings.ray_reconstruction = false;
            settings.reconstruction_quality = ReconstructionQuality::Native;
            settings.opacity_micromap = false;
            settings.stars = 0.0;
            settings.auto_exposure_compensation = 0.0;
        }
        settings
    }

    fn write_metadata(&self, output: &mut File, mode: RenderMode, width: u32, height: u32) {
        writeln!(output, "mode={mode:?} width={width} height={height}").unwrap();
        writeln!(output, "light_sampling={:?}", self.light_sampling).unwrap();
        writeln!(output, "integrator={:?}", self.integrator).unwrap();
        writeln!(
            output,
            "sphere_tree_build={}",
            crate::light_sphere_cpu::register_tree_build()
        )
        .unwrap();
        writeln!(
            output,
            "profile={} sdk=false",
            if self.explicit_raw {
                "raw-native"
            } else {
                "legacy-default-settings"
            }
        )
        .unwrap();
        writeln!(output, "settings_template={:?}", self.settings(mode, 4)).unwrap();
        if let Some((path, bytes, hash)) = &self.reference {
            writeln!(output, "reference_path={}", path.display()).unwrap();
            writeln!(
                output,
                "reference_sha256={hash} reference_bytes={}",
                bytes.len()
            )
            .unwrap();
            writeln!(
                output,
                "transport_bank={} single_sample_bank=false",
                match mode {
                    RenderMode::Realtime => "realtime-k2",
                    RenderMode::Offline => "offline-general",
                }
            )
            .unwrap();
        } else {
            writeln!(
                output,
                "reference_path=embedded reference_sha256=unrecorded"
            )
            .unwrap();
            writeln!(output, "transport_bank=embedded-default").unwrap();
        }
    }
}

#[test]
#[ignore = "fixed-sequence linear evidence; exclusive GPU with validation"]
fn dump_transport_equivalence() {
    let options = RegisterOptions::from_environment();
    let directory = std::env::var_os("PRIME_REGISTER_DUMP").expect("set PRIME_REGISTER_DUMP");
    std::fs::create_dir_all(&directory).unwrap();
    let directory = std::path::Path::new(&directory);
    let mut metadata = File::create(directory.join("samples.csv")).unwrap();
    writeln!(metadata, "case,bounces,samples,width,height,file").unwrap();
    let (width, height) = (31, 17);
    let mut renderer = Renderer::with_mode(RenderMode::Offline).unwrap();
    renderer
        .configure(options.settings(RenderMode::Offline, 1))
        .unwrap();
    if let Some((_, bytes, _)) = &options.reference {
        renderer.install_transport_reference(bytes).unwrap();
    }
    let mut details = File::create(directory.join("metadata.txt")).unwrap();
    options.write_metadata(&mut details, RenderMode::Offline, width, height);
    writeln!(details, "bounces=1,2,4,7 samples=4").unwrap();
    writeln!(details, "{}", renderer.context.benchmark_device_details()).unwrap();
    let distribution = distribution_fixture(&mut details);
    let deep = distribution.is_some();
    let cases = distribution.map_or_else(
        || {
            Case::ALL
                .into_iter()
                .enumerate()
                .map(|(i, c)| (format!("{c:?}"), fixture(c, i as u64 + 1)))
                .collect::<Vec<_>>()
        },
        |scene| vec![("LightDistribution".into(), scene)],
    );
    for (name, (scene, camera)) in cases {
        if deep {
            let mut preload = 0;
            while !renderer.fixture_ready(&scene) {
                assert!(
                    preload < 512,
                    "Deep fixture did not finish publishing within 512 frames"
                );
                renderer.render(&scene, &camera, width, height, 0).unwrap();
                preload += 1;
            }
            let (triangles, blas, pages, lamps, local_depths) = renderer.fixture_geometry_summary();
            writeln!(details, "preload_frames={preload} published_triangles={triangles} published_blas={blas} published_light_pages={pages} published_lights={lamps}").unwrap();
            if options.light_sampling == LightSampling::TreeSphere {
                assert_eq!(local_depths.iter().sum::<u64>(), lamps as u64);
                writeln!(details, "local_leaf_depths={local_depths:?}").unwrap();
            }
        }
        for budget in [1, 2, 4, 7] {
            renderer
                .configure(options.settings(RenderMode::Offline, budget))
                .unwrap();
            // Only the push-constant path budget changes; the installed bank survives.
            for sequence in 0..4 {
                renderer
                    .render(&scene, &camera, width, height, sequence)
                    .unwrap();
            }
            let buffer = renderer.output.as_ref().unwrap().linear_buffer();
            let bytes = (u64::from(width * height) * 16) as usize;
            let context = &renderer.context;
            let readback = Buffer::new_readback(context, bytes as u64).unwrap();
            context
                .submit_named("register-equivalence-readback", |command| unsafe {
                    let barrier = [vk::BufferMemoryBarrier::default()
                        .buffer(buffer.buffer)
                        .offset(0)
                        .size(bytes as u64)
                        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                        .src_access_mask(vk::AccessFlags::SHADER_WRITE)
                        .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
                    context.device.cmd_pipeline_barrier(
                        command,
                        vk::PipelineStageFlags::ALL_COMMANDS,
                        vk::PipelineStageFlags::TRANSFER,
                        vk::DependencyFlags::empty(),
                        &[],
                        &barrier,
                        &[],
                    );
                    context.device.cmd_copy_buffer(
                        command,
                        buffer.buffer,
                        readback.buffer,
                        &[vk::BufferCopy::default().size(bytes as u64)],
                    );
                    let barrier = [vk::MemoryBarrier::default()
                        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                        .dst_access_mask(vk::AccessFlags::HOST_READ)];
                    context.device.cmd_pipeline_barrier(
                        command,
                        vk::PipelineStageFlags::TRANSFER,
                        vk::PipelineStageFlags::HOST,
                        vk::DependencyFlags::empty(),
                        &barrier,
                        &[],
                        &[],
                    );
                })
                .unwrap();
            let output = readback.read(bytes).unwrap();
            for pixel in output.as_chunks::<16>().0 {
                for channel in 0..3 {
                    let value =
                        f32::from_le_bytes(pixel[channel * 4..channel * 4 + 4].try_into().unwrap());
                    assert!(
                        value.is_finite() && value >= 0.,
                        "{name}/{budget} non-finite linear result {value}"
                    );
                }
            }
            let file = format!("{name}-{budget}.f32");
            std::fs::write(directory.join(&file), output).unwrap();
            writeln!(metadata, "{name},{budget},4,{width},{height},{file}").unwrap();
        }
    }
}

#[test]
#[ignore = "native 1080p steady transport matrix; exclusive GPU, validation off"]
fn steady_transport_matrix() {
    let options = RegisterOptions::from_environment();
    let output = std::env::var_os("PRIME_REGISTER_CSV").expect("set PRIME_REGISTER_CSV");
    let mut csv = File::create(&output).unwrap();
    let mut details = File::create(PathBuf::from(&output).with_extension("txt")).unwrap();
    options.write_metadata(&mut details, RenderMode::Realtime, 1920, 1080);
    writeln!(
        csv,
        "case,sample,warmup,triangles,cpu_record_ns,cpu_wall_ns,gpu_ns,prepare_ns,render_ns"
    )
    .unwrap();
    let count = |name: &str, default: u32| {
        std::env::var(name).map_or(default, |text| {
            text.parse::<u32>().expect("count must be u32")
        })
    };
    let warmup = count("PRIME_REGISTER_WARMUP", 128);
    let samples = count("PRIME_REGISTER_SAMPLES", 256);
    assert!(samples > 0);
    let selected = std::env::var("PRIME_REGISTER_CASES").ok();
    writeln!(
        details,
        "bounces=4 warmup={warmup} samples={samples} serial_drain=true"
    )
    .unwrap();
    let distribution = distribution_fixture(&mut details);
    let deep = distribution.is_some();
    let cases = distribution.map_or_else(
        || {
            Case::ALL
                .into_iter()
                .filter_map(|c| {
                    let name = format!("{c:?}");
                    (!selected
                        .as_ref()
                        .is_some_and(|names| !names.split(',').any(|item| item == name)))
                    .then(|| (name, fixture(c, 1)))
                })
                .collect::<Vec<_>>()
        },
        |scene| vec![("LightDistribution".into(), scene)],
    );
    for (name, (scene, camera)) in cases {
        let mut host = HostBenchmark::new(1920, 1080).unwrap();
        host.configure(options.settings(RenderMode::Realtime, 4))
            .unwrap();
        if let Some((_, bytes, _)) = &options.reference {
            host.install_transport_reference(bytes).unwrap();
        }
        writeln!(details, "case={name} {}", host.device_details()).unwrap();
        eprintln!(
            "register matrix: case={name} device={} method={:?} integrator={:?} native=1920x1080 bounces=4 seed=0x13572468 warmup={warmup} samples={samples}",
            host.device_name(),
            options.light_sampling,
            options.integrator,
        );
        if deep {
            let mut preload = 0;
            while !host.fixture_ready(&scene) {
                assert!(
                    preload < 512,
                    "Deep fixture did not finish publishing within 512 frames"
                );
                host.enqueue(&scene, &camera, 0).unwrap();
                host.drain().unwrap();
                preload += 1;
            }
            let (triangles, blas, pages, lamps, local_depths) = host.fixture_geometry_summary();
            writeln!(details, "preload_frames={preload} published_triangles={triangles} published_blas={blas} published_light_pages={pages} published_lights={lamps}").unwrap();
            if options.light_sampling == LightSampling::TreeSphere {
                assert_eq!(local_depths.iter().sum::<u64>(), lamps as u64);
                writeln!(details, "local_leaf_depths={local_depths:?}").unwrap();
            }
        }
        for sample in 0..warmup.checked_add(samples).unwrap() {
            let frame = host.enqueue(&scene, &camera, sample).unwrap();
            let done = host.drain().unwrap()[0];
            writeln!(
                csv,
                "{name},{sample},{},{},{},{},{},{},{}",
                sample < warmup,
                host.triangle_count(),
                frame.record_ns,
                frame.wall_ns,
                done.gpu_ns,
                done.preparation_ns.unwrap_or(0),
                done.render_ns.unwrap_or(0)
            )
            .unwrap();
        }
    }
}
