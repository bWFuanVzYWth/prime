//! Resource-local sprite identity. Atlas UV interpretation and animation stay in Rust.
#[cfg(test)]
use crate::wire::Reader;
use prime_scene::{SourceScene, Texture, TextureLevel, TextureSampling};
use std::sync::Arc;

#[derive(PartialEq)]
pub(crate) struct Image {
    pub width: u32,
    pub height: u32,
    pub pixels: Arc<[u8]>,
}
#[derive(PartialEq)]
pub(crate) struct Sprite {
    pub name: String,
    pub bounds: [f32; 4],
    pub extent: [u32; 2],
    pub images: Vec<Image>,
    pub frames: Vec<(u32, u32)>,
    /// Prepared inclusive duration prefix; final endpoint is the validated cycle length.
    pub frame_ends: Vec<u32>,
    /// All actual sequence-frame windows, shared across per-tick texture views.
    pub coverage_frames: Arc<[[u32; 2]]>,
    pub interpolate: bool,
    pub material: Option<crate::labpbr::Material>,
}
pub(crate) fn texture(id: u32) -> u32 {
    if id == 0 { 1 } else { 0x4000_0000 + id }
}
fn frame_ends(frames: &[(u32, u32)]) -> Vec<u32> {
    let mut total = 0;
    frames
        .iter()
        .map(|&(_, duration)| {
            total += duration;
            total
        })
        .collect()
}
impl Sprite {
    pub fn from_typed(
        value: &prime_abi::PrimeMcSprite,
        source: &prime_abi::minecraft::Resources<'_>,
    ) -> Result<(u32, Self), String> {
        use prime_abi::minecraft::{finite, range, string};
        let bounds = value.bounds;
        let extent = value.extent;
        finite(&bounds)?;
        if value.id == 0
            || value.id >= 0x4000_0000
            || extent.iter().any(|&v| v == 0 || v > 16384)
            || bounds[0] < 0.
            || bounds[1] < 0.
            || bounds[2] > 1.
            || bounds[3] > 1.
            || bounds[2] <= bounds[0]
            || bounds[3] <= bounds[1]
            || value.interpolate > 1
        {
            return Err("invalid source sprite".into());
        }
        let descriptors = range(source.images(), value.images)?;
        if descriptors.is_empty() || descriptors.len() > 15 {
            return Err("invalid source mip count".into());
        }
        let mut images: Vec<Image> = Vec::with_capacity(descriptors.len());
        for (mip, desc) in descriptors.iter().enumerate() {
            let image = Image::from_typed(desc, source.bytes(), mip == 0)?;
            if mip > 0
                && (image.width != (images[0].width >> mip).max(1)
                    || image.height != (images[0].height >> mip).max(1)
                    || extent.iter().any(|&v| v >> mip == 0))
            {
                return Err("invalid source mip progression".into());
            }
            images.push(image);
        }
        let base = &images[0];
        let descriptors = range(source.frames(), value.frames)?;
        if !base.width.is_multiple_of(extent[0])
            || !base.height.is_multiple_of(extent[1])
            || !descriptors.is_empty() && base.pixels.is_empty()
        {
            return Err("invalid source animation image".into());
        }
        let capacity = (base.width / extent[0]) * (base.height / extent[1]);
        let mut total = 0_u32;
        let mut frames = Vec::with_capacity(descriptors.len());
        for f in descriptors {
            total = total
                .checked_add(f.duration)
                .ok_or("animation duration overflow")?;
            if f.frame >= capacity || f.duration == 0 {
                return Err("invalid source animation frame".into());
            }
            frames.push((f.frame, f.duration));
        }
        let row = base.width / extent[0];
        let mut coverage: Vec<_> = frames
            .iter()
            .map(|&(f, _)| [(f % row) * extent[0], (f / row) * extent[1]])
            .collect();
        coverage.sort_unstable();
        coverage.dedup();
        let mut sprite = Self {
            name: string(source.bytes(), value.name)?,
            bounds,
            extent,
            images,
            frame_ends: frame_ends(&frames),
            frames,
            coverage_frames: coverage.into(),
            interpolate: value.interpolate != 0,
            material: None,
        };
        let image = |id: u32| -> Result<Option<Image>, String> {
            if id == u32::MAX {
                return Ok(None);
            }
            Image::from_typed(
                source
                    .images()
                    .get(id as usize)
                    .ok_or("undefined material image")?,
                source.bytes(),
                false,
            )
            .map(Some)
        };
        let normal = image(value.normal_image)?;
        let specular = image(value.specular_image)?;
        if normal.is_some() || specular.is_some() {
            sprite.material = Some(crate::labpbr::Material::from_images(
                &sprite, normal, specular,
            ));
        }
        Ok((value.id, sprite))
    }
    pub fn reference(&self, scene: &SourceScene) -> Result<[f32; 4], String> {
        let base = &self.images[0];
        let [w, h] = self.extent;
        let (pixels, stride, x, y) = if base.pixels.is_empty() {
            let atlas = scene.texture(1).ok_or("sprite atlas missing")?;
            (
                &atlas.pixels,
                atlas.width,
                (self.bounds[0] * atlas.width as f32).round() as u32,
                (self.bounds[1] * atlas.height as f32).round() as u32,
            )
        } else {
            let first = self.frames.first().map_or(0, |&(f, _)| f);
            let row = base.width / w;
            (
                &base.pixels,
                base.width,
                (first % row) * w,
                (first / row) * h,
            )
        };
        // Stable source reference, matching the previous translator's UV-bounds midpoint.
        // Border paint must not turn a clear glass body into a colored absorbing medium.
        let at = (((y + h / 2) * stride + x + w / 2) * 4) as usize;
        let pixel = pixels
            .get(at..at + 4)
            .ok_or("sprite exceeds source atlas")?;
        Ok(std::array::from_fn(|i| f32::from(pixel[i]) / 255.))
    }

