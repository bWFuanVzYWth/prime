//! Hardware ray-query renderer. Production records into the host Vulkan
//! submission and target; synchronous readback is restricted to offline diagnostics.
mod arena;
mod atmosphere;
#[cfg(feature = "atmosphere-bake")]
pub use atmosphere::bake_default_atmosphere;
mod benchmark;
mod cpu_profile;
mod display;
mod dynamic;
mod light_grid;
mod light_grid_cpu;
#[cfg(all(test, feature = "shader-tests"))]
mod light_grid_tests;
#[cfg(feature = "light-sampling-bench")]
pub mod light_sampling;
mod objects;
mod omm;
mod omm_cpu;
#[cfg(all(test, feature = "shader-tests"))]
mod omm_tests;
mod openpbr;
mod static_directory;
#[cfg(all(test, feature = "shader-tests"))]
mod terrain_budget_tests;
pub use display::{PrimeDrtParameters, PrimeDrtSettings};
mod geometry;
mod material_arena;
mod packing;
#[cfg(all(test, feature = "shader-tests"))]
mod pbr_delta_tests;
#[cfg(all(test, feature = "shader-tests"))]
mod pbr_tests;
#[cfg(all(test, feature = "shader-tests"))]
mod pbr_texture_tests;
mod plan;
#[cfg(all(test, feature = "shader-tests"))]
mod primary_rr_tests;
#[cfg(all(test, feature = "shader-tests"))]
mod primary_tests;
mod realtime;
#[cfg(all(test, feature = "shader-tests"))]
mod realtime_perf_tests;
#[cfg(all(test, feature = "shader-tests"))]
mod realtime_tests;
mod reconstruction;
mod reconstruction_history;
mod resources;
#[cfg(all(test, feature = "shader-tests"))]
mod rr_display_tests;
mod scene_resources;
mod surface;
#[cfg(test)]
mod surface_tests;
mod target;
mod textures;
pub use benchmark::HostBenchmark;
use geometry::Geometry;
pub use resources::GpuProfile;

use ash::vk::{self, Handle};
use prime_scene::instances::InstanceInput;
use prime_scene::scene::{Camera, InstanceScene, Scene};
use prime_scene::settings::{RenderMode, RenderSettings};
use resources::{Buffer, Context, error};
use std::{io::Cursor, sync::Arc};
use target::Image;

const FRAME_SLOTS: usize = 3;

/// Forwards the host's real present call, allowing Streamline to retire its frame tags.
/// # Safety
/// Both arguments must remain valid for the actual vkQueuePresentKHR call.
pub unsafe fn streamline_present(queue: u64, present_info: u64) -> i32 {
    unsafe { reconstruction::present(queue, present_info) }
}

/// Last recorded frame's object work; independent of opt-in timing instrumentation.
#[derive(Clone, Copy, Debug, Default)]
pub struct InstanceWork {
    pub resident_blas: u32,
    pub rebuilt_blas: u32,
    pub instances: u32,
}

#[cfg(test)]
mod object_tests;

#[cfg(test)]
mod burst_perf;

#[cfg(all(test, feature = "shader-tests"))]
mod shader_tests;

