package dev.primept;

import dev.primept.settings.RenderSettings;
import dev.primept.capture.Packets;
import dev.primept.abi.PrimeAbi;
import static dev.primept.abi.PrimeAbi.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import static java.lang.foreign.ValueLayout.*;

/** One render-thread owner. Native code consumes borrowed input before return and owns all retained data. */
public final class NativeBridge implements AutoCloseable {
    private static volatile MethodHandle vulkanPresent;
    private static volatile MethodHandle presentationStats;
    private final Thread owner = Thread.currentThread();
    private final MethodHandle reset, textures, retireTextures, dynamic, instances,
            renderDiagnostic, attachVulkan, configure, record, submissionAccepted, displayOutput,
            presentHdr, prepareFrameGeneration, prepareResources, gpuTime, cpuDiagnostics,
            planSections, acceptSections, prepareMcResources, acceptColors, acceptBiomes, destroy,
            lastError, diagnosticsConfigure, diagnosticsFrame, diagnosticsClock, diagnosticsRead;
    private final Arena fixedArena = Arena.ofConfined();
    private final MemorySegment frame = fixedArena.allocate(PrimeFrame.LAYOUT);
    private final MemorySegment host = fixedArena.allocate(PrimeVulkanHost.LAYOUT);
    private final MemorySegment settingsPacket = fixedArena.allocate(RenderSettings.WIRE_BYTES, 8);
    private final ByteBuffer settingsBuffer =
            settingsPacket.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    private final MemorySegment target = fixedArena.allocate(PrimeRecordTarget.LAYOUT);
    private final MemorySegment prepare = fixedArena.allocate(PrimePrepareResources.LAYOUT);
    private final MemorySegment displayPacket = fixedArena.allocate(PrimeDisplayOutput.LAYOUT);
    private final MemorySegment hdrTarget = fixedArena.allocate(PrimeHdrTarget.LAYOUT);
    private final MemorySegment resetInput = fixedArena.allocate(PrimeReset.LAYOUT);
    private final MemorySegment errorBuffer = fixedArena.allocate(4096);
    private final MemorySegment cpuBuffer = fixedArena.allocate(8192);
    private final MemorySegment sourceRequest = fixedArena.allocate(PrimeMcRequests.LAYOUT);
    private final ByteBuffer frameBuffer = frame.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    private Arena packetArena;
    private MemorySegment packetBuffer;
    private long handle;

