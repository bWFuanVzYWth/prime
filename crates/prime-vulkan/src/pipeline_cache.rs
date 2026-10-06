//! Device-local driver compilation cache. No frame state or GPU work lives here.
use ash::{Device, Instance, vk};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    sync::{
        Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};

const MAGIC: &[u8; 8] = b"PRIMEVKC";
const VERSION: u32 = 1;
const IDENTITY_BYTES: usize = 68;
const HEADER_BYTES: usize = 8 + 4 + IDENTITY_BYTES + 8 + 32;
const MAX_CACHE_BYTES: usize = 256 * 1024 * 1024;
static TEMP_ID: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Identity {
    vendor: u32,
    device: u32,
    driver_version: u32,
    api_version: u32,
    pointer_bytes: u32,
    cache_uuid: [u8; vk::UUID_SIZE],
    device_uuid: [u8; vk::UUID_SIZE],
    driver_uuid: [u8; vk::UUID_SIZE],
}

impl Identity {
    unsafe fn query(instance: &Instance, physical: vk::PhysicalDevice) -> Self {
        let mut ids = vk::PhysicalDeviceIDProperties::default();
        let mut properties = vk::PhysicalDeviceProperties2::default().push_next(&mut ids);
        unsafe { instance.get_physical_device_properties2(physical, &mut properties) };
        let properties = properties.properties;
        Self {
            vendor: properties.vendor_id,
            device: properties.device_id,
            driver_version: properties.driver_version,
            api_version: properties.api_version,
            pointer_bytes: size_of::<usize>() as u32,
            cache_uuid: properties.pipeline_cache_uuid,
            device_uuid: ids.device_uuid,
            driver_uuid: ids.driver_uuid,
        }
    }

    fn bytes(self) -> [u8; IDENTITY_BYTES] {
        let mut bytes = [0; IDENTITY_BYTES];
        for (out, value) in bytes[..20].as_chunks_mut::<4>().0.iter_mut().zip([
            self.vendor,
            self.device,
            self.driver_version,
            self.api_version,
            self.pointer_bytes,
        ]) {
            *out = value.to_le_bytes();
        }
        bytes[20..36].copy_from_slice(&self.cache_uuid);
        bytes[36..52].copy_from_slice(&self.device_uuid);
        bytes[52..68].copy_from_slice(&self.driver_uuid);
        bytes
    }

    fn file_name(self) -> String {
        let hash = Sha256::digest(self.bytes());
        let hex: String = hash.iter().map(|byte| format!("{byte:02x}")).collect();
        format!("{hex}.bin")
    }

    fn valid_driver_header(self, data: &[u8]) -> bool {
        data.len() >= 32
            && data[..4] == 32u32.to_le_bytes()
            && data[4..8] == 1u32.to_le_bytes()
            && data[8..12] == self.vendor.to_le_bytes()
            && data[12..16] == self.device.to_le_bytes()
            && data[16..32] == self.cache_uuid
    }
}

struct State {
    handle: vk::PipelineCache,
}

pub(super) struct PipelineCache {
    state: Mutex<State>,
    dirty: AtomicBool,
    identity: Identity,
    path: Option<PathBuf>,
}

impl PipelineCache {
    pub unsafe fn new(device: &Device, instance: &Instance, physical: vk::PhysicalDevice) -> Self {
        let identity = unsafe { Identity::query(instance, physical) };
        let path = cache_directory().map(|directory| directory.join(identity.file_name()));
        unsafe { Self::with_path(device, identity, path) }
    }

