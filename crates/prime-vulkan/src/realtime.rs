//! Real-time scratch and byte-exact push contracts. All addresses are GPU-only borrows.
use super::{Buffer, Context, PrimeDrtParameters};
use prime_scene::scene::Camera;
use prime_scene::settings::{DiagnosticView, RenderSettings};
use std::sync::Arc;

pub(super) const PRIMARY_VARIANT: [usize; 6] = [0, 1, 2, 2, 3, 3];
pub(super) const PRIMARY_FEATURES: [[u32; 3]; 4] = [[0, 0, 0], [1, 0, 0], [2, 0, 0], [2, 0, 1]];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct ScratchLayout {
    optical: u64,
    prefix: u64,
    tail: u64,
    bytes: u64,
    diagnostic_bytes: u64,
}

impl ScratchLayout {
    fn new(pixels: u64) -> Result<Self, String> {
        // Slang's SoA element index is `plane * count + pixel`, with seven common planes.
        if pixels == 0 || pixels > (u64::from(u32::MAX) + 1) / 7 {
            return Err("Real-time scratch pixel count is outside shader indexing range".into());
        }
        let bytes = pixels
            .checked_mul(176)
            .ok_or("Real-time scratch size overflow")?;
        Ok(Self {
            optical: pixels * 112,
            prefix: pixels * 144,
            tail: pixels * 160,
            bytes,
            diagnostic_bytes: pixels * 16,
        })
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(super) struct Addresses {
    pub common: u64,
    pub optical: u64,
    pub prefix: u64,
    pub tail: u64,
    pub diagnostic: u64,
}

pub(super) struct Scratch {
    pub extent: [u32; 2],
    storage: Buffer,
    diagnostic: Option<Buffer>,
    layout: ScratchLayout,
}

impl Scratch {
    pub fn new(context: &Arc<Context>, extent: [u32; 2]) -> Result<Self, String> {
        let pixels = context.render_extent(extent[0], extent[1])?.pixels();
        let layout = ScratchLayout::new(pixels)?;
        let storage = Buffer::new_address(context, layout.bytes)?;
        Ok(Self {
            extent,
            storage,
            diagnostic: None,
            layout,
        })
    }

    pub fn set_diagnostic(&mut self, context: &Arc<Context>, needed: bool) -> Result<(), String> {
        if needed && self.diagnostic.is_none() {
            self.diagnostic = Some(Buffer::new_address(context, self.layout.diagnostic_bytes)?);
        } else if !needed {
            self.diagnostic = None;
        }
        Ok(())
    }

    pub fn addresses(&self) -> Addresses {
        let common = self.storage.address();
        Addresses {
            common,
            optical: common + self.layout.optical,
            prefix: common + self.layout.prefix,
            tail: common + self.layout.tail,
            diagnostic: self.diagnostic.as_ref().map_or(0, Buffer::address),
        }
    }
}

fn u32s(bytes: &mut [u8], values: &[u32]) {
    for (dst, value) in bytes.as_chunks_mut::<4>().0.iter_mut().zip(values) {
        dst.copy_from_slice(&value.to_le_bytes());
    }
}

fn f32s(bytes: &mut [u8], values: &[f32]) {
    for (dst, value) in bytes.as_chunks_mut::<4>().0.iter_mut().zip(values) {
        dst.copy_from_slice(&value.to_le_bytes());
    }
}

fn addresses(bytes: &mut [u8], values: &[u64]) {
    for (dst, value) in bytes.as_chunks_mut::<8>().0.iter_mut().zip(values) {
        dst.copy_from_slice(&value.to_le_bytes());
    }
}

pub(super) struct PushInputs {
    pub camera: Camera,
    pub input: [u32; 2],
    pub output: [u32; 2],
    pub sequence: u32,
    pub sobol_r: u32,
    pub settings: RenderSettings,
    pub display: PrimeDrtParameters,
    pub addresses: Addresses,
    pub jitter: Option<[f32; 2]>,
    pub bottom_up: bool,
}

impl PushInputs {
    fn sampling(&self) -> [u32; 4] {
        [
            self.input[0],
            self.input[1],
            self.sequence,
            self.settings.seed,
        ]
    }

    pub fn primary(&self) -> [u8; 128] {
        let mut push = [0; 128];
        let camera = self.camera;
        f32s(
            &mut push[..64],
            &[
                camera.position[0],
                camera.position[1],
                camera.position[2],
                (camera.vertical_fov_radians * 0.5).tan(),
                camera.forward[0],
                camera.forward[1],
                camera.forward[2],
                self.output[0] as f32 / self.output[1] as f32,
                camera.right[0],
                camera.right[1],
                camera.right[2],
                self.settings.sun,
                camera.up[0],
                camera.up[1],
                camera.up[2],
                self.settings.sky,
            ],
        );
        u32s(&mut push[64..80], &self.sampling());
        u32s(
            &mut push[80..96],
            &[
                self.sobol_r,
                self.settings.bounces,
                u32::from(self.addresses.diagnostic != 0),
                0,
            ],
        );
        addresses(
            &mut push[96..],
            &[
                self.addresses.common,
                self.addresses.optical,
                self.addresses.prefix,
                self.addresses.diagnostic,
            ],
        );
        push
    }

    pub fn transport(&self, surface_footprint: bool) -> [u8; 80] {
        let mut push = [0; 80];
        u32s(&mut push[..16], &self.sampling());
        u32s(
            &mut push[16..32],
            &[self.sobol_r, self.settings.bounces, 0, 0],
        );
        let spread = if surface_footprint {
            2.0 * (self.camera.vertical_fov_radians * 0.5).tan() / self.input[1] as f32
        } else {
            0.0
        };
        f32s(
            &mut push[32..48],
            &[self.settings.sun, self.settings.sky, spread, 0.0],
        );
        addresses(
            &mut push[48..],
            &[
                self.addresses.common,
                self.addresses.optical,
                self.addresses.tail,
                0,
            ],
        );
        push
    }

    pub fn post(&self) -> [u8; 112] {
        let mut push = [0; 112];
        f32s(&mut push[..16], &self.display.values[..4]);
        f32s(
            &mut push[16..32],
            &[
                self.display.values[4],
                self.settings.depth_range,
                self.settings.sun,
                0.0,
            ],
        );
        u32s(&mut push[32..48], &self.sampling());
        u32s(
            &mut push[48..64],
            &[
                self.output[0],
                self.output[1],
                u32::from(self.bottom_up),
                self.settings.view as u32,
            ],
        );
        let jitter = self.jitter.unwrap_or([0.0; 2]);
        f32s(&mut push[64..72], &jitter);
        u32s(
            &mut push[72..80],
            &[self.sobol_r, u32::from(self.jitter.is_some())],
        );
        addresses(
            &mut push[80..],
            &[
                self.addresses.prefix,
                self.addresses.tail,
                self.addresses.diagnostic,
                0,
            ],
        );
        push
    }
}

pub(super) fn needs_diagnostic(view: DiagnosticView) -> bool {
    matches!(view, DiagnosticView::LinearDepth | DiagnosticView::Normal)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inputs() -> PushInputs {
        PushInputs {
            camera: Camera {
                position: [1.0, 2.0, 3.0],
                forward: [0.0, 0.0, -1.0],
                right: [1.0, 0.0, 0.0],
                up: [0.0, 1.0, 0.0],
                vertical_fov_radians: 1.0,
            },
            input: [960, 540],
            output: [1920, 1080],
            sequence: 257,
            sobol_r: 10,
            settings: RenderSettings {
                seed: 123,
                bounces: 12,
                ..Default::default()
            },
            display: super::super::PrimeDrtSettings::default()
                .prepare(1.0)
                .unwrap(),
            addresses: Addresses {
                common: 0x1234_0000_0010,
                optical: 0x2345_0000_0020,
                prefix: 0x3456_0000_0030,
                tail: 0x4567_0000_0040,
                diagnostic: 0x5678_0000_0050,
            },
            jitter: Some([-0.25, 0.125]),
            bottom_up: true,
        }
    }

    fn word(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    }
    fn address(bytes: &[u8], offset: usize) -> u64 {
        u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap())
    }

    #[test]
    fn stage_push_contracts_keep_addresses_and_sample_identity_separate() {
        let input = inputs();
        let primary = input.primary();
        let transport = input.transport(true);
        let post = input.post();
        assert_eq!((primary.len(), transport.len(), post.len()), (128, 80, 112));
        for (push, start) in [(&primary[..], 64), (&transport[..], 0), (&post[..], 32)] {
            assert_eq!(
                (0..4)
                    .map(|i| word(push, start + 4 * i))
                    .collect::<Vec<_>>(),
                vec![960, 540, 257, 123]
            );
        }
        assert_eq!(address(&primary, 96), input.addresses.common);
        assert_eq!(address(&primary, 104), input.addresses.optical);
        assert_eq!(address(&primary, 112), input.addresses.prefix);
        assert_eq!(address(&primary, 120), input.addresses.diagnostic);
        assert_eq!(word(&primary, 88), 1);
        assert_eq!(word(&primary, 92), 0);
        let mut without_diagnostic = inputs();
        without_diagnostic.addresses.diagnostic = 0;
        assert_eq!(word(&without_diagnostic.primary(), 88), 0);
        assert_eq!(address(&without_diagnostic.primary(), 120), 0);
        assert_eq!(address(&transport, 48), input.addresses.common);
        assert_eq!(address(&transport, 56), input.addresses.optical);
        assert_eq!(address(&transport, 64), input.addresses.tail);
        assert_eq!(address(&transport, 72), 0);
        assert_eq!(address(&post, 80), input.addresses.prefix);
        assert_eq!(address(&post, 88), input.addresses.tail);
        assert_eq!(address(&post, 96), input.addresses.diagnostic);
        assert_eq!(address(&post, 104), 0);
        assert_eq!(f32::from_bits(word(&post, 64)), -0.25);
        assert_eq!(f32::from_bits(word(&post, 68)), 0.125);
        assert_eq!(word(&post, 72), 10);
        assert_eq!(word(&post, 76), 1);
        assert_eq!(word(&post, 56), 1);
        assert_eq!(
            f32::from_bits(word(&transport, 40)),
            2.0 * 0.5f32.tan() / 540.0
        );
        assert_eq!(word(&input.transport(false), 40), 0);
        let raw = PushInputs {
            jitter: None,
            ..input
        }
        .post();
        assert_eq!((word(&raw, 64), word(&raw, 68), word(&raw, 76)), (0, 0, 0));
    }

    #[test]
    fn scratch_ranges_are_disjoint_aligned_and_fit_shader_plane_indexing() {
        let maximum = (u64::from(u32::MAX) + 1) / 7;
        for pixels in [1, 17 * 9, 960 * 540, 1920 * 1080, maximum] {
            let layout = ScratchLayout::new(pixels).unwrap();
            assert_eq!(
                [layout.optical, layout.prefix, layout.tail, layout.bytes],
                [112 * pixels, 144 * pixels, 160 * pixels, 176 * pixels]
            );
            assert_eq!(layout.diagnostic_bytes, 16 * pixels);
            assert!(
                [layout.optical, layout.prefix, layout.tail, layout.bytes]
                    .into_iter()
                    .all(|offset| offset.is_multiple_of(16))
            );
        }
        assert!(ScratchLayout::new(0).is_err());
        assert!(ScratchLayout::new(maximum + 1).is_err());
        assert!(ScratchLayout::new(u64::from(u32::MAX) + 1).is_err());
        assert_eq!(
            PRIMARY_VARIANT.map(|index| PRIMARY_FEATURES[index]),
            [
                [0, 0, 0],
                [1, 0, 0],
                [2, 0, 0],
                [2, 0, 0],
                [2, 0, 1],
                [2, 0, 1]
            ]
        );
    }
}
