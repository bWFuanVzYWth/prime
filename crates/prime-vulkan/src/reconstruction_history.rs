//! Camera history uses the same anchor and top-left, unjittered UV convention as RR guides.
use prime_scene::scene::Camera;

pub(super) type Matrix = [f32; 16];
pub(super) const NEAR: f32 = 0.01;
pub(super) const FAR: f32 = 1_000_000.0;

pub(super) fn jitter(frame: u32, render_width: u32, output_width: u32) -> [f32; 2] {
    let ratio = output_width as f32 / render_width as f32;
    let phases = (8.0 * ratio * ratio).ceil().clamp(8.0, 256.0) as u32;
    let radical = |mut index: u32, base: u32| {
        let mut weight = 1.0;
        let mut value = 0.0;
        while index != 0 {
            weight /= base as f32;
            value += (index % base) as f32 * weight;
            index /= base;
        }
        value - 0.5
    };
    let index = frame % phases + 1;
    [radical(index, 2), radical(index, 3)]
}

fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a.into_iter().zip(b).map(|(a, b)| a * b).sum()
}

pub(super) fn multiply(a: Matrix, b: Matrix) -> Matrix {
    std::array::from_fn(|i| (0..4).map(|k| a[(i / 4) * 4 + k] * b[k * 4 + i % 4]).sum())
}

/// Streamline uses row vectors and row-major float4x4 storage.
pub(super) fn world_to_view(camera: Camera) -> Matrix {
    let [r, u, f, p] = [camera.right, camera.up, camera.forward, camera.position];
    [
        r[0],
        u[0],
        f[0],
        0.0,
        r[1],
        u[1],
        f[1],
        0.0,
        r[2],
        u[2],
        f[2],
        0.0,
        -dot(p, r),
        -dot(p, u),
        -dot(p, f),
        1.0,
    ]
}

pub(super) fn view_to_world(camera: Camera) -> Matrix {
    let [r, u, f, p] = [camera.right, camera.up, camera.forward, camera.position];
    [
        r[0], r[1], r[2], 0.0, u[0], u[1], u[2], 0.0, f[0], f[1], f[2], 0.0, p[0], p[1], p[2], 1.0,
    ]
}

pub(super) fn projection(camera: Camera, aspect: f32) -> (Matrix, Matrix) {
    let y = 1.0 / (camera.vertical_fov_radians * 0.5).tan();
    let x = y / aspect;
    let a = FAR / (FAR - NEAR);
    let b = -NEAR * a;
    (
        [
            x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, a, 1.0, 0.0, 0.0, b, 0.0,
        ],
        [
            1.0 / x,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0 / y,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0 / b,
            0.0,
            0.0,
            1.0,
            -a / b,
        ],
    )
}

pub(super) fn clip_to_previous(current: Camera, previous: Camera, aspect: f32) -> Matrix {
    multiply(
        multiply(
            multiply(projection(current, aspect).1, view_to_world(current)),
            world_to_view(previous),
        ),
        projection(previous, aspect).0,
    )
}

pub(super) fn rebase(mut camera: Camera, old_anchor: [f64; 3], anchor: [f64; 3]) -> Camera {
    for (i, value) in camera.position.iter_mut().enumerate() {
        *value = (f64::from(*value) + old_anchor[i] - anchor[i]) as f32;
    }
    camera
}

pub(super) fn camera_cut(current: Camera, previous: Camera) -> bool {
    current
        .position
        .into_iter()
        .zip(previous.position)
        .map(|(a, b)| (a - b).powi(2))
        .sum::<f32>()
        > 256.0
        || dot(current.forward, previous.forward) < 0.5
        || (current.vertical_fov_radians - previous.vertical_fov_radians).abs() > 0.2
}

#[cfg(test)]
mod tests {
    use super::*;
    fn camera() -> Camera {
        Camera {
            position: [3.0, 7.0, 2.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            forward: [0.0, 0.0, -1.0],
            vertical_fov_radians: 1.0,
        }
    }
    fn transform(p: [f32; 4], m: Matrix) -> [f32; 4] {
        std::array::from_fn(|i| (0..4).map(|j| p[j] * m[j * 4 + i]).sum())
    }
    fn close(a: f32, b: f32) {
        assert!((a - b).abs() < 2e-4, "{a} != {b}");
    }
    #[test]
    fn perspective_and_camera_round_trip() {
        let c = camera();
        let (projection, inverse) = projection(c, 16.0 / 9.0);
        for matrix in [
            multiply(projection, inverse),
            multiply(world_to_view(c), view_to_world(c)),
        ] {
            for (i, value) in matrix.into_iter().enumerate() {
                close(value, f32::from(i / 4 == i % 4));
            }
        }
        let view = transform([4.0, 9.0, -5.0, 1.0], world_to_view(c));
        assert_eq!(view, [1.0, 2.0, 7.0, 1.0]);
    }
    #[test]
    fn reprojection_matches_direct_previous_projection_and_uv_sign() {
        let previous = camera();
        let mut current = previous;
        current.position[0] += 0.25;
        let p = [3.0, 7.0, -5.0, 1.0];
        let projection = projection(current, 16.0 / 9.0).0;
        let now = transform(p, multiply(world_to_view(current), projection));
        let expected = transform(p, multiply(world_to_view(previous), projection));
        let actual = transform(now, clip_to_previous(current, previous, 16.0 / 9.0));
        for i in 0..4 {
            close(actual[i], expected[i]);
        }
        assert!(actual[0] / actual[3] - now[0] / now[3] > 0.0);
    }
    #[test]
    fn anchor_change_is_not_camera_motion_and_cuts_are_explicit() {
        let old = camera();
        let rebased = rebase(old, [1_000_000.0, 0.0, 0.0], [1_000_064.0, 0.0, 0.0]);
        assert_eq!(rebased.position[0], -61.0);
        assert!(!camera_cut(rebased, rebased));
        assert!(camera_cut(old, rebased));
        let mut rotated = old;
        rotated.forward = [0.0, 0.0, 1.0];
        assert!(camera_cut(rotated, old));
    }
    #[test]
    fn jitter_is_bounded_deterministic_and_scales_phase_count() {
        for frame in 0..256 {
            assert!(
                jitter(frame, 960, 1920)
                    .into_iter()
                    .all(|v| (-0.5..0.5).contains(&v))
            );
            assert_eq!(jitter(frame, 960, 1920), jitter(frame + 32, 960, 1920));
        }
        assert_ne!(jitter(0, 960, 1920), jitter(8, 960, 1920));
        assert_eq!(jitter(0, 1920, 1920), jitter(8, 1920, 1920));
    }
}