    unsafe fn with_path(device: &Device, identity: Identity, path: Option<PathBuf>) -> Self {
        let data = path
            .as_ref()
            .and_then(|path| match read_cache(path, identity) {
                Ok(data) => Some(data),
                Err(error) if error.kind() == io::ErrorKind::NotFound => None,
                Err(error) => {
                    eprintln!(
                        "[Prime PT] Ignoring pipeline cache {}: {error}",
                        path.display()
                    );
                    None
                }
            });
        let create = |data: &[u8]| unsafe {
            device.create_pipeline_cache(
                &vk::PipelineCacheCreateInfo::default().initial_data(data),
                None,
            )
        };
        let handle = if path.is_some() {
            match create(data.as_deref().unwrap_or_default()) {
                Ok(handle) => handle,
                Err(error) => {
                    eprintln!("[Prime PT] Pipeline cache initialization failed: {error:?}");
                    if data.is_some() {
                        create(&[]).unwrap_or_else(|error| {
                            eprintln!("[Prime PT] Empty pipeline cache unavailable: {error:?}");
                            vk::PipelineCache::null()
                        })
                    } else {
                        vk::PipelineCache::null()
                    }
                }
            }
        } else {
            vk::PipelineCache::null()
        };
        if let Some(path) = &path {
            eprintln!(
                "[Prime PT] Pipeline cache: {} (loaded={} bytes, enabled={})",
                path.display(),
                data.as_ref().map_or(0, Vec::len),
                handle != vk::PipelineCache::null()
            );
        }
        Self {
            state: Mutex::new(State { handle }),
            dirty: AtomicBool::new(false),
            identity,
            path,
        }
    }

    #[cfg(test)]
    pub fn disabled() -> Self {
        Self {
            state: Mutex::new(State {
                handle: vk::PipelineCache::null(),
            }),
            dirty: AtomicBool::new(false),
            identity: Identity {
                vendor: 0,
                device: 0,
                driver_version: 0,
                api_version: 0,
                pointer_bytes: size_of::<usize>() as u32,
                cache_uuid: [0; vk::UUID_SIZE],
                device_uuid: [0; vk::UUID_SIZE],
                driver_uuid: [0; vk::UUID_SIZE],
            },
            path: None,
        }
    }

