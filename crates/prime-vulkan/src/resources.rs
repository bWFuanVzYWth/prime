use crate::arena::{Arena, Lease};
use ash::vk::Handle;
use ash::{Device, Entry, Instance, vk};
use std::{
    collections::VecDeque,
    ffi::CStr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Instant,
};

/// Cumulative opt-in counters. GPU time comes from timestamp queries around each
/// command buffer; CPU waiting includes submission latency and GPU execution.
#[derive(Clone, Copy, Debug, Default)]
pub struct GpuProfile {
    pub submissions: u64,
    pub record_ns: u64,
    pub submit_ns: u64,
    pub wait_ns: u64,
    pub gpu_ns: u64,
    pub allocations: u64,
    pub allocation_ns: u64,
    pub allocated_bytes: u64,
    pub upload_ns: u64,
    pub uploaded_bytes: u64,
    pub readback_ns: u64,
    pub readback_bytes: u64,
}

#[derive(Default)]
struct ProfileCounters {
    submissions: AtomicU64,
    record_ns: AtomicU64,
    submit_ns: AtomicU64,
    wait_ns: AtomicU64,
    gpu_ns: AtomicU64,
    allocations: AtomicU64,
    allocation_ns: AtomicU64,
    allocated_bytes: AtomicU64,
    upload_ns: AtomicU64,
    uploaded_bytes: AtomicU64,
    readback_ns: AtomicU64,
    readback_bytes: AtomicU64,
}

struct Profile {
    query_pool: vk::QueryPool,
    timestamp_period: f64,
    timestamp_mask: u64,
    trace: bool,
    counters: ProfileCounters,
}

fn elapsed_ns(started: Option<Instant>) -> u64 {
    started.map_or(0, |start| {
        start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
    })
}

pub(super) fn error(context: &str, error: vk::Result) -> String {
    format!("{context}: {error:?}")
}

struct InstanceOwner {
    _entry: Entry,
    instance: Instance,
    debug: Option<(ash::ext::debug_utils::Instance, vk::DebugUtilsMessengerEXT)>,
    owned: bool,
}
impl Drop for InstanceOwner {
    fn drop(&mut self) {
        unsafe {
            if let Some((loader, messenger)) = &self.debug {
                loader.destroy_debug_utils_messenger(*messenger, None);
            }
            if self.owned {
                self.instance.destroy_instance(None);
            }
        }
    }
}

enum RetiredResource {
    Acceleration(vk::AccelerationStructureKHR),
    Micromap(vk::MicromapEXT),
    Buffer(vk::Buffer, vk::DeviceMemory),
    Image(vk::Image, vk::ImageView, vk::DeviceMemory),
}

struct HostState {
    command: vk::CommandBuffer,
    last_serial: u64,
    retired: VecDeque<(u64, RetiredResource)>,
}

struct Host {
    timeline: vk::Semaphore,
    // Once the final submission is proven complete, teardown must not consult
    // the host again: another failing query could otherwise split ownership.
    finished: AtomicBool,
    // Host rendering remains thread-confined. This one short metadata lock keeps
    // Arc resource drops sound without UnsafeCell; no Vulkan call holds the lock.
    state: Mutex<HostState>,
}
unsafe extern "system" fn validation(
    severity: vk::DebugUtilsMessageSeverityFlagsEXT,
    _kind: vk::DebugUtilsMessageTypeFlagsEXT,
    data: *const vk::DebugUtilsMessengerCallbackDataEXT<'_>,
    _user: *mut std::ffi::c_void,
) -> vk::Bool32 {
    if !data.is_null() {
        unsafe {
            eprintln!(
                "[Prime Vulkan {severity:?}] {}",
                CStr::from_ptr((*data).p_message).to_string_lossy()
            );
        }
    }
    vk::FALSE
}

pub(super) struct OpacityMicromapSupport {
    pub loader: ash::ext::opacity_micromap::Device,
    pub synchronization: ash::khr::synchronization2::Device,
    pub max_two_state: u32,
    pub max_four_state: u32,
}

unsafe fn micromap_supported(
    instance: &Instance,
    physical: vk::PhysicalDevice,
) -> Result<bool, String> {
    let available = unsafe { instance.enumerate_device_extension_properties(physical) }
        .map_err(|e| error("Enumerate optional OMM extensions", e))?;
    let has = |name: &CStr| {
        available
            .iter()
            .any(|a| unsafe { CStr::from_ptr(a.extension_name.as_ptr()) == name })
    };
    // Enable the KHR dependency even on 1.3 devices: the standalone instance requests 1.2.
    if !has(ash::ext::opacity_micromap::NAME) || !has(ash::khr::synchronization2::NAME) {
        return Ok(false);
    }
    let mut sync = vk::PhysicalDeviceSynchronization2Features::default();
    let mut omm = vk::PhysicalDeviceOpacityMicromapFeaturesEXT::default();
    unsafe {
        instance.get_physical_device_features2(
            physical,
            &mut vk::PhysicalDeviceFeatures2::default()
                .push_next(&mut omm)
                .push_next(&mut sync),
        );
    }
    Ok(omm.micromap != 0 && sync.synchronization2 != 0)
}

unsafe fn micromap_support(
    instance: &Instance,
    physical: vk::PhysicalDevice,
    device: &Device,
    enabled: bool,
) -> Option<OpacityMicromapSupport> {
    if !enabled {
        return None;
    }
    let mut properties = vk::PhysicalDeviceOpacityMicromapPropertiesEXT::default();
    unsafe {
        instance.get_physical_device_properties2(
            physical,
            &mut vk::PhysicalDeviceProperties2::default().push_next(&mut properties),
        );
    }
    Some(OpacityMicromapSupport {
        loader: ash::ext::opacity_micromap::Device::new(instance, device),
        synchronization: ash::khr::synchronization2::Device::new(instance, device),
        max_two_state: properties.max_opacity2_state_subdivision_level,
        max_four_state: properties.max_opacity4_state_subdivision_level,
    })
}

