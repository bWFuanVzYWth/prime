// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeArchitecturePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.with {
def architectureReport = layout.buildDirectory.dir('reports/prime-architecture')
def architectureClasses = layout.buildDirectory.dir('classes/java/client')

def normalizeClass = { String name ->
    int nested = name.indexOf('$')
    nested < 0 ? name : name.substring(0, nested)
}

def packageName = { String name ->
    int separator = name.lastIndexOf('.')
    separator < 0 ? '' : name.substring(0, separator)
}

def architectureLayers = ['client', 'runtime', 'adapter', 'vulkan', 'core', 'support'] as Set
def allowedLayerTargets = [
        client:  ['client', 'runtime', 'adapter', 'vulkan', 'core', 'support'] as Set,
        runtime: ['runtime', 'adapter', 'vulkan', 'core', 'support'] as Set,
        adapter: ['adapter', 'core', 'support'] as Set,
        vulkan:  ['vulkan', 'core', 'support'] as Set,
        core:    ['core', 'support'] as Set,
        support: ['support'] as Set]

def layerOf = { String name ->
    String candidatePackage = packageName(name)
    if (candidatePackage == 'dev.prime.mixin.accessor'
            || candidatePackage.startsWith('dev.prime.mixin.accessor.')) {
        return 'adapter'
    }
    if (name == 'dev.prime.PrimeClient'
            || candidatePackage == 'dev.prime.client'
            || candidatePackage.startsWith('dev.prime.client.')
            || candidatePackage == 'dev.prime.config'
            || candidatePackage.startsWith('dev.prime.config.')
            || candidatePackage == 'dev.prime.mixin'
            || candidatePackage.startsWith('dev.prime.mixin.')) {
        return 'client'
    }
    if (candidatePackage == 'dev.prime.render.runtime'
            || candidatePackage.startsWith('dev.prime.render.runtime.')) {
        return 'runtime'
    }
    // Minecraft capture is a side adapter, not a semantic or GPU layer.
    if (candidatePackage == 'dev.prime.render.scene.vanilla'
            || candidatePackage.startsWith('dev.prime.render.scene.vanilla.')) {
        return 'adapter'
    }
    if (candidatePackage == 'dev.prime.render.vulkan'
            || candidatePackage.startsWith('dev.prime.render.vulkan.')) {
        return 'vulkan'
    }
    if (candidatePackage == 'dev.prime.infrastructure'
            || candidatePackage.startsWith('dev.prime.infrastructure.')) {
        return 'support'
    }
    if (candidatePackage == 'dev.prime.render'
            || candidatePackage.startsWith('dev.prime.render.')) {
        return 'core'
    }
    return 'other'
}

def stronglyConnected = { Map<String, Set<String>> graph ->
    Set<String> nodes = new TreeSet<>()
    graph.each { source, targets ->
        nodes.add(source)
        nodes.addAll(targets)
    }
    Set<String> visited = new HashSet<>()
    List<String> order = []
    Closure<Void> visit
    visit = { String node ->
        if (!visited.add(node)) {
            return
        }
        (graph[node] ?: Collections.emptySet()).each { visit(it) }
        order.add(node)
    }
    nodes.each { visit(it) }

    Map<String, Set<String>> reverse = new HashMap<>()
    nodes.each { reverse[it] = new TreeSet<>() }
    graph.each { source, targets ->
        targets.each { reverse[it].add(source) }
    }
    visited.clear()
    List<Set<String>> components = []
    Closure<Void> collect
    collect = { String node ->
        if (!visited.add(node)) {
            return
        }
        components.last().add(node)
        reverse[node].each { collect(it) }
    }
    order.reverseEach { node ->
        if (!visited.contains(node)) {
            components.add(new TreeSet<>())
            collect(node)
        }
    }
    components
}

def forbiddenEdges = { Map<String, Set<String>> graph ->
    List<String> violations = []
    graph.each { source, targets ->
        String sourcePackage = packageName(source)
        String sourceLayer = layerOf(source)
        targets.each { target ->
            String targetPackage = packageName(target)
            String targetLayer = layerOf(target)
            if (target.startsWith('dev.prime.')
                    && architectureLayers.contains(sourceLayer)
                    && architectureLayers.contains(targetLayer)
                    && !allowedLayerTargets[sourceLayer].contains(targetLayer)) {
                violations.add(
                        "forbidden layer dependency (${sourceLayer} -> ${targetLayer}): ${source} -> ${target}")
            }
            boolean corePlatformDependency = sourceLayer == 'core'
                    && (targetPackage == 'net.minecraft.client'
                            || targetPackage.startsWith('net.minecraft.client.')
                            || targetPackage == 'com.mojang.blaze3d'
                            || targetPackage.startsWith('com.mojang.blaze3d.')
                            || targetPackage == 'com.mojang.renderpearl'
                            || targetPackage.startsWith('com.mojang.renderpearl.')
                            || targetPackage == 'org.lwjgl'
                            || targetPackage.startsWith('org.lwjgl.')
                            || targetPackage == 'org.spongepowered.asm.mixin'
                            || targetPackage.startsWith('org.spongepowered.asm.mixin.')
                            || targetPackage == 'net.fabricmc'
                            || targetPackage.startsWith('net.fabricmc.'))
            if (corePlatformDependency) {
                violations.add("core depends on client/GPU platform: ${source} -> ${target}")
            }
            if (sourceLayer == 'adapter'
                    && (targetPackage == 'org.lwjgl'
                            || targetPackage.startsWith('org.lwjgl.'))) {
                violations.add("Minecraft adapter depends on LWJGL: ${source} -> ${target}")
            }
        }
    }
    violations.sort()
}

