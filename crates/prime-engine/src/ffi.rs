//! The caller owns argument pointers; all retained data belongs to the engine.
use crate::engine::Engine;
#[cfg(test)]
use crate::engine::poison_on_failure;
use prime_scene::protocol::{ABI_VERSION, Frame, MAX_PACKET_BYTES};
use std::{
    cell::RefCell,
    collections::BTreeMap,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::atomic::{AtomicU64, Ordering},
};
// Each handle and its Vulkan queue live exclusively on the creating OS thread. Only
// the monotonic identity counter is shared; no scene or GPU state crosses threads.
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

/// Routes a real Vulkan present; its VkResult is returned unchanged.
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
    static ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn boundary<T>(fallback: T, work: impl FnOnce() -> Result<T, String>) -> T {
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

fn session<T>(
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

/// Returns the supported wire protocol version, without creating GPU resources.
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
        // fetch_update is supported by our stable MSRV; newer nightlies rename it.
        #[allow(deprecated)]
        let handle = NEXT_HANDLE
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| v.checked_add(1))
            .map_err(|_| "handle identity exhausted")?;
        SESSIONS.with(|sessions| {
            let mut sessions = sessions
                .try_borrow_mut()
                .map_err(|_| "reentrant native call")?;
            if sessions.len() >= 8 {
                return Err("at most eight sessions per thread are supported".into());
            }
            sessions.insert(handle, Engine::default());
            Ok(handle)
        })
    })
}

/// Copies and validates one scene command. Returns zero on success, -1 on error.
///
/// # Safety
/// `data` must reference `length` readable bytes for this synchronous call. Arbitrary
/// invalid native addresses cannot be validated by the ABI. No pointer is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_submit(handle: u64, data: *const u8, length: u64) -> i32 {
    boundary(-1, || {
        if data.is_null() || length > MAX_PACKET_BYTES as u64 {
            return Err("null or oversized input packet".into());
        }
        // SAFETY: Caller guarantees a live readable region; length is bounded above.
        let bytes = unsafe { std::slice::from_raw_parts(data, length as usize) };
        session(handle, |s| s.submit(bytes))?;
        Ok(0)
    })
}

#[repr(C)]
pub struct SourcePage {
    pub data: *const u8,
    pub length: u64,
}

/// # Safety
/// The descriptor table and each page remain readable until return. No input pointer is retained.
unsafe fn source_pages<'a>(pages: *const SourcePage, count: u64) -> Result<Vec<&'a [u8]>, String> {
    if pages.is_null()
        || count == 0
        || count > (isize::MAX as usize / std::mem::size_of::<SourcePage>()) as u64
    {
        return Err("invalid source page table".into());
    }
    let table = unsafe { std::slice::from_raw_parts(pages, count as usize) };
    table
        .iter()
        .map(|p| {
            if p.data.is_null() || p.length > MAX_PACKET_BYTES as u64 {
                return Err("invalid source page".into());
            }
            Ok(unsafe { std::slice::from_raw_parts(p.data, p.length as usize) })
        })
        .collect()
}

/// One synchronous demand batch per host frame, including empty batches.
/// # Safety
/// Input pages are borrowed until return. `output` points to a writable SourcePage.
/// The returned read-only request view belongs to this session; it expires at the next
/// plan/accept/destroy call. Java must finish reading it before returning its response.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_mc_plan(
    handle: u64,
    pages: *const SourcePage,
    count: u64,
    output: *mut SourcePage,
) -> i32 {
    boundary(-1, || {
        if output.is_null() {
            return Err("null source request result".into());
        }
        let pages = unsafe { source_pages(pages, count)? };
        session(handle, |engine| {
            let request = engine.plan_sections(&pages)?;
            unsafe {
                output.write(SourcePage {
                    data: request.as_ptr(),
                    length: request.len() as u64,
                });
            }
            Ok(())
        })?;
        Ok(0)
    })
}

/// # Safety
/// Input pages remain readable through synchronous decode/compile/join; no input pointers escape.
/// `output` points to a writable SourcePage. A nonempty session-owned view requests
/// color sources/biome samples; it expires at the next plan/accept/destroy. Zero length
/// confirms final publication.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_mc_sections(
    handle: u64,
    pages: *const SourcePage,
    count: u64,
    output: *mut SourcePage,
) -> i32 {
    boundary(-1, || {
        if output.is_null() {
            return Err("null tint request result".into());
        }
        let pages = unsafe { source_pages(pages, count)? };
        session(handle, |engine| {
            let request = engine.accept_sections(&pages)?;
            unsafe {
                output.write(SourcePage {
                    data: request.as_ptr(),
                    length: request.len() as u64,
                });
            }
            Ok(())
        })?;
        Ok(0)
    })
}

