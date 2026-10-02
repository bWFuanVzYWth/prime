//! Checked synchronous views. Raw pointers are validated once at the native boundary;
//! consumers receive named fields and borrowed payload slices, never wire packets.
use crate::*;

#[derive(Clone, Copy)]
pub struct MeshView<'a> {
    pub descriptor: &'a PrimeMeshSpan,
    pub vertices: &'a [u8],
}
impl MeshView<'_> {
    pub fn words(self) -> [u32; 8] {
        let s = self.descriptor;
        [
            s.texture_id,
            s.flags,
            s.topology,
            s.vertex_count,
            s.stride,
            s.position_offset,
            s.color_offset,
            s.uv_offset,
        ]
    }
}

unsafe fn mesh_spans<'a>(
    data: *const PrimeMeshSpan,
    count: u64,
    budget: &mut Budget,
) -> Result<&'a [PrimeMeshSpan], String> {
    budget.array::<PrimeMeshSpan>(count)?;
    let spans = unsafe { slice(data, count)? };
    for span in spans {
        budget.add(span.vertices.count)?;
        unsafe {
            slice(span.vertices.data, span.vertices.count)?;
        }
    }
    Ok(spans)
}
fn mesh_view(span: &PrimeMeshSpan) -> MeshView<'_> {
    // Constructed only from a validated batch; its caller keeps every input immutable.
    let vertices = if span.vertices.count == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(span.vertices.data, span.vertices.count as usize) }
    };
    MeshView {
        descriptor: span,
        vertices,
    }
}

#[derive(Clone, Copy)]
pub struct DynamicView<'a> {
    batch: &'a PrimeDynamicBatch,
    spans: &'a [PrimeMeshSpan],
}
impl<'a> DynamicView<'a> {
    /// # Safety
    /// All described input allocations are readable and immutable for `'a`, including
    /// every copy of this view and every slice or child view derived from it.
    pub unsafe fn read(pointer: *const PrimeDynamicBatch) -> Result<Self, String> {
        let batch = unsafe { input(pointer)? };
        let mut budget = Budget::default();
        budget.array::<PrimeDynamicBatch>(1)?;
        let spans = unsafe { mesh_spans(batch.spans, batch.count, &mut budget)? };
        Ok(Self { batch, spans })
    }
    pub fn batch(self) -> &'a PrimeDynamicBatch {
        self.batch
    }
    pub fn spans(self) -> impl ExactSizeIterator<Item = MeshView<'a>> {
        self.spans.iter().map(mesh_view)
    }
}

#[derive(Clone, Copy)]
pub struct TextureView<'a> {
    pub descriptor: &'a PrimeTextureSource,
    pub pixels: &'a [u8],
}
#[derive(Clone, Copy)]
pub struct TexturesView<'a> {
    epoch: u64,
    textures: &'a [PrimeTextureSource],
}
impl<'a> TexturesView<'a> {
    /// # Safety
    /// All described input allocations are readable and immutable for `'a`, including
    /// every copy of this view and every slice or child view derived from it.
    pub unsafe fn read(pointer: *const PrimeTextureBatch) -> Result<Self, String> {
        let batch = unsafe { input(pointer)? };
        let mut budget = Budget::default();
        budget.array::<PrimeTextureBatch>(1)?;
        budget.array::<PrimeTextureSource>(batch.count)?;
        let textures = unsafe { slice(batch.textures, batch.count)? };
        for texture in textures {
            budget.add(texture.rgba.count)?;
            unsafe {
                slice(texture.rgba.data, texture.rgba.count)?;
            }
        }
        Ok(Self {
            epoch: batch.epoch,
            textures,
        })
    }
    pub fn epoch(self) -> u64 {
        self.epoch
    }
    pub fn textures(self) -> impl ExactSizeIterator<Item = TextureView<'a>> {
        self.textures.iter().map(|descriptor| TextureView {
            descriptor,
            pixels: if descriptor.rgba.count == 0 {
                &[]
            } else {
                unsafe {
                    std::slice::from_raw_parts(descriptor.rgba.data, descriptor.rgba.count as usize)
                }
            },
        })
    }
}

#[derive(Clone, Copy)]
pub struct PrototypeView<'a> {
    descriptor: &'a PrimePrototypeSource,
}
impl<'a> PrototypeView<'a> {
    pub fn descriptor(self) -> &'a PrimePrototypeSource {
        self.descriptor
    }
    pub fn spans(self) -> impl ExactSizeIterator<Item = MeshView<'a>> {
        let p = self.descriptor;
        let spans = if p.count == 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(p.spans, p.count as usize) }
        };
        spans.iter().map(mesh_view)
    }
}
#[derive(Clone, Copy)]
pub struct InstancesView<'a> {
    batch: &'a PrimeInstanceBatch,
    prototypes: &'a [PrimePrototypeSource],
    prototype_removals: &'a [PrimeRemoval],
    instances: &'a [PrimeInstanceSource],
    instance_removals: &'a [PrimeRemoval],
}
impl<'a> InstancesView<'a> {
    /// # Safety
    /// All described input allocations are readable and immutable for `'a`, including
    /// every copy of this view and every slice or child view derived from it.
    pub unsafe fn read(pointer: *const PrimeInstanceBatch) -> Result<Self, String> {
        let batch = unsafe { input(pointer)? };
        let mut budget = Budget::default();
        budget.array::<PrimeInstanceBatch>(1)?;
        budget.array::<PrimePrototypeSource>(batch.prototype_count)?;
        budget.array::<PrimeRemoval>(batch.prototype_removal_count)?;
        budget.array::<PrimeInstanceSource>(batch.instance_count)?;
        budget.array::<PrimeRemoval>(batch.instance_removal_count)?;
        let prototypes = unsafe { slice(batch.prototypes, batch.prototype_count)? };
        let prototype_removals =
            unsafe { slice(batch.prototype_removals, batch.prototype_removal_count)? };
        let instances = unsafe { slice(batch.instances, batch.instance_count)? };
        let instance_removals =
            unsafe { slice(batch.instance_removals, batch.instance_removal_count)? };
        for prototype in prototypes {
            unsafe {
                mesh_spans(prototype.spans, prototype.count, &mut budget)?;
            }
        }
        Ok(Self {
            batch,
            prototypes,
            prototype_removals,
            instances,
            instance_removals,
        })
    }
    pub fn batch(self) -> &'a PrimeInstanceBatch {
        self.batch
    }
    pub fn prototype_removals(self) -> &'a [PrimeRemoval] {
        self.prototype_removals
    }
    pub fn instances(self) -> &'a [PrimeInstanceSource] {
        self.instances
    }
    pub fn instance_removals(self) -> &'a [PrimeRemoval] {
        self.instance_removals
    }
    pub fn prototypes(self) -> impl ExactSizeIterator<Item = PrototypeView<'a>> {
        self.prototypes
            .iter()
            .map(|descriptor| PrototypeView { descriptor })
    }
}
