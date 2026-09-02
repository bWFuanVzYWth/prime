package dev.prime.gradle.shader

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates runtime resource and schedule declarations from shaders/programs.json. */
@CacheableTask
abstract class GeneratePrimeShaderPrograms extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getManifestFile()

    @OutputDirectory
    abstract DirectoryProperty getJavaOutputDirectory()

    private static String quote(String value) {
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    }

    @TaskAction
    void generate() {
        def manifest = PrimeShaderManifest.read(manifestFile.get().asFile)
        def artifacts = manifest.artifacts
        def source = new StringBuilder('''package dev.prime.render.vulkan;

import java.util.List;

/** Generated from shaders/programs.json; do not edit. */
public final class GeneratedShaderPrograms {
    private GeneratedShaderPrograms() {}

    public static String resource(String id) {
        return switch (id) {
''')
        artifacts.each { id, artifact ->
            source.append('            case ').append(GeneratePrimeShaderPrograms.quote(id)).append(' -> ')
                    .append(GeneratePrimeShaderPrograms.quote('/prime/shaders/' + artifact.resource)).append(';\n')
        }
        source.append('            default -> throw new IllegalArgumentException("Unknown shader artifact: " + id);\n')
                .append('        };\n    }\n\n')
                .append('    static RaygenSchedule schedule(String id) {\n        return switch (id) {\n')
        manifest.schedules.each { id, schedule ->
            def modules = schedule.modules.collect { artifactId ->
                if (!artifacts.containsKey(artifactId)) {
                    throw new GradleException("Schedule ${id} references unknown artifact ${artifactId}")
                }
                return 'resource(' + GeneratePrimeShaderPrograms.quote(artifactId) + ')'
            }
            def groupModules = schedule.groups.collect { it.module }
            def controls = schedule.groups.collect { it.control }
            source.append('            case ').append(GeneratePrimeShaderPrograms.quote(id)).append(' -> RaygenSchedule.of(List.of(')
                    .append(modules.join(', ')).append('), new int[] {')
                    .append(groupModules.join(', ')).append('}, new int[] {')
                    .append(controls.join(', ')).append('});\n')
        }
        source.append('            default -> throw new IllegalArgumentException("Unknown shader schedule: " + id);\n')
                .append('        };\n    }\n}\n')
        def output = new File(javaOutputDirectory.get().asFile,
                'dev/prime/render/vulkan/GeneratedShaderPrograms.java')
        output.parentFile.mkdirs()
        output.setText(source.toString(), 'UTF-8')
    }
}
