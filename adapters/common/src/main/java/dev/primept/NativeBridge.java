package dev.primept;

import dev.primept.settings.RenderSettings;
import dev.primept.capture.Packets;
import dev.primept.capture.SourcePages;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** One render-thread owner. Native code consumes borrowed input before return and owns all retained data. */
public final class NativeBridge implements AutoCloseable {
    private static volatile MethodHandle vulkanPresent;
    private final Thread owner = Thread.currentThread();
    private final MethodHandle submit, renderDiagnostic, attachVulkan, configure, record, gpuTime,
            cpuDiagnostics, planSections, acceptSections, destroy, lastError;
    private final Arena fixedArena = Arena.ofConfined();
    private final MemorySegment frame = fixedArena.allocate(104, 8);
    private final MemorySegment host = fixedArena.allocate(48, 8);
    private final MemorySegment settingsPacket = fixedArena.allocate(RenderSettings.WIRE_BYTES, 8);
    private final ByteBuffer settingsBuffer =
            settingsPacket.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    private final MemorySegment errorBuffer = fixedArena.allocate(4096);
    private final MemorySegment cpuBuffer = fixedArena.allocate(8192);
    private final MemorySegment sourceRequest = fixedArena.allocate(16, 8);
    private final ByteBuffer frameBuffer = frame.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    private Arena packetArena;
    private MemorySegment packetBuffer;
    private long handle;

