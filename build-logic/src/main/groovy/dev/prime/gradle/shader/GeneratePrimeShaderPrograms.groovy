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

    private static String constant(String schedule, String group) {
        return (schedule + '_' + group).replaceAll('[^A-Za-z0-9]+', '_').toUpperCase()
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

''')
        def groupConstants = new LinkedHashMap<String, Integer>()
        manifest.schedules.each { id, schedule ->
            String topology = id.replaceFirst(/\.(scalar|ser)$/, '')
            schedule.groups.eachWithIndex { group, index ->
                String name = GeneratePrimeShaderPrograms.constant(topology, group.name)
                Integer previous = groupConstants.putIfAbsent(name, index)
                if (previous != null && previous != index) {
                    throw new GradleException("Shader group ${name} changes index between variants")
                }
            }
        }
        groupConstants.each { name, index ->
            source.append('    static final int ').append(name).append(' = ')
                    .append(index).append(';\n')
        }
        source.append('''
    public static String resource(String id) {
        return switch (id) {
''')
        artifacts.each { id, artifact ->
            source.append('            case ').append(GeneratePrimeShaderPrograms.quote(id)).append(' -> ')
                    .append(GeneratePrimeShaderPrograms.quote('/prime/shaders/' + artifact.resource)).append(';\n')
        }
        source.append('            default -> throw new IllegalArgumentException("Unknown shader artifact: " + id);\n')
                .append('        };\n    }\n\n')
                .append('    static RaygenSchedule schedule(String id, String suffix) {\n')
                .append('        String variant = switch (suffix) {\n')
                .append('            case ".rgen.spv" -> "scalar";\n')
                .append('            case "_ser.rgen.spv" -> "ser";\n')
                .append('            default -> throw new IllegalArgumentException("Unknown wavefront shader suffix: " + suffix);\n')
                .append('        };\n')
                .append('        return schedule(id + "." + variant);\n    }\n\n')
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