pub(super) struct Context {
    _instance: Arc<InstanceOwner>,
    pub device: Device,
    pub physical: vk::PhysicalDevice,
    pub acceleration: ash::khr::acceleration_structure::Device,
    pub opacity_micromap: Option<OpacityMicromapSupport>,
    pub streamline_capable: bool,
    pub queue: vk::Queue,
    pub queue_family: u32,
    pub pool: vk::CommandPool,
    pub memory: vk::PhysicalDeviceMemoryProperties,
    pub scratch_alignment: u64,
    pub max_storage_buffer_range: u64,
    max_image_dimension_2d: u32,
    max_compute_work_group_count: [u32; 3],
    pub max_memory_allocations: u32,
    max_blas_geometries: u64,
    max_blas_primitives: u64,
    pub timestamp_period: f32,
    pub timestamp_bits: u32,
    pub live_allocations: AtomicU64,
    pub name: String,
    // CPU diagnostics are independent of GPU queries/PRIME_PROFILE. Count only
    // successful mapped writes; these bytes do not claim PCIe traffic.
    cpu_uploaded_bytes: AtomicU64,
    profile: Option<Profile>,
    host: Option<Host>,
    // If both fence wait and device drain fail without DEVICE_LOST, Vulkan gives
    // no proof of completion. Retain native objects rather than free live work.
    pub uncertain_submission: AtomicBool,
}
impl Drop for Context {
    fn drop(&mut self) {
        unsafe {
            if self.host.is_some()
                && self.can_destroy()
                && let Err(message) = self.wait_host_idle()
            {
                self.uncertain_submission.store(true, Ordering::Relaxed);
                eprintln!("[Prime PT] Retaining pending host Vulkan resources: {message}");
            }
            if self.uncertain_submission.load(Ordering::Relaxed) {
                std::mem::forget(self._instance.clone());
                eprintln!(
                    "[Prime PT] Retaining Vulkan device after an unconfirmed submission failure"
                );
                return;
            }
            if self.host.is_none() {
                let _ = self.device.device_wait_idle();
            }
            if let Some(profile) = &self.profile {
                self.device.destroy_query_pool(profile.query_pool, None);
            }
            self.device.destroy_command_pool(self.pool, None);
            if self.host.is_none() {
                self.device.destroy_device(None);
            }
        }
    }
}
impl Context {
    pub fn new() -> Result<Arc<Self>, String> {
        unsafe {
            let entry = Entry::load().map_err(|e| format!("Load Vulkan loader: {e}"))?;
            let application = vk::ApplicationInfo::default()
                .application_name(c"Prime PT")
                .api_version(vk::API_VERSION_1_2);
            let validation_enabled =
                std::env::var_os("PRIME_VK_VALIDATION").is_some_and(|v| v != "0");
            let layer = c"VK_LAYER_KHRONOS_validation";
            if validation_enabled
                && !entry
                    .enumerate_instance_layer_properties()
                    .map_err(|e| error("Enumerate Vulkan layers", e))?
                    .iter()
                    .any(|p| CStr::from_ptr(p.layer_name.as_ptr()) == layer)
            {
                return Err(
                    "PRIME_VK_VALIDATION requested but VK_LAYER_KHRONOS_validation is unavailable"
                        .into(),
                );
            }
            let layers = if validation_enabled {
                vec![layer.as_ptr()]
            } else {
                vec![]
            };
            let extensions = if validation_enabled {
                vec![ash::ext::debug_utils::NAME.as_ptr()]
            } else {
                vec![]
            };
            let instance = entry
                .create_instance(
                    &vk::InstanceCreateInfo::default()
                        .application_info(&application)
                        .enabled_layer_names(&layers)
                        .enabled_extension_names(&extensions),
                    None,
                )
                .map_err(|e| error("Create Vulkan instance", e))?;
            let mut owner = InstanceOwner {
                _entry: entry,
                instance,
                debug: None,
                owned: true,
            };
            if validation_enabled {
                let loader = ash::ext::debug_utils::Instance::new(&owner._entry, &owner.instance);
                let messenger = loader
                    .create_debug_utils_messenger(
                        &vk::DebugUtilsMessengerCreateInfoEXT::default()
                            .message_severity(
                                vk::DebugUtilsMessageSeverityFlagsEXT::WARNING
                                    | vk::DebugUtilsMessageSeverityFlagsEXT::ERROR,
                            )
                            .message_type(
                                vk::DebugUtilsMessageTypeFlagsEXT::GENERAL
                                    | vk::DebugUtilsMessageTypeFlagsEXT::VALIDATION
                                    | vk::DebugUtilsMessageTypeFlagsEXT::PERFORMANCE,
                            )
                            .pfn_user_callback(Some(validation)),
                        None,
                    )
                    .map_err(|e| error("Create Vulkan validation messenger", e))?;
                owner.debug = Some((loader, messenger));
            }
            let owner = Arc::new(owner);
            let required = [
                ash::khr::acceleration_structure::NAME,
                ash::khr::ray_query::NAME,
                ash::khr::deferred_host_operations::NAME,
            ];
            let mut selected = None;
            for physical in owner
                .instance
                .enumerate_physical_devices()
                .map_err(|e| error("Enumerate Vulkan GPUs", e))?
            {
                let properties = owner.instance.get_physical_device_properties(physical);
                if properties.api_version < vk::API_VERSION_1_2 {
                    continue;
                }
                let available = owner
                    .instance
                    .enumerate_device_extension_properties(physical)
                    .map_err(|e| error("Enumerate Vulkan extensions", e))?;
                if !required.iter().all(|r| {
                    available
                        .iter()
                        .any(|a| CStr::from_ptr(a.extension_name.as_ptr()) == *r)
                }) {
                    continue;
                }
                let mut address = vk::PhysicalDeviceBufferDeviceAddressFeatures::default();
                let mut acceleration =
                    vk::PhysicalDeviceAccelerationStructureFeaturesKHR::default();
                let mut query = vk::PhysicalDeviceRayQueryFeaturesKHR::default();
                let mut timeline = vk::PhysicalDeviceTimelineSemaphoreFeatures::default();
                let mut features = vk::PhysicalDeviceFeatures2::default()
                    .push_next(&mut address)
                    .push_next(&mut acceleration)
                    .push_next(&mut query)
                    .push_next(&mut timeline);
                owner
                    .instance
                    .get_physical_device_features2(physical, &mut features);
                if address.buffer_device_address == 0
                    || acceleration.acceleration_structure == 0
                    || query.ray_query == 0
                    || timeline.timeline_semaphore == 0
                {
                    continue;
                }
                let family = owner
                    .instance
                    .get_physical_device_queue_family_properties(physical)
                    .iter()
                    .position(|p| {
                        p.queue_count > 0 && p.queue_flags.contains(vk::QueueFlags::COMPUTE)
                    });
                if let Some(family) = family {
                    let discrete = properties.device_type == vk::PhysicalDeviceType::DISCRETE_GPU;
                    if selected.is_none() || discrete {
                        selected = Some((physical, family as u32, properties));
                    }
                    if discrete {
                        break;
                    }
                }
            }
            let (physical, family, properties) = selected.ok_or("No Vulkan 1.2 device supports accelerationStructure, rayQuery, bufferDeviceAddress and timelineSemaphore")?;
            let priorities = [1.0];
            let queues = [vk::DeviceQueueCreateInfo::default()
                .queue_family_index(family)
                .queue_priorities(&priorities)];
            let omm_enabled = micromap_supported(&owner.instance, physical)?;
            let mut names: Vec<_> = required.iter().map(|n| n.as_ptr()).collect();
            if omm_enabled {
                names.extend([
                    ash::ext::opacity_micromap::NAME.as_ptr(),
                    ash::khr::synchronization2::NAME.as_ptr(),
                ]);
            }
            let mut sync_features =
                vk::PhysicalDeviceSynchronization2Features::default().synchronization2(omm_enabled);
            let mut omm_features =
                vk::PhysicalDeviceOpacityMicromapFeaturesEXT::default().micromap(omm_enabled);
            let mut address = vk::PhysicalDeviceBufferDeviceAddressFeatures::default()
                .buffer_device_address(true);
            let mut acceleration = vk::PhysicalDeviceAccelerationStructureFeaturesKHR::default()
                .acceleration_structure(true);
            let mut query = vk::PhysicalDeviceRayQueryFeaturesKHR::default().ray_query(true);
            let mut timeline =
                vk::PhysicalDeviceTimelineSemaphoreFeatures::default().timeline_semaphore(true);
            // Optional narrow storage formats are used by the standalone RR-display fixture.
            // Borrowed devices receive this feature through the host's RR capability negotiation.
            let supported_core = owner.instance.get_physical_device_features(physical);
            let optional_core = vk::PhysicalDeviceFeatures::default()
                .shader_storage_image_extended_formats(
                    supported_core.shader_storage_image_extended_formats != 0,
                );
            let mut create_info = vk::DeviceCreateInfo::default()
                .queue_create_infos(&queues)
                .enabled_extension_names(&names)
                .enabled_features(&optional_core)
                .push_next(&mut address)
                .push_next(&mut acceleration)
                .push_next(&mut query)
                .push_next(&mut timeline);
            if omm_enabled {
                create_info = create_info
                    .push_next(&mut omm_features)
                    .push_next(&mut sync_features);
            }
            let device = owner
                .instance
                .create_device(physical, &create_info, None)
                .map_err(|e| error("Create Vulkan ray-query device", e))?;
            let pool = match device.create_command_pool(
                &vk::CommandPoolCreateInfo::default()
                    .queue_family_index(family)
                    .flags(vk::CommandPoolCreateFlags::TRANSIENT),
                None,
            ) {
                Ok(pool) => pool,
                Err(e) => {
                    device.destroy_device(None);
                    return Err(error("Create Vulkan command pool", e));
                }
            };
            let acceleration_loader =
                ash::khr::acceleration_structure::Device::new(&owner.instance, &device);
            let mut acceleration_properties =
                vk::PhysicalDeviceAccelerationStructurePropertiesKHR::default();
            owner.instance.get_physical_device_properties2(
                physical,
                &mut vk::PhysicalDeviceProperties2::default()
                    .push_next(&mut acceleration_properties),
            );
            let memory = owner
                .instance
                .get_physical_device_memory_properties(physical);
            let queue = device.get_device_queue(family, 0);
            let name = CStr::from_ptr(properties.device_name.as_ptr())
                .to_string_lossy()
                .into_owned();
            let timestamp_bits = owner
                .instance
                .get_physical_device_queue_family_properties(physical)[family as usize]
                .timestamp_valid_bits;
            let profile = if std::env::var_os("PRIME_PROFILE").is_some_and(|v| v != "0") {
                let bits = owner
                    .instance
                    .get_physical_device_queue_family_properties(physical)[family as usize]
                    .timestamp_valid_bits;
                let query_pool = if bits > 0 {
                    match device.create_query_pool(
                        &vk::QueryPoolCreateInfo::default()
                            .query_type(vk::QueryType::TIMESTAMP)
                            .query_count(2),
                        None,
                    ) {
                        Ok(pool) => pool,
                        Err(e) => {
                            device.destroy_command_pool(pool, None);
                            device.destroy_device(None);
                            return Err(error("Create profiling timestamp queries", e));
                        }
                    }
                } else {
                    vk::QueryPool::null()
                };
                eprintln!(
                    "[Prime PT profile] GPU={name}, timestamp_bits={bits}, timestamp_period_ns={}",
                    properties.limits.timestamp_period
                );
                for (index, memory_type) in memory.memory_types[..memory.memory_type_count as usize]
                    .iter()
                    .enumerate()
                {
                    eprintln!(
                        "[Prime PT profile] memory_type={index} heap={} flags={:?}",
                        memory_type.heap_index, memory_type.property_flags
                    );
                }
                Some(Profile {
                    query_pool,
                    timestamp_period: f64::from(properties.limits.timestamp_period),
                    timestamp_mask: if bits >= 64 {
                        u64::MAX
                    } else {
                        (1u64 << bits) - 1
                    },
                    trace: std::env::var_os("PRIME_PROFILE_TRACE").is_some_and(|v| v != "0"),
                    counters: ProfileCounters::default(),
                })
            } else {
                None
            };
            let opacity_micromap =
                micromap_support(&owner.instance, physical, &device, omm_enabled);
            eprintln!("[Prime PT] OMM device capability enabled={omm_enabled}");
            Ok(Arc::new(Self {
                _instance: owner,
                device,
                physical,
                opacity_micromap,
                acceleration: acceleration_loader,
                streamline_capable: false,
                queue,
                queue_family: family,
                pool,
                memory,
                scratch_alignment: u64::from(
                    acceleration_properties.min_acceleration_structure_scratch_offset_alignment,
                ),
                max_storage_buffer_range: u64::from(properties.limits.max_storage_buffer_range),
                max_image_dimension_2d: properties.limits.max_image_dimension2_d,
                max_compute_work_group_count: properties.limits.max_compute_work_group_count,
                max_memory_allocations: properties.limits.max_memory_allocation_count,
                max_blas_geometries: acceleration_properties.max_geometry_count,
                max_blas_primitives: acceleration_properties.max_primitive_count,
                timestamp_period: properties.limits.timestamp_period,
                timestamp_bits,
                live_allocations: AtomicU64::new(0),
                name,
                cpu_uploaded_bytes: AtomicU64::new(0),
                profile,
                host: None,
                uncertain_submission: AtomicBool::new(false),
            }))
        }
    }

