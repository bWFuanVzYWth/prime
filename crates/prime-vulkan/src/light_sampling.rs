//! Opt-in, windowless direct-light estimator experiment. Not a replacement renderer.
//! Timing covers a 1-spp NEE/accumulation dispatch, not a complete game frame.
mod fixture;
mod gpu;
pub use fixture::{Fixture, METHODS, RECEIVERS, Theory, receiver};
pub use gpu::{BATCH, BatchTime, Gpu};

/// Linear image error over independent seeds. Pixel correlations (notably Z-Sobol)
/// are preserved: uncertainty is measured across seed-level MSEs, not across pixels.
pub struct ErrorAccumulator {
    sums: Vec<f64>,
    squares: Vec<f64>,
    mse: Vec<f64>,
}

#[derive(Debug)]
pub struct ErrorMetrics {
    pub mse: f64,
    pub mse_standard_error: f64,
    pub variance: f64,
    /// Unbiased estimate; may be negative due to finite replicate count. Do not clamp it.
    pub bias_squared: f64,
    pub relative_rmse: f64,
}

impl ErrorAccumulator {
    pub fn new(pixels: usize) -> Self {
        Self {
            sums: vec![0.0; pixels],
            squares: vec![0.0; pixels],
            mse: Vec::new(),
        }
    }

    pub fn add(&mut self, values: &[f32], reference: &[f64]) -> Result<f64, String> {
        if values.is_empty()
            || values.len() != self.sums.len()
            || reference.len() != values.len()
            || values.iter().any(|v| !v.is_finite())
            || reference.iter().any(|v| !v.is_finite())
        {
            return Err("Non-finite radiance or mismatched diagnostic extent".into());
        }
        let mut squared_error = 0.0;
        for (i, (&v, &r)) in values.iter().zip(reference).enumerate() {
            // Center on the reference before moment accumulation to avoid cancellation.
            let difference = f64::from(v) - r;
            self.sums[i] += difference;
            self.squares[i] += difference * difference;
            squared_error += difference * difference;
        }
        let mse = squared_error / values.len() as f64;
        self.mse.push(mse);
        Ok(mse)
    }

    pub fn finish(&self, reference: &[f64]) -> Result<ErrorMetrics, String> {
        if self.mse.len() < 2 || reference.len() != self.sums.len() || reference.is_empty() {
            return Err(
                "Need at least two independent seeds and a nonempty matching reference".into(),
            );
        }
        let n = self.mse.len() as f64;
        let pixels = reference.len() as f64;
        let mse = self.mse.iter().sum::<f64>() / n;
        let variance = self
            .squares
            .iter()
            .zip(&self.sums)
            .map(|(s, m)| (s - m * m / n).max(0.0) / (n - 1.0))
            .sum::<f64>()
            / pixels;
        let energy = reference.iter().map(|v| v * v).sum::<f64>() / pixels;
        if energy == 0.0 {
            return Err("Relative RMSE requires nonzero reference energy".into());
        }
        Ok(ErrorMetrics {
            mse,
            mse_standard_error: (self.mse.iter().map(|v| (v - mse).powi(2)).sum::<f64>()
                / (n * (n - 1.0)))
                .sqrt(),
            variance,
            bias_squared: mse - variance,
            relative_rmse: (mse / energy).sqrt(),
        })
    }
}

/// Least-squares log(MSE) / log(spp) slope. -1 is IID variance, not a QMC guarantee.
pub fn convergence_slope(points: &[(u32, f64)]) -> Option<f64> {
    if points.len() < 2
        || points
            .iter()
            .any(|&(n, mse)| n == 0 || mse <= 0.0 || !mse.is_finite())
    {
        return None;
    }
    let x = points.iter().map(|p| f64::from(p.0).ln()).sum::<f64>() / points.len() as f64;
    let y = points.iter().map(|p| p.1.ln()).sum::<f64>() / points.len() as f64;
    let denominator = points
        .iter()
        .map(|p| (f64::from(p.0).ln() - x).powi(2))
        .sum::<f64>();
    (denominator > 0.0).then(|| {
        points
            .iter()
            .map(|p| (f64::from(p.0).ln() - x) * (p.1.ln() - y))
            .sum::<f64>()
            / denominator
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn moments_separate_bias_noise_and_convergence() {
        let mut a = ErrorAccumulator::new(2);
        a.add(&[1.0, 3.0], &[2.0, 2.0]).unwrap();
        a.add(&[3.0, 1.0], &[2.0, 2.0]).unwrap();
        let m = a.finish(&[2.0, 2.0]).unwrap();
        assert_eq!(m.mse, 1.0);
        assert_eq!(m.variance, 2.0);
        assert_eq!(m.bias_squared, -1.0);
        assert_eq!(m.mse_standard_error, 0.0);
        assert_eq!(m.relative_rmse, 0.5);
        assert!(
            (convergence_slope(&[(1, 4.0), (4, 1.0), (16, 0.25)]).unwrap() + 1.0).abs() < 1e-12
        );
        assert!(a.add(&[f32::NAN, 1.0], &[2.0, 2.0]).is_err());
    }

    #[test]
    #[ignore = "requires a Vulkan GPU; background sampling/PDF and convergence contracts"]
    fn gpu_sampling_pdf_energy_and_iid_variance() {
        let fixture = Fixture::new("near_far").unwrap();
        let theory = fixture.theory();
        let gpu = Gpu::new(&fixture, 128, 64).unwrap();
        let reference: Vec<_> = (0..8192)
            .map(|i| theory.reference[(i / 128) / 16 * 8 + (i % 128) / 16])
            .collect();
        for method in 0..3 {
            gpu.run(method, 0, 73, 0, 1, true).unwrap();
            let info = gpu.read(true).unwrap();
            let probabilities: Vec<_> = (0..RECEIVERS)
                .map(|r| fixture.probabilities(method, receiver(r)))
                .collect();
            for i in 0..8192 {
                let light = info[i].to_bits() as usize;
                let r = (i / 128) / 16 * 8 + (i % 128) / 16;
                let expected = probabilities[r][light];
                assert!((f64::from(info[8192 + i]) / expected - 1.0).abs() < 2e-5);
            }
            for spp in [16, 64] {
                let mut errors = ErrorAccumulator::new(8192);
                let mut mean_error = 0.0;
                for seed in 0..8 {
                    for first in (0..spp).step_by(BATCH as usize) {
                        gpu.run(method, 0, seed + 7919, first, BATCH.min(spp - first), false)
                            .unwrap();
                    }
                    let pixels = gpu.read(false).unwrap();
                    mean_error += pixels
                        .iter()
                        .zip(&reference)
                        .map(|(&v, &r)| f64::from(v) - r)
                        .sum::<f64>()
                        / 8192.0
                        / 8.0;
                    errors.add(&pixels, &reference).unwrap();
                }
                let m = errors.finish(&reference).unwrap();
                let predicted =
                    theory.variance[method].iter().sum::<f64>() / RECEIVERS as f64 / f64::from(spp);
                assert!(
                    (m.mse / predicted - 1.0).abs() < 0.15,
                    "{} at {spp}: {m:?}, predicted={predicted}",
                    METHODS[method]
                );
                // IID pixel/seed ensemble mean must preserve reference energy (six SE).
                assert!(mean_error.abs() < 6.0 * (predicted / (8192.0 * 8.0)).sqrt());
            }
        }
    }
}
