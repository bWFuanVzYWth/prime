//! Named C batches after pointer/length validation at the FFI boundary.
use super::*;
use prime_abi::scene::{DynamicView, InstancesView, MeshView, TexturesView};

fn origin(value: [f64; 3]) -> Result<(), String> {
    if value
        .iter()
        .any(|v| !v.is_finite() || v.abs() > 32_000_000.0)
    {
        return Err("invalid world position".into());
    }
    Ok(())
}
fn payload_length(span: MeshView<'_>) -> Result<(), String> {
    let s = span.descriptor;
    if (s.vertex_count as usize).checked_mul(s.stride as usize) != Some(span.vertices.len()) {
        return Err("vertex payload length does not match its declared layout".into());
    }
    Ok(())
}
fn layout(span: MeshView<'_>, billboards: bool) -> Result<VertexLayout, String> {
    let s = span.descriptor;
    validate_flags(s.flags)?;
    let layout = VertexLayout {
        count: s.vertex_count as usize,
        stride: s.stride as usize,
        topology: s.topology as usize,
        position_offset: s.position_offset as usize,
        color_offset: s.color_offset as usize,
        uv_offset: s.uv_offset as usize,
    };
    if billboards && layout.topology == 1 {
        if layout.stride != 52
            || layout.position_offset != 0
            || layout.color_offset != 48
            || layout.uv_offset != 32
        {
            return Err("invalid billboard source layout".into());
        }
    } else {
        layout.validate()?;
    }
    payload_length(span)?;
    Ok(layout)
}

