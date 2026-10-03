package dev.primept.display;

import dev.primept.NativeBridge;
import dev.primept.abi.PrimeAbi;
import static dev.primept.abi.PrimeAbi.*;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** Independent SDR-to-scRGB owner for menus and vanilla. Retirement needs actual host completion. */
public final class HdrSurfaceBridge implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment target = arena.allocate(PrimeHdrTarget.LAYOUT);
    private final MemorySegment output = arena.allocate(PrimeDisplayOutput.LAYOUT);
    private final MethodHandle record, destroy;
    private long handle;

    public HdrSurfaceBridge(long instance, long physical, long device, long queue, long timeline,
                            int queueFamily) throws IOException {
        var lookup = SymbolLookup.libraryLookup(NativeBridge.resolveLibrary().toAbsolutePath(),
                                                Arena.global());
        record
        = PrimeAbi.bind(lookup, "prime_hdr_surface_record");
        destroy = PrimeAbi.bind(lookup, "prime_hdr_surface_destroy");
        var create = PrimeAbi.bind(lookup, "prime_hdr_surface_create");
        try {
            var host = arena.allocate(PrimeVulkanHost.LAYOUT);
            header(PrimeVulkanHost.header(host), PrimeVulkanHost.SIZE);
            PrimeVulkanHost.instance(host, instance);
            PrimeVulkanHost.physical_device(host, physical);
            PrimeVulkanHost.device(host, device);
            PrimeVulkanHost.queue(host, queue);
            PrimeVulkanHost.timeline(host, timeline);
            PrimeVulkanHost.queue_family(host, queueFamily);
            PrimeVulkanHost.capabilities(host, 0);
            handle = (long)create.invokeExact(host);
            if (handle == 0)
                throw new IllegalStateException("Cannot create HDR surface conversion owner");
        } catch (Throwable failure) {
            arena.close();
            throw rethrow(failure);
        }
    }

    public void record(long command, long uiImage, long uiView, long outputImage, long outputView,
                       long serial, int width, int height, HdrOutput.Calibration calibration) {
        checkOwner();
        header(PrimeHdrTarget.header(target), PrimeHdrTarget.SIZE);
        PrimeHdrTarget.command(target, command);
        PrimeHdrTarget.ui_image(target, uiImage);
        PrimeHdrTarget.ui_view(target, uiView);
        PrimeHdrTarget.output_image(target, outputImage);
        PrimeHdrTarget.output_view(target, outputView);
        PrimeHdrTarget.serial(target, serial);
        PrimeHdrTarget.width(target, width);
        PrimeHdrTarget.height(target, height);
        header(PrimeDisplayOutput.header(output), PrimeDisplayOutput.SIZE);
        PrimeDisplayOutput.active(output, 1);
        PrimeDisplayOutput.peak_nits(output, calibration.maximumNits());
        // The fallback has no world controls: this is the effective white already selected by Java.
        PrimeDisplayOutput.system_white_nits(output, calibration.referenceWhiteNits());
        PrimeDisplayOutput.reserved(output, 0);
        try {
            int status = (int)record.invokeExact(handle, target, output);
            if (status != 0)
                throw new IllegalStateException("HDR surface conversion failed: " + status);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    @Override
    public void close() {
        checkOwner();
        try {
            int status = (int)destroy.invokeExact(handle);
            if (status != 0)
                throw new IllegalStateException("HDR surface retirement has no completion proof: " +
                                                status);
            handle = 0;
            arena.close();
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner || handle == 0)
            throw new IllegalStateException("HDR surface owner is closed or on the wrong thread");
    }
    private static void header(MemorySegment header, long size) {
        PrimeHeader.abi_version(header, PRIME_ABI_VERSION);
        PrimeHeader.struct_size(header, Math.toIntExact(size));
    }
    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime)
            return runtime;
        if (failure instanceof Error error)
            throw error;
        return new IllegalStateException(failure);
    }
}
