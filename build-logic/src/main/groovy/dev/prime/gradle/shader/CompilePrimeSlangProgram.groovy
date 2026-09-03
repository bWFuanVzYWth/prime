package dev.prime.gradle.shader

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Compiles and validates exactly one manifest-declared Slang artifact. */
@CacheableTask
abstract class CompilePrimeSlangProgram extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSourceFile()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getShaderDependencies()

    @Internal
    abstract ConfigurableFileCollection getIncludeDirectories()

    @Input
    abstract Property<String> getArtifactId()

    @Input
    abstract Property<String> getStage()

    @Input
    abstract ListProperty<String> getDefinitions()

    @Input
    abstract Property<String> getSlangCompiler()

    @Input
    abstract Property<String> getSlangToolchainVersion()

    @Input
    abstract Property<String> getSpirvValidator()

    @Input
    abstract Property<String> getDebugLevel()

    /** -g2 embeds canonical source paths, so cross-workspace cache reuse would corrupt debug paths. */
    @Input
    abstract Property<String> getCanonicalWorkspacePath()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @OutputFile
    abstract RegularFileProperty getDependencyFile()

    @OutputFile
    abstract RegularFileProperty getMetricsFile()

    @TaskAction
    void compile() {
        def output = outputFile.get().asFile
        def depfile = dependencyFile.get().asFile
        def metrics = metricsFile.get().asFile
        output.parentFile.mkdirs()
        depfile.parentFile.mkdirs()
        metrics.parentFile.mkdirs()

        def arguments = PrimeShaderTool.compileArguments(
                slangCompiler.get(), sourceFile.get().asFile, stage.get(), debugLevel.get())
        arguments.addAll(definitions.get())
        includeDirectories.files.findAll { it.isDirectory() }.sort { it.absolutePath }.each {
            arguments.addAll(['-I', it.absolutePath])
        }
        arguments.addAll(['-depfile', depfile.absolutePath, '-o', output.absolutePath])

        long started = System.nanoTime()
        PrimeShaderTool.run(arguments)
        long compiled = System.nanoTime()
        def compilerDependencies = PrimeShaderTool.dependencies(depfile)
        def declaredDependencies = shaderDependencies.files.collect {
            it.canonicalPath
        }.toSet()
        if (declaredDependencies != compilerDependencies) {
            throw new GradleException(
                    "Slang dependency closure drift for ${artifactId.get()}; "
                            + "declaredOnly=${declaredDependencies - compilerDependencies}, "
                            + "compilerOnly=${compilerDependencies - declaredDependencies}")
        }
        PrimeShaderTool.run(
                [spirvValidator.get(), '--target-env', 'vulkan1.2', output.absolutePath])
        long finished = System.nanoTime()
        metrics.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                artifact: artifactId.get(),
                compileNanoseconds: compiled - started,
                validationNanoseconds: finished - compiled,
                outputBytes: output.length(),
                dependencyCount: declaredDependencies.size()
        ])) + System.lineSeparator(), 'UTF-8')
    }
}