    #[cfg(test)]
    pub fn read(r: &mut Reader<'_>) -> Result<(u32, Self), String> {
        let id = r.u32()?;
        let name = r.string()?;
        let bounds = [r.f32()?, r.f32()?, r.f32()?, r.f32()?];
        let extent = [r.u32()?, r.u32()?];
        if id == 0
            || id >= 0x4000_0000
            || extent.iter().any(|&v| v == 0 || v > 16384)
            || bounds[0] < 0.
            || bounds[1] < 0.
            || bounds[2] > 1.
            || bounds[3] > 1.
            || bounds[2] <= bounds[0]
            || bounds[3] <= bounds[1]
        {
            return Err("invalid source sprite".into());
        }
        let count = r.count(12)?;
        if count == 0 || count > 15 {
            return Err("invalid source mip count".into());
        }
        let mut images: Vec<Image> = Vec::with_capacity(count);
        for mip in 0..count {
            let width = r.u32()?;
            let height = r.u32()?;
            let count = r.count(4)?;
            if width == 0
                || height == 0
                || width > 16384
                || height > 16384
                || count != 0 && count as u64 != u64::from(width) * u64::from(height)
                || count == 0 && mip != 0
            {
                return Err("invalid source mip image".into());
            }
            let pixels: Arc<[u8]> = r
                .u32s(count)?
                .into_iter()
                .flat_map(u32::to_le_bytes)
                .collect();
            if mip > 0
                && (width != (images[0].width >> mip).max(1)
                    || height != (images[0].height >> mip).max(1)
                    || extent.iter().any(|&v| v >> mip == 0))
            {
                return Err("invalid source mip progression".into());
            }
            images.push(Image {
                width,
                height,
                pixels,
            });
        }
        let interpolate = r.u32()?;
        let count = r.count(8)?;
        let mut frames = Vec::with_capacity(count);
        let base = &images[0];
        if !base.width.is_multiple_of(extent[0])
            || !base.height.is_multiple_of(extent[1])
            || interpolate > 1
            || count != 0 && base.pixels.is_empty()
        {
            return Err("invalid source animation image".into());
        }
        let capacity = (base.width / extent[0]) * (base.height / extent[1]);
        let mut total = 0_u32;
        for _ in 0..count {
            let frame = r.u32()?;
            let duration = r.u32()?;
            total = total
                .checked_add(duration)
                .ok_or("animation duration overflow")?;
            if frame >= capacity || duration == 0 {
                return Err("invalid source animation frame".into());
            }
            frames.push((frame, duration));
        }
        let row = base.width / extent[0];
        let mut coverage_frames: Vec<_> = frames
            .iter()
            .map(|&(frame, _)| [(frame % row) * extent[0], (frame / row) * extent[1]])
            .collect();
        coverage_frames.sort_unstable();
        coverage_frames.dedup();
        Ok((
            id,
            Self {
                name,
                bounds,
                extent,
                images,
                frame_ends: frame_ends(&frames),
                frames,
                coverage_frames: coverage_frames.into(),
                interpolate: interpolate != 0,
                material: None,
            },
        ))
    }
    /// Normalize only a few representable endpoint steps, never an arbitrary UV margin.
    /// Intentional cross-sprite UVs retain their original atlas interpretation at the caller.
    pub fn local(&self, uvs: [[f32; 2]; 4]) -> Option<[[f32; 2]; 4]> {
        let mut result = uvs;
        for uv in &mut result {
            for (a, value) in uv.iter_mut().enumerate() {
                let lo = self.bounds[a];
                let hi = self.bounds[a + 2];
                if *value < lo {
                    if *value < lo.next_down().next_down().next_down().next_down() {
                        return None;
                    }
                    *value = lo;
                } else if *value > hi {
                    if *value > hi.next_up().next_up().next_up().next_up() {
                        return None;
                    }
                    *value = hi;
                }
                *value = (*value - lo) / (hi - lo);
            }
        }
        Some(result)
    }
    fn phase(&self, tick: u64) -> (u32, u32, f32) {
        let Some(&total) = self.frame_ends.last() else {
            return (0, 0, 0.0);
        };
        let at = (tick % u64::from(total)) as u32;
        let i = self.frame_ends.partition_point(|&end| end <= at);
        let start = if i == 0 { 0 } else { self.frame_ends[i - 1] };
        let (frame, duration) = self.frames[i];
        let blend = if self.interpolate {
            (((at - start) as f32 / duration as f32) * 1000.) as u32 as f32 / 1000.
        } else {
            0.
        };
        (frame, self.frames[(i + 1) % self.frames.len()].0, blend)
    }
    /// Only compare ticks within this immutable catalog sprite. Resource replacement
    /// constructs and validates new images independently, even at the same clock phase.
    pub fn same_phase(&self, previous: u64, tick: u64) -> bool {
        self.phase(previous) == self.phase(tick)
    }
    pub fn image(&self, tick: u64, scene: &SourceScene) -> Result<Texture, String> {
        let base = &self.images[0];
        let [w, h] = self.extent;
        let (frame, next, blend) = self.phase(tick);
        let row = base.width / w;
        let mut texture = if base.pixels.is_empty() {
            let atlas = scene
                .texture(1)
                .ok_or("sprite atlas has not been captured")?;
            let x = (self.bounds[0] * atlas.width as f32).round() as u32;
            let y = (self.bounds[1] * atlas.height as f32).round() as u32;
            if (self.bounds[2] * atlas.width as f32).round() as u32 != x + w
                || (self.bounds[3] * atlas.height as f32).round() as u32 != y + h
            {
                return Err("sprite bounds disagree with captured atlas extent".into());
            }
            Texture {
                region: Some([x, y, w, h]),
                sampling: None,
                material: None,
                ..atlas.clone()
            }
        } else {
            Texture {
                width: base.width,
                height: base.height,
                pixels: base.pixels.clone(),
                region: Some([(frame % row) * w, (frame / row) * h, w, h]),
                sampling: None,
                material: None,
            }
        };
        if self.images.len() > 1 || !self.frames.is_empty() {
            let region = texture.region.unwrap();
            let next = if base.pixels.is_empty() {
                [region[0], region[1]]
            } else {
                [(next % row) * w, (next / row) * h]
            };
            let levels = self
                .images
                .iter()
                .enumerate()
                .skip(1)
                .map(|(m, image)| {
                    let extent = self.extent.map(|v| (v >> m).max(1));
                    let [w, h] = extent;
                    TextureLevel {
                        width: image.width,
                        height: image.height,
                        pixels: image.pixels.clone(),
                        region: [(frame % row) * w, (frame / row) * h, w, h],
                        // Only mip-0 borrows the atlas. Higher static mips are sprite-local.
                        next: if base.pixels.is_empty() {
                            [0; 2]
                        } else {
                            next.map(|v| v >> m)
                        },
                    }
                })
                .collect();
            texture.sampling = Some(Arc::new(TextureSampling {
                levels,
                next,
                blend,
                coverage_frames: self.coverage_frames.clone(),
            }));
        }
        texture.validate()?;
        texture.material = self
            .material
            .as_ref()
            .map(|m| m.image(self, frame, next, blend));
        texture.validate()?;
        Ok(texture)
    }
    pub fn emission_maximum(&self) -> Option<f32> {
        self.material
            .as_ref()
            .and_then(crate::labpbr::Material::emission_maximum)
    }
    pub fn has_subsurface(&self) -> bool {
        self.material
            .as_ref()
            .is_some_and(crate::labpbr::Material::has_subsurface)
    }
    pub fn fresnel_code(&self, frame: u32, uv: [f32; 2]) -> Option<u8> {
        self.material
            .as_ref()
            .and_then(|m| m.fresnel_code(frame, uv))
    }
    pub fn fresnel_code_constant(&self) -> Option<u8> {
        self.material
            .as_ref()
            .and_then(crate::labpbr::Material::fresnel_code_constant)
    }
}