/// Renders top-left-origin RGBA8 synchronously; bytes after required output remain untouched.
///
/// # Safety
/// `data` must reference `length` readable bytes and `output` must reference
/// `capacity` writable bytes. Neither region may be concurrently accessed by other
/// threads during the call. Native work completes before these buffers are released.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_render(
    handle: u64,
    data: *const u8,
    length: u64,
    output: *mut u8,
    capacity: u64,
) -> i32 {
    boundary(-1, || {
        if data.is_null() || output.is_null() || length != 104 {
            return Err("frame must contain exactly 104 bytes and non-null buffers".into());
        }
        // SAFETY: Valid live input storage is part of the caller's ABI contract.
        let frame = Frame::parse(unsafe { std::slice::from_raw_parts(data, length as usize) })?;
        if capacity < frame.output_len() as u64 {
            return Err("output buffer is too small".into());
        }
        let rgba = session(handle, |s| s.render(&frame))?;
        if rgba.len() != frame.output_len() {
            return Err("renderer returned an invalid output extent".into());
        }
        // SAFETY: RGBA is a separate native allocation; output capacity was checked.
        unsafe {
            std::ptr::copy_nonoverlapping(rgba.as_ptr(), output, rgba.len());
        }
        Ok(0)
    })
}

/// Borrows the host instance, physical device, logical device, graphics queue and
/// completion timeline. All handles remain owned by Minecraft.
/// Descriptor flags at byte 44 describe enabled logical-device capabilities:
/// bit 0 is OMM and bit 1 is the Streamline RR extension/feature set in docs/abi.md.
/// # Safety
/// `data` must address 48 readable bytes. Handles/features/lifetimes must satisfy
/// Renderer::borrowed_mode_with_capabilities, including flushing the host encoder before
/// prime_destroy. An OMM flag proves VK_EXT_opacity_micromap, micromap and synchronization2
/// were enabled.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_attach_vulkan(handle: u64, data: *const u8, length: u64) -> i32 {
    boundary(-1, || {
        if data.is_null() || length != 48 {
            return Err("Vulkan host descriptor must contain 48 bytes".into());
        }
        let bytes = unsafe { std::slice::from_raw_parts(data, 48) };
        let (handles, family, capabilities) = parse_vulkan_host_descriptor(bytes)?;
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| {
                if s.failed || s.renderer.is_some() {
                    return Err("Engine already attached or failed".into());
                }
                s.renderer = Some(unsafe {
                    prime_vulkan::Renderer::borrowed_mode_with_capabilities(
                        handles[0],
                        handles[1],
                        handles[2],
                        handles[3],
                        family,
                        handles[4],
                        s.settings.mode,
                        capabilities,
                    )?
                });
                s.renderer.as_mut().unwrap().configure(s.settings)?;
                Ok(0)
            })
        }
        #[cfg(not(feature = "vulkan"))]
        {
            let _ = (handle, family, handles, capabilities);
            Err("Native library built without Vulkan".into())
        }
    })
}

fn parse_vulkan_host_descriptor(bytes: &[u8]) -> Result<([u64; 5], u32, u32), String> {
    if bytes.len() != 48 {
        return Err("Vulkan host descriptor must contain 48 bytes".into());
    }
    let value = |offset| u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap());
    let handles = [value(0), value(8), value(16), value(24), value(32)];
    let family = u32::from_le_bytes(bytes[40..44].try_into().unwrap());
    let capabilities = u32::from_le_bytes(bytes[44..48].try_into().unwrap());
    if handles.contains(&0) || capabilities & !3 != 0 {
        return Err("Invalid Vulkan host handles or unknown capability flags".into());
    }
    Ok((handles, family, capabilities))
}

/// Applies a current-version settings packet. Mode changes retire exclusive GPU resources.
/// # Safety
/// Data must contain 72 readable bytes. Call outside recording, after submitting the host encoder
/// when the renderer mode changes. This call may wait for that mode's last GPU consumer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_configure(handle: u64, data: *const u8, length: u64) -> i32 {
    boundary(-1, || {
        if data.is_null() || length != prime_scene::settings::RenderSettings::BYTES as u64 {
            return Err("Settings require a non-null 72-byte packet".into());
        }
        let settings = prime_scene::settings::RenderSettings::parse(unsafe {
            std::slice::from_raw_parts(data, length as usize)
        })?;
        session(handle, |s| s.configure(settings))?;
        Ok(0)
    })
}

