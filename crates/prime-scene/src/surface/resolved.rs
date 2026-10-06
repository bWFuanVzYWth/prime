//! Merge resolved sheets only after proving EVERY side/layer's affine map and constant color.
use super::*;
use std::collections::BTreeMap;
struct Group {
    quads: Vec<SurfaceQuad>,
    sources: Vec<SurfaceFace>,
    detail: Option<Arc<SurfaceDetail>>,
    media: [u32; 2],
    optics: Option<Optics>,
    material_thin: bool,
}

impl SurfaceCompiler {
    pub(super) fn merge_resolved(
        &mut self,
        faces: Vec<SurfaceFace>,
    ) -> Result<Vec<SurfaceFace>, String> {
        let mut retained = Vec::new();
        let mut groups = BTreeMap::<Vec<u32>, Group>::new();
        for face in faces {
            let g = &face.geometry;
            if face.repeat.is_some() || g.colors.iter().any(|c| *c != g.colors[0]) {
                retained.push(face);
                continue;
            }
            let geometry = CompiledQuad {
                positions: g.positions,
                uvs: g.uvs,
                color: g.colors[0],
                texture_id: g.texture_id,
                flags: g.flags,
            };
            if rectangles::mapping(&geometry).is_none() {
                retained.push(face);
                continue;
            }
            let mut key = vec![
                face.detail.as_ref().map_or(0, |d| d.mode as u32),
                u32::from(face.optics.is_some()),
                u32::from(face.material_thin),
            ];
            key.extend(face.media);
            if let Some(o) = face.optics {
                key.extend([u32::from(o.thin), u32::from(o.transmit)]);
                key.extend(o.ior_textures.map(|texture| texture.unwrap_or(0)));
                for m in [o.negative, o.positive] {
                    key.push(m.ior.to_bits());
                    key.extend(m.extinction.map(f32::to_bits));
                }
            }
            let mut normalized = face.detail.clone();
            if let Some(detail) = &mut normalized {
                let d = Arc::make_mut(detail);
                if d.layer.repeat.is_some()
                    || d.layer.colors.iter().any(|c| *c != d.layer.colors[0])
                {
                    retained.push(face);
                    continue;
                }
                let second = CompiledQuad {
                    uvs: d.layer.uvs,
                    ..geometry
                };
                let Some(map) = rectangles::mapping(&second) else {
                    retained.push(face);
                    continue;
                };
                key.extend([
                    d.mode as u32,
                    d.layer.texture_id,
                    d.layer.flags,
                    u32::from(d.layer.material_thin),
                    map.axes,
                ]);
                key.extend(d.layer.colors[0].map(f32::to_bits));
                key.extend(
                    [map.origin, map.du, map.dv]
                        .as_flattened()
                        .iter()
                        .map(|v| v.to_bits()),
                );
                key.extend(d.layer.emission.radiance.map(f32::to_bits));
                key.push(
                    u32::from(d.layer.emission.two_sided)
                        | (u32::from(d.layer.emission.textured) * 2),
                );
                d.layer.repeat = Some(map);
            }
            // Media and emission are already part of the primary merge label.
            let group = groups.entry(key).or_insert_with(|| Group {
                quads: Vec::new(),
                sources: Vec::new(),
                detail: normalized,
                media: face.media,
                optics: face.optics,
                material_thin: face.material_thin,
            });
            group.quads.push(SurfaceQuad {
                geometry,
                emission: face.emission,
                provenance: Provenance {
                    domain: 0,
                    source: group.quads.len() as u64,
                },
                rule: SurfaceRule::Preserve,
            });
            group.sources.push(face);
        }
        for (
            _,
            Group {
                quads,
                sources,
                detail,
                media,
                optics,
                material_thin,
            },
        ) in groups
        {
            let (mut merged, originals, _) = self.compile_rectangles(&quads)?;
            for face in &mut merged {
                face.media = media;
                face.optics = optics;
                face.material_thin = material_thin;
                if let Some(d) = &detail {
                    let mut d = (**d).clone();
                    d.layer.uvs = face.geometry.uvs;
                    face.detail = Some(Arc::new(d));
                }
            }
            for i in originals {
                merged.push(sources[i].clone());
            }
            retained.extend(merged);
        }
        Ok(retained)
    }
}
