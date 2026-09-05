package dev.prime.gradle.shader

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

/** Expands the concise source/variant declarations into the build's artifact model. */
final class PrimeShaderManifest {
    private static final Map<String, List<String>> STAGES = [
            '.compute.slang': ['compute', 'comp'],
            '.raygeneration.slang': ['raygeneration', 'rgen'],
            '.miss.slang': ['miss', 'rmiss'],
            '.closesthit.slang': ['closesthit', 'rchit'],
            '.anyhit.slang': ['anyhit', 'rahit']]
    private static final List<String> SER_DEFINITIONS = [
            '-DPRIME_ENABLE_SER=1',
            '-DPRIME_ENABLE_SUBGROUP_QUEUE=1']

    private PrimeShaderManifest() {}

    static Map read(File file) {
        def compact = new JsonSlurper().parse(file)
        if (compact.schema != 2) {
            throw new GradleException("Unsupported shader program schema ${compact.schema}")
        }
        def artifacts = new LinkedHashMap<String, Map>()
        compact.artifacts.each { String id, declaration ->
            def entry = declaration instanceof CharSequence
                    ? declaration.toString()
                    : declaration.entry?.toString()
            if (entry == null) {
                throw new GradleException("Shader artifact ${id} has no entry")
            }
            def stage = stage(entry)
            if (stage == null) {
                throw new GradleException("Shader artifact ${id} has an unknown stage: ${entry}")
            }
            addArtifact(artifacts, id, entry, stage.value, [])
            if (!(declaration instanceof CharSequence) && declaration.ser == true) {
                addArtifact(artifacts, id + '_ser', entry, stage.value, SER_DEFINITIONS)
            }
            if (!(declaration instanceof CharSequence) && declaration.subgroup == true) {
                addArtifact(artifacts, id + '_subgroup', entry, stage.value, ['-DPRIME_ENABLE_SUBGROUP_QUEUE=1'])
            }
        }
        def schedules = new LinkedHashMap<String, Map>()
        compact.schedules.each { String id, schedule ->
            if (!(schedule.groups instanceof Map) || schedule.groups.isEmpty()) {
                throw new GradleException("Shader schedule ${id} has no named groups")
            }
            (schedule.variants ?: [null]).each { variant ->
                if (!(variant in [null, 'scalar', 'ser', 'subgroup'])) {
                    throw new GradleException("Shader schedule ${id} has an unknown variant ${variant}")
                }
                def modules = []
                def groups = schedule.groups.collect { String name, group ->
                    if (!(group instanceof List) || group.size() != 2) {
                        throw new GradleException(
                                "Shader schedule ${id} has an invalid group ${name}")
                    }
                    String module = group[0]
                    def variantId = variant == null ? module : module + '_' + variant
                    String resolved = artifacts.containsKey(variantId) ? variantId : module
                    if (!artifacts.containsKey(resolved)) {
                        throw new GradleException(
                                "Shader schedule ${id} references unknown artifact ${module}")
                    }
                    int moduleIndex = modules.indexOf(resolved)
                    if (moduleIndex < 0) {
                        moduleIndex = modules.size()
                        modules.add(resolved)
                    }
                    [name: name, module: moduleIndex, control: group[1]]
                }
                schedules[variant == null ? id : id + '.' + variant] =
                        [modules: modules, groups: groups]
            }
        }
        return [schema: compact.schema, artifacts: artifacts, schedules: schedules]
    }

    static Map.Entry<String, List<String>> stage(String entry) {
        return STAGES.find { suffix, ignored -> entry.endsWith(suffix) }
    }

    private static void addArtifact(
            Map<String, Map> artifacts,
            String id,
            String entry,
            List<String> stage,
            List<String> definitions) {
        String legacyStage = stage[1]
        String suffix = '_' + legacyStage
        String resourceStem = id.endsWith(suffix)
                ? id.substring(0, id.length() - suffix.length())
                : id
        artifacts[id] = [
                source: 'shaders/' + entry,
                stage: stage[0],
                resource: resourceStem + '.' + legacyStage + '.spv',
                definitions: definitions]
    }
}