    /// Borrow a host device with accelerationStructure, bufferDeviceAddress,
    /// rayQuery and timelineSemaphore already enabled.
    ///
    /// # Safety
    /// Handles must be live and belong to the supplied instance/device/queue
    /// family. The host owns queue synchronization and must flush the command
    /// encoder and keep all handles alive until this context has been destroyed.
    pub unsafe fn borrowed(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
    ) -> Result<Arc<Self>, String> {
        unsafe {
            Self::borrowed_with_capabilities(instance, physical, device, queue, family, timeline, 0)
        }
    }

    /// # Safety
    /// Same contract as borrowed; bit 0 certifies that EXT opacity micromap and its
    /// micromap feature were actually enabled when this device was created.
    #[allow(clippy::too_many_arguments)]
    pub unsafe fn borrowed_with_capabilities(
        instance: u64,
        physical: u64,
        device: u64,
        queue: u64,
        family: u32,
        timeline: u64,
        capabilities: u32,
    ) -> Result<Arc<Self>, String> {
        if capabilities & !3 != 0 {
            return Err("Unknown host Vulkan capabilities".into());
        }
        if [instance, physical, device, queue, timeline].contains(&0) {
            return Err("Borrowed Vulkan handles must be non-null".into());
        }
        unsafe {
            let entry = Entry::load().map_err(|e| format!("Load host Vulkan loader: {e}"))?;
            let instance = Instance::load(entry.static_fn(), vk::Instance::from_raw(instance));
            let owner = Arc::new(InstanceOwner {
                _entry: entry,
                instance,
                debug: None,
                owned: false,
            });
            let physical = vk::PhysicalDevice::from_raw(physical);
            let properties = owner.instance.get_physical_device_properties(physical);
            let families = owner
                .instance
                .get_physical_device_queue_family_properties(physical);
            let queue_properties = families
                .get(family as usize)
                .ok_or("Host Vulkan queue family is out of range")?;
            if !queue_properties
                .queue_flags
                .contains(vk::QueueFlags::COMPUTE)
            {
                return Err("Host Vulkan queue does not support compute".into());
            }
            let device = Device::load(owner.instance.fp_v1_0(), vk::Device::from_raw(device));
            let timeline = vk::Semaphore::from_raw(timeline);
            device
                .get_semaphore_counter_value(timeline)
                .map_err(|e| error("Read host timeline", e))?;
            let pool = device
                .create_command_pool(
                    &vk::CommandPoolCreateInfo::default()
                        .queue_family_index(family)
                        .flags(vk::CommandPoolCreateFlags::TRANSIENT),
                    None,
                )
                .map_err(|e| error("Create owned command pool on host Vulkan device", e))?;
            let acceleration =
                ash::khr::acceleration_structure::Device::new(&owner.instance, &device);
            let mut acceleration_properties =
                vk::PhysicalDeviceAccelerationStructurePropertiesKHR::default();
            owner.instance.get_physical_device_properties2(
                physical,
                &mut vk::PhysicalDeviceProperties2::default()
                    .push_next(&mut acceleration_properties),
            );
            let memory = owner
                .instance
                .get_physical_device_memory_properties(physical);
            let name = CStr::from_ptr(properties.device_name.as_ptr())
                .to_string_lossy()
                .into_owned();
            let profile = std::env::var_os("PRIME_PROFILE")
                .is_some_and(|v| v != "0")
                .then(|| Profile {
                    query_pool: vk::QueryPool::null(),
                    timestamp_period: f64::from(properties.limits.timestamp_period),
                    timestamp_mask: 0,
                    trace: false,
                    counters: ProfileCounters::default(),
                });
            let opacity_micromap =
                micromap_support(&owner.instance, physical, &device, capabilities & 1 != 0);
            eprintln!("[Prime PT] Host OMM enabled={}", opacity_micromap.is_some());
            Ok(Arc::new(Self {
                _instance: owner,
                device,
                physical,
                acceleration,
                opacity_micromap,
                queue: vk::Queue::from_raw(queue),
                streamline_capable: capabilities & 2 != 0,
                queue_family: family,
                pool,
                memory,
                scratch_alignment: u64::from(
                    acceleration_properties.min_acceleration_structure_scratch_offset_alignment,
                ),
                max_storage_buffer_range: u64::from(properties.limits.max_storage_buffer_range),
                max_image_dimension_2d: properties.limits.max_image_dimension2_d,
                max_compute_work_group_count: properties.limits.max_compute_work_group_count,
                max_memory_allocations: properties.limits.max_memory_allocation_count,
                max_blas_geometries: acceleration_properties.max_geometry_count,
                max_blas_primitives: acceleration_properties.max_primitive_count,
                timestamp_period: properties.limits.timestamp_period,
                timestamp_bits: queue_properties.timestamp_valid_bits,
                live_allocations: AtomicU64::new(0),
                name,
                cpu_uploaded_bytes: AtomicU64::new(0),
                profile,
                host: Some(Host {
                    timeline,
                    finished: AtomicBool::new(false),
                    state: Mutex::new(HostState {
                        command: vk::CommandBuffer::null(),
                        last_serial: 0,
                        retired: VecDeque::new(),
                    }),
                }),
                uncertain_submission: AtomicBool::new(false),
            }))
        }
    }

    pub fn render_extent(
        &self,
        width: u32,
        height: u32,
    ) -> Result<prime_scene::extent::RenderExtent, String> {
        let extent = prime_scene::extent::RenderExtent::new(width, height)?;
        if width.max(height) > self.max_image_dimension_2d
            || width.div_ceil(8) > self.max_compute_work_group_count[0]
            || height.div_ceil(8) > self.max_compute_work_group_count[1]
            || extent.pixels() * 16 > self.max_storage_buffer_range
        {
            return Err(format!(
                "Render extent {width}x{height} exceeds device image/dispatch/accumulation limits"
            ));
        }
        Ok(extent)
    }

    pub fn is_borrowed(&self) -> bool {
        self.host.is_some()
    }
    pub fn instance_handle(&self) -> u64 {
        self._instance.instance.handle().as_raw()
    }

    #[cfg(all(test, feature = "shader-tests"))]
    pub fn benchmark_device_details(&self) -> String {
        let properties = unsafe {
            self._instance
                .instance
                .get_physical_device_properties(self.physical)
        };
        format!(
            "device={} vendor_id={} device_id={} driver_version={} vulkan_api={} timestamp_period_ns={} timestamp_bits={}",
            self.name,
            properties.vendor_id,
            properties.device_id,
            properties.driver_version,
            properties.api_version,
            self.timestamp_period,
            self.timestamp_bits,
        )
    }

    pub fn supports_linear_sampling(&self, format: vk::Format) -> bool {
        unsafe {
            self._instance
                .instance
                .get_physical_device_format_properties(self.physical, format)
        }
        .optimal_tiling_features
        .contains(vk::FormatFeatureFlags::SAMPLED_IMAGE_FILTER_LINEAR)
    }

    pub fn supports_storage_sampling(&self, format: vk::Format) -> bool {
        unsafe {
            self._instance
                .instance
                .get_physical_device_format_properties(self.physical, format)
        }
        .optimal_tiling_features
        .contains(
            vk::FormatFeatureFlags::STORAGE_IMAGE
                | vk::FormatFeatureFlags::SAMPLED_IMAGE
                | vk::FormatFeatureFlags::TRANSFER_SRC
                | vk::FormatFeatureFlags::TRANSFER_DST,
        )
    }

    /// Begin recording into the host's transient command buffer, whose completion
    /// is identified by a future value on the host submission timeline.
    pub fn begin_host_record(
        &self,
        command: vk::CommandBuffer,
        serial: u64,
    ) -> Result<u64, String> {
        if !self.can_destroy() {
            return Err("Host Vulkan context is quarantined".into());
        }
        let host = self
            .host
            .as_ref()
            .ok_or("This renderer owns its Vulkan device")?;
        if host.finished.load(Ordering::Relaxed) {
            return Err("Host Vulkan recording has been permanently closed".into());
        }
        let completed = self.completed_serial()?;
        if command == vk::CommandBuffer::null() || serial == 0 || serial <= completed {
            return Err("Host command buffer requires a future nonzero completion serial".into());
        }
        {
            let mut state = host.state.lock().unwrap_or_else(|p| p.into_inner());
            if state.command != vk::CommandBuffer::null() {
                return Err("Host Vulkan recording is already active".into());
            }
            if serial < state.last_serial {
                return Err("Host Vulkan completion serial regressed".into());
            }
            state.command = command;
            state.last_serial = serial;
        }
        // Reuse this completion proof for slot selection; another poll here is
        // unnecessary. A later completion can only make this value conservative.
        Ok(completed)
    }

