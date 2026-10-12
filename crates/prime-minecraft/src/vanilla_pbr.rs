//! Frozen vanilla material IDs and PNG frame sheets. General Bedrock pack loading is separate.
use crate::sprite::Image;
use std::{io::Cursor, sync::OnceLock};

pub(crate) struct Images {
    /// None shares the actual host albedo/alpha; no embedded copy or color conversion.
    pub color: Option<Image>,
    /// Schema 1: continuous metalness, emission, perceptual roughness, subsurface.
    pub mers: Image,
    /// Schema 1: normal XY, GGX distribution roughness, IOR (1 + 1.5 * A/255).
    /// None uses [128, 128, 0, 85] and no authored tangent normal.
    pub auxiliary: Option<Image>,
    pub authored_normal: bool,
}

struct Entry {
    name: &'static str,
    game_version: u32,
    extent: [u32; 2],
    mers_png: &'static [u8],
    auxiliary_png: Option<&'static [u8]>,
    authored_normal: bool,
    decoded: OnceLock<Result<Images, String>>,
}

impl Entry {
    const fn new(
        name: &'static str,
        game_version: u32,
        extent: [u32; 2],
        mers_png: &'static [u8],
        auxiliary_png: Option<&'static [u8]>,
        authored_normal: bool,
    ) -> Self {
        Self {
            name,
            game_version,
            extent,
            mers_png,
            auxiliary_png,
            authored_normal,
            decoded: OnceLock::new(),
        }
    }
    fn images(&self) -> Result<Images, String> {
        let cached = self.decoded.get_or_init(|| {
            let mers = decode_rgba8(self.mers_png)?;
            let auxiliary = self.auxiliary_png.map(decode_rgba8).transpose()?;
            if [mers.width, mers.height] != self.extent
                || auxiliary
                    .as_ref()
                    .is_some_and(|a| [a.width, a.height] != self.extent)
                || self.authored_normal && auxiliary.is_none()
            {
                return Err("PNG pages disagree with the frozen material layout".into());
            }
            Ok(Images {
                color: None,
                mers,
                auxiliary,
                authored_normal: self.authored_normal,
            })
        });
        match cached {
            Ok(value) => Ok(Images {
                color: None,
                mers: share(&value.mers),
                auxiliary: value.auxiliary.as_ref().map(share),
                authored_normal: value.authored_normal,
            }),
            Err(error) => Err(format!("{} ({}): {error}", self.name, self.game_version)),
        }
    }
}

// Sorted for lookup. Identity is an explicit Java ResourceLocation, never inferred
// from a Bedrock filename or a block/material substring.
include!("../assets/vanilla_pbr/registry.rs");

fn decode_rgba8(bytes: &[u8]) -> Result<Image, String> {
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("Missing material PNG bytes; download Git LFS objects before building".into());
    }
    let decoder = png::Decoder::new(Cursor::new(bytes));
    let mut reader = decoder.read_info().map_err(|e| e.to_string())?;
    let info = reader.info();
    if info.width > 16384 || info.height > 16384 {
        return Err("Embedded material PNG exceeds the source image extent".into());
    }
    if info.bit_depth != png::BitDepth::Eight || info.color_type != png::ColorType::Rgba {
        return Err("Internal material PNG must be 8-bit RGBA".into());
    }
    if info.animation_control.is_some() {
        return Err("Material animations use PNG frame sheets, not APNG".into());
    }
    let mut pixels = vec![
        0;
        reader
            .output_buffer_size()
            .ok_or("PNG buffer size overflow")?
    ];
    let output = reader.next_frame(&mut pixels).map_err(|e| e.to_string())?;
    pixels.truncate(output.buffer_size());
    // png::Decoder leaves samples unchanged: no ICC/gamma, sRGB or working-RGB
    // conversion applies to these raw material-data channels.
    Ok(Image {
        width: output.width,
        height: output.height,
        pixels: pixels.into(),
    })
}

fn share(image: &Image) -> Image {
    Image {
        width: image.width,
        height: image.height,
        pixels: image.pixels.clone(),
    }
}

