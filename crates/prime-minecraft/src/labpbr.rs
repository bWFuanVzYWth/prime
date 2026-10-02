//! LabPBR 1.3 source translation. Integer categories never enter a continuous filter.
use crate::{
    sprite::{Image, Sprite},
    wire::Reader,
};
use prime_scene::{Texture, TextureLevel, TextureMaterial, TextureSampling};
use std::sync::{Arc, OnceLock};

#[derive(PartialEq)]
pub(crate) struct Material {
    normal: Option<Plane>,
    specular: Option<Plane>,
    emission: Option<f32>,
    fresnel: Option<u8>,
    /// Original height survives normal-page translation, rebased per source frame.
    height: Option<Height>,
}
#[derive(PartialEq)]
struct Height {
    image: Image,
    extent: [u32; 2],
    columns: u32,
    minima: Vec<u8>,
}
#[derive(PartialEq)]
struct Plane {
    images: Vec<Image>,
    columns: u32,
    frames: u32,
    extent: [u32; 2],
}
struct Source {
    image: Image,
    extent: [u32; 2],
    columns: u32,
    frames: u32,
}

fn source(r: &mut Reader<'_>, sprite: &Sprite) -> Result<Option<Source>, String> {
    match r.u32()? {
        0 => Ok(None),
        1 => {
            let width = r.u32()?;
            let height = r.u32()?;
            let count = r.count(4)?;
            if width == 0
                || height == 0
                || width > 16384
                || height > 16384
                || count as u64 != u64::from(width) * u64::from(height)
            {
                return Err("invalid LabPBR source image".into());
            }
            let pixels = r
                .u32s(count)?
                .into_iter()
                .flat_map(u32::to_le_bytes)
                .collect();
            let [w, h] = sprite.extent;
            let base = &sprite.images[0];
            let columns = (base.width / w).max(1);
            let rows = (base.height / h).max(1);
            let (extent, columns, frames) = if width == w && height == h {
                ([width, height], 1, 1)
            } else if width.is_multiple_of(columns) && height.is_multiple_of(rows) {
                ([width / columns, height / rows], columns, columns * rows)
            } else {
                ([width, height], 1, 1)
            };
            Ok(Some(Source {
                image: Image {
                    width,
                    height,
                    pixels,
                },
                extent,
                columns,
                frames,
            }))
        }
        _ => Err("invalid LabPBR source presence".into()),
    }
}
impl Material {
    pub fn read(r: &mut Reader<'_>, sprite: &Sprite) -> Result<Self, String> {
        let normal = source(r, sprite)?;
        let specular = source(r, sprite)?;
        let emission = specular.as_ref().and_then(|s| {
            let mut maximum = None::<u8>;
            for p in s.image.pixels.as_chunks::<4>().0.iter() {
                if p[3] != 255 {
                    maximum = Some(maximum.map_or(p[3], |v| v.max(p[3])));
                }
            }
            maximum.map(|v| f32::from(v) / 254.)
        });
        let fresnel = specular.as_ref().and_then(|s| {
            let mut values = s
                .image
                .pixels
                .as_chunks::<4>()
                .0
                .iter()
                .map(|p| canonical_fresnel(p[1]));
            let first = values.next()?;
            values.all(|v| v == first).then_some(first)
        });
        let height = normal.as_ref().map(|s| {
            let [w, h] = s.extent;
            let minima = (0..s.frames)
                .map(|frame| {
                    let x = frame % s.columns * w;
                    let y = frame / s.columns * h;
                    (0..h)
                        .flat_map(|dy| {
                            (0..w).map(move |dx| {
                                s.image.pixels[((y + dy) * s.image.width + x + dx) as usize * 4 + 3]
                            })
                        })
                        .min()
                        .unwrap()
                })
                .collect();
            Height {
                image: Image {
                    width: s.image.width,
                    height: s.image.height,
                    pixels: s.image.pixels.clone(),
                },
                extent: s.extent,
                columns: s.columns,
                minima,
            }
        });
        Ok(Self {
            normal: normal.map(|s| s.compile(sprite, false)),
            specular: specular.map(|s| s.compile(sprite, true)),
            emission,
            fresnel,
            height,
        })
    }
    pub fn emission_maximum(&self) -> Option<f32> {
        self.emission
    }
    pub fn fresnel_code(&self, frame: u32, uv: [f32; 2]) -> Option<u8> {
        self.specular.as_ref().map(|s| {
            let frame = frame.min(s.frames - 1);
            let [w, h] = s.extent;
            let x = (uv[0] * w as f32).floor().clamp(0., (w - 1) as f32) as u32;
            let y = (uv[1] * h as f32).floor().clamp(0., (h - 1) as f32) as u32;
            let at = ((frame / s.columns * h + y) * s.images[0].width + frame % s.columns * w + x)
                as usize
                * 4
                + 1;
            s.images[0].pixels[at]
        })
    }
    pub fn fresnel_code_constant(&self) -> Option<u8> {
        self.fresnel
    }
    // Retained source decoding API; this renderer does not yet displace geometry.
    #[allow(dead_code)]
    pub fn height(&self, requested_frame: u32, uv: [f32; 2]) -> Option<f32> {
        self.height.as_ref().map(|s| {
            let frame = requested_frame.min(s.minima.len() as u32 - 1);
            let [w, h] = s.extent;
            let x = (uv[0] * w as f32).floor().clamp(0., (w - 1) as f32) as u32;
            let y = (uv[1] * h as f32).floor().clamp(0., (h - 1) as f32) as u32;
            let at = ((frame / s.columns * h + y) * s.image.width + frame % s.columns * w + x)
                as usize
                * 4
                + 3;
            f32::from(s.image.pixels[at] - s.minima[frame as usize]) / 255.
        })
    }
    pub fn image(
        &self,
        sprite: &Sprite,
        frame: u32,
        next: u32,
        blend: f32,
    ) -> Arc<TextureMaterial> {
        Arc::new(TextureMaterial {
            normal: self
                .normal
                .as_ref()
                .map(|p| p.image(sprite, frame, next, blend, false)),
            specular: self
                .specular
                .as_ref()
                .map(|p| p.image(sprite, frame, next, blend, true)),
            coverage: None,
            authored_emission: self.emission.is_some(),
            atlas_lookup: false,
            bounds: Some(sprite.bounds),
        })
    }
}
/// The one static atlas lookup links atlas-UV consumers to the independently animated sprites.
/// Updating animation never duplicates or uploads a full material atlas.
pub(crate) fn atlas(
    scene: &prime_scene::SourceScene,
    sprites: &std::collections::HashMap<u32, Sprite>,
) -> Option<Texture> {
    let atlas = scene.texture(1)?;
    if !sprites.values().any(|s| {
        s.material
            .as_ref()
            .is_some_and(|m| m.normal.is_some() || m.specular.is_some())
    }) {
        return None;
    }
    let mut lookup = atlas
        .material
        .as_ref()
        .filter(|m| m.atlas_lookup)
        .and_then(|m| m.coverage.as_ref())
        .map_or_else(|| vec![0; atlas.pixels.len()], |p| p.pixels.to_vec());
    for (&id, sprite) in sprites {
        if !sprite
            .material
            .as_ref()
            .is_some_and(|m| m.normal.is_some() || m.specular.is_some())
        {
            continue;
        }
        let x = (sprite.bounds[0] * atlas.width as f32).round() as u32;
        let y = (sprite.bounds[1] * atlas.height as f32).round() as u32;
        let [w, h] = sprite.extent;
        for dy in 0..h {
            for dx in 0..w {
                let at = ((y + dy) * atlas.width + x + dx) as usize * 4;
                lookup[at..at + 4].copy_from_slice(&crate::sprite::texture(id).to_le_bytes());
            }
        }
    }
    let coverage = Texture {
        width: atlas.width,
        height: atlas.height,
        pixels: lookup.into(),
        region: None,
        sampling: None,
        material: None,
    };
    let mut texture = atlas.clone();
    texture.material = Some(Arc::new(TextureMaterial {
        normal: None,
        specular: None,
        coverage: Some(coverage),
        authored_emission: false,
        atlas_lookup: true,
        bounds: None,
    }));
    Some(texture)
}
impl Source {
    fn compile(self, sprite: &Sprite, specular: bool) -> Plane {
        let mut images = Vec::with_capacity(sprite.images.len());
        for mip in 0..sprite.images.len() {
            let [w, h] = sprite.extent.map(|v| (v >> mip).max(1));
            let width = w * self.columns;
            let height = h * self.frames.div_ceil(self.columns);
            let mut pixels = vec![0; (width * height * 4) as usize];
            for frame in 0..self.frames {
                for y in 0..h {
                    for x in 0..w {
                        let pixel = self.filtered(
                            frame,
                            [
                                x as f64 * (1u32 << mip) as f64,
                                y as f64 * (1u32 << mip) as f64,
                                (x + 1) as f64 * (1u32 << mip) as f64,
                                (y + 1) as f64 * (1u32 << mip) as f64,
                            ],
                            sprite.extent,
                            specular,
                        );
                        let at = ((frame / self.columns * h + y) * width
                            + frame % self.columns * w
                            + x) as usize
                            * 4;
                        pixels[at..at + 4].copy_from_slice(&pixel);
                    }
                }
            }
            images.push(Image {
                width,
                height,
                pixels: pixels.into(),
            });
        }
        Plane {
            images,
            columns: self.columns,
            frames: self.frames,
            extent: sprite.extent,
        }
    }
    fn filtered(&self, frame: u32, bounds: [f64; 4], base: [u32; 2], specular: bool) -> [u8; 4] {
        let [w, h] = self.extent;
        let x0 = (bounds[0] * w as f64 / base[0] as f64)
            .floor()
            .clamp(0., (w - 1) as f64) as u32;
        let y0 = (bounds[1] * h as f64 / base[1] as f64)
            .floor()
            .clamp(0., (h - 1) as f64) as u32;
        let x1 = (bounds[2] * w as f64 / base[0] as f64)
            .ceil()
            .clamp((x0 + 1) as f64, w as f64) as u32;
        let y1 = (bounds[3] * h as f64 / base[1] as f64)
            .ceil()
            .clamp((y0 + 1) as f64, h as f64) as u32;
        let cx = (0.5 * (bounds[0] + bounds[2]) * w as f64 / base[0] as f64)
            .floor()
            .clamp(0., (w - 1) as f64) as u32;
        let cy = (0.5 * (bounds[1] + bounds[3]) * h as f64 / base[1] as f64)
            .floor()
            .clamp(0., (h - 1) as f64) as u32;
        let pixel = |x, y| {
            let at = ((frame / self.columns * h + y) * self.image.width
                + frame % self.columns * w
                + x) as usize
                * 4;
            <[u8; 4]>::try_from(&self.image.pixels[at..at + 4]).unwrap()
        };
        let count = u64::from((x1 - x0) * (y1 - y0));
        if specular {
            let center = pixel(cx, cy);
            let mut red = 0;
            let mut emission = 0;
            let mut sentinel = 0;
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = pixel(x, y);
                    red += u64::from(p[0]);
                    if p[3] == 255 {
                        sentinel += 1;
                    } else {
                        emission += u64::from(p[3]);
                    }
                }
            }
            [
                ((red + count / 2) / count) as u8,
                canonical_fresnel(center[1]),
                canonical_scattering(center[2]),
                if sentinel == count {
                    255
                } else {
                    ((emission + count / 2) / count) as u8
                },
            ]
        } else {
            let mut n = [0.; 3];
            let mut ao = 0;
            for y in y0..y1 {
                for x in x0..x1 {
                    let p = pixel(x, y);
                    let d = direction(p);
                    for i in 0..3 {
                        n[i] += d[i];
                    }
                    ao += u64::from(p[2]);
                }
            }
            n = n.map(|v| v / count as f64);
            let length = n.iter().map(|v| v * v).sum::<f64>().sqrt();
            [
                encode(n[0] / length.max(1e-20) * 0.5 + 0.5),
                encode(n[1] / length.max(1e-20) * 0.5 + 0.5),
                ((ao + count / 2) / count) as u8,
                distribution(length),
            ]
        }
    }
}
impl Plane {
    fn image(&self, sprite: &Sprite, frame: u32, next: u32, blend: f32, specular: bool) -> Texture {
        let frame = frame.min(self.frames - 1);
        let next = next.min(self.frames - 1);
        let progress = if self.frames == 1 {
            0
        } else {
            // The source animation clock truncates subFrame / frameTime to thousandths.
            ((blend * 1000.) as u32).min(999)
        };
        let mut views = Vec::with_capacity(self.images.len());
        for (mip, image) in self.images.iter().enumerate() {
            let [w, h] = sprite.extent.map(|v| (v >> mip).max(1));
            if progress == 0 || frame == next {
                views.push(TextureLevel {
                    width: image.width,
                    height: image.height,
                    pixels: image.pixels.clone(),
                    region: [frame % self.columns * w, frame / self.columns * h, w, h],
                    next: [frame % self.columns * w, frame / self.columns * h],
                });
            } else {
                let mut pixels = Vec::with_capacity((w * h * 4) as usize);
                for y in 0..h {
                    for x in 0..w {
                        let pixel = |f| {
                            let at = ((f / self.columns * h + y) * image.width
                                + f % self.columns * w
                                + x) as usize
                                * 4;
                            <[u8; 4]>::try_from(&image.pixels[at..at + 4]).unwrap()
                        };
                        pixels.extend(blend_filtered(
                            pixel(frame),
                            pixel(next),
                            progress,
                            specular,
                        ));
                    }
                }
                views.push(TextureLevel {
                    width: w,
                    height: h,
                    pixels: pixels.into(),
                    region: [0, 0, w, h],
                    next: [0; 2],
                });
            }
        }
        let base = views.remove(0);
        Texture {
            width: base.width,
            height: base.height,
            pixels: base.pixels,
            region: Some(base.region),
            sampling: (!views.is_empty()).then(|| {
                Arc::new(TextureSampling {
                    levels: views,
                    next: base.next,
                    blend: 0.,
                    coverage_frames: Arc::from([]),
                })
            }),
            material: None,
        }
    }
}
fn canonical_fresnel(v: u8) -> u8 {
    if v <= 237 {
        v + 1
    } else if v == 255 {
        239
    } else {
        0
    }
}
fn canonical_scattering(v: u8) -> u8 {
    if v <= 64 {
        v
    } else if v == 65 {
        0
    } else {
        v - 1
    }
}
fn encode(v: f64) -> u8 {
    (v * 255.).round().clamp(0., 255.) as u8
}
fn direction(p: [u8; 4]) -> [f64; 3] {
    let [x, y, z] = raw_direction(p);
    let inverse = 1. / (x * x + y * y + z * z).max(1e-20).sqrt();
    [x * inverse, y * inverse, z * inverse]
}
fn raw_direction(p: [u8; 4]) -> [f64; 3] {
    let x = f64::from(p[0]) * (2. / 255.) - 1.;
    let y = f64::from(p[1]) * (2. / 255.) - 1.;
    let z = (1. - x * x - y * y).max(0.).sqrt();
    [x, y, z]
}
fn distribution(length: f64) -> u8 {
    static LENGTHS: OnceLock<[f64; 256]> = OnceLock::new();
    let table = LENGTHS.get_or_init(|| {
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
    });
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
fn blend_filtered(a: [u8; 4], b: [u8; 4], progress: u32, specular: bool) -> [u8; 4] {
    let inverse = 1000 - progress;
    let mix =
        |a: u8, b: u8| ((u32::from(a) * inverse + u32::from(b) * progress + 500) / 1000) as u8;
    if specular {
        [
            mix(a[0], b[0]),
            a[1],
            a[2],
            if a[3] == 255 && b[3] == 255 {
                255
            } else {
                mix(
                    if a[3] == 255 { 0 } else { a[3] },
                    if b[3] == 255 { 0 } else { b[3] },
                )
            },
        ]
    } else {
        let x = raw_direction(a);
        let y = raw_direction(b);
        let n = std::array::from_fn::<_, 3, _>(|i| x[i] * inverse as f64 + y[i] * progress as f64);
        let length = n.iter().map(|v| v * v).sum::<f64>().max(1e-20).sqrt();
        [
            encode(n[0] / length * 0.5 + 0.5),
            encode(n[1] / length * 0.5 + 0.5),
            mix(a[2], b[2]),
            mix(a[3], b[3]),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn authored_sprite() -> Sprite {
        Sprite {
            name: "test:authored".into(),
            bounds: [0.5, 0., 1., 1.],
            extent: [2, 2],
            images: vec![
                Image {
                    width: 2,
                    height: 4,
                    pixels: vec![255; 32].into(),
                },
                Image {
                    width: 1,
                    height: 2,
                    pixels: vec![255; 8].into(),
                },
            ],
            frames: vec![(0, 2), (1, 2)],
            coverage_frames: Arc::from([[0, 0], [0, 2]]),
            interpolate: true,
            material: None,
        }
    }
    fn bytes(
        normal: Option<(u32, u32, Vec<u8>)>,
        specular: Option<(u32, u32, Vec<u8>)>,
    ) -> Vec<u8> {
        let mut out = Vec::new();
        for source in [normal, specular] {
            if let Some((w, h, pixels)) = source {
                for v in [1, w, h, (pixels.len() / 4) as u32] {
                    out.extend(v.to_le_bytes());
                }
                out.extend(pixels);
            } else {
                out.extend(0_u32.to_le_bytes());
            }
        }
        out
    }
    #[test]
    fn actual_resource_sheet_translates_mips_animation_emission_and_rebased_height() {
        let mut sprite = authored_sprite();
        let mut normals = Vec::new();
        for alpha in [200, 201, 202, 203, 250, 250, 250, 250] {
            normals.extend([128, 128, 64, alpha]);
        }
        let data = bytes(
            Some((2, 4, normals)),
            Some((1, 2, vec![0, 0, 65, 255, 254, 255, 255, 254])),
        );
        let pages = [data.as_slice()];
        let mut r = Reader::new(&pages).unwrap();
        let material = Material::read(&mut r, &sprite).unwrap();
        r.finish().unwrap();
        assert_eq!(material.emission_maximum(), Some(1.));
        assert_eq!(material.height(0, [0.75, 0.25]), Some(1. / 255.));
        assert_eq!(material.height(1, [1., 1.]), Some(0.));
        sprite.material = Some(material);
        let image = sprite
            .image(1, &prime_scene::SourceScene::default())
            .unwrap();
        image.validate().unwrap();
        let m = image.material.as_ref().unwrap();
        assert!(m.authored_emission);
        let specular = m.specular.as_ref().unwrap();
        assert_eq!(specular.pixels.as_ref(), [127, 1, 0, 127].repeat(4));
        assert_eq!(
            specular.sampling.as_ref().unwrap().levels[0]
                .pixels
                .as_ref(),
            [127, 1, 0, 127]
        );
        let transitioned = sprite
            .image(2, &prime_scene::SourceScene::default())
            .unwrap();
        let specular = transitioned
            .material
            .unwrap()
            .specular
            .as_ref()
            .unwrap()
            .clone();
        assert_eq!(specular.region, Some([0, 2, 2, 2]));
        assert_eq!(&specular.pixels[16..20], &[254, 239, 254, 254]);
        for end in 0..data.len() {
            let pages = [&data[..end]];
            let mut r = Reader::new(&pages).unwrap();
            assert!(
                Material::read(&mut r, &sprite).is_err(),
                "truncated material at {end}"
            );
        }
    }
    #[test]
    fn absent_and_all_sentinel_maps_do_not_author_emission_but_zero_does() {
        let sprite = authored_sprite();
        for (alpha, expected) in [(None, None), (Some(255), None), (Some(0), Some(0.))] {
            let data = bytes(None, alpha.map(|alpha| (1, 1, vec![0, 0, 0, alpha])));
            let pages = [data.as_slice()];
            let mut r = Reader::new(&pages).unwrap();
            let material = Material::read(&mut r, &sprite).unwrap();
            assert_eq!(material.emission_maximum(), expected);
        }
    }
    #[test]
    fn animation_clock_truncates_thousandths_before_filtering_auxiliary_channels() {
        let mut sprite = authored_sprite();
        sprite.frames = vec![(0, 3), (1, 3)];
        let data = bytes(None, Some((1, 2, vec![1, 10, 66, 1, 254, 80, 255, 254])));
        let pages = [data.as_slice()];
        sprite.material = Some(Material::read(&mut Reader::new(&pages).unwrap(), &sprite).unwrap());
        let image = sprite
            .image(2, &prime_scene::SourceScene::default())
            .unwrap();
        let material = image.material.unwrap();
        let optical = material.specular.as_ref().unwrap();
        assert_eq!(optical.pixels.as_ref(), [169, 11, 65, 169].repeat(4));
        assert_eq!(
            optical.sampling.as_ref().unwrap().levels[0].pixels.as_ref(),
            [169, 11, 65, 169]
        );
    }
    #[test]
    fn atlas_lookup_uses_source_sprite_identity_and_animation_does_not_rebuild_it() {
        let mut scene = prime_scene::SourceScene::default();
        scene
            .set_texture(
                1,
                Texture {
                    width: 4,
                    height: 2,
                    pixels: vec![255; 32].into(),
                    region: None,
                    sampling: None,
                    material: None,
                },
            )
            .unwrap();
        let mut sprite = authored_sprite();
        let data = bytes(None, Some((1, 1, vec![127, 0, 0, 254])));
        let pages = [data.as_slice()];
        let mut r = Reader::new(&pages).unwrap();
        sprite.material = Some(Material::read(&mut r, &sprite).unwrap());
        let sprites = std::collections::HashMap::from([(3, sprite)]);
        let texture = atlas(&scene, &sprites).unwrap();
        texture.validate().unwrap();
        assert!(Arc::ptr_eq(
            &texture.pixels,
            &scene.texture(1).unwrap().pixels
        ));
        let material = texture.material.as_ref().unwrap();
        assert!(material.atlas_lookup);
        let coverage = &material.coverage.as_ref().unwrap().pixels;
        let ids: Vec<_> = coverage
            .as_chunks::<4>()
            .0
            .iter()
            .map(|p| u32::from_le_bytes(*p))
            .collect();
        assert_eq!(
            ids,
            vec![
                0,
                0,
                crate::sprite::texture(3),
                crate::sprite::texture(3),
                0,
                0,
                crate::sprite::texture(3),
                crate::sprite::texture(3)
            ]
        );
        scene.set_texture(1, texture).unwrap();
        assert!(atlas(&scene, &std::collections::HashMap::new()).is_none());
    }
    #[test]
    fn canonical_codes_keep_identities_and_reserved_endpoints() {
        for v in 0..=255u8 {
            assert_eq!(
                canonical_fresnel(v),
                if v <= 237 {
                    v + 1
                } else if v == 255 {
                    239
                } else {
                    0
                }
            );
            assert_eq!(
                canonical_scattering(v),
                if v <= 64 {
                    v
                } else if v == 65 {
                    0
                } else {
                    v - 1
                }
            );
        }
    }
    #[test]
    fn filter_preserves_categories_sentinel_and_normal_distribution() {
        let source = Source {
            image: Image {
                width: 2,
                height: 1,
                pixels: vec![0, 10, 65, 255, 254, 255, 255, 254].into(),
            },
            extent: [2, 1],
            columns: 1,
            frames: 1,
        };
        assert_eq!(
            source.filtered(0, [0., 0., 2., 1.], [2, 1], true),
            [127, 239, 254, 127]
        );
        assert_eq!(
            source.filtered(0, [0., 0., 1., 1.], [2, 1], true),
            [0, 11, 0, 255]
        );
        let normal = Source {
            image: Image {
                width: 2,
                height: 1,
                pixels: vec![0, 128, 32, 11, 255, 128, 64, 212].into(),
            },
            extent: [2, 1],
            columns: 1,
            frames: 1,
        };
        let filtered = normal.filtered(0, [0., 0., 2., 1.], [2, 1], false);
        assert_eq!(filtered[0], 128);
        assert_eq!(filtered[2], 48);
        assert_eq!(filtered[3], 255);
        assert_eq!(distribution(1.), 0);
        assert_eq!(distribution(2. / 3.), 255);
    }
    #[test]
    fn animation_blends_continuous_channels_only() {
        assert_eq!(
            blend_filtered([0, 7, 9, 255], [254, 239, 254, 254], 500, true),
            [127, 7, 9, 127]
        );
        assert_eq!(
            blend_filtered([0, 7, 9, 255], [254, 239, 254, 255], 500, true),
            [127, 7, 9, 255]
        );
        let n = blend_filtered([0, 128, 0, 0], [255, 128, 254, 254], 500, false);
        assert_eq!(n[0], 128);
        assert_eq!(n[2..], [127, 127]);
        let a = [0, 0, 10, 11];
        let b = [255, 128, 20, 21];
        let x = raw_direction(a);
        let y = raw_direction(b);
        let n = std::array::from_fn::<_, 3, _>(|i| x[i] * 750. + y[i] * 250.);
        let length = n.iter().map(|v| v * v).sum::<f64>().sqrt();
        assert_eq!(
            blend_filtered(a, b, 250, false),
            [
                encode(n[0] / length * 0.5 + 0.5),
                encode(n[1] / length * 0.5 + 0.5),
                13,
                14
            ]
        );
    }
}
