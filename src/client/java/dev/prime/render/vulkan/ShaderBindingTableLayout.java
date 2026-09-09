// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

record ShaderBindingTableLayout(
        long recordStride,
        long raygenRecordStride,
        long raygenOffset,
        long missOffset,
        long hitOffset,
        long totalSize) {

    static ShaderBindingTableLayout create(
            int handleSize,
            int handleAlignment,
            int baseAlignment,
            int raygenDataSize,
            int raygenGroupCount,
            int missGroupCount,
            int hitGroupCount,
            long bufferAddress) {
        if (handleSize <= 0
                || raygenDataSize < 0
                || raygenGroupCount <= 0
                || missGroupCount <= 0
                || hitGroupCount <= 0) {
            throw new IllegalArgumentException("Shader binding table dimensions must be positive");
        }
        long recordStride = VulkanContext.alignUp(handleSize, handleAlignment);
        // vkCmdTraceRaysKHR requires the selected raygen address itself to satisfy the base
        // alignment. The raygen record also carries a small scheduler selector after its handle.
        long raygenRecordStride = VulkanContext.alignUp(
                Math.addExact((long) handleSize, raygenDataSize), baseAlignment);
        long raygenOffset = VulkanContext.alignUp(bufferAddress, baseAlignment) - bufferAddress;
        long raygenSize = Math.multiplyExact(raygenRecordStride, raygenGroupCount);
        long missOffset = VulkanContext.alignUp(bufferAddress + raygenOffset + raygenSize, baseAlignment)
                - bufferAddress;
        long missSize = Math.multiplyExact(recordStride, missGroupCount);
        long hitOffset = VulkanContext.alignUp(bufferAddress + missOffset + missSize, baseAlignment) - bufferAddress;
        long totalSize = Math.addExact(hitOffset, Math.multiplyExact(recordStride, hitGroupCount));
        return new ShaderBindingTableLayout(
                recordStride, raygenRecordStride, raygenOffset, missOffset, hitOffset, totalSize);
    }

    static long minimumBufferSize(
            int handleSize,
            int handleAlignment,
            int baseAlignment,
            int raygenDataSize,
            int raygenGroupCount,
            int missGroupCount,
            int hitGroupCount) {
        ShaderBindingTableLayout aligned = create(
                handleSize,
                handleAlignment,
                baseAlignment,
                raygenDataSize,
                raygenGroupCount,
                missGroupCount,
                hitGroupCount,
                0L);
        return Math.addExact(aligned.totalSize(), baseAlignment - 1L);
    }
}