    pub fn end_host_record(&self) {
        if let Some(host) = &self.host {
            let mut state = host.state.lock().unwrap_or_else(|p| p.into_inner());
            state.command = vk::CommandBuffer::null();
        }
    }

    pub fn completed_serial(&self) -> Result<u64, String> {
        let Some(host) = &self.host else {
            return Ok(u64::MAX);
        };
        if !self.can_destroy() {
            return Err(
                "Host Vulkan resources are quarantined after an earlier completion failure".into(),
            );
        }
        if host.finished.load(Ordering::Relaxed) {
            return Ok(host
                .state
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .last_serial);
        }
        let completed =
            unsafe { self.device.get_semaphore_counter_value(host.timeline) }.map_err(|e| {
                self.uncertain_submission.store(true, Ordering::Relaxed);
                error("Query host Vulkan completion", e)
            })?;
        let mut ready = Vec::new();
        {
            let mut state = host.state.lock().unwrap_or_else(|p| p.into_inner());
            while state
                .retired
                .front()
                .is_some_and(|(serial, _)| *serial <= completed)
            {
                ready.push(state.retired.pop_front().unwrap().1);
            }
        }
        for resource in ready {
            self.destroy_resource(resource);
        }
        Ok(completed)
    }

    pub fn wait_host_serial(&self, serial: u64) -> Result<u64, String> {
        let Some(host) = &self.host else {
            return Ok(u64::MAX);
        };
        if !self.can_destroy() {
            return Err(
                "Host Vulkan resources are quarantined; no further GPU wait is attempted".into(),
            );
        }
        if host.finished.load(Ordering::Relaxed) {
            let last = host
                .state
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .last_serial;
            return if serial <= last {
                Ok(last)
            } else {
                Err("Cannot wait for a new submission after host recording is closed".into())
            };
        }
        let semaphores = [host.timeline];
        let values = [serial];
        let result = unsafe {
            self.device.wait_semaphores(
                &vk::SemaphoreWaitInfo::default()
                    .semaphores(&semaphores)
                    .values(&values),
                5_000_000_000,
            )
        };
        if let Err(e) = result {
            self.uncertain_submission.store(true, Ordering::Relaxed);
            return Err(error("Host Vulkan work did not retire within 5 seconds", e));
        }
        self.completed_serial()
    }

    pub fn wait_host_idle(&self) -> Result<(), String> {
        let Some(host) = &self.host else {
            return Ok(());
        };
        if !self.can_destroy() {
            return Err(
                "Host Vulkan resources are quarantined; no further GPU wait is attempted".into(),
            );
        }
        let (active, serial) = {
            let state = host.state.lock().unwrap_or_else(|p| p.into_inner());
            (
                state.command != vk::CommandBuffer::null(),
                state.last_serial,
            )
        };
        if active {
            self.uncertain_submission.store(true, Ordering::Relaxed);
            return Err("Cannot retire host Vulkan resources while recording is active".into());
        }
        self.wait_host_serial(serial).map(|_| ())
    }

    /// Permanently close recording after proving every recorded command complete.
    /// Later resource drops and context teardown are local; they cannot introduce
    /// a new timeline failure after the caller starts destroying host objects.
    pub fn finish_host(&self) -> Result<(), String> {
        self.wait_host_idle()?;
        if let Some(host) = &self.host {
            host.finished.store(true, Ordering::Relaxed);
        }
        Ok(())
    }

    pub(super) fn retire_image(
        &self,
        image: vk::Image,
        view: vk::ImageView,
        memory: vk::DeviceMemory,
    ) {
        self.retire(RetiredResource::Image(image, view, memory));
    }

    pub(crate) fn retirement_serial(&self) -> u64 {
        self.host.as_ref().map_or(0, |host| {
            host.state
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .last_serial
        })
    }

    pub fn retire_micromap(&self, handle: vk::MicromapEXT) {
        if self.can_destroy() {
            self.retire(RetiredResource::Micromap(handle));
        }
    }
    fn retire(&self, resource: RetiredResource) {
        if let Some(host) = &self.host
            && !host.finished.load(Ordering::Relaxed)
        {
            let mut state = host.state.lock().unwrap_or_else(|p| p.into_inner());
            let serial = state.last_serial;
            state.retired.push_back((serial, resource));
        } else {
            self.destroy_resource(resource);
        }
    }

    fn destroy_resource(&self, resource: RetiredResource) {
        unsafe {
            match resource {
                RetiredResource::Acceleration(handle) => self
                    .acceleration
                    .destroy_acceleration_structure(handle, None),
                RetiredResource::Micromap(handle) => {
                    (self
                        .opacity_micromap
                        .as_ref()
                        .expect("enabled OMM owner")
                        .loader
                        .fp()
                        .destroy_micromap_ext)(
                        self.device.handle(), handle, std::ptr::null()
                    )
                }
                RetiredResource::Buffer(buffer, memory) => {
                    self.device.destroy_buffer(buffer, None);
                    self.device.free_memory(memory, None);
                    self.live_allocations.fetch_sub(1, Ordering::Relaxed);
                }
                RetiredResource::Image(image, view, memory) => {
                    self.device.destroy_image_view(view, None);
                    self.device.destroy_image(image, None);
                    self.device.free_memory(memory, None);
                }
            }
        }
    }

    // Owned mode completes each submission. Host mode appends to the active
    // encoder and retires resources against the host timeline instead.
    pub fn submit_named(
        &self,
        label: &str,
        record: impl FnOnce(vk::CommandBuffer),
    ) -> Result<(), String> {
        unsafe {
            if !self.can_destroy() {
                return Err("Vulkan device has an unconfirmed failed submission".into());
            }
            if let Some(host) = &self.host {
                let command = host.state.lock().unwrap_or_else(|p| p.into_inner()).command;
                if command == vk::CommandBuffer::null() {
                    return Err(
                        "Borrowed Vulkan work requires an active host command buffer".into(),
                    );
                }
                let started = self.profile.as_ref().map(|_| Instant::now());
                record(command);
                if let Some(profile) = &self.profile {
                    profile
                        .counters
                        .record_ns
                        .fetch_add(elapsed_ns(started), Ordering::Relaxed);
                }
                return Ok(());
            }
            let started = self.profile.as_ref().map(|_| Instant::now());
            let command = self
                .device
                .allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(self.pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(1),
                )
                .map_err(|e| error("Allocate command buffer", e))?[0];
            let result = (|| {
                self.device
                    .begin_command_buffer(
                        command,
                        &vk::CommandBufferBeginInfo::default()
                            .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                    )
                    .map_err(|e| error("Begin command buffer", e))?;
                if let Some(profile) = &self.profile
                    && profile.query_pool != vk::QueryPool::null()
                {
                    self.device
                        .cmd_reset_query_pool(command, profile.query_pool, 0, 2);
                    self.device.cmd_write_timestamp(
                        command,
                        vk::PipelineStageFlags::TOP_OF_PIPE,
                        profile.query_pool,
                        0,
                    );
                }
                record(command);
                if let Some(profile) = &self.profile
                    && profile.query_pool != vk::QueryPool::null()
                {
                    self.device.cmd_write_timestamp(
                        command,
                        vk::PipelineStageFlags::BOTTOM_OF_PIPE,
                        profile.query_pool,
                        1,
                    );
                }
                self.device
                    .end_command_buffer(command)
                    .map_err(|e| error("End command buffer", e))?;
                let fence = self
                    .device
                    .create_fence(&vk::FenceCreateInfo::default(), None)
                    .map_err(|e| error("Create submission fence", e))?;
                let commands = [command];
                let record_ns = elapsed_ns(started);
                let submitted_at = self.profile.as_ref().map(|_| Instant::now());
                let submitted = self.device.queue_submit(
                    self.queue,
                    &[vk::SubmitInfo::default().command_buffers(&commands)],
                    fence,
                );
                let submit_ns = elapsed_ns(submitted_at);
                let waiting_at = self.profile.as_ref().map(|_| Instant::now());
                let completed = submitted.and_then(|_| {
                    let waited = self.device.wait_for_fences(&[fence], true, u64::MAX);
                    if waited.is_err()
                        && waited != Err(vk::Result::ERROR_DEVICE_LOST)
                        && let Err(e) = self.device.device_wait_idle()
                        && e != vk::Result::ERROR_DEVICE_LOST
                    {
                        self.uncertain_submission.store(true, Ordering::Relaxed);
                    }
                    waited
                });
                let wait_ns = elapsed_ns(waiting_at);
                if self.can_destroy() {
                    self.device.destroy_fence(fence, None);
                }
                if completed.is_ok()
                    && let Some(profile) = &self.profile
                {
                    let mut timestamps = [0u64; 2];
                    let gpu_ns = if profile.query_pool != vk::QueryPool::null() {
                        self.device
                            .get_query_pool_results(
                                profile.query_pool,
                                0,
                                &mut timestamps,
                                vk::QueryResultFlags::TYPE_64,
                            )
                            .map_err(|e| error("Read completed GPU timestamps", e))?;
                        ((timestamps[1].wrapping_sub(timestamps[0]) & profile.timestamp_mask)
                            as f64
                            * profile.timestamp_period) as u64
                    } else {
                        0
                    };
                    profile.counters.submissions.fetch_add(1, Ordering::Relaxed);
                    profile
                        .counters
                        .record_ns
                        .fetch_add(record_ns, Ordering::Relaxed);
                    profile
                        .counters
                        .submit_ns
                        .fetch_add(submit_ns, Ordering::Relaxed);
                    profile
                        .counters
                        .wait_ns
                        .fetch_add(wait_ns, Ordering::Relaxed);
                    profile.counters.gpu_ns.fetch_add(gpu_ns, Ordering::Relaxed);
                    if profile.trace {
                        eprintln!(
                            "[Prime PT profile] submit={label} record_ms={:.3} submit_ms={:.3} wait_ms={:.3} gpu_ms={:.3}",
                            record_ns as f64 / 1e6,
                            submit_ns as f64 / 1e6,
                            wait_ns as f64 / 1e6,
                            gpu_ns as f64 / 1e6
                        );
                    }
                }
                completed.map_err(|e| error("Submit/wait Vulkan work", e))
            })();
            if self.can_destroy() {
                self.device.free_command_buffers(self.pool, &[command]);
            }
            result
        }
    }
    pub fn can_destroy(&self) -> bool {
        !self.uncertain_submission.load(Ordering::Relaxed)
    }

