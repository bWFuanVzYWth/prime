//! Compile actual Prime SPIR-V with driver executable statistics; no dispatch or game.
//! Usage: pipeline-stats compute FILE [surface light optical]
//!        pipeline-stats rt OUT_DIR [surface light optical]
//! Defaults to the six production feature tuples. CSV stdout, device metadata stderr.
use ash::{Entry, vk};
use std::{ffi::CStr, io::Cursor, path::Path};

fn text(bytes: &[std::ffi::c_char]) -> String {
    unsafe { CStr::from_ptr(bytes.as_ptr()) }
        .to_string_lossy()
        .into_owned()
}
fn csv(value: &str) -> String {
    format!("\"{}\"", value.replace('"', "\"\""))
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if !matches!(args.first().map(String::as_str), Some("compute" | "rt"))
        || !matches!(args.len(), 2 | 5)
    {
        return Err(
            "usage: pipeline-stats compute FILE | rt OUT_DIR [surface light optical]".into(),
        );
    }
    let tuples: Vec<[u32; 3]> = if args.len() == 5 {
        vec![[args[2].parse()?, args[3].parse()?, args[4].parse()?]]
    } else {
        vec![
            [0, 0, 0],
            [1, 0, 0],
            [2, 0, 0],
            [2, 1, 0],
            [2, 0, 1],
            [2, 1, 1],
        ]
    };
    unsafe {
        let entry = Entry::load()?;
        let app = vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_2);
        let instance = entry.create_instance(
            &vk::InstanceCreateInfo::default().application_info(&app),
            None,
        )?;
        let physical = instance
            .enumerate_physical_devices()?
            .into_iter()
            .find(|physical| {
                instance
                    .enumerate_device_extension_properties(*physical)
                    .is_ok_and(|extensions| {
                        extensions.iter().any(|ext| {
                            CStr::from_ptr(ext.extension_name.as_ptr())
                                == ash::khr::pipeline_executable_properties::NAME
                        })
                    })
            })
            .ok_or("No device exposes VK_KHR_pipeline_executable_properties")?;
        let properties = instance.get_physical_device_properties(physical);
        eprintln!(
            "device={} vendor={} driver={} api={}",
            text(&properties.device_name),
            properties.vendor_id,
            properties.driver_version,
            properties.api_version
        );
        let family = instance
            .get_physical_device_queue_family_properties(physical)
            .iter()
            .position(|family| family.queue_flags.contains(vk::QueueFlags::COMPUTE))
            .ok_or("No compute queue")? as u32;
        let priorities = [1.0];
        let queues = [vk::DeviceQueueCreateInfo::default()
            .queue_family_index(family)
            .queue_priorities(&priorities)];
        let extensions = [
            ash::khr::pipeline_executable_properties::NAME.as_ptr(),
            ash::khr::acceleration_structure::NAME.as_ptr(),
            ash::khr::ray_query::NAME.as_ptr(),
            ash::khr::ray_tracing_pipeline::NAME.as_ptr(),
            ash::khr::deferred_host_operations::NAME.as_ptr(),
        ];
        let mut executable = vk::PhysicalDevicePipelineExecutablePropertiesFeaturesKHR::default()
            .pipeline_executable_info(true);
        let mut acceleration = vk::PhysicalDeviceAccelerationStructureFeaturesKHR::default()
            .acceleration_structure(true);
        let mut query = vk::PhysicalDeviceRayQueryFeaturesKHR::default().ray_query(true);
        let mut tracing =
            vk::PhysicalDeviceRayTracingPipelineFeaturesKHR::default().ray_tracing_pipeline(true);
        let mut address =
            vk::PhysicalDeviceBufferDeviceAddressFeatures::default().buffer_device_address(true);
        let device = instance.create_device(
            physical,
            &vk::DeviceCreateInfo::default()
                .queue_create_infos(&queues)
                .enabled_extension_names(&extensions)
                .push_next(&mut executable)
                .push_next(&mut acceleration)
                .push_next(&mut query)
                .push_next(&mut tracing)
                .push_next(&mut address),
            None,
        )?;
        let statistics = ash::khr::pipeline_executable_properties::Device::new(&instance, &device);
        let ray_tracing = ash::khr::ray_tracing_pipeline::Device::new(&instance, &device);
        let all = vk::ShaderStageFlags::COMPUTE
            | vk::ShaderStageFlags::RAYGEN_KHR
            | vk::ShaderStageFlags::MISS_KHR
            | vk::ShaderStageFlags::ANY_HIT_KHR
            | vk::ShaderStageFlags::CLOSEST_HIT_KHR;
        let bindings: Vec<_> = [0, 2, 3, 4, 5, 7, 8]
            .into_iter()
            .map(|binding| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(binding)
                    .descriptor_count(1)
                    .stage_flags(all)
                    .descriptor_type(match binding {
                        0 => vk::DescriptorType::ACCELERATION_STRUCTURE_KHR,
                        4 => vk::DescriptorType::STORAGE_IMAGE,
                        _ => vk::DescriptorType::STORAGE_BUFFER,
                    })
            })
            .collect();
        let scene = device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )?;
        let bindings: Vec<_> = (0..5)
            .map(|binding| {
                vk::DescriptorSetLayoutBinding::default()
                    .binding(binding)
                    .descriptor_count(1)
                    .stage_flags(all)
                    .descriptor_type(if binding == 0 {
                        vk::DescriptorType::STORAGE_BUFFER
                    } else {
                        vk::DescriptorType::COMBINED_IMAGE_SAMPLER
                    })
            })
            .collect();
        let environment = device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )?;
        let bindings = [vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_count(1)
            .stage_flags(all)
            .descriptor_type(vk::DescriptorType::STORAGE_BUFFER)];
        let work = device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
            None,
        )?;
        let layouts = [scene, environment, work];
        let push = [vk::PushConstantRange::default().stage_flags(all).size(128)];
        let layout = device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&layouts)
                .push_constant_ranges(&push),
            None,
        )?;
        let shader_files: Vec<_> = if args[0] == "compute" {
            vec![(args[1].clone(), vk::ShaderStageFlags::COMPUTE)]
        } else {
            [
                ("path_raygen", vk::ShaderStageFlags::RAYGEN_KHR),
                ("shadow_raygen", vk::ShaderStageFlags::RAYGEN_KHR),
                ("path_miss", vk::ShaderStageFlags::MISS_KHR),
                ("shadow_miss", vk::ShaderStageFlags::MISS_KHR),
                ("path_anyhit", vk::ShaderStageFlags::ANY_HIT_KHR),
                ("path_closesthit", vk::ShaderStageFlags::CLOSEST_HIT_KHR),
                ("shadow_anyhit", vk::ShaderStageFlags::ANY_HIT_KHR),
                ("shadow_closesthit", vk::ShaderStageFlags::CLOSEST_HIT_KHR),
            ]
            .into_iter()
            .map(|(name, stage)| {
                (
                    Path::new(&args[1])
                        .join(format!("wavefront_{name}.spv"))
                        .to_string_lossy()
                        .into_owned(),
                    stage,
                )
            })
            .collect()
        };
        let mut modules = Vec::new();
        for (file, _) in &shader_files {
            let code = ash::util::read_spv(&mut Cursor::new(std::fs::read(file)?))?;
            modules.push(
                device.create_shader_module(
                    &vk::ShaderModuleCreateInfo::default().code(&code),
                    None,
                )?,
            );
        }
        println!("input,surface,light,optical,executable,stages,subgroup,metric,value,description");
        for features in tuples {
            let entries = std::array::from_fn::<_, 3, _>(|i| vk::SpecializationMapEntry {
                constant_id: i as u32,
                offset: i as u32 * 4,
                size: 4,
            });
            let data = features.map(u32::to_le_bytes);
            let specialization = vk::SpecializationInfo::default()
                .map_entries(&entries)
                .data(data.as_flattened());
            let stages: Vec<_> = modules
                .iter()
                .zip(&shader_files)
                .map(|(module, (_, stage))| {
                    vk::PipelineShaderStageCreateInfo::default()
                        .module(*module)
                        .stage(*stage)
                        .name(c"main")
                        .specialization_info(&specialization)
                })
                .collect();
            let pipeline = if args[0] == "compute" {
                device.create_compute_pipelines(
                    vk::PipelineCache::null(),
                    &[vk::ComputePipelineCreateInfo::default()
                        .stage(stages[0])
                        .layout(layout)
                        .flags(vk::PipelineCreateFlags::CAPTURE_STATISTICS_KHR)],
                    None,
                )
            } else {
                let general = |index| {
                    vk::RayTracingShaderGroupCreateInfoKHR::default()
                        .ty(vk::RayTracingShaderGroupTypeKHR::GENERAL)
                        .general_shader(index)
                        .any_hit_shader(vk::SHADER_UNUSED_KHR)
                        .closest_hit_shader(vk::SHADER_UNUSED_KHR)
                        .intersection_shader(vk::SHADER_UNUSED_KHR)
                };
                let hit = |any, closest| {
                    vk::RayTracingShaderGroupCreateInfoKHR::default()
                        .ty(vk::RayTracingShaderGroupTypeKHR::TRIANGLES_HIT_GROUP)
                        .general_shader(vk::SHADER_UNUSED_KHR)
                        .any_hit_shader(any)
                        .closest_hit_shader(closest)
                        .intersection_shader(vk::SHADER_UNUSED_KHR)
                };
                let groups = [
                    general(0),
                    general(1),
                    general(2),
                    general(3),
                    hit(4, 5),
                    hit(6, 7),
                ];
                ray_tracing.create_ray_tracing_pipelines(
                    vk::DeferredOperationKHR::null(),
                    vk::PipelineCache::null(),
                    &[vk::RayTracingPipelineCreateInfoKHR::default()
                        .stages(&stages)
                        .groups(&groups)
                        .layout(layout)
                        .max_pipeline_ray_recursion_depth(1)
                        .flags(vk::PipelineCreateFlags::CAPTURE_STATISTICS_KHR)],
                    None,
                )
            }
            .map_err(|(_, error)| error)?[0];
            let executables = statistics.get_pipeline_executable_properties(
                &vk::PipelineInfoKHR::default().pipeline(pipeline),
            )?;
            if executables.is_empty() {
                eprintln!(
                    "No executable properties returned for {} features={features:?}; statistics unavailable, not zero.",
                    args[1]
                );
            }
            for (index, executable) in executables.iter().enumerate() {
                let info = vk::PipelineExecutableInfoKHR::default()
                    .pipeline(pipeline)
                    .executable_index(index as u32);
                let stats = statistics.get_pipeline_executable_statistics(&info)?;
                for stat in stats {
                    let value = match stat.format {
                        vk::PipelineExecutableStatisticFormatKHR::BOOL32 => {
                            stat.value.b32.to_string()
                        }
                        vk::PipelineExecutableStatisticFormatKHR::INT64 => {
                            stat.value.i64.to_string()
                        }
                        vk::PipelineExecutableStatisticFormatKHR::UINT64 => {
                            stat.value.u64.to_string()
                        }
                        vk::PipelineExecutableStatisticFormatKHR::FLOAT64 => {
                            stat.value.f64.to_string()
                        }
                        _ => return Err("Unknown statistic value format".into()),
                    };
                    println!(
                        "{},{},{},{},{},{:?},{},{},{},{}",
                        csv(&args[1]),
                        features[0],
                        features[1],
                        features[2],
                        csv(&text(&executable.name)),
                        executable.stages,
                        executable.subgroup_size,
                        csv(&text(&stat.name)),
                        value,
                        csv(&text(&stat.description))
                    );
                }
            }
            device.destroy_pipeline(pipeline, None);
        }
        for module in modules {
            device.destroy_shader_module(module, None);
        }
        device.destroy_pipeline_layout(layout, None);
        for set in layouts {
            device.destroy_descriptor_set_layout(set, None);
        }
        device.destroy_device(None);
        instance.destroy_instance(None);
    }
    Ok(())
}
