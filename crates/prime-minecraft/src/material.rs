//! The fixed RGBA8 material contract. These routines know no external pack encoding.
use std::sync::OnceLock;

pub(crate) const DEFAULT_AUX: [u8; 4] = [128, 128, 0, 85];

pub(crate) fn encode(value: f64) -> u8 {
    (value * 255.).round().clamp(0., 255.) as u8
}
pub(crate) fn encode_ior(ior: f64) -> u8 {
    encode((ior - 1.) / 1.5)
}
pub(crate) fn decode_ior(value: u8) -> f32 {
    1. + 1.5 * f32::from(value) / 255.
}
pub(crate) fn encode_normal(value: f64) -> u8 {
    (128. + 127. * value).round().clamp(1., 255.) as u8
}
pub(crate) fn direction(pixel: [u8; 4]) -> [f64; 3] {
    let x = (f64::from(pixel[0]) - 128.) / 127.;
    let y = (f64::from(pixel[1]) - 128.) / 127.;
    unit([x, y, (1. - x * x - y * y).max(0.).sqrt()])
}
pub(crate) fn unit(vector: [f64; 3]) -> [f64; 3] {
    let length = vector.iter().map(|v| v * v).sum::<f64>().sqrt();
    if length == 0. {
        [0., 0., 1.]
    } else {
        vector.map(|v| v / length)
    }
}
fn lengths() -> &'static [f64; 256] {
    static LENGTHS: OnceLock<[f64; 256]> = OnceLock::new();
    LENGTHS.get_or_init(|| {
        std::array::from_fn(|i| {
            if i == 0 {
                return 1.;
            }
            if i == 255 {
                return 2. / 3.;
            }
            let alpha = (i as f64 / 255.).powi(2);
            let a = (1. - alpha * alpha).sqrt();
            (a - alpha * alpha * 0.5 * ((1. + a) / (1. - a)).ln()) / (a * a * a)
        })
    })
}
pub(crate) fn normal_length(distribution: u8) -> f64 {
    lengths()[distribution as usize]
}
pub(crate) fn distribution(length: f64) -> u8 {
    let table = lengths();
    if length >= 1. {
        return 0;
    }
    if length <= table[255] {
        return 255;
    }
    let mut lower = 0;
    let mut upper = 255;
    while upper - lower > 1 {
        let mid = (lower + upper) / 2;
        if table[mid] > length {
            lower = mid;
        } else {
            upper = mid;
        }
    }
    if table[lower] - length <= length - table[upper] {
        lower as u8
    } else {
        upper as u8
    }
}
pub(crate) fn normal_pixel(mean: [f64; 3], ior: u8) -> [u8; 4] {
    let length = mean.iter().map(|v| v * v).sum::<f64>().sqrt();
    let direction = unit(mean);
    [
        encode_normal(direction[0]),
        encode_normal(direction[1]),
        distribution(length),
        ior,
    ]
}
pub(crate) fn blend_continuous(a: [u8; 4], b: [u8; 4], progress: u32) -> [u8; 4] {
    std::array::from_fn(|i| {
        ((u32::from(a[i]) * (1000 - progress) + u32::from(b[i]) * progress + 500) / 1000) as u8
    })
}
pub(crate) fn blend_aux(a: [u8; 4], b: [u8; 4], progress: u32) -> [u8; 4] {
    let a_direction = direction(a);
    let b_direction = direction(b);
    let a_weight = normal_length(a[2]) * f64::from(1000 - progress) / 1000.;
    let b_weight = normal_length(b[2]) * f64::from(progress) / 1000.;
    normal_pixel(
        std::array::from_fn(|i| a_direction[i] * a_weight + b_direction[i] * b_weight),
        a[3],
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_ior_encoding_has_exact_defaults_and_bounded_error() {
        assert_eq!(encode_ior(1.), 0);
        assert_eq!(encode_ior(1.5), 85);
        assert_eq!(encode_ior(2.), 170);
        assert_eq!(encode_ior(2.5), 255);
        assert_eq!(decode_ior(85), 1.5);
        for i in 0..=10000 {
            let eta = 1. + 1.5 * f64::from(i) / 10000.;
            assert!((f64::from(decode_ior(encode_ior(eta))) - eta).abs() <= 1.5 / 510. + 1e-7);
        }
    }

    #[test]
    fn biased_normal_has_exact_flat_origin_and_unit_directions() {
        assert_eq!(direction(DEFAULT_AUX), [0., 0., 1.]);
        assert_eq!(normal_pixel([0., 0., 1.], 85), DEFAULT_AUX);
        for x in [-1., -0.7, 0., 0.7, 1.] {
            let d = direction([encode_normal(x), 128, 0, 85]);
            assert!((d.iter().map(|v| v * v).sum::<f64>() - 1.).abs() < 1e-12);
        }
    }

    #[test]
    fn animation_blends_normal_moments_and_keeps_current_ior() {
        let value = blend_aux([1, 128, 0, 0], [255, 128, 0, 255], 500);
        assert_eq!(value, [128, 128, 255, 0]);
        assert_eq!(blend_continuous([0; 4], [255; 4], 500), [128; 4]);
        for encoded in 0..=255 {
            assert_eq!(distribution(normal_length(encoded)), encoded);
        }
    }
}
