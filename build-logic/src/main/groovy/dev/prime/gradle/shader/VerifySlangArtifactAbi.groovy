// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import groovy.json.JsonSlurper
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@CacheableTask
abstract class VerifySlangArtifactAbi extends DefaultTask {
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract DirectoryProperty getSlangDirectory()

	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getSchemaFile()

	@Input
	abstract Property<String> getSpirvDisassembler()

	@OutputFile
	abstract RegularFileProperty getReportFile()

	private static final class Spirv {
		private static final def OPCODE = java.util.regex.Pattern.compile(
				'^\\s*(?:%\\w+\\s*=\\s*)?(Op\\w+)\\b')
		private static final def EXTENSION = java.util.regex.Pattern.compile(
				'^\\s*OpExtension "([^"]+)"')
		private static final def TYPE = java.util.regex.Pattern.compile(
				'^\\s*%(\\w+) = OpType(\\w+)(?: (.*))?$')
		private static final def CONSTANT = java.util.regex.Pattern.compile(
				'^\\s*%(\\w+) = OpConstant %\\w+ (\\d+)$')
		private static final def VARIABLE = java.util.regex.Pattern.compile(
				'^\\s*%(\\w+) = OpVariable %(\\w+) (\\w+)')
		private static final def DECORATION = java.util.regex.Pattern.compile(
				'^\\s*OpDecorate %(\\w+) (ArrayStride|Binding|DescriptorSet|Location) (\\d+)$')

		final Map<String, List<String>> types = [:]
		final Map<String, Long> constants = [:]
		final Map<String, Map<String, Integer>> decorations = [:]
		final List<Map<String, String>> variables = []
		final Set<String> opcodes = []
		final Set<String> extensions = []

		Spirv(String assembly) {
			assembly.eachLine { line ->
				def opcode = OPCODE.matcher(line)
				if (opcode.find()) {
					opcodes.add(opcode.group(1))
				}
				def extension = EXTENSION.matcher(line)
				if (extension.find()) {
					extensions.add(extension.group(1))
				}
				def type = TYPE.matcher(line)
				if (type.find()) {
					types[type.group(1)] = [type.group(2)] + operands(type.group(3))
				}
				def constant = CONSTANT.matcher(line)
				if (constant.find()) {
					constants[constant.group(1)] = constant.group(2) as long
				}
				def variable = VARIABLE.matcher(line)
				if (variable.find()) {
					variables.add([
							id: variable.group(1),
							type: variable.group(2),
							storage: variable.group(3)])
				}
				def decoration = DECORATION.matcher(line)
				if (decoration.find()) {
					decorations.computeIfAbsent(decoration.group(1)) { [:] }[
							decoration.group(2)] = decoration.group(3) as int
				}
			}
		}

		private static List<String> operands(String source) {
			if (source == null || source.isEmpty()) {
				return []
			}
			return source.trim().split(/\s+/).collect {
				it.startsWith('%') ? it.substring(1) : it
			}
		}

		private List<String> type(String id) {
			def type = types[id]
			if (type == null) {
				throw new GradleException("Missing SPIR-V type %${id}")
			}
			return type
		}

		private Integer decoration(String id, String name) {
			return decorations[id]?.get(name)
		}

        Map<Integer, String> payloads(String storage) {
            def result = [:]
            variables.findAll { it.storage == storage }.each { variable ->
                def location = decoration(variable.id, 'Location')
                if (location == null) throw new GradleException("${storage} payload has no Location")
                def value = shape(type(variable.type)[2])
                if (result.put(location, value) != null) {
                    throw new GradleException("Multiple ${storage} payloads use Location ${location}")
                }
            }
            return result
        }

		Set<Integer> descriptorBindings(int set) {
			return variables.findAll { decoration(it.id, 'DescriptorSet') == set }
					.collect { decoration(it.id, 'Binding') }
					.findAll { it != null }
					.toSet()
		}

		int descriptorArrayLength(int set, int binding) {
			def variable = descriptor(set, binding)
			def pointer = type(variable.type)
			def array = type(pointer[2])
			if (array[0] != 'Array') {
				throw new GradleException("Descriptor ${set}:${binding} is not a fixed-size array")
			}
			def length = constants[array[2]]
			if (length == null) {
				throw new GradleException("Descriptor ${set}:${binding} array has no constant length")
			}
			return Math.toIntExact(length)
		}

