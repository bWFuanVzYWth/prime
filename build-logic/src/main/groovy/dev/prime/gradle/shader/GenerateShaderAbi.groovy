package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

abstract class GenerateShaderAbi extends DefaultTask {
	@InputFile
	abstract RegularFileProperty getSchemaFile()

	@OutputDirectory
	abstract DirectoryProperty getJavaOutputDirectory()

	@OutputDirectory
	abstract DirectoryProperty getSlangOutputDirectory()

	@TaskAction
	void generate() {
		def schema = new groovy.json.JsonSlurper().parse(schemaFile.get().asFile)
		def colorContract = schema.colorContract
		def emissionContract = schema.emissionContract
		def atmosphereContract = schema.atmosphereContract
		def astronomyContract = schema.astronomyContract
		def starmapContract = schema.starmapContract
		def realtimeStbnContract = schema.realtimeStbnContract
		def nrdContract = schema.nrdContract
		def fsrContract = schema.fsrContract
		def wavefrontContract = schema.realtimeWavefrontContract
		def offlineWavefrontContract = schema.offlineWavefrontContract
		def textureRecordContract = schema.textureRecordContract
		def materialCoreContract = schema.materialCoreContract

		def schemaSha256 = java.util.HexFormat.of().formatHex(
				java.security.MessageDigest.getInstance('SHA-256')
						.digest(schemaFile.get().asFile.getText('UTF-8')
								.replace('\r\n', '\n').getBytes('UTF-8')))
		if (schemaSha256 != 'ae2777f2b4aceabd25e6b6fe7bc93066fd3bcd70b9242fdb6d82aafc25d9086e') {
			throw new GradleException(
					'Prime shader ABI changed without updating its reviewed contract hash')
		}
		def typeLayout = [
			float: [size: 4, alignment: 4],
			uint: [size: 4, alignment: 4],
			uint64_t: [size: 8, alignment: 8],
			mat4: [size: 64, alignment: 16],
			vec2: [size: 8, alignment: 8],
			vec3: [size: 12, alignment: 16],
			vec4: [size: 16, alignment: 16],
			uvec2: [size: 8, alignment: 8],
			uvec4: [size: 16, alignment: 16]
		]
		def validateStruct = { String name, def definition ->
			int cursor = 0
			int maximumAlignment = 1
			definition.fields.each { field ->
				def layout = typeLayout[field.type]
				if (layout == null) {
					throw new GradleException("Unsupported ABI field type ${field.type}")
				}
				cursor = (cursor + layout.alignment - 1).intdiv(layout.alignment) * layout.alignment
				if (field.offset != cursor) {
					throw new GradleException("${name}.${field.name} must have offset ${cursor}, found ${field.offset}")
				}
				cursor += layout.size
				maximumAlignment = Math.max(maximumAlignment, layout.alignment)
			}
			cursor = (cursor + maximumAlignment - 1).intdiv(maximumAlignment) * maximumAlignment
			if (definition.size != cursor) {
				throw new GradleException("${name} must have size ${cursor}, found ${definition.size}")
			}
		}
		def abiStructs = [
			PRIMITIVE_RECORD: ['PrimitiveRecord', schema.structs.primitiveRecord],
			SECTION_RECORD: ['SectionRecord', schema.structs.sectionRecord],
			LIGHT_NODE: ['LightNode', schema.structs.lightNode],
			LIGHT_LEAF: ['LightLeaf', schema.structs.lightLeaf],
			LIGHT_EMITTER: ['LightEmitter', schema.structs.lightEmitter],
			LIGHT_CELL: ['LightCell', schema.structs.lightCell],
			SECTION_LIGHT_HEADER: ['SectionLightHeader', schema.structs.sectionLightHeader],
			INTEGRATOR_RECORD: ['IntegratorRecord', schema.structs.integratorRecord],
			PATH_STATE: ['PathState', schema.structs.pathState],
			TRACE_PAYLOAD: ['TracePayload', schema.structs.tracePayload],
			SURFACE_INTERACTION: ['SurfaceInteraction', schema.structs.surfaceInteraction],
			WAVEFRONT_SURFACE_RECORD: ['WavefrontSurfaceRecord', schema.structs.wavefrontSurfaceRecord],
			PUSH_CONSTANT: ['PushConstants', schema.structs.pushConstants],
			NRD_MOTION_PUSH_CONSTANT: ['NrdMotionPushConstants', schema.structs.nrdMotionPushConstants],
			SUN_SHADOW_QUERY_CONSTANT: ['SunShadowQueryConstants', schema.structs.sunShadowQueryConstants]
		]
		abiStructs.values().each { name, definition -> validateStruct(name, definition) }

		def constantName = { String value ->
			value.replaceAll('([a-z0-9])([A-Z])', '$1_$2').toUpperCase(java.util.Locale.ROOT)
		}
		def javaOffsets = new StringBuilder()
		def offsetPrefixes = [
			PRIMITIVE: 'PRIMITIVE_RECORD',
			SECTION: 'SECTION_RECORD',
			LIGHT_NODE: 'LIGHT_NODE',
			LIGHT_LEAF: 'LIGHT_LEAF',
			LIGHT_EMITTER: 'LIGHT_EMITTER',
			LIGHT_CELL: 'LIGHT_CELL',
			SECTION_LIGHT_HEADER: 'SECTION_LIGHT_HEADER',
			INTEGRATOR: 'INTEGRATOR_RECORD',
			PATH_STATE: 'PATH_STATE',
			TRACE_PAYLOAD: 'TRACE_PAYLOAD',
			SURFACE: 'SURFACE_INTERACTION',
			WAVEFRONT_SURFACE: 'WAVEFRONT_SURFACE_RECORD',
			PUSH: 'PUSH_CONSTANT',
			NRD_MOTION_PUSH: 'NRD_MOTION_PUSH_CONSTANT',
			SUN_SHADOW_QUERY: 'SUN_SHADOW_QUERY_CONSTANT']
		offsetPrefixes.each { prefix, structName ->
			def definition = abiStructs[structName][1]
			definition.fields.each { field ->
				javaOffsets.append("    public static final int ${prefix}_${constantName(field.name)}_OFFSET = ${field.offset};\n")
			}
		}
		def slangTypes = [
			float: 'float',
			uint: 'uint',
			uint64_t: 'uint64_t',
			mat4: 'float4x4',
			vec2: 'float2',
			vec3: 'float3',
			vec4: 'float4',
			uvec2: 'uint2',
			uvec4: 'uint4'
		]
		def slangStructFields = { def definition ->
			definition.fields.collect { field ->
				def type = slangTypes[field.type]
				if (type == null) {
					throw new GradleException("No Slang ABI type mapping for ${field.type}")
				}
				return "    public ${type} ${field.name};"
			}.join('\n')
		}
		def javaLiteral = { value ->
			if (value instanceof CharSequence) {
				return value.startsWith('0x') ? value : "\"${value}\""
			}
			return value instanceof BigDecimal || value instanceof Double || value instanceof Float
					? "${value}f"
					: value.toString()
		}
		def javaConstants = new StringBuilder()
		def appendJava = { String prefix, Map values, Map renames = [:], Map transforms = [:] ->
			values.each { name, value ->
				String renamed = renames.getOrDefault(name, constantName(name))
				String javaName = prefix.isEmpty() ? renamed : "${prefix}_${renamed}"
				def transformed = transforms.containsKey(name) ? transforms[name](value) : value
				boolean stringValue = transformed instanceof CharSequence
						&& !transformed.startsWith('0x')
				javaConstants.append(
						"    public static final ${stringValue ? 'String' : transformed instanceof BigDecimal || transformed instanceof Double || transformed instanceof Float ? 'float' : 'int'} ${javaName} = ${javaLiteral(transformed)};\n")
			}
		}
		abiStructs.each { prefix, pair ->
			appendJava('', [("${prefix}_SIZE".toString()): pair[1].size])
		}
		appendJava('TEXTURE', textureRecordContract)
		appendJava('MATERIAL_CORE', materialCoreContract)
		appendJava('SURFACE', schema.surfaceRecordContract)
		def shadowDescriptorNames = (0..9).collectEntries {
			[("sunShadowDepth${it}".toString()): "SUN_SHADOW_DEPTH_${it}".toString()]
		}
		appendJava('DESCRIPTOR', schema.sharedDescriptors, shadowDescriptorNames)
		appendJava('DESCRIPTOR', schema.realtimeDescriptors,
				[nrdMaterialClass: 'RECONSTRUCTION_CONTROL'])
		appendJava('OFFLINE_DESCRIPTOR', schema.offlineDescriptors)
		appendJava('', [
			sceneTextureCount: schema.sceneTextureCount,
			materialPageCount: schema.materialPageCount,
			baseColorPageCount: schema.baseColorPageCount])
		appendJava('WAVEFRONT', wavefrontContract, [
			traceQueue0: 'TRACE_QUEUE_0',
			traceQueue1: 'TRACE_QUEUE_1',
			transparentTraceQueue0: 'TRANSPARENT_TRACE_QUEUE_0',
			transparentTraceQueue1: 'TRANSPARENT_TRACE_QUEUE_1'])
		appendJava('OFFLINE_WAVEFRONT', offlineWavefrontContract.findAll {
			it.key != 'activeMask'
		})
		appendJava('PATH', schema.pathControl.findAll {
			!(it.key in ['maximumBounces', 'russianRouletteStart'])
		})
		appendJava('', [
			maximumBounces: schema.pathControl.maximumBounces,
			russianRouletteStart: schema.pathControl.russianRouletteStart,
			cutoutAlphaThreshold: schema.cutoutAlphaThreshold])
		appendJava('', colorContract, [
			workingSpace: 'WORKING_COLOR_SPACE',
			textureEncoding: 'TEXTURE_COLOR_ENCODING',
			displayEncoding: 'DISPLAY_COLOR_ENCODING'])
		appendJava('', emissionContract, [level15BlockIntensity: 'LEVEL_15_BLOCK_INTENSITY'])
		appendJava('LAMBERT', schema.lambertContract)
		appendJava('NRD', nrdContract)
		appendJava('FSR', fsrContract)
		appendJava('ATMOSPHERE', atmosphereContract, [:], [
			worldUnitScaleKm: { it * atmosphereContract.worldToAtmosphereScale },
			aerialMaxDistanceKm: { it * atmosphereContract.worldToAtmosphereScale }])
		appendJava('ASTRONOMY', astronomyContract)
		appendJava('STARMAP', starmapContract)
		appendJava('REALTIME_STBN', realtimeStbnContract)
		def slangConstants = { String prefix, Map values,
				Map renames = [:], Map types = [:], Map transforms = [:] ->
			values.collect { name, value ->
				String renamed = renames.getOrDefault(name, constantName(name))
				String slangName = prefix.isEmpty() ? renamed : "${prefix}_${renamed}"
				def transformed = transforms.containsKey(name) ? transforms[name](value) : value
				String type = types.getOrDefault(
						name,
						transformed instanceof BigDecimal
								|| transformed instanceof Double
								|| transformed instanceof Float ? 'float' : 'uint')
				String separator = transformed.toString().startsWith('\n') ? '' : ' '
				"public static const ${type} PRIME_${slangName} =${separator}${transformed};"
			}.join('\n')
		}
		def slangTypeConstants = new StringBuilder()
		def appendSlangTypes = { String prefix, Map values,
				Map renames = [:], Map types = [:], Map transforms = [:] ->
			slangTypeConstants.append(
					slangConstants(prefix, values, renames, types, transforms)).append('\n')
		}
		def slangImages = { Map descriptors, int set, Map images ->
			images.collect { binding, image ->
				String access = image.size() > 4 ? "${image[4]} " : ''
				"[[vk::binding(${descriptors[binding]}, ${set})]] [[vk::image_format(\"${image[3]}\")]]\n" +
						"public ${access}${image[0]}<${image[1]}> ${image[2]};"
			}.join('\n')
		}
		abiStructs.findAll { it.key != 'WAVEFRONT_SURFACE_RECORD' }.each { prefix, pair ->
			appendSlangTypes('', [("${prefix}_SIZE".toString()): pair[1].size])
		}
		appendSlangTypes('', [sceneTextureCount: schema.sceneTextureCount],
				[sceneTextureCount: 'SCENE_TEXTURE_COUNT'])
		appendSlangTypes('DESCRIPTOR', [
			transmissionGgxEnergy: schema.sharedDescriptors.transmissionGgxEnergy])
		appendSlangTypes('PATH', schema.pathControl.findAll {
			!(it.key in ['historyValidMask', 'maximumBounces', 'russianRouletteStart'])
		}, [:], [
			latitudeBias: 'int',
			evQuarterBias: 'int',
			materialRoughnessStepsPerUnit: 'float',
			starEvQuarterBias: 'int'])
		appendSlangTypes('', [
			maximumBounces: schema.pathControl.maximumBounces,
			russianRouletteStart: schema.pathControl.russianRouletteStart,
			cutoutAlphaThreshold: schema.cutoutAlphaThreshold,
			level15BlockIntensity: emissionContract.level15BlockIntensity,
			displayExposure: colorContract.displayExposure], [
			level15BlockIntensity: 'LEVEL_15_BLOCK_INTENSITY'])
		appendSlangTypes('ASTRONOMY', [axialTiltRadians: astronomyContract.axialTiltDegrees],
				[:], [axialTiltRadians: 'float'],
				[axialTiltRadians: { "\n        ${it} * 0.017453292519943295" }])
		appendSlangTypes('ATMOSPHERE', atmosphereContract.findAll {
			it.key != 'spectralModel'
		}, [:], [:], [
			worldUnitScaleKm: { it * atmosphereContract.worldToAtmosphereScale },
			aerialMaxDistanceKm: { it * atmosphereContract.worldToAtmosphereScale }])
		appendSlangTypes('STARMAP', starmapContract.findAll {
			it.key != 'sourceSha256'
		})
		def slangStructs = abiStructs.findAll {
			it.key != 'WAVEFRONT_SURFACE_RECORD'
		}.collect { prefix, pair ->
			String name = prefix == 'PUSH_CONSTANT' ? 'PrimePushConstants' : pair[0]
			"public struct ${name}\n{\n${slangStructFields(pair[1])}\n};"
		}.join('\n\n')
		def environmentImages = slangImages(schema.sharedDescriptors, 0, [
			skyView: ['RWTexture2D', 'float4', 'primeSkyView', 'rgba16f', 'readonly'],
			cameraTransmittance: ['RWTexture2D', 'float4', 'primeCameraTransmittance', 'rgba16f', 'readonly'],
			aerialRadiance: ['RWTexture3D', 'float4', 'primeAerialRadiance', 'rgba16f', 'readonly'],
			aerialTransmittance: ['RWTexture3D', 'float4', 'primeAerialTransmittance', 'rgba16f', 'readonly']])
		def sunShadowImages = slangImages(schema.sharedDescriptors, 0,
				(0..9).collectEntries {
					[("sunShadowDepth${it}".toString()):
							['RWTexture2D', 'float', "primeSunShadowDepth${it}".toString(), 'r32f']]
				})
		def realtimeImages = slangImages(schema.realtimeDescriptors, 1, [
			stableRadiance: ['RWTexture2D', 'float4', 'primeStableRadiance', 'rgba32f'],
			nrdNoisyDiffuse: ['RWTexture2D', 'float4', 'primeNrdNoisyDiffuse', 'rgba16f'],
			nrdNoisySpecular: ['RWTexture2D', 'float4', 'primeNrdNoisySpecular', 'rgba16f'],
			nrdNormalRoughness: ['RWTexture2D', 'float4', 'primeNrdNormalRoughness', 'rgba32f'],
			nrdViewZ: ['RWTexture2D', 'float', 'primeNrdViewZ', 'r32f'],
			wavefrontTransportMetadata: ['RWTexture2D', 'float4', 'primeWavefrontTransportMetadata', 'rgba16f'],
			nrdMaterial: ['RWTexture2D', 'float4', 'primeNrdMaterial', 'rgba16f'],
			nrdSpecularMaterial: ['RWTexture2D', 'float4', 'primeNrdSpecularMaterial', 'rgba16f'],
			nrdMaterialClass: ['RWTexture2D', 'uint', 'primeReconstructionControl', 'r8ui'],
			nrdPrimaryPosition: ['RWTexture2D', 'float4', 'primeNrdPrimaryPosition', 'rgba32f'],
			nrdSunLighting: ['RWTexture2D', 'float4', 'primeNrdSunLighting', 'rgba16f'],
			nrdSunPenumbra: ['RWTexture2D', 'float', 'primeNrdSunPenumbra', 'r16f'],
			nrdDiffuseDirection: ['RWTexture2D', 'float4', 'primeNrdDiffuseDirection', 'rgba16f'],
			nrdSpecularDirection: ['RWTexture2D', 'float4', 'primeNrdSpecularDirection', 'rgba16f'],
			nrdReflectionNoisyDiffuse: ['RWTexture2D', 'float4', 'primeNrdReflectionNoisyDiffuse', 'rgba16f'],
			nrdReflectionNoisySpecular: ['RWTexture2D', 'float4', 'primeNrdReflectionNoisySpecular', 'rgba16f'],
			nrdReflectionNormalRoughness: ['RWTexture2D', 'float4', 'primeNrdReflectionNormalRoughness', 'rgba32f'],
			nrdReflectionMaterial: ['RWTexture2D', 'float4', 'primeNrdReflectionMaterial', 'rgba16f'],
			nrdReflectionSpecularMaterial: ['RWTexture2D', 'float4', 'primeNrdReflectionSpecularMaterial', 'rgba16f'],
			nrdReflectionPosition: ['RWTexture2D', 'float4', 'primeNrdReflectionPosition', 'rgba32f'],
			nrdReflectionDiffuseDirection: ['RWTexture2D', 'float4', 'primeNrdReflectionDiffuseDirection', 'rgba16f'],
			nrdReflectionSpecularDirection: ['RWTexture2D', 'float4', 'primeNrdReflectionSpecularDirection', 'rgba16f'],
			nrdDisplayPosition: ['RWTexture2D', 'float4', 'primeNrdDisplayPosition', 'rgba32f']])
		def realtimeConstants = slangConstants('', [rendererDescriptorSet: 1],
				[rendererDescriptorSet: 'RENDERER_DESCRIPTOR_SET']) + '\n' +
				slangConstants('DESCRIPTOR', [
					wavefrontPaths: schema.realtimeDescriptors.wavefrontPaths,
					wavefrontQueue: schema.realtimeDescriptors.wavefrontQueue]) + '\n' +
				slangConstants('WAVEFRONT', wavefrontContract.findAll {
					it.key in ['pathRecordSize', 'etaScaleOffset', 'pathControlReservedMask',
							'pathSlotsPerPixel', 'areaGuideRecordSize']
				}, [
					traceQueue0: 'TRACE_QUEUE_0',
					traceQueue1: 'TRACE_QUEUE_1',
					transparentTraceQueue0: 'TRANSPARENT_TRACE_QUEUE_0',
					transparentTraceQueue1: 'TRANSPARENT_TRACE_QUEUE_1']) + '\n' +
				slangConstants('WAVEFRONT', [
					surfaceRecordSize: abiStructs.WAVEFRONT_SURFACE_RECORD[1].size]) + '\n' +
				slangConstants('WAVEFRONT', wavefrontContract.findAll {
					!(it.key in ['pathRecordSize', 'etaScaleOffset', 'pathControlReservedMask',
							'pathSlotsPerPixel', 'areaGuideRecordSize'])
				}, [
					traceQueue0: 'TRACE_QUEUE_0',
					traceQueue1: 'TRACE_QUEUE_1',
					transparentTraceQueue0: 'TRANSPARENT_TRACE_QUEUE_0',
					transparentTraceQueue1: 'TRANSPARENT_TRACE_QUEUE_1'])
		def offlineConstants = slangConstants('', [rendererDescriptorSet: 1],
				[rendererDescriptorSet: 'RENDERER_DESCRIPTOR_SET']) + '\n' +
				slangConstants('DESCRIPTOR', schema.offlineDescriptors.findAll {
					it.key != 'runningMean'
				}) + '\n' +
				slangConstants('WAVEFRONT', offlineWavefrontContract.findAll {
					it.key in ['pathRecordSize', 'pathSlotsPerPixel']
				}) + '\n' +
				slangConstants('OFFLINE', offlineWavefrontContract.findAll {
					it.key in ['surfaceRecordSize', 'stagedLightRecordSize', 'stageRecordSize']
				}, [
					surfaceRecordSize: 'WAVEFRONT_SURFACE_RECORD_SIZE',
					stagedLightRecordSize: 'STAGED_LIGHT_RECORD_SIZE',
					stageRecordSize: 'WAVEFRONT_STAGE_RECORD_SIZE']) + '\n' +
				slangConstants('WAVEFRONT', offlineWavefrontContract.findAll {
					it.key in ['queueEntriesPerPixel', 'queueStorageEntriesPerPixel',
							'queueCount', 'queueCommandStride', 'queueIndexSize', 'activeMask']
				})

		def javaPackageDir = new File(javaOutputDirectory.get().asFile, 'dev/prime/render/shader')
		javaPackageDir.mkdirs()
		new File(javaPackageDir, 'ShaderAbi.java').text = """\
package dev.prime.render.shader;

/** Generated from shaders/abi.json. Do not edit by hand. */
public final class ShaderAbi {
${javaConstants}${javaOffsets}

    private ShaderAbi() {
    }
}
"""


		def slangDir = slangOutputDirectory.get().asFile
		slangDir.mkdirs()
		new File(slangDir, 'prime_surface_abi.slang').text = """\
#language slang 2026
module "prime_surface_abi.slang";

// Generated from shaders/abi.json. Do not edit by hand.
${slangConstants('SURFACE', schema.surfaceRecordContract)}
public static const uint PRIME_SURFACE_RECORDS_BINDING = ${schema.sharedDescriptors.surfaceRecords};
"""
		new File(slangDir, 'prime_material_core_abi.slang').text = """\
#language slang 2026
module "prime_material_core_abi.slang";

// Generated from shaders/abi.json. Do not edit by hand.
${slangConstants('MATERIAL_CORE', materialCoreContract)}
"""
		new File(slangDir, 'prime_abi_bindings.slang').text = """\
#language slang 2026
module "prime_abi_bindings.slang";

// Generated from shaders/abi.json. Do not edit by hand.
public static const uint PRIME_TRANSMISSION_GGX_ENERGY_BINDING = ${schema.sharedDescriptors.transmissionGgxEnergy};
public static const uint PRIME_REALTIME_STBN_BINDING = ${schema.sharedDescriptors.realtimeStbn};
public static const uint PRIME_REALTIME_STBN_WIDTH = ${realtimeStbnContract.width};
public static const uint PRIME_REALTIME_STBN_HEIGHT = ${realtimeStbnContract.height};
public static const uint PRIME_REALTIME_STBN_DEPTH = ${realtimeStbnContract.depth};
public static const uint PRIME_REALTIME_STBN_BANK_COUNT = ${realtimeStbnContract.bankCount};
"""
		new File(slangDir, 'prime_abi_types.slang').text = """\
#language slang 2026
module "prime_abi_types.slang";

// Generated from shaders/abi.json. Do not edit by hand.
${slangTypeConstants}
${slangStructs}

"""
		new File(slangDir, 'prime_abi.slang').text = """\
#language slang 2026
module "prime_abi.slang";

import "prime_abi_types.slang";

public [[vk::push_constant]] ConstantBuffer<PrimePushConstants> primePush;

[[vk::binding(${schema.sharedDescriptors.sunShadowQuery}, 0)]]
public ConstantBuffer<SunShadowQueryConstants> primeSunShadowQuery;

[[vk::binding(${schema.sharedDescriptors.tlas}, 0)]]
public RaytracingAccelerationStructure primeScene;
[[vk::binding(${schema.sharedDescriptors.blockAtlas}, 0)]]
public Sampler2D<float4> primeSceneTextures[PRIME_SCENE_TEXTURE_COUNT];

${environmentImages}
${sunShadowImages}
[[vk::binding(${schema.sharedDescriptors.starmap}, 0)]]
public Sampler2D<float4> primeStarmap;
"""
		new File(slangDir, 'prime_fsr_contract.slang').text = """\
#language slang 2026
module "prime_fsr_contract.slang";

// Generated from shaders/abi.json. FSR host parameters and shader guides share these values.
public static const float PRIME_FSR_NEAR_PLANE = ${fsrContract.nearPlane};
public static const float PRIME_FSR_VIEW_SPACE_TO_METERS_FACTOR = ${fsrContract.viewSpaceToMetersFactor};
"""
		new File(slangDir, 'prime_nrd_motion_contract.slang').text = """\
#language slang 2026
module "prime_nrd_motion_contract.slang";

import "prime_abi_types.slang";

// Generated from shaders/abi.json. The Vulkan range and shader block share this layout.
public [[vk::push_constant]] ConstantBuffer<NrdMotionPushConstants> primeMotionPush;
"""
		new File(slangDir, 'prime_lambert_contract.slang').text = """\
#language slang 2026
module "prime_lambert_contract.slang";

${slangConstants('LAMBERT', schema.lambertContract)}
"""
		new File(slangDir, 'prime_realtime_abi.slang').text = """\
#language slang 2026
module "prime_realtime_abi.slang";

import "prime_abi_types.slang";

${realtimeConstants}

public uint primeRealtimeQueueCapacity(uint pixelCount, uint queue) {
    bool wide = queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            || queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            || queue == PRIME_WAVEFRONT_AREA_QUEUE
            || queue == PRIME_WAVEFRONT_TRACE_QUEUE_0;
    return pixelCount * (wide ? PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL : 1u);
}

public uint primeRealtimeQueueCommandWord(uint pixelCount, uint queue) {
    return pixelCount * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u)
            + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

public uint primeRealtimeQueueWord(uint pixelCount, uint queue, uint entry) {
    uint base = primeRealtimeQueueCommandWord(
            pixelCount, PRIME_WAVEFRONT_QUEUE_COUNT);
    // Primary/transparent-1 share slot 2 after primary consumption. Initial area, serialized
    // guide, and tail area share slot 6; dispatch barriers order all three lifetimes.
    uint slot = queue == PRIME_WAVEFRONT_TRACE_QUEUE_0 ? 0u
            : queue == PRIME_WAVEFRONT_TRACE_QUEUE_1 ? 1u
            : queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 ? 2u
            : queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 ? 4u
            : queue == PRIME_WAVEFRONT_AREA_QUEUE
                    || queue == PRIME_WAVEFRONT_GUIDE_QUEUE ? 6u
            : 8u;
    return base + slot * pixelCount + entry;
}

${realtimeImages}

public float4 primeImageLoad(RWTexture2D<float4> image, int2 coordinate)
{
    return image[coordinate];
}

public float4 primeImageLoad(RWTexture2D<float> image, int2 coordinate)
{
    return float4(image[coordinate], 0.0, 0.0, 0.0);
}

public void primeImageStore(RWTexture2D<float4> image, int2 coordinate, float4 value)
{
    image[coordinate] = value;
}

public void primeImageStore(RWTexture2D<float> image, int2 coordinate, float4 value)
{
    image[coordinate] = value.x;
}

public uint primeImageLoad(RWTexture2D<uint> image, int2 coordinate)
{
    return image[coordinate];
}

public void primeImageStore(RWTexture2D<uint> image, int2 coordinate, uint value)
{
    image[coordinate] = value;
}

public void primeImageStoreWriteOnly(
        writeonly RWTexture2D<float4> image, int2 coordinate, float4 value)
{
    image[coordinate] = value;
}
"""
		new File(slangDir, 'prime_offline_abi.slang').text = """\
#language slang 2026
module "prime_offline_abi.slang";

import "prime_abi_types.slang";

${offlineConstants}

public struct PrimeOfflineTransportRecord {
    public uint4 physicalOriginAndPreviousBsdfPdf;
    public uint4 sourceStateAndMediumIds;
    public uint4 rayDirectionAndEtaScale;
    public uint4 throughputAndPreviousLightNormalX;
    public uint4 medium0AndPreviousLightNormalY;
    public uint4 medium1AndPreviousLightNormalZ;
};

[[vk::binding(${schema.offlineDescriptors.runningMean}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeOfflineRunningMean;

public float4 primeImageLoad(RWTexture2D<float4> image, int2 coordinate)
{
    return image[coordinate];
}

public void primeImageStore(RWTexture2D<float4> image, int2 coordinate, float4 value)
{
    image[coordinate] = value;
}
"""
	}
}
