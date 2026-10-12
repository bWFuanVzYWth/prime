//! LabPBR source preparation into the fixed color/MERS/auxiliary RGBA8 contract.
use crate::material::{self, DEFAULT_AUX};
use crate::sprite::{Image, Sprite};
#[cfg(test)]
use crate::wire::Reader;
use prime_scene::{SourceScene, Texture, TextureLevel, TextureMaterial, TextureSampling};
use std::sync::Arc;

#[derive(PartialEq)]
pub(crate) struct Material {
    auxiliary: Option<Plane>,
    mers: Option<Plane>,
    color: Option<Plane>,
    color_source: Option<Source>,
    has_normal: bool,
    emission: Option<f32>,
    ior: Option<u8>,
    ior_identity: Option<u32>,
    subsurface: bool,
}
#[derive(PartialEq)]
struct Plane {
    images: Vec<Image>,
    columns: u32,
    frames: u32,
}
#[derive(PartialEq)]
struct Source {
    image: Image,
    extent: [u32; 2],
    columns: u32,
    frames: u32,
}
#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Color,
    Mers,
    Auxiliary,
}
impl Source {
    fn new(sprite: &Sprite, image: Image) -> Self {
        let [w, h] = sprite.extent;
        let base = &sprite.images[0];
        let columns = (base.width / w).max(1);
        let rows = (base.height / h).max(1);
        let (extent, columns, frames) = if image.width == w && image.height == h {
            ([image.width, image.height], 1, 1)
        } else if image.width.is_multiple_of(columns) && image.height.is_multiple_of(rows) {
            (
                [image.width / columns, image.height / rows],
                columns,
                columns * rows,
            )
        } else {
            ([image.width, image.height], 1, 1)
        };
        Self {
            image,
            extent,
            columns,
            frames,
        }
    }
    fn pixel(&self, frame: u32, x: u32, y: u32) -> [u8; 4] {
        let frame = frame.min(self.frames - 1);
        let [w, h] = self.extent;
        let at = ((frame / self.columns * h + y) * self.image.width + frame % self.columns * w + x)
            as usize
            * 4;
        self.image.pixels[at..at + 4].try_into().unwrap()
    }
    fn sample(&self, frame: u32, uv: [f64; 2]) -> [u8; 4] {
        let [w, h] = self.extent;
        self.pixel(
            frame,
            (uv[0] * f64::from(w)).floor().clamp(0., f64::from(w - 1)) as u32,
            (uv[1] * f64::from(h)).floor().clamp(0., f64::from(h - 1)) as u32,
        )
    }
    fn mean(
        &self,
        frame: u32,
        bounds: [f64; 4],
        base: [u32; 2],
        adapt: impl Fn([u8; 4]) -> [f64; 4],
    ) -> [f64; 4] {
        let [w, h] = self.extent;
        let x0 = (bounds[0] * f64::from(w) / f64::from(base[0]))
            .floor()
            .clamp(0., f64::from(w - 1)) as u32;
        let y0 = (bounds[1] * f64::from(h) / f64::from(base[1]))
            .floor()
            .clamp(0., f64::from(h - 1)) as u32;
        let x1 = (bounds[2] * f64::from(w) / f64::from(base[0]))
            .ceil()
            .clamp(f64::from(x0 + 1), f64::from(w)) as u32;
        let y1 = (bounds[3] * f64::from(h) / f64::from(base[1]))
            .ceil()
            .clamp(f64::from(y0 + 1), f64::from(h)) as u32;
        let mut sum = [0.; 4];
        for y in y0..y1 {
            for x in x0..x1 {
                let value = adapt(self.pixel(frame, x, y));
                for i in 0..4 {
                    sum[i] += value[i];
                }
            }
        }
        let count = f64::from((x1 - x0) * (y1 - y0));
        sum.map(|v| v / count)
    }
    fn compile(&self, sprite: &Sprite, kind: Kind) -> Plane {
        compile(sprite, self.columns, self.frames, |frame, bounds| {
            if kind == Kind::Auxiliary {
                let values = self.mean(frame, bounds, sprite.extent, |p| {
                    let d = material::direction(p);
                    let length = material::normal_length(p[2]);
                    [
                        d[0] * length,
                        d[1] * length,
                        d[2] * length,
                        f64::from(material::decode_ior(p[3])),
                    ]
                });
                material::normal_pixel(
                    [values[0], values[1], values[2]],
                    material::encode_ior(values[3]),
                )
            } else {
                self.mean(frame, bounds, sprite.extent, |p| {
                    p.map(|v| f64::from(v) / 255.)
                })
                .map(material::encode)
            }
        })
    }
}
#[cfg(test)]
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
            Ok(Some(Source::new(
                sprite,
                Image {
                    width,
                    height,
                    pixels,
                },
            )))
        }
        _ => Err("invalid LabPBR source presence".into()),
    }
}
impl Material {
    #[cfg(test)]
    pub fn read(r: &mut Reader<'_>, sprite: &Sprite) -> Result<Self, String> {
        let normal = source(r, sprite)?;
        let specular = source(r, sprite)?;
        Ok(Self::prepare(sprite, normal, specular))
    }
    pub fn from_images(sprite: &Sprite, normal: Option<Image>, specular: Option<Image>) -> Self {
        Self::prepare(
            sprite,
            normal.map(|s| Source::new(sprite, s)),
            specular.map(|s| Source::new(sprite, s)),
        )
    }
    fn prepare(sprite: &Sprite, normal: Option<Source>, specular: Option<Source>) -> Self {
        let mut subsurface = false;
        let mut standard_metal = false;
        let mut ior_identity = None;
        let mut ior = None;
        let emission = specular.as_ref().and_then(|s| {
            let first = s.image.pixels.as_chunks::<4>().0[0][1];
            ior_identity = Some((lab_f0(first) as f32).to_bits());
            let mut maximum = None::<u8>;
            for p in s.image.pixels.as_chunks::<4>().0 {
                subsurface |= p[2] > 65;
                standard_metal |= (230..=237).contains(&p[1]);
                ior_identity = ior_identity.filter(|v| *v == (lab_f0(p[1]) as f32).to_bits());
                if p[3] != 255 {
                    maximum = Some(maximum.map_or(p[3], |v| v.max(p[3])));
                }
            }
            ior = ior_identity.map(|_| lab_ior_byte(first));
            maximum.map(|v| f32::from(material::encode(f64::from(v) / 254.)) / 255.)
        });
        let mers = specular.as_ref().map(|s| {
            compile(sprite, s.columns, s.frames, |frame, bounds| {
                s.mean(frame, bounds, sprite.extent, lab_mers)
                    .map(material::encode)
            })
        });
        let has_normal = normal.is_some();
        let auxiliary = normal
            .as_ref()
            .or(specular.as_ref())
            .filter(|_| has_normal || ior != Some(DEFAULT_AUX[3]))
            .map(|layout| {
                let frames = normal
                    .as_ref()
                    .map_or(1, |s| s.frames)
                    .max(specular.as_ref().map_or(1, |s| s.frames));
                let columns = normal
                    .as_ref()
                    .filter(|s| s.frames > 1)
                    .or(specular.as_ref().filter(|s| s.frames > 1))
                    .unwrap_or(layout)
                    .columns;
                compile(sprite, columns, frames, |frame, bounds| {
                    let direction = normal.as_ref().map_or([0., 0., 1., 0.], |s| {
                        s.mean(frame, bounds, sprite.extent, |p| {
                            let x = f64::from(p[0]) * (2. / 255.) - 1.;
                            let y = f64::from(p[1]) * (2. / 255.) - 1.;
                            let n = material::unit([x, y, (1. - x * x - y * y).max(0.).sqrt()]);
                            [n[0], n[1], n[2], 0.]
                        })
                    });
                    let ior_byte = ior.unwrap_or_else(|| {
                        material::encode_ior(specular.as_ref().map_or(1.5, |s| {
                            s.mean(frame, bounds, sprite.extent, |p| {
                                [lab_ior(p[1]), 0., 0., 0.]
                            })[0]
                        }))
                    });
                    material::normal_pixel([direction[0], direction[1], direction[2]], ior_byte)
                })
            });
        Self {
            auxiliary,
            mers,
            color: None,
            color_source: standard_metal.then_some(specular).flatten(),
            has_normal,
            emission,
            ior,
            ior_identity,
            subsurface,
        }
    }
    pub fn from_internal_images(
        sprite: &Sprite,
        color: Option<Image>,
        mers: Image,
        auxiliary: Option<Image>,
        has_normal: bool,
    ) -> Self {
        let mers = Source::new(sprite, mers);
        let emission = mers
            .image
            .pixels
            .as_chunks::<4>()
            .0
            .iter()
            .map(|p| p[1])
            .max()
            .unwrap();
        let subsurface = mers
            .image
            .pixels
            .as_chunks::<4>()
            .0
            .iter()
            .any(|p| p[3] != 0);
        let auxiliary = auxiliary.map(|s| Source::new(sprite, s));
        let ior = auxiliary.as_ref().map_or(Some(DEFAULT_AUX[3]), |s| {
            constant(s.image.pixels.as_chunks::<4>().0.iter().map(|p| p[3]))
        });
        Self {
            auxiliary: auxiliary.map(|s| s.compile(sprite, Kind::Auxiliary)),
            mers: Some(mers.compile(sprite, Kind::Mers)),
            color: color.map(|s| Source::new(sprite, s).compile(sprite, Kind::Color)),
            color_source: None,
            has_normal,
            emission: Some(f32::from(emission) / 255.),
            ior,
            ior_identity: ior.map(|v| material::decode_ior(v).to_bits()),
            subsurface,
        }
    }
    /// The staged atlas is available only after typed source validation. No source sheet escapes
    /// this once-per-generation conversion, and unchanged host colors retain their backing.
    pub fn prepare_color(&mut self, sprite: &Sprite, scene: &SourceScene) -> Result<(), String> {
        if sprite.images[0].pixels.is_empty()
            && (self.color.is_some() || self.color_source.is_some())
        {
            let atlas = scene.texture(1).ok_or("material color atlas missing")?;
            let x = (sprite.bounds[0] * atlas.width as f32).round() as u32;
            let y = (sprite.bounds[1] * atlas.height as f32).round() as u32;
            if (sprite.bounds[2] * atlas.width as f32).round() as u32 != x + sprite.extent[0]
                || (sprite.bounds[3] * atlas.height as f32).round() as u32 != y + sprite.extent[1]
            {
                return Err("sprite bounds disagree with captured atlas extent".into());
            }
        }
        let Some(source) = self.color_source.take() else {
            return Ok(());
        };
        let base = &sprite.images[0];
        let [w, h] = sprite.extent;
        let columns = base.width / w;
        let frames = columns * (base.height / h);
        let mut images = Vec::with_capacity(sprite.images.len());
        for (mip, image) in sprite.images.iter().enumerate() {
            let [w, h] = sprite.extent.map(|v| (v >> mip).max(1));
            let width = w * columns;
            let height = h * frames.div_ceil(columns);
            let (pixels, stride, origin) = if image.pixels.is_empty() {
                let atlas = scene.texture(1).ok_or("material color atlas missing")?;
                let origin = [
                    (sprite.bounds[0] * atlas.width as f32).round() as u32,
                    (sprite.bounds[1] * atlas.height as f32).round() as u32,
                ];
                (&atlas.pixels, atlas.width, origin)
            } else {
                (&image.pixels, image.width, [0; 2])
            };
            let mut out = vec![0; (width * height * 4) as usize];
            for frame in 0..frames {
                for y in 0..h {
                    for x in 0..w {
                        let sx = frame % columns * w + x;
                        let sy = frame / columns * h + y;
                        let at = ((origin[1] + sy) * stride + origin[0] + sx) as usize * 4;
                        let pixel = pixels
                            .get(at..at + 4)
                            .ok_or("material color exceeds source atlas")?;
                        let metal = source.sample(
                            frame,
                            [
                                (f64::from(x) + 0.5) / f64::from(w),
                                (f64::from(y) + 0.5) / f64::from(h),
                            ],
                        )[1];
                        let f0 = standard_f0(metal);
                        let destination =
                            ((frame / columns * h + y) * width + frame % columns * w + x) as usize
                                * 4;
                        for c in 0..3 {
                            out[destination + c] = if let Some(f0) = f0 {
                                material::encode(srgb_encode(
                                    srgb_decode(f64::from(pixel[c]) / 255.) * f0[c],
                                ))
                            } else {
                                pixel[c]
                            };
                        }
                        out[destination + 3] = pixel[3];
                    }
                }
            }
            images.push(Image {
                width,
                height,
                pixels: out.into(),
            });
        }
        self.color = Some(Plane {
            images,
            columns,
            frames,
        });
        Ok(())
    }
    pub fn color_image(
        &self,
        sprite: &Sprite,
        frame: u32,
        next: u32,
        blend: f32,
    ) -> Option<Texture> {
        self.color
            .as_ref()
            .map(|p| p.image(sprite, frame, next, blend, Kind::Color))
    }
    pub fn emission_maximum(&self) -> Option<f32> {
        self.emission
    }
    pub fn has_specular(&self) -> bool {
        self.mers.is_some()
    }
    pub fn has_subsurface(&self) -> bool {
        self.subsurface
    }
    pub fn ior_byte(&self, frame: u32, uv: [f32; 2]) -> Option<u8> {
        self.mers.as_ref()?;
        Some(self.auxiliary.as_ref().map_or(DEFAULT_AUX[3], |p| {
            let image = &p.images[0];
            let [w, h] = self.extent();
            let frame = frame.min(p.frames - 1);
            let x = (uv[0] * w as f32).floor().clamp(0., (w - 1) as f32) as u32;
            let y = (uv[1] * h as f32).floor().clamp(0., (h - 1) as f32) as u32;
            image.pixels[((frame / p.columns * h + y) * image.width + frame % p.columns * w + x)
                as usize
                * 4
                + 3]
        }))
    }
    fn extent(&self) -> [u32; 2] {
        let plane = self.auxiliary.as_ref().or(self.mers.as_ref()).unwrap();
        [
            plane.images[0].width / plane.columns,
            plane.images[0].height / plane.frames.div_ceil(plane.columns),
        ]
    }
    pub fn ior_byte_constant(&self) -> Option<u8> {
        self.ior
    }
    pub fn ior_identity(&self) -> Option<u32> {
        self.ior_identity
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
                .auxiliary
                .as_ref()
                .map(|p| p.image(sprite, frame, next, blend, Kind::Auxiliary)),
            specular: self
                .mers
                .as_ref()
                .map(|p| p.image(sprite, frame, next, blend, Kind::Mers)),
            has_normal: self.has_normal,
            coverage: None,
            authored_emission: self.emission.is_some(),
            atlas_lookup: false,
            bounds: Some(sprite.bounds),
        })
    }
}
fn compile(
    sprite: &Sprite,
    columns: u32,
    frames: u32,
    filtered: impl Fn(u32, [f64; 4]) -> [u8; 4],
) -> Plane {
    let mut images = Vec::with_capacity(sprite.images.len());
    for mip in 0..sprite.images.len() {
        let [w, h] = sprite.extent.map(|v| (v >> mip).max(1));
        let width = w * columns;
        let height = h * frames.div_ceil(columns);
        let scale = f64::from(1u32 << mip);
        let mut pixels = vec![0; (width * height * 4) as usize];
        for frame in 0..frames {
            for y in 0..h {
                for x in 0..w {
                    let pixel = filtered(
                        frame,
                        [
                            f64::from(x) * scale,
                            f64::from(y) * scale,
                            f64::from(x + 1) * scale,
                            f64::from(y + 1) * scale,
                        ],
                    );
                    let at =
                        ((frame / columns * h + y) * width + frame % columns * w + x) as usize * 4;
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
        columns,
        frames,
    }
}
impl Plane {
    fn image(&self, sprite: &Sprite, frame: u32, next: u32, blend: f32, kind: Kind) -> Texture {
        let frame = frame.min(self.frames - 1);
        let next = next.min(self.frames - 1);
        let progress = if self.frames == 1 {
            0
        } else {
            ((blend * 1000.) as u32).min(999)
        };
        let mut views = Vec::with_capacity(self.images.len());
        for (mip, image) in self.images.iter().enumerate() {
            let [w, h] = sprite.extent.map(|v| (v >> mip).max(1));
            if kind == Kind::Color || progress == 0 || frame == next {
                views.push(TextureLevel {
                    width: image.width,
                    height: image.height,
                    pixels: image.pixels.clone(),
                    region: [frame % self.columns * w, frame / self.columns * h, w, h],
                    next: if kind == Kind::Color {
                        [next % self.columns * w, next / self.columns * h]
                    } else {
                        [frame % self.columns * w, frame / self.columns * h]
                    },
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
                            image.pixels[at..at + 4].try_into().unwrap()
                        };
                        pixels.extend(if kind == Kind::Auxiliary {
                            material::blend_aux(pixel(frame), pixel(next), progress)
                        } else {
                            material::blend_continuous(pixel(frame), pixel(next), progress)
                        });
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
        let coverage_frames = if kind == Kind::Color && !sprite.frames.is_empty() {
            let [w, h] = sprite.extent;
            let mut frames: Vec<_> = sprite
                .frames
                .iter()
                .map(|&(f, _)| {
                    let f = f.min(self.frames - 1);
                    [f % self.columns * w, f / self.columns * h]
                })
                .collect();
            frames.sort_unstable();
            frames.dedup();
            Arc::from(frames)
        } else {
            Arc::from([])
        };
        Texture {
            width: base.width,
            height: base.height,
            pixels: base.pixels,
            region: Some(base.region),
            sampling: (!views.is_empty() || kind == Kind::Color && !sprite.frames.is_empty()).then(
                || {
                    Arc::new(TextureSampling {
                        levels: views,
                        next: base.next,
                        blend: if kind == Kind::Color {
                            progress as f32 / 1000.
                        } else {
                            0.
                        },
                        coverage_frames,
                    })
                },
            ),
            material: None,
        }
    }
}
fn constant(values: impl Iterator<Item = u8>) -> Option<u8> {
    let mut values = values;
    let first = values.next()?;
    values.all(|v| v == first).then_some(first)
}
fn lab_f0(source: u8) -> f64 {
    if source <= 229 {
        (f64::from(source) / 255.).clamp(0.02, 0.17)
    } else {
        0.04
    }
}
fn lab_ior(source: u8) -> f64 {
    let f0 = lab_f0(source);
    let root = f0.sqrt();
    (1. + root) / (1. - root)
}
fn lab_ior_byte(source: u8) -> u8 {
    material::encode_ior(lab_ior(source))
}
fn lab_mers(pixel: [u8; 4]) -> [f64; 4] {
    [
        if (230..=237).contains(&pixel[1]) || pixel[1] == 255 {
            1.
        } else {
            0.
        },
        if pixel[3] == 255 {
            0.
        } else {
            f64::from(pixel[3]) / 254.
        },
        f64::from(255 - pixel[0]) / 255.,
        f64::from(pixel[2].saturating_sub(65)) / 190.,
    ]
}
fn srgb_decode(value: f64) -> f64 {
    if value <= 0.04045 {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).powf(2.4)
    }
}
fn srgb_encode(value: f64) -> f64 {
    if value <= 0.0031308 {
        value * 12.92
    } else {
        1.055 * value.powf(1. / 2.4) - 0.055
    }
}
fn standard_f0(source: u8) -> Option<[f64; 3]> {
    let (eta, k) = match source {
        230 => ([2.9114, 2.9497, 2.5845], [3.0893, 2.9318, 2.7670]),
        231 => ([0.18299, 0.42108, 1.3734], [3.4242, 2.3459, 1.7704]),
        232 => ([1.3456, 0.96521, 0.61722], [7.4746, 6.3995, 5.3031]),
        233 => ([3.1071, 3.1812, 2.3230], [3.3314, 3.3291, 3.1350]),
        234 => ([0.27105, 0.67693, 1.3164], [3.6092, 2.6248, 2.2921]),
        235 => ([1.9100, 1.8300, 1.4400], [3.5100, 3.4000, 3.1800]),
        236 => ([2.3757, 2.0847, 1.8453], [4.2655, 3.7153, 3.1365]),
        237 => ([0.15943, 0.14512, 0.13547], [3.9291, 3.1900, 2.3808]),
        _ => return None,
    };
    Some(std::array::from_fn(|i| {
        ((eta[i] - 1.) * (eta[i] - 1.) + k[i] * k[i])
            / ((eta[i] + 1.) * (eta[i] + 1.) + k[i] * k[i])
    }))
}
/// A static lookup links atlas-UV consumers to independently prepared material sprites.
pub(crate) fn atlas(
    scene: &SourceScene,
    sprites: &std::collections::HashMap<u32, Sprite>,
) -> Option<Texture> {
    let atlas = scene.texture(1)?;
    if !sprites
        .values()
        .any(|s| s.block_atlas && s.material.is_some())
    {
        return None;
    }
    let mut lookup = atlas
        .material
        .as_ref()
        .filter(|m| m.atlas_lookup)
        .and_then(|m| m.coverage.as_ref())
        .map_or_else(|| vec![0; atlas.pixels.len()], |p| p.pixels.to_vec());
    for (&id, sprite) in sprites {
        if !sprite.block_atlas || sprite.material.is_none() {
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
        has_normal: false,
        coverage: Some(coverage),
        authored_emission: false,
        atlas_lookup: true,
        bounds: None,
    }));
    Some(texture)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn sprite() -> Sprite {
        Sprite {
            name: "test:material".into(),
            block_atlas: true,
            bounds: [0., 0., 1., 1.],
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
            frame_ends: vec![2, 4],
            coverage_frames: Arc::from([[0, 0], [0, 2]]),
            interpolate: true,
            material: None,
        }
    }
    fn image(width: u32, height: u32, pixels: Vec<u8>) -> Image {
        Image {
            width,
            height,
            pixels: pixels.into(),
        }
    }
    fn bytes(normal: Option<Image>, specular: Option<Image>) -> Vec<u8> {
        let mut out = Vec::new();
        for source in [normal, specular] {
            if let Some(s) = source {
                for v in [1, s.width, s.height, (s.pixels.len() / 4) as u32] {
                    out.extend(v.to_le_bytes());
                }
                out.extend(s.pixels.iter());
            } else {
                out.extend(0_u32.to_le_bytes());
            }
        }
        out
    }
    #[test]
    fn lab_sheet_prepares_continuous_mers_and_current_frame_ior() {
        let sprite = sprite();
        let material = Material::from_images(
            &sprite,
            None,
            Some(image(1, 2, vec![0, 0, 65, 255, 254, 255, 255, 254])),
        );
        assert_eq!(material.emission_maximum(), Some(1.));
        assert!(material.has_subsurface());
        let view = material.image(&sprite, 0, 1, 0.5);
        assert!(!view.has_normal);
        assert_eq!(
            view.specular.as_ref().unwrap().pixels.as_ref(),
            [128, 128, 128, 128].repeat(4)
        );
        let aux = view.normal.as_ref().unwrap();
        assert_eq!(&aux.pixels[..4], &[128, 128, 0, lab_ior_byte(0)]);
        assert_eq!(
            view.specular
                .as_ref()
                .unwrap()
                .sampling
                .as_ref()
                .unwrap()
                .levels[0]
                .pixels
                .as_ref(),
            [128; 4]
        );
        let next = material.image(&sprite, 1, 0, 0.);
        let page = next.specular.as_ref().unwrap();
        assert_eq!(&page.pixels[16..20], &[255, 255, 1, 255]);
    }
    #[test]
    fn missing_and_sentinel_emission_preserve_host_while_zero_authors_off() {
        let sprite = sprite();
        for (alpha, expected) in [(None, None), (Some(255), None), (Some(0), Some(0.))] {
            let material =
                Material::from_images(&sprite, None, alpha.map(|a| image(1, 1, vec![0, 0, 0, a])));
            assert_eq!(material.emission_maximum(), expected);
        }
    }
    #[test]
    fn all_lab_codes_translate_without_gpu_categories_or_unused_channels() {
        for value in 0..=255 {
            let mers = lab_mers([value, value, value, value]);
            assert_eq!(
                material::encode(mers[0]),
                if (230..=237).contains(&value) || value == 255 {
                    255
                } else {
                    0
                }
            );
            assert_eq!(material::encode(mers[2]), 255 - value);
            assert_eq!(
                material::encode(mers[3]),
                material::encode(f64::from(value.saturating_sub(65)) / 190.)
            );
            assert!((1. ..=2.5).contains(&lab_ior(value)));
        }
    }
    #[test]
    fn auxiliary_discards_ao_height_and_retains_filtered_distribution() {
        let mut sprite = sprite();
        sprite.images[0] = image(2, 2, vec![255; 16]);
        sprite.images[1] = image(1, 1, vec![255; 4]);
        let a = Material::from_images(
            &sprite,
            Some(image(
                2,
                2,
                vec![
                    0, 128, 0, 0, 255, 128, 255, 255, 0, 128, 10, 20, 255, 128, 40, 50,
                ],
            )),
            None,
        );
        let b = Material::from_images(
            &sprite,
            Some(image(
                2,
                2,
                vec![
                    0, 128, 90, 10, 255, 128, 20, 30, 0, 128, 40, 50, 255, 128, 60, 70,
                ],
            )),
            None,
        );
        assert!(a.auxiliary.as_ref().unwrap().images == b.auxiliary.as_ref().unwrap().images);
        assert_eq!(
            &a.auxiliary.as_ref().unwrap().images[1].pixels[..4],
            &[128, 255, 255, 85]
        );
    }
    #[test]
    fn internal_pages_share_normal_defaults_and_preserve_data_alpha() {
        let sprite = sprite();
        let material = Material::from_internal_images(
            &sprite,
            None,
            image(1, 1, vec![50, 60, 70, 80]),
            None,
            false,
        );
        let view = material.image(&sprite, 0, 1, 0.5);
        assert!(view.normal.is_none());
        assert!(!view.has_normal);
        assert_eq!(
            view.specular.as_ref().unwrap().pixels.as_ref(),
            [50, 60, 70, 80].repeat(4)
        );
        assert_eq!(material.ior_byte(0, [0.5; 2]), Some(85));
    }
    #[test]
    fn standard_metal_converts_color_once_and_preserves_alpha_and_backings() {
        let mut sprite = sprite();
        sprite.images[0] = image(
            2,
            2,
            vec![
                255, 255, 255, 32, 128, 64, 32, 64, 255, 255, 255, 96, 255, 255, 255, 128,
            ],
        );
        sprite.images[1] = image(1, 1, vec![255, 255, 255, 255]);
        sprite.frames.clear();
        sprite.frame_ends.clear();
        let mut material = Material::from_images(
            &sprite,
            None,
            Some(image(
                2,
                2,
                vec![0, 231, 0, 255, 0, 255, 0, 255, 0, 0, 0, 255, 0, 230, 0, 255],
            )),
        );
        material
            .prepare_color(&sprite, &SourceScene::default())
            .unwrap();
        assert!(material.color_source.is_none());
        let color = material.color_image(&sprite, 0, 0, 0.).unwrap();
        let gold = standard_f0(231).unwrap();
        assert_eq!(
            &color.pixels[..3],
            &gold.map(|v| material::encode(srgb_encode(v)))
        );
        assert_eq!(&color.pixels[4..8], &[128, 64, 32, 64]);
        assert_eq!(
            color
                .pixels
                .iter()
                .skip(3)
                .step_by(4)
                .copied()
                .collect::<Vec<_>>(),
            [32, 64, 96, 128]
        );
        material
            .prepare_color(&sprite, &SourceScene::default())
            .unwrap();
        let again = material.color_image(&sprite, 0, 0, 0.).unwrap();
        assert!(Arc::ptr_eq(&color.pixels, &again.pixels));
        color.validate().unwrap();
    }
    #[test]
    fn nonstandard_sources_do_not_allocate_color_override() {
        for value in [0, 229, 238, 254, 255] {
            let sprite = sprite();
            let mut material =
                Material::from_images(&sprite, None, Some(image(1, 1, vec![0, value, 0, 255])));
            material
                .prepare_color(&sprite, &SourceScene::default())
                .unwrap();
            assert!(material.color_image(&sprite, 0, 1, 0.5).is_none());
        }
    }
    #[test]
    fn standard_color_animation_keeps_complete_frames_and_shared_backing() {
        let sprite = sprite();
        let mut material = Material::from_images(
            &sprite,
            None,
            Some(image(1, 2, vec![0, 230, 0, 255, 0, 231, 0, 255])),
        );
        material
            .prepare_color(&sprite, &SourceScene::default())
            .unwrap();
        let current = material.color_image(&sprite, 0, 1, 0.5).unwrap();
        let next = material.color_image(&sprite, 1, 0, 0.5).unwrap();
        current.validate().unwrap();
        next.validate().unwrap();
        assert!(current.same_backings(&next));
        let sampling = current.sampling.as_ref().unwrap();
        assert_eq!(sampling.blend, 0.5);
        assert_eq!(sampling.coverage_frames.as_ref(), [[0, 0], [0, 2]]);
    }
    #[test]
    fn static_standard_color_uses_actual_atlas_window_and_releases_source_sheet() {
        let mut sprite = sprite();
        sprite.bounds = [0.5, 0., 1., 1.];
        sprite.images[0] = image(2, 2, vec![]);
        sprite.images[1] = image(1, 1, vec![255; 4]);
        sprite.frames.clear();
        sprite.frame_ends.clear();
        let source = image(1, 1, vec![0, 230, 0, 255]);
        let source_owner = Arc::downgrade(&source.pixels);
        let mut material = Material::from_images(&sprite, None, Some(source));
        let mut scene = SourceScene::default();
        let mut pixels = vec![0; 32];
        for at in [8, 12, 24, 28] {
            pixels[at..at + 4].copy_from_slice(&[255, 255, 255, 128]);
        }
        scene
            .set_texture(
                1,
                Texture {
                    width: 4,
                    height: 2,
                    pixels: pixels.into(),
                    region: None,
                    sampling: None,
                    material: None,
                },
            )
            .unwrap();
        material.prepare_color(&sprite, &scene).unwrap();
        assert!(source_owner.upgrade().is_none());
        assert!(material.auxiliary.is_none());
        let color = material.color_image(&sprite, 0, 0, 0.).unwrap();
        assert_eq!(color.region, Some([0, 0, 2, 2]));
        let reflectance = standard_f0(230)
            .unwrap()
            .map(|v| material::encode(srgb_encode(v)));
        for pixel in color.pixels.as_chunks::<4>().0 {
            assert_eq!(&pixel[..3], &reflectance);
            assert_eq!(pixel[3], 128);
        }
    }
    #[test]
    fn mismatched_atlas_window_cannot_publish_standard_or_internal_color() {
        let mut sprite = sprite();
        sprite.images[0] = image(2, 2, vec![]);
        sprite.images[1] = image(1, 1, vec![255; 4]);
        sprite.frames.clear();
        sprite.frame_ends.clear();
        let mut scene = SourceScene::default();
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
        let original = scene.texture(1).unwrap().pixels.clone();
        for bounds in [[0.5, 0., 0.75, 1.], [0.5, 0., 1., 0.5]] {
            sprite.bounds = bounds;
            let mut standard =
                Material::from_images(&sprite, None, Some(image(1, 1, vec![0, 230, 0, 255])));
            assert!(standard.prepare_color(&sprite, &scene).is_err());
            assert!(standard.color_image(&sprite, 0, 0, 0.).is_none());
            let mut internal = Material::from_internal_images(
                &sprite,
                Some(image(2, 2, vec![128; 16])),
                image(1, 1, vec![0; 4]),
                None,
                false,
            );
            assert!(internal.prepare_color(&sprite, &scene).is_err());
            assert!(Arc::ptr_eq(&original, &scene.texture(1).unwrap().pixels));
            assert!(scene.texture(1).unwrap().material.is_none());
        }
    }
    #[test]
    fn reader_rejects_truncated_sources() {
        let sprite = sprite();
        let data = bytes(
            Some(image(1, 1, vec![128, 128, 64, 200])),
            Some(image(1, 1, vec![0, 0, 65, 255])),
        );
        for end in 0..data.len() {
            let pages = [&data[..end]];
            assert!(Material::read(&mut Reader::new(&pages).unwrap(), &sprite).is_err());
        }
        let pages = [&data[..]];
        let mut reader = Reader::new(&pages).unwrap();
        Material::read(&mut reader, &sprite).unwrap();
        reader.finish().unwrap();
    }
}
