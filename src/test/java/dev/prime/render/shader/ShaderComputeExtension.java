// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.opentest4j.TestAbortedException;

/** Injects and owns one Vulkan compute context for each GPU test class. */
final class ShaderComputeExtension implements TestInstancePostProcessor, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ShaderComputeExtension.class);

    @Override
    public void postProcessTestInstance(
            Object testInstance, ExtensionContext extensionContext) {
        ShaderComputeRunner runner;
        try {
            runner = ShaderComputeRunner.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A validated Vulkan compute device is required for shader tests",
                        exception);
            }
            throw new TestAbortedException(
                    "Vulkan shader tests unavailable: " + exception.getMessage(), exception);
        }
        extensionContext.getStore(NAMESPACE).put(ShaderComputeRunner.class, runner);
        ((GpuShaderTest) testInstance).runner = runner;
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        ShaderComputeRunner runner = extensionContext
                .getStore(NAMESPACE)
                .remove(ShaderComputeRunner.class, ShaderComputeRunner.class);
        if (runner != null) {
            runner.close();
        }
    }
}
