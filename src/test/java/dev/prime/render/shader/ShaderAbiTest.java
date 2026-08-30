package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ShaderAbiTest {
    @Test
    void fixedRecordSizesAndBindingsMatchTheContract() {
        assertEquals(32, ShaderAbi.PRIMITIVE_RECORD_SIZE);
        assertEquals(96, ShaderAbi.SECTION_RECORD_SIZE);
        assertEquals(64, ShaderAbi.SECTION_INSTANCE_TINT_OFFSET);
        assertEquals(32, ShaderAbi.LIGHT_NODE_SIZE);
        assertEquals(8, ShaderAbi.LIGHT_LEAF_SIZE);
        assertEquals(8, ShaderAbi.LIGHT_LEAF_ENTRY_SIZE);
        assertEquals(96, ShaderAbi.LIGHT_EMITTER_SIZE);
        assertEquals(12, ShaderAbi.LIGHT_CELL_SIZE);
        assertEquals(48, ShaderAbi.SECTION_LIGHT_HEADER_SIZE);
        assertEquals(32, ShaderAbi.INTEGRATOR_RECORD_SIZE);
        assertEquals(64, ShaderAbi.PATH_STATE_SIZE);
        assertEquals(96, ShaderAbi.TRACE_PAYLOAD_SIZE);
        assertEquals(96, ShaderAbi.SURFACE_INTERACTION_SIZE);
        assertEquals(96, ShaderAbi.WAVEFRONT_SURFACE_RECORD_SIZE);
        assertEquals(
                0, ShaderAbi.WAVEFRONT_SURFACE_DISTANCE_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(
                16, ShaderAbi.WAVEFRONT_SURFACE_HIT_MATERIAL_SECTION_EMITTER_OFFSET);
        assertEquals(
                32, ShaderAbi.WAVEFRONT_SURFACE_BASE_COLOR_TEXTURE_LOD_OFFSET);
        assertEquals(
                48, ShaderAbi.WAVEFRONT_SURFACE_OPACITY_SHADING_ROUGHNESS_OPTICAL_OFFSET);
        assertEquals(
                64, ShaderAbi.WAVEFRONT_SURFACE_ADJACENT_BASE_COLOR_INTERFACE_OFFSET);
        assertEquals(80, ShaderAbi.WAVEFRONT_SURFACE_POSITION_RESERVED_OFFSET);
        assertEquals(76, ShaderAbi.SURFACE_MOTION_ZFLAGS_OFFSET);
        assertEquals(128, ShaderAbi.PUSH_CONSTANT_SIZE);
        assertEquals(48, ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE);
        assertEquals(0, ShaderAbi.SUN_SHADOW_QUERY_DIRECTION_TO_SUN_OFFSET);
        assertEquals(12, ShaderAbi.SUN_SHADOW_QUERY_BANK_OFFSET);
        assertEquals(16, ShaderAbi.SUN_SHADOW_QUERY_BASIS_U_OFFSET);
        assertEquals(28, ShaderAbi.SUN_SHADOW_QUERY_VALID_OFFSET);
        assertEquals(32, ShaderAbi.SUN_SHADOW_QUERY_BASIS_V_OFFSET);
        assertEquals(0, ShaderAbi.DESCRIPTOR_TLAS);
        assertEquals(2, ShaderAbi.DESCRIPTOR_BLOCK_ATLAS);
        assertEquals(3, ShaderAbi.DESCRIPTOR_STABLE_RADIANCE);
        assertEquals(4, ShaderAbi.DESCRIPTOR_SKY_VIEW);
        assertEquals(5, ShaderAbi.DESCRIPTOR_TRANSMITTANCE_LOW);
        assertEquals(6, ShaderAbi.DESCRIPTOR_TRANSMITTANCE_HIGH);
        assertEquals(7, ShaderAbi.DESCRIPTOR_AERIAL_RADIANCE);
        assertEquals(8, ShaderAbi.DESCRIPTOR_AERIAL_TRANSMITTANCE);
        assertEquals(14, ShaderAbi.DESCRIPTOR_NRD_PRIMARY_POSITION);
        assertEquals(15, ShaderAbi.DESCRIPTOR_NRD_NOISY_SPECULAR);
        assertEquals(16, ShaderAbi.DESCRIPTOR_NRD_SPECULAR_MATERIAL);
        assertEquals(17, ShaderAbi.DESCRIPTOR_TRANSMISSION_GGX_ENERGY);
        assertEquals(18, ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS);
        assertEquals(19, ShaderAbi.DESCRIPTOR_MATERIAL_NORMAL_PAGES);
        assertEquals(49, ShaderAbi.DESCRIPTOR_MATERIAL_OPTICAL_PAGES);
        assertEquals(16, ShaderAbi.MATERIAL_PAGE_COUNT);
        assertEquals(20, ShaderAbi.DESCRIPTOR_NRD_SUN_LIGHTING);
        assertEquals(21, ShaderAbi.DESCRIPTOR_NRD_SUN_PENUMBRA);
        assertEquals(128, ShaderAbi.MAXIMUM_BOUNCES);
        assertEquals(22, ShaderAbi.DESCRIPTOR_NRD_DIFFUSE_DIRECTION);
        assertEquals(23, ShaderAbi.DESCRIPTOR_NRD_SPECULAR_DIRECTION);
        assertEquals(34, ShaderAbi.DESCRIPTOR_STARMAP);
        assertEquals(35, ShaderAbi.DESCRIPTOR_REALTIME_STBN);
        assertEquals(36, ShaderAbi.DESCRIPTOR_WAVEFRONT_PATHS);
        assertEquals(37, ShaderAbi.DESCRIPTOR_WAVEFRONT_QUEUE);
        assertEquals(38, ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_0);
        assertEquals(47, ShaderAbi.DESCRIPTOR_SUN_SHADOW_DEPTH_9);
        assertEquals(48, ShaderAbi.DESCRIPTOR_SUN_SHADOW_QUERY);
        assertEquals(112, ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE);
        assertEquals(2, ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        assertEquals(272, ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE);
        assertEquals(2, ShaderAbi.WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL);
        assertEquals(10, ShaderAbi.WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL);
        assertEquals(7, ShaderAbi.WAVEFRONT_QUEUE_COUNT);
        assertEquals(0, ShaderAbi.WAVEFRONT_TRACE_QUEUE_0);
        assertEquals(1, ShaderAbi.WAVEFRONT_TRACE_QUEUE_1);
        assertEquals(2, ShaderAbi.WAVEFRONT_PRIMARY_QUEUE);
        assertEquals(3, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0);
        assertEquals(2, ShaderAbi.WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1);
        assertEquals(4, ShaderAbi.WAVEFRONT_AREA_QUEUE);
        assertEquals(5, ShaderAbi.WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE);
        assertEquals(6, ShaderAbi.WAVEFRONT_GUIDE_QUEUE);
        assertEquals(16, ShaderAbi.WAVEFRONT_QUEUE_COMMAND_STRIDE);
        assertEquals(4, ShaderAbi.WAVEFRONT_QUEUE_INDEX_SIZE);
        assertEquals(1, ShaderAbi.WAVEFRONT_ACTIVE_MASK);
        assertEquals(0, ShaderAbi.OFFLINE_DESCRIPTOR_RUNNING_MEAN);
        assertEquals(1, ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS);
        assertEquals(2, ShaderAbi.OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE);
        assertEquals(128, ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE);
        assertEquals(1, ShaderAbi.OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL);
        assertEquals(92, ShaderAbi.OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE);
        assertEquals(104, ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE);
        assertEquals(1, ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL);
        assertEquals(2, ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL);
        assertEquals(2, ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COUNT);
        assertEquals(16, ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE);
        assertEquals(4, ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_INDEX_SIZE);
        assertEquals(0xffff, ShaderAbi.PATH_SAMPLE_INDEX_MASK);
        assertEquals(16, ShaderAbi.PATH_SOLAR_LONGITUDE_SHIFT);
        assertEquals(0x1ff, ShaderAbi.PATH_SOLAR_LONGITUDE_MASK);
        assertEquals(0x02000000, ShaderAbi.PATH_SEAMLESS_GLASS_MASK);
        assertEquals(0x04000000, ShaderAbi.PATH_AIR_GAP_MASK);
        assertEquals(0x7fffffff, ShaderAbi.PATH_SAMPLE_EPOCH_MASK);
        assertEquals(0xff, ShaderAbi.PATH_MAXIMUM_BOUNCES_MASK);
        assertEquals(8, ShaderAbi.PATH_LATITUDE_SHIFT);
        assertEquals(0xff, ShaderAbi.PATH_LATITUDE_MASK);
        assertEquals(90, ShaderAbi.PATH_LATITUDE_BIAS);
        assertEquals(0x80000000, ShaderAbi.PATH_CAMERA_IN_WATER_MASK);
        assertEquals(0x1fff, ShaderAbi.PATH_JITTER_PHASE_MASK);
        assertEquals(29, ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_SHIFT);
        assertEquals(0x3, ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_MASK);
        assertEquals(0, ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_NRD);
        assertEquals(1, ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR);
        assertEquals(2, ShaderAbi.PATH_TRANSPARENT_GUIDE_MODE_DISABLED);
        assertEquals(0, ShaderAbi.PATH_SUN_EV_QUARTER_SHIFT);
        assertEquals(8, ShaderAbi.PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT);
        assertEquals(0xff, ShaderAbi.PATH_EV_QUARTER_MASK);
        assertEquals(128, ShaderAbi.PATH_EV_QUARTER_BIAS);
        assertEquals(16, ShaderAbi.PATH_MATERIAL_ROUGHNESS_SHIFT);
        assertEquals(0x7f, ShaderAbi.PATH_MATERIAL_ROUGHNESS_MASK);
        assertEquals(100, ShaderAbi.PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT);
        assertEquals(0x00800000, ShaderAbi.PATH_SH_INPUT_MASK);
        assertEquals(25, ShaderAbi.PATH_STAR_EV_QUARTER_SHIFT);
        assertEquals(0x7f, ShaderAbi.PATH_STAR_EV_QUARTER_MASK);
        assertEquals(32, ShaderAbi.PATH_STAR_EV_QUARTER_BIAS);
        assertEquals(1, ShaderAbi.RUSSIAN_ROULETTE_START);
        assertEquals("linear-rec2020-d65", ShaderAbi.WORKING_COLOR_SPACE);
        assertEquals("srgb", ShaderAbi.TEXTURE_COLOR_ENCODING);
        assertEquals("srgb", ShaderAbi.DISPLAY_COLOR_ENCODING);
        assertEquals("rec709-d65", ShaderAbi.DISPLAY_COLOR_SPACE);
        assertEquals(1.0F, ShaderAbi.DISPLAY_EXPOSURE);
        assertEquals("screen-pixel-2.5d", ShaderAbi.NRD_MOTION_SPACE);
        assertEquals("3.1.4", ShaderAbi.FSR_VERSION);
        assertEquals(
                "normalized-uv-current-to-previous",
                ShaderAbi.FSR_MOTION_SPACE);
        assertEquals("reversed-infinite", ShaderAbi.FSR_DEPTH_SPACE);
        assertEquals(0.05F, ShaderAbi.FSR_NEAR_PLANE);
        assertEquals(1.0F, ShaderAbi.FSR_VIEW_SPACE_TO_METERS_FACTOR);
        assertEquals(
                "dual-reblur-diffuse-specular-sh-plus-sigma-sun-shadow",
                ShaderAbi.NRD_DENOISER);
        assertEquals("hillaire-8wave-rec2020-d65", ShaderAbi.ATMOSPHERE_SPECTRAL_MODEL);
        assertEquals(1.0F, ShaderAbi.ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE);
        assertEquals(
                6_360.0F,
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM);
        assertEquals(
                6_460.0F,
                ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM);
        assertEquals(12.5F, ShaderAbi.ATMOSPHERE_SPACE_SUN_INTENSITY);
        assertEquals(1.5F, ShaderAbi.LEVEL_15_BLOCK_INTENSITY);
        assertEquals(0.00471F, ShaderAbi.ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS);
        assertEquals(-128.0F, ShaderAbi.ATMOSPHERE_WORLD_SEA_LEVEL_Y);
        assertEquals(
                0.001F * ShaderAbi.ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE,
                ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM);
        assertEquals(
                2.048F * ShaderAbi.ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE,
                ShaderAbi.ATMOSPHERE_AERIAL_MAX_DISTANCE_KM);
        assertEquals(128, ShaderAbi.ATMOSPHERE_AERIAL_WIDTH);
        assertEquals(64, ShaderAbi.ATMOSPHERE_AERIAL_HEIGHT);
        assertEquals(128, ShaderAbi.ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES);
        assertEquals(256, ShaderAbi.ATMOSPHERE_AERIAL_EPIPOLAR_SLICES);
        assertEquals(128, ShaderAbi.ATMOSPHERE_AERIAL_DEPTH);
        assertEquals(2, ShaderAbi.ATMOSPHERE_AERIAL_SEGMENT_SAMPLES);
        assertEquals(23.43928F, ShaderAbi.ASTRONOMY_AXIAL_TILT_DEGREES);
        assertEquals(-90, ShaderAbi.ASTRONOMY_MINIMUM_LATITUDE_DEGREES);
        assertEquals(90, ShaderAbi.ASTRONOMY_MAXIMUM_LATITUDE_DEGREES);
        assertEquals(30, ShaderAbi.ASTRONOMY_DEFAULT_LATITUDE_DEGREES);
        assertEquals(0, ShaderAbi.ASTRONOMY_MINIMUM_SOLAR_LONGITUDE_DEGREES);
        assertEquals(359, ShaderAbi.ASTRONOMY_MAXIMUM_SOLAR_LONGITUDE_DEGREES);
        assertEquals(0, ShaderAbi.ASTRONOMY_DEFAULT_SOLAR_LONGITUDE_DEGREES);
        assertEquals(8192, ShaderAbi.STARMAP_WIDTH);
        assertEquals(4096, ShaderAbi.STARMAP_HEIGHT);
        assertEquals(0.025F, ShaderAbi.STARMAP_BASE_RADIANCE_SCALE);
        assertEquals(
                "dc6c4f413e85707a29a25a9451148154554ecca2c996f84fa8f47b65ef9ff7c4",
                ShaderAbi.STARMAP_SOURCE_SHA256);
    }

    @Test
    void generatedOffsetsMatchTheStd430Layout() {
        assertEquals(0, ShaderAbi.PRIMITIVE_UV0_OFFSET);
        assertEquals(12, ShaderAbi.PRIMITIVE_TINT_OFFSET);
        assertEquals(16, ShaderAbi.PRIMITIVE_UNUSED_OFFSET);
        assertEquals(24, ShaderAbi.PRIMITIVE_UV_DENSITY_OFFSET);
        assertEquals(28, ShaderAbi.PRIMITIVE_TANGENT_OFFSET);
        assertEquals(0, ShaderAbi.SECTION_PRIMITIVE_ADDRESS_OFFSET);
        assertEquals(8, ShaderAbi.SECTION_LIGHT_ADDRESS_OFFSET);
        assertEquals(16, ShaderAbi.SECTION_WORLD_LIGHT_ADDRESS_OFFSET);
        assertEquals(24, ShaderAbi.SECTION_CUTOUT_BASE_OFFSET);
        assertEquals(28, ShaderAbi.SECTION_TRANSMISSIVE_BASE_OFFSET);
        assertEquals(32, ShaderAbi.SECTION_WORLD_LIGHT_PATH_OFFSET);
        assertEquals(40, ShaderAbi.SECTION_WORLD_LIGHT_LEAF_ADDRESS_OFFSET);
        assertEquals(48, ShaderAbi.SECTION_TRANSLATION_OFFSET);
        assertEquals(60, ShaderAbi.SECTION_WORLD_LIGHT_LEAF_COUNT_OFFSET);
        assertEquals(64, ShaderAbi.SECTION_INSTANCE_TINT_OFFSET);
        assertEquals(68, ShaderAbi.SECTION_OPAQUE_MACRO_TRIANGLE_BASE_OFFSET);
        assertEquals(72, ShaderAbi.SECTION_CUTOUT_MACRO_TRIANGLE_BASE_OFFSET);
        assertEquals(76, ShaderAbi.SECTION_TRANSMISSIVE_MACRO_TRIANGLE_BASE_OFFSET);
        assertEquals(80, ShaderAbi.SECTION_SURFACE_RELATION_ADDRESS_OFFSET);
        assertEquals(88, ShaderAbi.SECTION_POSITION_ADDRESS_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_NODE_CENTROID_POWER_OFFSET);
        assertEquals(16, ShaderAbi.LIGHT_NODE_DIRECTION_CHILD_RESERVED_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_LEAF_FIRST_ENTRY_OFFSET);
        assertEquals(4, ShaderAbi.LIGHT_LEAF_ENTRY_COUNT_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_LEAF_ENTRY_INDEX_OFFSET);
        assertEquals(4, ShaderAbi.LIGHT_LEAF_ENTRY_POWER_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_EMITTER_CORNER_AREA_OFFSET);
        assertEquals(48, ShaderAbi.LIGHT_EMITTER_NORMAL_PADDING_OFFSET);
        assertEquals(64, ShaderAbi.LIGHT_EMITTER_UVS_TINT_OFFSET);
        assertEquals(80, ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET);
        assertEquals(0, ShaderAbi.LIGHT_CELL_ALIAS_PROBABILITY_OFFSET);
        assertEquals(8, ShaderAbi.LIGHT_CELL_PROBABILITY_MASS_OFFSET);
        assertEquals(4, ShaderAbi.LIGHT_CELL_ALIAS_GEOMETRY_OFFSET);
        assertEquals(0, ShaderAbi.SECTION_LIGHT_HEADER_NODE_ADDRESS_OFFSET);
        assertEquals(8, ShaderAbi.SECTION_LIGHT_HEADER_LEAF_ADDRESS_OFFSET);
        assertEquals(16, ShaderAbi.SECTION_LIGHT_HEADER_ENTRY_ADDRESS_OFFSET);
        assertEquals(24, ShaderAbi.SECTION_LIGHT_HEADER_EMITTER_ADDRESS_OFFSET);
        assertEquals(32, ShaderAbi.SECTION_LIGHT_HEADER_CELL_ADDRESS_OFFSET);
        assertEquals(40, ShaderAbi.SECTION_LIGHT_HEADER_ROOT_OFFSET);
        assertEquals(44, ShaderAbi.SECTION_LIGHT_HEADER_EMITTER_COUNT_OFFSET);
        assertEquals(0, ShaderAbi.INTEGRATOR_SUN_DIRECTION_INTENSITY_OFFSET);
        assertEquals(16, ShaderAbi.INTEGRATOR_ENVIRONMENT_RADIANCE_OFFSET);
        assertEquals(0, ShaderAbi.PATH_STATE_PHYSICAL_ORIGIN_OFFSET);
        assertEquals(12, ShaderAbi.PATH_STATE_PREVIOUS_BSDF_PDF_OFFSET);
        assertEquals(28, ShaderAbi.PATH_STATE_ETA_SCALE_OFFSET);
        assertEquals(44, ShaderAbi.PATH_STATE_CONTROL_OFFSET);
        assertEquals(48, ShaderAbi.PATH_STATE_SOURCE_PRIMITIVE_OFFSET);
        assertEquals(56, ShaderAbi.PATH_STATE_SAMPLE_SEED_OFFSET);
        assertEquals(0, ShaderAbi.TRACE_PAYLOAD_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(28, ShaderAbi.TRACE_PAYLOAD_MATERIAL_CONTROL_OFFSET);
        assertEquals(32, ShaderAbi.TRACE_PAYLOAD_SECTION_INDEX_OFFSET);
        assertEquals(36, ShaderAbi.TRACE_PAYLOAD_EMITTER_INDEX_OFFSET);
        assertEquals(40, ShaderAbi.TRACE_PAYLOAD_TEXTURE_LOD_OFFSET);
        assertEquals(44, ShaderAbi.TRACE_PAYLOAD_OPACITY_OFFSET);
        assertEquals(48, ShaderAbi.TRACE_PAYLOAD_SHADING_NORMAL_OFFSET);
        assertEquals(52, ShaderAbi.TRACE_PAYLOAD_ROUGHNESS_OFFSET);
        assertEquals(56, ShaderAbi.TRACE_PAYLOAD_OPTICAL_CONTROL_OFFSET);
        assertEquals(60, ShaderAbi.TRACE_PAYLOAD_HIT_KIND_OFFSET);
        assertEquals(64, ShaderAbi.TRACE_PAYLOAD_ADJACENT_BASE_COLOR_OFFSET);
        assertEquals(76, ShaderAbi.TRACE_PAYLOAD_ADJACENT_INTERFACE_CONTROL_OFFSET);
        assertEquals(80, ShaderAbi.TRACE_PAYLOAD_POSITION_OFFSET);
        assertEquals(92, ShaderAbi.TRACE_PAYLOAD_POSITION_RESERVED_OFFSET);
        assertEquals(0, ShaderAbi.SURFACE_POSITION_OFFSET);
        assertEquals(16, ShaderAbi.SURFACE_GEOMETRIC_NORMAL_OFFSET);
        assertEquals(44, ShaderAbi.SURFACE_MATERIAL_CONTROL_OFFSET);
        assertEquals(48, ShaderAbi.SURFACE_SECTION_INDEX_OFFSET);
        assertEquals(52, ShaderAbi.SURFACE_EMITTER_INDEX_OFFSET);
        assertEquals(56, ShaderAbi.SURFACE_TEXTURE_LOD_OFFSET);
        assertEquals(60, ShaderAbi.SURFACE_OPACITY_OFFSET);
        assertEquals(64, ShaderAbi.SURFACE_SHADING_NORMAL_OFFSET);
        assertEquals(68, ShaderAbi.SURFACE_ROUGHNESS_OFFSET);
        assertEquals(72, ShaderAbi.SURFACE_OPTICAL_CONTROL_OFFSET);
        assertEquals(80, ShaderAbi.SURFACE_ADJACENT_BASE_COLOR_OFFSET);
        assertEquals(92, ShaderAbi.SURFACE_ADJACENT_INTERFACE_CONTROL_OFFSET);
        assertEquals(0, ShaderAbi.PUSH_INVERSE_VIEW_PROJECTION_OFFSET);
        assertEquals(64, ShaderAbi.PUSH_CAMERA_POSITION_OFFSET);
        assertEquals(76, ShaderAbi.PUSH_ATMOSPHERE_EYE_RADIUS_KM_OFFSET);
        assertEquals(80, ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET);
        assertEquals(88, ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET);
        assertEquals(96, ShaderAbi.PUSH_SUN_DIRECTION_OFFSET);
        assertEquals(108, ShaderAbi.PUSH_RAY_CONE_OFFSET);
        assertEquals(112, ShaderAbi.PUSH_PATH_OFFSET);
    }
}