/// Records GPU work in the host command buffer, directly writing the host color image.
/// # Safety
/// The packet is readable for length bytes. Vulkan arguments meet record_host's
/// contract; the caller must submit this command in order and signal the host timeline.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn prime_record(
    handle: u64,
    data: *const u8,
    length: u64,
    command: u64,
    image: u64,
    view: u64,
    serial: u64,
) -> i32 {
    boundary(-1, || {
        if data.is_null() || length != 104 || [command, image, view, serial].contains(&0) {
            return Err("Invalid host frame packet or Vulkan recording handles".into());
        }
        let frame = Frame::parse(unsafe { std::slice::from_raw_parts(data, length as usize) })?;
        #[cfg(feature = "vulkan")]
        {
            session(handle, |s| unsafe {
                s.record(&frame, command, image, view, serial)
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
        assert_eq!(unsafe { prime_submit(1, std::ptr::null(), 24) }, -1);
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
        let bytes: Vec<_> = [
            5_u32,
            0,
            4,
            1,
            1_f32.to_bits(),
            0.75_f32.to_bits(),
            0.08_f32.to_bits(),
            0,
            1_f32.to_bits(),
            1_f32.to_bits(),
            128_f32.to_bits(),
            0x13572468,
            30,
            0,
            1,
            1,
            3,
            8,
        ]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect();
        assert_eq!(unsafe { prime_configure(handle, bytes.as_ptr(), 72) }, 0);
        assert_eq!(unsafe { prime_configure(handle, std::ptr::null(), 72) }, -1);
        assert_eq!(
            unsafe { prime_configure(handle, bytes.as_ptr(), u64::MAX) },
            -1
        );
        let mut invalid = bytes.clone();
        invalid[0] = 2;
        assert_eq!(unsafe { prime_configure(handle, invalid.as_ptr(), 72) }, -1);
        let foreign = bytes.clone();
        assert_eq!(
            std::thread::spawn(move || unsafe { prime_configure(handle, foreign.as_ptr(), 72) })
                .join()
                .unwrap(),
            -1
        );
        invalid = bytes.clone();
        invalid[4] = 1;
        assert_eq!(
            unsafe { prime_configure(handle, invalid.as_ptr(), 72) },
            -1,
            "No rendered frame can be frozen"
        );
        session(handle, |engine| {
            assert_eq!(
                engine.settings,
                prime_scene::settings::RenderSettings {
                    // The accepted packet explicitly chooses four, independently of defaults.
                    bounces: 4,
                    ..Default::default()
                }
            );
            assert!(!engine.failed);
            Ok(())
        })
        .unwrap();
        assert_eq!(unsafe { prime_configure(handle, bytes.as_ptr(), 72) }, 0);
        assert_eq!(prime_destroy(handle), 0);
    }
    #[test]
    fn host_descriptor_requires_enabled_capabilities_and_known_flag_bits() {
        let mut bytes = [0; 48];
        for (index, value) in [11_u64, 22, 33, 44, 55].into_iter().enumerate() {
            bytes[index * 8..index * 8 + 8].copy_from_slice(&value.to_le_bytes());
        }
        bytes[40..44].copy_from_slice(&7_u32.to_le_bytes());
        assert_eq!(
            parse_vulkan_host_descriptor(&bytes).unwrap(),
            ([11, 22, 33, 44, 55], 7, 0)
        );
        bytes[44..48].copy_from_slice(&1_u32.to_le_bytes());
        assert_eq!(parse_vulkan_host_descriptor(&bytes).unwrap().2, 1);
        for flags in [4_u32, 5, u32::MAX] {
            bytes[44..48].copy_from_slice(&flags.to_le_bytes());
            assert!(parse_vulkan_host_descriptor(&bytes).is_err());
        }
        bytes[44..48].fill(0);
        for index in 0..5 {
            let mut zero_handle = bytes;
            zero_handle[index * 8..index * 8 + 8].fill(0);
            assert!(parse_vulkan_host_descriptor(&zero_handle).is_err());
        }
        assert!(parse_vulkan_host_descriptor(&bytes[..47]).is_err());
    }
}
