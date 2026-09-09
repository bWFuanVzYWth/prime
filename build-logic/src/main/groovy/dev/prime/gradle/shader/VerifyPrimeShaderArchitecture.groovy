// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.gradle.shader

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Validates and reports the production Slang visibility graph. */
abstract class VerifyPrimeShaderArchitecture extends DefaultTask {
    private static final Set<String> ENTRY_SUFFIXES = [
            '.compute.slang', '.raygeneration.slang', '.miss.slang',
            '.closesthit.slang', '.anyhit.slang'] as Set

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getShaderDirectory()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getIncludeDirectories()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getProgramManifest()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getClosureBudget()

    @OutputFile
    abstract RegularFileProperty getReportFile()

    private static boolean entryFile(File file) {
        return ENTRY_SUFFIXES.any { file.name.endsWith(it) }
    }

    @TaskAction
    void verify() {
        // canonicalFile performs a filesystem round-trip for every candidate on Windows. The
        // configured roots are already absolute and do not permit symlink aliases, so lexical
        // normalization gives the same graph identity without multiplying I/O by every import.
        def normalizedFile = { File file ->
            file.toPath().toAbsolutePath().normalize().toFile()
        }
        def pathKey = { File file ->
            file.toPath().toAbsolutePath().normalize().toString()
        }
        def shaderRoot = normalizedFile(shaderDirectory.get().asFile)
        def roots = ([shaderRoot] + includeDirectories.files)
                .collect { normalizedFile(it) }
                .findAll { it.isDirectory() }
                .unique { pathKey(it) }
        def sources = []
        roots.each { root ->
            root.eachFileRecurse(groovy.io.FileType.FILES) { source ->
                if (source.name.endsWith('.slang') || source.name.endsWith('.h')) {
                    sources.add(normalizedFile(source))
                }
            }
        }
        sources = sources.unique { pathKey(it) }
        def sourceByPath = sources.collectEntries { [(pathKey(it)): it] }
        def shaderRelative = { File file ->
            def path = file.toPath().toAbsolutePath().normalize()
            return path.startsWith(shaderRoot.toPath())
                    ? shaderRoot.toPath().relativize(path).toString().replace('\\', '/')
                    : null
        }
        def modulePattern = java.util.regex.Pattern.compile(
                '(?m)^\\s*module\\s+"([^"]+)"\\s*;')
        def dependencyPattern = java.util.regex.Pattern.compile(
                '(?m)^\\s*(?:#\\s*include\\s+"([^"]+)"|(?:__exported\\s+)?import\\s+"([^"]+)"\\s*;)')
        def modulePaths = new HashSet<String>()
        def unmodularized = []
        sources.findAll { it.toPath().startsWith(shaderRoot.toPath()) }.each { source ->
            def relative = shaderRoot.toPath().relativize(source.toPath())
                    .toString().replace('\\', '/')
            def matcher = modulePattern.matcher(source.getText('UTF-8'))
            if (matcher.find()) {
                modulePaths.add(pathKey(source))
                if (matcher.group(1) != relative) {
                    throw new GradleException(
                            "Shader module ${matcher.group(1)} does not match path ${relative}")
                }
            } else if (source.name.endsWith('.slang')) {
                unmodularized.add(relative)
            }
        }

        def graph = new TreeMap<String, Set<String>>()
        def dependencyKinds = new HashMap<String, Map<String, Boolean>>()
        sources.each { source ->
            def sourcePath = pathKey(source)
            def targets = graph.computeIfAbsent(sourcePath) { new TreeSet<String>() }
            def kinds = dependencyKinds.computeIfAbsent(sourcePath) { [:] }
            def matcher = dependencyPattern.matcher(source.getText('UTF-8'))
            while (matcher.find()) {
                def included = matcher.group(1) != null
                def name = matcher.group(1) ?: matcher.group(2)
                def matches = ([new File(source.parentFile, name)]
                        + roots.collect { new File(it, name) })
                        .collect { normalizedFile(it) }
                        .findAll { sourceByPath.containsKey(pathKey(it)) }
                        .unique { pathKey(it) }
                if (matches.size() != 1) {
                    throw new GradleException(
                            "Cannot resolve unique shader dependency ${name} from ${source}")
                }
                def target = matches.first()
                def targetPath = pathKey(target)
                if (included && modulePaths.contains(targetPath)) {
                    throw new GradleException(
                            "Explicit shader module ${name} must be imported, not included, from ${source}")
                }
                targets.add(targetPath)
                kinds[targetPath] = included
            }
        }

        dependencyKinds.each { sourcePath, kinds ->
            def source = new File(sourcePath)
            def sourceRelative = shaderRelative(source)
            if (sourceRelative == null) return
            kinds.findAll { targetPath, included -> included }.each { targetPath, ignored ->
                def target = new File(targetPath)
                def targetName = shaderRelative(target) ?: target.name
                throw new GradleException(
                        "Production shader dependencies must use modules: "
                                + "${sourceRelative} -> ${targetName}")
            }
        }

        graph.each { sourcePath, targets ->
            def sourceRelative = shaderRelative(new File(sourcePath))
            if (sourceRelative == null) return
            targets.each { targetPath ->
                def target = new File(targetPath)
                def targetRelative = shaderRelative(target)
                if (sourceRelative.startsWith('state/')
                        && targetRelative?.startsWith('transport/')) {
                    throw new GradleException(
                            "State imports transport: ${sourceRelative} -> ${targetRelative}")
                }
                boolean compactContract = sourceRelative.startsWith('bsdf/compact/contract/')
                boolean rootContract = sourceRelative.startsWith('contract/')
                boolean serviceBsdfContract = sourceRelative.startsWith('service/bsdf/contract/')
                if (compactContract && targetRelative != null
                        && !targetRelative.startsWith('bsdf/compact/contract/')) {
                    throw new GradleException(
                            "Compact contract imports behavior: ${sourceRelative} -> ${targetRelative}")
                }
                if (rootContract && targetRelative != null
                        && !targetRelative.startsWith('contract/')) {
                    throw new GradleException(
                            "Contract imports behavior: ${sourceRelative} -> ${targetRelative}")
                }
                if (serviceBsdfContract && targetRelative != null
                        && !targetRelative.startsWith('service/bsdf/contract/')) {
                    throw new GradleException(
                            "BSDF contract imports behavior: ${sourceRelative} -> ${targetRelative}")
                }
                def sourceRoot = sourceRelative.substring(0, sourceRelative.indexOf('/'))
                def targetRoot = targetRelative == null
                        ? null
                        : targetRelative.substring(0, targetRelative.indexOf('/'))
                def allowedLayerDependencies = [
                        'bsdf': ['bsdf'] as Set,
                        'contract': ['contract'] as Set,
                        'math': ['contract', 'math'] as Set,
                        'model': ['contract', 'math', 'model'] as Set
                ]
                if (targetRoot != null
                        && allowedLayerDependencies.containsKey(sourceRoot)
                        && !allowedLayerDependencies[sourceRoot].contains(targetRoot)) {
                    throw new GradleException(
                            "Shader layer violation: ${sourceRelative} -> ${targetRelative}")
                }
            }
        }

        def forbiddenSources = [
                'bsdf/adapter/bsdf.slang',
                'integrator/integrator.slang',
                'integrator/transport_core.slang',
                'realtime/realtime_wavefront_common.slang',
                'realtime/wavefront_state.slang',
                'offline/offline_wavefront.slang'
        ]
        forbiddenSources.each { relative ->
            if (new File(shaderRoot, relative).exists()) {
                throw new GradleException("Removed shader umbrella was restored: ${relative}")
            }
        }
        def allowedRoots = [
                'bsdf', 'contract', 'entry', 'math', 'model',
                'policy', 'service', 'state', 'transport'] as Set
        sources.findAll { shaderRelative(it) != null }.each { source ->
            def relative = shaderRelative(source)
            def root = relative.contains('/') ? relative.substring(0, relative.indexOf('/')) : ''
            if (!allowedRoots.contains(root)) {
                throw new GradleException(
                        "Production shader remains outside the dependency-layer roots: ${relative}")
            }
            if (relative.startsWith('bsdf/') && !relative.startsWith('bsdf/compact/')) {
                throw new GradleException(
                        "OpenPBR code outside the compact implementation was restored: ${relative}")
            }
        }
        def forbiddenMacros = [
                'PRIME_TWO_STAGE_WAVEFRONT',
                'PRIME_STEADY_WAVEFRONT',
                'PRIME_WAVEFRONT_CLASSIFY_ONLY',
                'PRIME_WAVEFRONT_NO_ADVANCE'
        ]
        sources.findAll { shaderRelative(it) != null }.each { source ->
            def text = source.getText('UTF-8')
            forbiddenMacros.each { macro ->
                if (text.contains(macro)) {
                    throw new GradleException(
                            "Removed shader mode macro ${macro} was restored in ${shaderRelative(source)}")
                }
            }
        }

        PrimeShaderDependencyGraph.requireAcyclic(graph)

        def manifest = PrimeShaderManifest.read(programManifest.get().asFile)
        def manifestSources = manifest.artifacts.values().collect { artifact ->
            pathKey(new File(shaderRoot.parentFile, artifact.source))
        }.toSet()
        def productionEntries = []
        new File(shaderRoot, 'entry').eachFileRecurse(groovy.io.FileType.FILES) { source ->
            if (VerifyPrimeShaderArchitecture.entryFile(source)) {
                productionEntries.add(normalizedFile(source))
            }
        }
        def actualEntries = productionEntries.collect { pathKey(it) }.toSet()
        if (manifestSources != actualEntries) {
            def missing = actualEntries - manifestSources
            def stale = manifestSources - actualEntries
            throw new GradleException(
                    "Program manifest mismatch; missing=${missing}, stale=${stale}")
        }
        def resources = manifest.artifacts.values().collect { it.resource }
        if (resources.size() != resources.toSet().size()) {
            throw new GradleException('Program manifest contains duplicate SPIR-V resources')
        }
        manifest.schedules.each { scheduleId, schedule ->
            schedule.modules.each { id ->
                if (!manifest.artifacts.containsKey(id)) {
                    throw new GradleException(
                            "Schedule ${scheduleId} references unknown artifact ${id}")
                }
            }
            schedule.groups.each { group ->
                if (group.module < 0 || group.module >= schedule.modules.size()) {
                    throw new GradleException(
                            "Schedule ${scheduleId} contains an invalid module index")
                }
            }
        }

        def closures = productionEntries.collectEntries { entry ->
            def entryPath = pathKey(entry)
            [(entryPath): PrimeShaderDependencyGraph.paths(entryPath, graph)]
        }
        def normalizedSourceBytes = { String path ->
            new File(path).getText('UTF-8')
                    .replace('\r\n', '\n')
                    .replace('\r', '\n')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length
        }
        def atmosphereSpectrum = pathKey(new File(shaderRoot, 'model/atmosphere/spectrum.slang'))
        productionEntries.findAll { !it.name.endsWith('.compute.slang') }.each { entry ->
            if (closures[pathKey(entry)].contains(atmosphereSpectrum)) {
                throw new GradleException("RT entry reaches atmosphere spectral integration: ${entry}")
            }
        }
        def deltaWalk = productionEntries.find {
            shaderRelative(it) == 'entry/realtime/delta_walk.raygeneration.slang'
        }
        if (deltaWalk == null) {
            throw new GradleException('Delta-walk production entry is missing')
        }
        def primaryForbiddenPrefixes = [
                'bsdf/compact/foliage/',
                'service/bsdf/foliage/',
                'transport/direct/'
        ]
        def primaryForbiddenFiles = [
                'bsdf/compact/math/microfacet_distribution.slang',
                'bsdf/compact/opaque/lobes.slang',
                'bsdf/compact/opaque/sample.slang',
                'bsdf/compact/opaque/evaluate.slang',
                'bsdf/compact/dielectric/refract.slang',
                'bsdf/compact/dielectric/sample.slang',
                'bsdf/compact/dielectric/evaluate.slang',
                'service/bsdf/opaque/sample.slang',
                'service/bsdf/dielectric/sample.slang',
                'service/bsdf/dispatch/sample.slang',
                'service/bsdf/dispatch/guided_sample.slang',
                'service/bsdf/dispatch/guide_albedo.slang'
        ] as Set
        def primaryViolations = closures[pathKey(deltaWalk)].collect {
            shaderRelative(new File(it))
        }.findAll { relative ->
            relative != null && (primaryForbiddenFiles.contains(relative)
                    || primaryForbiddenPrefixes.any { relative.startsWith(it) })
        }.sort()
        if (!primaryViolations.empty) {
            throw new GradleException(
                    "Delta-walk reaches non-discrete BSDF/NEE code: ${primaryViolations}")
        }
        productionEntries.findAll { shaderRelative(it).startsWith('entry/lambert/') }.each { entry ->
            def relative = shaderRelative(entry)
            def narrowStage = entry.name.startsWith('shade.') || entry.name.startsWith('terminal.')
                    || entry.name.startsWith('trace.') || entry.name.startsWith('camera.') || entry.name.startsWith('guide.')
            def violations = closures[pathKey(entry)].collect { shaderRelative(new File(it)) }.findAll {
                it != null && (it.startsWith('bsdf/') || it.startsWith('service/bsdf/')
                        || it == 'service/material/surface.slang'
                        || (narrowStage && (it == 'service/light/tree_select.slang'
                                || it == 'service/light/area_sample.slang')))
            }
            if (!violations.empty) throw new GradleException("Lambert stage widened: ${relative}: ${violations}")
        }
        def budget = new JsonSlurper().parse(closureBudget.get().asFile)
        if (budget.schema != 1) {
            throw new GradleException("Unsupported shader closure budget schema: ${budget.schema}")
        }
        def entryByRelative = productionEntries.collectEntries { entry ->
            [(shaderRelative(entry)): entry]
        }
        if (budget.entries.keySet() as Set != entryByRelative.keySet() as Set) {
            def missing = entryByRelative.keySet() - budget.entries.keySet()
            def stale = budget.entries.keySet() - entryByRelative.keySet()
            throw new GradleException(
                    "Shader closure budget mismatch; missing=${missing}, stale=${stale}")
        }
        budget.entries.each { relative, limit ->
            def closure = closures[pathKey(entryByRelative[relative])]
            long bytes = closure.sum(normalizedSourceBytes) as long
            if (closure.size() > limit.maxFiles || bytes > limit.maxBytes) {
                throw new GradleException(
                        "Shader closure budget exceeded for ${relative}: "
                                + "files=${closure.size()}/${limit.maxFiles}, "
                                + "bytes=${bytes}/${limit.maxBytes}")
            }
        }
        def diagnostics = sources.findAll {
            def path = pathKey(it).replace('\\', '/').toLowerCase(Locale.ROOT)
            path.contains('diagnostic') || shaderRelative(it) == 'core/numerical.slang'
        }.collect { pathKey(it) }.toSet()
        productionEntries.findAll {
            !it.name.toLowerCase(Locale.ROOT).contains('diagnostic')
        }.each { entry ->
            def reached = closures[pathKey(entry)].intersect(diagnostics)
            if (!reached.empty) {
                throw new GradleException(
                        "Production entry ${entry.name} reaches diagnostic code: ${reached}")
            }
        }
        def resolvedContract = pathKey(new File(
                shaderRoot, 'contract/realtime/resolved.slang'))
        productionEntries.findAll {
            def relative = shaderRelative(it)
            relative != 'entry/realtime/noisy_output_resolve.raygeneration.slang'
                    && relative != 'entry/realtime/branch_resolve.raygeneration.slang'
        }.each { entry ->
            if (closures[pathKey(entry)].contains(resolvedContract)) {
                throw new GradleException(
                        "Non-resolve entry ${shaderRelative(entry)} reaches PrimeResolvedSample")
            }
        }

        def fanout = new HashMap<String, Integer>()
        closures.values().each { closure ->
            closure.each { source -> fanout.merge(source, 1, Integer::sum) }
        }
        def artifactsBySource = new HashMap<String, Set<String>>()
        manifest.artifacts.each { id, artifact ->
            def source = pathKey(new File(shaderRoot.parentFile, artifact.source))
            closures[source].each { dependency ->
                artifactsBySource.computeIfAbsent(dependency) { new TreeSet<String>() }.add(id)
            }
        }
        def artifactIdsByEntry = manifest.artifacts.groupBy { id, artifact ->
            artifact.source.substring('shaders/'.length())
        }.collectEntries { relative, artifacts ->
            [(relative): artifacts.keySet().sort()]
        }
        def lines = []
        lines.add("entries=${productionEntries.size()}")
        lines.add("artifacts=${manifest.artifacts.size()}")
        lines.add("unmodularized=${unmodularized.size()}")
        productionEntries.sort { pathKey(it) }.each { entry ->
            def closure = closures[pathKey(entry)]
            long bytes = closure.sum(normalizedSourceBytes) as long
            def relative = shaderRoot.toPath().relativize(entry.toPath())
                    .toString().replace('\\', '/')
            lines.add("entry ${relative} files=${closure.size()} bytes=${bytes} "
                    + "artifacts=${artifactIdsByEntry[relative].join(',')}")
        }
        fanout.entrySet().sort { left, right ->
            right.value <=> left.value ?: left.key <=> right.key
        }.each { item ->
            def file = new File(item.key)
            def relative = file.toPath().startsWith(shaderRoot.toPath())
                    ? shaderRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    : file.name
            def invalidated = artifactsBySource[item.key] ?: Collections.emptySet()
            lines.add("fanout entries=${item.value} artifacts=${invalidated.size()} ${relative}")
            lines.add("invalidates ${relative} ${invalidated.join(',')}")
        }
        if (!unmodularized.empty) {
            lines.add('migration-unmodularized')
            lines.addAll(unmodularized.sort())
        }
        def report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.setText(lines.join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
    }
}
