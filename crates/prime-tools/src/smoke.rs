//! Standalone GPU smoke path; no Minecraft installation or window is required.
#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;
#[cfg(feature = "vulkan")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    use prime_scene::{Camera, Scene, SceneMesh, Texture, Triangle};
    use prime_vulkan::Renderer;
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.first().is_some_and(|arg| arg != "smoke") || args.len() > 3 {
        return Err("usage: prime-pt-smoke [smoke [output.png [samples]]]".into());
    }
    let path = args
        .get(1)
        .map(String::as_str)
        .unwrap_or("artifacts/smoke.png");
    let samples: u32 = args.get(2).map(|v| v.parse()).transpose()?.unwrap_or(32);
    if samples == 0 || samples > 4096 {
        return Err("samples must be 1..4096".into());
    }
    let mut scene = Scene {
        ready_terrain: [prime_scene::spatial::Cell::containing([0.0; 3])?].into(),
        revision: 1,
        ..Default::default()
    };
    scene.textures.insert(
        1,
        Texture {
            region: None,
            sampling: None,
            width: 2,
            height: 2,
            pixels: vec![
                245, 245, 245, 255, 110, 140, 190, 255, 110, 140, 190, 255, 245, 245, 245, 255,
            ]
            .into(),
        },
    );
    let mut triangles = Vec::new();
    let mut quad = |points: [[f32; 3]; 4], color: [f32; 4], texture_id| {
        let uv = [[0.0, 0.0], [4.0, 0.0], [4.0, 4.0], [0.0, 4.0]];
        for indices in [[0, 1, 2], [2, 3, 0]] {
            triangles.push(Triangle {
                positions: indices.map(|i| points[i]),
                colors: [color; 3],
                uvs: indices.map(|i| uv[i]),
                texture_id,
                flags: 0,
            });
        }
    };
    quad(
        [
            [-4.0, 0.0, 3.0],
            [4.0, 0.0, 3.0],
            [4.0, 0.0, -4.0],
            [-4.0, 0.0, -4.0],
        ],
        [0.8, 0.8, 0.8, 1.0],
        1,
    );
    quad(
        [
            [-4.0, 0.0, -4.0],
            [4.0, 0.0, -4.0],
            [4.0, 4.0, -4.0],
            [-4.0, 4.0, -4.0],
        ],
        [0.85, 0.85, 0.85, 1.0],
        0,
    );
    let (x0, x1, z0, z1, y) = (-0.8, 0.8, -1.8, -0.2, 1.6);
    quad(
        [[x0, 0.0, z1], [x1, 0.0, z1], [x1, y, z1], [x0, y, z1]],
        [0.85, 0.25, 0.1, 1.0],
        0,
    );
    quad(
        [[x1, 0.0, z0], [x0, 0.0, z0], [x0, y, z0], [x1, y, z0]],
        [0.85, 0.25, 0.1, 1.0],
        0,
    );
    quad(
        [[x0, 0.0, z0], [x0, 0.0, z1], [x0, y, z1], [x0, y, z0]],
        [0.85, 0.25, 0.1, 1.0],
        0,
    );
    quad(
        [[x1, 0.0, z1], [x1, 0.0, z0], [x1, y, z0], [x1, y, z1]],
        [0.85, 0.25, 0.1, 1.0],
        0,
    );
    quad(
        [[x0, y, z1], [x1, y, z1], [x1, y, z0], [x0, y, z0]],
        [0.85, 0.25, 0.1, 1.0],
        0,
    );
    scene.meshes.insert(
        (0, 0),
        SceneMesh {
            revision: 1,
            flags: 0,
            origin: [0.0; 3],
            triangles: triangles.into(),
        },
    );
    let angle = 0.18_f32;
    let camera = Camera {
        position: [2.8, 2.1, 5.8],
        forward: [-0.35 * angle.cos(), -angle.sin(), -0.9367497 * angle.cos()],
        right: [0.9367497, 0.0, -0.35],
        up: [-0.35 * angle.sin(), angle.cos(), -0.9367497 * angle.sin()],
        vertical_fov_radians: 55.0_f32.to_radians(),
    };
    let (width, height) = (1920, 1080);
    let started = std::time::Instant::now();
    let mut renderer = Renderer::new()?;
    println!("Vulkan device: {}", renderer.device_name());
    let mut pixels = Vec::new();
    for sample in 0..samples {
        pixels = renderer.render(&scene, &camera, width, height, sample)?;
    }
    if pixels.len() != (width * height * 4) as usize
        || pixels.as_chunks::<4>().0.iter().any(|p| p[3] != 255)
    {
        return Err("GPU returned invalid RGBA8 output".into());
    }
    let range = pixels
        .as_chunks::<4>()
        .0
        .iter()
        .fold((255u8, 0u8), |(low, high), p| {
            (low.min(p[0]), high.max(p[0]))
        });
    if range.1.saturating_sub(range.0) < 30 {
        return Err("GPU smoke produced a flat image".into());
    }
    let path = std::path::Path::new(path);
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    let mut encoder = png::Encoder::new(
        std::io::BufWriter::new(std::fs::File::create(path)?),
        width,
        height,
    );
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    encoder.write_header()?.write_image_data(&pixels)?;
    println!(
        "Rendered {} triangles, {samples} samples, {width}x{height} in {:.2}s: {}",
        scene.triangle_count(),
        started.elapsed().as_secs_f32(),
        path.display()
    );
    Ok(())
}

#[cfg(not(feature = "vulkan"))]
fn main() {
    eprintln!("GPU smoke requires the default vulkan feature");
}