    pub fn cpu_upload_bytes(&self) -> u64 {
        self.cpu_uploaded_bytes.load(Ordering::Relaxed)
    }

    pub fn profile_snapshot(&self) -> Option<GpuProfile> {
        self.profile.as_ref().map(|p| {
            let c = &p.counters;
            GpuProfile {
                submissions: c.submissions.load(Ordering::Relaxed),
                record_ns: c.record_ns.load(Ordering::Relaxed),
                submit_ns: c.submit_ns.load(Ordering::Relaxed),
                wait_ns: c.wait_ns.load(Ordering::Relaxed),
                gpu_ns: c.gpu_ns.load(Ordering::Relaxed),
                allocations: c.allocations.load(Ordering::Relaxed),
                allocation_ns: c.allocation_ns.load(Ordering::Relaxed),
                allocated_bytes: c.allocated_bytes.load(Ordering::Relaxed),
                upload_ns: c.upload_ns.load(Ordering::Relaxed),
                uploaded_bytes: c.uploaded_bytes.load(Ordering::Relaxed),
                readback_ns: c.readback_ns.load(Ordering::Relaxed),
                readback_bytes: c.readback_bytes.load(Ordering::Relaxed),
            }
        })
    }
}

pub(super) struct Buffer {
    pub context: Arc<Context>,
    pub buffer: vk::Buffer,
    pub memory: vk::DeviceMemory,
    pub size: u64,
    // This owner never rebinds its allocation. Zero for buffers without BDA usage.
    device_address: u64,
    mapped: Option<std::ptr::NonNull<u8>>,
}
impl Drop for Buffer {
    fn drop(&mut self) {
        if self.mapped.is_some() {
            unsafe {
                self.context.device.unmap_memory(self.memory);
            }
        }
        if self.context.can_destroy() {
            self.context
                .retire(RetiredResource::Buffer(self.buffer, self.memory));
        }
    }
}
impl Buffer {
    /// PhysicalStorageBuffer accesses use a device address, not a storage-buffer descriptor.
    /// maxStorageBufferRange does not constrain this allocation; the allocation limit still does.
    pub fn new_address(context: &Arc<Context>, size: u64) -> Result<Self, String> {
        if size == 0 || !size.is_multiple_of(16) {
            return Err("Device-address scratch size must be a positive multiple of 16".into());
        }
        let mut limits = vk::PhysicalDeviceMaintenance3Properties::default();
        let mut properties = vk::PhysicalDeviceProperties2::default().push_next(&mut limits);
        unsafe {
            context
                ._instance
                .instance
                .get_physical_device_properties2(context.physical, &mut properties);
        }
        if size > limits.max_memory_allocation_size {
            return Err(format!(
                "Device-address scratch {size} exceeds maxMemoryAllocationSize {}",
                limits.max_memory_allocation_size
            ));
        }
        let result = Self::new(
            context,
            size,
            vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            false,
        )?;
        if result.address() == 0
            || !result.address().is_multiple_of(16)
            || result.address().checked_add(size).is_none()
        {
            return Err("Device-address scratch is not a valid aligned address range".into());
        }
        Ok(result)
    }

    pub fn new(
        context: &Arc<Context>,
        size: u64,
        usage: vk::BufferUsageFlags,
        host: bool,
    ) -> Result<Self, String> {
        Self::allocate(context, size, usage, host, vk::MemoryPropertyFlags::empty())
    }

    /// Readback is CPU-read-heavy: prefer a coherent, cached system-memory type.
    /// The first merely HOST_VISIBLE type can be uncached on discrete GPUs.
    pub fn new_readback(context: &Arc<Context>, size: u64) -> Result<Self, String> {
        Self::allocate(
            context,
            size,
            vk::BufferUsageFlags::TRANSFER_DST,
            true,
            vk::MemoryPropertyFlags::HOST_CACHED,
        )
    }

