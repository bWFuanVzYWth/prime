//! Fixed C ABI. C++ SDK types never cross into Rust or Java.
use crate::{Context, Image, reconstruction_history as history};
use ash::vk::{self, Handle};
use prime_scene::{scene::Camera, settings::ReconstructionQuality};
#[cfg(target_os = "windows")]
use std::ffi::{CStr, c_char, c_void};

#[cfg(any(target_os = "windows", test))]
#[repr(C)]
struct Init {
    instance: u64,
    physical_device: u64,
    device: u64,
    queue_family: u32,
    queue_index: u32,
}
#[cfg(target_os = "windows")]
#[repr(C)]
#[derive(Default)]
struct Size {
    width: u32,
    height: u32,
}
#[repr(C)]
#[derive(Clone, Copy, Default)]
struct Resource {
    image: u64,
    view: u64,
    memory: u64,
    width: u32,
    height: u32,
    format: u32,
    layout: u32,
    usage: u32,
    reserved: u32,
}

#[repr(C)]
#[derive(Clone)]
pub(super) struct Frame {
    command: u64,
    index: u32,
    reset: u32,
    jitter: [f32; 2],
    near: f32,
    far: f32,
    fov: f32,
    aspect: f32,
    position: [f32; 3],
    up: [f32; 3],
    right: [f32; 3],
    forward: [f32; 3],
    world_to_view: [f32; 16],
    view_to_world: [f32; 16],
    view_to_clip: [f32; 16],
    clip_to_view: [f32; 16],
    clip_to_previous: [f32; 16],
    previous_to_clip: [f32; 16],
    images: [Resource; 8],
}

impl Frame {
    pub fn new(
        camera: Camera,
        previous: Camera,
        aspect: f32,
        jitter: [f32; 2],
        reset: bool,
        index: u32,
    ) -> Self {
        let (view_to_clip, clip_to_view) = history::projection(camera, aspect);
        Self {
            command: 0,
            index,
            reset: u32::from(reset),
            // The tracer samples pixel center + jitter. NGX expects the projection's
            // pixel displacement, which moves that sample back onto the pixel center.
            jitter: [-jitter[0], -jitter[1]],
            near: history::NEAR,
            far: history::FAR,
            fov: camera.vertical_fov_radians,
            aspect,
            position: camera.position,
            up: camera.up,
            right: camera.right,
            forward: camera.forward,
            world_to_view: history::world_to_view(camera),
            view_to_world: history::view_to_world(camera),
            view_to_clip,
            clip_to_view,
            clip_to_previous: history::clip_to_previous(camera, previous, aspect),
            previous_to_clip: history::clip_to_previous(previous, camera, aspect),
            images: [Resource::default(); 8],
        }
    }
}

#[cfg(target_os = "windows")]
unsafe extern "C" {
    fn prime_sl_abi_version() -> u32;
    fn prime_sl_create(init: *const Init, output: *mut *mut c_void) -> i32;
    fn prime_sl_configure(
        context: *mut c_void,
        width: u32,
        height: u32,
        quality: u32,
        size: *mut Size,
    ) -> i32;
    fn prime_sl_evaluate(context: *mut c_void, frame: *const Frame) -> i32;
    fn prime_sl_destroy(context: *mut c_void) -> i32;
    fn prime_sl_last_error() -> *const c_char;
    fn prime_sl_present(queue: u64, present_info: u64) -> i32;
}

/// # Safety
/// The queue and VkPresentInfoKHR pointer must satisfy vkQueuePresentKHR's host contract.
pub(crate) unsafe fn present(queue: u64, present_info: u64) -> i32 {
    #[cfg(target_os = "windows")]
    {
        unsafe { prime_sl_present(queue, present_info) }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (queue, present_info);
        vk::Result::ERROR_EXTENSION_NOT_PRESENT.as_raw()
    }
}

pub(super) struct Runtime {
    #[cfg(target_os = "windows")]
    handle: *mut c_void,
}

pub(super) struct EvaluationFailure {
    pub message: String,
    pub unsafe_recording: bool,
}

#[cfg(target_os = "windows")]
fn check(result: i32) -> Result<(), String> {
    if result == 0 {
        return Ok(());
    }
    let pointer = unsafe { prime_sl_last_error() };
    let detail = if pointer.is_null() {
        "no SDK diagnostic".into()
    } else {
        unsafe { CStr::from_ptr(pointer) }.to_string_lossy()
    };
    Err(format!("Streamline result {result}: {detail}"))
}