def verifyArchitecture = tasks.register('verifyArchitecture') {
    group = 'verification'
    description = 'Verifies compiled Prime class dependencies and cross-layer SCCs with jdeps.'
    dependsOn tasks.named('compileClientJava')
    inputs.dir(architectureClasses)
    outputs.dir(architectureReport)

    doLast {
        File classes = architectureClasses.get().asFile
        File reportDirectory = architectureReport.get().asFile
        reportDirectory.mkdirs()
        String executableName = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('win')
                ? 'jdeps.exe'
                : 'jdeps'
        File jdeps = new File(System.getProperty('java.home'), "bin/${executableName}")
        if (!jdeps.isFile()) {
            throw new GradleException("JDK jdeps was not found at ${jdeps}")
        }
        Process process = new ProcessBuilder(
                jdeps.absolutePath,
                '--multi-release', '21',
                '--ignore-missing-deps',
                '-verbose:class',
                '-filter:none',
                classes.absolutePath)
                .redirectErrorStream(true)
                .start()
        String output = process.inputStream.getText('UTF-8')
        int exitCode = process.waitFor()
        new File(reportDirectory, 'jdeps.txt').setText(output, 'UTF-8')
        if (exitCode != 0) {
            throw new GradleException("jdeps failed with exit code ${exitCode}")
        }

        Map<String, Set<String>> dependencies = new TreeMap<>()
        output.eachLine { line ->
            def match = line =~ /^\s+(dev\.prime\.[^\s]+)\s+->\s+([^\s]+)\s+.*$/
            if (match.matches()) {
                String source = normalizeClass(match.group(1))
                String target = normalizeClass(match.group(2))
                if (source != target) {
                    dependencies.computeIfAbsent(source) { new TreeSet<>() }.add(target)
                }
            }
        }
        Map<String, Set<String>> graph = new TreeMap<>()
        dependencies.each { source, targets ->
            targets.findAll { it.startsWith('dev.prime.') }.each { target ->
                graph.computeIfAbsent(source) { new TreeSet<>() }.add(target)
            }
        }

        // Executable detector self-test: layer, platform and SCC rules must all trip.
        Map<String, Set<String>> injected = [
                'dev.prime.render.terrain.InjectedCore':
                        ['dev.prime.client.InjectedClient', 'org.lwjgl.vulkan.VK12'] as Set,
                'dev.prime.client.InjectedClient':
                        ['dev.prime.render.terrain.InjectedCore'] as Set,
                'dev.prime.render.scene.vanilla.InjectedAdapter':
                        ['dev.prime.render.vulkan.InjectedGpu'] as Set]
        if (forbiddenEdges(injected).size() < 3
                || !stronglyConnected(injected).any { component ->
                    component.collect { layerOf(it) }.toSet().intersect(
                            architectureLayers).size() > 1
                }) {
            throw new GradleException('Architecture verifier injection self-test did not fail')
        }

        List<String> violations = forbiddenEdges(dependencies)
        List<Set<String>> components = stronglyConnected(graph)
        List<Set<String>> crossLayer = components.findAll { component ->
                    component.size() > 1
                    && component.collect { layerOf(it) }.toSet().intersect(
                            architectureLayers).size() > 1
        }
        crossLayer.each { component ->
            violations.add("cross-layer SCC: ${component.join(', ')}")
        }

        Map<String, Set<String>> packageGraph = new TreeMap<>()
        Map<String, Set<String>> layerGraph = new TreeMap<>()
        graph.each { source, targets ->
            String sourcePackage = packageName(source)
            String sourceLayer = layerOf(source)
            targets.each { target ->
                String targetPackage = packageName(target)
                String targetLayer = layerOf(target)
                if (sourcePackage != targetPackage) {
                    packageGraph.computeIfAbsent(sourcePackage) { new TreeSet<>() }
                            .add(targetPackage)
                }
                if (sourceLayer != targetLayer) {
                    layerGraph.computeIfAbsent(sourceLayer) { new TreeSet<>() }
                            .add(targetLayer)
                }
            }
        }

        new File(reportDirectory, 'class-edges.txt').setText(
                graph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'scc.txt').setText(
                components.findAll { it.size() > 1 }
                        .collect { it.join(', ') }
                        .join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'package-edges.txt').setText(
                packageGraph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'layer-edges.txt').setText(
                layerGraph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'violations.txt').setText(
                violations.join(System.lineSeparator())
                        + (violations.isEmpty() ? '' : System.lineSeparator()),
                'UTF-8')
        new File(reportDirectory, 'summary.txt').setText(
                "classes=${(graph.keySet() + graph.values().flatten()).toSet().size()}\n"
                        + "edges=${graph.values().sum { it.size() } ?: 0}\n"
                        + "packages=${(packageGraph.keySet() + packageGraph.values().flatten()).toSet().size()}\n"
                        + "layerEdges=${layerGraph.values().sum { it.size() } ?: 0}\n"
                        + "scc=${components.count { it.size() > 1 }}\n"
                        + "crossLayerScc=${crossLayer.size()}\n"
                        + "violations=${violations.size()}\n"
                        + "injectionSelfTest=passed\n",
                'UTF-8')
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Prime architecture violations: ${violations.size()}; see ${reportDirectory}")
        }
    }
}

tasks.named('check') {
    dependsOn verifyArchitecture
}
        }
    }
}
