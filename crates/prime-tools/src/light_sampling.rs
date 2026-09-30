//! Windowless light-selection cost / variance experiment. See docs/guides/light-sampling.md.
use prime_vulkan::light_sampling::{
    BATCH, ErrorAccumulator, Fixture, Gpu, METHODS, RECEIVERS, convergence_slope,
};
use std::{
    fs::{self, File},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    time::Instant,
};

#[global_allocator]
static ALLOCATOR: mimalloc::MiMalloc = mimalloc::MiMalloc;

struct Options {
    output: PathBuf,
    scenes: Vec<String>,
    width: u32,
    height: u32,
    spp: u32,
    replicas: u32,
    frames: u32,
    warmup: u32,
    seed: u32,
}

impl Options {
    fn parse() -> Result<Self, String> {
        let mut options = Self {
            output: "artifacts/light-sampling".into(),
            scenes: ["uniform", "near_far", "occluded", "many"]
                .map(str::to_string)
                .into(),
            width: 1920,
            height: 1080,
            spp: 256,
            replicas: 4,
            frames: 512,
            warmup: 256,
            seed: 20260930,
        };
        let mut args = std::env::args().skip(1);
        while let Some(key) = args.next() {
            if key == "--help" {
                println!(
                    "light-sampling [--output DIR] [--scenes uniform,near_far,occluded,many,many_strips] [--spp 256] [--replicas 4] [--frames 512] [--warmup 256] [--seed 20260930] [--width 1920 --height 1080]\nAll methods and IID/Z-Sobol are compared. Only native 1920x1080 is a formal cost baseline. No game or window is opened."
                );
                std::process::exit(0);
            }
            let value = args
                .next()
                .ok_or_else(|| format!("Missing value for {key}"))?;
            let integer = || {
                value
                    .parse::<u32>()
                    .map_err(|_| format!("Invalid integer for {key}"))
            };
            match key.as_str() {
                "--output" => options.output = value.into(),
                "--scenes" => options.scenes = value.split(',').map(str::to_string).collect(),
                "--width" => options.width = integer()?,
                "--height" => options.height = integer()?,
                "--spp" => options.spp = integer()?,
                "--replicas" => options.replicas = integer()?,
                "--frames" => options.frames = integer()?,
                "--warmup" => options.warmup = integer()?,
                "--seed" => options.seed = integer()?,
                _ => return Err(format!("Unknown argument {key}")),
            }
        }
        if !(4..=4096).contains(&options.spp)
            || !(2..=64).contains(&options.replicas)
            || !(32..=65536).contains(&options.frames)
            || !(32..=4096).contains(&options.warmup)
        {
            return Err(
                "spp=4..4096, replicas=2..64, frames=32..65536, warmup=32..4096 required".into(),
            );
        }
        if options.width < 8
            || options.height < 4
            || options.width > 3840
            || options.height > 2160
            || !options.width.is_multiple_of(8)
            || !options.height.is_multiple_of(4)
        {
            return Err(
                "Extent must fit 3840x2160 and be a multiple of the 8x4 receiver grid".into(),
            );
        }
        if options.scenes.iter().any(|s| {
            !["uniform", "near_far", "occluded", "many", "many_strips"].contains(&s.as_str())
        }) {
            return Err("Unknown fixture".into());
        }
        Ok(options)
    }
}

fn csv(path: &Path, header: &str) -> std::io::Result<BufWriter<File>> {
    let mut file = BufWriter::new(File::create(path)?);
    writeln!(file, "{header}")?;
    Ok(file)
}

// Linear monochrome PFM (bottom-up, little endian). White emitters make this both
// radiance and luminance; no exposure, tonemapping, denoising or clipping is measured.
fn pfm(path: &Path, width: u32, height: u32, values: &[f32]) -> std::io::Result<()> {
    let mut file = BufWriter::new(File::create(path)?);
    writeln!(file, "Pf\n{width} {height}\n-1.0")?;
    for row in values.chunks_exact(width as usize).rev() {
        for v in row {
            file.write_all(&v.to_le_bytes())?;
        }
    }
    Ok(())
}

fn quantile(values: &[u64], q: f64) -> f64 {
    let mut values = values.to_vec();
    values.sort_unstable();
    values[((values.len() - 1) as f64 * q).round() as usize] as f64 / 1e6
}

