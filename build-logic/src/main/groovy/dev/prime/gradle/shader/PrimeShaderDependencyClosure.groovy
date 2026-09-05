package dev.prime.gradle.shader

import org.gradle.api.provider.Provider
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec

/** Lazily resolves the exact source closure of one Slang entry point. */
class PrimeShaderDependencyClosure implements Spec<FileTreeElement>, Serializable {
    private final String sourcePath
    private final Provider<PrimeShaderDependencyGraph> dependencyGraph
    private transient Set<String> paths

    @javax.inject.Inject
    PrimeShaderDependencyClosure(
            File source, Provider<PrimeShaderDependencyGraph> dependencyGraph) {
        this.sourcePath = source.canonicalPath
        this.dependencyGraph = dependencyGraph
    }

    @Override
    boolean isSatisfiedBy(FileTreeElement element) {
        if (element.isDirectory()) return true
        if (paths == null) {
            paths = dependencyGraph.get().dependencies(new File(sourcePath))
                    .collect { it.toPath().toAbsolutePath().normalize().toString() }.toSet()
        }
        return paths.contains(element.file.toPath().toAbsolutePath().normalize().toString())
    }
}
