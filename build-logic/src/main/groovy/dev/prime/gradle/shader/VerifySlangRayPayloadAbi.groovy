package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@CacheableTask
abstract class VerifySlangRayPayloadAbi extends DefaultTask {
	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract DirectoryProperty getSlangDirectory()

	@Input
	abstract Property<String> getSpirvDisassembler()

	@OutputFile
	abstract RegularFileProperty getReportFile()

	private static Map<String, Integer> payloadLocations(String assembly, String storageClass) {
		def names = [:]
		def nameMatcher = java.util.regex.Pattern.compile(
				'(?m)^\\s*OpName %(\\w+) "([^"]+)"\\s*$').matcher(assembly)
		while (nameMatcher.find()) {
			names[nameMatcher.group(1)] = nameMatcher.group(2)
		}
		def locations = [:]
		def locationMatcher = java.util.regex.Pattern.compile(
				'(?m)^\\s*OpDecorate %(\\w+) Location (\\d+)\\s*$').matcher(assembly)
		while (locationMatcher.find()) {
			locations[locationMatcher.group(1)] = locationMatcher.group(2) as int
		}
		def payloads = [:]
		def payloadMatcher = java.util.regex.Pattern.compile(
				'(?m)^\\s*%(\\w+) = OpVariable %\\w+ (\\w+)\\s*$')
				.matcher(assembly)
		while (payloadMatcher.find()) {
			if (payloadMatcher.group(2) != storageClass) {
				continue
			}
			def id = payloadMatcher.group(1)
			def name = names[id]
			if (name == null) {
				throw new GradleException("${storageClass} variable %${id} has no SPIR-V name")
			}
			if (locations[id] == null) {
				throw new GradleException("${storageClass} variable ${name} has no Location")
			}
			payloads[name] = locations[id]
		}
		return payloads
	}

	@TaskAction
	void verify() {
		def root = new File(slangDirectory.get().asFile, 'prime/shaders')
		def shaders = (root.listFiles() ?: [] as File[])
				.findAll { file ->
					file.name.endsWith('.rgen.spv')
							|| file.name.endsWith('.rmiss.spv')
							|| file.name.endsWith('.rchit.spv')
							|| file.name.endsWith('.rahit.spv')
				}
				.sort { it.name }
		if (shaders.empty) {
			throw new GradleException('No production Slang ray-tracing shaders were found')
		}

		def expectedOutgoing = [primeSurfacePayload: 0, primeShadowPayload: 1]
		def extSerModules = []
		shaders.each { shader ->
			def assembly = PrimeShaderTool.run(
					[spirvDisassembler.get(), shader.absolutePath])
			if (shader.name.endsWith('_ser.rgen.spv')) {
				if (assembly.contains('SPV_NV_shader_invocation_reorder')
						|| assembly.contains('OpReorderThreadWithHitObjectNV')) {
					throw new GradleException(
							"SER shader ${shader.name} uses the vendor-specific NV dialect")
				}
				if (assembly.contains('OpReorderThreadWithHitObjectEXT')) {
					if (!assembly.contains('SPV_EXT_shader_invocation_reorder')) {
						throw new GradleException(
								"SER shader ${shader.name} uses EXT reorder instructions without the EXT declaration")
					}
					extSerModules.add(shader.name)
				}
			}
			def outgoing = VerifySlangRayPayloadAbi.payloadLocations(
					assembly, 'RayPayloadKHR')
			outgoing.each { name, location ->
				if (expectedOutgoing[name] == null) {
					throw new GradleException(
							"Unexpected outgoing payload ${name} in ${shader.name}")
				}
				if (expectedOutgoing[name] != location) {
					throw new GradleException(
							"Outgoing payload ${name} in ${shader.name} uses location ${location}; "
									+ "expected ${expectedOutgoing[name]}")
				}
			}

			def incoming = VerifySlangRayPayloadAbi.payloadLocations(
					assembly, 'IncomingRayPayloadKHR')
			if (!incoming.isEmpty()) {
				def expectedLocation = (shader.name.startsWith('world.')
						|| shader.name.startsWith('world_')) ? 0
						: (shader.name.startsWith('shadow.')
						|| shader.name.startsWith('shadow_')) ? 1 : null
				if (expectedLocation == null) {
					throw new GradleException(
							"Incoming payload stage has no declared ABI class: ${shader.name}")
				}
				incoming.each { name, location ->
					if (location != expectedLocation) {
						throw new GradleException(
								"Incoming payload ${name} in ${shader.name} uses location ${location}; "
										+ "expected ${expectedLocation}")
					}
				}
			}
		}
		if (extSerModules.empty) {
			throw new GradleException(
					'No production SER shader emitted SPV_EXT_shader_invocation_reorder')
		}

		def report = reportFile.get().asFile
		report.parentFile.mkdirs()
		report.setText([
				'payloadAbi=valid',
				"shaderCount=${shaders.size()}",
				"extSerModules=${extSerModules.sort().join(',')}"
		].join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
	}
}
