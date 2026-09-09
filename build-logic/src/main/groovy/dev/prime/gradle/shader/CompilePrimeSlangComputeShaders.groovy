// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import groovy.json.JsonSlurper

@org.gradle.api.tasks.CacheableTask
abstract class CompilePrimeSlangComputeShaders extends DefaultTask {
	private static final java.util.concurrent.ConcurrentHashMap<
			String, java.util.concurrent.Semaphore> compilerGates =
			new java.util.concurrent.ConcurrentHashMap<>()

	@Internal
	abstract DirectoryProperty getSourceDirectory()

	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getProgramManifest()

	@org.gradle.work.Incremental
	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract ConfigurableFileCollection getIncludeDirectories()

	@OutputDirectory
	abstract DirectoryProperty getOutputDirectory()

	@Input
	abstract Property<String> getSlangCompiler()

	@Input
	abstract Property<String> getSpirvValidator()

	@Input
	abstract Property<String> getDebugLevel()

	@Input
	abstract Property<Integer> getMaxCompilerProcesses()

	@Internal
	abstract DirectoryProperty getCompilerGateRoot()

	@javax.inject.Inject
	abstract org.gradle.api.file.FileSystemOperations getFileSystemOperations()

	@TaskAction
	void compile(org.gradle.work.InputChanges inputChanges) {
		def definitions = new JsonSlurper().parse(programManifest.get().asFile).programs
				.collectEntries { [(it.entry.toString()): (it.definitions ?: [])] }
		def sources = sourceDirectory.get().asFileTree.matching {
			include '**/*.compute.slang',
					'**/*.raygeneration.slang',
					'**/*.miss.slang',
					'**/*.closesthit.slang',
					'**/*.anyhit.slang'
		}.files.sort { it.absolutePath }
		if (sources.empty) {
			throw new GradleException('No Prime Slang entry points were found')
		}

		def includes = includeDirectories.files
				.findAll { it.isDirectory() }
				.sort { it.absolutePath }
		def published = new File(outputDirectory.get().asFile, 'prime/shaders')
		def incremental = inputChanges.incremental && published.isDirectory()
		def changedPaths = incremental
				? inputChanges.getFileChanges(includeDirectories).collect {
					it.file.canonicalPath
				}.toSet()
				: Collections.emptySet()
		def dependencyGraph = PrimeShaderDependencyGraph.graph(includes)
		def dependencyClosures = new HashMap<String, Set<String>>()
		Closure<Boolean> requiresCompilation = { File source, File output ->
			if (!incremental || !output.isFile()) {
				return true
			}
			def dependencies = dependencyClosures.computeIfAbsent(source.canonicalPath) {
				PrimeShaderDependencyGraph.paths(source, dependencyGraph)
			}
			return !Collections.disjoint(dependencies, changedPaths)
		}

		def scratch = new File(temporaryDir, 'spv')
		fileSystemOperations.delete { delete scratch }
		scratch.mkdirs()
		def compiler = slangCompiler.get()
		def validator = spirvValidator.get()
		def processLimit = maxCompilerProcesses.get()
		def compilerGateKey = "${compilerGateRoot.get().asFile.canonicalPath}|${processLimit}"
		def compilerGate = compilerGates.computeIfAbsent(compilerGateKey) {
			new java.util.concurrent.Semaphore(processLimit, true)
		}
		def compilationUnits = []
		def expectedOutputs = new TreeSet<String>()
		sources.each { source ->
			def stage = PrimeShaderManifest.stage(source.name)
			if (stage == null) {
				throw new GradleException("Unknown Slang stage suffix: ${source}")
			}
			def stem = source.name.substring(
					0, source.name.length() - stage.key.length())
			def outputName = "${stem}.${stage.value[1]}.spv".toString()
			expectedOutputs.add(outputName)
			def output = new File(scratch, outputName)
			if (!requiresCompilation(source, new File(published, outputName))) {
				return
			}
			compilationUnits.add({
					def arguments = PrimeShaderTool.compileArguments(
							compiler, source, stage.value[0], debugLevel.get())
					arguments.addAll(definitions[source.name])
					includes.each { include ->
						arguments.addAll(['-I', include.absolutePath])
					}
					arguments.addAll(['-o', output.absolutePath])
					def permitAcquired = false
					try {
						compilerGate.acquire()
						permitAcquired = true
						PrimeShaderTool.run(arguments)
						PrimeShaderTool.run(
								[validator, '--target-env', 'vulkan1.2', output.absolutePath])
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt()
						throw new GradleException('Slang compiler scheduling was interrupted', exception)
					} finally {
						if (permitAcquired) {
							compilerGate.release()
						}
					}
					return null
				} as java.util.concurrent.Callable<Void>)
		}

		def compilerCount = compilationUnits.empty ? 0 : Math.min(
				compilationUnits.size(),
				Math.min(maxCompilerProcesses.get(), Runtime.runtime.availableProcessors()))
		logger.lifecycle(
				"Compiling ${compilationUnits.size()} of ${sources.size()} Slang unit(s) with "
						+ "${compilerCount} process(es)")
		if (!compilationUnits.empty) {
			def compilerPool = java.util.concurrent.Executors.newFixedThreadPool(compilerCount)
			try {
				def compilations = compilationUnits.collect { compilerPool.submit(it) }
				compilations.each { compilation ->
					try {
						compilation.get()
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt()
						throw new GradleException('Slang compilation was interrupted', exception)
					} catch (java.util.concurrent.ExecutionException exception) {
						def cause = exception.cause
						if (cause instanceof RuntimeException) {
							throw cause
						}
						throw new GradleException('Slang compilation failed', cause)
					}
				}
			} finally {
				compilerPool.shutdownNow()
			}
		}

		published.mkdirs()
		scratch.listFiles().each { source ->
			java.nio.file.Files.move(
					source.toPath(), new File(published, source.name).toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		}
		def manifestName = 'manifest.sha256'
		expectedOutputs.add(manifestName)
		published.listFiles().findAll { !expectedOutputs.contains(it.name) }.each {
			java.nio.file.Files.delete(it.toPath())
		}
		def manifest = expectedOutputs.findAll { it != manifestName }.collect { name ->
			def digest = java.security.MessageDigest.getInstance('SHA-256')
					.digest(new File(published, name).bytes)
					.encodeHex().toString()
			return "${digest}  ${name}"
		}.join(System.lineSeparator()) + System.lineSeparator()
		new File(published, manifestName).setText(manifest, 'UTF-8')
	}
}
