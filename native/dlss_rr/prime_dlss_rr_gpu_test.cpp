// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
// Optional real-NGX test. Run on an RTX device with the packaged nvngx_dlssd.dll directory.
#include "prime_dlss_rr_bridge.cpp"
#include <atomic>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr std::size_t STAR_MAP = IMAGE_COUNT, TRANSMITTANCE = IMAGE_COUNT + 1, VISIBILITY = IMAGE_COUNT + 2;
VKAPI_ATTR VkBool32 VKAPI_CALL validationMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
        VkDebugUtilsMessageTypeFlagsEXT, const VkDebugUtilsMessengerCallbackDataEXT* message, void* user) {
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
        static_cast<std::atomic_uint*>(user)->fetch_add(1, std::memory_order_relaxed);
        std::fprintf(stderr, "Vulkan validation: %s\n", message->pMessage);
    }
    return VK_FALSE;
}
void require(int result, const char* operation) {
    if (result != 0) throw std::runtime_error(std::string(operation) + ": " + std::to_string(result));
}
float fromHalf(std::uint16_t value) {
    auto exponent = (value >> 10) & 31;
    float sign = (value & 0x8000) ? -1.0F : 1.0F;
    if (exponent == 31) return INFINITY;
    return sign * (exponent == 0 ? std::ldexp(float(value & 1023), -24)
            : std::ldexp(float(1024 + (value & 1023)), int(exponent) - 25));
}
std::uint16_t toHalf(float value) {
    std::uint32_t bits;
    std::memcpy(&bits, &value, 4);
    if (value == 0) return 0;
    int exponent = int((bits >> 23) & 255) - 127 + 15;
    return std::uint16_t(((bits >> 16) & 0x8000) | (exponent << 10) | ((bits & 0x7fffff) >> 13));
}
float halton(int index, int base) {
    float value = 0, weight = 1;
    while (index > 0) { weight /= float(base); value += float(index % base) * weight; index /= base; }
    return value - 0.5F;
}
struct Image {
    VkImage image{};
    VkImageView view{};
    VkDeviceMemory memory{};
    VkFormat format{};
    std::uint32_t width{}, height{}, bytes{};
    VkDeviceSize offset{};
};
struct Gpu {
    VkInstance instance{};
    VkDebugUtilsMessengerEXT messenger{};
    // Validation callbacks may come from driver threads; the test owns this one error counter.
    std::atomic_uint validationErrors{};
    VkPhysicalDevice physical{};
    VkDevice device{};
    VkQueue queue{};
    VkCommandPool pool{};
    VkCommandBuffer command{};
    VkBuffer staging{};
    VkDeviceMemory stagingMemory{};
    std::byte* mapped{};
    void* context{};
    void* feature{};
    VkSampler sampler{};
    VkDescriptorSetLayout setLayout{};
    VkDescriptorPool descriptors{};
    VkDescriptorSet set{};
    VkPipelineLayout pipelineLayout{};
    VkPipeline pipeline{};
    VkShaderModule shader{};
    std::array<Image, IMAGE_COUNT + 3> images{};
    ~Gpu() {
        if (device) vkDeviceWaitIdle(device);
        primeDlssRrReleaseFeature(feature);
        primeDlssRrShutdown(context);
        if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
        if (shader) vkDestroyShaderModule(device, shader, nullptr);
        if (pipelineLayout) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        if (descriptors) vkDestroyDescriptorPool(device, descriptors, nullptr);
        if (setLayout) vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
        if (sampler) vkDestroySampler(device, sampler, nullptr);
        for (auto& image : images) {
            if (image.view) vkDestroyImageView(device, image.view, nullptr);
            if (image.image) vkDestroyImage(device, image.image, nullptr);
            if (image.memory) vkFreeMemory(device, image.memory, nullptr);
        }
        if (mapped) vkUnmapMemory(device, stagingMemory);
        if (staging) vkDestroyBuffer(device, staging, nullptr);
        if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
        if (pool) vkDestroyCommandPool(device, pool, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (messenger) reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"))(instance, messenger, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    }
    std::uint32_t memoryType(std::uint32_t mask, VkMemoryPropertyFlags flags) {
        VkPhysicalDeviceMemoryProperties properties;
        vkGetPhysicalDeviceMemoryProperties(physical, &properties);
        for (std::uint32_t i = 0; i < properties.memoryTypeCount; i++)
            if ((mask & (1u << i)) && (properties.memoryTypes[i].propertyFlags & flags) == flags) return i;
        throw std::runtime_error("No compatible Vulkan memory type");
    }
    void begin() {
        require(vkResetCommandBuffer(command, 0), "reset command");
        VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        require(vkBeginCommandBuffer(command, &info), "begin command");
    }
    void submit() {
        require(vkEndCommandBuffer(command), "end command");
        VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        info.commandBufferCount = 1; info.pCommandBuffers = &command;
        require(vkQueueSubmit(queue, 1, &info, VK_NULL_HANDLE), "submit");
        require(vkQueueWaitIdle(queue), "wait");
    }
    void barrier(VkPipelineStageFlags source, VkPipelineStageFlags destination,
            VkAccessFlags sourceAccess, VkAccessFlags destinationAccess) {
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        b.srcAccessMask = sourceAccess; b.dstAccessMask = destinationAccess;
        vkCmdPipelineBarrier(command, source, destination, 0, 1, &b, 0, nullptr, 0, nullptr);
    }
};
}

int wmain(int argc, wchar_t** argv) try {
    if (argc != 4) throw std::runtime_error("Usage: prime_dlss_rr_gpu_test <NGX directory> <work directory> <rr_stars SPIR-V>");
    auto featurePath = std::filesystem::absolute(argv[1]).wstring();
    auto workPath = std::filesystem::absolute(argv[2]).wstring();
    std::filesystem::create_directories(workPath);
    Gpu gpu;
    char names[64][EXTENSION_NAME_STRIDE]{};
    PrimeExtensionQuery query{};
    query.capacity = 64; query.names = &names[0][0];
    query.featurePath = featurePath.c_str(); query.applicationDataPath = workPath.c_str();
    query.engineVersion = "Prime RR alpha test";
    require(primeDlssRrGetInstanceExtensions(&query), "instance extensions");
    std::vector<const char*> extensions;
    for (std::uint32_t i = 0; i < query.count; i++) extensions.push_back(names[i]);
    extensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    const char* layer = "VK_LAYER_KHRONOS_validation";
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_3;
    VkInstanceCreateInfo instance{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; instance.pApplicationInfo = &app;
    instance.enabledExtensionCount = std::uint32_t(extensions.size()); instance.ppEnabledExtensionNames = extensions.data();
    instance.enabledLayerCount = 1; instance.ppEnabledLayerNames = &layer;
    require(vkCreateInstance(&instance, nullptr, &gpu.instance), "create instance");
    VkDebugUtilsMessengerCreateInfoEXT debug{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debug.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    debug.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debug.pfnUserCallback = validationMessage; debug.pUserData = &gpu.validationErrors;
    require(reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(gpu.instance,
            "vkCreateDebugUtilsMessengerEXT"))(gpu.instance, &debug, nullptr, &gpu.messenger), "create validation callback");
    std::uint32_t count = 0;
    require(vkEnumeratePhysicalDevices(gpu.instance, &count, nullptr), "enumerate GPUs");
    std::vector<VkPhysicalDevice> devices(count);
    require(vkEnumeratePhysicalDevices(gpu.instance, &count, devices.data()), "enumerate GPUs");
    for (auto device : devices) {
        VkPhysicalDeviceProperties p; vkGetPhysicalDeviceProperties(device, &p);
        if (p.vendorID == 0x10de) { gpu.physical = device; std::printf("GPU: %s\n", p.deviceName); break; }
    }
    if (!gpu.physical) throw std::runtime_error("An NVIDIA RTX device is required");
    query.instance = reinterpret_cast<std::uint64_t>(gpu.instance);
    query.physicalDevice = reinterpret_cast<std::uint64_t>(gpu.physical);
    require(primeDlssRrGetDeviceExtensions(&query), "device extensions");
    extensions.clear();
    for (std::uint32_t i = 0; i < query.count; i++) extensions.push_back(names[i]);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu.physical, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu.physical, &count, families.data());
    std::uint32_t family = 0;
    while (family < count && !(families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) family++;
    if (family == count) throw std::runtime_error("No graphics queue");
    float priority = 1;
    VkDeviceQueueCreateInfo queue{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue.queueFamilyIndex = family; queue.queueCount = 1; queue.pQueuePriorities = &priority;
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}; f12.pNext = &f13;
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2}; features.pNext = &f12;
    vkGetPhysicalDeviceFeatures2(gpu.physical, &features);
    VkDeviceCreateInfo device{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; device.pNext = &features;
    device.queueCreateInfoCount = 1; device.pQueueCreateInfos = &queue;
    device.enabledExtensionCount = std::uint32_t(extensions.size()); device.ppEnabledExtensionNames = extensions.data();
    require(vkCreateDevice(gpu.physical, &device, nullptr, &gpu.device), "create device");
    vkGetDeviceQueue(gpu.device, family, 0, &gpu.queue);
    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool.queueFamilyIndex = family; pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    require(vkCreateCommandPool(gpu.device, &pool, nullptr, &gpu.pool), "create command pool");
    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = gpu.pool; alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; alloc.commandBufferCount = 1;
    require(vkAllocateCommandBuffers(gpu.device, &alloc, &gpu.command), "allocate command");
    PrimeInitDescription init{query.instance, query.physicalDevice, reinterpret_cast<std::uint64_t>(gpu.device),
            featurePath.c_str(), workPath.c_str(), query.engineVersion, &gpu.context};
    require(primeDlssRrInitialize(&init), "NGX initialize");
    PrimeOptimalSettings optimal{gpu.context, 1280, 720, NVSDK_NGX_PerfQuality_Value_MaxQuality};
    require(primeDlssRrGetOptimalSettings(&optimal), "NGX optimal size");
    gpu.begin();
    PrimeFeatureDescription feature{gpu.context, reinterpret_cast<std::uint64_t>(gpu.command),
            optimal.renderWidth, optimal.renderHeight, optimal.outputWidth, optimal.outputHeight,
            optimal.quality, 0, &gpu.feature};
    require(primeDlssRrCreateFeature(&feature), "NGX create with alpha"); gpu.submit();

    const VkFormat formats[IMAGE_COUNT + 3] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_UNDEFINED, VK_FORMAT_R16_SFLOAT, VK_FORMAT_UNDEFINED,
        VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8_UINT};
    const std::uint32_t sizes[IMAGE_COUNT + 3] = {8,8,16,8,8,4,8,0,2,0,8,8,1};
    VkDeviceSize total = 0;
    gpu.begin();
    for (std::size_t i = 0; i < gpu.images.size(); i++) {
        if (!sizes[i]) continue;
        auto& im = gpu.images[i]; im.format = formats[i]; im.bytes = sizes[i]; im.offset = total;
        im.width = i == OUTPUT_COLOR ? optimal.outputWidth : optimal.renderWidth;
        im.height = i == OUTPUT_COLOR ? optimal.outputHeight : optimal.renderHeight;
        if (i == STAR_MAP) { im.width = 16; im.height = 16; }
        if (i == TRANSMITTANCE) { im.width = 64; im.height = 1; }
        total = (total + VkDeviceSize(im.width) * im.height * im.bytes + 15) & ~VkDeviceSize(15);
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = im.format; ci.extent = {im.width, im.height, 1}; ci.mipLevels = 1; ci.arrayLayers = 1;
        ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        require(vkCreateImage(gpu.device, &ci, nullptr, &im.image), "create image");
        VkMemoryRequirements req; vkGetImageMemoryRequirements(gpu.device, im.image, &req);
        VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mi.allocationSize = req.size;
        mi.memoryTypeIndex = gpu.memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        require(vkAllocateMemory(gpu.device, &mi, nullptr, &im.memory), "allocate image");
        require(vkBindImageMemory(gpu.device, im.image, im.memory, 0), "bind image");
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = im.image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = im.format; vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        require(vkCreateImageView(gpu.device, &vi, nullptr, &im.view), "create view");
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.image = im.image;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.subresourceRange = vi.subresourceRange; b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vkCmdPipelineBarrier(gpu.command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, 0, nullptr, 0, nullptr, 1, &b);
    }
    gpu.submit();
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size = total;
    bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    require(vkCreateBuffer(gpu.device, &bi, nullptr, &gpu.staging), "create staging");
    VkMemoryRequirements req; vkGetBufferMemoryRequirements(gpu.device, gpu.staging, &req);
    VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mi.allocationSize = req.size;
    mi.memoryTypeIndex = gpu.memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    require(vkAllocateMemory(gpu.device, &mi, nullptr, &gpu.stagingMemory), "allocate staging");
    require(vkBindBufferMemory(gpu.device, gpu.staging, gpu.stagingMemory, 0), "bind staging");
    require(vkMapMemory(gpu.device, gpu.stagingMemory, 0, total, 0, reinterpret_cast<void**>(&gpu.mapped)), "map staging");
    std::memset(gpu.mapped, 0, std::size_t(total));

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = VK_FILTER_LINEAR; si.minFilter = VK_FILTER_LINEAR; si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT; si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    require(vkCreateSampler(gpu.device, &si, nullptr, &gpu.sampler), "create star sampler");
    VkDescriptorSetLayoutBinding bindings[4]{};
    for (std::uint32_t i = 0; i < 4; i++) bindings[i] = {i,
            i == 1 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo sl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    sl.bindingCount = 4; sl.pBindings = bindings;
    require(vkCreateDescriptorSetLayout(gpu.device, &sl, nullptr, &gpu.setLayout), "create star layout");
    VkDescriptorPoolSize poolSizes[] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1}};
    VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dp.maxSets = 1; dp.poolSizeCount = 2; dp.pPoolSizes = poolSizes;
    require(vkCreateDescriptorPool(gpu.device, &dp, nullptr, &gpu.descriptors), "create star descriptors");
    VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    da.descriptorPool = gpu.descriptors; da.descriptorSetCount = 1; da.pSetLayouts = &gpu.setLayout;
    require(vkAllocateDescriptorSets(gpu.device, &da, &gpu.set), "allocate star descriptors");
    const std::size_t imageIndices[] = {OUTPUT_COLOR, STAR_MAP, TRANSMITTANCE, VISIBILITY};
    VkDescriptorImageInfo infos[4]{}; VkWriteDescriptorSet writes[4]{};
    for (std::uint32_t i = 0; i < 4; i++) {
        infos[i] = {i == 1 ? gpu.sampler : VK_NULL_HANDLE, gpu.images[imageIndices[i]].view, VK_IMAGE_LAYOUT_GENERAL};
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[i].dstSet = gpu.set;
        writes[i].dstBinding = i; writes[i].descriptorCount = 1; writes[i].descriptorType = bindings[i].descriptorType;
        writes[i].pImageInfo = &infos[i];
    }
    vkUpdateDescriptorSets(gpu.device, 4, writes, 0, nullptr);
    VkPushConstantRange pushRange{VK_SHADER_STAGE_COMPUTE_BIT, 0, 104};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1; pl.pSetLayouts = &gpu.setLayout; pl.pushConstantRangeCount = 1; pl.pPushConstantRanges = &pushRange;
    require(vkCreatePipelineLayout(gpu.device, &pl, nullptr, &gpu.pipelineLayout), "create star pipeline layout");
    std::ifstream file(argv[3], std::ios::binary | std::ios::ate);
    if (!file || file.tellg() <= 0 || file.tellg() % 4 != 0) throw std::runtime_error("Invalid stars SPIR-V");
    std::vector<std::uint32_t> spirv(std::size_t(file.tellg()) / 4);
    file.seekg(0); file.read(reinterpret_cast<char*>(spirv.data()), std::streamsize(spirv.size() * 4));
    if (!file) throw std::runtime_error("Reading stars SPIR-V failed");
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; sm.codeSize = spirv.size() * 4; sm.pCode = spirv.data();
    require(vkCreateShaderModule(gpu.device, &sm, nullptr, &gpu.shader), "create stars shader");
    VkComputePipelineCreateInfo pc{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pc.layout = gpu.pipelineLayout;
    pc.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; pc.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pc.stage.module = gpu.shader; pc.stage.pName = "main";
    require(vkCreateComputePipelines(gpu.device, VK_NULL_HANDLE, 1, &pc, nullptr, &gpu.pipeline), "create stars pipeline");

    double worstSky = 0, worstForeground = 0;
    double worstLeak = 0, worstStarError = 0;
    double worstEdgeError = 0;
    for (int frame = 0; frame < 64; frame++) {
        // A flat opaque silhouette moves across a deterministic sky; RGB and alpha share its edge.
        // The second sequence reverses direction after a camera cut and uses subpixel jitter.
        int phase = frame % 32;
        float movement = frame < 32 ? 0.5F : -0.5F;
        float edge = (frame < 32 ? 0.5F : 0.6F) + float(phase) * movement / float(optimal.outputWidth);
        float jitterX = frame < 32 ? 0.0F : halton(phase + 1, 2);
        float jitterY = frame < 32 ? 0.0F : halton(phase + 1, 3);
        gpu.begin();
        gpu.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        PrimeEvaluateDescription eval{}; eval.feature = gpu.feature;
        eval.commandBuffer = reinterpret_cast<std::uint64_t>(gpu.command);
        eval.renderWidth = optimal.renderWidth; eval.renderHeight = optimal.renderHeight;
        eval.motionScaleX = float(optimal.renderWidth); eval.motionScaleY = float(optimal.renderHeight);
        eval.frameTimeMilliseconds = 16.667F; eval.reset = phase == 0;
        eval.jitterX = jitterX; eval.jitterY = jitterY;
        for (int c = 0; c < 4; c++) { eval.worldToView[c*4+c] = 1; eval.viewToClip[c*4+c] = 1; }
        for (std::size_t i = 0; i < gpu.images.size(); i++) {
            auto& im = gpu.images[i]; if (!im.image) continue;
            if (i < IMAGE_COUNT) eval.images[i] = {reinterpret_cast<std::uint64_t>(im.image), reinterpret_cast<std::uint64_t>(im.view),
                    im.format, im.width, im.height, 0};
            if (i == OUTPUT_COLOR) continue;
            for (std::uint32_t y = 0; y < im.height; y++) for (std::uint32_t x = 0; x < im.width; x++) {
                bool surface = (float(x) + 0.5F + jitterX) / float(im.width) < edge;
                float values[4]{};
                switch (i) {
                    case INPUT_COLOR: values[0] = surface ? 1.0F : 0.02F; values[1] = surface ? 0.4F : 0.03F;
                        values[2] = surface ? 0.2F : 0.04F; values[3] = surface ? 1.0F : 0.0F; break;
                    case DIFFUSE_ALBEDO: values[0] = values[1] = values[2] = 0.5F; break;
                    case NORMAL_ROUGHNESS: values[2] = surface ? 1.0F : 0.0F; values[3] = 1.0F; break;
                    case LINEAR_DEPTH: values[0] = surface ? 2.0F : 65504.0F; break;
                    case SPECULAR_HIT_DISTANCE: values[0] = 65504.0F; break;
                    case MOTION_VECTORS: values[0] = surface && phase > 0 ? -movement / float(optimal.outputWidth) : 0.0F; break;
                    case STAR_MAP: case TRANSMITTANCE: values[0] = values[1] = values[2] = values[3] = 1.0F; break;
                }
                auto dest = gpu.mapped + im.offset + (VkDeviceSize(y) * im.width + x) * im.bytes;
                if (i == VISIBILITY) *reinterpret_cast<std::uint8_t*>(dest) = surface ? 0x04 : 0;
                else if (im.format == VK_FORMAT_R32G32B32A32_SFLOAT || im.format == VK_FORMAT_R32G32_SFLOAT || im.format == VK_FORMAT_R32_SFLOAT)
                    std::memcpy(dest, values, im.bytes);
                else for (std::uint32_t c = 0; c < im.bytes / 2; c++) reinterpret_cast<std::uint16_t*>(dest)[c] = toHalf(values[c]);
            }
            VkBufferImageCopy copy{}; copy.bufferOffset = im.offset; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
            copy.imageExtent = {im.width, im.height,1};
            vkCmdCopyBufferToImage(gpu.command, gpu.staging, im.image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
        }
        gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        require(primeDlssRrEvaluate(&eval), "NGX evaluate alpha");
        gpu.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        auto& out = gpu.images[OUTPUT_COLOR];
        VkBufferImageCopy copy{}; copy.bufferOffset = out.offset; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        copy.imageExtent = {out.width,out.height,1};
        vkCmdCopyImageToBuffer(gpu.command, out.image, VK_IMAGE_LAYOUT_GENERAL, gpu.staging, 1, &copy);
        gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
        gpu.submit();
        auto colors = reinterpret_cast<std::uint16_t*>(gpu.mapped + out.offset);
        double frameError = 0;
        std::uint32_t worstX = 0, worstY = 0;
        for (std::uint32_t y = 16; y < out.height - 16; y++) for (std::uint32_t x = 16; x < out.width - 16; x++) {
            auto index = (VkDeviceSize(y) * out.width + x) * 4;
            for (int c = 0; c < 4; c++) if (!std::isfinite(fromHalf(colors[index+c]))) throw std::runtime_error("Nonfinite NGX output");
            float alpha = fromHalf(colors[index+3]);
            if (alpha < -0.01F || alpha > 1.01F) throw std::runtime_error("NGX alpha outside coverage range");
            float distance = float(x) + 0.5F - edge * float(out.width);
            if (distance > 12) worstSky = std::max(worstSky, double(std::abs(alpha)));
            if (distance < -12) {
                double error = double(std::abs(1-alpha));
                worstForeground = std::max(worstForeground, error);
                if (error > frameError) { frameError = error; worstX = x; worstY = y; }
            }
        }
        std::printf("frame %d: foreground error %.6f at %u,%u; center alpha %.6f\n",
                frame, frameError, worstX, worstY, fromHalf(colors[(out.width * (out.height/2) + out.width/4)*4+3]));
        std::vector<std::uint16_t> before(colors, colors + std::size_t(out.width) * out.height * 4);
        gpu.begin();
        gpu.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        // Column-major inverse projection rotated toward the zenith; every pixel sees atmosphere.
        float push[26] = {1,0,0,0, 0,0,-1,0, 0,0,0,19.998F, 0,1,0,0.002F,
                0,1,0,6360.1F, 0,0,0.125F,0, jitterX,jitterY};
        vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipeline);
        vkCmdBindDescriptorSets(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipelineLayout, 0, 1, &gpu.set, 0, nullptr);
        vkCmdPushConstants(gpu.command, gpu.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), push);
        vkCmdDispatch(gpu.command, (out.width + 7) / 8, (out.height + 7) / 8, 1);
        gpu.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        vkCmdCopyImageToBuffer(gpu.command, out.image, VK_IMAGE_LAYOUT_GENERAL, gpu.staging, 1, &copy);
        gpu.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
        gpu.submit();
        for (std::uint32_t y = 16; y < out.height - 16; y++) for (std::uint32_t x = 16; x < out.width - 16; x++) {
            auto index = (VkDeviceSize(y) * out.width + x) * 4;
            float distance = float(x) + 0.5F - edge * float(out.width);
            for (int c = 0; c < 3; c++) {
                float delta = fromHalf(colors[index+c]) - fromHalf(before[index+c]);
                if (!std::isfinite(delta)) throw std::runtime_error("Nonfinite stars output");
                if (distance < -12) worstLeak = std::max(worstLeak, double(std::abs(delta)));
                if (distance > 12) worstStarError = std::max(worstStarError, double(std::abs(delta - 0.125F)));
                // At the silhouette itself the shader must preserve the NGX antialiased coverage.
                if (std::abs(distance) < 0.5F) {
                    float expected = 0.125F * (1.0F - std::clamp(fromHalf(before[index+3]), 0.0F, 1.0F));
                    worstEdgeError = std::max(worstEdgeError, double(std::abs(delta - expected)));
                }
            }
        }
    }
    std::printf("64 NGX frames: max sky alpha %.6f, max foreground coverage error %.6f\n", worstSky, worstForeground);
    std::printf("Production stars pass: max foreground leak %.6f, max sky radiance error %.6f\n", worstLeak, worstStarError);
    std::printf("Max silhouette coverage composition error: %.6f\n", worstEdgeError);
    if (worstSky > 0.02 || worstLeak > 0.001 || worstStarError > 0.002 || worstEdgeError > 0.002)
        throw std::runtime_error("Stars composite failed coverage limits");
    if (gpu.validationErrors.load(std::memory_order_relaxed) != 0) throw std::runtime_error("Vulkan validation errors");
    return 0;
} catch (const std::exception& error) {
    std::fprintf(stderr, "%s\n", error.what());
    return 1;
}