fn find<'a>(entries: &'a [Entry], name: &str, game_version: u32) -> Option<&'a Entry> {
    entries
        .binary_search_by(|entry| (entry.name, entry.game_version).cmp(&(name, game_version)))
        .or_else(|_| {
            entries.binary_search_by(|entry| (entry.name, entry.game_version).cmp(&(name, 0)))
        })
        .ok()
        .map(|index| &entries[index])
}
pub(crate) fn images_for_version(
    java_sprite: &str,
    game_version: u32,
) -> Result<Option<Images>, String> {
    find(&PRESETS, java_sprite, game_version)
        .map(Entry::images)
        .transpose()
}
#[cfg(test)]
pub(crate) fn images(java_sprite: &str) -> Result<Option<Images>, String> {
    images_for_version(java_sprite, 262)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{TerrainContext, sprite};
    use prime_abi::{minecraft::Resources, *};
    use prime_scene::SourceScene;
    use std::sync::Arc;

    fn png_bytes(color: png::ColorType, depth: png::BitDepth, pixels: &[u8]) -> Vec<u8> {
        png_sheet([16, 16], color, depth, pixels)
    }
    fn png_sheet(
        extent: [u32; 2],
        color: png::ColorType,
        depth: png::BitDepth,
        pixels: &[u8],
    ) -> Vec<u8> {
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, extent[0], extent[1]);
            encoder.set_color(color);
            encoder.set_depth(depth);
            encoder.set_source_gamma(png::ScaledFloat::new(0.45455));
            encoder
                .write_header()
                .unwrap()
                .write_image_data(pixels)
                .unwrap();
        }
        bytes
    }

    #[test]
    fn embedded_pngs_decode_and_share_owned_pixels() {
        assert!(
            PRESETS
                .windows(2)
                .all(|e| { (e[0].name, e[0].game_version) < (e[1].name, e[1].game_version) })
        );
        for entry in &PRESETS {
            let first = entry.images().unwrap();
            let second = entry.images().unwrap();
            assert_eq!([first.mers.width, first.mers.height], entry.extent);
            assert_eq!(
                first.mers.pixels.len(),
                (entry.extent[0] * entry.extent[1] * 4) as usize
            );
            assert!(Arc::ptr_eq(&first.mers.pixels, &second.mers.pixels));
            assert!(first.color.is_none());
            assert_eq!(first.auxiliary.is_some(), entry.auxiliary_png.is_some());
            assert_eq!(first.authored_normal, entry.authored_normal);
            if let Some(auxiliary) = &first.auxiliary {
                assert_eq!([auxiliary.width, auxiliary.height], entry.extent);
                assert!(Arc::ptr_eq(
                    &auxiliary.pixels,
                    &second.auxiliary.as_ref().unwrap().pixels
                ));
            }
        }
        for unknown in [
            "stone",
            "minecraft:block/__primept_unregistered__",
            "example:block/stone",
        ] {
            assert!(images(unknown).unwrap().is_none());
        }
    }

    #[test]
    fn manifest_matches_runtime_resource_id_and_layout() {
        let manifest: serde_json::Value =
            serde_json::from_str(include_str!("../assets/vanilla_pbr/manifest.json")).unwrap();
        assert_eq!(manifest["schema"], 1);
        let entries = manifest["entries"].as_array().unwrap();
        assert_eq!(entries.len(), PRESETS.len());
        for (asset, entry) in entries.iter().zip(&PRESETS) {
            assert_eq!(asset["java_id"], entry.name);
            assert_eq!(
                asset["game_version"].as_u64().unwrap_or(0),
                u64::from(entry.game_version)
            );
            assert_eq!(asset["extent"], serde_json::json!(entry.extent));
            assert_eq!(asset["base"], "host");
            assert_eq!(asset["auxiliary"].is_null(), entry.auxiliary_png.is_none());
            assert_eq!(asset["authored_normal"], entry.authored_normal);
            let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("assets/vanilla_pbr");
            assert_eq!(
                std::fs::read(root.join(asset["mers"].as_str().unwrap())).unwrap(),
                entry.mers_png
            );
            if let Some(auxiliary) = entry.auxiliary_png {
                assert_eq!(
                    std::fs::read(root.join(asset["auxiliary"].as_str().unwrap())).unwrap(),
                    auxiliary
                );
            }
        }
    }

    #[test]
    fn png_loader_preserves_raw_data_despite_color_metadata() {
        let pixels = [23, 45, 67, 89].repeat(16 * 16);
        let encoded = png_bytes(png::ColorType::Rgba, png::BitDepth::Eight, &pixels);
        let decoded = decode_rgba8(&encoded).unwrap();
        assert_eq!(&*decoded.pixels, pixels);
        let rectangular = [3, 20, 250, 17].repeat(2 * 6);
        let encoded = png_sheet(
            [2, 6],
            png::ColorType::Rgba,
            png::BitDepth::Eight,
            &rectangular,
        );
        let decoded = decode_rgba8(&encoded).unwrap();
        assert_eq!([decoded.width, decoded.height], [2, 6]);
        assert_eq!(&*decoded.pixels, rectangular);
    }

    #[test]
    fn version_selection_never_substitutes_another_version_layout() {
        let entries = [
            Entry::new("minecraft:block/a", 0, [2, 2], &[], None, false),
            Entry::new("minecraft:block/a", 262, [2, 4], &[], None, false),
            Entry::new("minecraft:block/b", 263, [2, 6], &[], None, false),
        ];
        assert_eq!(
            find(&entries, "minecraft:block/a", 262).unwrap().extent,
            [2, 4]
        );
        assert_eq!(
            find(&entries, "minecraft:block/a", 263).unwrap().extent,
            [2, 2]
        );
        assert_eq!(
            find(&entries, "minecraft:block/a", 999).unwrap().extent,
            [2, 2]
        );
        assert!(find(&entries, "minecraft:block/b", 262).is_none());
        assert!(find(&entries, "minecraft:block/b", 999).is_none());
        assert!(find(&entries, "minecraft:block/c", 263).is_none());
    }

    struct Fixture {
        raw: PrimeMcResourceBatch,
        bytes: Vec<u8>,
        images: Vec<PrimeMcImage>,
        frames: Vec<PrimeMcAnimationFrame>,
        sprites: Vec<PrimeMcSprite>,
    }
    impl Fixture {
        fn new(name: &str, version: u32, sheet: [u32; 2], extent: [u32; 2], private: bool) -> Self {
            let host = [111, 93, 64, 71].repeat((sheet[0] * sheet[1]) as usize);
            let count = host.len() as u64;
            let mut bytes = host;
            let atlas_offset = bytes.len() as u64;
            bytes.extend([5, 6, 7, 255].repeat((extent[0] * extent[1]) as usize));
            let name_offset = bytes.len() as u64;
            bytes.extend(name.as_bytes());
            let images = vec![PrimeMcImage {
                width: sheet[0],
                height: sheet[1],
                pixels: PrimeMcRange { offset: 0, count },
            }];
            Self {
                raw: PrimeMcResourceBatch {
                    identity: PrimeMcIdentity {
                        struct_size: std::mem::size_of::<PrimeMcResourceBatch>() as u32,
                        abi_version: PRIME_ABI_VERSION,
                        source_version: PRIME_MC_SOURCE_VERSION,
                        game_version: version,
                        epoch: 1,
                        resource_generation: 1,
                        ..Default::default()
                    },
                    flags: PRIME_MC_RESOURCE_REPLACE,
                    atlas: PrimeMcImage {
                        width: extent[0],
                        height: extent[1],
                        pixels: PrimeMcRange {
                            offset: atlas_offset,
                            count: u64::from(extent[0]) * u64::from(extent[1]) * 4,
                        },
                    },
                    ..Default::default()
                },
                sprites: vec![PrimeMcSprite {
                    id: 1,
                    flags: PRIME_MC_SPRITE_VANILLA_COLOR
                        | if private {
                            PRIME_MC_SPRITE_PRIVATE_COLOR
                        } else {
                            0
                        },
                    bounds: [0., 0., 1., 1.],
                    extent,
                    name: PrimeMcRange {
                        offset: name_offset,
                        count: name.len() as u64,
                    },
                    images: PrimeMcRange {
                        offset: 0,
                        count: 1,
                    },
                    normal_image: u32::MAX,
                    specular_image: u32::MAX,
                    ..Default::default()
                }],
                bytes,
                images,
                frames: Vec::new(),
            }
        }
        fn animation(&mut self, animation: &serde_json::Value) {
            if animation.is_null() {
                return;
            }
            self.frames = animation["frames"]
                .as_array()
                .unwrap()
                .iter()
                .map(|f| PrimeMcAnimationFrame {
                    frame: f[0].as_u64().unwrap() as u32,
                    duration: f[1].as_u64().unwrap() as u32,
                })
                .collect();
            self.sprites[0].frames.count = self.frames.len() as u64;
            self.sprites[0].interpolate = u32::from(animation["interpolate"].as_bool().unwrap());
        }
        fn source(&mut self) -> Resources<'_> {
            self.raw.images = self.images.as_ptr();
            self.raw.image_count = self.images.len() as u64;
            self.raw.sprites = self.sprites.as_ptr();
            self.raw.sprite_count = self.sprites.len() as u64;
            self.raw.frames = self.frames.as_ptr();
            self.raw.frame_count = self.frames.len() as u64;
            self.raw.bytes = self.bytes.as_ptr();
            self.raw.byte_count = self.bytes.len() as u64;
            Resources::from_parts(
                &self.raw,
                &[],
                &[],
                &[],
                &[],
                &[],
                &[],
                &self.sprites,
                &self.images,
                &self.frames,
                &[],
                &[],
                &self.bytes,
            )
            .unwrap()
        }
    }
    fn scene() -> SourceScene {
        let mut scene = SourceScene::default();
        scene.reset_world(1).unwrap();
        scene
    }

    #[test]
    fn every_embedded_sheet_reaches_resource_preparation_and_actual_animation_frames() {
        let manifest: serde_json::Value =
            serde_json::from_str(include_str!("../assets/vanilla_pbr/manifest.json")).unwrap();
        for (entry, metadata) in PRESETS.iter().zip(manifest["entries"].as_array().unwrap()) {
            let extent = metadata["frame_extent"]
                .as_array()
                .map_or(entry.extent, |v| {
                    [v[0].as_u64().unwrap() as u32, v[1].as_u64().unwrap() as u32]
                });
            let private = entry.name.starts_with("minecraft:item/");
            let version = if entry.game_version == 0 {
                262
            } else {
                entry.game_version
            };
            let mut fixture = Fixture::new(entry.name, version, entry.extent, extent, private);
            fixture.animation(&metadata["animation"]);
            let mut context = TerrainContext::default();
            let mut scene = scene();
            context
                .prepare_resources(&fixture.source(), &mut scene)
                .unwrap();
            let prepared = &context.catalog.sprites[&1];
            assert_eq!(prepared.block_atlas, !private, "{}", entry.name);
            let host = prepared.images[0].pixels.clone();
            let data = entry.images().unwrap();
            assert_eq!(
                prepared.emission_maximum(),
                {
                    let maximum = f32::from(
                        data.mers
                            .pixels
                            .as_chunks::<4>()
                            .0
                            .iter()
                            .map(|p| p[1])
                            .max()
                            .unwrap(),
                    ) / 255.;
                    (maximum != 0.).then_some(maximum)
                },
                "{}",
                entry.name
            );
            assert_eq!(
                prepared.material.as_ref().unwrap().has_subsurface(),
                data.mers
                    .pixels
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .any(|p| p[3] != 0),
                "{}",
                entry.name
            );
            assert_eq!(
                &*host,
                [111, 93, 64, 71].repeat((entry.extent[0] * entry.extent[1]) as usize)
            );
            let mut tick = 0;
            for (index, frame) in fixture.frames.iter().enumerate() {
                for at in [tick, tick + u64::from(frame.duration / 2)] {
                    let color = prepared.image(at, &scene).unwrap();
                    assert!(Arc::ptr_eq(&color.pixels, &host));
                    let column = entry.extent[0] / extent[0];
                    assert_eq!(
                        color.region,
                        Some([
                            frame.frame % column * extent[0],
                            frame.frame / column * extent[1],
                            extent[0],
                            extent[1],
                        ]),
                        "{} frame {index}",
                        entry.name
                    );
                    let material = color.material.as_ref().unwrap();
                    assert_eq!(material.has_normal, entry.authored_normal);
                    let mers = material.specular.as_ref().unwrap();
                    let next = &fixture.frames[(index + 1) % fixture.frames.len()];
                    let sampling = color.sampling.as_ref().unwrap();
                    assert_eq!(
                        sampling.next,
                        [
                            next.frame % column * extent[0],
                            next.frame / column * extent[1]
                        ]
                    );
                    if sampling.blend == 0. || frame.frame == next.frame {
                        assert_eq!(
                            &*mers.pixels, &*data.mers.pixels,
                            "{} frame {index}",
                            entry.name
                        );
                        assert_eq!(mers.region, color.region);
                    } else {
                        assert_eq!(mers.region, Some([0, 0, extent[0], extent[1]]));
                        let progress = (sampling.blend * 1000.) as u32;
                        for y in 0..extent[1] {
                            for x in 0..extent[0] {
                                let source = |f: u32| -> [u8; 4] {
                                    let at = ((f / column * extent[1] + y) * entry.extent[0]
                                        + f % column * extent[0]
                                        + x) as usize
                                        * 4;
                                    data.mers.pixels[at..at + 4].try_into().unwrap()
                                };
                                let at = (y * extent[0] + x) as usize * 4;
                                assert_eq!(
                                    &mers.pixels[at..at + 4],
                                    &crate::material::blend_continuous(
                                        source(frame.frame),
                                        source(next.frame),
                                        progress
                                    ),
                                    "{} frame {index}",
                                    entry.name
                                );
                            }
                        }
                    }
                }
                tick += u64::from(frame.duration);
            }
            let color = scene.texture(sprite::texture(1)).unwrap();
            assert!(Arc::ptr_eq(&color.pixels, &host));
            assert_eq!(
                &*color
                    .material
                    .as_ref()
                    .unwrap()
                    .specular
                    .as_ref()
                    .unwrap()
                    .pixels,
                &*data.mers.pixels,
                "{}",
                entry.name
            );
            if private {
                assert!(scene.texture(1).unwrap().material.is_none());
            }
        }
    }

    #[test]
    fn private_sprites_keep_host_color_and_never_overwrite_block_atlas_lookup() {
        let mut fixture = Fixture::new("minecraft:block/stone", 262, [16, 16], [16, 16], false);
        fixture.sprites.push(PrimeMcSprite {
            id: 2,
            flags: PRIME_MC_SPRITE_VANILLA_COLOR | PRIME_MC_SPRITE_PRIVATE_COLOR,
            ..fixture.sprites[0]
        });
        let mut context = TerrainContext::default();
        let mut scene = scene();
        context
            .prepare_resources(&fixture.source(), &mut scene)
            .unwrap();
        assert!(!context.catalog.sprites[&2].block_atlas);
        let color = scene.texture(sprite::texture(2)).unwrap();
        assert_eq!(&color.pixels[..4], &[111, 93, 64, 71]);
        assert!(!Arc::ptr_eq(
            &color.pixels,
            &scene.texture(1).unwrap().pixels
        ));
        assert!(color.material.as_ref().unwrap().specular.is_some());
        let lookup = scene
            .texture(1)
            .unwrap()
            .material
            .as_ref()
            .unwrap()
            .coverage
            .as_ref()
            .unwrap();
        assert!(
            lookup
                .pixels
                .as_chunks::<4>()
                .0
                .iter()
                .all(|v| u32::from_le_bytes(*v) == sprite::texture(1))
        );

        fixture.raw.flags = 0;
        fixture.raw.atlas = Default::default();
        fixture.sprites[1].images = PrimeMcRange {
            offset: 1,
            count: 1,
        };
        fixture.images.push(PrimeMcImage {
            width: 16,
            height: 16,
            ..Default::default()
        });
        let revision = scene.revision();
        assert!(
            context
                .prepare_resources(&fixture.source(), &mut scene)
                .is_err()
        );
        assert_eq!(scene.revision(), revision);
        assert_eq!(
            &scene.texture(sprite::texture(2)).unwrap().pixels[..4],
            &[111, 93, 64, 71]
        );
    }

    #[test]
    fn private_sprite_external_normal_group_takes_priority_and_wrong_layout_uses_default() {
        let mut fixture = Fixture::new("minecraft:block/stone", 262, [16, 16], [16, 16], true);
        let offset = fixture.bytes.len() as u64;
        fixture.bytes.extend([128, 128, 255, 100]);
        fixture.images.push(PrimeMcImage {
            width: 1,
            height: 1,
            pixels: PrimeMcRange { offset, count: 4 },
        });
        fixture.sprites[0].normal_image = 1;
        let mut context = TerrainContext::default();
        let mut source = scene();
        context
            .prepare_resources(&fixture.source(), &mut source)
            .unwrap();
        let material = source
            .texture(sprite::texture(1))
            .unwrap()
            .material
            .as_ref()
            .unwrap();
        assert!(material.has_normal);
        assert!(material.specular.is_none());
        assert!(source.texture(1).unwrap().material.is_none());

        for flags in [0, PRIME_MC_SPRITE_VANILLA_COLOR] {
            let mut fixture = Fixture::new("minecraft:block/stone", 262, [8, 8], [8, 8], true);
            fixture.sprites[0].flags = PRIME_MC_SPRITE_PRIVATE_COLOR | flags;
            let mut context = TerrainContext::default();
            let mut source = scene();
            context
                .prepare_resources(&fixture.source(), &mut source)
                .unwrap();
            assert!(
                source
                    .texture(sprite::texture(1))
                    .unwrap()
                    .material
                    .is_none()
            );
            assert!(source.texture(1).unwrap().material.is_none());
        }
    }

    #[test]
    fn version_specific_preset_is_only_prepared_for_its_actual_game_version() {
        for (version, expected) in [(262, false), (263, true)] {
            let mut fixture = Fixture::new(
                "minecraft:block/poplar_planks",
                version,
                [16, 16],
                [16, 16],
                true,
            );
            let mut context = TerrainContext::default();
            let mut source = scene();
            context
                .prepare_resources(&fixture.source(), &mut source)
                .unwrap();
            assert_eq!(
                source
                    .texture(sprite::texture(1))
                    .unwrap()
                    .material
                    .is_some(),
                expected
            );
            assert!(source.texture(1).unwrap().material.is_none());
        }
    }

    #[test]
    fn actual_metal_emission_and_subsurface_examples_reach_material_and_light_support() {
        use crate::model::{Model, Quad, State};
        let manifest: serde_json::Value =
            serde_json::from_str(include_str!("../assets/vanilla_pbr/manifest.json")).unwrap();
        for (name, channel, fractional) in [
            ("minecraft:block/gold_block", 0, false),
            ("minecraft:block/iron_block", 0, false),
            ("minecraft:block/copper_block", 0, true),
            ("minecraft:block/redstone_lamp_on", 1, false),
            ("minecraft:block/magma", 1, false),
            ("minecraft:block/fire_0", 1, false),
            ("minecraft:block/oak_leaves", 3, false),
        ] {
            let entry = find(&PRESETS, name, 262).unwrap();
            let data = entry.images().unwrap();
            assert!(
                data.mers
                    .pixels
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .any(|p| { p[channel] != 0 && (!fractional || p[channel] != 255) }),
                "missing example channel in {name}"
            );
            let metadata = manifest["entries"]
                .as_array()
                .unwrap()
                .iter()
                .find(|m| m["java_id"] == name && m["game_version"] == entry.game_version)
                .unwrap();
            let frame = metadata["frame_extent"].as_array().unwrap();
            let extent = [
                frame[0].as_u64().unwrap() as u32,
                frame[1].as_u64().unwrap() as u32,
            ];
            let mut fixture = Fixture::new(name, 262, entry.extent, extent, false);
            fixture.animation(&metadata["animation"]);
            let mut context = TerrainContext::default();
            let mut source = scene();
            context
                .prepare_resources(&fixture.source(), &mut source)
                .unwrap();
            let actual = source
                .texture(sprite::texture(1))
                .unwrap()
                .material
                .as_ref()
                .unwrap()
                .specular
                .as_ref()
                .unwrap();
            assert_eq!(&*actual.pixels, &*data.mers.pixels);
            if channel == 1 {
                let expected = 1.5 * context.catalog.sprites[&1].emission_maximum().unwrap();
                let state = State {
                    id: 1,
                    model: 1,
                    ..Default::default()
                };
                context.catalog.states.insert(1, state.clone());
                context.catalog.models.insert(
                    1,
                    Model::Mesh(vec![Quad {
                        positions: [[0., 0., 0.], [1., 0., 0.], [1., 1., 0.], [0., 1., 0.]],
                        uvs: [[0., 0.], [1., 0.], [1., 1.], [0., 1.]],
                        face: 6,
                        tint: -1,
                        layer: 0,
                        sprite: 1,
                        emission: 0,
                    }]),
                );
                context.catalog.prepare();
                let mut layers = Default::default();
                let mut surfaces = Default::default();
                context.catalog.emit(
                    &state,
                    [0; 3],
                    127,
                    &mut layers,
                    &mut Default::default(),
                    &mut Default::default(),
                    &mut surfaces,
                );
                assert!(layers[0].is_empty());
                assert_eq!(surfaces[0].len(), 1);
                assert_eq!(surfaces[0][0].emission.radiance, [expected; 3]);
                let mesh = prime_scene::surface::SurfaceMesh::from_resolved(1, surfaces[0].clone())
                    .unwrap();
                assert_eq!(mesh.lights.emitters.len(), 1);
                assert_eq!(mesh.lights.emitters[0].power, expected);
            }
        }
    }

    #[test]
    fn embedded_lava_zero_emission_preserves_native_fluid_faces_and_light_support() {
        use crate::{
            Job, SectionData,
            contact::{Cache, Context},
            fluid::{Fluid, FluidMaterial},
            model::State,
            schedule::Section,
        };
        use std::collections::{HashMap, HashSet};
        let manifest: serde_json::Value =
            serde_json::from_str(include_str!("../assets/vanilla_pbr/manifest.json")).unwrap();
        for name in ["minecraft:block/lava_still", "minecraft:block/lava_flow"] {
            for version in [262, 263] {
                for external_zero in [false, true] {
                    let entry = find(&PRESETS, name, version).unwrap();
                    let metadata = manifest["entries"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .find(|m| m["java_id"] == name && m["game_version"] == entry.game_version)
                        .unwrap();
                    let extent = [
                        metadata["frame_extent"][0].as_u64().unwrap() as u32,
                        metadata["frame_extent"][1].as_u64().unwrap() as u32,
                    ];
                    let data = entry.images().unwrap();
                    assert!(
                        data.mers
                            .pixels
                            .as_chunks::<4>()
                            .0
                            .iter()
                            .all(|p| p[1] == 0)
                    );
                    let mut fixture = Fixture::new(name, version, entry.extent, extent, false);
                    fixture.animation(&metadata["animation"]);
                    if external_zero {
                        let offset = fixture.bytes.len() as u64;
                        fixture.bytes.extend([0, 4, 0, 0]);
                        fixture.images.push(PrimeMcImage {
                            width: 1,
                            height: 1,
                            pixels: PrimeMcRange { offset, count: 4 },
                        });
                        fixture.sprites[0].specular_image = 1;
                    }
                    let mut context = TerrainContext::default();
                    let mut scene = scene();
                    context
                        .prepare_resources(&fixture.source(), &mut scene)
                        .unwrap();
                    let prepared = &context.catalog.sprites[&1];
                    assert_eq!(prepared.emission_maximum(), external_zero.then_some(0.));
                    let view = scene
                        .texture(sprite::texture(1))
                        .unwrap()
                        .material
                        .as_ref()
                        .unwrap();
                    assert_eq!(view.authored_emission, external_zero);
                    assert!(view.specular.is_some());
                    if !external_zero {
                        assert_eq!(&*view.specular.as_ref().unwrap().pixels, &*data.mers.pixels);
                    }
                    context.catalog.states.insert(
                        0,
                        State {
                            flags: 1,
                            ..Default::default()
                        },
                    );
                    context.catalog.states.insert(
                        1,
                        State {
                            id: 1,
                            name: "minecraft:lava".into(),
                            flags: 16,
                            emission: 15,
                            fluid: Fluid {
                                kind: 2,
                                amount: 8,
                                falling: false,
                                material: 1,
                            },
                            ..Default::default()
                        },
                    );
                    let (id, material) = FluidMaterial::from_typed(&prime_abi::PrimeMcFluid {
                        id: 1,
                        identities: [1; 3],
                        bounds: [0., 0., 1., 1., 0., 0., 1., 1., 0., 0., 1., 1.],
                        ..Default::default()
                    })
                    .unwrap();
                    context.catalog.fluids.insert(id, material);
                    context.catalog.prepare();
                    let p = [8; 3];
                    let at = (p[1] * 256 + p[2] * 16 + p[0]) as usize;
                    let mut storage = vec![0; 64];
                    storage[at / 64] |= 1 << (at % 64);
                    let sections = HashMap::from([(
                        Section(0, 0, 0),
                        SectionData {
                            palette: vec![0, 1],
                            storage,
                            bits: 1,
                            per_word: 64,
                        },
                    )]);
                    let cells = HashSet::from([[0; 3]]);
                    let contacts = Context {
                        catalog: &context.catalog,
                        sections: &sections,
                        cells: &cells,
                        origin: [0; 3],
                    };
                    let mut job = Job {
                        key: Section(0, 0, 0),
                        first_y: 0,
                        layers: Default::default(),
                        surfaces: Default::default(),
                        hacks: Default::default(),
                        compiled: None,
                        tints: Default::default(),
                        color_start: 0,
                    };
                    contacts.emit(&mut Cache::new(), &mut job, p, 127);
                    let faces: Vec<_> = job.layers[0]
                        .iter()
                        .copied()
                        .map(prime_scene::surface::SurfaceFace::from_quad)
                        .chain(job.surfaces[0].iter().cloned())
                        .collect();
                    assert_eq!(
                        faces.len(),
                        6,
                        "{name} version{version} external={external_zero}"
                    );
                    let expected = if external_zero { [0.; 3] } else { [1.5; 3] };
                    assert!(faces.iter().all(|face| face.emission.radiance == expected));
                    let mesh = prime_scene::surface::SurfaceMesh::from_resolved(1, faces).unwrap();
                    assert_eq!(
                        mesh.lights.emitters.len(),
                        if external_zero { 0 } else { 6 }
                    );
                    assert!(mesh.lights.emitters.iter().all(|e| e.power > 0.));
                }
            }
        }
    }

    #[test]
    fn invalid_png_layout_and_truncated_payload_return_errors() {
        let rgb = png_bytes(png::ColorType::Rgb, png::BitDepth::Eight, &[0; 16 * 16 * 3]);
        assert!(decode_rgba8(&rgb).is_err());
        let sixteen = png_bytes(
            png::ColorType::Rgba,
            png::BitDepth::Sixteen,
            &[0; 16 * 16 * 8],
        );
        assert!(decode_rgba8(&sixteen).is_err());
        assert!(decode_rgba8(b"version https://git-lfs.github.com/spec/v1\n").is_err());
        let rgba = png_bytes(
            png::ColorType::Rgba,
            png::BitDepth::Eight,
            &[0; 16 * 16 * 4],
        );
        assert!(decode_rgba8(&rgba[..rgba.len() / 2]).is_err());
    }
}