    public NativeBridge(Path library) {
        try {
            // Keep executable code loaded for process lifetime, including quarantined GPU callbacks after device failure.
            var lookup = SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global());
            var abi = bind(lookup, "prime_abi_version", FunctionDescriptor.of(JAVA_INT));
            if ((int)abi.invokeExact() != Packets.ABI_VERSION)
                throw new IllegalStateException("Native ABI version mismatch");
            if (System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("windows"))
                vulkanPresent = bind(lookup, "prime_streamline_present",
                                     FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
            var create = bind(lookup, "prime_create", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
            submit = bind(lookup, "prime_submit",
                          FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG));
            renderDiagnostic = bind(lookup, "prime_render",
                                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG,
                                                          ADDRESS, JAVA_LONG));
            attachVulkan = bind(lookup, "prime_attach_vulkan",
                                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG));
            configure = bind(lookup, "prime_configure",
                             FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG));
            record
            = bind(lookup, "prime_record",
                   FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG,
                                         JAVA_LONG, JAVA_LONG, JAVA_LONG));
            gpuTime = bind(lookup, "prime_gpu_time", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
            cpuDiagnostics = bind(lookup, "prime_cpu_diagnostics",
                                  FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG));
            planSections =
                    bind(lookup, "prime_mc_plan",
                         FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
            acceptSections =
                    bind(lookup, "prime_mc_sections",
                         FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
            destroy = bind(lookup, "prime_destroy", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
            lastError = bind(lookup, "prime_last_error",
                             FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
            handle = (long)create.invokeExact(Packets.ABI_VERSION);
            if (handle == 0)
                throw new IllegalStateException(error());
        } catch (Throwable failure) {
            fixedArena.close();
            throw rethrow(failure);
        }
    }

    private static MethodHandle bind(SymbolLookup lookup, String name,
                                     FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(
                        () -> new UnsatisfiedLinkError("Missing native symbol " + name)),
                descriptor);
    }

    public void submit(byte[] packet) {
        checkOwner();
        var storage = packetStorage(packet.length);
        storage.copyFrom(MemorySegment.ofArray(packet));
        submit(storage);
    }

    /** One pixel copy into reusable native wire storage, borrowed only through submit's return. */
    public void submitTexture(long epoch, int id, int width, int height, byte[] rgba) {
        checkOwner();
        int pixels = Packets.texturePixelBytes(width, height);
        if (rgba.length != pixels)
            throw new IllegalArgumentException("Unexpected texture byte count");
        var storage = packetStorage(Packets.TEXTURE_HEADER_BYTES + pixels);
        Packets.writeTexture(storage.asByteBuffer(), epoch, id, width, height, rgba);
        submit(storage);
    }

    private MemorySegment packetStorage(int size) {
        if (size > Packets.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Packet exceeds 256 MiB");
        if (packetBuffer == null || packetBuffer.byteSize() < size) {
            long capacity = Math.min(
                    Packets.MAX_PACKET_BYTES,
                    Math.max(size, packetBuffer == null ? 65536L : packetBuffer.byteSize() * 2));
            var replacement = Arena.ofConfined();
            MemorySegment memory;
            try {
                memory = replacement.allocate(capacity, 8);
            } catch (Throwable failure) {
                replacement.close();
                throw failure;
            }
            if (packetArena != null)
                packetArena.close();
            packetArena = replacement;
            packetBuffer = memory;
        }
        return packetBuffer.asSlice(0, size);
    }

    /** Borrows an owner-thread native packet only for this call; Rust retains no source pointer. */
    public void submit(MemorySegment packet) {
        checkOwner();
        if (!packet.isNative())
            throw new IllegalArgumentException("Native packet storage is required");
        try {
            int status = (int)submit.invokeExact(handle, packet, packet.byteSize());
            if (status != 0)
                throw new IllegalStateException("prime_submit (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Exactly one synchronous Rust demand batch. Its view expires when sections() is called. */
    public MemorySegment requestSections(SourcePages events) {
        checkOwner();
        try {
            int status = (int)planSections.invokeExact(handle, events.table(), events.pageCount(),
                                                       sourceRequest);
            if (status != 0)
                throw new IllegalStateException("prime_mc_plan: " + error());
            long length = sourceRequest.get(JAVA_LONG, 8);
            return sourceRequest.get(ADDRESS, 0).reinterpret(length);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    /** Empty on publication; otherwise a borrowed color source/sample batch, answered through this same entry. */
    public MemorySegment sections(SourcePages response) {
        checkOwner();
        try {
            int status = (int)acceptSections.invokeExact(handle, response.table(),
                                                         response.pageCount(), sourceRequest);
            if (status != 0)
                throw new IllegalStateException("prime_mc_sections: " + error());
            return sourceRequest.get(ADDRESS, 0).reinterpret(sourceRequest.get(JAVA_LONG, 8));
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public void attachVulkan(long instance, long physicalDevice, long device, long queue,
                             long timeline, int queueFamily, boolean opacityMicromapEnabled,
                             boolean streamlineEnabled) {
        checkOwner();
        var descriptor = host.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        descriptor.putLong(instance)
                .putLong(physicalDevice)
                .putLong(device)
                .putLong(queue)
                .putLong(timeline)
                .putInt(queueFamily)
                .putInt((opacityMicromapEnabled ? 1 : 0) | (streamlineEnabled ? 2 : 0));
        try {
            int status = (int)attachVulkan.invokeExact(handle, host, 48L);
            if (status != 0)
                throw new IllegalStateException("prime_attach_vulkan (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public static boolean hasVulkanPresent() {
        return vulkanPresent != null;
    }

    /** Borrows the host present descriptor only for this call; returns the actual Vulkan result. */
    public static int presentVulkan(long queue, long presentInfo) {
        try {
            return (int)vulkanPresent.invokeExact(queue, presentInfo);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Settings are borrowed until return; mode switches require a submitted host encoder. */
    public void configure(RenderSettings settings, boolean offline, RenderSettings.View view) {
        checkOwner();
        settings.write(settingsBuffer, offline, view);
        try {
            int status = (int)configure.invokeExact(handle, settingsPacket,
                                                    (long)RenderSettings.WIRE_BYTES);
            if (status != 0)
                throw new IllegalStateException("prime_configure (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Reused wire frame, never retained by native code after record returns. */
    public ByteBuffer frameBuffer() {
        checkOwner();
        return frameBuffer;
    }

    public void record(long commandBuffer, long image, long imageView, long submitValue) {
        checkOwner();
        if (frameBuffer.position() != 104)
            throw new IllegalStateException("Frame packet is incomplete");
        try {
            int status = (int)record.invokeExact(handle, frame, 104L, commandBuffer, image,
                                                 imageView, submitValue);
            if (status != 0)
                throw new IllegalStateException("prime_record (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public long lastGpuTimeNanos() {
        checkOwner();
        try {
            return (long)gpuTime.invokeExact(handle);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Formats the last native CPU record only on demand; never waits for or reads back the GPU. */
    public String cpuDiagnostics() {
        checkOwner();
        try {
            long length = (long)cpuDiagnostics.invokeExact(handle, cpuBuffer, cpuBuffer.byteSize());
            if (length < 0)
                throw new IllegalStateException("prime_cpu_diagnostics: " + error());
            if (length >= cpuBuffer.byteSize())
                throw new IllegalStateException(
                        "CPU diagnostic record exceeded its bounded buffer");
            return new String(cpuBuffer.asSlice(0, length).toArray(JAVA_BYTE),
                              java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    /** Standalone diagnostics only; the production host renderer never calls the CPU readback ABI. */
    public void renderDiagnostic(byte[] packet, ByteBuffer output) {
        checkOwner();
        if (!output.isDirect())
            throw new IllegalArgumentException("Native output must be direct");
        if (packet.length != 104)
            throw new IllegalArgumentException("Frame packet requires 104 bytes");
        try {
            frame.copyFrom(MemorySegment.ofArray(packet));
            var rgba = MemorySegment.ofBuffer(output);
            int status = (int)renderDiagnostic.invokeExact(handle, frame, frame.byteSize(), rgba,
                                                           rgba.byteSize());
            if (status != 0)
                throw new IllegalStateException("prime_render (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private String error() throws Throwable {
        long length = (long)lastError.invokeExact(errorBuffer, errorBuffer.byteSize());
        return new String(errorBuffer.asSlice(0, Math.min(length, 4095)).toArray(JAVA_BYTE),
                          java.nio.charset.StandardCharsets.UTF_8);
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Native renderer called off its owner thread");
        if (handle == 0)
            throw new IllegalStateException("Native renderer is closed");
    }

    @Override
    public void close() {
        if (handle == 0)
            return;
        checkOwner();
        try {
            int status = (int)destroy.invokeExact(handle);
            if (status != 0)
                throw new IllegalStateException("prime_destroy (" + status + "): " + error());
            handle = 0;
            if (packetArena != null)
                packetArena.close();
            fixedArena.close();
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof Error error)
            throw error;
        if (failure instanceof RuntimeException runtime)
            return runtime;
        return new IllegalStateException(failure);
    }

    public static Path resolveLibrary() throws IOException {
        String override = System.getProperty("primept.native.path");
        if (override != null && !override.isBlank())
            return Path.of(override);
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        boolean windows = os.startsWith("windows");
        if (!windows && !os.startsWith("linux"))
            throw new IOException("Unsupported native operating system: " + os);
        String platform = windows ? "windows-x86_64" : "linux-x86_64";
        String file = windows ? "prime_engine.dll" : "libprime_engine.so";
        String arch = System.getProperty("os.arch");
        if (!(arch.equals("amd64") || arch.equals("x86_64")))
            throw new IOException("Unsupported native architecture: " + arch);
        return NativeRuntime.extract(
                Path.of(System.getProperty("java.io.tmpdir"), "primept-natives"), platform,
                windows ? NativeRuntime.WINDOWS_FILES : java.util.List.of(file),
                name
                -> NativeBridge.class.getResourceAsStream("/natives/" + platform + "/" + name));
    }
}