impl Image {
    pub fn from_typed(
        value: &prime_abi::PrimeMcImage,
        bytes: &[u8],
        allow_empty: bool,
    ) -> Result<Self, String> {
        let pixels = prime_abi::minecraft::range(bytes, value.pixels)?;
        if value.width == 0
            || value.height == 0
            || value.width > 16384
            || value.height > 16384
            || !(allow_empty && pixels.is_empty())
                && pixels.len() as u64 != u64::from(value.width) * u64::from(value.height) * 4
        {
            return Err("invalid source image".into());
        }
        Ok(Self {
            width: value.width,
            height: value.height,
            pixels: pixels.into(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prepared_phase_matches_duration_clock_and_preserves_thousandth_quantization() {
        let frames = [(3, 2), (1, 12345), (3, 7)];
        for interpolate in [false, true] {
            let bytes = source_bytes(&frames, interpolate);
            let pages = [bytes.as_slice()];
            let (_, sprite) = Sprite::read(&mut Reader::new(&pages).unwrap()).unwrap();
            assert_eq!(sprite.frame_ends, [2, 12347, 12354]);
            for tick in (0..25000).chain([u64::MAX - 1, u64::MAX]) {
                let mut at = tick % 12354;
                let mut index = 0;
                while at >= u64::from(frames[index].1) {
                    at -= u64::from(frames[index].1);
                    index += 1;
                }
                let blend = if interpolate {
                    ((at as f32 / frames[index].1 as f32) * 1000.) as u32 as f32 / 1000.
                } else {
                    0.
                };
                assert_eq!(
                    sprite.phase(tick),
                    (frames[index].0, frames[(index + 1) % 3].0, blend)
                );
            }
            assert!(sprite.same_phase(2, 3));
            assert!(sprite.same_phase(2, 12356));
            assert!(!sprite.same_phase(1, 2));
        }
    }

    fn source_bytes(frames: &[(u32, u32)], interpolate: bool) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend(1_u32.to_le_bytes());
        bytes.extend(4_u32.to_le_bytes());
        bytes.extend(b"test");
        for bound in [0_f32, 0., 1., 1.] {
            bytes.extend(bound.to_le_bytes());
        }
        for value in [2_u32, 2, 1, 4, 4, 16] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend([255; 64]);
        bytes.extend(u32::from(interpolate).to_le_bytes());
        bytes.extend((frames.len() as u32).to_le_bytes());
        for &(frame, duration) in frames {
            bytes.extend(frame.to_le_bytes());
            bytes.extend(duration.to_le_bytes());
        }
        bytes
    }
    #[test]
    fn no_mip_animation_keeps_all_actual_frame_windows_in_one_shared_allocation() {
        let frames = [(3, 2), (1, 3), (3, 1)];
        for interpolate in [false, true] {
            let bytes = source_bytes(&frames, interpolate);
            let pages = [bytes.as_slice()];
            let mut reader = Reader::new(&pages).unwrap();
            let (_, sprite) = Sprite::read(&mut reader).unwrap();
            reader.finish().unwrap();
            assert_eq!(sprite.coverage_frames.as_ref(), [[2, 0], [2, 2]]);
            let scene = SourceScene::default();
            for (tick, region, next) in [
                (0, [2, 2, 2, 2], [2, 0]),
                (1, [2, 2, 2, 2], [2, 0]),
                (2, [2, 0, 2, 2], [2, 2]),
                (5, [2, 2, 2, 2], [2, 2]),
                (6, [2, 2, 2, 2], [2, 0]),
            ] {
                let image = sprite.image(tick, &scene).unwrap();
                assert_eq!(image.region, Some(region));
                assert!(Arc::ptr_eq(&image.pixels, &sprite.images[0].pixels));
                let sampling = image.sampling.unwrap();
                assert!(sampling.levels.is_empty());
                assert_eq!(sampling.next, next);
                assert!(Arc::ptr_eq(
                    &sampling.coverage_frames,
                    &sprite.coverage_frames
                ));
                assert_eq!(
                    sampling.blend,
                    if interpolate && tick == 1 { 0.5 } else { 0. }
                );
            }
        }
        let bytes = source_bytes(&[], false);
        let pages = [bytes.as_slice()];
        let (_, sprite) = Sprite::read(&mut Reader::new(&pages).unwrap()).unwrap();
        assert!(sprite.coverage_frames.is_empty());
        assert!(
            sprite
                .image(100, &SourceScene::default())
                .unwrap()
                .sampling
                .is_none()
        );
    }
    #[test]
    fn out_of_sheet_sequence_frames_are_rejected_before_coverage_publication() {
        let bytes = source_bytes(&[(4, 1)], false);
        let pages = [bytes.as_slice()];
        assert!(Sprite::read(&mut Reader::new(&pages).unwrap()).is_err());
    }
    #[test]
    fn local_uvs_only_repair_endpoint_ulps_and_keep_crops_and_orientation() {
        let sprite = Sprite {
            name: "test:uv".into(),
            bounds: [0.25, 0.5, 0.375, 0.625],
            extent: [16, 16],
            images: vec![],
            frames: vec![],
            frame_ends: vec![],
            coverage_frames: Arc::from([]),
            interpolate: false,
            material: None,
        };
        let uvs = [
            [0.375_f32.next_up(), 0.5_f32.next_down()],
            [0.25, 0.625],
            [0.3125, 0.5625],
            [0.25, 0.5],
        ];
        assert_eq!(
            sprite.local(uvs).unwrap(),
            [[1., 0.], [0., 1.], [0.5, 0.5], [0., 0.]]
        );
        let mut outside = uvs;
        outside[0][0] = 0.376;
        assert!(sprite.local(outside).is_none());
    }
    #[test]
    fn animation_uses_source_frames_durations_mips_and_immutable_backings() {
        let pixels: Arc<[u8]> = (0..64).flat_map(|i| [i, 0, 0, 255]).collect();
        let sprite = Sprite {
            name: "test:animated".into(),
            bounds: [0., 0., 1., 1.],
            extent: [4, 4],
            images: vec![
                Image {
                    width: 8,
                    height: 8,
                    pixels: pixels.clone(),
                },
                Image {
                    width: 4,
                    height: 4,
                    pixels: vec![7; 64].into(),
                },
            ],
            frames: vec![(3, 2), (0, 3)],
            frame_ends: vec![2, 5],
            coverage_frames: Arc::from([[0, 0], [4, 4]]),
            interpolate: true,
            material: None,
        };
        let scene = SourceScene::default();
        for (tick, region, next, blend) in [
            (0, [4, 4, 4, 4], [0, 0], 0.),
            (1, [4, 4, 4, 4], [0, 0], 0.5),
            (2, [0, 0, 4, 4], [4, 4], 0.),
            (4, [0, 0, 4, 4], [4, 4], 0.666),
            (5, [4, 4, 4, 4], [0, 0], 0.),
        ] {
            let t = sprite.image(tick, &scene).unwrap();
            assert_eq!(t.region, Some(region));
            assert!(Arc::ptr_eq(&t.pixels, &pixels));
            let s = t.sampling.as_ref().unwrap();
            assert!(Arc::ptr_eq(&s.coverage_frames, &sprite.coverage_frames));
            assert_eq!(s.blend, blend);
            assert_eq!(s.next, next);
            assert_eq!(s.levels[0].region, [region[0] / 2, region[1] / 2, 2, 2]);
            assert_eq!(s.levels[0].next, next.map(|v| v / 2));
        }
        let reference = sprite.reference(&scene).unwrap();
        assert_eq!(reference[0], 54. / 255.);
    }
    #[test]
    fn static_atlas_window_uses_sprite_local_mip_coordinates() {
        let mut scene = SourceScene::default();
        scene
            .set_texture(
                1,
                Texture {
                    width: 16,
                    height: 8,
                    pixels: vec![255; 16 * 8 * 4].into(),
                    region: None,
                    sampling: None,
                    material: None,
                },
            )
            .unwrap();
        let sprite = Sprite {
            name: "test:offset_static".into(),
            bounds: [0.5, 0.5, 0.75, 1.],
            extent: [4, 4],
            images: vec![
                Image {
                    width: 4,
                    height: 4,
                    pixels: Arc::from([]),
                },
                Image {
                    width: 2,
                    height: 2,
                    pixels: vec![63; 16].into(),
                },
                Image {
                    width: 1,
                    height: 1,
                    pixels: vec![127; 4].into(),
                },
            ],
            frames: vec![],
            frame_ends: vec![],
            coverage_frames: Arc::from([]),
            interpolate: false,
            material: None,
        };
        let image = sprite.image(123, &scene).unwrap();
        assert_eq!(image.region, Some([8, 4, 4, 4]));
        assert!(Arc::ptr_eq(
            &image.pixels,
            &scene.texture(1).unwrap().pixels
        ));
        let sampling = image.sampling.unwrap();
        assert_eq!(sampling.next, [8, 4]);
        assert_eq!(sampling.blend, 0.);
        for (i, mip) in sampling.levels.iter().enumerate() {
            let extent = 2 >> i;
            assert_eq!(mip.region, [0, 0, extent, extent]);
            assert_eq!(mip.next, [0, 0]);
            assert!(Arc::ptr_eq(&mip.pixels, &sprite.images[i + 1].pixels));
        }
    }
    #[test]
    fn clear_glass_reference_does_not_average_opaque_border_paint_into_the_medium() {
        let mut scene = SourceScene::default();
        let mut pixels = vec![255; 64];
        pixels[40..44].copy_from_slice(&[255, 255, 255, 0]);
        scene
            .set_texture(
                1,
                Texture {
                    width: 4,
                    height: 4,
                    pixels: pixels.into(),
                    region: None,
                    sampling: None,
                    material: None,
                },
            )
            .unwrap();
        let mut sprite = Sprite {
            name: "test:glass".into(),
            bounds: [0., 0., 1., 1.],
            extent: [4, 4],
            images: vec![Image {
                width: 4,
                height: 4,
                pixels: Arc::from([]),
            }],
            frames: vec![],
            frame_ends: vec![],
            coverage_frames: Arc::from([]),
            interpolate: false,
            material: None,
        };
        assert_eq!(sprite.reference(&scene).unwrap(), [1., 1., 1., 0.]);
        assert_eq!(
            crate::optics::glass(sprite.reference(&scene).unwrap()).extinction,
            [0.; 3]
        );
        assert!(Arc::ptr_eq(
            &sprite.image(0, &scene).unwrap().pixels,
            &scene.texture(1).unwrap().pixels
        ));
        sprite.extent = [3, 4];
        assert!(sprite.image(0, &scene).is_err());
    }
}
