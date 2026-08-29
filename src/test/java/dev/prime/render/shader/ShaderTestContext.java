package dev.prime.render.shader;

import java.nio.file.Path;

/** One class-scoped Vulkan owner and compiled-test-program resolver. */
final class ShaderTestContext implements AutoCloseable {
    private final ShaderComputeRunner runner;

    private ShaderTestContext(ShaderComputeRunner runner) {
        this.runner = runner;
    }

    static ShaderTestContext open() throws ShaderComputeRunner.UnavailableException {
        return new ShaderTestContext(ShaderComputeRunner.open());
    }

    ShaderComputeRunner runner() {
        return this.runner;
    }

    Path shader(String artifact) {
        String directory = System.getProperty("prime.test.slangShaderDirectory");
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("prime.test.slangShaderDirectory is not configured");
        }
        return Path.of(directory, artifact);
    }

    @Override
    public void close() {
        this.runner.close();
    }
}
