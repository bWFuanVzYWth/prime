// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import org.gradle.api.GradleException

/** Source dependency domains, independent of function names, code size and optimizer DCE. */
final class PrimeShaderVisibility {
    private PrimeShaderVisibility() {}

    static void requireSeparated(Map<String, Set<String>> graph, String root,
            Collection<String> forbiddenDomains, String contract) {
        if (!graph.containsKey(root)) {
            throw new GradleException("${contract}: missing capability root ${root}")
        }
        def reached = PrimeShaderDependencyGraph.paths(root, graph)
        def violations = reached.findAll { path ->
            forbiddenDomains.any { domain -> path == domain || path.startsWith(domain + '/') }
        }
        if (!violations.empty) {
            throw new GradleException("${contract}: ${root} reaches ${violations.sort()}")
        }
    }
}
