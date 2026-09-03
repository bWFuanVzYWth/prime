package dev.prime.gradle.shader

import org.gradle.api.GradleException

/** Runs shader tools without blocking either process output or Gradle cancellation. */
final class PrimeShaderTool {
    private PrimeShaderTool() {
    }

    static String run(List<?> arguments) {
        def command = arguments.collect { it.toString() }
        Process process
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (IOException exception) {
            throw new GradleException("Could not start shader tool: ${command[0]}", exception)
        }
        def output = new ByteArrayOutputStream()
        def drain = new Thread(
                { process.inputStream.transferTo(output) }, 'prime-shader-tool-output')
        drain.start()
        try {
            int exitCode = process.waitFor()
            drain.join()
            def text = output.toString(java.nio.charset.StandardCharsets.UTF_8)
            if (exitCode != 0) {
                throw new GradleException(
                        "Shader tool failed with exit code ${exitCode}: ${command.join(' ')}"
                                + System.lineSeparator() + text)
            }
            return text
        } catch (InterruptedException exception) {
            process.destroyForcibly()
            drain.interrupt()
            Thread.currentThread().interrupt()
            throw new GradleException("Shader tool was interrupted: ${command.join(' ')}", exception)
        }
    }

    static List<String> compileArguments(
            String compiler, File source, String stage, String debugLevel) {
        return [
                compiler, source.absolutePath,
                '-target', 'spirv',
                '-profile', 'glsl_460',
                '-capability', 'spirv_1_5',
                // This explicit closure keeps warnings-as-errors useful; unused capabilities
                // are not emitted. The typed HitObject API requires the vendor-neutral EXT form.
                '-capability', 'SPV_KHR_non_semantic_info',
                '-capability', 'SPV_GOOGLE_user_type',
                '-capability', 'spvSparseResidency',
                '-capability', 'spvMinLod',
                '-capability', 'spvFragmentFullyCoveredEXT',
                '-capability', 'spvGroupNonUniform',
                '-capability', 'spvGroupNonUniformBallot',
                '-capability', 'spvShaderInvocationReorderEXT',
                '-entry', 'main', '-stage', stage,
                // Explicit ray-payload locations remain a cross-stage Vulkan ABI contract.
                '-allow-glsl', '-matrix-layout-row-major', '-fvk-use-gl-layout',
                '-emit-spirv-directly', '-warnings-as-errors', 'all',
                '-O2', debugLevel
        ]
    }

    static Set<String> dependencies(File depfile) {
        def text = depfile.getText('UTF-8')
        int separator = text.indexOf(': ')
        if (separator < 0) {
            throw new GradleException("Malformed Slang depfile: ${depfile}")
        }
        def dependencies = new TreeSet<String>()
        def token = new StringBuilder()
        boolean escaped = false
        text.substring(separator + 2).each { character ->
            if (escaped) {
                token.append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (Character.isWhitespace((char) character)) {
                if (token.length() > 0) {
                    dependencies.add(new File(token.toString()).canonicalPath)
                    token.setLength(0)
                }
            } else {
                token.append(character)
            }
        }
        if (escaped) token.append('\\')
        if (token.length() > 0) dependencies.add(new File(token.toString()).canonicalPath)
        return dependencies
    }
}