		int recordStride(int set, int binding) {
			def variable = descriptor(set, binding)
			def pointer = type(variable.type)
			def block = type(pointer[2])
			def stride = decoration(block[1], 'ArrayStride')
			if (stride == null) {
				throw new GradleException("Descriptor ${set}:${binding} has no record stride")
			}
			return stride
		}

		Set<String> payloadShapes(String storage) {
			return variables.findAll { it.storage == storage }
					.collect { shape(type(it.type)[2]) }
					.toSet()
		}

		private Map<String, String> descriptor(int set, int binding) {
			def result = variables.find {
				decoration(it.id, 'DescriptorSet') == set
						&& decoration(it.id, 'Binding') == binding
			}
			if (result == null) {
				throw new GradleException("Missing descriptor ${set}:${binding}")
			}
			return result
		}

		private String shape(String id) {
			def type = type(id)
			switch (type[0]) {
				case 'Int':
					return (type[2] == '0' ? 'u' : 'i') + type[1]
				case 'Float':
					return 'f' + type[1]
				case 'Vector':
					return "vec${type[2]}(${shape(type[1])})"
				case 'Struct':
					return 'struct(' + type.drop(1).collect { shape(it) }.join(',') + ')'
				default:
					throw new GradleException("Unsupported SPIR-V type ${type[0]}")
			}
		}
	}

	private static Spirv requireModule(Map<String, Spirv> modules, String name) {
		def module = modules[name]
		if (module == null) {
			throw new GradleException("Missing compiled shader ${name}")
		}
		return module
	}

	private static String wavefrontShader(String renderer, String stage, String suffix) {
		if (suffix == '_ser' && ((renderer == 'realtime'
				&& stage in ['noisy_output_resolve', 'visible_direct'])
				|| (renderer == 'offline' && stage in ['sample_resolve', 'light_select']))) {
			suffix = ''
		}
		return "${renderer}_wavefront_${stage}${suffix}.rgen.spv"
	}

	private static Set<Integer> descriptorBindings(
			Map<String, Spirv> modules, Collection<String> shaders, int set) {
		def result = [] as Set<Integer>
		shaders.each { result.addAll(requireModule(modules, it).descriptorBindings(set)) }
		return result
	}

	private static void requireEqual(Object expected, Object actual, String contract) {
		if (actual != expected) {
			throw new GradleException("${contract}: expected ${expected}, found ${actual}")
		}
	}

	private static void requireBinding(Set<Integer> bindings, int binding,
			boolean expected, String contract) {
		requireEqual(expected, bindings.contains(binding), contract)
	}

