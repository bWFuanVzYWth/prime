//! The caller owns argument pointers; all retained data belongs to the engine.
use crate::engine::Engine;
#[cfg(test)]
use crate::engine::poison_on_failure;
use prime_abi::*;
use prime_scene::protocol::Frame;
const ABI_VERSION: u32 = PRIME_ABI_VERSION;
use std::{
    cell::RefCell,
    collections::BTreeMap,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::atomic::{AtomicU64, Ordering},
};
// Each handle and its Vulkan queue live exclusively on the creating OS thread. Only
// the monotonic identity counter is shared; no scene or GPU state crosses threads.
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
fn allocate_handle() -> Result<u64, String> {
    #[allow(deprecated)]
    NEXT_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| {
            value.checked_add(1)
        })
        .map_err(|_| "handle identity exhausted".into())
}

/// Routes one host Vulkan present; returns SDK status merged with reported API errors.
/// Loaded frame generation can report an asynchronous error on a later call.
/// # Safety
/// `queue` is a live VkQueue and `present_info` points to a valid VkPresentInfoKHR
/// with all referenced arrays/handles alive until this synchronous call returns.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_streamline_present(queue: u64, present_info: u64) -> i32 {
    #[cfg(feature = "vulkan")]
    {
        unsafe { prime_vulkan::streamline_present(queue, present_info) }
    }
    #[cfg(not(feature = "vulkan"))]
    {
        let _ = (queue, present_info);
        -7
    }
}
thread_local! {
    static SESSIONS: RefCell<BTreeMap<u64, Engine>> = const { RefCell::new(BTreeMap::new()) };
    #[cfg(feature = "vulkan")]
    static HDR_SURFACES: RefCell<BTreeMap<u64, prime_vulkan::HdrSurface>> = const { RefCell::new(BTreeMap::new()) };
    static ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

#[unsafe(no_mangle)]
pub extern "C" fn prime_streamline_bootstrap() -> i32 {
    boundary(-1, || {
        #[cfg(feature = "vulkan")]
        {
            prime_vulkan::streamline_bootstrap()?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            Err("Native library built without Vulkan".into())
        }
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn prime_streamline_frame(action: u32, enabled: u32) -> i32 {
    boundary(-1, || {
        if action > PRIME_STREAMLINE_HOST_SHUTDOWN || enabled > 1 {
            return Err("Invalid Streamline logical frame controls".into());
        }
        #[cfg(feature = "vulkan")]
        {
            prime_vulkan::streamline_frame(action, enabled != 0)?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// The host handles/features/queue/timeline remain live through explicit surface shutdown.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_hdr_surface_create(host: *const PrimeVulkanHost) -> u64 {
    boundary(0, || {
        let host = unsafe { input(host)? };
        validate_vulkan_host(host)?;
        #[cfg(feature = "vulkan")]
        {
            let owner = unsafe {
                prime_vulkan::HdrSurface::new(
                    host.instance,
                    host.physical_device,
                    host.device,
                    host.queue,
                    host.queue_family,
                    host.timeline,
                )?
            };
            let id = allocate_handle()?;
            HDR_SURFACES.with(|owners| {
                owners
                    .try_borrow_mut()
                    .map_err(|_| "reentrant HDR surface creation")?
                    .insert(id, owner)
                    .map_or(Ok(()), |_| Err("Duplicate HDR surface identity"))
            })?;
            Ok(id)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// Target and display metadata satisfy the same borrowed image/calibration contracts as
/// prime_present_hdr/prime_display_output. Record once in each host submission.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_hdr_surface_record(
    handle: u64,
    target: *const PrimeHdrTarget,
    display: *const PrimeDisplayOutput,
) -> i32 {
    boundary(-1, || {
        let target = unsafe { input(target)? };
        let display = unsafe { input(display)? };
        if display.active != 1
            || display.reserved != 0
            || target.ui_image == 0
            || target.output_image == 0
        {
            return Err("Invalid HDR surface metadata".into());
        }
        #[cfg(feature = "vulkan")]
        {
            HDR_SURFACES.with(|owners| unsafe {
                owners
                    .try_borrow_mut()
                    .map_err(|_| "reentrant HDR surface call")?
                    .get_mut(&handle)
                    .ok_or("Invalid HDR surface handle or thread")?
                    .record(
                        target.command,
                        target.ui_view,
                        target.output_view,
                        target.serial,
                        [target.width, target.height],
                        display.peak_nits,
                        display.system_white_nits,
                    )
            })?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}

/// Host submits pending work before calling; destruction requires real completion.
#[unsafe(no_mangle)]
pub extern "C" fn prime_hdr_surface_destroy(handle: u64) -> i32 {
    boundary(-1, || {
        #[cfg(feature = "vulkan")]
        {
            HDR_SURFACES.with(|owners| {
                let mut owners = owners
                    .try_borrow_mut()
                    .map_err(|_| "reentrant HDR surface destruction")?;
                owners
                    .get_mut(&handle)
                    .ok_or("Invalid HDR surface handle or thread")?
                    .shutdown()?;
                owners.remove(&handle);
                Ok(0)
            })
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}

pub(crate) fn boundary<T>(fallback: T, work: impl FnOnce() -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(value)) => {
            ERROR.with(|e| e.borrow_mut().clear());
            value
        }
        Ok(Err(message)) => {
            ERROR.with(|e| *e.borrow_mut() = message);
            fallback
        }
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .copied()
                .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
                .unwrap_or("unknown panic");
            ERROR.with(|e| {
                *e.borrow_mut() = format!("native panic contained at FFM boundary: {detail}")
            });
            fallback
        }
    }
}

pub(crate) fn session<T>(
    handle: u64,
    work: impl FnOnce(&mut Engine) -> Result<T, String>,
) -> Result<T, String> {
    SESSIONS.with(|sessions| {
        let mut sessions = sessions
            .try_borrow_mut()
            .map_err(|_| "reentrant native call")?;
        work(
            sessions
                .get_mut(&handle)
                .ok_or("invalid handle or call from a different OS thread")?,
        )
    })
}

/// Returns the supported public C ABI version, without creating GPU resources.
#[unsafe(no_mangle)]
pub extern "C" fn prime_abi_version() -> u32 {
    ABI_VERSION
}

/// Creates a thread-confined session; zero means failure (see prime_last_error).
#[unsafe(no_mangle)]
pub extern "C" fn prime_create(version: u32) -> u64 {
    boundary(0, || {
        if version != ABI_VERSION {
            return Err(format!(
                "ABI mismatch: expected {ABI_VERSION}, received {version}"
            ));
        }
        let handle = allocate_handle()?;
        SESSIONS.with(|sessions| {
            let mut sessions = sessions
                .try_borrow_mut()
                .map_err(|_| "reentrant native call")?;
            if sessions.len() >= 8 {
                return Err("at most eight sessions per thread are supported".into());
            }
            sessions.insert(handle, Engine::new()?);
            Ok(handle)
        })
    })
}

/// Reset world-owned state while preserving registered resource-generation textures.
/// # Safety
/// The named input and its header remain readable until synchronous return. A borrowed
/// host must submit and complete recorded work before reset; offline state must be thawed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_reset(handle: u64, reset: *const PrimeReset) -> i32 {
    boundary(-1, || {
        let r = unsafe { input(reset)? };
        session(handle, |s| s.reset_world(r.epoch))?;
        Ok(0)
    })
}
/// # Safety
/// All descriptors and pixel spans remain readable and immutable until return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_textures(handle: u64, batch: *const PrimeTextureBatch) -> i32 {
    boundary(-1, || {
        let view = unsafe { scene::TexturesView::read(batch)? };
        session(handle, |s| {
            s.update_source(|src| src.submit_textures_typed(view))
        })?;
        Ok(0)
    })
}
/// # Safety
/// Input and its identity span remain readable until return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_retire_textures(
    handle: u64,
    batch: *const PrimeTextureRetire,
) -> i32 {
    boundary(-1, || {
        let b = unsafe { input(batch)? };
        let mut budget = Budget::default();
        budget.array::<PrimeTextureRetire>(1)?;
        budget.array::<u32>(b.ids.count)?;
        let ids = unsafe { slice(b.ids.data, b.ids.count)? };
        session(handle, |s| {
            s.update_source(|src| src.retire_textures_typed(b.epoch, ids))
        })?;
        Ok(0)
    })
}
/// # Safety
/// The batch, descriptors and source vertex spans are immutable through return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_dynamic(handle: u64, batch: *const PrimeDynamicBatch) -> i32 {
    boundary(-1, || {
        let view = unsafe { scene::DynamicView::read(batch)? };
        session(handle, |s| {
            s.update_source(|src| src.submit_dynamic_typed(view))
        })?;
        Ok(0)
    })
}
/// # Safety
/// Every typed array and raw source payload remains immutable through return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_instances(handle: u64, batch: *const PrimeInstanceBatch) -> i32 {
    boundary(-1, || {
        let view = unsafe { scene::InstancesView::read(batch)? };
        session(handle, |s| {
            s.update_source(|src| src.submit_instances_typed(view))
        })?;
        Ok(0)
    })
}
/// Renders top-left-origin RGBA8 synchronously; trailing output bytes are untouched.
/// # Safety
/// Frame is readable; output addresses capacity writable bytes through return.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_render(
    handle: u64,
    data: *const PrimeFrame,
    output: *mut u8,
    capacity: u64,
) -> i32 {
    boundary(-1, || {
        let frame = Frame::from_abi(unsafe { input(data)? })?;
        if output.is_null() || capacity < frame.output_len() as u64 {
            return Err("output buffer is null or too small".into());
        }
        let rgba = session(handle, |s| s.render(&frame))?;
        if rgba.len() != frame.output_len() {
            return Err("renderer returned an invalid output extent".into());
        }
        unsafe {
            std::ptr::copy_nonoverlapping(rgba.as_ptr(), output, rgba.len());
        }
        Ok(0)
    })
}
fn validate_vulkan_host(host: &PrimeVulkanHost) -> Result<(), String> {
    if [
        host.instance,
        host.physical_device,
        host.device,
        host.queue,
        host.timeline,
    ]
    .contains(&0)
        || host.capabilities & !(PRIME_HOST_OMM | PRIME_HOST_STREAMLINE) != 0
    {
        return Err("Invalid Vulkan host handles or unknown capability flags".into());
    }
    Ok(())
}
/// Borrows host Vulkan objects; capability bits certify enabled logical-device features.
/// # Safety
/// Named descriptor and Vulkan handles meet borrowed_with_settings_and_workers's
/// contract; submit the active encoder before reconfiguration or destruction.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_attach_vulkan(handle: u64, data: *const PrimeVulkanHost) -> i32 {
    boundary(-1, || {
        let h = unsafe { input(data)? };
        validate_vulkan_host(h)?;
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| {
                if s.failed || s.renderer.is_some() {
                    return Err("Engine already attached or failed".into());
                }
                s.renderer = Some(unsafe {
                    prime_vulkan::Renderer::borrowed_with_settings_and_workers(
                        h.instance,
                        h.physical_device,
                        h.device,
                        h.queue,
                        h.queue_family,
                        h.timeline,
                        h.capabilities,
                        s.settings,
                        s.workers.clone(),
                    )?
                });
                Ok(0)
            })
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}
/// # Safety
/// Settings remain readable. Mode changes require an already submitted host encoder.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_configure(handle: u64, data: *const PrimeSettings) -> i32 {
    boundary(-1, || {
        let settings = prime_scene::settings::RenderSettings::from_abi(unsafe { input(data)? })?;
        session(handle, |s| s.configure(settings))?;
        Ok(0)
    })
}
/// # Safety
/// The descriptor is readable and references an active host command buffer and its
/// real future timeline serial. The caller submits it on the attached queue.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_prepare_resources(
    handle: u64,
    data: *const PrimePrepareResources,
) -> i32 {
    boundary(-1, || {
        let p = unsafe { input(data)? };
        if p.command == 0 || p.serial == 0 {
            return Err("Invalid resource preparation handles".into());
        }
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| unsafe {
                s.prepare_resources(p.command, p.serial)
            })?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}
/// # Safety
/// Typed inputs remain readable. Vulkan arguments satisfy record_host; submit in
/// queue order and signal the descriptor's actual completion serial.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_record(
    handle: u64,
    data: *const PrimeFrame,
    target: *const PrimeRecordTarget,
) -> i32 {
    boundary(-1, || {
        let frame = Frame::from_abi(unsafe { input(data)? })?;
        let t = unsafe { input(target)? };
        if [t.command, t.image, t.view, t.serial].contains(&0) {
            return Err("Invalid Vulkan recording handles".into());
        }
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| unsafe {
                s.record(&frame, t.command, t.image, t.view, t.serial)
            })?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = (handle, frame);
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// The attached host queue has accepted the matching recorded command buffer and serial.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_submission_accepted(handle: u64, serial: u64) -> i32 {
    boundary(-1, || {
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| s.submission_accepted(serial))?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = (handle, serial);
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// Display metadata describes the actual selected surface at an accepted frame boundary.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_display_output(
    handle: u64,
    display: *const PrimeDisplayOutput,
) -> i32 {
    boundary(-1, || {
        let display = unsafe { input(display)? };
        if display.active > 1 || display.reserved != 0 {
            return Err("Invalid display output state".into());
        }
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| {
                s.renderer
                    .as_mut()
                    .ok_or("Attach a Vulkan host before configuring display")?
                    .display_output(
                        display.active != 0,
                        display.peak_nits,
                        display.system_white_nits,
                    )
            })?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// Borrowed images/command satisfy PrimeHdrTarget's layout, device and completion contract.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_present_hdr(handle: u64, target: *const PrimeHdrTarget) -> i32 {
    boundary(-1, || {
        let target = unsafe { input(target)? };
        if [
            target.command,
            target.ui_image,
            target.ui_view,
            target.output_image,
            target.output_view,
            target.serial,
        ]
        .contains(&0)
        {
            return Err("Invalid HDR presentation handles".into());
        }
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| unsafe {
                s.renderer
                    .as_mut()
                    .ok_or("Attach a Vulkan host before HDR presentation")?
                    .present_hdr(
                        target.command,
                        target.ui_view,
                        target.output_view,
                        target.serial,
                        [target.width, target.height],
                    )
            })?;
            Ok(0)
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = handle;
            Err("Native library built without Vulkan".into())
        }
    })
}

/// # Safety
/// The target has the same ownership/layout contract as prime_present_hdr and the
/// SDK's published input-consumer completion is also required before release.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_prepare_frame_generation(
    handle: u64,
    target: *const PrimeHdrTarget,
    back_buffer_count: u32,
    back_buffer_format: u32,
) -> i32 {
    boundary(-1, || {
        let target = unsafe { input(target)? };
        if [
            target.command,
            target.ui_image,
            target.ui_view,
            target.output_image,
            target.output_view,
            target.serial,
        ]
        .contains(&0)
        {
            return Err("Invalid frame generation presentation handles".into());
        }
        #[cfg(feature = "vulkan")]
        {
            let prepared = session(handle, |s| unsafe {
                s.renderer
                    .as_mut()
                    .ok_or("Attach a Vulkan host before frame generation")?
                    .prepare_frame_generation(
                        target.command,
                        target.ui_view,
                        target.serial,
                        [target.width, target.height],
                        back_buffer_count,
                        back_buffer_format,
                    )
            })?;
            Ok(i32::from(!prepared))
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = (handle, back_buffer_count, back_buffer_format);
            Err("Native library built without Vulkan".into())
        }
    })
}

/// Last completed host PT timestamp duration in ns; zero when profiling is unavailable.
#[unsafe(no_mangle)]
pub extern "C" fn prime_gpu_time(handle: u64) -> u64 {
    boundary(0, || {
        session(handle, |s| {
            #[cfg(feature = "vulkan")]
            {
                Ok(s.renderer
                    .as_ref()
                    .map_or(0, prime_vulkan::Renderer::last_gpu_time_ns))
            }
            #[cfg(not(feature = "vulkan"))]
            {
                let _ = s;
                Ok(0)
            }
        })
    })
}

/// Copies a diagnostic UTF-8 snapshot of the last CPU preparation/recording, NUL terminated.
/// Returns the full length excluding NUL, or u64::MAX on error. No GPU wait/readback.
///
/// # Safety
/// For nonzero capacity, output must point to that many writable bytes. A null
/// pointer is allowed only with zero capacity. Ownership/thread rules match prime_record.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_cpu_diagnostics(handle: u64, output: *mut u8, capacity: u64) -> u64 {
    boundary(u64::MAX, || {
        if capacity > isize::MAX as u64 || (capacity != 0 && output.is_null()) {
            return Err("Invalid CPU diagnostics buffer".into());
        }
        let report = session(handle, |s| Ok(s.cpu_diagnostics()))?;
        if capacity != 0 {
            let count = report.len().min(capacity as usize - 1);
            // SAFETY: Caller owns a validated writable span. The report is independently owned.
            unsafe {
                std::ptr::copy_nonoverlapping(report.as_ptr(), output, count);
                output.add(count).write(0);
            }
        }
        Ok(report.len() as u64)
    })
}

/// Waits for owned GPU work and destroys the session on its creating thread.
#[unsafe(no_mangle)]
pub extern "C" fn prime_destroy(handle: u64) -> i32 {
    boundary(-1, || {
        #[cfg(feature = "vulkan")]
        session(handle, |s| {
            if let Some(renderer) = &mut s.renderer {
                renderer.shutdown()?;
            }
            Ok(())
        })?;
        SESSIONS.with(|sessions| {
            let value = sessions
                .try_borrow_mut()
                .map_err(|_| "reentrant native call")?
                .remove(&handle)
                .ok_or("invalid handle or call from a different OS thread")?;
            drop(value);
            Ok(0)
        })
    })
}

/// Copies the thread's last error as UTF-8, NUL terminated when capacity > 0.
/// Returns the full UTF-8 byte length excluding NUL. Reading does not clear it.
///
/// # Safety
/// With nonzero capacity, output must be writable for capacity bytes. Null is
/// accepted only for querying length with capacity zero.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_last_error(output: *mut u8, capacity: u64) -> u64 {
    ERROR.with(|error| {
        let error = error.borrow();
        if !output.is_null() && capacity > 0 {
            let count = error
                .len()
                .min((capacity - 1).min(isize::MAX as u64) as usize);
            // SAFETY: Caller guarantees capacity; source is independent native storage.
            unsafe {
                std::ptr::copy_nonoverlapping(error.as_ptr(), output, count);
                output.add(count).write(0);
            }
        }
        error.len() as u64
    })
}

const _: PrimeAbiVersionFn = prime_abi_version;
const _: PrimeCreateFn = prime_create;
const _: PrimeResetFn = prime_reset;
const _: PrimeTexturesFn = prime_textures;
const _: PrimeRetireTexturesFn = prime_retire_textures;
const _: PrimeDynamicFn = prime_dynamic;
const _: PrimeInstancesFn = prime_instances;
const _: PrimeRenderFn = prime_render;
const _: PrimeAttachVulkanFn = prime_attach_vulkan;
const _: PrimeConfigureFn = prime_configure;
const _: PrimePrepareResourcesFn = prime_prepare_resources;
const _: PrimeRecordFn = prime_record;
const _: PrimeSubmissionAcceptedFn = prime_submission_accepted;
const _: PrimeDisplayOutputFn = prime_display_output;
const _: PrimePresentHdrFn = prime_present_hdr;
const _: PrimePrepareFrameGenerationFn = prime_prepare_frame_generation;
const _: PrimeHdrSurfaceCreateFn = prime_hdr_surface_create;
const _: PrimeHdrSurfaceRecordFn = prime_hdr_surface_record;
const _: PrimeHdrSurfaceDestroyFn = prime_hdr_surface_destroy;
const _: PrimeStreamlineBootstrapFn = prime_streamline_bootstrap;
const _: PrimeStreamlineFrameFn = prime_streamline_frame;
const _: PrimeGpuTimeFn = prime_gpu_time;
const _: PrimeCpuDiagnosticsFn = prime_cpu_diagnostics;
const _: PrimeDestroyFn = prime_destroy;
const _: PrimeLastErrorFn = prime_last_error;
const _: PrimeStreamlinePresentFn = prime_streamline_present;

#[cfg(test)]
mod abi_tests {
    use super::*;
    #[test]
    fn cpu_diagnostics_are_thread_confined_bounded_and_gpu_independent() {
        let handle = prime_create(ABI_VERSION);
        let length = unsafe { prime_cpu_diagnostics(handle, std::ptr::null_mut(), 0) };
        assert!(length > 0 && length < 8192);
        let mut buffer = vec![0xa5; length as usize + 2];
        assert_eq!(
            unsafe { prime_cpu_diagnostics(handle, buffer.as_mut_ptr(), length + 1) },
            length
        );
        assert_eq!(buffer[length as usize], 0);
        assert_eq!(buffer[length as usize + 1], 0xa5);
        let text = std::str::from_utf8(&buffer[..length as usize]).unwrap();
        assert!(text.contains("available=false"));
        let mut tiny = [0xa5; 2];
        assert_eq!(
            unsafe { prime_cpu_diagnostics(handle, tiny.as_mut_ptr(), 1) },
            length
        );
        assert_eq!(tiny, [0, 0xa5]);
        assert_eq!(
            unsafe { prime_cpu_diagnostics(handle, std::ptr::null_mut(), 1) },
            u64::MAX
        );
        assert_eq!(
            std::thread::spawn(move || unsafe {
                prime_cpu_diagnostics(handle, std::ptr::null_mut(), 0)
            })
            .join()
            .unwrap(),
            u64::MAX
        );
        assert_eq!(prime_destroy(handle), 0);
        assert_eq!(
            unsafe { prime_cpu_diagnostics(handle, std::ptr::null_mut(), 0) },
            u64::MAX
        );
    }

    #[test]
    fn handles_are_thread_confined_and_retired() {
        let handle = prime_create(ABI_VERSION);
        assert_ne!(handle, 0);
        assert_eq!(
            std::thread::spawn(move || prime_destroy(handle))
                .join()
                .unwrap(),
            -1
        );
        assert_eq!(prime_destroy(handle), 0);
        assert_eq!(prime_destroy(handle), -1);
        assert!(unsafe { prime_last_error(std::ptr::null_mut(), 0) } > 0);
    }
    #[test]
    fn errors_are_queryable_and_inputs_checked() {
        assert_eq!(prime_create(99), 0);
        let mut bytes = [0xcc; 8];
        assert!(unsafe { prime_last_error(bytes.as_mut_ptr(), bytes.len() as u64) } > 8);
        assert_eq!(bytes[7], 0);
        assert_eq!(unsafe { prime_dynamic(1, std::ptr::null()) }, -1);
    }
    #[test]
    fn texture_retirement_budget_includes_root_before_reading_ids() {
        let mut batch = PrimeTextureRetire {
            header: PrimeHeader {
                struct_size: std::mem::size_of::<PrimeTextureRetire>() as u32,
                abi_version: ABI_VERSION,
            },
            epoch: 1,
            ids: PrimeU32Span {
                data: std::ptr::null(),
                count: PRIME_MAX_BATCH_BYTES as u64 / 4,
            },
        };
        let error = |batch: &PrimeTextureRetire| {
            assert_eq!(unsafe { prime_retire_textures(0, batch) }, -1);
            let mut bytes = [0_u8; 128];
            let length = unsafe { prime_last_error(bytes.as_mut_ptr(), bytes.len() as u64) };
            std::str::from_utf8(&bytes[..length as usize])
                .unwrap()
                .to_owned()
        };
        assert!(error(&batch).contains("ABI batch exceeds 256 MiB"));
        batch.ids.count -= std::mem::size_of::<PrimeTextureRetire>() as u64 / 4;
        assert!(error(&batch).contains("Null or misaligned ABI span"));
        batch.ids.count += 1;
        assert!(error(&batch).contains("ABI batch exceeds 256 MiB"));
    }
    #[test]
    fn gpu_error_and_panic_poison_session_before_return() {
        let mut failed = false;
        assert_eq!(poison_on_failure(&mut failed, || Ok(7)), Ok(7));
        assert!(!failed);
        let panic = catch_unwind(AssertUnwindSafe(|| {
            let _: Result<(), String> =
                poison_on_failure(&mut failed, || panic!("simulated driver failure"));
        }));
        assert!(panic.is_err());
        assert!(failed);
        assert!(poison_on_failure(&mut failed, || Ok(())).is_err());
        failed = false;
        assert!(poison_on_failure::<()>(&mut failed, || Err("device lost".into())).is_err());
        assert!(failed);
    }
    #[test]
    fn configuration_validates_version_borrow_and_thread_before_mutation() {
        let handle = prime_create(ABI_VERSION);
        let valid = PrimeSettings {
            header: PrimeHeader {
                struct_size: std::mem::size_of::<PrimeSettings>() as u32,
                abi_version: ABI_VERSION,
            },
            mode: 0,
            bounces: 4,
            offline_samples: 1,
            exposure: 1.0,
            hue: 0.75,
            saturation: 0.08,
            view: 0,
            sun: 1.0,
            sky: 1.0,
            depth_range: 128.0,
            seed: 0x13572468,
            latitude_degrees: 30,
            solar_longitude_degrees: 0,
            opacity_micromap: 1,
            ray_reconstruction: 1,
            reconstruction_quality: 3,
            terrain_batches_per_frame: 8,
            stars: 1.0,
            auto_exposure_compensation: 0.6,
            hdr: 0,
            hdr_reference_white: 0,
            frame_generation: 0,
        };
        assert_eq!(unsafe { prime_configure(handle, &valid) }, 0);
        assert_eq!(unsafe { prime_configure(handle, std::ptr::null()) }, -1);
        let mut invalid = valid;
        invalid.header.struct_size -= 1;
        assert_eq!(unsafe { prime_configure(handle, &invalid) }, -1);
        invalid = valid;
        invalid.header.abi_version = 7;
        assert_eq!(unsafe { prime_configure(handle, &invalid) }, -1);
        assert_eq!(
            std::thread::spawn(move || unsafe { prime_configure(handle, &valid) })
                .join()
                .unwrap(),
            -1
        );
        invalid = valid;
        invalid.mode = 1;
        assert_eq!(
            unsafe { prime_configure(handle, &invalid) },
            -1,
            "No rendered frame can be frozen"
        );
        session(handle, |engine| {
            assert_eq!(
                engine.settings,
                prime_scene::settings::RenderSettings {
                    // The accepted packet explicitly chooses four, independently of defaults.
                    bounces: 4,
                    saturation: 0.08,
                    ..Default::default()
                }
            );
            assert!(!engine.failed);
            Ok(())
        })
        .unwrap();
        assert_eq!(unsafe { prime_configure(handle, &valid) }, 0);
        assert_eq!(prime_destroy(handle), 0);
    }
    #[test]
    fn host_descriptor_requires_enabled_capabilities_and_known_flag_bits() {
        let valid = PrimeVulkanHost {
            instance: 11,
            physical_device: 22,
            device: 33,
            queue: 44,
            timeline: 55,
            queue_family: 7,
            ..Default::default()
        };
        for capabilities in 0..4 {
            assert!(
                validate_vulkan_host(&PrimeVulkanHost {
                    capabilities,
                    ..valid
                })
                .is_ok()
            );
        }
        for capabilities in [4, 5, u32::MAX] {
            assert!(
                validate_vulkan_host(&PrimeVulkanHost {
                    capabilities,
                    ..valid
                })
                .is_err()
            );
        }
        for h in [
            PrimeVulkanHost {
                instance: 0,
                ..valid
            },
            PrimeVulkanHost {
                physical_device: 0,
                ..valid
            },
            PrimeVulkanHost { device: 0, ..valid },
            PrimeVulkanHost { queue: 0, ..valid },
            PrimeVulkanHost {
                timeline: 0,
                ..valid
            },
        ] {
            assert!(validate_vulkan_host(&h).is_err());
        }
    }
}

// Generated header signatures are also checked against actual exported Rust functions.
