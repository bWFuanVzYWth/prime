//! Branch-independent output evidence and native 1080p transport cost fixtures.
//! Run exclusively on the GPU. Readback belongs only to the equivalence test.
use super::*;
use crate::register_fixtures::{Case, fixture};
use std::{fs::File, io::Write};

#[test]
#[ignore = "fixed-sequence linear evidence; exclusive GPU with validation"]
fn dump_transport_equivalence() {
    let directory = std::env::var_os("PRIME_REGISTER_DUMP").expect("set PRIME_REGISTER_DUMP");
    std::fs::create_dir_all(&directory).unwrap();
    let directory = std::path::Path::new(&directory);
    let mut metadata = File::create(directory.join("samples.csv")).unwrap();
    writeln!(metadata, "case,bounces,samples,width,height,file").unwrap();
    let (width, height) = (31, 17);
    let mut renderer = Renderer::with_mode(RenderMode::Offline).unwrap();
    for (index, case) in Case::ALL.into_iter().enumerate() {
        let (scene, camera) = fixture(case, index as u64 + 1);
        for budget in [1, 2, 4, 7] {
            renderer
                .configure(RenderSettings {
                    mode: RenderMode::Offline,
                    bounces: budget,
                    seed: 0x1357_2468,
                    ..Default::default()
                })
                .unwrap();
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
                        "{case:?}/{budget} non-finite linear result {value}"
                    );
                }
            }
            let name = format!("{case:?}-{budget}.f32");
            std::fs::write(directory.join(&name), output).unwrap();
            writeln!(metadata, "{case:?},{budget},4,{width},{height},{name}").unwrap();
        }
    }
}

#[test]
#[ignore = "native 1080p steady transport matrix; exclusive GPU, validation off"]
fn steady_transport_matrix() {
    let output = std::env::var_os("PRIME_REGISTER_CSV").expect("set PRIME_REGISTER_CSV");
    let mut csv = File::create(output).unwrap();
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
    for case in Case::ALL {
        let name = format!("{case:?}");
        if selected
            .as_ref()
            .is_some_and(|names| !names.split(',').any(|item| item == name))
        {
            continue;
        }
        let (scene, camera) = fixture(case, 1);
        let mut host = HostBenchmark::new(1920, 1080).unwrap();
        host.configure(RenderSettings {
            mode: RenderMode::Realtime,
            bounces: 4,
            seed: 0x1357_2468,
            ..Default::default()
        })
        .unwrap();
        eprintln!(
            "register matrix: case={name} device={} native=1920x1080 bounces=4 seed=0x13572468 warmup={warmup} samples={samples}",
            host.device_name()
        );
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