struct Pipeline {
    context: Arc<Context>,
    layout: vk::PipelineLayout,
    descriptor_layout: vk::DescriptorSetLayout,
    environment_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    descriptors: [vk::DescriptorSet; FRAME_SLOTS],
    pipelines: [vk::Pipeline; 6],
    single_sample_pipelines: Option<[vk::Pipeline; 6]>,
    primary_pipelines: Option<[vk::Pipeline; 4]>,
    realtime_post: Option<vk::Pipeline>,
    reconstruction_display: Option<vk::Pipeline>,
}
impl Drop for Pipeline {
    fn drop(&mut self) {
        if self.context.can_destroy() {
            unsafe {
                for pipeline in self
                    .pipelines
                    .into_iter()
                    .chain(self.single_sample_pipelines.into_iter().flatten())
                    .chain(self.primary_pipelines.into_iter().flatten())
                    .chain(self.realtime_post)
                    .chain(self.reconstruction_display)
                {
                    self.context.device.destroy_pipeline(pipeline, None);
                }
                self.context.device.destroy_descriptor_pool(self.pool, None);
                self.context
                    .device
                    .destroy_pipeline_layout(self.layout, None);
                self.context
                    .device
                    .destroy_descriptor_set_layout(self.descriptor_layout, None);
                self.context
                    .device
                    .destroy_descriptor_set_layout(self.environment_layout, None);
            }
        }
    }
}
struct ShaderModule<'a> {
    context: &'a Context,
    handle: vk::ShaderModule,
}
impl Drop for ShaderModule<'_> {
    fn drop(&mut self) {
        unsafe { self.context.device.destroy_shader_module(self.handle, None) };
    }
}
impl Pipeline {
    fn new(
        context: &Arc<Context>,
        mode: RenderMode,
        reconstruction: bool,
        energy_lut: &openpbr::EnergyLut,
    ) -> Result<Self, String> {
        unsafe {
            let mut result = Self {
                context: context.clone(),
                layout: vk::PipelineLayout::null(),
                descriptor_layout: vk::DescriptorSetLayout::null(),
                environment_layout: vk::DescriptorSetLayout::null(),
                pool: vk::DescriptorPool::null(),
                descriptors: [vk::DescriptorSet::null(); FRAME_SLOTS],
                pipelines: [vk::Pipeline::null(); 6],
                single_sample_pipelines: (mode == RenderMode::Offline)
                    .then_some([vk::Pipeline::null(); 6]),
                primary_pipelines: (mode == RenderMode::Realtime)
                    .then_some([vk::Pipeline::null(); 4]),
                realtime_post: None,
                reconstruction_display: None,
            };
            let binding_ids: &[u32] = match mode {
                RenderMode::Offline => &[0, 2, 3, 4, 5, 7, 8, 9],
                RenderMode::Realtime if reconstruction => &[
                    0, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                ],
                RenderMode::Realtime => &[0, 2, 3, 4, 7, 8, 9],
            };
            let bindings: Vec<_> = binding_ids
                .iter()
                .copied()
                .map(|binding| {
                    vk::DescriptorSetLayoutBinding::default()
                        .binding(binding)
                        .descriptor_count(1)
                        .stage_flags(vk::ShaderStageFlags::COMPUTE)
                        .descriptor_type(if binding == 0 {
                            vk::DescriptorType::ACCELERATION_STRUCTURE_KHR
                        } else if binding == 9 {
                            vk::DescriptorType::COMBINED_IMAGE_SAMPLER
                        } else if binding == 17 {
                            vk::DescriptorType::UNIFORM_BUFFER
                        } else if binding == 4 || (10..=16).contains(&binding) || binding >= 18 {
                            vk::DescriptorType::STORAGE_IMAGE
                        } else {
                            vk::DescriptorType::STORAGE_BUFFER
                        })
                })
                .collect();
            result.descriptor_layout = context
                .device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .map_err(|e| error("Create path-tracing descriptor layout", e))?;
            result.environment_layout = atmosphere::consumer_layout(context)?;
            let layouts = [result.descriptor_layout, result.environment_layout];
            let push = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::COMPUTE)
                .offset(0)
                .size(128)];
            result.layout = context
                .device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&push),
                    None,
                )
                .map_err(|e| error("Create path-tracing pipeline layout", e))?;
            let sizes = [
                vk::DescriptorPoolSize {
                    ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
                    descriptor_count: FRAME_SLOTS as u32,
                },
                vk::DescriptorPoolSize {
                    ty: vk::DescriptorType::ACCELERATION_STRUCTURE_KHR,
                    descriptor_count: FRAME_SLOTS as u32,
                },
                vk::DescriptorPoolSize {
                    ty: vk::DescriptorType::STORAGE_BUFFER,
                    descriptor_count: (if mode == RenderMode::Offline { 5 } else { 4 })
                        * FRAME_SLOTS as u32,
                },
                vk::DescriptorPoolSize {
                    ty: vk::DescriptorType::STORAGE_IMAGE,
                    descriptor_count: (if reconstruction { 11 } else { 1 }) * FRAME_SLOTS as u32,
                },
                vk::DescriptorPoolSize {
                    ty: vk::DescriptorType::UNIFORM_BUFFER,
                    descriptor_count: FRAME_SLOTS as u32,
                },
            ];
            result.pool = context
                .device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(FRAME_SLOTS as u32)
                        .pool_sizes(&sizes),
                    None,
                )
                .map_err(|e| error("Create path-tracing descriptor pool", e))?;
            result.descriptors = context
                .device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(result.pool)
                        .set_layouts(&[result.descriptor_layout; FRAME_SLOTS]),
                )
                .map_err(|e| error("Allocate path-tracing descriptors", e))?
                .try_into()
                .map_err(|_| "Invalid descriptor count")?;
            let energy_info = [energy_lut.descriptor()];
            let energy_writes: Vec<_> = result
                .descriptors
                .iter()
                .map(|&set| {
                    vk::WriteDescriptorSet::default()
                        .dst_set(set)
                        .dst_binding(9)
                        .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                        .image_info(&energy_info)
                })
                .collect();
            context.device.update_descriptor_sets(&energy_writes, &[]);
            let mut modules = std::collections::BTreeMap::new();
            let mut create = |bytes: &[u8],
                              features: [u32; 3],
                              single_sample: u32,
                              primary: bool|
             -> Result<vk::Pipeline, String> {
                let shader = modules.entry((bytes.as_ptr() as usize, bytes.len()));
                let shader = match shader {
                    std::collections::btree_map::Entry::Occupied(module) => module.into_mut(),
                    std::collections::btree_map::Entry::Vacant(module) => {
                        let spirv = ash::util::read_spv(&mut Cursor::new(bytes))
                            .map_err(|e| format!("Read compiled Slang SPIR-V: {e}"))?;
                        module.insert(ShaderModule {
                            context,
                            handle: context
                                .device
                                .create_shader_module(
                                    &vk::ShaderModuleCreateInfo::default().code(&spirv),
                                    None,
                                )
                                .map_err(|e| error("Create Slang shader module", e))?,
                        })
                    }
                };
                let entries = [
                    vk::SpecializationMapEntry {
                        constant_id: 0,
                        offset: 0,
                        size: 4,
                    },
                    vk::SpecializationMapEntry {
                        constant_id: 1,
                        offset: 4,
                        size: 4,
                    },
                    vk::SpecializationMapEntry {
                        constant_id: 2,
                        offset: 8,
                        size: 4,
                    },
                    vk::SpecializationMapEntry {
                        constant_id: 4,
                        offset: 12,
                        size: 4,
                    },
                ];
                let data =
                    [features[0], features[1], features[2], single_sample].map(u32::to_le_bytes);
                let primary_entries = [entries[0], entries[2]];
                let specialization = vk::SpecializationInfo::default()
                    .map_entries(if primary {
                        &primary_entries
                    } else {
                        &entries[..if mode == RenderMode::Offline { 4 } else { 3 }]
                    })
                    .data(data.as_flattened());
                let stage = vk::PipelineShaderStageCreateInfo::default()
                    .stage(vk::ShaderStageFlags::COMPUTE)
                    .module(shader.handle)
                    .name(c"main")
                    .specialization_info(&specialization);
                let created = context.device.create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default()
                        .stage(stage)
                        .layout(result.layout)],
                    None,
                );
                Ok(match created {
                    Ok(pipelines) => pipelines[0],
                    Err((partial, e)) => {
                        for pipeline in partial {
                            context.device.destroy_pipeline(pipeline, None);
                        }
                        return Err(error("Create Slang compute pipeline", e));
                    }
                })
            };
            let shader: &[u8] = match mode {
                RenderMode::Offline => include_bytes!(concat!(env!("OUT_DIR"), "/path_trace.spv")),
                RenderMode::Realtime if reconstruction => {
                    include_bytes!(concat!(env!("OUT_DIR"), "/realtime_transport_rr.spv"))
                }
                RenderMode::Realtime => {
                    include_bytes!(concat!(env!("OUT_DIR"), "/realtime_transport.spv"))
                }
            };
            for (i, features) in [
                [0, 0, 0],
                [1, 0, 0],
                [2, 0, 0],
                [2, 1, 0],
                [2, 0, 1],
                [2, 1, 1],
            ]
            .into_iter()
            .enumerate()
            {
                result.pipelines[i] = create(shader, features, 0, false)?;
                if let Some(single) = &mut result.single_sample_pipelines {
                    single[i] = create(shader, features, 1, false)?;
                }
            }
            if let Some(primary) = &mut result.primary_pipelines {
                let shader: &[u8] = if reconstruction {
                    include_bytes!(concat!(env!("OUT_DIR"), "/realtime_primary_rr.spv"))
                } else {
                    include_bytes!(concat!(env!("OUT_DIR"), "/realtime_primary.spv"))
                };
                for (target, features) in primary.iter_mut().zip(realtime::PRIMARY_FEATURES) {
                    *target = create(shader, features, 0, true)?;
                }
                result.realtime_post = Some(create(
                    if reconstruction {
                        include_bytes!(concat!(env!("OUT_DIR"), "/realtime_rr.spv"))
                    } else {
                        include_bytes!(concat!(env!("OUT_DIR"), "/realtime.spv"))
                    },
                    [0, 0, 0],
                    0,
                    false,
                )?);
            }
            if reconstruction {
                result.reconstruction_display = Some(create(
                    include_bytes!(concat!(env!("OUT_DIR"), "/rr_display.spv")),
                    [0, 0, 0],
                    0,
                    false,
                )?);
            }
            Ok(result)
        }
    }

    fn for_dispatch(&self, scene_variant: usize, samples_this_dispatch: u32) -> vk::Pipeline {
        match (&self.single_sample_pipelines, samples_this_dispatch) {
            (Some(single), 1) => single[scene_variant],
            _ => self.pipelines[scene_variant],
        }
    }
}