    /// The mutex also prevents snapshots/destruction from racing cache mutation.
    /// # Safety
    /// The create infos obey vkCreateComputePipelines and reference this live device.
    pub unsafe fn create_compute(
        &self,
        device: &Device,
        infos: &[vk::ComputePipelineCreateInfo<'_>],
    ) -> Result<Vec<vk::Pipeline>, (Vec<vk::Pipeline>, vk::Result)> {
        let state = self.state.lock().unwrap();
        let result = unsafe { device.create_compute_pipelines(state.handle, infos, None) };
        // Failed batches may still have compiled entries and partial pipelines.
        if state.handle != vk::PipelineCache::null() && !infos.is_empty() {
            self.dirty.store(true, Ordering::Release);
        }
        result
    }

    pub fn save(&self, device: &Device) {
        if !self.dirty.load(Ordering::Acquire) {
            return;
        }
        let state = self.state.lock().unwrap();
        if !self.dirty.swap(false, Ordering::Relaxed) || state.handle == vk::PipelineCache::null() {
            return;
        }
        let Some(path) = &self.path else { return };
        let result = (|| {
            let data = unsafe { driver_data(device, state.handle) }.map_err(io::Error::other)?;
            let header = encode_header(self.identity, &data)?;
            atomic_write_parts(path, &[&header, &data])
        })();
        match result {
            Ok(()) => {}
            Err(error) => eprintln!(
                "[Prime PT] Could not save pipeline cache {}: {error}",
                path.display()
            ),
        }
    }

    /// Called only while the owning Context still has a live, legally destroyable device.
    pub unsafe fn finish(&mut self, device: &Device) {
        // One shutdown retry is allowed after a failed initialization checkpoint;
        // a failed checkpoint is never retried once per ordinary frame.
        self.dirty.store(true, Ordering::Relaxed);
        self.save(device);
        let state = self.state.get_mut().unwrap();
        if state.handle != vk::PipelineCache::null() {
            unsafe { device.destroy_pipeline_cache(state.handle, None) };
            state.handle = vk::PipelineCache::null();
        }
    }
}

fn cache_directory() -> Option<PathBuf> {
    if let Some(directory) = std::env::var_os("PRIME_PIPELINE_CACHE") {
        return if directory == "0" {
            None
        } else if directory.is_empty() {
            default_directory()
        } else {
            Some(PathBuf::from(directory))
        };
    }
    default_directory()
}

fn default_directory() -> Option<PathBuf> {
    #[cfg(windows)]
    let root = std::env::var_os("LOCALAPPDATA").map(PathBuf::from);
    #[cfg(not(windows))]
    let root = std::env::var_os("XDG_CACHE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".cache")));
    root.map(|root| root.join("PrimePT").join("pipeline-cache").join("v1"))
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn encode_header(identity: Identity, data: &[u8]) -> io::Result<[u8; HEADER_BYTES]> {
    if data.len() > MAX_CACHE_BYTES || !identity.valid_driver_header(data) {
        return Err(invalid("Invalid or oversized Vulkan pipeline cache"));
    }
    let mut header = [0; HEADER_BYTES];
    header[..8].copy_from_slice(MAGIC);
    header[8..12].copy_from_slice(&VERSION.to_le_bytes());
    header[12..80].copy_from_slice(&identity.bytes());
    header[80..88].copy_from_slice(&(data.len() as u64).to_le_bytes());
    header[88..120].copy_from_slice(&Sha256::digest(data));
    Ok(header)
}

fn validate(identity: Identity, header: &[u8], data: &[u8]) -> io::Result<()> {
    if header.len() != HEADER_BYTES
        || header[..8] != *MAGIC
        || header[8..12] != VERSION.to_le_bytes()
        || header[12..80] != identity.bytes()
    {
        return Err(invalid("Pipeline cache format or device identity mismatch"));
    }
    let length = u64::from_le_bytes(header[80..88].try_into().unwrap());
    if length > MAX_CACHE_BYTES as u64
        || length != data.len() as u64
        || !identity.valid_driver_header(data)
        || header[88..120] != Sha256::digest(data)[..]
    {
        return Err(invalid("Truncated, corrupt or oversized pipeline cache"));
    }
    Ok(())
}

fn read_cache(path: &Path, identity: Identity) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let maximum = (HEADER_BYTES + MAX_CACHE_BYTES) as u64;
    if file.metadata()?.len() > maximum {
        return Err(invalid("Pipeline cache exceeds size limit"));
    }
    let mut header = [0; HEADER_BYTES];
    file.read_exact(&mut header)?;
    let mut data = Vec::new();
    file.take(MAX_CACHE_BYTES as u64 + 1)
        .read_to_end(&mut data)?;
    if data.len() > MAX_CACHE_BYTES {
        return Err(invalid("Pipeline cache exceeds size limit"));
    }
    validate(identity, &header, &data)?;
    Ok(data)
}

unsafe fn driver_data(device: &Device, handle: vk::PipelineCache) -> Result<Vec<u8>, String> {
    let get = device.fp_v1_0().get_pipeline_cache_data;
    let mut size = 0;
    let result = unsafe { get(device.handle(), handle, &mut size, std::ptr::null_mut()) };
    if result != vk::Result::SUCCESS {
        return Err(format!("Get pipeline cache size: {result:?}"));
    }
    if !(32..=MAX_CACHE_BYTES).contains(&size) {
        return Err("Pipeline cache exceeds storage bounds".into());
    }
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(size)
        .map_err(|error| format!("Allocate pipeline cache snapshot: {error}"))?;
    bytes.resize(size, 0);
    let result = unsafe {
        get(
            device.handle(),
            handle,
            &mut size,
            bytes.as_mut_ptr().cast(),
        )
    };
    if result != vk::Result::SUCCESS || size > bytes.len() {
        return Err(format!("Get complete pipeline cache data: {result:?}"));
    }
    bytes.truncate(size);
    Ok(bytes)
}

fn atomic_write_parts(path: &Path, parts: &[&[u8]]) -> io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| invalid("Pipeline cache has no parent directory"))?;
    fs::create_dir_all(parent)?;
    let name = path
        .file_name()
        .ok_or_else(|| invalid("Pipeline cache has no file name"))?;
    let (temporary, mut file) = loop {
        let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
        let temporary = parent.join(format!(
            "{}.{}.{id}.tmp",
            name.to_string_lossy(),
            std::process::id()
        ));
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
        {
            Ok(file) => break (temporary, file),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error),
        }
    };
    let result = (|| {
        for part in parts {
            file.write_all(part)?;
        }
        file.sync_all()?;
        drop(file);
        replace(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(not(windows))]
fn replace(source: &Path, target: &Path) -> io::Result<()> {
    fs::rename(source, target)
}

#[cfg(windows)]
fn replace(source: &Path, target: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn MoveFileExW(source: *const u16, target: *const u16, flags: u32) -> i32;
    }
    let source: Vec<_> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let target: Vec<_> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    // Same-volume atomic replacement; never delete the previous valid cache first.
    if unsafe { MoveFileExW(source.as_ptr(), target.as_ptr(), 1 | 8) } == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ash::vk::Handle;
    use std::{cell::RefCell, ffi::c_void};

    fn encode(identity: Identity, data: &[u8]) -> io::Result<Vec<u8>> {
        let header = encode_header(identity, data)?;
        Ok([&header[..], data].concat())
    }

    fn decode(identity: Identity, bytes: &[u8]) -> io::Result<Vec<u8>> {
        if bytes.len() < HEADER_BYTES {
            return Err(invalid("Truncated pipeline cache header"));
        }
        validate(identity, &bytes[..HEADER_BYTES], &bytes[HEADER_BYTES..])?;
        Ok(bytes[HEADER_BYTES..].to_vec())
    }

    fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
        atomic_write_parts(path, &[bytes])
    }

    fn identity() -> Identity {
        Identity {
            vendor: 0x10de,
            device: 42,
            driver_version: 7,
            api_version: vk::API_VERSION_1_3,
            pointer_bytes: size_of::<usize>() as u32,
            cache_uuid: [1; vk::UUID_SIZE],
            device_uuid: [2; vk::UUID_SIZE],
            driver_uuid: [3; vk::UUID_SIZE],
        }
    }

    fn payload() -> Vec<u8> {
        let id = identity();
        let mut data = Vec::new();
        data.extend_from_slice(&32u32.to_le_bytes());
        data.extend_from_slice(&1u32.to_le_bytes());
        data.extend_from_slice(&id.vendor.to_le_bytes());
        data.extend_from_slice(&id.device.to_le_bytes());
        data.extend_from_slice(&id.cache_uuid);
        data.extend_from_slice(b"opaque driver entry");
        data
    }

    struct Directory(PathBuf);
    impl Directory {
        fn new() -> Self {
            let id = TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir()
                .join(format!("prime-pipeline-cache-{}-{id}", std::process::id()));
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
        fn file(&self) -> PathBuf {
            self.0.join("cache.bin")
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn cache_round_trip_and_every_identity_component_is_required() {
        let id = identity();
        let bytes = encode(id, &payload()).unwrap();
        assert_eq!(decode(id, &bytes).unwrap(), payload());
        for offset in [0, 4, 8, 12, 16, 20, 36, 52] {
            let mut mismatch = bytes.clone();
            mismatch[12 + offset] ^= 1;
            assert!(
                decode(id, &mismatch).is_err(),
                "accepted identity field at {offset}"
            );
        }
        let mut changed_driver = id;
        changed_driver.driver_version += 1;
        assert_ne!(id.file_name(), changed_driver.file_name());
    }

    #[test]
    fn corruption_truncation_and_invalid_driver_headers_are_rejected_before_vulkan() {
        let id = identity();
        let bytes = encode(id, &payload()).unwrap();
        for length in 0..bytes.len() {
            assert!(
                decode(id, &bytes[..length]).is_err(),
                "accepted truncation at {length}"
            );
        }
        for index in [0, 8, 80, 88, HEADER_BYTES, bytes.len() - 1] {
            let mut damaged = bytes.clone();
            damaged[index] ^= 0x80;
            assert!(
                decode(id, &damaged).is_err(),
                "accepted corruption at {index}"
            );
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(decode(id, &trailing).is_err());
        let mut oversized = bytes.clone();
        oversized[80..88].copy_from_slice(&((MAX_CACHE_BYTES as u64) + 1).to_le_bytes());
        assert!(decode(id, &oversized).is_err());
        for offset in [0, 4, 8, 12, 16] {
            let mut damaged = bytes.clone();
            damaged[HEADER_BYTES + offset] ^= 1;
            let digest = Sha256::digest(&damaged[HEADER_BYTES..]);
            damaged[88..120].copy_from_slice(&digest);
            assert!(
                decode(id, &damaged).is_err(),
                "accepted bad Vulkan header at {offset}"
            );
        }
    }

    #[test]
    fn atomic_replacement_and_concurrent_writers_publish_only_complete_cache_files() {
        let directory = Directory::new();
        let path = directory.file();
        let bytes = encode(identity(), &payload()).unwrap();
        atomic_write(&path, &bytes).unwrap();
        assert_eq!(read_cache(&path, identity()).unwrap(), payload());
        let mut alternate = payload();
        alternate.extend_from_slice(b"new shader specialization");
        let next = encode(identity(), &alternate).unwrap();
        let published = std::thread::scope(|scope| {
            let mut workers = Vec::new();
            for index in 0..8 {
                let data = if index % 2 == 0 { &bytes } else { &next };
                let target = &path;
                workers.push(scope.spawn(move || atomic_write(target, data).is_ok()));
            }
            workers
                .into_iter()
                .map(|worker| worker.join().unwrap())
                .filter(|published| *published)
                .count()
        });
        // Windows can reject competing replace operations while an old target
        // is pending deletion. Losing writers must clean up and keep a valid file.
        assert!(published > 0);
        let final_data = read_cache(&path, identity()).unwrap();
        assert!(final_data == payload() || final_data == alternate);
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[test]
    fn failed_replace_keeps_the_existing_target_and_removes_its_temporary_file() {
        let directory = Directory::new();
        let path = directory.file();
        fs::create_dir(&path).unwrap();
        fs::write(path.join("existing-owner"), b"kept").unwrap();
        assert!(atomic_write(&path, b"new cache").is_err());
        assert_eq!(fs::read(path.join("existing-owner")).unwrap(), b"kept");
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[cfg(windows)]
    #[test]
    fn failed_windows_replace_retains_previous_valid_cache_when_target_is_in_use() {
        use std::os::windows::fs::OpenOptionsExt;
        let directory = Directory::new();
        let path = directory.file();
        let bytes = encode(identity(), &payload()).unwrap();
        atomic_write(&path, &bytes).unwrap();
        let _reader = OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&path)
            .unwrap();
        assert!(atomic_write(&path, b"replacement").is_err());
        assert_eq!(read_cache(&path, identity()).unwrap(), payload());
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[derive(Default)]
    struct Calls {
        initial_sizes: Vec<usize>,
        cache_handles: Vec<vk::PipelineCache>,
        data_queries: u32,
        destroyed: u32,
        reject_seed: bool,
        reject_empty: bool,
        reject_snapshot: bool,
    }
    thread_local! { static CALLS: RefCell<Calls> = RefCell::new(Calls::default()); }

    unsafe extern "system" fn create_cache(
        _: vk::Device,
        info: *const vk::PipelineCacheCreateInfo<'_>,
        _: *const vk::AllocationCallbacks<'_>,
        handle: *mut vk::PipelineCache,
    ) -> vk::Result {
        CALLS.with(|calls| {
            let mut calls = calls.borrow_mut();
            let size = unsafe { (*info).initial_data_size };
            calls.initial_sizes.push(size);
            if size > 0 && calls.reject_seed || size == 0 && calls.reject_empty {
                vk::Result::ERROR_UNKNOWN
            } else {
                unsafe { *handle = vk::PipelineCache::from_raw(11) };
                vk::Result::SUCCESS
            }
        })
    }

    unsafe extern "system" fn get_cache(
        _: vk::Device,
        _: vk::PipelineCache,
        size: *mut usize,
        data: *mut c_void,
    ) -> vk::Result {
        CALLS.with(|calls| {
            let mut calls = calls.borrow_mut();
            calls.data_queries += 1;
            if calls.reject_snapshot {
                return vk::Result::ERROR_UNKNOWN;
            }
            let bytes = payload();
            if !data.is_null() {
                assert!(unsafe { *size } >= bytes.len());
                unsafe {
                    std::ptr::copy_nonoverlapping(bytes.as_ptr(), data.cast::<u8>(), bytes.len())
                };
            }
            unsafe { *size = bytes.len() };
            vk::Result::SUCCESS
        })
    }

    unsafe extern "system" fn destroy_cache(
        _: vk::Device,
        handle: vk::PipelineCache,
        _: *const vk::AllocationCallbacks<'_>,
    ) {
        assert_eq!(handle.as_raw(), 11);
        CALLS.with(|calls| calls.borrow_mut().destroyed += 1);
    }

    unsafe extern "system" fn create_pipelines(
        _: vk::Device,
        cache: vk::PipelineCache,
        count: u32,
        _: *const vk::ComputePipelineCreateInfo<'_>,
        _: *const vk::AllocationCallbacks<'_>,
        pipelines: *mut vk::Pipeline,
    ) -> vk::Result {
        CALLS.with(|calls| calls.borrow_mut().cache_handles.push(cache));
        for index in 0..count as usize {
            unsafe { *pipelines.add(index) = vk::Pipeline::from_raw(index as u64 + 20) };
        }
        vk::Result::SUCCESS
    }

    fn device() -> Device {
        unsafe {
            Device::load_with(
                |name| match name.to_bytes() {
                    b"vkCreatePipelineCache" => create_cache as *const () as *const c_void,
                    b"vkGetPipelineCacheData" => get_cache as *const () as *const c_void,
                    b"vkDestroyPipelineCache" => destroy_cache as *const () as *const c_void,
                    b"vkCreateComputePipelines" => create_pipelines as *const () as *const c_void,
                    _ => std::ptr::null(),
                },
                vk::Device::from_raw(1),
            )
        }
    }

    #[test]
    fn driver_receives_loaded_cache_and_checkpoint_is_not_repeated_without_new_pipelines() {
        CALLS.with(|calls| *calls.borrow_mut() = Calls::default());
        let directory = Directory::new();
        let path = directory.file();
        atomic_write(&path, &encode(identity(), &payload()).unwrap()).unwrap();
        let device = device();
        let mut cache =
            unsafe { PipelineCache::with_path(&device, identity(), Some(path.clone())) };
        let pipelines = unsafe {
            cache.create_compute(&device, &[vk::ComputePipelineCreateInfo::default(); 2])
        }
        .unwrap();
        assert_eq!(pipelines.len(), 2);
        cache.save(&device);
        cache.save(&device);
        CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!(calls.initial_sizes, [payload().len()]);
            assert_eq!(calls.cache_handles, [vk::PipelineCache::from_raw(11)]);
            assert_eq!(calls.data_queries, 2);
        });
        assert_eq!(read_cache(&path, identity()).unwrap(), payload());
        unsafe { cache.finish(&device) };
        CALLS.with(|calls| assert_eq!(calls.borrow().destroyed, 1));
    }

    #[test]
    fn driver_rejection_retries_empty_cache_and_cache_unavailability_keeps_pipeline_creation_working()
     {
        let directory = Directory::new();
        let path = directory.file();
        atomic_write(&path, &encode(identity(), &payload()).unwrap()).unwrap();
        let device = device();
        CALLS.with(|calls| {
            *calls.borrow_mut() = Calls {
                reject_seed: true,
                ..Default::default()
            }
        });
        let mut cache =
            unsafe { PipelineCache::with_path(&device, identity(), Some(path.clone())) };
        unsafe { cache.create_compute(&device, &[vk::ComputePipelineCreateInfo::default()]) }
            .unwrap();
        CALLS.with(|calls| assert_eq!(calls.borrow().initial_sizes, [payload().len(), 0]));
        unsafe { cache.finish(&device) };
        CALLS.with(|calls| {
            *calls.borrow_mut() = Calls {
                reject_seed: true,
                reject_empty: true,
                ..Default::default()
            }
        });
        let mut cache = unsafe { PipelineCache::with_path(&device, identity(), Some(path)) };
        unsafe { cache.create_compute(&device, &[vk::ComputePipelineCreateInfo::default()]) }
            .unwrap();
        unsafe { cache.finish(&device) };
        CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!(calls.cache_handles, [vk::PipelineCache::null()]);
            assert_eq!(calls.data_queries, 0);
            assert_eq!(calls.destroyed, 0);
        });
    }

    #[test]
    fn invalid_disk_data_is_not_passed_to_driver_and_failed_snapshots_do_not_retry_each_frame() {
        CALLS.with(|calls| {
            *calls.borrow_mut() = Calls {
                reject_snapshot: true,
                ..Default::default()
            }
        });
        let directory = Directory::new();
        let path = directory.file();
        fs::write(&path, b"broken cache").unwrap();
        let device = device();
        let mut cache =
            unsafe { PipelineCache::with_path(&device, identity(), Some(path.clone())) };
        unsafe { cache.create_compute(&device, &[vk::ComputePipelineCreateInfo::default()]) }
            .unwrap();
        for _ in 0..4 {
            cache.save(&device);
        }
        CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!(calls.initial_sizes, [0]);
            assert_eq!(calls.data_queries, 1);
        });
        assert_eq!(fs::read(&path).unwrap(), b"broken cache");
        unsafe { cache.finish(&device) };
        CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!(calls.data_queries, 2);
            assert_eq!(calls.destroyed, 1);
        });
    }
}
