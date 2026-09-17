// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

final class PrimeShaderVisibilityTest {
    @Test void permitsGrowthWithinTheDeclaredCapabilityDomain() {
        def graph = ['select': ['classify'] as Set, 'classify': [] as Set]
        (0..<100).each { index ->
            String leaf = "math/${index}"
            graph.classify.add(leaf)
            graph[leaf] = [] as Set
        }
        graph.trace = ['service/trace/world'] as Set
        graph['service/trace/world'] = [] as Set
        PrimeShaderVisibility.requireSeparated(graph, 'select', ['service/trace'], 'selection')
    }

    @Test void rejectsForbiddenCapabilityThroughAnIntermediateModule() {
        def graph = ['select': ['adapter'] as Set, 'adapter': ['service/trace/world'] as Set,
                     'service/trace/world': [] as Set]
        assertThrows(GradleException) {
            PrimeShaderVisibility.requireSeparated(graph, 'select', ['service/trace'], 'selection')
        }
        graph.adapter = ['service/trace_metadata'] as Set
        graph['service/trace_metadata'] = [] as Set
        PrimeShaderVisibility.requireSeparated(graph, 'select', ['service/trace'], 'selection')
    }

    @Test void dependencyCyclesFailIndependentlyOfSize() {
        assertThrows(GradleException) {
            PrimeShaderDependencyGraph.requireAcyclic(['a': ['b'] as Set, 'b': ['a'] as Set])
        }
    }

    @Test void movingACapabilityCannotSilentlyDisableItsBoundary() {
        assertThrows(GradleException) {
            PrimeShaderVisibility.requireSeparated([:], 'select', ['service/trace'], 'selection')
        }
    }
}
