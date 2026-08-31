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
		def primitive = schema.structs.primitiveRecord
		def section = schema.structs.sectionRecord
		def lightNode = schema.structs.lightNode
		def lightLeaf = schema.structs.lightLeaf
		def lightLeafEntry = schema.structs.lightLeafEntry
		def lightEmitter = schema.structs.lightEmitter
		def lightCell = schema.structs.lightCell
		def sectionLightHeader = schema.structs.sectionLightHeader
		def integrator = schema.structs.integratorRecord
		def pathState = schema.structs.pathState
		def tracePayload = schema.structs.tracePayload
		def surfaceInteraction = schema.structs.surfaceInteraction
		def wavefrontSurfaceRecord = schema.structs.wavefrontSurfaceRecord
		def push = schema.structs.pushConstants
		def nrdMotionPush = schema.structs.nrdMotionPushConstants
		def sunShadowQuery = schema.structs.sunShadowQueryConstants
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
		def materialCoreContract = schema.materialCoreContract

		if (primitive.size != 32 || section.size != 96 || lightNode.size != 32
				|| lightLeaf.size != 8 || lightLeafEntry.size != 8
				|| lightEmitter.size != 96 || lightCell.size != 12
				|| sectionLightHeader.size != 48
				|| integrator.size != 32 || pathState.size != 80 || tracePayload.size != 112
				|| surfaceInteraction.size != 112 || wavefrontSurfaceRecord.size != 112
				|| push.size != 128
				|| nrdMotionPush.size != 144
				|| sunShadowQuery.size != 48
				|| schema.sceneTextureCount != 64
				|| schema.materialPageCount != 16
				|| schema.baseColorPageCount != 64
				|| schema.sharedDescriptors.textureRecords != 18
				|| schema.sharedDescriptors.materialNormalPages != 19
				|| schema.sharedDescriptors.materialOpticalPages != 49
				|| schema.sharedDescriptors.tintOperators != 50
				|| schema.sharedDescriptors.baseColorPages != 51
				|| schema.sharedDescriptors.materialCoreRecords != 52
				|| materialCoreContract.recordSize != 4
				|| !materialCoreContract.textureIdMask.toString().equalsIgnoreCase('0xffff')
				|| materialCoreContract.recipeControlShift != 16
				|| !materialCoreContract.recipeControlMask.toString().equalsIgnoreCase('0xffff')) {
			throw new GradleException(
					'Prime shader ABI sizes or scene texture count changed without a coordinated migration')
		}
		if (colorContract.workingSpace != 'linear-rec2020-d65'
				|| colorContract.textureEncoding != 'srgb'
				|| colorContract.displayEncoding != 'srgb'
				|| colorContract.displayColorSpace != 'rec709-d65'
				|| colorContract.displayExposure != 1.0) {
			throw new GradleException(
					'Prime color contract must remain sRGB input -> linear Rec.2020 D65 -> Reinhard-Gamut -> sRGB Rec.709 display')
		}
		if (emissionContract.level15BlockIntensity != 1.5) {
			throw new GradleException(
					'Prime emission contract must preserve a 1.5-unit white level-15 block source')
		}
		if (atmosphereContract.spectralModel != 'hillaire-8wave-rec2020-d65'
				|| atmosphereContract.worldToAtmosphereScale <= 0.0
				|| atmosphereContract.worldSeaLevelY != -128.0
				|| atmosphereContract.worldUnitScaleKm != 0.001
				|| atmosphereContract.spaceSunIntensity != 12.5
				|| atmosphereContract.sunAngularRadiusRadians != 0.00471) {
			throw new GradleException(
					'Prime atmosphere contract must remain the eight-wave Rec.2020 model, '
							+ 'the -128 virtual ground with positive world-to-atmosphere scale, '
							+ 'and a 12.5-unit space sun')
		}
		if (starmapContract.width != 8192
				|| starmapContract.height != 4096
				|| starmapContract.baseRadianceScale != 0.025
				|| starmapContract.sourceSha256 != 'dc6c4f413e85707a29a25a9451148154554ecca2c996f84fa8f47b65ef9ff7c4') {
			throw new GradleException(
					'Prime starmap contract must preserve the full NASA source and its calibrated radiance baseline')
		}
		if (schema.sharedDescriptors.realtimeStbn != 35
				|| realtimeStbnContract.width != 128
				|| realtimeStbnContract.height != 128
				|| realtimeStbnContract.depth != 64
				|| realtimeStbnContract.bankCount != 3
				|| realtimeStbnContract.channels != 2
				|| realtimeStbnContract.channelBits != 16
				|| !(realtimeStbnContract.resourceSha256 ==~ /[0-9a-f]{64}/)) {
			throw new GradleException(
					'Prime realtime STBN contract must remain three 128x128x64 RG16UI banks')
		}
		if (astronomyContract.axialTiltDegrees != 23.43928
				|| astronomyContract.minimumLatitudeDegrees != -90
				|| astronomyContract.maximumLatitudeDegrees != 90
				|| astronomyContract.defaultLatitudeDegrees != 30
				|| astronomyContract.minimumSolarLongitudeDegrees != 0
				|| astronomyContract.maximumSolarLongitudeDegrees != 359
				|| astronomyContract.defaultSolarLongitudeDegrees != 0) {
			throw new GradleException(
					'Prime astronomy contract must preserve the axial tilt and complete integer-degree control domains')
		}
		if (nrdContract.version != '4.17.4'
                || nrdContract.denoiser != 'dual-reblur-diffuse-specular-sh-plus-sigma-sun-shadow'
				|| nrdContract.normalEncoding != 'rgba32-sfloat-direct'
				|| nrdContract.roughnessEncoding != 'linear'
				|| nrdContract.motionSpace != 'screen-pixel-2.5d'
				|| nrdContract.signalSpace != 'demodulated-linear-rec2020-d65') {
			throw new GradleException('Prime NRD signal and native-library contracts changed without a coordinated migration')
		}
		if (fsrContract.version != '3.1.4'
				|| fsrContract.motionSpace != 'normalized-uv-current-to-previous'
				|| fsrContract.depthSpace != 'reversed-infinite'
				|| fsrContract.nearPlane != 0.05
				|| fsrContract.viewSpaceToMetersFactor != 1.0) {
			throw new GradleException('Prime FSR signal and projection contracts changed without a coordinated migration')
		}
		if (wavefrontContract.pathRecordSize != 144
				|| wavefrontContract.etaScaleOffset != 108
				|| !wavefrontContract.pathControlReservedMask.toString().equalsIgnoreCase('0x00ffff00')
				|| wavefrontContract.pathSlotsPerPixel != 2
				|| wavefrontContract.areaRecordSize != 320
				|| wavefrontContract.areaRecordSize
						!= 32 + (wavefrontSurfaceRecord.size + 12)
								* wavefrontContract.pathSlotsPerPixel
								+ 40
				|| wavefrontContract.queueEntriesPerPixel != 2
				|| wavefrontContract.queueStorageEntriesPerPixel != 10
				|| wavefrontContract.queueCount != 7
				|| wavefrontContract.traceQueue0 != 0
				|| wavefrontContract.traceQueue1 != 1
				|| wavefrontContract.primaryQueue != 2
				|| wavefrontContract.transparentTraceQueue0 != 3
				|| wavefrontContract.transparentTraceQueue1 != 2
				|| wavefrontContract.areaQueue != 4
				|| wavefrontContract.transparentResolveQueue != 5
				|| wavefrontContract.guideQueue != 6
				|| wavefrontContract.queueCommandStride != 16
				|| wavefrontContract.queueIndexSize != 4
				|| !wavefrontContract.activeMask.toString().equalsIgnoreCase('0x1')) {
			throw new GradleException(
					'Prime realtime scheduler must preserve its execution-mode queue ABI')
		}
		if (offlineWavefrontContract.pathRecordSize != 144
				|| offlineWavefrontContract.pathSlotsPerPixel != 1
				|| offlineWavefrontContract.surfaceRecordSize != 108
				|| offlineWavefrontContract.stageRecordSize != 112
				|| offlineWavefrontContract.stageRecordSize
						!= offlineWavefrontContract.surfaceRecordSize + 4
				|| offlineWavefrontContract.queueEntriesPerPixel != 1
				|| offlineWavefrontContract.queueStorageEntriesPerPixel != 2
				|| offlineWavefrontContract.queueCount != 2
				|| offlineWavefrontContract.queueCommandStride != 16
				|| offlineWavefrontContract.queueIndexSize != 4
				|| !offlineWavefrontContract.activeMask.toString().equalsIgnoreCase('0x1')) {
			throw new GradleException(
					'Prime offline scheduler must preserve its single-slot four-stage queue ABI')
		}
		if (!schema.pathControl.sampleEpochMask.toString().equalsIgnoreCase('0x7fffffff')
				|| !schema.pathControl.historyValidMask.toString().equalsIgnoreCase('0x80000000')
				|| !schema.pathControl.seamlessGlassMask.toString().equalsIgnoreCase('0x02000000')
				|| !schema.pathControl.airGapMask.toString().equalsIgnoreCase('0x04000000')
				|| !schema.pathControl.vanillaPbrPresetsMask.toString().equalsIgnoreCase('0x08000000')
				|| !schema.pathControl.transparentNeeUnbiasedMask.toString().equalsIgnoreCase('0x10000000')
				|| !schema.pathControl.cameraInWaterMask.toString().equalsIgnoreCase('0x80000000')
				|| !schema.pathControl.jitterPhaseMask.toString().equalsIgnoreCase('0x1fff')
				|| schema.pathControl.transparentGuideModeShift != 29
				|| !schema.pathControl.transparentGuideModeMask.toString().equalsIgnoreCase('0x3')
				|| schema.pathControl.transparentGuideModeNrd != 0
				|| schema.pathControl.transparentGuideModeDlssRr != 1
				|| schema.pathControl.transparentGuideModeDisabled != 2
				|| schema.pathControl.sunEvQuarterShift != 0
				|| schema.pathControl.blockLightEvQuarterShift != 8
				|| !schema.pathControl.evQuarterMask.toString().equalsIgnoreCase('0xff')
				|| schema.pathControl.evQuarterBias != 128
				|| schema.pathControl.materialRoughnessShift != 16
				|| !schema.pathControl.materialRoughnessMask.toString().equalsIgnoreCase('0x7f')
				|| schema.pathControl.materialRoughnessStepsPerUnit != 100
				|| !schema.pathControl.shInputMask.toString().equalsIgnoreCase('0x00800000')
				|| schema.pathControl.starEvQuarterShift != 25
				|| !schema.pathControl.starEvQuarterMask.toString().equalsIgnoreCase('0x7f')
				|| schema.pathControl.starEvQuarterBias != 32
				|| schema.pathControl.maximumBounces != 128
				|| schema.pathControl.russianRouletteStart != 1) {
			throw new GradleException(
					'Prime path controls must preserve sampling, camera, lighting, material, jitter, and one-guaranteed-continuation roulette ABI')
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
		validateStruct('PrimitiveRecord', primitive)
		validateStruct('SectionRecord', section)
		validateStruct('LightNode', lightNode)
		validateStruct('LightLeaf', lightLeaf)
		validateStruct('LightLeafEntry', lightLeafEntry)
		validateStruct('LightEmitter', lightEmitter)
		validateStruct('LightCell', lightCell)
		validateStruct('SectionLightHeader', sectionLightHeader)
		validateStruct('IntegratorRecord', integrator)
		validateStruct('PathState', pathState)
		validateStruct('TracePayload', tracePayload)
		validateStruct('SurfaceInteraction', surfaceInteraction)
		validateStruct('WavefrontSurfaceRecord', wavefrontSurfaceRecord)
		validateStruct('PushConstants', push)
		validateStruct('NrdMotionPushConstants', nrdMotionPush)
		validateStruct('SunShadowQueryConstants', sunShadowQuery)

		def constantName = { String value ->
			value.replaceAll('([a-z0-9])([A-Z])', '$1_$2').toUpperCase(java.util.Locale.ROOT)
		}
		def javaOffsets = new StringBuilder()
		[
			PRIMITIVE: primitive,
			SECTION: section,
			LIGHT_NODE: lightNode,
			LIGHT_LEAF: lightLeaf,
			LIGHT_LEAF_ENTRY: lightLeafEntry,
			LIGHT_EMITTER: lightEmitter,
			LIGHT_CELL: lightCell,
			SECTION_LIGHT_HEADER: sectionLightHeader,
			INTEGRATOR: integrator,
			PATH_STATE: pathState,
			TRACE_PAYLOAD: tracePayload,
			SURFACE: surfaceInteraction,
			WAVEFRONT_SURFACE: wavefrontSurfaceRecord,
			PUSH: push,
			NRD_MOTION_PUSH: nrdMotionPush,
			SUN_SHADOW_QUERY: sunShadowQuery
		].each { prefix, definition ->
			definition.fields.each { field ->
				javaOffsets.append("    public static final int ${prefix}_${constantName(field.name)}_OFFSET = ${field.offset};\n")
			}
		}
		def glslStructFields = { def definition ->
			definition.fields.collect { field -> "    ${field.type} ${field.name};" }.join('\n')
		}
		def glslPushFields = push.fields.collect { field ->
			"    layout(offset = ${field.offset}) ${field.type} ${field.name};"
		}.join('\n')
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

		def javaPackageDir = new File(javaOutputDirectory.get().asFile, 'dev/prime/render/shader')
		javaPackageDir.mkdirs()
		new File(javaPackageDir, 'ShaderAbi.java').text = """\
package dev.prime.render.shader;

/** Generated from shaders/abi.json. Do not edit by hand. */
public final class ShaderAbi {
    public static final int PRIMITIVE_RECORD_SIZE = ${primitive.size};
    public static final int SECTION_RECORD_SIZE = ${section.size};
    public static final int LIGHT_NODE_SIZE = ${lightNode.size};
    public static final int LIGHT_LEAF_SIZE = ${lightLeaf.size};
    public static final int LIGHT_LEAF_ENTRY_SIZE = ${lightLeafEntry.size};
    public static final int LIGHT_EMITTER_SIZE = ${lightEmitter.size};
    public static final int LIGHT_CELL_SIZE = ${lightCell.size};
    public static final int SECTION_LIGHT_HEADER_SIZE = ${sectionLightHeader.size};
    public static final int INTEGRATOR_RECORD_SIZE = ${integrator.size};
    public static final int PATH_STATE_SIZE = ${pathState.size};
    public static final int TRACE_PAYLOAD_SIZE = ${tracePayload.size};
    public static final int SURFACE_INTERACTION_SIZE = ${surfaceInteraction.size};
    public static final int WAVEFRONT_SURFACE_RECORD_SIZE = ${wavefrontSurfaceRecord.size};
    public static final int PUSH_CONSTANT_SIZE = ${push.size};
    public static final int NRD_MOTION_PUSH_CONSTANT_SIZE = ${nrdMotionPush.size};
    public static final int SUN_SHADOW_QUERY_CONSTANT_SIZE = ${sunShadowQuery.size};
    public static final int MATERIAL_CORE_RECORD_SIZE = ${materialCoreContract.recordSize};
    public static final int MATERIAL_CORE_TEXTURE_ID_MASK = ${materialCoreContract.textureIdMask};
    public static final int MATERIAL_CORE_RECIPE_CONTROL_SHIFT = ${materialCoreContract.recipeControlShift};
    public static final int MATERIAL_CORE_RECIPE_CONTROL_MASK = ${materialCoreContract.recipeControlMask};
    public static final int DESCRIPTOR_TLAS = ${schema.sharedDescriptors.tlas};
    public static final int DESCRIPTOR_BLOCK_ATLAS = ${schema.sharedDescriptors.blockAtlas};
    public static final int DESCRIPTOR_STABLE_RADIANCE = ${schema.realtimeDescriptors.stableRadiance};
    public static final int DESCRIPTOR_SKY_VIEW = ${schema.sharedDescriptors.skyView};
    public static final int DESCRIPTOR_TRANSMITTANCE_LOW = ${schema.sharedDescriptors.transmittanceLow};
    public static final int DESCRIPTOR_TRANSMITTANCE_HIGH = ${schema.sharedDescriptors.transmittanceHigh};
    public static final int DESCRIPTOR_AERIAL_RADIANCE = ${schema.sharedDescriptors.aerialRadiance};
    public static final int DESCRIPTOR_AERIAL_TRANSMITTANCE = ${schema.sharedDescriptors.aerialTransmittance};
    public static final int DESCRIPTOR_NRD_NOISY_DIFFUSE = ${schema.realtimeDescriptors.nrdNoisyDiffuse};
    public static final int DESCRIPTOR_NRD_NORMAL_ROUGHNESS = ${schema.realtimeDescriptors.nrdNormalRoughness};
    public static final int DESCRIPTOR_NRD_VIEW_Z = ${schema.realtimeDescriptors.nrdViewZ};
    public static final int DESCRIPTOR_WAVEFRONT_TRANSPORT_METADATA = ${schema.realtimeDescriptors.wavefrontTransportMetadata};
    public static final int DESCRIPTOR_NRD_MATERIAL = ${schema.realtimeDescriptors.nrdMaterial};
    public static final int DESCRIPTOR_NRD_PRIMARY_POSITION = ${schema.realtimeDescriptors.nrdPrimaryPosition};
    public static final int DESCRIPTOR_NRD_NOISY_SPECULAR = ${schema.realtimeDescriptors.nrdNoisySpecular};
    public static final int DESCRIPTOR_NRD_SPECULAR_MATERIAL = ${schema.realtimeDescriptors.nrdSpecularMaterial};
    public static final int DESCRIPTOR_RECONSTRUCTION_CONTROL = ${schema.realtimeDescriptors.nrdMaterialClass};
    public static final int DESCRIPTOR_TRANSMISSION_GGX_ENERGY = ${schema.sharedDescriptors.transmissionGgxEnergy};
    public static final int DESCRIPTOR_TEXTURE_RECORDS = ${schema.sharedDescriptors.textureRecords};
    public static final int DESCRIPTOR_MATERIAL_NORMAL_PAGES = ${schema.sharedDescriptors.materialNormalPages};
    public static final int DESCRIPTOR_MATERIAL_OPTICAL_PAGES = ${schema.sharedDescriptors.materialOpticalPages};
    public static final int DESCRIPTOR_TINT_OPERATORS = ${schema.sharedDescriptors.tintOperators};
    public static final int DESCRIPTOR_BASE_COLOR_PAGES = ${schema.sharedDescriptors.baseColorPages};
    public static final int DESCRIPTOR_MATERIAL_CORE_RECORDS = ${schema.sharedDescriptors.materialCoreRecords};
    public static final int DESCRIPTOR_NRD_SUN_LIGHTING = ${schema.realtimeDescriptors.nrdSunLighting};
    public static final int DESCRIPTOR_NRD_SUN_PENUMBRA = ${schema.realtimeDescriptors.nrdSunPenumbra};
    public static final int DESCRIPTOR_NRD_DIFFUSE_DIRECTION = ${schema.realtimeDescriptors.nrdDiffuseDirection};
    public static final int DESCRIPTOR_NRD_SPECULAR_DIRECTION = ${schema.realtimeDescriptors.nrdSpecularDirection};
    public static final int DESCRIPTOR_NRD_REFLECTION_NOISY_DIFFUSE = ${schema.realtimeDescriptors.nrdReflectionNoisyDiffuse};
    public static final int DESCRIPTOR_NRD_REFLECTION_NOISY_SPECULAR = ${schema.realtimeDescriptors.nrdReflectionNoisySpecular};
    public static final int DESCRIPTOR_NRD_REFLECTION_NORMAL_ROUGHNESS = ${schema.realtimeDescriptors.nrdReflectionNormalRoughness};
    public static final int DESCRIPTOR_NRD_REFLECTION_MATERIAL = ${schema.realtimeDescriptors.nrdReflectionMaterial};
    public static final int DESCRIPTOR_NRD_REFLECTION_SPECULAR_MATERIAL = ${schema.realtimeDescriptors.nrdReflectionSpecularMaterial};
    public static final int DESCRIPTOR_NRD_REFLECTION_POSITION = ${schema.realtimeDescriptors.nrdReflectionPosition};
    public static final int DESCRIPTOR_NRD_REFLECTION_DIFFUSE_DIRECTION = ${schema.realtimeDescriptors.nrdReflectionDiffuseDirection};
    public static final int DESCRIPTOR_NRD_REFLECTION_SPECULAR_DIRECTION = ${schema.realtimeDescriptors.nrdReflectionSpecularDirection};
    public static final int DESCRIPTOR_NRD_DISPLAY_POSITION = ${schema.realtimeDescriptors.nrdDisplayPosition};
    public static final int DESCRIPTOR_STARMAP = ${schema.sharedDescriptors.starmap};
    public static final int DESCRIPTOR_REALTIME_STBN = ${schema.sharedDescriptors.realtimeStbn};
    public static final int DESCRIPTOR_WAVEFRONT_PATHS = ${schema.realtimeDescriptors.wavefrontPaths};
    public static final int DESCRIPTOR_WAVEFRONT_QUEUE = ${schema.realtimeDescriptors.wavefrontQueue};
    public static final int OFFLINE_DESCRIPTOR_RUNNING_MEAN = ${schema.offlineDescriptors.runningMean};
    public static final int OFFLINE_DESCRIPTOR_WAVEFRONT_PATHS = ${schema.offlineDescriptors.wavefrontPaths};
    public static final int OFFLINE_DESCRIPTOR_WAVEFRONT_QUEUE = ${schema.offlineDescriptors.wavefrontQueue};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_0 = ${schema.sharedDescriptors.sunShadowDepth0};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_1 = ${schema.sharedDescriptors.sunShadowDepth1};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_2 = ${schema.sharedDescriptors.sunShadowDepth2};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_3 = ${schema.sharedDescriptors.sunShadowDepth3};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_4 = ${schema.sharedDescriptors.sunShadowDepth4};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_5 = ${schema.sharedDescriptors.sunShadowDepth5};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_6 = ${schema.sharedDescriptors.sunShadowDepth6};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_7 = ${schema.sharedDescriptors.sunShadowDepth7};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_8 = ${schema.sharedDescriptors.sunShadowDepth8};
    public static final int DESCRIPTOR_SUN_SHADOW_DEPTH_9 = ${schema.sharedDescriptors.sunShadowDepth9};
    public static final int DESCRIPTOR_SUN_SHADOW_QUERY = ${schema.sharedDescriptors.sunShadowQuery};
    public static final int SCENE_TEXTURE_COUNT = ${schema.sceneTextureCount};
    public static final int MATERIAL_PAGE_COUNT = ${schema.materialPageCount};
    public static final int BASE_COLOR_PAGE_COUNT = ${schema.baseColorPageCount};
    public static final int WAVEFRONT_PATH_RECORD_SIZE = ${wavefrontContract.pathRecordSize};
    public static final int WAVEFRONT_ETA_SCALE_OFFSET = ${wavefrontContract.etaScaleOffset};
    public static final int WAVEFRONT_PATH_CONTROL_RESERVED_MASK = ${wavefrontContract.pathControlReservedMask};
    public static final int WAVEFRONT_PATH_SLOTS_PER_PIXEL = ${wavefrontContract.pathSlotsPerPixel};
    public static final int OFFLINE_WAVEFRONT_PATH_RECORD_SIZE = ${offlineWavefrontContract.pathRecordSize};
    public static final int OFFLINE_WAVEFRONT_PATH_SLOTS_PER_PIXEL = ${offlineWavefrontContract.pathSlotsPerPixel};
    public static final int OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE = ${offlineWavefrontContract.surfaceRecordSize};
    public static final int OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE = ${offlineWavefrontContract.stageRecordSize};
    public static final int OFFLINE_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL = ${offlineWavefrontContract.queueEntriesPerPixel};
    public static final int OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL = ${offlineWavefrontContract.queueStorageEntriesPerPixel};
    public static final int OFFLINE_WAVEFRONT_QUEUE_COUNT = ${offlineWavefrontContract.queueCount};
    public static final int OFFLINE_WAVEFRONT_QUEUE_COMMAND_STRIDE = ${offlineWavefrontContract.queueCommandStride};
    public static final int OFFLINE_WAVEFRONT_QUEUE_INDEX_SIZE = ${offlineWavefrontContract.queueIndexSize};
    public static final int WAVEFRONT_AREA_RECORD_SIZE = ${wavefrontContract.areaRecordSize};
    public static final int WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL = ${wavefrontContract.queueEntriesPerPixel};
    public static final int WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL = ${wavefrontContract.queueStorageEntriesPerPixel};
    public static final int WAVEFRONT_QUEUE_COUNT = ${wavefrontContract.queueCount};
    public static final int WAVEFRONT_TRACE_QUEUE_0 = ${wavefrontContract.traceQueue0};
    public static final int WAVEFRONT_TRACE_QUEUE_1 = ${wavefrontContract.traceQueue1};
    public static final int WAVEFRONT_PRIMARY_QUEUE = ${wavefrontContract.primaryQueue};
    public static final int WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 = ${wavefrontContract.transparentTraceQueue0};
    public static final int WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 = ${wavefrontContract.transparentTraceQueue1};
    public static final int WAVEFRONT_AREA_QUEUE = ${wavefrontContract.areaQueue};
    public static final int WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE = ${wavefrontContract.transparentResolveQueue};
    public static final int WAVEFRONT_GUIDE_QUEUE = ${wavefrontContract.guideQueue};
    public static final int WAVEFRONT_QUEUE_COMMAND_STRIDE = ${wavefrontContract.queueCommandStride};
    public static final int WAVEFRONT_QUEUE_INDEX_SIZE = ${wavefrontContract.queueIndexSize};
    public static final int WAVEFRONT_ACTIVE_MASK = ${wavefrontContract.activeMask};
    public static final int PATH_SAMPLE_INDEX_MASK = ${schema.pathControl.sampleIndexMask};
    public static final int PATH_SOLAR_LONGITUDE_SHIFT = ${schema.pathControl.solarLongitudeShift};
    public static final int PATH_SOLAR_LONGITUDE_MASK = ${schema.pathControl.solarLongitudeMask};
    public static final int PATH_SEAMLESS_GLASS_MASK = ${schema.pathControl.seamlessGlassMask};
    public static final int PATH_AIR_GAP_MASK = ${schema.pathControl.airGapMask};
    public static final int PATH_VANILLA_PBR_PRESETS_MASK = ${schema.pathControl.vanillaPbrPresetsMask};
    public static final int PATH_TRANSPARENT_NEE_UNBIASED_MASK = ${schema.pathControl.transparentNeeUnbiasedMask};
    public static final int PATH_SAMPLE_EPOCH_MASK = ${schema.pathControl.sampleEpochMask};
    public static final int PATH_HISTORY_VALID_MASK = ${schema.pathControl.historyValidMask};
    public static final int PATH_MAXIMUM_BOUNCES_MASK = ${schema.pathControl.maximumBouncesMask};
    public static final int PATH_LATITUDE_SHIFT = ${schema.pathControl.latitudeShift};
    public static final int PATH_LATITUDE_MASK = ${schema.pathControl.latitudeMask};
    public static final int PATH_LATITUDE_BIAS = ${schema.pathControl.latitudeBias};
    public static final int PATH_CAMERA_IN_WATER_MASK = ${schema.pathControl.cameraInWaterMask};
    public static final int PATH_JITTER_PHASE_MASK = ${schema.pathControl.jitterPhaseMask};
    public static final int PATH_TRANSPARENT_GUIDE_MODE_SHIFT = ${schema.pathControl.transparentGuideModeShift};
    public static final int PATH_TRANSPARENT_GUIDE_MODE_MASK = ${schema.pathControl.transparentGuideModeMask};
    public static final int PATH_TRANSPARENT_GUIDE_MODE_NRD = ${schema.pathControl.transparentGuideModeNrd};
    public static final int PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR = ${schema.pathControl.transparentGuideModeDlssRr};
    public static final int PATH_TRANSPARENT_GUIDE_MODE_DISABLED = ${schema.pathControl.transparentGuideModeDisabled};
    public static final int PATH_SUN_EV_QUARTER_SHIFT = ${schema.pathControl.sunEvQuarterShift};
    public static final int PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT = ${schema.pathControl.blockLightEvQuarterShift};
    public static final int PATH_EV_QUARTER_MASK = ${schema.pathControl.evQuarterMask};
    public static final int PATH_EV_QUARTER_BIAS = ${schema.pathControl.evQuarterBias};
    public static final int PATH_MATERIAL_ROUGHNESS_SHIFT = ${schema.pathControl.materialRoughnessShift};
    public static final int PATH_MATERIAL_ROUGHNESS_MASK = ${schema.pathControl.materialRoughnessMask};
    public static final int PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT = ${schema.pathControl.materialRoughnessStepsPerUnit};
    public static final int PATH_SH_INPUT_MASK = ${schema.pathControl.shInputMask};
    public static final int PATH_STAR_EV_QUARTER_SHIFT = ${schema.pathControl.starEvQuarterShift};
    public static final int PATH_STAR_EV_QUARTER_MASK = ${schema.pathControl.starEvQuarterMask};
    public static final int PATH_STAR_EV_QUARTER_BIAS = ${schema.pathControl.starEvQuarterBias};
    public static final int MAXIMUM_BOUNCES = ${schema.pathControl.maximumBounces};
    public static final int RUSSIAN_ROULETTE_START = ${schema.pathControl.russianRouletteStart};
    public static final float CUTOUT_ALPHA_THRESHOLD = ${schema.cutoutAlphaThreshold}f;
    public static final String WORKING_COLOR_SPACE = "${colorContract.workingSpace}";
    public static final String TEXTURE_COLOR_ENCODING = "${colorContract.textureEncoding}";
    public static final String DISPLAY_COLOR_ENCODING = "${colorContract.displayEncoding}";
    public static final String DISPLAY_COLOR_SPACE = "${colorContract.displayColorSpace}";
    public static final float DISPLAY_EXPOSURE = ${colorContract.displayExposure}f;
    public static final float LEVEL_15_BLOCK_INTENSITY = ${emissionContract.level15BlockIntensity}f;
    public static final String NRD_VERSION = "${nrdContract.version}";
    public static final String NRD_DENOISER = "${nrdContract.denoiser}";
    public static final String NRD_NORMAL_ENCODING = "${nrdContract.normalEncoding}";
    public static final String NRD_ROUGHNESS_ENCODING = "${nrdContract.roughnessEncoding}";
    public static final String NRD_MOTION_SPACE = "${nrdContract.motionSpace}";
    public static final String NRD_SIGNAL_SPACE = "${nrdContract.signalSpace}";
    public static final String FSR_VERSION = "${fsrContract.version}";
    public static final String FSR_MOTION_SPACE = "${fsrContract.motionSpace}";
    public static final String FSR_DEPTH_SPACE = "${fsrContract.depthSpace}";
    public static final float FSR_NEAR_PLANE = ${fsrContract.nearPlane}f;
    public static final float FSR_VIEW_SPACE_TO_METERS_FACTOR = ${fsrContract.viewSpaceToMetersFactor}f;
    public static final String ATMOSPHERE_SPECTRAL_MODEL = "${atmosphereContract.spectralModel}";
    public static final float ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE = ${atmosphereContract.worldToAtmosphereScale}f;
    public static final float ATMOSPHERE_BOTTOM_RADIUS_KM = ${atmosphereContract.bottomRadiusKm}f;
    public static final float ATMOSPHERE_TOP_RADIUS_KM = ${atmosphereContract.topRadiusKm}f;
    public static final float ATMOSPHERE_WORLD_SEA_LEVEL_Y = ${atmosphereContract.worldSeaLevelY}f;
    public static final float ATMOSPHERE_WORLD_UNIT_SCALE_KM = ${atmosphereContract.worldUnitScaleKm * atmosphereContract.worldToAtmosphereScale}f;
    public static final float ATMOSPHERE_SPACE_SUN_INTENSITY = ${atmosphereContract.spaceSunIntensity}f;
    public static final float ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS = ${atmosphereContract.sunAngularRadiusRadians}f;
    public static final float ATMOSPHERE_AERIAL_MAX_DISTANCE_KM = ${atmosphereContract.aerialMaxDistanceKm * atmosphereContract.worldToAtmosphereScale}f;
    public static final int ATMOSPHERE_AERIAL_WIDTH = ${atmosphereContract.aerialWidth};
    public static final int ATMOSPHERE_AERIAL_HEIGHT = ${atmosphereContract.aerialHeight};
    public static final int ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES = ${atmosphereContract.aerialEpipolarSamples};
    public static final int ATMOSPHERE_AERIAL_EPIPOLAR_SLICES = ${atmosphereContract.aerialEpipolarSlices};
    public static final int ATMOSPHERE_AERIAL_DEPTH = ${atmosphereContract.aerialDepth};
    public static final int ATMOSPHERE_AERIAL_SEGMENT_SAMPLES = ${atmosphereContract.aerialSegmentSamples};
    public static final float ASTRONOMY_AXIAL_TILT_DEGREES = ${astronomyContract.axialTiltDegrees}f;
    public static final int ASTRONOMY_MINIMUM_LATITUDE_DEGREES = ${astronomyContract.minimumLatitudeDegrees};
    public static final int ASTRONOMY_MAXIMUM_LATITUDE_DEGREES = ${astronomyContract.maximumLatitudeDegrees};
    public static final int ASTRONOMY_DEFAULT_LATITUDE_DEGREES = ${astronomyContract.defaultLatitudeDegrees};
    public static final int ASTRONOMY_MINIMUM_SOLAR_LONGITUDE_DEGREES = ${astronomyContract.minimumSolarLongitudeDegrees};
    public static final int ASTRONOMY_MAXIMUM_SOLAR_LONGITUDE_DEGREES = ${astronomyContract.maximumSolarLongitudeDegrees};
    public static final int ASTRONOMY_DEFAULT_SOLAR_LONGITUDE_DEGREES = ${astronomyContract.defaultSolarLongitudeDegrees};
    public static final int STARMAP_WIDTH = ${starmapContract.width};
    public static final int STARMAP_HEIGHT = ${starmapContract.height};
    public static final float STARMAP_BASE_RADIANCE_SCALE = ${starmapContract.baseRadianceScale}f;
    public static final String STARMAP_SOURCE_SHA256 = "${starmapContract.sourceSha256}";
    public static final int REALTIME_STBN_WIDTH = ${realtimeStbnContract.width};
    public static final int REALTIME_STBN_HEIGHT = ${realtimeStbnContract.height};
    public static final int REALTIME_STBN_DEPTH = ${realtimeStbnContract.depth};
    public static final int REALTIME_STBN_BANK_COUNT = ${realtimeStbnContract.bankCount};
    public static final int REALTIME_STBN_CHANNELS = ${realtimeStbnContract.channels};
    public static final int REALTIME_STBN_CHANNEL_BITS = ${realtimeStbnContract.channelBits};
    public static final String REALTIME_STBN_RESOURCE_SHA256 = "${realtimeStbnContract.resourceSha256}";
${javaOffsets}

    private ShaderAbi() {
    }
}
"""


		def slangDir = slangOutputDirectory.get().asFile
		slangDir.mkdirs()
		new File(slangDir, 'prime_material_core_abi.slang').text = """\
#language slang 2026
module "prime_material_core_abi.slang";

// Generated from shaders/abi.json. Do not edit by hand.
public static const uint PRIME_MATERIAL_CORE_RECORD_SIZE = ${materialCoreContract.recordSize};
public static const uint PRIME_MATERIAL_CORE_TEXTURE_ID_MASK = ${materialCoreContract.textureIdMask};
public static const uint PRIME_MATERIAL_CORE_RECIPE_CONTROL_SHIFT = ${materialCoreContract.recipeControlShift};
public static const uint PRIME_MATERIAL_CORE_RECIPE_CONTROL_MASK = ${materialCoreContract.recipeControlMask};
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
static const uint PRIME_PRIMITIVE_RECORD_SIZE = ${primitive.size};
static const uint PRIME_SECTION_RECORD_SIZE = ${section.size};
static const uint PRIME_LIGHT_NODE_SIZE = ${lightNode.size};
static const uint PRIME_LIGHT_LEAF_SIZE = ${lightLeaf.size};
static const uint PRIME_LIGHT_LEAF_ENTRY_SIZE = ${lightLeafEntry.size};
static const uint PRIME_LIGHT_EMITTER_SIZE = ${lightEmitter.size};
static const uint PRIME_LIGHT_CELL_SIZE = ${lightCell.size};
static const uint PRIME_SECTION_LIGHT_HEADER_SIZE = ${sectionLightHeader.size};
static const uint PRIME_INTEGRATOR_RECORD_SIZE = ${integrator.size};
static const uint PRIME_PATH_STATE_SIZE = ${pathState.size};
static const uint PRIME_TRACE_PAYLOAD_SIZE = ${tracePayload.size};
static const uint PRIME_SURFACE_INTERACTION_SIZE = ${surfaceInteraction.size};
static const uint PRIME_PUSH_CONSTANT_SIZE = ${push.size};
static const uint PRIME_NRD_MOTION_PUSH_CONSTANT_SIZE = ${nrdMotionPush.size};
static const uint PRIME_SUN_SHADOW_QUERY_CONSTANT_SIZE = ${sunShadowQuery.size};
static const uint PRIME_SCENE_TEXTURE_COUNT = ${schema.sceneTextureCount};
static const uint PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY = ${schema.sharedDescriptors.transmissionGgxEnergy};
static const uint PRIME_PATH_SAMPLE_INDEX_MASK = ${schema.pathControl.sampleIndexMask};
static const uint PRIME_PATH_SOLAR_LONGITUDE_SHIFT = ${schema.pathControl.solarLongitudeShift};
static const uint PRIME_PATH_SOLAR_LONGITUDE_MASK = ${schema.pathControl.solarLongitudeMask};
static const uint PRIME_PATH_SEAMLESS_GLASS_MASK = ${schema.pathControl.seamlessGlassMask};
static const uint PRIME_PATH_AIR_GAP_MASK = ${schema.pathControl.airGapMask};
static const uint PRIME_PATH_VANILLA_PBR_PRESETS_MASK = ${schema.pathControl.vanillaPbrPresetsMask};
static const uint PRIME_PATH_TRANSPARENT_NEE_UNBIASED_MASK = ${schema.pathControl.transparentNeeUnbiasedMask};
static const uint PRIME_PATH_SAMPLE_EPOCH_MASK = ${schema.pathControl.sampleEpochMask};
static const uint PRIME_PATH_MAXIMUM_BOUNCES_MASK = ${schema.pathControl.maximumBouncesMask};
static const uint PRIME_PATH_LATITUDE_SHIFT = ${schema.pathControl.latitudeShift};
static const uint PRIME_PATH_LATITUDE_MASK = ${schema.pathControl.latitudeMask};
static const int PRIME_PATH_LATITUDE_BIAS = ${schema.pathControl.latitudeBias};
static const uint PRIME_PATH_CAMERA_IN_WATER_MASK = ${schema.pathControl.cameraInWaterMask};
static const uint PRIME_PATH_JITTER_PHASE_MASK = ${schema.pathControl.jitterPhaseMask};
static const uint PRIME_PATH_TRANSPARENT_GUIDE_MODE_SHIFT = ${schema.pathControl.transparentGuideModeShift};
static const uint PRIME_PATH_TRANSPARENT_GUIDE_MODE_MASK = ${schema.pathControl.transparentGuideModeMask};
static const uint PRIME_PATH_TRANSPARENT_GUIDE_MODE_NRD = ${schema.pathControl.transparentGuideModeNrd};
static const uint PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR = ${schema.pathControl.transparentGuideModeDlssRr};
static const uint PRIME_PATH_TRANSPARENT_GUIDE_MODE_DISABLED = ${schema.pathControl.transparentGuideModeDisabled};
static const uint PRIME_PATH_SUN_EV_QUARTER_SHIFT = ${schema.pathControl.sunEvQuarterShift};
static const uint PRIME_PATH_BLOCK_LIGHT_EV_QUARTER_SHIFT = ${schema.pathControl.blockLightEvQuarterShift};
static const uint PRIME_PATH_EV_QUARTER_MASK = ${schema.pathControl.evQuarterMask};
static const int PRIME_PATH_EV_QUARTER_BIAS = ${schema.pathControl.evQuarterBias};
static const uint PRIME_PATH_MATERIAL_ROUGHNESS_SHIFT = ${schema.pathControl.materialRoughnessShift};
static const uint PRIME_PATH_MATERIAL_ROUGHNESS_MASK = ${schema.pathControl.materialRoughnessMask};
static const float PRIME_PATH_MATERIAL_ROUGHNESS_STEPS_PER_UNIT = ${schema.pathControl.materialRoughnessStepsPerUnit};
static const uint PRIME_PATH_SH_INPUT_MASK = ${schema.pathControl.shInputMask};
static const uint PRIME_PATH_STAR_EV_QUARTER_SHIFT = ${schema.pathControl.starEvQuarterShift};
static const uint PRIME_PATH_STAR_EV_QUARTER_MASK = ${schema.pathControl.starEvQuarterMask};
static const int PRIME_PATH_STAR_EV_QUARTER_BIAS = ${schema.pathControl.starEvQuarterBias};
static const uint PRIME_MAXIMUM_BOUNCES = ${schema.pathControl.maximumBounces};
static const uint PRIME_RUSSIAN_ROULETTE_START = ${schema.pathControl.russianRouletteStart};
static const float PRIME_CUTOUT_ALPHA_THRESHOLD = ${schema.cutoutAlphaThreshold};
static const float PRIME_LEVEL_15_BLOCK_INTENSITY = ${emissionContract.level15BlockIntensity};
static const float PRIME_DISPLAY_EXPOSURE = ${colorContract.displayExposure};
static const float PRIME_ASTRONOMY_AXIAL_TILT_RADIANS =
        ${astronomyContract.axialTiltDegrees} * 0.017453292519943295;
static const float PRIME_ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE = ${atmosphereContract.worldToAtmosphereScale};
static const float PRIME_ATMOSPHERE_BOTTOM_RADIUS_KM = ${atmosphereContract.bottomRadiusKm};
static const float PRIME_ATMOSPHERE_TOP_RADIUS_KM = ${atmosphereContract.topRadiusKm};
static const float PRIME_ATMOSPHERE_WORLD_SEA_LEVEL_Y = ${atmosphereContract.worldSeaLevelY};
static const float PRIME_ATMOSPHERE_WORLD_UNIT_SCALE_KM = ${atmosphereContract.worldUnitScaleKm * atmosphereContract.worldToAtmosphereScale};
static const float PRIME_ATMOSPHERE_SPACE_SUN_INTENSITY = ${atmosphereContract.spaceSunIntensity};
static const float PRIME_ATMOSPHERE_SUN_ANGULAR_RADIUS_RADIANS = ${atmosphereContract.sunAngularRadiusRadians};
static const float PRIME_ATMOSPHERE_AERIAL_MAX_DISTANCE_KM = ${atmosphereContract.aerialMaxDistanceKm * atmosphereContract.worldToAtmosphereScale};
static const uint PRIME_ATMOSPHERE_AERIAL_WIDTH = ${atmosphereContract.aerialWidth};
static const uint PRIME_ATMOSPHERE_AERIAL_HEIGHT = ${atmosphereContract.aerialHeight};
static const uint PRIME_ATMOSPHERE_AERIAL_EPIPOLAR_SAMPLES = ${atmosphereContract.aerialEpipolarSamples};
static const uint PRIME_ATMOSPHERE_AERIAL_EPIPOLAR_SLICES = ${atmosphereContract.aerialEpipolarSlices};
static const uint PRIME_ATMOSPHERE_AERIAL_DEPTH = ${atmosphereContract.aerialDepth};
static const uint PRIME_ATMOSPHERE_AERIAL_SEGMENT_SAMPLES = ${atmosphereContract.aerialSegmentSamples};
static const uint PRIME_STARMAP_WIDTH = ${starmapContract.width};
static const uint PRIME_STARMAP_HEIGHT = ${starmapContract.height};
static const float PRIME_STARMAP_BASE_RADIANCE_SCALE = ${starmapContract.baseRadianceScale};

public struct PrimitiveRecord
{
${slangStructFields(primitive)}
};

public struct SectionRecord
{
${slangStructFields(section)}
};

public struct LightNode
{
${slangStructFields(lightNode)}
};

public struct LightLeaf
{
${slangStructFields(lightLeaf)}
};

public struct LightLeafEntry
{
${slangStructFields(lightLeafEntry)}
};

public struct LightEmitter
{
${slangStructFields(lightEmitter)}
};

public struct LightCell
{
${slangStructFields(lightCell)}
};

public struct SectionLightHeader
{
${slangStructFields(sectionLightHeader)}
};

public struct IntegratorRecord
{
${slangStructFields(integrator)}
};

public struct PathState
{
${slangStructFields(pathState)}
};

public struct TracePayload
{
${slangStructFields(tracePayload)}
};

public struct SurfaceInteraction
{
${slangStructFields(surfaceInteraction)}
};

// Physical queue lanes. Floating-point members are stored by bit representation.
public struct WavefrontSurfaceRecord
{
${slangStructFields(wavefrontSurfaceRecord)}
};

public struct PrimePushConstants
{
${slangStructFields(push)}
};

public struct NrdMotionPushConstants
{
${slangStructFields(nrdMotionPush)}
};

public struct SunShadowQueryConstants
{
${slangStructFields(sunShadowQuery)}
};

""".replace('static const ', 'public static const ')
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

[[vk::binding(${schema.sharedDescriptors.skyView}, 0)]] [[vk::image_format("rgba16f")]]
public readonly RWTexture2D<float4> primeSkyView;
[[vk::binding(${schema.sharedDescriptors.transmittanceLow}, 0)]] [[vk::image_format("rgba16f")]]
public readonly RWTexture2D<float4> primeTransmittanceLow;
[[vk::binding(${schema.sharedDescriptors.transmittanceHigh}, 0)]] [[vk::image_format("rgba16f")]]
public readonly RWTexture2D<float4> primeTransmittanceHigh;
[[vk::binding(${schema.sharedDescriptors.aerialRadiance}, 0)]] [[vk::image_format("rgba16f")]]
public readonly RWTexture3D<float4> primeAerialRadiance;
[[vk::binding(${schema.sharedDescriptors.aerialTransmittance}, 0)]] [[vk::image_format("rgba16f")]]
public readonly RWTexture3D<float4> primeAerialTransmittance;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth0}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth0;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth1}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth1;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth2}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth2;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth3}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth3;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth4}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth4;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth5}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth5;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth6}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth6;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth7}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth7;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth8}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth8;
[[vk::binding(${schema.sharedDescriptors.sunShadowDepth9}, 0)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeSunShadowDepth9;
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
		new File(slangDir, 'prime_realtime_abi.slang').text = """\
#language slang 2026
module "prime_realtime_abi.slang";

import "prime_abi_types.slang";

public static const uint PRIME_RENDERER_DESCRIPTOR_SET = 1;
public static const uint PRIME_DESCRIPTOR_WAVEFRONT_PATHS = ${schema.realtimeDescriptors.wavefrontPaths};
public static const uint PRIME_DESCRIPTOR_WAVEFRONT_QUEUE = ${schema.realtimeDescriptors.wavefrontQueue};
public static const uint PRIME_WAVEFRONT_PATH_RECORD_SIZE = ${wavefrontContract.pathRecordSize};
public static const uint PRIME_WAVEFRONT_ETA_SCALE_OFFSET = ${wavefrontContract.etaScaleOffset};
public static const uint PRIME_WAVEFRONT_PATH_CONTROL_RESERVED_MASK = ${wavefrontContract.pathControlReservedMask};
public static const uint PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL = ${wavefrontContract.pathSlotsPerPixel};
public static const uint PRIME_WAVEFRONT_SURFACE_RECORD_SIZE = ${wavefrontSurfaceRecord.size};
public static const uint PRIME_WAVEFRONT_AREA_RECORD_SIZE = ${wavefrontContract.areaRecordSize};
public static const uint PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL = ${wavefrontContract.queueEntriesPerPixel};
public static const uint PRIME_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL = ${wavefrontContract.queueStorageEntriesPerPixel};
public static const uint PRIME_WAVEFRONT_QUEUE_COUNT = ${wavefrontContract.queueCount};
public static const uint PRIME_WAVEFRONT_TRACE_QUEUE_0 = ${wavefrontContract.traceQueue0};
public static const uint PRIME_WAVEFRONT_TRACE_QUEUE_1 = ${wavefrontContract.traceQueue1};
public static const uint PRIME_WAVEFRONT_PRIMARY_QUEUE = ${wavefrontContract.primaryQueue};
public static const uint PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 = ${wavefrontContract.transparentTraceQueue0};
public static const uint PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 = ${wavefrontContract.transparentTraceQueue1};
// Transitional aliases keep the retired split-stage entry points compilable on this experiment.
public static const uint PRIME_WAVEFRONT_SHADE_QUEUE = PRIME_WAVEFRONT_PRIMARY_QUEUE;
public static const uint PRIME_WAVEFRONT_TRANSPARENT_SHADE_QUEUE = PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0;
public static const uint PRIME_WAVEFRONT_AREA_QUEUE = ${wavefrontContract.areaQueue};
public static const uint PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE = ${wavefrontContract.transparentResolveQueue};
public static const uint PRIME_WAVEFRONT_GUIDE_QUEUE = ${wavefrontContract.guideQueue};
public static const uint PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE = ${wavefrontContract.queueCommandStride};
public static const uint PRIME_WAVEFRONT_QUEUE_INDEX_SIZE = ${wavefrontContract.queueIndexSize};
public static const uint PRIME_WAVEFRONT_ACTIVE_MASK = ${wavefrontContract.activeMask};

[[vk::binding(${schema.realtimeDescriptors.stableRadiance}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeStableRadiance;
[[vk::binding(${schema.realtimeDescriptors.nrdNoisyDiffuse}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdNoisyDiffuse;
[[vk::binding(${schema.realtimeDescriptors.nrdNoisySpecular}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdNoisySpecular;
[[vk::binding(${schema.realtimeDescriptors.nrdNormalRoughness}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeNrdNormalRoughness;
[[vk::binding(${schema.realtimeDescriptors.nrdViewZ}, 1)]] [[vk::image_format("r32f")]]
public RWTexture2D<float> primeNrdViewZ;
[[vk::binding(${schema.realtimeDescriptors.wavefrontTransportMetadata}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeWavefrontTransportMetadata;
[[vk::binding(${schema.realtimeDescriptors.nrdMaterial}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdMaterial;
[[vk::binding(${schema.realtimeDescriptors.nrdSpecularMaterial}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdSpecularMaterial;
[[vk::binding(${schema.realtimeDescriptors.nrdMaterialClass}, 1)]] [[vk::image_format("r8ui")]]
public RWTexture2D<uint> primeReconstructionControl;
[[vk::binding(${schema.realtimeDescriptors.nrdPrimaryPosition}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeNrdPrimaryPosition;
[[vk::binding(${schema.realtimeDescriptors.nrdSunLighting}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdSunLighting;
[[vk::binding(${schema.realtimeDescriptors.nrdSunPenumbra}, 1)]] [[vk::image_format("r16f")]]
public RWTexture2D<float> primeNrdSunPenumbra;
[[vk::binding(${schema.realtimeDescriptors.nrdDiffuseDirection}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdDiffuseDirection;
[[vk::binding(${schema.realtimeDescriptors.nrdSpecularDirection}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdSpecularDirection;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionNoisyDiffuse}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionNoisyDiffuse;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionNoisySpecular}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionNoisySpecular;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionNormalRoughness}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeNrdReflectionNormalRoughness;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionMaterial}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionMaterial;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionSpecularMaterial}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionSpecularMaterial;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionPosition}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeNrdReflectionPosition;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionDiffuseDirection}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionDiffuseDirection;
[[vk::binding(${schema.realtimeDescriptors.nrdReflectionSpecularDirection}, 1)]] [[vk::image_format("rgba16f")]]
public RWTexture2D<float4> primeNrdReflectionSpecularDirection;
[[vk::binding(${schema.realtimeDescriptors.nrdDisplayPosition}, 1)]] [[vk::image_format("rgba32f")]]
public RWTexture2D<float4> primeNrdDisplayPosition;

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

public static const uint PRIME_RENDERER_DESCRIPTOR_SET = 1;
public static const uint PRIME_DESCRIPTOR_WAVEFRONT_PATHS = ${schema.offlineDescriptors.wavefrontPaths};
public static const uint PRIME_DESCRIPTOR_WAVEFRONT_QUEUE = ${schema.offlineDescriptors.wavefrontQueue};
public static const uint PRIME_WAVEFRONT_PATH_RECORD_SIZE = ${offlineWavefrontContract.pathRecordSize};
public static const uint PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL = ${offlineWavefrontContract.pathSlotsPerPixel};
public static const uint PRIME_OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE = ${offlineWavefrontContract.surfaceRecordSize};
public static const uint PRIME_OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE = ${offlineWavefrontContract.stageRecordSize};
public static const uint PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL = ${offlineWavefrontContract.queueEntriesPerPixel};
public static const uint PRIME_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL = ${offlineWavefrontContract.queueStorageEntriesPerPixel};
public static const uint PRIME_WAVEFRONT_QUEUE_COUNT = ${offlineWavefrontContract.queueCount};
public static const uint PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE = ${offlineWavefrontContract.queueCommandStride};
public static const uint PRIME_WAVEFRONT_QUEUE_INDEX_SIZE = ${offlineWavefrontContract.queueIndexSize};
public static const uint PRIME_WAVEFRONT_ACTIVE_MASK = ${offlineWavefrontContract.activeMask};

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
