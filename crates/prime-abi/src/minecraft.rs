//! Validated, call-scoped MC descriptor borrows. No pointer enters the Minecraft owner.
use crate::*;

/// # Safety
/// Every advertised, aligned allocation remains alive and immutable for `'a`, including
/// every slice derived from the view.
unsafe fn source_slice<'a, T>(data: *const T, count: u64) -> Result<&'a [T], String> {
    let bytes = count
        .checked_mul(std::mem::size_of::<T>() as u64)
        .ok_or("MC span overflow")?;
    if bytes > isize::MAX as u64 {
        return Err("MC span exceeds address space".into());
    }
    if count == 0 {
        return Ok(&[]);
    }
    if data.is_null() || !(data as usize).is_multiple_of(std::mem::align_of::<T>()) {
        return Err("Null or misaligned MC span".into());
    }
    Ok(unsafe { std::slice::from_raw_parts(data, count as usize) })
}

macro_rules! view {
    ($name:ident, $raw:ident, { $($field:ident: $ty:ty => $count:ident),* $(,)? }) => {
        pub struct $name<'a> {
            raw: &'a $raw,
            $($field: &'a [$ty],)*
        }
        impl<'a> $name<'a> {
            /// # Safety
            /// All descriptor/payload allocations remain alive, readable and immutable for `'a`,
            /// including the lifetime of every slice derived from the returned view.
            pub unsafe fn read(pointer: *const $raw) -> Result<Self, String> {
                let raw = unsafe { crate::input(pointer)? };
                Self::from_parts(raw, $(unsafe { source_slice(raw.$field, raw.$count)? },)*)
            }
            /// Safe construction from Rust-owned allocations. Pointer/count metadata must name
            /// these exact borrowed slices; this function never dereferences raw pointers.
            #[allow(clippy::too_many_arguments)]
            pub fn from_parts(raw: &'a $raw, $($field: &'a [$ty],)*) -> Result<Self, String> {
                if raw.identity.struct_size as usize != std::mem::size_of::<$raw>()
                    || raw.identity.abi_version != PRIME_ABI_VERSION {
                    return Err("MC root layout or ABI version mismatch".into());
                }
                if raw.identity.source_version != PRIME_MC_SOURCE_VERSION
                    || !matches!(raw.identity.game_version, 262 | 263)
                    || raw.identity.resource_generation == 0 {
                    return Err("MC source version or resource identity mismatch".into());
                }
                $(if raw.$count != $field.len() as u64
                    || !$field.is_empty() && raw.$field != $field.as_ptr() {
                    return Err(concat!("MC span metadata mismatch: ", stringify!($field)).into());
                })*
                Ok(Self { raw, $($field,)* })
            }
            pub fn raw(&self) -> &'a $raw { self.raw }
            $(pub fn $field(&self) -> &'a [$ty] { self.$field })*
        }
    }
}

view!(Plan, PrimeMcPlan, { events: PrimeMcEvent => event_count });
view!(Resources, PrimeMcResourceBatch, {
    states: PrimeMcState => state_count, models: PrimeMcModel => model_count,
    quads: PrimeMcQuad => quad_count, children: PrimeMcModelChild => child_count,
    faces: PrimeMcFace => face_count, fluids: PrimeMcFluid => fluid_count,
    sprites: PrimeMcSprite => sprite_count, images: PrimeMcImage => image_count,
    frames: PrimeMcAnimationFrame => frame_count, coordinates: f64 => coordinate_count,
    words: u64 => word_count, bytes: u8 => byte_count
});
view!(Sections, PrimeMcSectionBatch, {
    sections: PrimeMcSection => section_count, palette: u32 => palette_count,
    words: u64 => word_count
});
view!(Colors, PrimeMcColorBatch, {
    recipes: PrimeMcColorRecipe => recipe_count,
    definitions: PrimeMcBiomeDefinitions => definition_count, colormaps: u32 => colormap_count
});
view!(Biomes, PrimeMcBiomeBatch, {
    biomes: PrimeMcBiome => biome_count, indices: u32 => index_count
});

pub fn range<T>(values: &[T], range: PrimeMcRange) -> Result<&[T], String> {
    let end = range
        .offset
        .checked_add(range.count)
        .ok_or("MC range overflow")?;
    if end > values.len() as u64 {
        return Err("MC range outside typed payload".into());
    }
    Ok(&values[range.offset as usize..end as usize])
}

pub fn string(bytes: &[u8], span: PrimeMcRange) -> Result<String, String> {
    String::from_utf8(range(bytes, span)?.to_vec()).map_err(|_| "Invalid MC UTF-8".into())
}

pub fn finite(values: &[f32]) -> Result<(), String> {
    if values.iter().any(|v| !v.is_finite()) {
        return Err("Nonfinite MC source float".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_mc_views_validate_identity_and_exact_borrowed_span_metadata() {
        let events = [PrimeMcEvent {
            kind: 1,
            ..Default::default()
        }];
        let mut raw = PrimeMcPlan {
            identity: PrimeMcIdentity {
                struct_size: std::mem::size_of::<PrimeMcPlan>() as u32,
                abi_version: PRIME_ABI_VERSION,
                source_version: PRIME_MC_SOURCE_VERSION,
                game_version: 262,
                resource_generation: 1,
                ..Default::default()
            },
            events: events.as_ptr(),
            event_count: 1,
            ..Default::default()
        };
        assert_eq!(Plan::from_parts(&raw, &events).unwrap().events().len(), 1);
        raw.identity.source_version = 0;
        assert!(Plan::from_parts(&raw, &events).is_err());
        raw.identity.source_version = PRIME_MC_SOURCE_VERSION;
        raw.event_count = 2;
        assert!(Plan::from_parts(&raw, &events).is_err());
        raw.event_count = 1;
        raw.events = std::ptr::null();
        assert!(Plan::from_parts(&raw, &events).is_err());
        raw.events = events.as_ptr();
        assert!(Plan::from_parts(&raw, &[]).is_err());
        raw.identity.struct_size -= 1;
        assert!(Plan::from_parts(&raw, &events).is_err());
    }
}