impl SourceScene {
    fn typed_epoch(&self, epoch: u64) -> Result<(), String> {
        if epoch == 0 || epoch != self.epoch {
            return Err("stale or uninitialized resource epoch".into());
        }
        Ok(())
    }
    pub fn submit_textures_typed(&mut self, view: TexturesView<'_>) -> Result<(), String> {
        self.typed_epoch(view.epoch())?;
        let mut textures = Vec::new();
        textures
            .try_reserve(view.textures().len())
            .map_err(|_| "texture batch allocation failed")?;
        for source in view.textures() {
            let s = source.descriptor;
            if s.reserved != 0 || self.is_resource_texture(s.id) {
                return Err("dynamic texture batch cannot overwrite a resource texture or use reserved fields".into());
            }
            if s.id == 0
                || s.id == u32::MAX
                || s.width == 0
                || s.height == 0
                || s.width > 16384
                || s.height > 16384
                || source.pixels.len() != s.width as usize * s.height as usize * 4
            {
                return Err("invalid texture identity, extent or payload".into());
            }
            textures.push((
                s.id,
                Texture {
                    region: None,
                    sampling: None,
                    material: None,
                    width: s.width,
                    height: s.height,
                    pixels: source.pixels.into(),
                },
            ));
        }
        self.set_textures(textures)
    }
    pub fn retire_textures_typed(&mut self, epoch: u64, ids: &[u32]) -> Result<(), String> {
        self.typed_epoch(epoch)?;
        if ids
            .iter()
            .any(|&id| id <= 1 || id == u32::MAX || self.is_resource_texture(id))
        {
            return Err("cannot retire a reserved or resident resource texture".into());
        }
        for &id in ids {
            if self.textures.contains_key(&id) {
                self.texture_lifetime.retire(id);
            }
        }
        Ok(())
    }
    pub fn submit_instances_typed(&mut self, view: InstancesView<'_>) -> Result<(), String> {
        self.typed_epoch(view.batch().epoch)?;
        std::mem::swap(
            &mut self.texture_lifetime,
            &mut self.instances.texture_lifetime,
        );
        let result = self.instances.submit_typed(
            view,
            &self.textures,
            self.triangle_count + self.dynamic.triangles.len(),
        );
        std::mem::swap(
            &mut self.texture_lifetime,
            &mut self.instances.texture_lifetime,
        );
        result
    }
    pub fn submit_dynamic_typed(&mut self, view: DynamicView<'_>) -> Result<(), String> {
        let b = view.batch();
        self.typed_epoch(b.epoch)?;
        origin(b.origin)?;
        if b.sequence <= self.dynamic.sequence {
            return Err("dynamic frame sequence must increase within its resource epoch".into());
        }
        // Canonical owned content proof contains named scalar values and authored vertex bytes.
        // Pointer values, C padding and origin are deliberately excluded. No temporary copy is made.
        let proof_len = view.spans().try_fold(0usize, |n, s| {
            // The declared shape fixes each span's boundary in the content proof.
            // Without this check a caller could merge later descriptors into a payload.
            payload_length(s)?;
            n.checked_add(32 + s.vertices.len())
                .ok_or_else(|| "dynamic proof size overflow".to_string())
        })?;
        let mut offset = 0;
        let same = self.dynamic.source.len() == proof_len
            && view.spans().all(|s| {
                let words = s.words().map(u32::to_le_bytes);
                let equal = words.iter().all(|word| {
                    let equal =
                        self.dynamic.source.get(offset..offset + 4) == Some(word.as_slice());
                    offset += 4;
                    equal
                });
                let equal = equal
                    && self.dynamic.source.get(offset..offset + s.vertices.len())
                        == Some(s.vertices);
                offset += s.vertices.len();
                equal
            });
        if same {
            self.dynamic.sequence = b.sequence;
            if self.dynamic.origin.map(f64::to_bits) != b.origin.map(f64::to_bits) {
                self.dynamic.origin = b.origin;
                self.dynamic.revision = b.sequence;
            }
            return Ok(());
        }
        let spare = self
            .dynamic_spares
            .iter()
            .position(|s| std::sync::Arc::strong_count(s) == 1);
        let mut storage = spare
            .map(|i| self.dynamic_spares.swap_remove(i))
            .unwrap_or_default();
        self.dynamic_spares
            .retain(|s| std::sync::Arc::strong_count(s) != 1);
        let triangles = std::sync::Arc::get_mut(&mut storage).unwrap();
        triangles.clear();
        let mut texture_ids = std::collections::BTreeSet::new();
        let decoded = (|| {
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for s in view.spans() {
                let l = layout(s, true)?;
                let d = s.descriptor;
                if l.count != 0 && d.texture_id != 0 && !self.textures.contains_key(&d.texture_id) {
                    return Err(
                        "dynamic frame references a texture that has not been captured".into(),
                    );
                }
                if l.count > 0 {
                    texture_ids.insert(d.texture_id);
                }
                let count = if l.topology == 1 {
                    l.count.checked_mul(2).ok_or("billboard count overflow")?
                } else {
                    l.count / l.topology * (l.topology - 2)
                };
                validate_triangle_capacity(
                    self.triangle_count + triangles.len() + count + self.instances.triangle_count(),
                )?;
                triangles
                    .try_reserve(count)
                    .map_err(|_| "dynamic frame allocation failed")?;
                if l.topology == 1 {
                    self.routing.billboards(
                        s.vertices,
                        d.texture_id,
                        d.flags,
                        triangles,
                        &mut bounds,
                    )?;
                } else {
                    decode_vertices(
                        s.vertices,
                        &l,
                        d.texture_id,
                        d.flags,
                        triangles,
                        &mut bounds,
                    )?;
                }
            }
            Ok::<_, String>(bounds)
        })();
        let bounds = match decoded {
            Ok(b) => b,
            Err(e) => {
                self.dynamic_spares.push(storage);
                return Err(e);
            }
        };
        if self
            .dynamic
            .source
            .try_reserve(proof_len.saturating_sub(self.dynamic.source.len()))
            .is_err()
        {
            self.dynamic_spares.push(storage);
            return Err("dynamic source cache allocation failed".into());
        }
        for &id in &texture_ids {
            self.texture_lifetime.acquire(id);
        }
        for &id in &self.dynamic.texture_ids {
            self.texture_lifetime.release(id);
        }
        self.dynamic.texture_ids = texture_ids;
        self.dynamic_spares
            .push(std::mem::replace(&mut self.dynamic.triangles, storage));
        self.dynamic.revision = b.sequence;
        self.dynamic.sequence = b.sequence;
        self.dynamic.source.clear();
        for s in view.spans() {
            for word in s.words() {
                self.dynamic.source.extend_from_slice(&word.to_le_bytes());
            }
            self.dynamic.source.extend_from_slice(s.vertices);
        }
        self.dynamic.origin = b.origin;
        self.dynamic.bounds = bounds;
        Ok(())
    }
}