fn float(bytes: &mut Vec<u8>, value: f32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
fn uint(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
fn float4(bytes: &mut Vec<u8>, value: [f32; 4]) {
    for v in value {
        float(bytes, v);
    }
}

mod frame;
use frame::Output;

#[derive(Clone, Copy, Debug, Default)]
struct GpuIntervals {
    serial: u64,
    preparation_ns: u64,
    render_ns: u64,
    total_ns: u64,
}
impl GpuIntervals {
    fn retain_latest(&mut self, completed: Self) {
        // Descriptor slots can retire out of serial order; unrelated host submissions
        // also leave gaps. Keep the diagnostic sample independent of the modulo ring.
        if completed.serial > self.serial {
            *self = completed;
        }
    }
}

pub struct Renderer {
    workers: Arc<prime_scene::workers::CpuWorkers>,
    context: Arc<Context>,
    // Only the selected backend's pipeline and sized output exist; scene geometry is shared.
    pipeline: Option<Pipeline>,
    // Immutable device data outlives mode and quality changes.
    energy_lut: openpbr::EnergyLut,
    reconstruction: Option<reconstruction::Reconstruction>,
    reconstruction_error: Option<String>,
    geometry: Option<Geometry>,
    scene_resources: Option<scene_resources::SharedResources>,
    atmosphere: Option<atmosphere::Atmosphere>,
    atmosphere_scene_revision: u64,
    environment: prime_scene::environment::Environment,
    output: Option<Output>,
    camera: Option<Camera>,
    samples: u32,
    frame_seed: u32,
    display: PrimeDrtParameters,
    settings: RenderSettings,
    scene_frozen: bool,
    failed: bool,
    host_serials: [u64; FRAME_SLOTS],
    query_serials: [u64; FRAME_SLOTS],
    gpu_intervals: [GpuIntervals; FRAME_SLOTS],
    host_query: vk::QueryPool,
    last_gpu: GpuIntervals,
    descriptor_keys: [[u64; 7]; FRAME_SLOTS],
    cpu_profile: cpu_profile::CpuProfile,
}
#[cfg(test)]
mod tests {
    use super::*;
    use prime_scene::scene::{SceneMesh, Texture, Triangle};

    #[test]
    fn gpu_diagnostic_keeps_latest_complete_interval_despite_slot_order_and_serial_gaps() {
        let mut latest = GpuIntervals::default();
        let mut ring = [GpuIntervals::default(); FRAME_SLOTS];
        // The older sample aliases the newest one's modulo bucket and arrives later.
        for (serial, preparation_ns, render_ns) in [(9, 20, 30), (9 - FRAME_SLOTS as u64, 2, 3)] {
            let sample = GpuIntervals {
                serial,
                preparation_ns,
                render_ns,
                total_ns: preparation_ns + render_ns,
            };
            ring[(serial % FRAME_SLOTS as u64) as usize] = sample;
            latest.retain_latest(sample);
        }
        assert_eq!(latest.serial, 9);
        assert_eq!(
            (latest.preparation_ns, latest.render_ns, latest.total_ns),
            (20, 30, 50)
        );
        assert!(ring.iter().all(|sample| sample.serial != latest.serial));
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; run with synchronization validation"]
    fn gpu_dynamic_snapshot_alpha_coverage_and_incremental_textures() {
        use prime_scene::scene::DynamicScene;
        let mut renderer = Renderer::new().unwrap();
        // Coverage is a primary-hit contract. A black surface now receives aerial
        // radiance, so test the actual hit guide instead of assuming black output.
        renderer
            .configure(RenderSettings {
                view: prime_scene::settings::DiagnosticView::LinearDepth,
                ..Default::default()
            })
            .unwrap();
        let camera = Camera {
            position: [0.0, 0.0, 2.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let quad = |alpha| {
            vec![
                Triangle {
                    positions: [[-20.0, -20.0, 0.0], [20.0, -20.0, 0.0], [20.0, 20.0, 0.0]],
                    colors: [[0.0, 0.0, 0.0, alpha]; 3],
                    uvs: [[0.5; 2]; 3],
                    texture_id: 7,
                    flags: 2,
                },
                Triangle {
                    positions: [[-20.0, -20.0, 0.0], [20.0, 20.0, 0.0], [-20.0, 20.0, 0.0]],
                    colors: [[0.0, 0.0, 0.0, alpha]; 3],
                    uvs: [[0.5; 2]; 3],
                    texture_id: 7,
                    flags: 2,
                },
            ]
        };
        let mut scene = Scene {
            ready_terrain: [
                [-64.0, 0.0, 0.0],
                [0.0; 3],
                [64.0, 0.0, 0.0],
                [128.0, 0.0, 0.0],
            ]
            .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
            .into(),
            revision: 1,
            epoch: 1,
            ..Default::default()
        };
        scene.textures.insert(
            7,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 1,
                height: 1,
                pixels: vec![255; 4].into(),
            },
        );
        scene.meshes.insert(
            (7, 0),
            SceneMesh {
                revision: 1,
                flags: 2,
                origin: [128.0, 0.0, 0.0],
                triangles: quad(1.0).into(),
            },
        );
        let miss = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        let static_buffer = renderer.geometry.as_ref().unwrap().static_bases.buffer;
        let static_revision = renderer.geometry.as_ref().unwrap().revision;
        scene.dynamic = DynamicScene {
            revision: 1,
            origin: [0.0; 3],
            triangles: quad(0.0).into(),
        };
        assert_eq!(renderer.render(&scene, &camera, 64, 48, 0).unwrap(), miss);
        let dynamic_buffer = renderer
            .geometry
            .as_ref()
            .unwrap()
            .objects
            .material_addresses();
        let top = renderer.geometry.as_ref().unwrap().top.handle();
        let allocations = renderer
            .profile_snapshot()
            .map(|profile| profile.allocations);
        scene.dynamic.revision += 1;
        scene.dynamic.triangles = quad(1.0).into();
        let opaque = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        assert!(opaque.as_chunks::<4>().0.iter().all(|pixel| pixel[0] > 0
            && pixel[0] == pixel[1]
            && pixel[1] == pixel[2]
            && pixel[3] == 255));
        scene.dynamic.revision += 1;
        scene.dynamic.triangles = quad(0.5).into();
        let half = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        let covered = half
            .as_chunks::<4>()
            .0
            .iter()
            .filter(|pixel| pixel[0] > 0)
            .count();
        assert!(
            (1300..1800).contains(&covered),
            "half-alpha coverage was {covered}/3072"
        );
        scene.dynamic.revision += 1;
        let different_seed = renderer.render(&scene, &camera, 64, 48, 1).unwrap();
        assert_ne!(
            different_seed, half,
            "dynamic coverage seed must advance independently of reset"
        );
        let mut doubled = quad(0.5);
        let back_faces: Vec<_> = doubled
            .iter()
            .map(|triangle| {
                let mut back = *triangle;
                back.positions.swap(0, 2);
                back
            })
            .collect();
        doubled.extend(back_faces);
        scene.dynamic.revision += 1;
        scene.dynamic.triangles = doubled.into();
        assert_eq!(
            renderer.render(&scene, &camera, 64, 48, 0).unwrap(),
            half,
            "coincident reversed fluid faces must share their alpha event"
        );
        assert_eq!(
            renderer.geometry.as_ref().unwrap().static_bases.buffer,
            static_buffer
        );
        assert_eq!(
            renderer.geometry.as_ref().unwrap().revision,
            static_revision
        );
        assert_eq!(
            renderer
                .geometry
                .as_ref()
                .unwrap()
                .objects
                .material_addresses(),
            dynamic_buffer
        );
        assert_eq!(renderer.geometry.as_ref().unwrap().top.handle(), top);
        assert_eq!(
            renderer
                .profile_snapshot()
                .map(|profile| profile.allocations),
            allocations,
            "same-capacity snapshots must not allocate new GPU buffers or AS storage"
        );
        let original_index = renderer
            .geometry
            .as_ref()
            .unwrap()
            .textures()
            .index(7)
            .unwrap();
        scene.textures.insert(
            3,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 2,
                height: 2,
                pixels: vec![128; 16].into(),
            },
        );
        scene.revision += 1;
        assert_eq!(renderer.render(&scene, &camera, 64, 48, 0).unwrap(), half);
        assert_eq!(
            renderer
                .geometry
                .as_ref()
                .unwrap()
                .textures()
                .index(7)
                .unwrap(),
            original_index
        );
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 0);
        scene.textures.get_mut(&7).unwrap().pixels = vec![255, 255, 255, 0].into();
        scene.revision += 1;
        assert_eq!(
            renderer.render(&scene, &camera, 64, 48, 0).unwrap(),
            miss,
            "texture delta must affect the existing dynamic material without a geometry update"
        );
        scene.dynamic.revision += 1;
        scene.dynamic.triangles = Arc::default();
        assert_eq!(renderer.render(&scene, &camera, 64, 48, 0).unwrap(), miss);
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU"]
    fn gpu_cluster_identity_incremental_update_rebase_epoch_and_texture_lifetimes() {
        let mut renderer = Renderer::new().unwrap();
        let mut scene = Scene {
            ready_terrain: [
                [-64.0, 0.0, 0.0],
                [0.0; 3],
                [64.0, 0.0, 0.0],
                [128.0, 0.0, 0.0],
            ]
            .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
            .into(),
            revision: 1,
            ..Default::default()
        };
        scene.textures.insert(
            7,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 2,
                height: 1,
                pixels: vec![255, 0, 0, 255, 0, 0, 0, 0].into(),
            },
        );
        let make_mesh = |origin, flags, texture_id, color| SceneMesh {
            revision: 1,
            flags,
            origin,
            triangles: vec![
                Triangle {
                    positions: [[-2.0, -2.0, 0.0], [2.0, -2.0, 0.0], [2.0, 2.0, 0.0]],
                    colors: [color; 3],
                    uvs: [[0.0, 1.0], [1.0, 1.0], [1.0, 0.0]],
                    texture_id,
                    flags,
                },
                Triangle {
                    positions: [[-2.0, -2.0, 0.0], [2.0, 2.0, 0.0], [-2.0, 2.0, 0.0]],
                    colors: [color; 3],
                    uvs: [[0.0, 1.0], [1.0, 0.0], [0.0, 0.0]],
                    texture_id,
                    flags,
                },
            ]
            .into(),
        };
        scene
            .meshes
            .insert((1, 0), make_mesh([-64.0, 0.0, 0.0], 1, 7, [1.0; 4]));
        scene.meshes.insert(
            (2, 0),
            make_mesh([64.0, 0.0, 0.0], 0, 0, [0.0, 1.0, 0.0, 1.0]),
        );
        let mut camera = Camera {
            position: [64.0, 0.0, 2.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let center =
            |pixels: &[u8]| -> [u8; 4] { pixels[(24 * 64 + 32) * 4..][..4].try_into().unwrap() };
        let green = center(&renderer.render(&scene, &camera, 64, 48, 0).unwrap());
        assert!(
            green[1] > green[0] + 30 && green[1] > green[2] + 30,
            "wrong opaque instance material: {green:?}"
        );
        camera.position[0] = -64.0;
        let cutout = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        let red = &cutout[(24 * 64 + 16) * 4..][..4];
        let hole = &cutout[(24 * 64 + 48) * 4..][..4];
        assert!(
            red[0] > red[2] + 30 && hole[2] > hole[0],
            "wrong cutout instance mapping"
        );
        let mesh = scene.meshes.get_mut(&(2, 0)).unwrap();
        let prime_scene::geometry::MeshGeometry::Triangles(data) = &mut mesh.triangles else {
            panic!("expected diagnostic triangle mesh")
        };
        for triangle in Arc::make_mut(data) {
            triangle.colors = [[0.0, 0.0, 1.0, 1.0]; 3];
        }
        mesh.revision += 1;
        scene.revision += 1;
        camera.position[0] = 64.0;
        let blue = center(&renderer.render(&scene, &camera, 64, 48, 0).unwrap());
        assert!(blue[2] > blue[0] + 30 && blue[2] > blue[1] + 30);
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 1);
        // Anchor changes preserve local BLAS even without a separate scene revision bump.
        scene.anchor[0] += 256.0;
        camera.position[0] -= 256.0;
        assert_eq!(
            center(&renderer.render(&scene, &camera, 64, 48, 0).unwrap()),
            blue
        );
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 0);
        // Texture pixels change independently of geometry; this must not rebuild any BLAS.
        scene.textures.get_mut(&7).unwrap().pixels = vec![0, 255, 0, 255, 0, 0, 0, 0].into();
        scene.revision += 1;
        camera.position[0] = -320.0;
        let updated = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        let pixel = &updated[(24 * 64 + 16) * 4..][..4];
        assert!(pixel[1] > pixel[0] + 30);
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 0);
        // New epochs may reuse source revisions and keys with entirely different geometry.
        scene.epoch += 1;
        let mesh = scene.meshes.get_mut(&(2, 0)).unwrap();
        let prime_scene::geometry::MeshGeometry::Triangles(data) = &mut mesh.triangles else {
            panic!("expected diagnostic triangle mesh")
        };
        for triangle in Arc::make_mut(data) {
            triangle.colors = [[1.0, 0.0, 0.0, 1.0]; 3];
        }
        camera.position[0] = -192.0;
        let changed = center(&renderer.render(&scene, &camera, 64, 48, 0).unwrap());
        assert!(changed[0] > changed[2] + 30);
        assert_eq!(renderer.geometry.as_ref().unwrap().rebuilt_clusters, 2);
        scene.meshes.remove(&(1, 0));
        scene.revision += 1;
        camera.position[0] = -320.0;
        let sky = center(&renderer.render(&scene, &camera, 64, 48, 0).unwrap());
        assert!(sky[2] > sky[0], "deleted cluster still intersects rays");
    }

    #[test]
    #[ignore = "requires a Vulkan ray-query GPU; run with PRIME_VK_VALIDATION=1"]
    fn gpu_cutout_history_resize_and_scene_replacement() {
        let mut renderer = Renderer::new().unwrap();
        let camera = Camera {
            position: [0.0, 0.0, 2.0],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        let mut scene = Scene {
            ready_terrain: [
                [-64.0, 0.0, 0.0],
                [0.0; 3],
                [64.0, 0.0, 0.0],
                [128.0, 0.0, 0.0],
            ]
            .map(|origin| prime_scene::spatial::Cell::containing(origin).unwrap())
            .into(),
            revision: 1,
            ..Scene::default()
        };
        // Empty BLAS/TLAS handling must produce a real shader-evaluated sky.
        let sky = renderer.render(&scene, &camera, 64, 48, 0).unwrap();
        assert_eq!(sky.len(), 64 * 48 * 4);
        assert!(sky.as_chunks::<4>().0.iter().all(|p| p[3] == 255));
        scene.textures.insert(
            7,
            Texture {
                region: None,
                sampling: None,
                material: None,
                width: 2,
                height: 1,
                pixels: vec![255, 0, 0, 255, 0, 0, 0, 0].into(),
            },
        );
        let triangles = vec![
            Triangle {
                positions: [[-2.0, -2.0, 0.0], [2.0, -2.0, 0.0], [2.0, 2.0, 0.0]],
                colors: [[1.0; 4]; 3],
                uvs: [[0.0, 1.0], [1.0, 1.0], [1.0, 0.0]],
                texture_id: 7,
                flags: 1,
            },
            Triangle {
                positions: [[-2.0, -2.0, 0.0], [2.0, 2.0, 0.0], [-2.0, 2.0, 0.0]],
                colors: [[1.0; 4]; 3],
                uvs: [[0.0, 1.0], [1.0, 0.0], [0.0, 0.0]],
                texture_id: 7,
                flags: 1,
            },
        ];
        scene.meshes.insert(
            (0, 0),
            SceneMesh {
                revision: 1,
                flags: 1,
                origin: [0.0; 3],
                triangles: triangles.into(),
            },
        );
        scene.revision += 1;
        let mut pixels = Vec::new();
        for sample in 0..4 {
            pixels = renderer.render(&scene, &camera, 64, 48, sample).unwrap();
        }
        let red = &pixels[(24 * 64 + 16) * 4..][..4];
        let hole = &pixels[(24 * 64 + 48) * 4..][..4];
        assert!(
            red[0] > red[2] + 30,
            "opaque red texel should shade red: {red:?}"
        );
        assert!(
            hole[2] > hole[0],
            "alpha-zero texel must let the blue sky through: {hole:?}"
        );
        let resized = renderer.render(&scene, &camera, 31, 17, 4).unwrap();
        assert_eq!(resized.len(), 31 * 17 * 4);
        scene.meshes.clear();
        scene.revision += 1;
        let cleared = renderer.render(&scene, &camera, 64, 48, 5).unwrap();
        assert_eq!(
            cleared, sky,
            "geometry replacement must reset accumulation and remove old occluders"
        );
    }

    #[test]
    #[ignore = "requires Vulkan; tests real output, accumulation and display control lifetime"]
    fn gpu_resize_resets_history_and_display_controls_preserve_linear_history() {
        let mut renderer = Renderer::new().unwrap();
        let mut fresh = Renderer::new().unwrap();
        let scene = Scene::default();
        let camera = Camera {
            position: [0.0; 3],
            forward: [0.0, 0.0, -1.0],
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            vertical_fov_radians: 1.0,
        };
        for (width, height) in [(31, 17), (17, 31), (1, 1), (4097, 3), (1919, 17), (31, 17)] {
            let resized = renderer.render(&scene, &camera, width, height, 99).unwrap();
            let reference = fresh.render(&scene, &camera, width, height, 0).unwrap();
            assert_eq!(
                resized, reference,
                "resize must refresh aspect, Sobol domain and history"
            );
            assert_eq!(resized.len(), width as usize * height as usize * 4);
            assert_eq!(renderer.samples, 1);
            renderer
                .render(&scene, &camera, width, height, 100)
                .unwrap();
            assert_eq!(renderer.samples, 2);
            assert_eq!(renderer.frame_seed, 1);
        }
        renderer
            .set_prime_drt(PrimeDrtSettings {
                exposure_multiplier: 0.25,
                ..Default::default()
            })
            .unwrap();
        let darker = renderer.render(&scene, &camera, 31, 17, 101).unwrap();
        fresh.render(&scene, &camera, 31, 17, 100).unwrap();
        let regular = fresh.render(&scene, &camera, 31, 17, 101).unwrap();
        assert_eq!(
            renderer.samples, 3,
            "display-only settings must preserve accumulation"
        );
        assert!(
            darker[0..3].iter().map(|&v| u32::from(v)).sum::<u32>()
                < regular[0..3].iter().map(|&v| u32::from(v)).sum::<u32>()
        );
        assert!(renderer.context.render_extent(0, 1).is_err());
        assert!(renderer.context.render_extent(65536, 65536).is_err());
    }
}

#[cfg(all(test, feature = "shader-tests"))]
mod register_fixtures;
#[cfg(all(test, feature = "shader-tests"))]
mod register_tests;