	private static void verifyPayloads(Map<String, Spirv> modules) {
        // Compatibility is a relation between linked producer/consumer interfaces. Debug variable
        // names and a snapshot of today's complete shading payload are not ABI requirements.
        def incoming = { String name, int location ->
            def payloads = requireModule(modules, name).payloads('IncomingRayPayloadKHR')
            requireEqual([location] as Set, payloads.keySet(), "Incoming payload location in ${name}")
            return payloads[location]
        }
        def trace = incoming('world.rchit.spv', 0)
        def shadow = incoming('shadow.rchit.spv', 1)
        def lambert = incoming('lambert_world.rchit.spv', 0)
        def lambertShadow = incoming('lambert_shadow.rchit.spv', 1)
        def traversal = incoming('world_rahit_ser.rahit.spv', 2)
        // The traversal request's exact integer source identity is shared by both integrators.
        requireEqual('struct(vec2(u32),u32)', traversal, 'Exact source traversal ABI')
        requireEqual(traversal, incoming('lambert_world_rahit_ser.rahit.spv', 2), 'Shared traversal ABI')
        modules.each { name, module ->
            def wires = name.startsWith('lambert_')
                    ? [(0): lambert, (1): lambertShadow, (2): traversal]
                    : [(0): trace, (1): shadow, (2): traversal]
            module.payloads('RayPayloadKHR').each { location, shape ->
                requireEqual(wires[location], shape, "Outgoing payload location ${location} in ${name}")
            }
            module.payloads('IncomingRayPayloadKHR').each { location, shape ->
                def expected = name == 'lambert_world_rahit_ser.rahit.spv' || name == 'world_rahit_ser.rahit.spv' ? 2
                        : name.startsWith('world.') || name.startsWith('world_') || name.startsWith('lambert_world.') ? 0
                        : name.startsWith('shadow.') || name.startsWith('shadow_')
                                || name.startsWith('lambert_shadow.') || name.startsWith('lambert_shadow_') ? 1 : null
                requireEqual(expected, location, "Incoming payload location in ${name}")
                requireEqual(wires[location], shape, "Incoming payload shape in ${name}")
            }
        }

		verifyShapes(modules, [lambert] as Set, 'RayPayloadKHR', ['lambert_trace.rgen.spv', 'lambert_camera.rgen.spv', 'lambert_camera_subgroup.rgen.spv', 'lambert_guide.rgen.spv'])
		verifyShapes(modules, [lambert, traversal] as Set, 'RayPayloadKHR',
                ['lambert_trace_ser.rgen.spv', 'lambert_camera_ser.rgen.spv', 'lambert_guide_ser.rgen.spv'])
        verifyShapes(modules, [traversal] as Set, 'IncomingRayPayloadKHR',
                ['lambert_world_rahit_ser.rahit.spv'])
		verifyShapes(modules, [lambertShadow] as Set, 'RayPayloadKHR',
                ['lambert_shade.rgen.spv', 'lambert_shade_subgroup.rgen.spv', 'lambert_first.rgen.spv', 'lambert_first_subgroup.rgen.spv'])
		verifyShapes(modules, [] as Set, 'RayPayloadKHR', ['lambert_terminal.rgen.spv', 'lambert_resolve.rgen.spv'])
		verifyShapes(modules, [lambert] as Set, 'IncomingRayPayloadKHR', [
				'lambert_world.rmiss.spv', 'lambert_world.rchit.spv', 'lambert_world.rahit.spv'])
		verifyShapes(modules, [lambertShadow] as Set, 'IncomingRayPayloadKHR', [
				'lambert_shadow.rmiss.spv', 'lambert_shadow.rchit.spv', 'lambert_shadow.rahit.spv',
				'lambert_shadow_opaque.rahit.spv'])
		verifyShapes(modules, [trace] as Set, 'IncomingRayPayloadKHR',
				['world.rmiss.spv', 'world.rchit.spv'])
		verifyShapes(modules, [shadow] as Set, 'IncomingRayPayloadKHR', [
				'shadow.rmiss.spv', 'shadow.rchit.spv',
				'shadow_opaque.rahit.spv', 'shadow_nonopaque.rahit.spv'])
		['', '_ser'].each { suffix ->
			verifyWavefrontShapes(modules, (suffix.isEmpty() ? [trace] : [trace, traversal]) as Set, 'realtime', suffix,
					['camera_trace', 'delta_walk', 'guide_delta_walk', 'secondary_trace'])
			verifyWavefrontShapes(modules, [shadow] as Set, 'realtime', suffix,
					['landing_direct', 'secondary_direct', 'visible_direct'])
			verifyWavefrontShapes(modules, [] as Set, 'realtime', suffix, [
					'surface_split', 'landing_light_select', 'landing_scatter',
					'secondary_light_select', 'secondary_scatter', 'branch_resolve',
					'noisy_output_resolve'])
			verifyWavefrontShapes(modules, (suffix.isEmpty() ? [trace] : [trace, traversal]) as Set, 'offline', suffix,
					['camera_trace', 'bridge_trace'])
			verifyWavefrontShapes(modules, [shadow] as Set, 'offline', suffix, ['direct'])
			verifyWavefrontShapes(modules, [] as Set, 'offline', suffix,
					['light_select', 'scatter'])
		}
	}

	private static void verifyWavefrontShapes(Map<String, Spirv> modules,
			Set<String> expected, String renderer, String suffix, Collection<String> stages) {
		verifyShapes(modules, expected, 'RayPayloadKHR',
				stages.collect { wavefrontShader(renderer, it, suffix) })
	}

	private static void verifyShapes(Map<String, Spirv> modules, Set<String> expected,
			String storage, Collection<String> shaders) {
		shaders.each { shader ->
			requireEqual(expected, requireModule(modules, shader).payloadShapes(storage),
					"Payload shape in ${shader}")
		}
	}

