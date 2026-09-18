// Appended to the pinned Sky Tracer crate only by export_atmosphere.py.
pub async fn export_prime_gpu(path: &std::path::Path, aerosol_scale: f32) -> Result<()> {
    let instance = wgpu::Instance::default();
    let adapter = instance.request_adapter(&Default::default()).await?;
    let (d, q) = adapter
        .request_device(&wgpu::DeviceDescriptor {
            required_limits: adapter.limits(),
            ..Default::default()
        })
        .await?;
    let mut r = Renderer::new(
        &d,
        &Model::earth_with_aerosol_scale(aerosol_scale)?,
        &Wavelengths::optimized_four(),
        0.18,
        Config::balanced(),
    )?;
    r.rebuild(&d, &q)?;
    r.resize(&d, [16, 12]);
    let cases: [[f32; 4]; 10] = [
        [0.064, 90.0, 0.0, 30.0],
        [0.064, 0.0, 0.0, 0.0],
        [0.064, -6.0, 180.0, 5.0],
        [12.0, -6.0, 0.0, 0.0],
        [30.0, -6.0, 180.0, -5.0],
        [35.0, 2.0, 0.0, -5.0],
        [108.0, -4.0, 0.0, -8.0],
        [119.99, 10.0, 0.0, -8.0],
        [400.0, -4.0, 0.0, -20.0],
        [0.064, -18.0, 0.0, 5.0],
    ];
    let mut metadata = Vec::new();
    for (i, c) in cases.iter().enumerate() {
        // Prime's existing eye-radius ABI quantizes height in planet-radius f32 coordinates.
        let height = (6360.0 + c[0]) - 6360.0;
        let view = View {
            altitude_km: height,
            sun_elevation_deg: c[1],
            sun_azimuth_deg: 0.0,
            yaw_deg: c[2],
            pitch_deg: c[3],
            fov_y_deg: 85.0,
        };
        metadata.push([height, c[1], c[2], c[3]]);
        for sky in [false, true] {
            r.spectral_output = !sky;
            r.resize(&d, [16, 12]);
            let mut e = d.create_command_encoder(&Default::default());
            r.render(&q, &mut e, view);
            q.submit([e.finish()]);
            d.poll(wgpu::PollType::wait_indefinitely())?;
            if sky {
                std::fs::write(
                    path.join(format!("sky-{i}.bin")),
                    read_texture(&d, &q, &r._sky, 16)?,
                )?;
                std::fs::write(
                    path.join(format!("projection-{i}.bin")),
                    read_texture(&d, &q, &r.target, 16)?,
                )?;
            } else {
                std::fs::write(
                    path.join(format!("light-{i}.bin")),
                    read_texture(&d, &q, &r.target, 16)?,
                )?;
                std::fs::write(
                    path.join(format!("trans-{i}.bin")),
                    read_texture(&d, &q, &r.transmittance, 16)?,
                )?;
            }
        }
    }
    std::fs::write(
        path.join("cases.json"),
        serde_json::to_vec_pretty(&metadata)?,
    )?;
    Ok(())
}
