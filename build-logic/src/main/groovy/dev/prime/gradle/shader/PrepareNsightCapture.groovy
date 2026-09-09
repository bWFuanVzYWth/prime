// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

abstract class PrepareNsightCapture extends DefaultTask {
	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getLaunchConfig()

	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getArgumentFile()

	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getDebugShader()

	@InputFile
	@PathSensitive(PathSensitivity.NONE)
	abstract RegularFileProperty getJavaExecutable()

	@Input
	abstract Property<String> getWorkingDirectory()

	@Input
	abstract Property<Boolean> getFullShaderDebug()

	@Input
	abstract Property<String> getUserCountry()

	@Input
	abstract Property<String> getUserLanguage()

	@Input
	abstract Property<String> getUserVariant()

	@OutputFile
	abstract RegularFileProperty getReportFile()

	@TaskAction
	void prepare() {
		if (!fullShaderDebug.get()) {
			throw new GradleException(
					'Nsight capture requires primeShaderFullDebug=true so Slang emits -g2 debug information')
		}
		def shaderText = new String(
				debugShader.get().asFile.bytes,
				java.nio.charset.StandardCharsets.ISO_8859_1)
		if (!shaderText.contains('NonSemantic.Shader.DebugInfo.100')) {
			throw new GradleException(
					'Compiled production SPIR-V does not contain standard Slang debug information')
		}

		def javaPath = javaExecutable.get().asFile.absolutePath
		def workPath = workingDirectory.get()
		def argumentPath = argumentFile.get().asFile.absolutePath
		def configPath = launchConfig.get().asFile.absolutePath
		def arguments = [
				'-Dfile.encoding=UTF-8',
				"-Duser.country=${userCountry.get()}",
				"-Duser.language=${userLanguage.get()}",
				"-Duser.variant=${userVariant.get()}",
				"\"@${argumentPath}\"",
				"\"-Dfabric.dli.config=${configPath}\"",
				'-Dfabric.dli.env=client',
				'-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient',
				'--sun-misc-unsafe-memory-access=allow',
				'--enable-native-access=ALL-UNNAMED',
				'net.fabricmc.devlaunchinjector.Main'
		].join(' ')
		def report = reportFile.get().asFile
		report.parentFile.mkdirs()
		report.setText([
				'Nsight Graphics launch fields',
				'',
				'Executable:',
				javaPath,
				'',
				'Working directory:',
				workPath,
				'',
				'Command-line arguments:',
				arguments
		].join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
		logger.lifecycle("Nsight launch fields: ${report.absolutePath}")
	}
}