    public NativeBridge(Path library) {
        try {
            // Keep executable code loaded for process lifetime, including quarantined GPU callbacks after device failure.
            var lookup = SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global());
            var abi = PrimeAbi.bind(lookup, "prime_abi_version");
            if ((int)abi.invokeExact() != PRIME_ABI_VERSION)
                throw new IllegalStateException("Native ABI version mismatch");
            if (System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("windows")) {
                vulkanPresent = PrimeAbi.bind(lookup, "prime_streamline_present");
            }
            bindPresentationStats(lookup);
            var create = PrimeAbi.bind(lookup, "prime_create");
            reset = PrimeAbi.bind(lookup, "prime_reset");
            textures = PrimeAbi.bind(lookup, "prime_textures");
            retireTextures = PrimeAbi.bind(lookup, "prime_retire_textures");
            dynamic = PrimeAbi.bind(lookup, "prime_dynamic");
            instances = PrimeAbi.bind(lookup, "prime_instances");
            renderDiagnostic = PrimeAbi.bind(lookup, "prime_render");
            attachVulkan = PrimeAbi.bind(lookup, "prime_attach_vulkan");
            configure = PrimeAbi.bind(lookup, "prime_configure");
            record
            = PrimeAbi.bind(lookup, "prime_record");
            submissionAccepted = PrimeAbi.bind(lookup, "prime_submission_accepted");
            displayOutput = PrimeAbi.bind(lookup, "prime_display_output");
            presentHdr = PrimeAbi.bind(lookup, "prime_present_hdr");
            prepareFrameGeneration = PrimeAbi.bind(lookup, "prime_prepare_frame_generation");
            prepareResources = PrimeAbi.bind(lookup, "prime_prepare_resources");
            gpuTime = PrimeAbi.bind(lookup, "prime_gpu_time");
            cpuDiagnostics = PrimeAbi.bind(lookup, "prime_cpu_diagnostics");
            planSections = PrimeAbi.bind(lookup, "prime_mc_plan");
            acceptSections = PrimeAbi.bind(lookup, "prime_mc_sections");
            prepareMcResources = PrimeAbi.bind(lookup, "prime_mc_resources");
            acceptColors = PrimeAbi.bind(lookup, "prime_mc_colors");
            acceptBiomes = PrimeAbi.bind(lookup, "prime_mc_biomes");
            destroy = PrimeAbi.bind(lookup, "prime_destroy");
            lastError = PrimeAbi.bind(lookup, "prime_last_error");
            diagnosticsConfigure = PrimeAbi.bind(lookup, "prime_diagnostics_configure");
            diagnosticsFrame = PrimeAbi.bind(lookup, "prime_diagnostics_frame");
            diagnosticsClock = PrimeAbi.bind(lookup, "prime_diagnostics_clock");
            diagnosticsRead = PrimeAbi.bind(lookup, "prime_diagnostics_read");
            handle = (long)create.invokeExact(PRIME_ABI_VERSION);
            if (handle == 0)
                throw new IllegalStateException(error());
            Diagnostics.attached(this);
        } catch (Throwable failure) {
            fixedArena.close();
            throw rethrow(failure);
        }
    }

    public static void header(MemorySegment header, long size) {
        PrimeHeader.struct_size(header, Math.toIntExact(size));
        PrimeHeader.abi_version(header, PRIME_ABI_VERSION);
    }
    private void call(MethodHandle method, String name, MemorySegment input) {
        checkOwner();
        if (!input.isNative())
            throw new IllegalArgumentException("Native input storage is required");
        try (var span = Diagnostics.span(name)) {
            try {
                int status = (int)method.invokeExact(handle, input);
                if (status != 0) {
                    span.fail();
                    throw new IllegalStateException(name + " (" + status + "): " + error());
                }
            } catch (Throwable failure) {
                span.fail();
                throw rethrow(failure);
            }
        }
    }
    public void reset(long epoch) {
        checkOwner();
        header(PrimeReset.header(resetInput), PrimeReset.SIZE);
        PrimeReset.epoch(resetInput, epoch);
        call(reset, "prime_reset", resetInput);
    }
    public void submitDynamic(MemorySegment input) {
        call(dynamic, "prime_dynamic", input);
    }
    public void submitInstances(MemorySegment input) {
        call(instances, "prime_instances", input);
    }
    /** One copy of authored pixels; typed descriptors and bytes share reusable native owner storage. */
    public void submitTexture(long epoch, int id, int width, int height, byte[] rgba) {
        submitTextures(epoch, List.of(new TextureSource(id, width, height, rgba)));
    }

    /** Authored pixels are borrowed synchronously; descriptors share one bounded native transaction. */
    public record TextureSource(int id, int width, int height, byte[] rgba) {}

    public void submitTextures(long epoch, List<TextureSource> sources) {
        checkOwner();
        int size = textureBatchBytes(sources);
        var storage = packetStorage(size);
        call(textures, "prime_textures", encodeTextures(storage, epoch, sources));
    }

    static int textureBatchBytes(List<TextureSource> sources) {
        long size = PrimeTextureBatch.SIZE;
        for (var source : sources) {
            int pixels = Packets.texturePixelBytes(source.width(), source.height());
            if (source.rgba().length != pixels)
                throw new IllegalArgumentException("Unexpected texture byte count");
            size += PrimeTextureSource.SIZE + pixels;
            if (size > Packets.MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Texture batch exceeds 256 MiB");
        }
        return Math.toIntExact(size);
    }

    /** Receives the validated exact storage range; no source pointer escapes the synchronous submit. */
    static MemorySegment encodeTextures(MemorySegment storage, long epoch,
                                        List<TextureSource> sources) {
        var batch = storage.asSlice(0, PrimeTextureBatch.SIZE);
        long descriptors = PrimeTextureBatch.SIZE;
        long payloadAt = descriptors + sources.size() * PrimeTextureSource.SIZE;
        header(PrimeTextureBatch.header(batch), PrimeTextureBatch.SIZE);
        PrimeTextureBatch.epoch(batch, epoch);
        PrimeTextureBatch.textures(
                batch, storage.asSlice(descriptors, sources.size() * PrimeTextureSource.SIZE));
        PrimeTextureBatch.count(batch, sources.size());
        for (int i = 0; i < sources.size(); ++i) {
            var input = sources.get(i);
            var source = storage.asSlice(descriptors + i * PrimeTextureSource.SIZE,
                                         PrimeTextureSource.SIZE);
            var payload = storage.asSlice(payloadAt, input.rgba().length);
            payloadAt += input.rgba().length;
            payload.copyFrom(MemorySegment.ofArray(input.rgba()));
            PrimeTextureSource.id(source, input.id());
            PrimeTextureSource.width(source, input.width());
            PrimeTextureSource.height(source, input.height());
            PrimeTextureSource.reserved(source, 0);
            PrimeByteSpan.data(PrimeTextureSource.rgba(source), payload);
            PrimeByteSpan.count(PrimeTextureSource.rgba(source), input.rgba().length);
        }
        return batch;
    }
    public void retireTextures(long epoch, int[] ids) {
        checkOwner();
        var storage = packetStorage(
                Math.toIntExact(PrimeTextureRetire.SIZE + Math.multiplyExact((long)ids.length, 4)));
        var batch = storage.asSlice(0, PrimeTextureRetire.SIZE);
        var values = storage.asSlice(PrimeTextureRetire.SIZE);
        for (int i = 0; i < ids.length; i++)
            values.setAtIndex(JAVA_INT, i, ids[i]);
        header(PrimeTextureRetire.header(batch), PrimeTextureRetire.SIZE);
        PrimeTextureRetire.epoch(batch, epoch);
        PrimeU32Span.data(PrimeTextureRetire.ids(batch), values);
        PrimeU32Span.count(PrimeTextureRetire.ids(batch), ids.length);
        call(retireTextures, "prime_retire_textures", batch);
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

    /** Borrowed typed requests expire at the next Minecraft source call. */
    public MemorySegment requestSections(MemorySegment input) {
        return mc(planSections, input, "prime_mc_plan");
    }
    public MemorySegment sections(MemorySegment input) {
        return mc(acceptSections, input, "prime_mc_sections");
    }
    public MemorySegment colors(MemorySegment input) {
        return mc(acceptColors, input, "prime_mc_colors");
    }
    public MemorySegment biomes(MemorySegment input) {
        return mc(acceptBiomes, input, "prime_mc_biomes");
    }
    public void resources(MemorySegment input) {
        call(prepareMcResources, "prime_mc_resources", input);
    }
    private MemorySegment mc(MethodHandle operation, MemorySegment input, String name) {
        checkOwner();
        if (!input.isNative())
            throw new IllegalArgumentException("Native input storage is required");
        try (var span = Diagnostics.span(name)) {
            try {
                int status = (int)operation.invokeExact(handle, input, sourceRequest);
                if (status != 0) {
                    span.fail();
                    throw new IllegalStateException(name + ": " + error());
                }
                return sourceRequest;
            } catch (Throwable failure) {
                span.fail();
                throw rethrow(failure);
            }
        }
    }

    public void attachVulkan(long instance, long physicalDevice, long device, long queue,
                             long timeline, int queueFamily, boolean opacityMicromapEnabled,
                             boolean streamlineEnabled) {
        checkOwner();
        header(PrimeVulkanHost.header(host), PrimeVulkanHost.SIZE);
        PrimeVulkanHost.instance(host, instance);
        PrimeVulkanHost.physical_device(host, physicalDevice);
        PrimeVulkanHost.device(host, device);
        PrimeVulkanHost.queue(host, queue);
        PrimeVulkanHost.timeline(host, timeline);
        PrimeVulkanHost.queue_family(host, queueFamily);
        PrimeVulkanHost.capabilities(host, (opacityMicromapEnabled ? PRIME_HOST_OMM : 0) |
                                                   (streamlineEnabled ? PRIME_HOST_STREAMLINE : 0));
        call(attachVulkan, "prime_attach_vulkan", host);
    }

    public static boolean hasVulkanPresent() {
        return vulkanPresent != null;
    }

    static void bindPresentationStats(SymbolLookup lookup) {
        presentationStats = PrimeAbi.bind(lookup, "prime_streamline_present_stats");
    }

    /** 0 copied, 1 busy with output unchanged, -1 unavailable; no SDK query or GPU wait. */
    public static int presentationStatistics(MemorySegment output) {
        MethodHandle query = presentationStats;
        if (query == null)
            return -1;
        header(PrimePresentationStats.header(output), PrimePresentationStats.SIZE);
        try {
            return (int)query.invokeExact(output);
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    /** Borrows the host descriptor for one call; returns SDK status merged with API errors. */
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
            int status = (int)configure.invokeExact(handle, settingsPacket);
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
        if (frameBuffer.position() != PrimeFrame.SIZE)
            throw new IllegalStateException("Frame packet is incomplete");
        try {
            header(PrimeRecordTarget.header(target), PrimeRecordTarget.SIZE);
            PrimeRecordTarget.command(target, commandBuffer);
            PrimeRecordTarget.image(target, image);
            PrimeRecordTarget.view(target, imageView);
            PrimeRecordTarget.serial(target, submitValue);
            int status = (int)record.invokeExact(handle, frame, target);
            if (status != 0)
                throw new IllegalStateException("prime_record (" + status + "): " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public void prepareResources(long command, long serial) {
        checkOwner();
        header(PrimePrepareResources.header(prepare), PrimePrepareResources.SIZE);
        PrimePrepareResources.command(prepare, command);
        PrimePrepareResources.serial(prepare, serial);
        call(prepareResources, "prime_prepare_resources", prepare);
    }

    /** Actual queue acceptance advances temporal identities; completion remains the timeline's job. */
    public void submissionAccepted(long serial) {
        checkOwner();
        try {
            int status = (int)submissionAccepted.invokeExact(handle, serial);
            if (status != 0)
                throw new IllegalStateException("prime_submission_accepted: " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    public void displayOutput(boolean active, float peak, float systemWhite) {
        header(PrimeDisplayOutput.header(displayPacket), PrimeDisplayOutput.SIZE);
        PrimeDisplayOutput.active(displayPacket, active ? 1 : 0);
        PrimeDisplayOutput.peak_nits(displayPacket, peak);
        PrimeDisplayOutput.system_white_nits(displayPacket, systemWhite);
        PrimeDisplayOutput.reserved(displayPacket, 0);
        call(displayOutput, "prime_display_output", displayPacket);
    }
    public void presentHdr(long command, long uiImage, long uiView, long outputImage,
                           long outputView, long serial, int width, int height) {
        presentationTarget(command, uiImage, uiView, outputImage, outputView, serial, width,
                           height);
        call(presentHdr, "prime_present_hdr", hdrTarget);
    }
    public boolean prepareFrameGeneration(long command, long uiImage, long uiView, long outputImage,
                                          long outputView, long serial, int width, int height,
                                          int backBufferCount, int backBufferFormat) {
        presentationTarget(command, uiImage, uiView, outputImage, outputView, serial, width,
                           height);
        try {
            int status = (int)prepareFrameGeneration.invokeExact(handle, hdrTarget, backBufferCount,
                                                                 backBufferFormat);
            if (status < 0)
                throw new IllegalStateException("prime_prepare_frame_generation: " + error());
            return status == 0;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    private void presentationTarget(long command, long uiImage, long uiView, long outputImage,
                                    long outputView, long serial, int width, int height) {
        checkOwner();
        header(PrimeHdrTarget.header(hdrTarget), PrimeHdrTarget.SIZE);
        PrimeHdrTarget.command(hdrTarget, command);
        PrimeHdrTarget.ui_image(hdrTarget, uiImage);
        PrimeHdrTarget.ui_view(hdrTarget, uiView);
        PrimeHdrTarget.output_image(hdrTarget, outputImage);
        PrimeHdrTarget.output_view(hdrTarget, outputView);
        PrimeHdrTarget.serial(hdrTarget, serial);
        PrimeHdrTarget.width(hdrTarget, width);
        PrimeHdrTarget.height(hdrTarget, height);
    }

    public long lastGpuTimeNanos() {
        checkOwner();
        try {
            return (long)gpuTime.invokeExact(handle);
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }

    public void diagnosticsConfigure(int flags) {
        checkOwner();
        try {
            int status = (int)diagnosticsConfigure.invokeExact(handle, flags);
            if (status != 0)
                throw new IllegalStateException("prime_diagnostics_configure: " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    public void diagnosticsFrame(long frameId) {
        checkOwner();
        try {
            int status = (int)diagnosticsFrame.invokeExact(handle, frameId);
            if (status != 0)
                throw new IllegalStateException("prime_diagnostics_frame: " + error());
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    public long diagnosticsClock() {
        checkOwner();
        try {
            long value = (long)diagnosticsClock.invokeExact(handle);
            if (value < 0)
                throw new IllegalStateException("prime_diagnostics_clock: " + error());
            return value;
        } catch (Throwable failure) {
            throw rethrow(failure);
        }
    }
    /** Length query caches one immutable chunk; copying that chunk consumes it. */
    public String diagnosticsRead() {
        return diagnosticsRead(Diagnostics.captureSession());
    }
    /** Transport timings stay in Java and cannot recursively create another native chunk. */
    String diagnosticsRead(PerformanceCapture session) {
        checkOwner();
        try (var read = Diagnostics.span(session, "diag.read")) {
            try {
                long length;
                try (var drain = Diagnostics.span(session, "diag.drain")) {
                    drain.fail();
                    length = (long)diagnosticsRead.invokeExact(handle, MemorySegment.NULL, 0L);
                    if (length < 0 || length > Integer.MAX_VALUE)
                        throw new IllegalStateException(
                                "Native diagnostic chunk exceeds Java string limit");
                    drain.count("bytes", length);
                    drain.succeed();
                }
                read.count("bytes", length);
                if (length == 0)
                    return "";
                Arena storage;
                MemorySegment output;
                try (var allocate = Diagnostics.span(session, "diag.alloc")) {
                    allocate.fail();
                    storage = Arena.ofConfined();
                    try {
                        output = storage.allocate(length + 1);
                    } catch (Throwable failure) {
                        storage.close();
                        throw failure;
                    }
                    allocate.count("bytes", length + 1);
                    allocate.succeed();
                }
                try (storage) {
                    try (var copy = Diagnostics.span(session, "diag.copy")) {
                        copy.fail();
                        long copied = (long)diagnosticsRead.invokeExact(handle, output,
                                                                        output.byteSize());
                        if (copied != length)
                            throw new IllegalStateException(
                                    "Native diagnostic chunk changed during copy");
                        copy.count("bytes", copied);
                        copy.succeed();
                    }
                    byte[] bytes;
                    try (var array = Diagnostics.span(session, "diag.array")) {
                        array.fail();
                        bytes = output.asSlice(0, length).toArray(JAVA_BYTE);
                        array.count("bytes", bytes.length);
                        array.succeed();
                    }
                    try (var utf8 = Diagnostics.span(session, "diag.utf8")) {
                        utf8.fail();
                        String result = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        utf8.count("chars", result.length());
                        utf8.succeed();
                        return result;
                    }
                }
            } catch (Throwable failure) {
                read.fail();
                throw rethrow(failure);
            }
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
        if (packet.length != PrimeFrame.SIZE)
            throw new IllegalArgumentException("Frame structure has an unexpected size");
        try {
            frame.copyFrom(MemorySegment.ofArray(packet));
            var rgba = MemorySegment.ofBuffer(output);
            int status = (int)renderDiagnostic.invokeExact(handle, frame, rgba, rgba.byteSize());
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
            Diagnostics.detaching(this);
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