    fn allocate(
        context: &Arc<Context>,
        size: u64,
        usage: vk::BufferUsageFlags,
        host: bool,
        preferred: vk::MemoryPropertyFlags,
    ) -> Result<Self, String> {
        unsafe {
            let started = context.profile.as_ref().map(|_| Instant::now());
            let size = size.max(16);
            if usage.contains(vk::BufferUsageFlags::STORAGE_BUFFER)
                && size > context.max_storage_buffer_range
            {
                return Err(format!(
                    "Storage buffer {size} exceeds device limit {}",
                    context.max_storage_buffer_range
                ));
            }
            let buffer = context
                .device
                .create_buffer(
                    &vk::BufferCreateInfo::default()
                        .size(size)
                        .usage(usage)
                        .sharing_mode(vk::SharingMode::EXCLUSIVE),
                    None,
                )
                .map_err(|e| error("Create Vulkan buffer", e))?;
            let requirements = context.device.get_buffer_memory_requirements(buffer);
            let required = if host {
                vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT
            } else {
                vk::MemoryPropertyFlags::DEVICE_LOCAL
            };
            let compatible = |i: &u32| {
                (requirements.memory_type_bits & (1 << i)) != 0
                    && context.memory.memory_types[*i as usize]
                        .property_flags
                        .contains(required)
            };
            let memory_index = (0..context.memory.memory_type_count)
                .find(|i| {
                    compatible(i)
                        && context.memory.memory_types[*i as usize]
                            .property_flags
                            .contains(preferred)
                })
                .or_else(|| (0..context.memory.memory_type_count).find(compatible));
            let Some(memory_index) = memory_index else {
                context.device.destroy_buffer(buffer, None);
                return Err(format!("No Vulkan memory type for {required:?}"));
            };
            let mut flags = vk::MemoryAllocateFlagsInfo::default()
                .flags(vk::MemoryAllocateFlags::DEVICE_ADDRESS);
            let mut allocate = vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_index);
            if usage.contains(vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS) {
                allocate = allocate.push_next(&mut flags);
            }
            // Retired host resources still occupy allocations until their serial
            // completes. Count them, not just the current cluster batch.
            let previous = context.live_allocations.fetch_add(1, Ordering::Relaxed);
            if previous >= u64::from(context.max_memory_allocations) {
                context.live_allocations.fetch_sub(1, Ordering::Relaxed);
                context.device.destroy_buffer(buffer, None);
                return Err("Native Vulkan allocation limit reached; host submissions must retire before more scene uploads".into());
            }
            let memory = match context.device.allocate_memory(&allocate, None) {
                Ok(memory) => memory,
                Err(e) => {
                    context.live_allocations.fetch_sub(1, Ordering::Relaxed);
                    context.device.destroy_buffer(buffer, None);
                    return Err(error("Allocate Vulkan memory", e));
                }
            };
            let mut result = Self {
                context: context.clone(),
                buffer,
                memory,
                size,
                device_address: 0,
                mapped: None,
            };
            context
                .device
                .bind_buffer_memory(buffer, memory, 0)
                .map_err(|e| error("Bind Vulkan buffer memory", e))?;
            if host {
                result.mapped = std::ptr::NonNull::new(
                    context
                        .device
                        .map_memory(memory, 0, size, vk::MemoryMapFlags::empty())
                        .map_err(|e| error("Map persistent upload", e))?
                        .cast(),
                );
            }
            if usage.contains(vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS) {
                result.device_address = context.device.get_buffer_device_address(
                    &vk::BufferDeviceAddressInfo::default().buffer(buffer),
                );
            }
            if let Some(profile) = &context.profile {
                profile.counters.allocations.fetch_add(1, Ordering::Relaxed);
                profile
                    .counters
                    .allocated_bytes
                    .fetch_add(size, Ordering::Relaxed);
                profile
                    .counters
                    .allocation_ns
                    .fetch_add(elapsed_ns(started), Ordering::Relaxed);
                if profile.trace {
                    eprintln!(
                        "[Prime PT profile] allocate bytes={size} usage={usage:?} memory_type={memory_index} flags={:?}",
                        context.memory.memory_types[memory_index as usize].property_flags
                    );
                }
            }
            Ok(result)
        }
    }
    pub fn upload(
        context: &Arc<Context>,
        bytes: &[u8],
        usage: vk::BufferUsageFlags,
    ) -> Result<Self, String> {
        let buffer = Self::new(context, bytes.len() as u64, usage, true)?;
        buffer.write(bytes)?;
        Ok(buffer)
    }

    /// Stage immutable shader/geometry input into local GPU memory. Retaining a
    /// mapped upload allocation makes every subsequent shader read cross PCIe.
    pub fn upload_device(
        context: &Arc<Context>,
        bytes: &[u8],
        usage: vk::BufferUsageFlags,
    ) -> Result<Self, String> {
        let destination = Self::new(
            context,
            bytes.len() as u64,
            usage | vk::BufferUsageFlags::TRANSFER_DST,
            false,
        )?;
        if bytes.is_empty() {
            return Ok(destination);
        }
        let staging = Self::upload(context, bytes, vk::BufferUsageFlags::TRANSFER_SRC)?;
        context.submit_named("upload", |command| unsafe {
            context.device.cmd_copy_buffer(
                command,
                staging.buffer,
                destination.buffer,
                &[vk::BufferCopy::default().size(bytes.len() as u64)],
            );
            let barrier = [vk::MemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::MEMORY_READ)];
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::ALL_COMMANDS,
                vk::DependencyFlags::empty(),
                &barrier,
                &[],
                &[],
            );
        })?;
        Ok(destination)
    }
    pub fn write(&self, bytes: &[u8]) -> Result<(), String> {
        self.write_at(0, bytes)
    }

    // The owner proves this upload slot is complete before mutation; mapping lifetime is
    // independent of GPU use.
    pub fn write_at(&self, offset: u64, bytes: &[u8]) -> Result<(), String> {
        let started = self.context.profile.as_ref().map(|_| Instant::now());
        if offset
            .checked_add(bytes.len() as u64)
            .is_none_or(|end| end > self.size)
        {
            return Err("Upload exceeds Vulkan allocation".into());
        }
        let mapped = self.mapped.ok_or("Buffer is not host visible")?;
        unsafe {
            std::ptr::copy_nonoverlapping(
                bytes.as_ptr(),
                mapped.as_ptr().add(offset as usize),
                bytes.len(),
            );
        }
        self.context
            .cpu_uploaded_bytes
            .fetch_add(bytes.len() as u64, Ordering::Relaxed);
        if let Some(profile) = &self.context.profile {
            profile
                .counters
                .upload_ns
                .fetch_add(elapsed_ns(started), Ordering::Relaxed);
            profile
                .counters
                .uploaded_bytes
                .fetch_add(bytes.len() as u64, Ordering::Relaxed);
        }
        Ok(())
    }

    /// Initialize an exclusively owned mapped range without an intermediate host copy.
    /// The callback must initialize every byte on success; on failure no submission may read it.
    ///
    /// # Safety
    /// The caller must own this range exclusively for the whole callback, including any joined
    /// workers, and prove it has no unfinished GPU consumer. The slice cannot escape the call.
    pub unsafe fn write_with(
        &self,
        offset: u64,
        size: usize,
        write: impl FnOnce(&mut [std::mem::MaybeUninit<u8>]) -> Result<(), String>,
    ) -> Result<(), String> {
        let started = self.context.profile.as_ref().map(|_| Instant::now());
        if offset
            .checked_add(size as u64)
            .is_none_or(|end| end > self.size)
        {
            return Err("Upload exceeds Vulkan allocation".into());
        }
        let mapped = self.mapped.ok_or("Buffer is not host visible")?;
        // SAFETY: The checked range is mapped for Buffer's lifetime. The caller proves exclusive
        // CPU ownership and no GPU reader. MaybeUninit does not assume newly allocated bytes exist.
        let bytes = unsafe {
            std::slice::from_raw_parts_mut(mapped.as_ptr().add(offset as usize).cast(), size)
        };
        write(bytes)?;
        self.context
            .cpu_uploaded_bytes
            .fetch_add(size as u64, Ordering::Relaxed);
        if let Some(profile) = &self.context.profile {
            profile
                .counters
                .upload_ns
                .fetch_add(elapsed_ns(started), Ordering::Relaxed);
            profile
                .counters
                .uploaded_bytes
                .fetch_add(size as u64, Ordering::Relaxed);
        }
        Ok(())
    }
    pub fn read(&self, count: usize) -> Result<Vec<u8>, String> {
        unsafe {
            let started = self.context.profile.as_ref().map(|_| Instant::now());
            if count as u64 > self.size {
                return Err("Readback exceeds Vulkan allocation".into());
            }
            let mapped = self.mapped.ok_or("Buffer is not host visible")?;
            let bytes = std::slice::from_raw_parts(mapped.as_ptr(), count).to_vec();
            if let Some(profile) = &self.context.profile {
                profile
                    .counters
                    .readback_ns
                    .fetch_add(elapsed_ns(started), Ordering::Relaxed);
                profile
                    .counters
                    .readback_bytes
                    .fetch_add(count as u64, Ordering::Relaxed);
            }
            Ok(bytes)
        }
    }
    pub fn address(&self) -> u64 {
        self.device_address
    }
}

pub(super) struct Acceleration {
    pub context: Arc<Context>,
    pub handle: vk::AccelerationStructureKHR,
    storage: Option<Lease>,
    // AS addresses are queried from the AS, never inferred from its storage buffer.
    device_address: u64,
}

/// Owns scratch and the destination AS until the caller completes its batched
/// submission. Input vertex/instance buffers must also outlive that submission.
pub(super) struct PreparedAcceleration<'a> {
    acceleration: Acceleration,
    scratch: Option<Lease>,
    scratch_size: u64,
    geometries: Vec<vk::AccelerationStructureGeometryKHR<'a>>,
    ranges: Vec<vk::AccelerationStructureBuildRangeInfoKHR>,
    kind: vk::AccelerationStructureTypeKHR,
    flags: vk::BuildAccelerationStructureFlagsKHR,
}

impl PreparedAcceleration<'_> {
    /// Record an independent build. The batch owner inserts one read barrier
    /// after all independent BLAS builds, before a dependent TLAS or tracing.
    pub fn record_unbarriered(&self, command: vk::CommandBuffer) {
        self.record_geometries(command, &self.geometries, &self.ranges);
    }

    /// Rebuild into retained capacity. The owner orders prior trace/build users
    /// before this write and supplies the same geometry format used at allocation.
    pub fn record_geometry(
        &self,
        command: vk::CommandBuffer,
        geometry: vk::AccelerationStructureGeometryKHR<'_>,
        count: u32,
    ) {
        assert!(self.ranges.len() == 1 && count <= self.ranges[0].primitive_count);
        let geometries = [geometry];
        let ranges = [vk::AccelerationStructureBuildRangeInfoKHR::default().primitive_count(count)];
        self.record_geometries(command, &geometries, &ranges);
    }

    fn record_geometries(
        &self,
        command: vk::CommandBuffer,
        geometries: &[vk::AccelerationStructureGeometryKHR<'_>],
        ranges: &[vk::AccelerationStructureBuildRangeInfoKHR],
    ) {
        let context = &self.acceleration.context;
        let address = self
            .scratch
            .as_ref()
            .expect("prepared build scratch")
            .address();
        let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
            .ty(self.kind)
            .flags(self.flags)
            .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
            .geometries(geometries)
            .dst_acceleration_structure(self.acceleration.handle)
            .scratch_data(vk::DeviceOrHostAddressKHR {
                device_address: address,
            });
        unsafe {
            context
                .acceleration
                .cmd_build_acceleration_structures(command, &[info], &[ranges]);
        }
    }

    pub fn acceleration(&self) -> &Acceleration {
        &self.acceleration
    }

    /// The caller has recorded its build. Scratch is either synchronously complete
    /// or retained by Context's host timeline retirement before physical destruction.
    pub fn ensure_scratch(&mut self, arena: &mut Arena) -> Result<(), String> {
        if self.scratch.is_none() {
            self.scratch = Some(arena.allocate(
                &self.acceleration.context,
                self.scratch_size,
                self.acceleration.context.scratch_alignment,
            )?);
        }
        Ok(())
    }
    pub fn release_scratch(&mut self, arena: &mut Arena) {
        if let Some(scratch) = self.scratch.take() {
            arena.retire(scratch);
        }
    }
    pub fn retire(mut self, arena: &mut Arena) {
        self.release_scratch(arena);
        self.acceleration.retire(arena);
    }
    pub fn finish(mut self, arena: &mut Arena) -> Acceleration {
        self.release_scratch(arena);
        self.acceleration
    }
}

