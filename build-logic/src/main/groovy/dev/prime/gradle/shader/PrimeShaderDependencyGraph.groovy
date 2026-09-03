package dev.prime.gradle.shader

import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Shares one exact Slang dependency graph across all compile tasks in a build. */
abstract class PrimeShaderDependencyGraph
        implements BuildService<PrimeShaderDependencyGraph.Parameters>, AutoCloseable {
    interface Parameters extends BuildServiceParameters {
        ConfigurableFileCollection getIncludeRoots()
    }

    private static final java.util.regex.Pattern DEPENDENCY = java.util.regex.Pattern.compile(
            '(?m)^\\s*(?:#\\s*include\\s+"([^"]+)"|import\\s+"([^"]+)"\\s*;)')
    private static final java.util.regex.Pattern MODULE = java.util.regex.Pattern.compile(
            '(?m)^\\s*module\\s+"[^"]+"\\s*;')

    private Map<String, Set<String>> sourceGraph

    synchronized List<File> dependencies(File source) {
        if (sourceGraph == null) sourceGraph = graph(parameters.includeRoots.files)
        return paths(source, sourceGraph).collect { new File(it) }
    }

    static Map<String, Set<String>> graph(Collection<File> includeRoots) {
        def roots = includeRoots.findAll { it.isDirectory() }.collect { it.canonicalFile }
        def sources = []
        roots.each { root ->
            root.eachFileRecurse(groovy.io.FileType.FILES) { source ->
                if (source.name.endsWith('.slang') || source.name.endsWith('.h')) {
                    sources.add(source.canonicalFile)
                }
            }
        }
        def sourcePaths = sources.collect { it.canonicalPath }.toSet()
        def modulePaths = sources.findAll {
            MODULE.matcher(it.getText('UTF-8')).find()
        }.collect { it.canonicalPath }.toSet()
        def graph = new TreeMap<String, Set<String>>()
        sources.each { source ->
            def targets = graph.computeIfAbsent(source.canonicalPath) { new TreeSet<String>() }
            def matcher = DEPENDENCY.matcher(source.getText('UTF-8'))
            while (matcher.find()) {
                def name = matcher.group(1) ?: matcher.group(2)
                def matches = ([new File(source.parentFile, name)]
                        + roots.collect { new File(it, name) })
                        .collect { it.canonicalFile }
                        .findAll { sourcePaths.contains(it.canonicalPath) }
                        .unique { it.canonicalPath }
                if (matches.empty) {
                    throw new GradleException(
                            "Unresolved shader dependency ${name} from ${source}")
                }
                if (matches.size() != 1) {
                    throw new GradleException(
                            "Ambiguous shader dependency ${name} from ${source}: ${matches}")
                }
                if (matcher.group(1) != null
                        && modulePaths.contains(matches.first().canonicalPath)) {
                    throw new GradleException(
                            "Explicit shader module ${name} must be imported, not included, from ${source}")
                }
                targets.add(matches.first().canonicalPath)
            }
        }
        requireAcyclic(graph)
        return graph
    }

    private static void requireAcyclic(Map<String, Set<String>> graph) {
        def state = new HashMap<String, Integer>()
        def stack = []
        Closure<Void> visit
        visit = { String source ->
            if (state[source] == 1) {
                int start = stack.indexOf(source)
                throw new GradleException(
                        "Shader dependency cycle: ${(stack.subList(start, stack.size()) + source).join(' -> ')}")
            }
            if (state[source] == 2) return
            state[source] = 1
            stack.add(source)
            graph[source].each { visit(it) }
            stack.remove(stack.size() - 1)
            state[source] = 2
        }
        graph.keySet().each { visit(it) }
    }

    static Set<String> paths(File source, Map<String, Set<String>> graph) {
        def root = source.canonicalPath
        if (!graph.containsKey(root)) {
            throw new GradleException("Shader entry is outside its include roots: ${source}")
        }
        def closure = new TreeSet<String>()
        def pending = new ArrayDeque<String>()
        pending.add(root)
        while (!pending.empty) {
            def current = pending.removeLast()
            if (closure.add(current)) {
                (graph[current] ?: Collections.emptySet()).each { pending.add(it) }
            }
        }
        return closure
    }

    @Override
    void close() {}
}