fn command_output(command: &str, args: &[&str]) -> String {
    std::process::Command::new(command)
        .args(args)
        .output()
        .ok()
        .filter(|r| r.status.success())
        .map(|r| String::from_utf8_lossy(&r.stdout).trim().to_owned())
        .unwrap_or_else(|| "unavailable".into())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let o = Options::parse()?;
    if o.output.join("metrics.csv").exists() {
        return Err(
            "Output already contains results; choose a new --output to preserve evidence".into(),
        );
    }
    fs::create_dir_all(&o.output)?;
    let mut manifest = File::create(o.output.join("run.txt"))?;
    writeln!(
        manifest,
        "scope=isolated_direct_NEE_accumulation_no_shadow_ray_no_full_path\nwidth={}\nheight={}\nformal_extent={}\nspp={}\nreplicas={}\nwarmup={}\nframes={}\nseed={}\nscenes={}\nray_budget=one_area_light_sample_per_pixel_per_dispatch\nreference=closed_form_f64_rectangle_irradiance\nsecond_moment=4x4_Gauss_Legendre_checked_against_2x2\nsequence=IID_hash_and_production_ZSobol_S8_independent_scrambles\nvalidation={}\nprofile={}\nrustc={}\ncommit={}\nworking_tree={}\ncommand={}",
        o.width,
        o.height,
        o.width == 1920 && o.height == 1080,
        o.spp,
        o.replicas,
        o.warmup,
        o.frames,
        o.seed,
        o.scenes.join(","),
        std::env::var("PRIME_VK_VALIDATION").unwrap_or_default(),
        std::env::var("PRIME_PROFILE").unwrap_or_default(),
        command_output("rustc", &["--version"]),
        command_output("git", &["rev-parse", "HEAD"]),
        command_output("git", &["status", "--porcelain"]).replace('\n', ";"),
        std::env::args().collect::<Vec<_>>().join(" ")
    )?;
    let mut frames = csv(
        &o.output.join("frames.csv"),
        "scene,method,sequence,phase,round,frame,gpu_ns",
    )?;
    let mut batches = csv(
        &o.output.join("batches.csv"),
        "scene,method,sequence,phase,round,frames,host_record_submit_wait_ns",
    )?;
    let mut timings = csv(
        &o.output.join("timings.csv"),
        "scene,method,sequence,gpu_mean_ms,gpu_p50_ms,gpu_p95_ms,gpu_max_ms,sampler_bytes,emitter_bytes",
    )?;
    let mut theoretical = csv(
        &o.output.join("theory.csv"),
        "scene,receiver,reference,power_tree_variance,power_alias_variance,spatial_tree_variance,oracle_discrete_variance",
    )?;
    let mut trials = csv(
        &o.output.join("trials.csv"),
        "scene,method,sequence,replica,seed,spp,mse",
    )?;
    let mut metrics = csv(
        &o.output.join("metrics.csv"),
        "scene,method,sequence,spp,mse,mse_standard_error,variance,bias_squared,relative_rmse,iid_predicted_mse,gpu_budget_ms,mse_times_gpu_ms",
    )?;
    let mut slopes = csv(
        &o.output.join("slopes.csv"),
        "scene,method,sequence,mse_log_slope,iid_coefficient_times_ms,iid_predicted_ms_to_5pct_rmse",
    )?;
    let checkpoints: Vec<_> = (0..=12)
        .map(|p| 1u32 << p)
        .filter(|n| *n <= o.spp && (*n == 1 || n.trailing_zeros().is_multiple_of(2)))
        .chain([o.spp])
        .collect::<std::collections::BTreeSet<_>>()
        .into_iter()
        .collect();
    for name in &o.scenes {
        eprintln!("{name}: building fixture and independent reference");
        let began = Instant::now();
        let fixture = Fixture::new(name)?;
        let theory = fixture.theory();
        if theory.quadrature_relative_gap > 1e-4 {
            return Err(format!("Second-moment quadrature has not converged for {name}").into());
        }
        writeln!(
            manifest,
            "{name}.emitters={}\n{name}.pages={}\n{name}.moment_quadrature_relative_gap={:.10e}\n{name}.prepare_cpu_s={:.3}",
            fixture.emitters.len(),
            fixture.pages.len(),
            theory.quadrature_relative_gap,
            began.elapsed().as_secs_f64()
        )?;
        for r in 0..RECEIVERS {
            writeln!(
                theoretical,
                "{name},{r},{:.12e},{:.12e},{:.12e},{:.12e},{:.12e}",
                theory.reference[r],
                theory.variance[0][r],
                theory.variance[1][r],
                theory.variance[2][r],
                theory.optimal_variance[r]
            )?;
        }
        let pixels = o.width as usize * o.height as usize;
        let reference: Vec<_> = (0..pixels)
            .map(|i| {
                theory.reference[(i / o.width as usize) * 4 / o.height as usize * 8
                    + (i % o.width as usize) * 8 / o.width as usize]
            })
            .collect();
        pfm(
            &o.output.join(format!("{name}-reference.pfm")),
            o.width,
            o.height,
            &reference.iter().map(|v| *v as f32).collect::<Vec<_>>(),
        )?;
        let gpu = Gpu::new(&fixture, o.width, o.height)?;
        writeln!(manifest, "{name}.gpu={}", gpu.device_name())?;
        let mut times: [Vec<u64>; 6] = std::array::from_fn(|_| Vec::new());
        eprintln!(
            "{name}: {} lights, timing six variants at {}x{}",
            fixture.emitters.len(),
            o.width,
            o.height
        );
        for (phase, count) in [("warmup", o.warmup), ("steady", o.frames)] {
            for round in 0..count.div_ceil(BATCH) {
                for k in 0..6 {
                    // Rotate order between batches to reduce systematic clock/thermal bias.
                    let variant = (k + round as usize) % 6;
                    let (method, sequence) = (variant / 2, variant % 2);
                    let sequence_name = ["iid", "zsobol"][sequence];
                    let batch = BATCH.min(count - round * BATCH);
                    let time = gpu.run(
                        method,
                        sequence,
                        o.seed.wrapping_add(round * 7919),
                        0,
                        batch,
                        false,
                    )?;
                    writeln!(
                        batches,
                        "{name},{},{sequence_name},{phase},{round},{batch},{}",
                        METHODS[method], time.host_ns
                    )?;
                    for (i, value) in time.gpu_ns.iter().enumerate() {
                        writeln!(
                            frames,
                            "{name},{},{sequence_name},{phase},{round},{},{value}",
                            METHODS[method],
                            round * BATCH + i as u32
                        )?;
                    }
                    if phase == "steady" {
                        times[variant].extend(time.gpu_ns);
                    }
                }
            }
        }
        for (variant, time) in times.iter().enumerate() {
            let (method, sequence) = (variant / 2, variant % 2);
            let method_name = METHODS[method];
            let sequence_name = ["iid", "zsobol"][sequence];
            let mean_ms = time.iter().map(|v| *v as f64).sum::<f64>() / time.len() as f64 / 1e6;
            let sampler_bytes = if method == 1 {
                (fixture.aliases.len() + fixture.emitters.len()) * 8
            } else {
                (fixture.world.len() + fixture.pages.iter().map(|p| p.nodes.len()).sum::<usize>())
                    * 32
            };
            writeln!(
                timings,
                "{name},{method_name},{sequence_name},{mean_ms:.8},{:.8},{:.8},{:.8},{sampler_bytes},{}",
                quantile(time, 0.5),
                quantile(time, 0.95),
                quantile(time, 1.0),
                fixture.emitters.len() * 32
            )?;
            let mut errors: Vec<_> = checkpoints
                .iter()
                .map(|_| ErrorAccumulator::new(pixels))
                .collect();
            for replica in 0..o.replicas {
                let seed = o.seed.wrapping_add(replica.wrapping_mul(0x9e3779b9));
                let mut first = 0;
                for (c, &spp) in checkpoints.iter().enumerate() {
                    while first < spp {
                        let count = (spp - first).min(BATCH);
                        gpu.run(method, sequence, seed, first, count, false)?;
                        first += count;
                    }
                    let values = gpu.read(false)?;
                    let mse = errors[c].add(&values, &reference)?;
                    writeln!(
                        trials,
                        "{name},{method_name},{sequence_name},{replica},{seed},{spp},{mse:.12e}"
                    )?;
                    if replica == 0 {
                        pfm(
                            &o.output
                                .join(format!("{name}-{method_name}-{sequence_name}-{spp}.pfm")),
                            o.width,
                            o.height,
                            &values,
                        )?;
                    }
                }
            }
            let coefficient = theory.variance[method].iter().sum::<f64>() / RECEIVERS as f64;
            let mut curve = Vec::new();
            for (&spp, e) in checkpoints.iter().zip(&errors) {
                let m = e.finish(&reference)?;
                let budget = f64::from(spp) * mean_ms;
                writeln!(
                    metrics,
                    "{name},{method_name},{sequence_name},{spp},{:.12e},{:.12e},{:.12e},{:.12e},{:.8},{:.12e},{budget:.8},{:.12e}",
                    m.mse,
                    m.mse_standard_error,
                    m.variance,
                    m.bias_squared,
                    m.relative_rmse,
                    coefficient / f64::from(spp),
                    m.mse * budget
                )?;
                curve.push((spp, m.mse));
            }
            let energy = theory.reference.iter().map(|v| v * v).sum::<f64>() / RECEIVERS as f64;
            writeln!(
                slopes,
                "{name},{method_name},{sequence_name},{:.8},{:.12e},{:.8}",
                convergence_slope(&curve).unwrap(),
                coefficient * mean_ms,
                coefficient * mean_ms / (0.05 * 0.05 * energy)
            )?;
            eprintln!(
                "{name} {method_name}/{sequence_name}: GPU p50={:.3} p95={:.3} ms, MSE slope={:.3}",
                quantile(time, 0.5),
                quantile(time, 0.95),
                convergence_slope(&curve).unwrap()
            );
        }
        frames.flush()?;
        batches.flush()?;
        timings.flush()?;
        theoretical.flush()?;
        trials.flush()?;
        metrics.flush()?;
        slopes.flush()?;
    }
    writeln!(manifest, "completed=true")?;
    eprintln!("Results: {}", o.output.display());
    Ok(())
}