	private static void verifyDescriptors(Map<String, Spirv> modules, def schema) {
		requireEqual([0, 1] as Set, requireModule(modules,
				'image_diagnostic_rgba8.comp.spv').descriptorBindings(0), 'RGBA8 diagnostic descriptors')
		requireEqual([0, 1] as Set, requireModule(modules,
				'image_diagnostic_rgba16.comp.spv').descriptorBindings(0), 'RGBA16 diagnostic descriptors')
		requireEqual([0, 1, 2, 3, 4] as Set, requireModule(modules,
				'streamline_input.comp.spv').descriptorBindings(0), 'Streamline input descriptors')

		def queue = schema.realtimeDescriptors.wavefrontQueue as int
		def paths = schema.realtimeDescriptors.wavefrontPaths as int
		['camera', 'camera_subgroup', 'camera_ser', 'guide', 'guide_ser', 'first', 'first_subgroup', 'trace', 'trace_ser', 'shade', 'shade_subgroup', 'terminal', 'resolve'].each { stage ->
			def module = requireModule(modules, "lambert_${stage}.rgen.spv")
			requireEqual(schema.lambertContract.recordSize as int, module.recordStride(1, paths),
					"Lambert path stride in ${stage}")
			if (stage != 'resolve') requireEqual(4, module.recordStride(1, queue), "Lambert queue stride in ${stage}")
		}
		requireBinding(requireModule(modules, 'lambert_shade.rgen.spv').descriptorBindings(0),
				schema.sharedDescriptors.realtimeStbn as int, true, 'Lambert STBN binding')
		requireEqual([schema.sharedDescriptors.surfaceRecords as int] as Set,
                requireModule(modules, 'lambert_shadow_opaque.rahit.spv').descriptorBindings(0),
				'Lambert opaque shadow reads only the exact emitter identity table')
		['lambert_shade.rgen.spv', 'lambert_shade_subgroup.rgen.spv', 'lambert_first.rgen.spv',
		 'lambert_first_subgroup.rgen.spv', 'lambert_world.rchit.spv'].each { artifact ->
			requireBinding(requireModule(modules, artifact).descriptorBindings(0), 19, false,
					"Lambert must not bind normal maps in ${artifact}")
		}
		['', '_ser'].each { suffix ->
			def camera = requireModule(modules,
					wavefrontShader('realtime', 'camera_trace', suffix)).descriptorBindings(1)
			requireBinding(camera, queue, true, "Camera queue ${suffix}")
			requireBinding(camera, paths, false, "Camera paths ${suffix}")
			def split = requireModule(modules,
					wavefrontShader('realtime', 'surface_split', suffix)).descriptorBindings(1)
			requireBinding(split, paths, true, "Surface split paths ${suffix}")
		}
		def visible = requireModule(modules,
				wavefrontShader('realtime', 'visible_direct', '')).descriptorBindings(1)
		requireBinding(visible, queue, true, 'Visible direct queue')
		requireBinding(visible, paths, false, 'Visible direct paths')

		def realtimeStages = [
				'camera_trace', 'surface_split', 'delta_walk', 'guide_delta_walk',
				'landing_light_select', 'landing_direct', 'landing_scatter',
				'secondary_trace', 'secondary_light_select', 'secondary_direct', 'secondary_scatter',
				'branch_resolve', 'visible_direct', 'noisy_output_resolve']
		def offlineStages = [
				'camera_trace', 'bridge_trace', 'light_select', 'direct', 'scatter',
				'sample_resolve']
		['', '_ser'].each { suffix ->
			def realtime = descriptorBindings(modules,
					realtimeStages.collect { wavefrontShader('realtime', it, suffix) }, 1)
			[schema.realtimeDescriptors.wavefrontPaths,
			 schema.realtimeDescriptors.wavefrontQueue,
			 schema.realtimeDescriptors.stableRadiance].each {
				requireBinding(realtime, it as int, true, "Realtime set-one ABI ${suffix}")
			}
			schema.offlineDescriptors.values().each {
				requireBinding(realtime, it as int, false, "Realtime/offline isolation ${suffix}")
			}
			def offline = descriptorBindings(modules,
					offlineStages.collect { wavefrontShader('offline', it, suffix) }, 1)
			requireEqual(schema.offlineDescriptors.values().collect { it as int }.toSet(),
					offline, "Offline set-one ABI ${suffix}")
		}

		def stbn = schema.sharedDescriptors.realtimeStbn as int
		['', '_ser'].each { suffix ->
			def realtime = requireModule(modules,
					wavefrontShader('realtime', 'secondary_direct', suffix)).descriptorBindings(0)
			requireBinding(realtime, stbn, true, "Realtime STBN ${suffix}")
			def offline = descriptorBindings(modules,
					offlineStages.collect { wavefrontShader('offline', it, suffix) }, 0)
			requireBinding(offline, stbn, false, "Offline STBN isolation ${suffix}")
		}

		def baseColor = schema.sharedDescriptors.baseColorPages as int
		def pageCount = schema.baseColorPageCount as int
		requireEqual(pageCount, requireModule(modules, 'world.rchit.spv')
				.descriptorArrayLength(0, baseColor), 'World base-color page capacity')
		requireEqual(pageCount, requireModule(modules, 'shadow_nonopaque.rahit.spv')
				.descriptorArrayLength(0, baseColor), 'Shadow base-color page capacity')
		requireBinding(requireModule(modules, 'shadow_opaque.rahit.spv').descriptorBindings(0),
				baseColor, false, 'Opaque shadow base-color closure')

		['', '_ser'].each { suffix ->
			['delta_walk', 'guide_delta_walk', 'landing_light_select',
			 'landing_direct', 'landing_scatter'].each { stage ->
				requireEqual(schema.realtimeWavefrontContract.pathRecordSize as int,
						requireModule(modules, wavefrontShader('realtime', stage, suffix))
								.recordStride(1, paths),
						"Realtime path stride ${stage}${suffix}")
			}
			['direct', 'scatter'].each { stage ->
				requireEqual(schema.offlineWavefrontContract.pathRecordSize as int,
						requireModule(modules, wavefrontShader('offline', stage, suffix))
								.recordStride(1, schema.offlineDescriptors.wavefrontPaths as int),
						"Offline path stride ${stage}${suffix}")
			}
		}
	}

