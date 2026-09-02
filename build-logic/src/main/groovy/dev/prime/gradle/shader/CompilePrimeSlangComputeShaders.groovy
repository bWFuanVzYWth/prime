package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

@org.gradle.api.tasks.CacheableTask
abstract class CompilePrimeSlangComputeShaders extends DefaultTask {
	private static final java.util.concurrent.ConcurrentHashMap<
			String, java.util.concurrent.Semaphore> compilerGates =
			new java.util.concurrent.ConcurrentHashMap<>()

	@Internal
	abstract DirectoryProperty getSourceDirectory()

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

	private static void runCommand(List<String> arguments) {
		def command = arguments.collect { it.toString() }
		def process = new ProcessBuilder(command).redirectErrorStream(true).start()
		def output = new java.io.ByteArrayOutputStream()
		def outputDrain = new Thread({
			process.inputStream.transferTo(output)
		}, 'prime-shader-tool-output')
		outputDrain.start()
		try {
			def exitCode = process.waitFor()
			outputDrain.join()
			if (exitCode != 0) {
				throw new GradleException(
						"Shader tool failed with exit code ${exitCode}: ${command.join(' ')}"
								+ System.lineSeparator()
								+ output.toString(java.nio.charset.StandardCharsets.UTF_8))
			}
		} catch (InterruptedException exception) {
			process.destroyForcibly()
			outputDrain.interrupt()
			Thread.currentThread().interrupt()
			throw new GradleException(
					"Shader tool was interrupted: ${command.join(' ')}", exception)
		}
	}

	private static Map<String, Set<String>> dependencyGraph(List<File> includeRoots) {
		def sources = []
		includeRoots.each { root ->
			if (root.isDirectory()) {
				root.eachFileRecurse(groovy.io.FileType.FILES) { source ->
					if (source.name.endsWith('.slang') || source.name.endsWith('.h')) {
						sources.add(source.canonicalFile)
					}
				}
			}
		}
		def sourcePaths = sources.collect { it.canonicalPath }.toSet()
		def dependencyPattern = java.util.regex.Pattern.compile(
				'(?m)^\\s*(?:#\\s*include\\s+"([^"]+)"|import\\s+"([^"]+)"\\s*;)')
		def graph = new HashMap<String, Set<String>>()
		sources.each { source ->
			def targets = graph.computeIfAbsent(source.canonicalPath) {
				new HashSet<String>()
			}
			def matcher = dependencyPattern.matcher(source.getText('UTF-8'))
			while (matcher.find()) {
				def dependencyName = matcher.group(1) ?: matcher.group(2)
				def candidates = ([new File(source.parentFile, dependencyName)]
						+ includeRoots.collect { new File(it, dependencyName) })
						.collect { it.canonicalFile }
						.findAll { sourcePaths.contains(it.canonicalPath) }
						.unique { it.canonicalPath }
				if (candidates.size() != 1) {
					throw new GradleException(
							"Cannot resolve unique shader dependency ${dependencyName} from ${source}")
				}
				targets.add(candidates.first().canonicalPath)
			}
		}
		return graph
	}

	private static Set<String> dependencyClosure(
			File source, Map<String, Set<String>> graph) {
		def result = new HashSet<String>()
		def pending = new ArrayDeque<String>()
		pending.add(source.canonicalPath)
		while (!pending.empty) {
			def current = pending.removeLast()
			if (!result.add(current)) {
				continue
			}
			(graph[current] ?: Collections.emptySet()).each { pending.add(it) }
		}
		return result
	}

	@TaskAction
	void compile(org.gradle.work.InputChanges inputChanges) {
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
		def dependencyGraph = dependencyGraph(includes)
		def dependencyClosures = new HashMap<String, Set<String>>()
		Closure<Boolean> requiresCompilation = { File source, File output ->
			if (!incremental || !output.isFile()) {
				return true
			}
			def dependencies = dependencyClosures.computeIfAbsent(source.canonicalPath) {
				CompilePrimeSlangComputeShaders.dependencyClosure(source, dependencyGraph)
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
			def stages = [
					'compute': [slang: 'compute', legacy: 'comp'],
					'raygeneration': [slang: 'raygeneration', legacy: 'rgen'],
					'miss': [slang: 'miss', legacy: 'rmiss'],
					'closesthit': [slang: 'closesthit', legacy: 'rchit'],
					'anyhit': [slang: 'anyhit', legacy: 'rahit']
			]
			def suffix = stages.keySet().find { source.name.endsWith(".${it}.slang") }
			if (suffix == null) {
				throw new GradleException("Unknown Slang stage suffix: ${source}")
			}
			def stage = stages[suffix]
			def stem = source.name.substring(
					0, source.name.length() - ".${suffix}.slang".length())
			def outputName = "${stem}.${stage.legacy}.spv".toString()
			expectedOutputs.add(outputName)
			def output = new File(scratch, outputName)
			if (!requiresCompilation(source, new File(published, outputName))) {
				return
			}
			compilationUnits.add({
					def arguments = [
					compiler,
					source.absolutePath,
					'-target', 'spirv',
					'-profile', 'glsl_460',
					'-capability', 'spirv_1_5',
					// Physical pointers and non-uniform sampled-image arrays make Slang
					// close the glsl_460 profile over these SPIR-V capability aliases. Naming
					// the closure explicitly keeps warnings-as-errors useful; unused
					// capabilities are not emitted into the resulting module.
					'-capability', 'SPV_KHR_non_semantic_info',
					'-capability', 'SPV_GOOGLE_user_type',
					'-capability', 'spvSparseResidency',
					'-capability', 'spvMinLod',
					'-capability', 'spvFragmentFullyCoveredEXT',
					'-capability', 'spvGroupNonUniform',
					'-capability', 'spvGroupNonUniformBallot',
					// The typed Slang HitObject API lowers to the vendor-neutral EXT dialect.
					// Naming its capability keeps warnings-as-errors useful without permitting
					// an accidental fallback to NV-suffixed GLSL intrinsics.
					'-capability', 'spvShaderInvocationReorderEXT',
					'-entry', 'main',
					'-stage', stage.slang,
					// Explicit ray-payload locations are a cross-stage Vulkan ABI contract. The
					// Slang compatibility layer exposes location-indexed payload/hit-object
					// intrinsics while the rest of each translation unit remains ordinary Slang.
					'-allow-glsl',
					'-matrix-layout-row-major',
					'-fvk-use-gl-layout',
					'-emit-spirv-directly',
					'-warnings-as-errors', 'all',
						'-O2', debugLevel.get()
					]
					includes.each { include ->
						arguments.addAll(['-I', include.absolutePath])
					}
					arguments.addAll(['-o', output.absolutePath])
					def permitAcquired = false
					try {
						compilerGate.acquire()
						permitAcquired = true
						CompilePrimeSlangComputeShaders.runCommand(arguments)
						CompilePrimeSlangComputeShaders.runCommand(
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