impl Runtime {
    pub fn new(context: &Context) -> Result<Self, String> {
        #[cfg(target_os = "windows")]
        {
            if unsafe { prime_sl_abi_version() } != 1 {
                return Err("Unsupported Streamline bridge ABI".into());
            }
            let init = Init {
                instance: context.instance_handle(),
                physical_device: context.physical.as_raw(),
                device: context.device.handle().as_raw(),
                queue_family: context.queue_family,
                queue_index: 0,
            };
            let mut handle = std::ptr::null_mut();
            check(unsafe { prime_sl_create(&init, &mut handle) })?;
            if handle.is_null() {
                return Err("Streamline returned a null context".into());
            }
            Ok(Self { handle })
        }
        #[cfg(not(target_os = "windows"))]
        {
            let _ = context;
            Err("Streamline RR currently supports Windows x86_64".into())
        }
    }
    pub fn configure(
        &mut self,
        output: [u32; 2],
        quality: ReconstructionQuality,
    ) -> Result<[u32; 2], String> {
        #[cfg(target_os = "windows")]
        {
            let mut size = Size::default();
            check(unsafe {
                prime_sl_configure(self.handle, output[0], output[1], quality as u32, &mut size)
            })?;
            if size.width == 0
                || size.height == 0
                || size.width > output[0]
                || size.height > output[1]
            {
                return Err("Streamline returned an invalid render size".into());
            }
            Ok([size.width, size.height])
        }
        #[cfg(not(target_os = "windows"))]
        {
            let _ = (output, quality);
            Err("Streamline unavailable".into())
        }
    }
    pub fn evaluate(
        &mut self,
        command: vk::CommandBuffer,
        frame: &Frame,
        images: &[Image; 8],
        formats: [vk::Format; 8],
        input: [u32; 2],
        output: [u32; 2],
    ) -> Result<(), EvaluationFailure> {
        let mut frame = frame.clone();
        frame.command = command.as_raw();
        // Native ABI orders reconstructed output before the optional hit-distance.
        for (index, source) in [0, 1, 2, 3, 4, 5, 7, 6].into_iter().enumerate() {
            let extent = if source == 7 { output } else { input };
            let image = &images[source];
            frame.images[index] = Resource {
                image: image.image.as_raw(),
                view: image.view.as_raw(),
                memory: image.memory.as_raw(),
                width: extent[0],
                height: extent[1],
                format: formats[source].as_raw() as u32,
                layout: vk::ImageLayout::GENERAL.as_raw() as u32,
                usage: (vk::ImageUsageFlags::STORAGE
                    | vk::ImageUsageFlags::SAMPLED
                    | vk::ImageUsageFlags::TRANSFER_SRC
                    | vk::ImageUsageFlags::TRANSFER_DST)
                    .as_raw(),
                reserved: 0,
            };
        }
        #[cfg(target_os = "windows")]
        {
            let result = unsafe { prime_sl_evaluate(self.handle, &frame) };
            check(result).map_err(|message| EvaluationFailure {
                message,
                unsafe_recording: result == -100,
            })
        }
        #[cfg(not(target_os = "windows"))]
        {
            Err(EvaluationFailure {
                message: "Streamline unavailable".into(),
                unsafe_recording: false,
            })
        }
    }
}

impl Drop for Runtime {
    fn drop(&mut self) {
        #[cfg(target_os = "windows")]
        if let Err(message) = check(unsafe { prime_sl_destroy(self.handle) }) {
            eprintln!("[Prime PT] Streamline shutdown: {message}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sdk_projection_jitter_cancels_the_traced_sample_displacement() {
        let angle = 0.3f32;
        let camera = Camera {
            position: [3.0, 7.0, 2.0],
            forward: [angle.sin(), 0.0, -angle.cos()],
            right: [angle.cos(), 0.0, angle.sin()],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.2,
        };
        let transform = |p: [f32; 4], m: history::Matrix| -> [f32; 4] {
            std::array::from_fn(|i| (0..4).map(|j| p[j] * m[j * 4 + i]).sum())
        };
        let aspect = 1920.0 / 1080.0;
        let tangent = (camera.vertical_fov_radians * 0.5).tan();
        for [width, height] in [[960u32, 540u32], [853, 479]] {
            for sequence in 0..16 {
                let sample = history::jitter(sequence, width, 1920);
                let frame = Frame::new(camera, camera, aspect, sample, false, sequence);
                for [x, y] in [[0, 0], [43, 77], [width - 1, height - 1]] {
                    let center = [x as f32 + 0.5, y as f32 + 0.5];
                    let screen = [
                        (center[0] + sample[0]) / width as f32 * 2.0 - 1.0,
                        (center[1] + sample[1]) / height as f32 * 2.0 - 1.0,
                    ];
                    for depth in [1.0, 100.0] {
                        // Same primary ray as transport.slang, intersected with a view-Z plane.
                        let point = std::array::from_fn(|i| {
                            if i == 3 {
                                1.0
                            } else {
                                camera.position[i]
                                    + depth
                                        * (camera.forward[i]
                                            + camera.right[i] * screen[0] * tangent * aspect
                                            - camera.up[i] * screen[1] * tangent)
                            }
                        });
                        let clip =
                            transform(transform(point, frame.world_to_view), frame.view_to_clip);
                        let raster = [
                            (clip[0] / clip[3] + 1.0) * 0.5 * width as f32,
                            (1.0 - clip[1] / clip[3]) * 0.5 * height as f32,
                        ];
                        for i in 0..2 {
                            assert!(
                                (raster[i] - center[i] - sample[i]).abs() < 0.002,
                                "unjittered projection must locate the actual traced sample"
                            );
                            assert!(
                                (raster[i] + frame.jitter[i] - center[i]).abs() < 0.002,
                                "SDK projection jitter must move that sample to its pixel center"
                            );
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn bridge_layout_and_matrix_offsets_are_exact() {
        assert_eq!(size_of::<Init>(), 32);
        assert_eq!(size_of::<Resource>(), 48);
        assert_eq!(size_of::<Frame>(), 856);
        assert_eq!(std::mem::offset_of!(Frame, world_to_view), 88);
        assert_eq!(std::mem::offset_of!(Frame, images), 472);
        assert_eq!(align_of::<Frame>(), 8);
        #[cfg(target_os = "windows")]
        assert_eq!(unsafe { prime_sl_abi_version() }, 1);
    }
}