	@TaskAction
	void verify() {
		def root = new File(slangDirectory.get().asFile, 'prime/shaders')
		def computeContracts = [
				'image_diagnostic_rgba8.comp.spv',
				'image_diagnostic_rgba16.comp.spv',
				'streamline_input.comp.spv'] as Set
		def shaders = (root.listFiles() ?: [] as File[])
				.findAll { shader ->
					shader.name ==~ /.*\.(rgen|rmiss|rchit|rahit)\.spv/
							|| computeContracts.contains(shader.name)
				}
				.sort { it.name }
		if (shaders.isEmpty()) {
			throw new GradleException('No production Slang shaders were found')
		}
		def modules = shaders.collectEntries { shader ->
			[(shader.name): new Spirv(PrimeShaderTool.run(
					[spirvDisassembler.get(), shader.absolutePath]))]
		}

		def extSerModules = []
		modules.findAll { it.key.endsWith('_ser.rgen.spv') }.each { name, module ->
			if (module.extensions.contains('SPV_NV_shader_invocation_reorder')
					|| module.opcodes.contains('OpReorderThreadWithHitObjectNV')) {
				throw new GradleException("SER shader ${name} uses the vendor-specific NV dialect")
			}
			if (module.opcodes.contains('OpReorderThreadWithHitObjectEXT')) {
				if (!module.extensions.contains('SPV_EXT_shader_invocation_reorder')) {
					throw new GradleException(
							"SER shader ${name} uses EXT reorder without the EXT declaration")
				}
				extSerModules.add(name)
			}
		}
		if (extSerModules.isEmpty()) {
			throw new GradleException('No production SER shader emitted EXT invocation reorder')
		}

		verifyPayloads(modules)
		verifyDescriptors(modules, new JsonSlurper().parse(schemaFile.get().asFile))

		def report = reportFile.get().asFile
		report.parentFile.mkdirs()
		report.setText([
				'artifactAbi=valid',
				"shaderCount=${shaders.size()}",
				"extSerModules=${extSerModules.sort().join(',')}"
		].join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
	}
}
