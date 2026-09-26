package dev.primept;

import dev.primept.capture.InstanceCapture;
import dev.primept.capture.Packets;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

/** Real capture -> direct FFM -> native source transaction; no Minecraft, Vulkan or rendered FPS. */
public final class InstanceSubmitPerf {
    private static final int PROTOTYPES = 7;
    private static final float[] UV = {1, 1, 0, 0};

    private record Sample(String phase, int sample, boolean warmup, int changed, int bytes,
                          long observeNs, long sealNs, long nativeNs, long acknowledgeNs) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: InstanceSubmitPerf <dll> <objects> <warmup> <samples> <csv>");
        }
        Path library = Path.of(args[0]).toAbsolutePath().normalize();
        int objects = Integer.parseInt(args[1]), warmup = Integer.parseInt(args[2]);
        int samples = Integer.parseInt(args[3]);
        if (objects < PROTOTYPES || objects > 262144 || warmup < 0 || samples < 1)
            throw new IllegalArgumentException("Invalid resident count or sample count");
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        var rows = new ArrayList<Sample>();
        try (var bridge = new NativeBridge(library)) {
            run(bridge, objects, 1, "steady", 0, warmup, samples, rows);
            run(bridge, objects, 2, "pose_1pct", (objects + 99) / 100, warmup, samples, rows);
            run(bridge, objects, 3, "pose_100pct", objects, warmup, samples, rows);
        }
        // File I/O, hashing and formatting stay outside all measured batches.
        Files.createDirectories(output.getParent());
        var csv = new StringBuilder(
                "objects,prototypes,phase,sample,warmup,changed,wire_bytes,observe_ns,seal_ns,native_submit_ns,acknowledge_ns,total_ns\n");
        for (Sample row : rows) {
            csv.append(objects)
                    .append(',')
                    .append(PROTOTYPES)
                    .append(',')
                    .append(row.phase)
                    .append(',')
                    .append(row.sample)
                    .append(',')
                    .append(row.warmup ? 1 : 0)
                    .append(',')
                    .append(row.changed)
                    .append(',')
                    .append(row.bytes)
                    .append(',')
                    .append(row.observeNs)
                    .append(',')
                    .append(row.sealNs)
                    .append(',')
                    .append(row.nativeNs)
                    .append(',')
                    .append(row.acknowledgeNs)
                    .append(',')
                    .append(row.observeNs + row.sealNs + row.nativeNs + row.acknowledgeNs)
                    .append('\n');
        }
        Files.writeString(output, csv, StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library)));
        Files.writeString(
                Path.of(output + ".metadata.txt"),
                "scope=InstanceCapture + NativeBridge.submit(MemorySegment) + SourceScene; no Minecraft/GPU/FPS\n"
                        + "library=" + library + "\nsha256=" + sha +
                        "\njava=" + System.getProperty("java.runtime.version") +
                        "\nvm=" + System.getProperty("java.vm.name") + "\nobjects=" + objects +
                        "\nprototypes=" + PROTOTYPES + "\nwarmup_per_phase=" + warmup +
                        "\nsamples_per_phase=" + samples +
                        "\ninitial=one cold publication per fresh epoch; not a steady-state distribution\n"
                        + "observe=beginFrame + observe all resident handles + endFrame\n"
                        + "seal=sealDelta including continuous packet writes; no native call\n"
                        +
                        "native_submit=one synchronous direct-segment FFM call; zero for unchanged frames\n"
                        +
                        "acknowledge=clear accepted Java dirty journal; zero for unchanged frames\n",
                StandardCharsets.UTF_8);
        System.out.println("Wrote " + rows.size() + " CPU-only samples to " + output +
                           " (native SHA256 " + sha + ")");
    }

    private static void run(NativeBridge bridge, int objects, long epoch, String phase, int changed,
                            int warmup, int samples, ArrayList<Sample> rows) {
        bridge.submit(Packets.reset(epoch));
        try (var capture = new InstanceCapture(epoch)) {
            var prototypes = new InstanceCapture.Prototype[PROTOTYPES];
            for (int i = 0; i < prototypes.length; i++) {
                prototypes[i] = capture.prototype(4, 4, 24, 0, 12, 16, quad(i));
            }
            var instances = new InstanceCapture.Instance[objects];
            for (int i = 0; i < instances.length; i++)
                instances[i] = capture.instance();
            float[] still = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
            float[] moved = still.clone();
            rows.add(frame(bridge, capture, prototypes, instances, still, moved, "initial_" + phase,
                           0, false, 0, objects, true));
            for (int sample = 0; sample < warmup + samples; sample++) {
                // Alternate exact, finite affine values; all changed records differ from the last frame.
                moved[3] = (sample & 1) == 0 ? 0.125f : 0.25f;
                rows.add(frame(bridge, capture, prototypes, instances, still, moved, phase, sample,
                               sample < warmup, changed, changed, false));
            }
        }
    }

    private static Sample frame(NativeBridge bridge, InstanceCapture capture,
                                InstanceCapture.Prototype[] prototypes,
                                InstanceCapture.Instance[] instances, float[] still, float[] moved,
                                String phase, int sample, boolean warmup, int moving,
                                int expectedUpdates, boolean initial) {
        long begin = System.nanoTime();
        capture.beginFrame();
        for (int i = 0; i < instances.length; i++) {
            capture.observe(instances[i], prototypes[i % prototypes.length],
                            30_000_000.0 + (i & 255), 64 + ((i >>> 8) & 255), -30_000_000.0,
                            i < moving ? moved : still, 0, 0, -1, UV);
        }
        capture.endFrame();
        long observed = System.nanoTime();
        MemorySegment packet = capture.sealDelta();
        long sealed = System.nanoTime();
        long nativeNs = 0, acknowledgeNs = 0;
        if (packet != null) {
            long submitBegin = System.nanoTime();
            bridge.submit(packet);
            long submitted = System.nanoTime();
            capture.acknowledge();
            long acknowledged = System.nanoTime();
            nativeNs = submitted - submitBegin;
            acknowledgeNs = acknowledged - submitted;
        }
        InstanceCapture.Stats stats = capture.stats();
        int expectedBytes = expectedUpdates == 0 ? 0 : 48 + expectedUpdates * 128;
        if (initial)
            expectedBytes += PROTOTYPES * (56 + 4 * 24);
        if (stats.instanceUpserts() != expectedUpdates || stats.instanceRemoves() != 0 ||
            stats.prototypeUpserts() != (initial ? PROTOTYPES : 0) ||
            stats.prototypeRemoves() != 0 || stats.bytes() != expectedBytes ||
            stats.activeInstances() != instances.length || (packet == null) != (expectedBytes == 0))
            throw new AssertionError("Unexpected update stream for " + phase + ": " + stats);
        return new Sample(phase, sample, warmup, expectedUpdates, expectedBytes, observed - begin,
                          sealed - observed, nativeNs, acknowledgeNs);
    }

    private static ByteBuffer quad(int variant) {
        var bytes = ByteBuffer.allocate(4 * 24).order(ByteOrder.LITTLE_ENDIAN);
        float size = 0.25f + variant * 0.03125f;
        for (float[] p : new float[][] {{0, 0}, {size, 0}, {size, size}, {0, size}}) {
            bytes.putFloat(p[0]).putFloat(p[1]).putFloat(0);
            bytes.putInt(-1).putFloat(p[0]).putFloat(p[1]);
        }
        return bytes.flip();
    }
}
