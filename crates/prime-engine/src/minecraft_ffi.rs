//! MC production C descriptors. All pointer borrows end at the synchronous boundary.
use crate::ffi::{boundary, session_named};
use prime_abi::{minecraft, *};

unsafe fn output(pointer: *mut PrimeMcRequests) -> Result<(), String> {
    if pointer.is_null()
        || !(pointer as usize).is_multiple_of(std::mem::align_of::<PrimeMcRequests>())
    {
        return Err("null or misaligned MC result".into());
    }
    Ok(())
}

/// # Safety
/// The complete typed resource batch and payloads remain readable until return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_mc_resources(
    handle: u64,
    batch: *const PrimeMcResourceBatch,
) -> i32 {
    boundary(-1, || {
        let batch = unsafe { minecraft::Resources::read(batch)? };
        session_named(handle, "mc.resources", |engine| {
            engine.check_live_source()?;
            let result = engine
                .minecraft
                .prepare_resources(&batch, &mut engine.source);
            if result.is_err() {
                engine.failed = true;
            }
            result?;
            engine.source_changed();
            Ok(())
        })?;
        Ok(0)
    })
}

/// # Safety
/// Input descriptors/payloads remain readable; output is a writable distinct descriptor.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_mc_plan(
    handle: u64,
    batch: *const PrimeMcPlan,
    result: *mut PrimeMcRequests,
) -> i32 {
    boundary(-1, || {
        unsafe { output(result)? };
        let batch = unsafe { minecraft::Plan::read(batch)? };
        session_named(handle, "mc.plan", |engine| {
            engine.check_live_source()?;
            engine.minecraft.plan_typed(
                &batch,
                engine.source.epoch(),
                engine.settings.terrain_batches_per_frame,
            )?;
            unsafe { result.write(engine.minecraft.requests_typed()) };
            Ok(())
        })?;
        Ok(0)
    })
}

macro_rules! accept {
    ($name:ident,$raw:ident,$view:ident,$method:ident) => {
        /// # Safety
        /// Typed input allocations remain immutable until return; output is writable and distinct.
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn $name(
            handle: u64,
            batch: *const $raw,
            result: *mut PrimeMcRequests,
        ) -> i32 {
            boundary(-1, || {
                unsafe { output(result)? };
                let batch = unsafe { minecraft::$view::read(batch)? };
                session_named(handle, stringify!($name), |engine| {
                    engine.check_live_source()?;
                    let old = engine.source.revision();
                    let accepted = engine.minecraft.$method(&batch, &mut engine.source);
                    if accepted.is_err() {
                        engine.failed = true;
                    }
                    accepted?;
                    if engine.source.revision() != old {
                        engine.source_changed();
                    }
                    unsafe { result.write(engine.minecraft.requests_typed()) };
                    Ok(())
                })?;
                Ok(0)
            })
        }
    };
}
accept!(
    prime_mc_sections,
    PrimeMcSectionBatch,
    Sections,
    sections_typed
);
accept!(prime_mc_colors, PrimeMcColorBatch, Colors, colors_typed);
accept!(prime_mc_biomes, PrimeMcBiomeBatch, Biomes, biomes_typed);

const _: PrimeMcPlanFn = prime_mc_plan;
const _: PrimeMcSectionsFn = prime_mc_sections;
const _: PrimeMcResourcesFn = prime_mc_resources;
const _: PrimeMcColorsFn = prime_mc_colors;
const _: PrimeMcBiomesFn = prime_mc_biomes;
