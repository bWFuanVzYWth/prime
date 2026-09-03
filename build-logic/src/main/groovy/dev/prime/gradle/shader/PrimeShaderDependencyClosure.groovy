package dev.prime.gradle.shader

import org.gradle.api.provider.Provider
import java.util.concurrent.Callable

/** Lazily resolves the exact source closure of one Slang entry point. */
class PrimeShaderDependencyClosure implements Callable<List<File>>, Serializable {
    private final String sourcePath
    private final Provider<PrimeShaderDependencyGraph> dependencyGraph

    @javax.inject.Inject
    PrimeShaderDependencyClosure(
            File source, Provider<PrimeShaderDependencyGraph> dependencyGraph) {
        this.sourcePath = source.canonicalPath
        this.dependencyGraph = dependencyGraph
    }

    @Override
    List<File> call() {
        return dependencyGraph.get().dependencies(new File(sourcePath))
    }
}
