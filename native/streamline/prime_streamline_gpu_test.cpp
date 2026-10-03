// Real Streamline / DLSS RR evaluation on an isolated Vulkan device, without a window.
// The finite, fully written output check does not establish image quality or game performance.
// Validation errors always make this executable fail; SDK-owned resources are not exempt.
#include "prime_streamline.cpp"
#include <vector>
#include <atomic>
#include <limits>
#include <stdexcept>
#include "sl_matrix_helpers.h"
template <class T> T vk_symbol(const char *name);
std::atomic_uint32_t validation_errors{};
bool abort_on_validation_error{true};
bool omit_specular_distance{};
VkInstance dispatch_instance{};
VkDevice dispatch_device{};

int evaluate_frame(VkPhysicalDevice physical, VkDevice device, uint32_t family, void *context,
                   PrimeSlSize size) {
    const auto create_image = vk_symbol<PFN_vkCreateImage>("vkCreateImage");
    const auto requirements =
            vk_symbol<PFN_vkGetImageMemoryRequirements>("vkGetImageMemoryRequirements");
    const auto memory_properties = vk_symbol<PFN_vkGetPhysicalDeviceMemoryProperties>(
            "vkGetPhysicalDeviceMemoryProperties");
    const auto allocate = vk_symbol<PFN_vkAllocateMemory>("vkAllocateMemory");
    const auto bind = vk_symbol<PFN_vkBindImageMemory>("vkBindImageMemory");
    const auto create_view = vk_symbol<PFN_vkCreateImageView>("vkCreateImageView");
    const auto create_pool = vk_symbol<PFN_vkCreateCommandPool>("vkCreateCommandPool");
    const auto allocate_cmd = vk_symbol<PFN_vkAllocateCommandBuffers>("vkAllocateCommandBuffers");
    const auto begin = vk_symbol<PFN_vkBeginCommandBuffer>("vkBeginCommandBuffer");
    const auto barrier = vk_symbol<PFN_vkCmdPipelineBarrier>("vkCmdPipelineBarrier");
    const auto clear = vk_symbol<PFN_vkCmdClearColorImage>("vkCmdClearColorImage");
    const auto end = vk_symbol<PFN_vkEndCommandBuffer>("vkEndCommandBuffer");
    const auto get_queue = vk_symbol<PFN_vkGetDeviceQueue>("vkGetDeviceQueue");
    const auto submit = vk_symbol<PFN_vkQueueSubmit>("vkQueueSubmit");
    const auto wait = vk_symbol<PFN_vkQueueWaitIdle>("vkQueueWaitIdle");
    const auto create_buffer = vk_symbol<PFN_vkCreateBuffer>("vkCreateBuffer");
    const auto buffer_requirements =
            vk_symbol<PFN_vkGetBufferMemoryRequirements>("vkGetBufferMemoryRequirements");
    const auto bind_buffer = vk_symbol<PFN_vkBindBufferMemory>("vkBindBufferMemory");
    const auto copy = vk_symbol<PFN_vkCmdCopyImageToBuffer>("vkCmdCopyImageToBuffer");
    const auto map = vk_symbol<PFN_vkMapMemory>("vkMapMemory");
    const auto unmap = vk_symbol<PFN_vkUnmapMemory>("vkUnmapMemory");
    VkPhysicalDeviceMemoryProperties mp{};
    memory_properties(physical, &mp);
    auto type = [&](uint32_t bits, VkMemoryPropertyFlags flags) {
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((bits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & flags) == flags)
                return i;
        return UINT32_MAX;
    };
    VkImage images[PRIME_SL_IMAGE_COUNT]{};
    VkImageView views[PRIME_SL_IMAGE_COUNT]{};
    VkDeviceMemory allocations[PRIME_SL_IMAGE_COUNT]{};
    PrimeSlFrame frame{};
    frame.reset = 1;
    frame.camera_near = .1f;
    frame.camera_far = 1000;
    frame.camera_fov = 1.2f;
    frame.camera_aspect = 16.f / 9.f;
    frame.camera_up[1] = 1;
    frame.camera_right[0] = 1;
    frame.camera_forward[2] = 1;
    sl::float4x4 id;
    identity(id);
    std::memcpy(frame.world_to_view, &id, 64);
    std::memcpy(frame.view_to_world, &id, 64);
    std::memcpy(frame.clip_to_previous_clip, &id, 64);
    std::memcpy(frame.previous_clip_to_clip, &id, 64);
    frame.view_to_clip[0] = 1 / (std::tan(frame.camera_fov / 2) * frame.camera_aspect);
    frame.view_to_clip[5] = 1 / std::tan(frame.camera_fov / 2);
    frame.view_to_clip[10] = frame.camera_far / (frame.camera_far - frame.camera_near);
    frame.view_to_clip[11] = 1;
    frame.view_to_clip[14] = -frame.camera_near * frame.view_to_clip[10];
    sl::float4x4 projection, inverse;
    copy_matrix(projection, frame.view_to_clip);
    sl::matrixFullInvert(inverse, projection);
    std::memcpy(frame.clip_to_view, &inverse, 64);
    const VkFormat formats[] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT,       VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT};
    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    for (uint32_t i = 0; i < PRIME_SL_IMAGE_COUNT; ++i) {
        auto &desc = frame.images[i];
        desc.width = i == 6 ? 1920 : size.render_width;
        desc.height = i == 6 ? 1080 : size.render_height;
        desc.format = formats[i];
        desc.layout = VK_IMAGE_LAYOUT_GENERAL;
        desc.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = formats[i];
        ci.extent = {desc.width, desc.height, 1};
        ci.mipLevels = ci.arrayLayers = 1;
        ci.samples = VK_SAMPLE_COUNT_1_BIT;
        ci.usage = desc.usage;
        if (create_image(device, &ci, nullptr, &images[i]))
            throw std::runtime_error("Vulkan test resource operation failed");
        VkMemoryRequirements mr{};
        requirements(device, images[i], &mr);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (allocate(device, &ai, nullptr, &allocations[i]) ||
            bind(device, images[i], allocations[i], 0))
            throw std::runtime_error("Vulkan test resource operation failed");
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = images[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = formats[i];
        vi.subresourceRange = range;
        if (create_view(device, &vi, nullptr, &views[i]))
            throw std::runtime_error("Vulkan test resource operation failed");
        desc.image = (uint64_t)images[i];
        desc.view = (uint64_t)views[i];
        desc.memory = (uint64_t)allocations[i];
    }
    VkBuffer readback{};
    VkDeviceMemory host_memory{};
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = 1920 * 1080 * 8;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (create_buffer(device, &bci, nullptr, &readback))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkMemoryRequirements br{};
    buffer_requirements(device, readback, &br);
    VkMemoryAllocateInfo bai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    bai.allocationSize = br.size;
    bai.memoryTypeIndex = type(br.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (allocate(device, &bai, nullptr, &host_memory) ||
        bind_buffer(device, readback, host_memory, 0))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandPool pool{};
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = family;
    if (create_pool(device, &pci, nullptr, &pool))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandBuffer cmd{};
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (allocate_cmd(device, &cai, &cmd))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (begin(cmd, &cbi) != VK_SUCCESS)
        throw std::runtime_error("vkBeginCommandBuffer failed");
    const VkClearColorValue colors[] = {
            {{.25f, .3f, .4f, 1}},
            {{10, 0, 0, 0}},
            {{0, 0, 0, 0}},
            {{0, 0, -1, 1}},
            {{.5f, .5f, .5f, 1}},
            {{.04f, .04f, .04f, 1}},
            {{std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::quiet_NaN(),
              std::numeric_limits<float>::quiet_NaN(), 0}},
            {{0, 0, 0, 0}},
            {{0, 0, 0, 0}}}; // Static fixture: both dense motion fields are zero input pixels.
    for (uint32_t i = 0; i < PRIME_SL_IMAGE_COUNT; ++i) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ib.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.image = images[i];
        ib.subresourceRange = range;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                nullptr, 0, nullptr, 1, &ib);
        clear(cmd, images[i], VK_IMAGE_LAYOUT_GENERAL, &colors[i], 1, &range);
    }
    VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    ready.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1,
            &ready, 0, nullptr, 0, nullptr);
    frame.command_buffer = (uint64_t)cmd;
    if (omit_specular_distance)
        frame.images[7] = {};
    int result = prime_sl_evaluate(context, &frame);
    std::printf("prime_sl_evaluate=%d error=%s\n", result, prime_sl_last_error());
    if (!result) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        ib.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.image = images[6];
        ib.subresourceRange = range;
        barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
                nullptr, 0, nullptr, 1, &ib);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {1920, 1080, 1};
        copy(cmd, images[6], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &region);
        VkMemoryBarrier host_read{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        host_read.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        host_read.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host_read,
                0, nullptr, 0, nullptr);
    }
    if (end(cmd) != VK_SUCCESS)
        throw std::runtime_error("vkEndCommandBuffer failed");
    VkQueue queue{};
    get_queue(device, family, 0, &queue);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    // An exception may leave a partial SDK recording unsafe to submit. Destroying
    // this unsubmitted command pool is its cancellation proof.
    VkResult submitted = result == -100 ? VK_ERROR_UNKNOWN : submit(queue, 1, &si, VK_NULL_HANDLE);
    VkResult completed = wait(queue);
    std::printf("queueSubmit=%d queueWaitIdle=%d\n", int(submitted), int(completed));
    if (!result && !submitted && !completed) {
        void *bytes{};
        if (map(device, host_memory, 0, VK_WHOLE_SIZE, 0, &bytes) != VK_SUCCESS)
            throw std::runtime_error("vkMapMemory failed");
        auto data = (uint16_t *)bytes;
        uint64_t nonfinite = 0, nonzero = 0;
        for (size_t p = 0; p < 1920 * 1080; ++p)
            for (size_t c = 0; c < 3; ++c) {
                const auto v = data[4 * p + c];
                nonfinite += ((v & 0x7c00) == 0x7c00);
                nonzero += (v & 0x7fff) != 0;
            }
        std::printf("output_rgb_half nonfinite=%llu nonzero=%llu center=%x,%x,%x\n", nonfinite,
                    nonzero, data[4 * (540 * 1920 + 960)], data[4 * (540 * 1920 + 960) + 1],
                    data[4 * (540 * 1920 + 960) + 2]);
        unmap(device, host_memory);
        if (nonfinite || nonzero != 1920u * 1080u * 3u)
            result = 5;
    }
    vk_symbol<PFN_vkDestroyCommandPool>("vkDestroyCommandPool")(device, pool, nullptr);
    for (uint32_t i = 0; i < PRIME_SL_IMAGE_COUNT; ++i) {
        vk_symbol<PFN_vkDestroyImageView>("vkDestroyImageView")(device, views[i], nullptr);
        vk_symbol<PFN_vkDestroyImage>("vkDestroyImage")(device, images[i], nullptr);
        vk_symbol<PFN_vkFreeMemory>("vkFreeMemory")(device, allocations[i], nullptr);
    }
    vk_symbol<PFN_vkDestroyBuffer>("vkDestroyBuffer")(device, readback, nullptr);
    vk_symbol<PFN_vkFreeMemory>("vkFreeMemory")(device, host_memory, nullptr);
    return result ? result : (submitted != VK_SUCCESS || completed != VK_SUCCESS ? 7 : 0);
}