impl Drop for Acceleration {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            self.context
                .retire(RetiredResource::Acceleration(self.handle));
        }
    }
}
impl Acceleration {
    #[cfg(test)]
    pub fn storage_bytes(&self) -> u64 {
        self.storage.as_ref().map_or(0, |s| s.size)
    }
    pub fn retire(mut self, arena: &mut Arena) {
        if let Some(storage) = self.storage.take() {
            arena.retire(storage);
        }
    }
    pub fn prepare_geometries<'a>(
        context: &Arc<Context>,
        arena: &mut Arena,
        geometries: Vec<vk::AccelerationStructureGeometryKHR<'a>>,
        counts: &[u32],
        allow_disable_micromaps: bool,
    ) -> Result<PreparedAcceleration<'a>, String> {
        let mut flags = vk::BuildAccelerationStructureFlagsKHR::PREFER_FAST_TRACE;
        if allow_disable_micromaps {
            flags |= vk::BuildAccelerationStructureFlagsKHR::ALLOW_DISABLE_OPACITY_MICROMAPS_EXT;
        }
        Self::prepare_ranges(
            context,
            arena,
            geometries,
            counts,
            vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL,
            flags,
        )
    }

    pub fn prepare_with_flags<'a>(
        context: &Arc<Context>,
        arena: &mut Arena,
        geometry: vk::AccelerationStructureGeometryKHR<'a>,
        count: u32,
        kind: vk::AccelerationStructureTypeKHR,
        flags: vk::BuildAccelerationStructureFlagsKHR,
    ) -> Result<PreparedAcceleration<'a>, String> {
        Self::prepare_ranges(context, arena, vec![geometry], &[count], kind, flags)
    }

    fn prepare_ranges<'a>(
        context: &Arc<Context>,
        arena: &mut Arena,
        geometries: Vec<vk::AccelerationStructureGeometryKHR<'a>>,
        counts: &[u32],
        kind: vk::AccelerationStructureTypeKHR,
        flags: vk::BuildAccelerationStructureFlagsKHR,
    ) -> Result<PreparedAcceleration<'a>, String> {
        assert_eq!(geometries.len(), counts.len());
        if kind == vk::AccelerationStructureTypeKHR::BOTTOM_LEVEL
            && (geometries.len() as u64 > context.max_blas_geometries
                || counts.iter().map(|&n| u64::from(n)).sum::<u64>() > context.max_blas_primitives)
        {
            return Err("Static/dynamic BLAS exceeds device geometry or primitive capacity".into());
        }
        unsafe {
            let info = vk::AccelerationStructureBuildGeometryInfoKHR::default()
                .ty(kind)
                .flags(flags)
                .mode(vk::BuildAccelerationStructureModeKHR::BUILD)
                .geometries(&geometries);
            let mut sizes = vk::AccelerationStructureBuildSizesInfoKHR::default();
            context.acceleration.get_acceleration_structure_build_sizes(
                vk::AccelerationStructureBuildTypeKHR::DEVICE,
                &info,
                counts,
                &mut sizes,
            );
            let storage = arena.allocate(context, sizes.acceleration_structure_size, 256)?;
            let handle = context
                .acceleration
                .create_acceleration_structure(
                    &vk::AccelerationStructureCreateInfoKHR::default()
                        .buffer(storage.buffer.buffer)
                        .offset(storage.offset)
                        .size(sizes.acceleration_structure_size)
                        .ty(kind),
                    None,
                )
                .map_err(|e| error("Create acceleration structure", e))?;
            let result = Self {
                context: context.clone(),
                handle,
                storage: Some(storage),
                device_address: context
                    .acceleration
                    .get_acceleration_structure_device_address(
                        &vk::AccelerationStructureDeviceAddressInfoKHR::default()
                            .acceleration_structure(handle),
                    ),
            };
            let scratch =
                arena.allocate(context, sizes.build_scratch_size, context.scratch_alignment)?;
            Ok(PreparedAcceleration {
                acceleration: result,
                scratch: Some(scratch),
                scratch_size: sizes.build_scratch_size,
                geometries,
                ranges: counts
                    .iter()
                    .map(|&count| {
                        vk::AccelerationStructureBuildRangeInfoKHR::default().primitive_count(count)
                    })
                    .collect(),
                kind,
                flags,
            })
        }
    }

    pub fn read_barrier(context: &Context, command: vk::CommandBuffer) {
        let barrier = [vk::MemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::ACCELERATION_STRUCTURE_WRITE_KHR)
            .dst_access_mask(vk::AccessFlags::ACCELERATION_STRUCTURE_READ_KHR)];
        unsafe {
            context.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::ACCELERATION_STRUCTURE_BUILD_KHR,
                vk::PipelineStageFlags::ACCELERATION_STRUCTURE_BUILD_KHR
                    | vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &barrier,
                &[],
                &[],
            );
        }
    }
    pub fn address(&self) -> u64 {
        self.device_address
    }
}

#[cfg(test)]
mod host_tests {
    use super::*;
    use std::{
        cell::RefCell,
        ffi::{c_char, c_void},
    };

    #[derive(Default)]
    struct FakeCalls {
        waits: usize,
        queries: usize,
        completed: u64,
        reject_queries: bool,
        destroyed: Vec<&'static str>,
    }
    thread_local! {
        static FAKE_CALLS: RefCell<FakeCalls> = RefCell::new(FakeCalls::default());
    }
    unsafe extern "system" fn fake_wait(
        _: vk::Device,
        info: *const vk::SemaphoreWaitInfo<'_>,
        _: u64,
    ) -> vk::Result {
        FAKE_CALLS.with(|calls| {
            let mut calls = calls.borrow_mut();
            calls.waits += 1;
            unsafe {
                calls.completed = calls.completed.max(*(*info).p_values);
            }
        });
        vk::Result::SUCCESS
    }
    unsafe extern "system" fn fake_counter(
        _: vk::Device,
        _: vk::Semaphore,
        output: *mut u64,
    ) -> vk::Result {
        FAKE_CALLS.with(|calls| {
            let mut calls = calls.borrow_mut();
            calls.queries += 1;
            if calls.reject_queries {
                vk::Result::ERROR_OUT_OF_HOST_MEMORY
            } else {
                unsafe {
                    *output = calls.completed;
                }
                vk::Result::SUCCESS
            }
        })
    }
    unsafe extern "system" fn fake_destroy_pool(
        _: vk::Device,
        _: vk::CommandPool,
        _: *const vk::AllocationCallbacks<'_>,
    ) {
        FAKE_CALLS.with(|calls| calls.borrow_mut().destroyed.push("pool"));
    }
    unsafe extern "system" fn fake_destroy_buffer(
        _: vk::Device,
        _: vk::Buffer,
        _: *const vk::AllocationCallbacks<'_>,
    ) {
        FAKE_CALLS.with(|calls| calls.borrow_mut().destroyed.push("buffer"));
    }
    unsafe extern "system" fn fake_free_memory(
        _: vk::Device,
        _: vk::DeviceMemory,
        _: *const vk::AllocationCallbacks<'_>,
    ) {
        FAKE_CALLS.with(|calls| calls.borrow_mut().destroyed.push("memory"));
    }
    unsafe extern "system" fn fake_instance_proc(
        _: vk::Instance,
        _: *const c_char,
    ) -> vk::PFN_vkVoidFunction {
        None
    }
    unsafe extern "system" fn fake_destroy_micromap(
        _: vk::Device,
        _: vk::MicromapEXT,
        _: *const vk::AllocationCallbacks<'_>,
    ) {
        FAKE_CALLS.with(|calls| calls.borrow_mut().destroyed.push("micromap"));
    }
    unsafe extern "system" fn fake_device_proc(
        _: vk::Device,
        name: *const c_char,
    ) -> vk::PFN_vkVoidFunction {
        if unsafe { CStr::from_ptr(name) } == c"vkDestroyMicromapEXT" {
            Some(unsafe {
                std::mem::transmute::<
                    unsafe extern "system" fn(
                        vk::Device,
                        vk::MicromapEXT,
                        *const vk::AllocationCallbacks<'_>,
                    ),
                    unsafe extern "system" fn(),
                >(fake_destroy_micromap)
            })
        } else {
            None
        }
    }
    fn fake_context() -> Arc<Context> {
        FAKE_CALLS.with(|calls| {
            *calls.borrow_mut() = FakeCalls {
                completed: 7,
                ..Default::default()
            };
        });
        unsafe {
            // CPU-only dispatch: a missing function panics if teardown ever calls
            // into Vulkan outside the deliberately supplied test operations.
            let instance = Instance::load_with(
                |name| {
                    if name == c"vkGetDeviceProcAddr" {
                        fake_device_proc as *const () as *const c_void
                    } else {
                        std::ptr::null()
                    }
                },
                vk::Instance::null(),
            );
            let device = Device::load_with(
                |name| match name.to_bytes() {
                    b"vkWaitSemaphores" => fake_wait as *const () as *const c_void,
                    b"vkGetSemaphoreCounterValue" => fake_counter as *const () as *const c_void,
                    b"vkDestroyCommandPool" => fake_destroy_pool as *const () as *const c_void,
                    b"vkDestroyBuffer" => fake_destroy_buffer as *const () as *const c_void,
                    b"vkFreeMemory" => fake_free_memory as *const () as *const c_void,
                    _ => std::ptr::null(),
                },
                vk::Device::null(),
            );
            let acceleration = ash::khr::acceleration_structure::Device::new(&instance, &device);
            Arc::new(Context {
                _instance: Arc::new(InstanceOwner {
                    _entry: Entry::from_static_fn(ash::StaticFn {
                        get_instance_proc_addr: fake_instance_proc,
                    }),
                    instance,
                    debug: None,
                    owned: false,
                }),
                device,
                physical: vk::PhysicalDevice::null(),
                acceleration,
                opacity_micromap: None,
                streamline_capable: false,
                queue: vk::Queue::null(),
                queue_family: 0,
                pool: vk::CommandPool::null(),
                memory: vk::PhysicalDeviceMemoryProperties::default(),
                scratch_alignment: 1,
                max_storage_buffer_range: 1,
                max_image_dimension_2d: 1,
                max_compute_work_group_count: [1; 3],
                max_memory_allocations: 1,
                max_blas_geometries: u64::MAX,
                max_blas_primitives: u64::MAX,
                timestamp_period: 1.0,
                timestamp_bits: 0,
                live_allocations: AtomicU64::new(2),
                name: "CPU teardown test".into(),
                cpu_uploaded_bytes: AtomicU64::new(0),
                profile: None,
                host: Some(Host {
                    timeline: vk::Semaphore::null(),
                    finished: AtomicBool::new(false),
                    state: Mutex::new(HostState {
                        command: vk::CommandBuffer::null(),
                        last_serial: 7,
                        retired: VecDeque::new(),
                    }),
                }),
                uncertain_submission: AtomicBool::new(false),
            })
        }
    }
    fn fake_omm_context() -> Arc<Context> {
        let mut context = fake_context();
        let support = OpacityMicromapSupport {
            loader: ash::ext::opacity_micromap::Device::new(
                &context._instance.instance,
                &context.device,
            ),
            synchronization: ash::khr::synchronization2::Device::new(
                &context._instance.instance,
                &context.device,
            ),
            max_two_state: 8,
            max_four_state: 8,
        };
        Arc::get_mut(&mut context).unwrap().opacity_micromap = Some(support);
        context
    }
    #[test]
    fn micromap_retirement_requires_actual_host_completion_and_keeps_failure_quarantined() {
        let context = fake_omm_context();
        context
            .begin_host_record(vk::CommandBuffer::from_raw(1), 8)
            .unwrap();
        context.retire_micromap(vk::MicromapEXT::from_raw(123));
        context.end_host_record();
        assert_eq!(context.completed_serial().unwrap(), 7);
        FAKE_CALLS.with(|calls| assert!(calls.borrow().destroyed.is_empty()));
        context.finish_host().unwrap();
        FAKE_CALLS.with(|calls| assert_eq!(calls.borrow().destroyed, ["micromap"]));
        drop(context);
        FAKE_CALLS.with(|calls| assert_eq!(calls.borrow().destroyed, ["micromap", "pool"]));

        let context = fake_omm_context();
        context.retire_micromap(vk::MicromapEXT::from_raw(124));
        FAKE_CALLS.with(|calls| calls.borrow_mut().reject_queries = true);
        assert!(context.finish_host().is_err());
        drop(context);
        FAKE_CALLS.with(|calls| assert!(calls.borrow().destroyed.is_empty()));
    }

