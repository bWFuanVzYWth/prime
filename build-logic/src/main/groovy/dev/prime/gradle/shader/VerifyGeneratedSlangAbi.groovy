package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

abstract class VerifyGeneratedSlangAbi extends DefaultTask {
	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getSchemaFile()

	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getSmokeShader()

	@InputDirectory
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract DirectoryProperty getGeneratedIncludeDirectory()

	@Input
	abstract Property<String> getSlangCompiler()

	@Input
	abstract Property<String> getSpirvValidator()

	@Input
	abstract Property<String> getSpirvDisassembler()

	private static String runAndCapture(List<String> arguments) {
		def command = arguments.collect { it.toString() }
		def process
		try {
			process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start()
		} catch (IOException exception) {
			throw new GradleException("Could not start shader tool: ${command[0]}", exception)
		}
		def output = process.inputStream.getText('UTF-8')
		try {
			def exitCode = process.waitFor()
			if (exitCode != 0) {
				throw new GradleException(
						"Shader tool failed with exit code ${exitCode}: ${command[0]}\n${output}")
			}
		} catch (InterruptedException exception) {
			process.destroyForcibly()
			Thread.currentThread().interrupt()
			throw new GradleException("Shader tool was interrupted: ${command[0]}", exception)
		}
		return output.trim()
	}

	@TaskAction
	void verify() {
		def output = new File(temporaryDir, 'generated_abi_smoke.spv')
		output.parentFile.mkdirs()
		runAndCapture([
				slangCompiler.get(),
				smokeShader.get().asFile.absolutePath,
				'-I', generatedIncludeDirectory.get().asFile.absolutePath,
				'-target', 'spirv',
				'-profile', 'glsl_460',
				'-capability', 'spirv_1_5',
				'-entry', 'main',
				'-stage', 'compute',
				'-matrix-layout-row-major',
				'-fvk-use-gl-layout',
				'-emit-spirv-directly',
				'-warnings-as-errors', 'all',
				'-O0', '-g0',
				'-o', output.absolutePath
		])
		runAndCapture([
				spirvValidator.get(),
				'--target-env', 'vulkan1.2',
				output.absolutePath
		])
		def assembly = runAndCapture([spirvDisassembler.get(), output.absolutePath])
		def schema = new groovy.json.JsonSlurper().parse(schemaFile.get().asFile)
		def structNames = [
				primitiveRecord: 'PrimitiveRecord',
				sectionRecord: 'SectionRecord',
				lightNode: 'LightNode',
				lightLeaf: 'LightLeaf',
				lightEmitter: 'LightEmitter',
				lightCell: 'LightCell',
				sectionLightHeader: 'SectionLightHeader',
				integratorRecord: 'IntegratorRecord',
				pathState: 'PathState',
				tracePayload: 'TracePayload',
				surfaceInteraction: 'SurfaceInteraction',
				nrdMotionPushConstants: 'NrdMotionPushConstants',
				sunShadowQueryConstants: 'SunShadowQueryConstants'
		]
		def missing = []
		structNames.each { schemaName, slangName ->
			def definition = schema.structs[schemaName]
			definition.fields.eachWithIndex { field, index ->
				def pattern = "(?m)^\\s*OpMemberDecorate %${slangName}_std430 ${index} Offset ${field.offset}\\s*\$"
				if (!java.util.regex.Pattern.compile(pattern).matcher(assembly).find()) {
					missing.add("${slangName}.${field.name} offset ${field.offset}")
				}
			}
			def stridePattern = "(?m)^\\s*OpDecorate %_runtimearr_${slangName}_std430 ArrayStride ${definition.size}\\s*\$"
			if (!java.util.regex.Pattern.compile(stridePattern).matcher(assembly).find()) {
				missing.add("${slangName} stride ${definition.size}")
			}
		}
		def section = schema.structs.sectionRecord
		def physicalSectionStride =
				"(?m)^\\s*OpDecorate %_ptr_PhysicalStorageBuffer_SectionRecord_natural ArrayStride ${section.size}\\s*\$"
		if (!java.util.regex.Pattern.compile(physicalSectionStride).matcher(assembly).find()) {
			missing.add("SectionRecord physical-pointer stride ${section.size}")
		}
		def push = schema.structs.pushConstants
		push.fields.eachWithIndex { field, index ->
			def pattern = "(?m)^\\s*OpMemberDecorate %PrimePushConstants_std430 ${index} Offset ${field.offset}\\s*\$"
			if (!java.util.regex.Pattern.compile(pattern).matcher(assembly).find()) {
				missing.add("PrimePushConstants.${field.name} offset ${field.offset}")
			}
		}
		[
				'(?m)^\\s*OpMemberDecorate %PrimePushConstants_std430 0 ColMajor\\s*$',
				'(?m)^\\s*OpMemberDecorate %PrimePushConstants_std430 0 MatrixStride 16\\s*$',
				'(?m)^\\s*OpDecorate %outputValues Binding 14\\s*$',
				'(?m)^\\s*OpDecorate %outputValues DescriptorSet 0\\s*$',
				'(?m)^\\s*%\\w+ = OpTypePointer PushConstant '
		].each { pattern ->
			if (!java.util.regex.Pattern.compile(pattern).matcher(assembly).find()) {
				missing.add(pattern)
			}
		}
		if (!missing.empty) {
			throw new GradleException(
					"Generated Slang ABI does not match shaders/abi.json: ${missing}")
		}
	}
}
