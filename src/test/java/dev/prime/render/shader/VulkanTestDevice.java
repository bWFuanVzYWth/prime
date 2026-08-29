package dev.prime.render.shader;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

/** Owns the validated instance, device, queue and command pool used by one Shader test class. */
final class VulkanTestDevice implements AutoCloseable {
    private static final AtomicInteger REPORT_SEQUENCE = new AtomicInteger();

    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue queue;
    private final int queueFamily;
    private final long commandPool;
    private final long debugMessenger;
    private final VkDebugUtilsMessengerCallbackEXT debugCallback;
    private final ConcurrentLinkedQueue<String> validationErrors;
    private boolean closed;

    private VulkanTestDevice(
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue queue,
            int queueFamily,
            long commandPool,
            long debugMessenger,
            VkDebugUtilsMessengerCallbackEXT debugCallback,
            ConcurrentLinkedQueue<String> validationErrors) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queue = queue;
        this.queueFamily = queueFamily;
        this.commandPool = commandPool;
        this.debugMessenger = debugMessenger;
        this.debugCallback = debugCallback;
        this.validationErrors = validationErrors;
    }

    static VulkanTestDevice open() throws ShaderComputeRunner.UnavailableException {
        return open(false);
    }

    static VulkanTestDevice openRayTracing()
            throws ShaderComputeRunner.UnavailableException {
        return open(true);
    }

    private static VulkanTestDevice open(boolean requireRayTracing)
            throws ShaderComputeRunner.UnavailableException {
        VkInstance instance = null;
        VkDevice device = null;
        long commandPool = 0L;
        long debugMessenger = 0L;
        VkDebugUtilsMessengerCallbackEXT debugCallback = null;
        ConcurrentLinkedQueue<String> validationErrors = new ConcurrentLinkedQueue<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean validationAvailable = hasValidationLayer(stack);
            if (Boolean.getBoolean("prime.shaderValidation.required") && !validationAvailable) {
                throw new ShaderComputeRunner.UnavailableException(
                        "VK_LAYER_KHRONOS_validation is unavailable");
            }
            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Prime shader tests"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Prime"))
                    .engineVersion(1)
                    .apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(applicationInfo);
            VkDebugUtilsMessengerCreateInfoEXT debugInfo = null;
            if (validationAvailable) {
                debugCallback = VkDebugUtilsMessengerCallbackEXT.create(
                        (severity, types, callbackData, userData) -> {
                            if ((severity
                                            & EXTDebugUtils
                                                    .VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                                    != 0) {
                                validationErrors.add(
                                        VkDebugUtilsMessengerCallbackDataEXT
                                                .create(callbackData)
                                                .pMessageString());
                            }
                            return VK10.VK_FALSE;
                        });
                debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType$Default()
                        .messageSeverity(
                                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                        | EXTDebugUtils
                                                .VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(
                                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                        | EXTDebugUtils
                                                .VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                        | EXTDebugUtils
                                                .VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(debugCallback);
                instanceInfo
                        .ppEnabledLayerNames(stack.pointers(
                                stack.UTF8("VK_LAYER_KHRONOS_validation")))
                        .ppEnabledExtensionNames(stack.pointers(
                                stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)))
                        .pNext(debugInfo.address());
            }
            PointerBuffer pointer = stack.mallocPointer(1);
            int result = VK12.vkCreateInstance(instanceInfo, null, pointer);
            if (result != VK12.VK_SUCCESS) {
                throw new ShaderComputeRunner.UnavailableException(
                        "vkCreateInstance returned " + result);
            }
            instance = new VkInstance(pointer.get(0), instanceInfo);
            if (debugInfo != null) {
                LongBuffer debugHandle = stack.mallocLong(1);
                result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                        instance, debugInfo, null, debugHandle);
                if (result != VK12.VK_SUCCESS) {
                    throw new ShaderComputeRunner.UnavailableException(
                            "vkCreateDebugUtilsMessengerEXT returned " + result);
                }
                debugMessenger = debugHandle.get(0);
            }

            SelectedDevice selected = selectDevice(instance, stack, requireRayTracing);
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0)
                    .sType$Default()
                    .queueFamilyIndex(selected.queueFamily())
                    .pQueuePriorities(stack.floats(1.0F));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfo)
                    .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack)
                            .shaderInt64(true));
            if (requireRayTracing) {
                VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddress =
                        VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                                .sType$Default()
                                .bufferDeviceAddress(true);
                VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure =
                        VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                                .sType$Default()
                                .accelerationStructure(true);
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipeline =
                        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                                .sType$Default()
                                .rayTracingPipeline(true);
                bufferDeviceAddress.pNext(accelerationStructure.address());
                accelerationStructure.pNext(rayTracingPipeline.address());
                deviceInfo
                        .pNext(bufferDeviceAddress.address())
                        .ppEnabledExtensionNames(stack.pointers(
                                stack.UTF8(KHRDeferredHostOperations
                                        .VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME),
                                stack.UTF8(KHRAccelerationStructure
                                        .VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME),
                                stack.UTF8(KHRRayTracingPipeline
                                        .VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)));
            }
            pointer.clear();
            result = VK12.vkCreateDevice(selected.physicalDevice(), deviceInfo, null, pointer);
            if (result != VK12.VK_SUCCESS) {
                throw new ShaderComputeRunner.UnavailableException(
                        "vkCreateDevice returned " + result);
            }
            device = new VkDevice(pointer.get(0), selected.physicalDevice(), deviceInfo);

            pointer.clear();
            VK12.vkGetDeviceQueue(device, selected.queueFamily(), 0, pointer);
            VkQueue queue = new VkQueue(pointer.get(0), device);
            LongBuffer handle = stack.mallocLong(1);
            check(
                    VK12.vkCreateCommandPool(
                            device,
                            VkCommandPoolCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .flags(VK12.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                                    .queueFamilyIndex(selected.queueFamily()),
                            null,
                            handle),
                    "create shader-test command pool");
            commandPool = handle.get(0);
            return new VulkanTestDevice(
                    instance,
                    selected.physicalDevice(),
                    device,
                    queue,
                    selected.queueFamily(),
                    commandPool,
                    debugMessenger,
                    debugCallback,
                    validationErrors);
        } catch (ShaderComputeRunner.UnavailableException exception) {
            closePartial(instance, device, commandPool, debugMessenger, debugCallback);
            throw exception;
        } catch (RuntimeException | LinkageError exception) {
            closePartial(instance, device, commandPool, debugMessenger, debugCallback);
            throw exception;
        }
    }

    VkPhysicalDevice physicalDevice() {
        return this.physicalDevice;
    }

    VkDevice device() {
        return this.device;
    }

    VkQueue queue() {
        return this.queue;
    }

    long commandPool() {
        return this.commandPool;
    }

    int queueFamily() {
        return this.queueFamily;
    }

    void waitIdle() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan test device is closed");
        }
        check(VK12.vkDeviceWaitIdle(this.device), "wait for Vulkan test device");
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        VK12.vkDeviceWaitIdle(this.device);
        VK12.vkDestroyCommandPool(this.device, this.commandPool, null);
        VK12.vkDestroyDevice(this.device, null);
        AssertionError validationFailure = this.validationErrors.isEmpty()
                ? null
                : new AssertionError(
                        "Vulkan validation errors:\n" + String.join("\n", this.validationErrors));
        if (this.debugMessenger != 0L) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(
                    this.instance, this.debugMessenger, null);
        }
        VK12.vkDestroyInstance(this.instance, null);
        if (this.debugCallback != null) {
            this.debugCallback.free();
        }
        writeValidationReport(this.validationErrors);
        if (validationFailure != null) {
            throw validationFailure;
        }
    }

    private static boolean hasValidationLayer(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateInstanceLayerProperties(count, null);
        if (result != VK10.VK_SUCCESS || count.get(0) == 0) {
            return false;
        }
        VkLayerProperties.Buffer properties = VkLayerProperties.calloc(count.get(0), stack);
        result = VK10.vkEnumerateInstanceLayerProperties(count, properties);
        if (result != VK10.VK_SUCCESS) {
            return false;
        }
        for (int index = 0; index < properties.remaining(); index++) {
            if ("VK_LAYER_KHRONOS_validation".equals(
                    properties.get(index).layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private static SelectedDevice selectDevice(
            VkInstance instance,
            MemoryStack stack,
            boolean requireRayTracing)
            throws ShaderComputeRunner.UnavailableException {
        IntBuffer count = stack.ints(0);
        int result = VK12.vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK12.VK_SUCCESS || count.get(0) == 0) {
            throw new ShaderComputeRunner.UnavailableException(
                    "No Vulkan physical device is available");
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(VK12.vkEnumeratePhysicalDevices(instance, count, devices),
                "enumerate shader-test devices");
        for (int deviceIndex = 0; deviceIndex < devices.remaining(); deviceIndex++) {
            VkPhysicalDevice physicalDevice =
                    new VkPhysicalDevice(devices.get(deviceIndex), instance);
            VkPhysicalDeviceProperties deviceProperties =
                    VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);
            if (Integer.compareUnsigned(
                            deviceProperties.apiVersion(), VK12.VK_API_VERSION_1_2)
                    < 0) {
                continue;
            }
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(physicalDevice, features);
            if (!features.shaderInt64()) {
                continue;
            }
            if (requireRayTracing && !supportsRayTracing(physicalDevice, stack)) {
                continue;
            }
            count.put(0, 0);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
            VkQueueFamilyProperties.Buffer queueProperties =
                    VkQueueFamilyProperties.calloc(count.get(0), stack);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(
                    physicalDevice, count, queueProperties);
            for (int queueFamily = 0; queueFamily < queueProperties.remaining(); queueFamily++) {
                if (queueProperties.get(queueFamily).queueCount() > 0
                        && (queueProperties.get(queueFamily).queueFlags()
                                & VK12.VK_QUEUE_COMPUTE_BIT) != 0) {
                    return new SelectedDevice(physicalDevice, queueFamily);
                }
            }
        }
        throw new ShaderComputeRunner.UnavailableException(
                requireRayTracing
                        ? "No Vulkan 1.2 device with acceleration structures and ray tracing is available"
                        : "No Vulkan 1.2 compute queue with shaderInt64 is available");
    }

    private static boolean supportsRayTracing(
            VkPhysicalDevice physicalDevice,
            MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        int result = VK12.vkEnumerateDeviceExtensionProperties(
                physicalDevice, (java.nio.ByteBuffer) null, count, null);
        if (result != VK12.VK_SUCCESS) {
            return false;
        }
        boolean deferredHostOperations = false;
        boolean accelerationStructure = false;
        boolean rayTracingPipeline = false;
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.calloc(count.get(0));
        try {
            result = VK12.vkEnumerateDeviceExtensionProperties(
                    physicalDevice, (java.nio.ByteBuffer) null, count, extensions);
            if (result != VK12.VK_SUCCESS) {
                return false;
            }
            for (int index = 0; index < extensions.remaining(); index++) {
                String name = extensions.get(index).extensionNameString();
                deferredHostOperations |= KHRDeferredHostOperations
                        .VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME.equals(name);
                accelerationStructure |= KHRAccelerationStructure
                        .VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME.equals(name);
                rayTracingPipeline |= KHRRayTracingPipeline
                        .VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME.equals(name);
            }
        } finally {
            extensions.free();
        }
        if (!deferredHostOperations || !accelerationStructure || !rayTracingPipeline) {
            return false;
        }

        VkPhysicalDeviceFeatures2 features =
                VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddress =
                VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default();
        VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
        features.pNext(bufferDeviceAddress.address());
        bufferDeviceAddress.pNext(acceleration.address());
        acceleration.pNext(rayTracing.address());
        VK12.vkGetPhysicalDeviceFeatures2(physicalDevice, features);
        return bufferDeviceAddress.bufferDeviceAddress()
                && acceleration.accelerationStructure()
                && rayTracing.rayTracingPipeline();
    }

    private static void closePartial(
            VkInstance instance,
            VkDevice device,
            long commandPool,
            long debugMessenger,
            VkDebugUtilsMessengerCallbackEXT debugCallback) {
        if (commandPool != 0L && device != null) {
            VK12.vkDestroyCommandPool(device, commandPool, null);
        }
        if (device != null) {
            VK12.vkDestroyDevice(device, null);
        }
        if (debugMessenger != 0L && instance != null) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        if (instance != null) {
            VK12.vkDestroyInstance(instance, null);
        }
        if (debugCallback != null) {
            debugCallback.free();
        }
    }

    private static void writeValidationReport(ConcurrentLinkedQueue<String> errors) {
        String directory = System.getProperty("prime.vulkanValidationReportDirectory");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path report = Path.of(directory).resolve(
                "device-" + REPORT_SEQUENCE.incrementAndGet() + ".txt");
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(
                    report,
                    errors.isEmpty()
                            ? "No Vulkan validation errors.\n"
                            : String.join(System.lineSeparator(), errors)
                                    + System.lineSeparator());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write Vulkan validation report " + report,
                    exception);
        }
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    private record SelectedDevice(VkPhysicalDevice physicalDevice, int queueFamily) {
    }
}
