//! Windowless production display passes. Readback is test-only; no Present or Streamline.
use super::*;
use crate::{
    display::PrimeDrtSettings,
    display_pipeline::LinearDisplay,
    hdr::{FrameGenerationPresent, HdrPresent},
    starmap::{Starmap, Stars, StarsParameters},
    target::Image,
};

fn bytes(values: &[f32]) -> Vec<u8> {
    values.iter().flat_map(|v| v.to_le_bytes()).collect()
}

fn rgba(values: &[f32]) -> Vec<f32> {
    values.iter().flat_map(|&v| [v, v, v, 1.]).collect()
}

fn floats(data: &[u8]) -> Vec<f32> {
    data.as_chunks::<4>()
        .0
        .iter()
        .map(|v| f32::from_le_bytes(*v))
        .collect()
}

fn half(value: f32) -> u16 {
    // These fixture values are exactly representable normal binary fractions (or zero).
    if value == 0. {
        return 0;
    }
    let bits = value.to_bits();
    (((bits >> 16) & 0x8000) | ((((bits >> 23) & 255) - 112) << 10) | ((bits >> 13) & 1023)) as u16
}

fn unhalf(value: u16) -> f32 {
    let sign = f32::from_bits(u32::from(value & 0x8000) << 16 | 0x3f800000);
    let exponent = i32::from((value >> 10) & 31);
    let fraction = f32::from(value & 1023);
    let magnitude = if exponent == 0 {
        fraction * 2.0f32.powi(-24)
    } else {
        (1. + fraction / 1024.) * 2.0f32.powi(exponent - 15)
    };
    magnitude.copysign(sign)
}

fn halves(data: &[u8]) -> Vec<f32> {
    data.as_chunks::<2>()
        .0
        .iter()
        .map(|v| unhalf(u16::from_le_bytes(*v)))
        .collect()
}

fn before_transfer(context: &Context, command: vk::CommandBuffer) {
    barrier(
        context,
        command,
        vk::PipelineStageFlags::ALL_COMMANDS,
        vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE,
        vk::PipelineStageFlags::TRANSFER,
        vk::AccessFlags::TRANSFER_READ | vk::AccessFlags::TRANSFER_WRITE,
    );
}

fn copy_region(extent: [u32; 2]) -> vk::BufferImageCopy {
    vk::BufferImageCopy::default()
        .image_subresource(
            vk::ImageSubresourceLayers::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR)
                .layer_count(1),
        )
        .image_extent(vk::Extent3D {
            width: extent[0],
            height: extent[1],
            depth: 1,
        })
}

fn upload(context: &Arc<Context>, image: &Image, extent: [u32; 2], data: &[u8]) {
    let source = Buffer::upload(context, data, vk::BufferUsageFlags::TRANSFER_SRC).unwrap();
    context
        .submit_named("mature_display_fixture_upload", |command| unsafe {
            before_transfer(context, command);
            context.device.cmd_copy_buffer_to_image(
                command,
                source.buffer,
                image.image,
                vk::ImageLayout::GENERAL,
                &[copy_region(extent)],
            );
            barrier(
                context,
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
            );
        })
        .unwrap();
}

fn read_image(
    context: &Arc<Context>,
    image: &Image,
    extent: [u32; 2],
    pixel_bytes: u64,
) -> Vec<u8> {
    let size = u64::from(extent[0]) * u64::from(extent[1]) * pixel_bytes;
    let readback = Buffer::new_readback(context, size).unwrap();
    context
        .submit_named("mature_display_fixture_readback", |command| unsafe {
            before_transfer(context, command);
            context.device.cmd_copy_image_to_buffer(
                command,
                image.image,
                vk::ImageLayout::GENERAL,
                readback.buffer,
                &[copy_region(extent)],
            );
            barrier(
                context,
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::PipelineStageFlags::HOST,
                vk::AccessFlags::HOST_READ,
            );
        })
        .unwrap();
    readback.read(size as usize).unwrap()
}

