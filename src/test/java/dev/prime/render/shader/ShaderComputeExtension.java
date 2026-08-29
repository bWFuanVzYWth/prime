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
        ShaderTestContext context;
        try {
            context = ShaderTestContext.open();
        } catch (ShaderComputeRunner.UnavailableException | LinkageError exception) {
            if (Boolean.getBoolean("prime.shaderTests.required")) {
                throw new AssertionError(
                        "A validated Vulkan compute device is required for shader tests",
                        exception);
            }
            throw new TestAbortedException(
                    "Vulkan shader tests unavailable: " + exception.getMessage(), exception);
        }
        extensionContext.getStore(NAMESPACE).put(testClass, context);
        inject(testClass, context);
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        Class<?> testClass = extensionContext.getRequiredTestClass();
        ShaderTestContext context = extensionContext
                .getStore(NAMESPACE)
                .remove(testClass, ShaderTestContext.class);
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            clear(testClass);
        }
    }

    private static void inject(Class<?> testClass, ShaderTestContext context) {
        int injected = 0;
        for (Field field : testClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value;
            if (field.getType() == ShaderComputeRunner.class) {
                value = context.runner();
            } else if (field.getType() == ShaderTestContext.class) {
                value = context;
            } else {
                continue;
            }
            set(field, value);
            injected++;
        }
        if (injected == 0) {
            context.close();
            throw new ExtensionConfigurationException(
                    testClass.getName()
                            + " must declare a static ShaderComputeRunner or ShaderTestContext field");
        }
    }

    private static void clear(Class<?> testClass) {
        for (Field field : testClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && (field.getType() == ShaderComputeRunner.class
                            || field.getType() == ShaderTestContext.class)) {
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