    fn fake_buffer(context: &Arc<Context>) -> Buffer {
        Buffer {
            context: context.clone(),
            buffer: vk::Buffer::null(),
            memory: vk::DeviceMemory::null(),
            size: 4,
            device_address: 0,
            mapped: None,
        }
    }

    #[test]
    fn recording_and_wait_return_their_completion_proof_without_an_extra_query() {
        let context = fake_context();
        let completed = context
            .begin_host_record(vk::CommandBuffer::from_raw(1), 8)
            .unwrap();
        assert_eq!(completed, 7);
        FAKE_CALLS.with(|calls| assert_eq!(calls.borrow().queries, 1));
        context.end_host_record();
        assert_eq!(context.wait_host_serial(8).unwrap(), 8);
        FAKE_CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!((calls.waits, calls.queries), (1, 2));
        });
        context.finish_host().unwrap();
    }

    #[test]
    fn buffer_address_reads_use_the_owner_cache_without_a_driver_function() {
        let context = fake_context();
        let mut buffer = fake_buffer(&context);
        assert_eq!(buffer.address(), 0);
        buffer.device_address = 0x12345678abcdef00;
        assert_eq!(buffer.address(), 0x12345678abcdef00);
        // Fake dispatch has no vkGetBufferDeviceAddress entry; a lookup here
        // would fail. The full 64-bit cached address remains valid until drop.
        assert_eq!(buffer.address(), buffer.device_address);
        drop(buffer);
        context.finish_host().unwrap();
    }

    #[test]
    fn finished_host_teardown_never_requeries_and_retires_late_drops_locally() {
        let context = fake_context();
        let surviving = fake_buffer(&context);
        drop(fake_buffer(&context));
        assert_eq!(context.live_allocations.load(Ordering::Relaxed), 2);
        context.finish_host().unwrap();
        // Any subsequent driver completion query would fail. Renderer::drop's
        // wait and the final Context::drop must use the stored completion proof.
        FAKE_CALLS.with(|calls| calls.borrow_mut().reject_queries = true);
        context.wait_host_idle().unwrap();
        context.finish_host().unwrap();
        assert_eq!(context.completed_serial().unwrap(), 7);
        assert!(context.wait_host_serial(8).is_err());
        assert!(
            context
                .begin_host_record(vk::CommandBuffer::from_raw(1), 8)
                .is_err()
        );
        drop(surviving);
        assert_eq!(context.live_allocations.load(Ordering::Relaxed), 0);
        drop(context);
        FAKE_CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!((calls.waits, calls.queries), (1, 1));
            assert_eq!(
                calls.destroyed,
                ["buffer", "memory", "buffer", "memory", "pool"]
            );
        });
    }

    #[test]
    fn failed_host_finish_preserves_quarantine_and_does_not_free_resources() {
        let context = fake_context();
        let surviving = fake_buffer(&context);
        drop(fake_buffer(&context));
        FAKE_CALLS.with(|calls| calls.borrow_mut().reject_queries = true);
        assert!(context.finish_host().is_err());
        assert!(!context.can_destroy());
        assert!(
            !context
                .host
                .as_ref()
                .unwrap()
                .finished
                .load(Ordering::Relaxed)
        );
        assert!(context.finish_host().is_err());
        drop(surviving);
        drop(context);
        FAKE_CALLS.with(|calls| {
            let calls = calls.borrow();
            assert_eq!((calls.waits, calls.queries), (1, 1));
            assert!(calls.destroyed.is_empty());
        });
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; validates host resource retirement"]
    fn borrowed_resources_wait_for_host_timeline_and_do_not_destroy_device() {
        let owner = Context::new().unwrap();
        unsafe {
            let mut timeline_type =
                vk::SemaphoreTypeCreateInfo::default().semaphore_type(vk::SemaphoreType::TIMELINE);
            let timeline = owner
                .device
                .create_semaphore(
                    &vk::SemaphoreCreateInfo::default().push_next(&mut timeline_type),
                    None,
                )
                .unwrap();
            let borrowed = Context::borrowed(
                owner.instance_handle(),
                owner.physical.as_raw(),
                owner.device.handle().as_raw(),
                owner.queue.as_raw(),
                owner.queue_family,
                timeline.as_raw(),
            )
            .unwrap();
            let command = owner
                .device
                .allocate_command_buffers(
                    &vk::CommandBufferAllocateInfo::default()
                        .command_pool(owner.pool)
                        .level(vk::CommandBufferLevel::PRIMARY)
                        .command_buffer_count(1),
                )
                .unwrap()[0];
            owner
                .device
                .begin_command_buffer(command, &vk::CommandBufferBeginInfo::default())
                .unwrap();
            assert_eq!(borrowed.begin_host_record(command, 1).unwrap(), 0);
            let buffer =
                Buffer::new(&borrowed, 16, vk::BufferUsageFlags::TRANSFER_DST, false).unwrap();
            assert_eq!(buffer.address(), 0);
            borrowed
                .submit_named("host_fill", |cmd| {
                    borrowed
                        .device
                        .cmd_fill_buffer(cmd, buffer.buffer, 0, 16, 0xabcdef01)
                })
                .unwrap();
            let uploaded = Buffer::upload_device(
                &borrowed,
                &[0x42; 16],
                vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::SHADER_DEVICE_ADDRESS,
            )
            .unwrap();
            let queried = borrowed.device.get_buffer_device_address(
                &vk::BufferDeviceAddressInfo::default().buffer(uploaded.buffer),
            );
            assert_ne!(queried, 0);
            assert_eq!(uploaded.address(), queried);
            assert_eq!(uploaded.address(), queried);
            drop(uploaded);
            drop(buffer);
            borrowed.end_host_record();
            assert_eq!(borrowed.completed_serial().unwrap(), 0);
            assert_eq!(
                borrowed.live_allocations.load(Ordering::Relaxed),
                3,
                "recorded storage must survive Rust drop before the host submits it"
            );
            owner.device.end_command_buffer(command).unwrap();
            let values = [1];
            let signals = [timeline];
            let commands = [command];
            let mut timeline_submit =
                vk::TimelineSemaphoreSubmitInfo::default().signal_semaphore_values(&values);
            owner
                .device
                .queue_submit(
                    owner.queue,
                    &[vk::SubmitInfo::default()
                        .command_buffers(&commands)
                        .signal_semaphores(&signals)
                        .push_next(&mut timeline_submit)],
                    vk::Fence::null(),
                )
                .unwrap();
            borrowed.wait_host_idle().unwrap();
            assert_eq!(borrowed.live_allocations.load(Ordering::Relaxed), 0);
            drop(borrowed);
            // Borrowed context destruction must leave the owning device usable.
            let still_live =
                Buffer::new(&owner, 16, vk::BufferUsageFlags::TRANSFER_DST, false).unwrap();
            drop(still_live);
            owner.device.free_command_buffers(owner.pool, &[command]);
            owner.device.destroy_semaphore(timeline, None);
        }
    }
}