fn read_state(context: &Arc<Context>, exposure: &Exposure) -> [f32; 4] {
    let readback = Buffer::new_readback(context, 16).unwrap();
    context
        .submit_named("mature_exposure_state_readback", |command| unsafe {
            before_transfer(context, command);
            context.device.cmd_copy_buffer(
                command,
                exposure.state.buffer,
                readback.buffer,
                &[vk::BufferCopy::default().size(16)],
            );
            barrier(
                context,
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::AccessFlags::TRANSFER_WRITE,
                vk::PipelineStageFlags::HOST,
                vk::AccessFlags::HOST_READ,
            );
        })
        .unwrap();
    floats(&readback.read(16).unwrap()).try_into().unwrap()
}

fn close(actual: f32, expected: f32, tolerance: f32) {
    assert!(
        (actual - expected).abs() <= tolerance * expected.abs().max(1.),
        "{actual} != {expected} (relative/absolute tolerance {tolerance})"
    );
}

#[test]
#[ignore = "windowless production BDA exposure/display pixels; exclusive GPU with validation"]
fn gpu_exposure_bda_and_linear_display() {
    let context = Context::new().unwrap();
    let extent = [2, 2];
    let input = Image::with_format(&context, 2, 2, vk::Format::R32G32B32A32_SFLOAT).unwrap();
    let host = Image::new(&context, 2, 2).unwrap();
    let encoded_hdr = Image::with_format(&context, 2, 2, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let baseline = Image::new(&context, 2, 2).unwrap();
    let radiance = rgba(&[0.02, 0.18, 1., 8.]);
    let input_buffer = Buffer::upload(
        &context,
        &bytes(&radiance),
        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
    )
    .unwrap();
    upload(&context, &input, extent, &bytes(&radiance));
    let mut exposure = Exposure::new(&context).unwrap();
    context
        .submit_named("mature_exposure_image", |command| {
            exposure
                .record(
                    command, 0, input.view, 0, extent, false, 0.1, true, true, 0.6,
                )
                .unwrap();
        })
        .unwrap();
    let image_state = read_state(&context, &exposure);
    assert_eq!(image_state[1].to_bits(), 1);
    // Independent histogram quantization and scene-key math, not a call to the shader helper.
    let bins: Vec<_> = [0.02f64, 0.18, 1., 8.]
        .map(|v| (((v.log2() + 16.) / 36. * 256.).floor() as u32).min(255))
        .into();
    let logs: Vec<_> = bins
        .iter()
        .map(|&b| -16. + (f64::from(b) + 0.5) * 36. / 256.)
        .collect();
    let measured = logs.iter().sum::<f64>() / 4.;
    let range = logs[3] - logs[0];
    let bias = 2. * (2. * measured - logs[0] - logs[3]) / range.max(2.);
    let target = (0.16f64.log2() - measured + bias).clamp(-16., 16.) * 0.6;
    close(image_state[0], target as f32, 2e-6);
    close(image_state[2], target as f32, 2e-6);
    close(image_state[3], measured as f32, 2e-6);
    context
        .submit_named("mature_exposure_bda", |command| {
            exposure
                .record(
                    command,
                    0,
                    input.view,
                    input_buffer.address(),
                    extent,
                    false,
                    0.1,
                    true,
                    true,
                    0.6,
                )
                .unwrap();
        })
        .unwrap();
    assert_eq!(image_state, read_state(&context, &exposure));
    let display = LinearDisplay::new(&context).unwrap();
    let sdr = PrimeDrtSettings::default().prepare(1.).unwrap();
    let hdr = PrimeDrtSettings::default().prepare(5.).unwrap();
    let views = [input.view, host.view, encoded_hdr.view, baseline.view];
    context
        .submit_named("mature_display_image", |command| {
            display
                .record(
                    command,
                    0,
                    views,
                    0,
                    extent,
                    false,
                    false,
                    true,
                    exposure.state_address(),
                    sdr,
                    hdr,
                    false,
                )
                .unwrap();
        })
        .unwrap();
    let image_sdr = read_image(&context, &host, extent, 4);
    let image_hdr = read_image(&context, &encoded_hdr, extent, 8);
    assert!(image_sdr.as_chunks::<4>().0.iter().all(|p| p[3] == 0));
    assert_eq!(image_sdr, read_image(&context, &baseline, extent, 4));
    context
        .submit_named("mature_display_bda", |command| {
            display
                .record(
                    command,
                    0,
                    views,
                    input_buffer.address(),
                    extent,
                    false,
                    false,
                    true,
                    exposure.state_address(),
                    sdr,
                    hdr,
                    false,
                )
                .unwrap();
        })
        .unwrap();
    assert_eq!(image_sdr, read_image(&context, &host, extent, 4));
    assert_eq!(image_hdr, read_image(&context, &encoded_hdr, extent, 8));
    context
        .submit_named("mature_display_sdr_fg_baseline", |command| {
            display
                .record(
                    command,
                    0,
                    views,
                    input_buffer.address(),
                    extent,
                    false,
                    false,
                    false,
                    exposure.state_address(),
                    sdr,
                    hdr,
                    true,
                )
                .unwrap();
        })
        .unwrap();
    assert_eq!(image_sdr, read_image(&context, &host, extent, 4));
    assert_eq!(image_sdr, read_image(&context, &baseline, extent, 4));
    // SDR FG never writes an HDR snapshot; disabled HDR storage may instead be a dummy.
    assert_eq!(image_hdr, read_image(&context, &encoded_hdr, extent, 8));
    // Manual and automatic multipliers each occur once, before primeDRT.
    let manual = PrimeDrtSettings {
        exposure_multiplier: 2.,
        ..Default::default()
    }
    .prepare(1.)
    .unwrap();
    context
        .submit_named("mature_display_manual_and_auto", |command| {
            display
                .record(
                    command,
                    0,
                    views,
                    input_buffer.address(),
                    extent,
                    false,
                    true,
                    false,
                    exposure.state_address(),
                    manual,
                    hdr,
                    false,
                )
                .unwrap();
        })
        .unwrap();
    let actual = read_image(&context, &host, extent, 4);
    let scaled: Vec<_> = radiance
        .as_chunks::<4>()
        .0
        .iter()
        .flat_map(|p| {
            [
                p[0] * 2. * image_state[0].exp2(),
                p[1] * 2. * image_state[0].exp2(),
                p[2] * 2. * image_state[0].exp2(),
                p[3],
            ]
        })
        .collect();
    let scaled_buffer = Buffer::upload(
        &context,
        &bytes(&scaled),
        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
    )
    .unwrap();
    context
        .submit_named("mature_display_scaled_oracle", |command| {
            display
                .record(
                    command,
                    0,
                    views,
                    scaled_buffer.address(),
                    extent,
                    false,
                    true,
                    false,
                    0,
                    sdr,
                    hdr,
                    false,
                )
                .unwrap();
        })
        .unwrap();
    let expected = read_image(&context, &host, extent, 4);
    for (&a, &e) in actual.iter().zip(&expected) {
        assert!(a.abs_diff(e) <= 1);
    }
    assert!(actual.as_chunks::<4>().0.iter().all(|p| p[3] == 255));
    context
        .submit_named("mature_exposure_disabled", |command| {
            exposure
                .record(
                    command,
                    0,
                    input.view,
                    input_buffer.address(),
                    extent,
                    false,
                    0.1,
                    true,
                    true,
                    0.,
                )
                .unwrap();
        })
        .unwrap();
    close(read_state(&context, &exposure)[0], 0., 0.);
}

fn eotf(value: f32) -> f32 {
    let v = f64::from(value).abs();
    (if v <= 0.04045 {
        v / 12.92
    } else {
        ((v + 0.055) / 1.055).powf(2.4)
    } as f32)
        .copysign(value)
}

#[test]
#[ignore = "windowless production HDR composite pixel readback; exclusive GPU with validation"]
fn gpu_hdr_composite_absolute_units_and_orientation() {
    let context = Context::new().unwrap();
    let extent = [2, 2];
    let world = Image::with_format(&context, 2, 2, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let baseline = Image::new(&context, 2, 2).unwrap();
    let ui = Image::new(&context, 2, 2).unwrap();
    let output = Image::with_format(&context, 2, 2, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let hudless = Image::with_format(&context, 2, 2, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let ui_mask = Image::with_format(&context, 2, 2, vk::Format::R8_UNORM).unwrap();
    let world_values = [
        -0.125f32, 0.5, 2., 1., 1., 0.25, 0.5, 1., 2., 1., 0.125, 1., 0., 2., 0.5, 1.,
    ];
    let world_bytes: Vec<_> = world_values
        .into_iter()
        .flat_map(|v| half(v).to_le_bytes())
        .collect();
    let baseline_bytes = [
        10u8, 20, 30, 0, 40, 50, 60, 0, 70, 80, 90, 0, 100, 110, 120, 0,
    ];
    let ui_bytes = [
        70u8, 80, 90, 0, 110, 90, 80, 85, 80, 110, 100, 170, 180, 170, 160, 255,
    ];
    upload(&context, &world, extent, &world_bytes);
    upload(&context, &baseline, extent, &baseline_bytes);
    upload(&context, &ui, extent, &ui_bytes);
    let present = HdrPresent::new(&context).unwrap();
    for composite in [true, false] {
        context
            .submit_named("mature_hdr_present", |command| {
                present
                    .record(
                        command,
                        0,
                        [
                            world.view,
                            baseline.view,
                            ui.view,
                            output.view,
                            hudless.view,
                            ui_mask.view,
                        ],
                        extent,
                        composite,
                        true,
                        2.5,
                        composite,
                    )
                    .unwrap();
            })
            .unwrap();
        let actual = halves(&read_image(&context, &output, extent, 8));
        let actual_hudless = composite.then(|| halves(&read_image(&context, &hudless, extent, 8)));
        if composite {
            assert_eq!(
                read_image(&context, &ui_mask, extent, 1),
                ui_bytes
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .map(|p| p[3])
                    .collect::<Vec<_>>()
            );
        }
        for p in 0..4 {
            let wp = (1 - p / 2) * 2 + p % 2;
            let coverage = f32::from(ui_bytes[4 * p + 3]) / 255.;
            for c in 0..3 {
                let u = f32::from(ui_bytes[4 * p + c]) / 255.;
                let baseline = f32::from(baseline_bytes[4 * wp + c]) / 255.;
                let expected = if composite {
                    let encoded_ui = (u - baseline * (1. - coverage)).max(0.);
                    let ui = if coverage > 0. {
                        eotf(encoded_ui / coverage) * coverage
                    } else {
                        0.
                    };
                    (ui + eotf(world_values[4 * wp + c]) * (1. - coverage)) * 2.5
                } else {
                    eotf(u) * 2.5
                };
                close(actual[4 * p + c], expected, 1e-3);
                if let Some(hudless) = &actual_hudless {
                    close(
                        hudless[4 * p + c],
                        eotf(world_values[4 * wp + c]) * 2.5,
                        1e-3,
                    );
                }
            }
            assert_eq!(actual[4 * p + 3], 1.);
        }
    }
}

#[test]
#[ignore = "windowless SDR FG HUDless and UI coverage readback; exclusive GPU with validation"]
fn gpu_sdr_frame_generation_hudless_and_mask() {
    let context = Context::new().unwrap();
    let extent = [2, 2];
    let baseline = Image::new(&context, 2, 2).unwrap();
    let ui = Image::new(&context, 2, 2).unwrap();
    let hudless = Image::new(&context, 2, 2).unwrap();
    let mask = Image::with_format(&context, 2, 2, vk::Format::R8_UNORM).unwrap();
    let source = [1u8, 2, 3, 0, 5, 7, 11, 0, 13, 17, 19, 0, 23, 29, 31, 0];
    let overlay = [
        99u8, 88, 77, 0, 66, 55, 44, 85, 33, 22, 11, 170, 9, 8, 7, 255,
    ];
    upload(&context, &baseline, extent, &source);
    upload(&context, &ui, extent, &overlay);
    let pass = FrameGenerationPresent::new(&context).unwrap();
    for bottom_up in [false, true] {
        context
            .submit_named("mature_sdr_fg_present", |command| {
                pass.record(
                    command,
                    0,
                    [baseline.view, ui.view, hudless.view, mask.view],
                    extent,
                    bottom_up,
                )
                .unwrap();
            })
            .unwrap();
        let result = read_image(&context, &hudless, extent, 4);
        for p in 0..4 {
            let src = if bottom_up {
                (1 - p / 2) * 2 + p % 2
            } else {
                p
            };
            assert_eq!(&result[4 * p..4 * p + 3], &source[4 * src..4 * src + 3]);
            assert_eq!(result[4 * p + 3], 255);
        }
        assert_eq!(
            read_image(&context, &mask, extent, 1),
            vec![0, 85, 170, 255]
        );
    }
}

#[test]
#[ignore = "windowless production FP32 Stars and real BC6H mip upload; exclusive GPU with validation"]
fn gpu_stars_fp32_coverage_foreground_and_ground() {
    let context = Context::new().unwrap();
    let image = Image::with_format(&context, 1, 1, vk::Format::R32G32B32A32_SFLOAT).unwrap();
    let status = Image::with_format(&context, 1, 1, vk::Format::R8_UNORM).unwrap();
    let transmittance =
        Image::with_format(&context, 8193, 1, vk::Format::R32G32B32A32_SFLOAT).unwrap();
    upload(
        &context,
        &transmittance,
        [8193, 1],
        &bytes(&[1f32; 4].repeat(8193)),
    );
    upload(&context, &status, [1, 1], &[0]);
    let mut map = Starmap::new(&context).unwrap();
    let mut uploads = Vec::new();
    context
        .submit_named("mature_starmap_complete_mips", |command| {
            uploads = map.prepare(command).unwrap();
        })
        .unwrap();
    drop(uploads); // Standalone submit_named has now completed the upload's fence.
    let stars = Stars::new(&context).unwrap();
    let mut parameters = StarsParameters {
        forward: [0., 0.5, -0.8660254, 0.5],
        right: [1., 0., 0., 1.],
        up: [0., 0.8660254, 0.5, 0.],
        sun: [0., 1., 0., -0.02],
        settings: [30f32.to_radians(), 0., 0.025, 1.],
        dimensions: [1, 1, 1, 1],
        jitter: [0.; 2],
    };
    // Execute the real FP32 RR selector before Stars; selected RGB/alpha cannot be tone mapped.
    let noisy = Image::with_format(&context, 1, 1, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let reconstructed =
        Image::with_format(&context, 1, 1, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let depth = Image::with_format(&context, 1, 1, vk::Format::R32_SFLOAT).unwrap();
    let normal = Image::with_format(&context, 1, 1, vk::Format::R16G16B16A16_SFLOAT).unwrap();
    let selector = PostCompute::new(
        &context,
        &[vk::DescriptorType::STORAGE_IMAGE; 20],
        &[include_bytes!(concat!(env!("OUT_DIR"), "/rr_linear.spv"))],
        64,
    )
    .unwrap();
    for (binding, view) in [
        (4, image.view),
        (10, noisy.view),
        (11, depth.view),
        (13, normal.view),
        (18, reconstructed.view),
        (19, status.view),
    ] {
        selector.image(0, binding, vk::DescriptorType::STORAGE_IMAGE, view);
    }
    let run = |alpha: f32, status_value: u8, success: bool| {
        let scene = [0.25, 0.5, 1., alpha];
        let fixture: Vec<_> = scene
            .into_iter()
            .flat_map(|v| {
                if v.is_nan() {
                    0x7e00u16.to_le_bytes()
                } else {
                    half(v).to_le_bytes()
                }
            })
            .collect();
        upload(&context, &reconstructed, [1, 1], &fixture);
        let fallback: Vec<_> = [1., 0.5, 0.25, alpha]
            .into_iter()
            .flat_map(|v: f32| {
                if v.is_nan() {
                    0x7e00u16.to_le_bytes()
                } else {
                    half(v).to_le_bytes()
                }
            })
            .collect();
        upload(&context, &noisy, [1, 1], &fallback);
        upload(&context, &status, [1, 1], &[status_value]);
        let mut push = [0u8; 64];
        for (out, value) in push[..32].as_chunks_mut::<4>().0.iter_mut().zip([
            1,
            1,
            1,
            1,
            0,
            0,
            u32::from(success),
            0,
        ]) {
            *out = value.to_le_bytes();
        }
        context
            .submit_named("mature_stars_fp32", |command| {
                barrier(
                    &context,
                    command,
                    vk::PipelineStageFlags::ALL_COMMANDS,
                    vk::AccessFlags::MEMORY_WRITE,
                    vk::PipelineStageFlags::COMPUTE_SHADER,
                    vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
                );
                selector.dispatch(command, 0, 0, &push, [1; 3]);
                stars.record(
                    command,
                    0,
                    image.view,
                    status.view,
                    transmittance.view,
                    &map,
                    &parameters,
                );
            })
            .unwrap();
        floats(&read_image(&context, &image, [1, 1], 16))
    };
    let sky = run(0., 0, true);
    assert!(
        sky[0] > 0.25 && sky[1] > 0.5 && sky[2] > 1.,
        "real mip chain must add finite positive star energy: {sky:?}"
    );
    let half_coverage = run(0.5, 0, true);
    for (i, base) in [0.25, 0.5, 1.].into_iter().enumerate() {
        close(half_coverage[i] - base, (sky[i] - base) * 0.5, 2e-6);
    }
    assert_eq!(half_coverage[3], 0.5);
    assert_eq!(run(1., 0, true), vec![0.25, 0.5, 1., 1.]);
    assert_eq!(run(f32::NAN, 0, true), vec![0.25, 0.5, 1., 1.]);
    assert_eq!(run(0., 8, true), vec![0.25, 0.5, 1., 0.]);
    let fallback = run(0., 0, false);
    for (i, (base, expected)) in [(1., 0.25), (0.5, 0.5), (0.25, 1.)].into_iter().enumerate() {
        close(fallback[i] - base, sky[i] - expected, 2e-6);
    }
    // Stars itself rejects invalid coverage rather than injecting a sky guess.
    upload(&context, &image, [1, 1], &bytes(&[0.25, 0.5, 1., f32::NAN]));
    upload(&context, &status, [1, 1], &[0]);
    context
        .submit_named("mature_stars_invalid_coverage", |command| {
            stars.record(
                command,
                0,
                image.view,
                status.view,
                transmittance.view,
                &map,
                &parameters,
            );
        })
        .unwrap();
    let invalid = floats(&read_image(&context, &image, [1, 1], 16));
    assert_eq!(&invalid[..3], &[0.25, 0.5, 1.]);
    assert!(invalid[3].is_nan());
    parameters.forward = [0., -1., 0., 0.5];
    upload(&context, &status, [1, 1], &[0]);
    upload(&context, &image, [1, 1], &bytes(&[0.25, 0.5, 1., 0.]));
    context
        .submit_named("mature_stars_ground", |command| {
            stars.record(
                command,
                0,
                image.view,
                status.view,
                transmittance.view,
                &map,
                &parameters,
            );
        })
        .unwrap();
    assert_eq!(
        floats(&read_image(&context, &image, [1, 1], 16)),
        vec![0.25, 0.5, 1., 0.]
    );
}