template <class T> T vk_symbol(const char *name) {
    if (bootstrapped) {
        auto get_instance = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
                GetProcAddress(bootstrapped->module, "vkGetInstanceProcAddr"));
        auto get_device = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                GetProcAddress(bootstrapped->module, "vkGetDeviceProcAddr"));
        const bool physical_function = std::strncmp(name, "vkGetPhysicalDevice", 19) == 0;
        auto address =
                dispatch_device && !physical_function ? get_device(dispatch_device, name) : nullptr;
        if (!address)
            address = get_instance(dispatch_instance, name);
        if (address)
            return reinterpret_cast<T>(address);
    }
    auto pointer = reinterpret_cast<T>(GetProcAddress(vulkan_loader(), name));
    if (!pointer)
        throw std::runtime_error(name);
    return pointer;
}
VkBool32 VKAPI_CALL validation(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
                               VkDebugUtilsMessageTypeFlagsEXT,
                               const VkDebugUtilsMessengerCallbackDataEXT *data, void *) {
    if (severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        ++validation_errors;
    if (severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
        std::printf("VALIDATION: %s\n", data->pMessage);
    return abort_on_validation_error && severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
                   ? VK_TRUE
                   : VK_FALSE;
}
int main(int argc, char **argv) try {
    if (prime_sl_abi_version() != 2)
        throw std::runtime_error("Streamline GPU test requires bridge ABI v2");
    bool omit_write = false, api13 = false, init_only = false, synchronization2 = true,
         interposed = false;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--omit-write-without-format") == 0)
            omit_write = true;
        else if (std::strcmp(argv[i], "--vulkan13") == 0)
            api13 = true;
        else if (std::strcmp(argv[i], "--initialization-only") == 0)
            init_only = true;
        else if (std::strcmp(argv[i], "--abort-on-validation-error") == 0)
            abort_on_validation_error = true;
        else if (std::strcmp(argv[i], "--report-only-validation") == 0)
            abort_on_validation_error = false;
        else if (std::strcmp(argv[i], "--omit-specular-distance") == 0)
            omit_specular_distance = true;
        else if (std::strcmp(argv[i], "--synchronization2") == 0)
            synchronization2 = true;
        else if (std::strcmp(argv[i], "--omit-synchronization2") == 0)
            synchronization2 = false;
        else if (std::strcmp(argv[i], "--interposed") == 0)
            interposed = true;
        else
            throw std::runtime_error("Unknown test argument");
    }
    if (interposed) {
        // The pinned interposer checks only driver-global extensions and rejects layer-only
        // VK_EXT_validation_features. Khronos' documented setting enables the same sync checks.
        if (!SetEnvironmentVariableA("VK_LAYER_VALIDATE_SYNC", "1"))
            throw std::runtime_error("Cannot enable strict synchronization validation");
        int result = prime_sl_bootstrap();
        std::printf("prime_sl_bootstrap=%d error=%s\n", result, prime_sl_last_error());
        if (result)
            return 4;
    }
    auto create_instance = vk_symbol<PFN_vkCreateInstance>("vkCreateInstance");
    auto enumerate = vk_symbol<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
    auto properties = vk_symbol<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties");
    auto families = vk_symbol<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
            "vkGetPhysicalDeviceQueueFamilyProperties");
    auto get_features = vk_symbol<PFN_vkGetPhysicalDeviceFeatures2>("vkGetPhysicalDeviceFeatures2");
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Prime Streamline headless attachment test";
    app.apiVersion = api13 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_2;
    std::printf("API=%u.%u bridge_abi=2 output=1920x1080 quality=Performance preset=F "
                "motion_units=input_pixels specular_motion=explicit hit_distance_tag=absent "
                "validation=enabled synchronization_validation=enabled abort_on_error=%u\n",
                VK_API_VERSION_MAJOR(app.apiVersion), VK_API_VERSION_MINOR(app.apiVersion),
                unsigned(abort_on_validation_error));
    const auto enumerate_layers =
            vk_symbol<PFN_vkEnumerateInstanceLayerProperties>("vkEnumerateInstanceLayerProperties");
    uint32_t layer_count{};
    enumerate_layers(&layer_count, nullptr);
    std::vector<VkLayerProperties> layer_properties(layer_count);
    enumerate_layers(&layer_count, layer_properties.data());
    for (const auto &layer : layer_properties)
        if (std::strcmp(layer.layerName, "VK_LAYER_KHRONOS_validation") == 0)
            std::printf("validation_layer specVersion=%u.%u.%u implementationVersion=%u\n",
                        VK_API_VERSION_MAJOR(layer.specVersion),
                        VK_API_VERSION_MINOR(layer.specVersion),
                        VK_API_VERSION_PATCH(layer.specVersion), layer.implementationVersion);
    VkDebugUtilsMessengerCreateInfoEXT debug{
            VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debug.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
    debug.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
    debug.pfnUserCallback = validation;
    const char *layers[] = {"VK_LAYER_KHRONOS_validation"};
    const char *instance_extensions[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME,
                                         VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME};
    const VkValidationFeatureEnableEXT synchronization =
            VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT;
    VkValidationFeaturesEXT validation_features{VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT};
    validation_features.enabledValidationFeatureCount = 1;
    validation_features.pEnabledValidationFeatures = &synchronization;
    debug.pNext = interposed ? nullptr : &validation_features;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledLayerCount = 1;
    ici.ppEnabledLayerNames = layers;
    ici.enabledExtensionCount = interposed ? 1 : 2;
    ici.ppEnabledExtensionNames = instance_extensions;
    ici.pNext = &debug;
    VkInstance instance{};
    VkResult vr = create_instance(&ici, nullptr, &instance);
    std::printf("vkCreateInstance=%d\n", int(vr));
    if (vr)
        return 1;
    dispatch_instance = instance;
    auto create_device = vk_symbol<PFN_vkCreateDevice>("vkCreateDevice");
    auto destroy_instance = vk_symbol<PFN_vkDestroyInstance>("vkDestroyInstance");
    uint32_t count = 0;
    enumerate(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> physical(count);
    enumerate(instance, &count, physical.data());
    VkPhysicalDevice selected{};
    VkPhysicalDeviceProperties props{};
    for (auto p : physical) {
        properties(p, &props);
        if (props.vendorID == 0x10de) {
            selected = p;
            break;
        }
    }
    if (!selected) {
        destroy_instance(instance, nullptr);
        return 2;
    }
    std::printf("adapter=%s driverVersion=0x%08x\n", props.deviceName, props.driverVersion);
    families(selected, &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    families(selected, &count, queues.data());
    uint32_t family = 0;
    for (; family < count; ++family)
        if ((queues[family].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
            (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))
            break;
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkPhysicalDeviceVulkan12Features supported12{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 supported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    supported.pNext = &supported12;
    get_features(selected, &supported);
    auto get_instance_proc = vk_symbol<PFN_vkGetInstanceProcAddr>("vkGetInstanceProcAddr");
    auto create_messenger = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
            get_instance_proc(instance, "vkCreateDebugUtilsMessengerEXT"));
    auto destroy_messenger = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
            get_instance_proc(instance, "vkDestroyDebugUtilsMessengerEXT"));
    VkDebugUtilsMessengerEXT messenger{};
    debug.pNext = nullptr;
    create_messenger(instance, &debug, nullptr, &messenger);
    VkPhysicalDeviceVulkan12Features enabled12{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    enabled12.timelineSemaphore = VK_TRUE;
    enabled12.descriptorIndexing = VK_TRUE;
    enabled12.bufferDeviceAddress = VK_TRUE;
    std::vector<const char *> extensions = {
            VK_NVX_BINARY_IMPORT_EXTENSION_NAME, VK_NVX_IMAGE_VIEW_HANDLE_EXTENSION_NAME,
            VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME, VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME};
    VkPhysicalDeviceFeatures enabled{};
    enabled.shaderStorageImageExtendedFormats = VK_TRUE;
    if (!omit_write) {
        enabled.shaderStorageImageWriteWithoutFormat = VK_TRUE;
    }
    VkPhysicalDeviceVulkan13Features enabled13{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceSynchronization2Features enabled_synchronization2{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES};
    VkPhysicalDevicePrivateDataFeatures enabled_private_data{
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES};
    if (api13) {
        enabled13.privateData = VK_TRUE;
        enabled13.synchronization2 = synchronization2 ? VK_TRUE : VK_FALSE;
        enabled12.pNext = &enabled13;
    } else if (synchronization2) {
        enabled_synchronization2.synchronization2 = VK_TRUE;
        enabled12.pNext = &enabled_synchronization2;
        extensions.push_back(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
    }
    if (interposed && !api13) {
        enabled_private_data.privateData = VK_TRUE;
        enabled_private_data.pNext = enabled12.pNext;
        enabled12.pNext = &enabled_private_data;
    }
    std::printf(
            "unformatted_storage writeEnabled=%u readEnabled=%u readSupported=%u writeSupported=%u\n",
            enabled.shaderStorageImageWriteWithoutFormat,
            enabled.shaderStorageImageReadWithoutFormat,
            supported.features.shaderStorageImageReadWithoutFormat,
            supported.features.shaderStorageImageWriteWithoutFormat);
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &enabled12;
    dci.pEnabledFeatures = &enabled;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    dci.ppEnabledExtensionNames = extensions.data();
    VkDevice device{};
    vr = create_device(selected, &dci, nullptr, &device);
    std::printf("vkCreateDevice=%d\n", int(vr));
    if (vr) {
        destroy_instance(instance, nullptr);
        return 3;
    }
    dispatch_device = device;
    auto destroy_device = vk_symbol<PFN_vkDestroyDevice>("vkDestroyDevice");
    PrimeSlInit init{reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(selected),
                     reinterpret_cast<uint64_t>(device), family, 0};
    void *context{};
    int result = prime_sl_create(&init, &context);
    std::printf("prime_sl_create=%d error=%s\n", result, prime_sl_last_error());
    if (context) {
        PrimeSlSize size{};
        result = prime_sl_configure(context, 1920, 1080, 3, &size);
        std::printf("prime_sl_configure=%d render=%ux%u error=%s\n", result, size.render_width,
                    size.render_height, prime_sl_last_error());
        if (!result && !init_only)
            result = evaluate_frame(selected, device, family, context, size);
        int destroyed = prime_sl_destroy(context);
        std::printf("prime_sl_destroy=%d\n", destroyed);
        if (!result)
            result = destroyed;
    }
    if (interposed) {
        int shutdown = prime_sl_frame(4, 0);
        std::printf("prime_sl_frame(shutdown)=%d\n", shutdown);
        if (!result)
            result = shutdown;
    }
    destroy_device(device, nullptr);
    destroy_messenger(instance, messenger, nullptr);
    destroy_instance(instance, nullptr);
    std::printf("validation_errors=%u\n", validation_errors.load());
    return result ? 4 : (validation_errors.load() ? 6 : 0);
}

catch (const std::exception &error) {
    std::fprintf(stderr, "Streamline GPU test failed: %s\n", error.what());
    return 1;
}
