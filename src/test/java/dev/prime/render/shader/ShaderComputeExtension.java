package dev.prime.render.shader;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

/** Injects and owns one Vulkan compute context for each GPU test class. */
final class ShaderComputeExtension implements BeforeAllCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ShaderComputeExtension.class);

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        Class<?> testClass = extensionContext.getRequiredTestClass();
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
        extensionContext.getStore(NAMESPACE).put(testClass, runner);
        inject(testClass, runner);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        Class<?> testClass = extensionContext.getRequiredTestClass();
        ShaderComputeRunner runner = extensionContext
                .getStore(NAMESPACE)
                .remove(testClass, ShaderComputeRunner.class);
        try {
            if (runner != null) {
                runner.close();
            }
        } finally {
            clear(testClass);
        }
    }

    private static void inject(Class<?> testClass, ShaderComputeRunner runner) {
        int injected = 0;
        for (Field field : testClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == ShaderComputeRunner.class) {
                set(field, runner);
                injected++;
            }
        }
        if (injected == 0) {
            runner.close();
            throw new ExtensionConfigurationException(
                    testClass.getName()
                            + " must declare a static ShaderComputeRunner field");
        }
    }

    private static void clear(Class<?> testClass) {
        for (Field field : testClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == ShaderComputeRunner.class) {
                set(field, null);
            }
        }
    }

    private static void set(Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(null, value);
        } catch (IllegalAccessException exception) {
            throw new ExtensionConfigurationException(
                    "Cannot inject Shader test context into " + field, exception);
        }
    }
}
