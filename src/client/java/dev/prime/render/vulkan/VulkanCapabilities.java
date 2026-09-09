// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

public record VulkanCapabilities(
        boolean available,
        String deviceName,
        String unavailableReason,
        int shaderGroupHandleSize,
        int shaderGroupHandleAlignment,
        int shaderGroupBaseAlignment,
        int maxShaderGroupStride,
        int maxRayDispatchInvocationCount,
        int maxRayRecursionDepth,
        long maxAccelerationStructurePrimitiveCount,
        long maxAccelerationStructureInstanceCount,
        int accelerationStructureScratchAlignment,
        boolean wavefrontSubgroupSupported,
        boolean invocationReorderSupported,
        boolean opacityMicromapSupported,
        int maxOpacity2StateSubdivisionLevel,
        int maxOpacity4StateSubdivisionLevel,
        boolean fsrFp16Supported) {

    public String wavefrontShaderSuffix() {
        return wavefrontShaderSuffix(
                this.wavefrontSubgroupSupported,
                this.invocationReorderSupported);
    }

    static String wavefrontShaderSuffix(
            boolean subgroupSupported, boolean invocationReorderSupported) {
        return subgroupSupported && invocationReorderSupported
                ? "_ser.rgen.spv"
                : ".rgen.spv";
    }

    public static VulkanCapabilities unavailable(String deviceName, String reason) {
        return new VulkanCapabilities(
                false,
                deviceName,
                reason,
                0,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0,
                false,
                false,
                false,
                0,
                0,
                false);
    }
}