impl InstanceBatch {
    pub(crate) fn decode_typed(&mut self, view: InstancesView<'_>) -> Result<(), String> {
        self.clear();
        let b = view.batch();
        if b.prototype_count
            + b.prototype_removal_count
            + b.instance_count
            + b.instance_removal_count
            == 0
        {
            return Err("empty instance batches must not be submitted".into());
        }
        self.epoch = b.epoch;
        self.sequence = b.sequence;
        self.prototypes
            .try_reserve(view.prototypes().len())
            .map_err(|_| "prototype record allocation failed")?;
        self.prototype_removals
            .try_reserve(view.prototype_removals().len())
            .map_err(|_| "prototype removal allocation failed")?;
        self.instances
            .try_reserve(view.instances().len())
            .map_err(|_| "instance record allocation failed")?;
        self.instance_removals
            .try_reserve(view.instance_removals().len())
            .map_err(|_| "instance removal allocation failed")?;
        let mut total = 0;
        for p in view.prototypes() {
            let d = p.descriptor();
            validate_record(d.id, d.revision, b.sequence)?;
            let mut triangles = Vec::new();
            let mut bounds = [[f32::INFINITY; 3], [f32::NEG_INFINITY; 3]];
            for s in p.spans() {
                let l = layout(s, false)?;
                let count = l.count / l.topology * (l.topology - 2);
                total += count;
                validate_triangle_capacity(total)?;
                triangles
                    .try_reserve(count)
                    .map_err(|_| "prototype allocation failed")?;
                decode_vertices(
                    s.vertices,
                    &l,
                    s.descriptor.texture_id,
                    s.descriptor.flags,
                    &mut triangles,
                    &mut bounds,
                )?;
            }
            if triangles.is_empty() {
                return Err("prototype must contain at least one triangle".into());
            }
            self.prototypes.push((
                d.id,
                Prototype {
                    revision: d.revision,
                    triangles: triangles.into(),
                    bounds,
                },
            ));
        }
        for r in view.prototype_removals() {
            validate_record(r.id, r.revision, b.sequence)?;
            self.prototype_removals.push((r.id, r.revision));
        }
        for s in view.instances() {
            validate_record(s.id, s.revision, b.sequence)?;
            origin(s.origin)?;
            if s.reserved != 0
                || s.transform
                    .iter()
                    .chain(s.uv_transform.iter())
                    .any(|v| !v.is_finite())
            {
                return Err("invalid instance scalar fields".into());
            }
            validate_affine(s.transform)?;
            if s.flags != u32::MAX {
                validate_flags(s.flags)?;
            }
            self.instances.push((
                s.id,
                Instance {
                    revision: s.revision,
                    prototype_id: s.prototype_id,
                    origin: s.origin,
                    transform: s.transform,
                    texture_id: s.texture_id,
                    flags: s.flags,
                    tint: s.rgba.to_le_bytes(),
                    uv_transform: s.uv_transform,
                },
            ));
        }
        for r in view.instance_removals() {
            validate_record(r.id, r.revision, b.sequence)?;
            self.instance_removals.push((r.id, r.revision));
        }
        validate_unique(&mut self.prototypes, &mut self.prototype_removals)?;
        validate_unique(&mut self.instances, &mut self.instance_removals)
    }
}

impl Frame {
    pub fn from_abi(f: &prime_abi::PrimeFrame) -> Result<Self, String> {
        origin(f.position)?;
        for v in [f.forward, f.right, f.up] {
            let squared: f32 = v.into_iter().map(|a| a * a).sum();
            if !squared.is_finite() || (squared - 1.0).abs() > 0.01 {
                return Err("camera basis must be finite and normalized".into());
            }
        }
        for (a, b) in [(f.forward, f.right), (f.forward, f.up), (f.right, f.up)] {
            if (0..3).map(|i| a[i] * b[i]).sum::<f32>().abs() > 0.01 {
                return Err("camera basis must be orthogonal".into());
            }
        }
        if !(0.01..3.0).contains(&f.fov_y) || !f.solar_hour_angle.is_finite() {
            return Err("invalid field of view or solar angle".into());
        }
        crate::extent::RenderExtent::new(f.width, f.height)?;
        Ok(Self {
            epoch: f.epoch,
            world_position: f.position,
            camera: Camera {
                position: [0.0; 3],
                forward: f.forward,
                right: f.right,
                up: f.up,
                vertical_fov_radians: f.fov_y,
            },
            width: f.width,
            height: f.height,
            sample_index: f.sample_index,
            solar_hour_angle: f.solar_hour_angle,
        })
    }
}
