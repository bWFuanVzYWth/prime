package dev.primept.capture;

import dev.primept.NativeBridge;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Set;
import static dev.primept.abi.PrimeAbi.*;

/** Actual Java typed publications to a CPU-only native session, including a rejected late texture batch. */
public final class PublicationCpuSmoke {
    private PublicationCpuSmoke() {}
    public static void run() throws Exception {
        try (var bridge =
                     new NativeBridge(Path.of(System.getProperty("primept.smoke.nativeLibrary")))) {
            bridge.reset(17);
            modelBytes(bridge);
            textureBatches(bridge);
        } finally {
            ModelCapture.close();
            DynamicTextures.releaseSources();
        }
        System.out.println(
                "PRIME_PT_PUBLICATION_CPU_OK: whole instance batch bytes; bounded multi-texture FFM; failed batch acknowledgements; retry");
    }
    private static void modelBytes(NativeBridge bridge) throws Exception {
        ModelCapture.begin(17);
        var owner = (InstanceCapture)field(ModelCapture.class, "instances").get(null);
        var bytes = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] point : new float[][] {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}})
            bytes.putFloat(point[0])
                    .putFloat(point[1])
                    .putFloat(point[2])
                    .putInt(-1)
                    .putFloat(point[0])
                    .putFloat(point[1]);
        bytes.flip();
        var prototype = owner.prototype(4, 4, 24, 0, 12, 16, bytes);
        owner.observe(owner.instance(), prototype, 0, 0, 0,
                      new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}, 0, 0, -1,
                      new float[] {1, 1, 0, 0});
        ModelCapture.end();
        ModelCapture.submit(bridge);
        int expected = (int)(PrimeInstanceBatch.SIZE + PrimePrototypeSource.SIZE +
                             PrimeMeshSpan.SIZE + 96 + PrimeInstanceSource.SIZE);
        check(ModelCapture.stats().deltaBytes() == expected && owner.stats().bytes() == expected,
              "Instance profile includes every descriptor and payload, not the 88 B root");
        ModelCapture.begin(17);
        ModelCapture.end();
        ModelCapture.submit(bridge);
        check(ModelCapture.stats().deltaBytes() == PrimeInstanceBatch.SIZE + PrimeRemoval.SIZE,
              "Removal profile retains whole transaction size");
        ModelCapture.begin(17);
        ModelCapture.end();
        ModelCapture.submit(bridge);
        check(ModelCapture.stats().deltaBytes() == 0, "Stable empty frame publishes zero bytes");
        ModelCapture.close();
    }
    @SuppressWarnings("unchecked")
    private static void textureBatches(NativeBridge bridge) throws Exception {
        DynamicTextures.releaseSources();
        var needed = (Set<Object>)field(DynamicTextures.class, "NEEDED").get(null);
        var type = Class.forName(DynamicTextures.class.getName() + "$Texture");
        var constructor =
                type.getDeclaredConstructor(int.class, int.class, int.class, byte[].class);
        constructor.setAccessible(true);
        Object first = constructor.newInstance(2, 1, 1, new byte[] {1, 2, 3, -1});
        Object second = constructor.newInstance(3, 1, 1, new byte[] {4, 5, 6, -1});
        Object third = constructor.newInstance(4, 1, 1, new byte[] {7, 8, 9, -1});
        Object invalid = constructor.newInstance(0, 1, 1, new byte[] {10, 11, 12, -1});
        needed.add(first);
        needed.add(second);
        needed.add(third);
        needed.add(invalid);
        int twoPerBatch = (int)(PrimeTextureBatch.SIZE + 2 * (PrimeTextureSource.SIZE + 4));
        boolean rejected = false;
        try {
            DynamicTextures.submit(17, bridge, twoPerBatch);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        var sentEpoch = field(type, "sentEpoch");
        check(rejected && sentEpoch.getLong(first) == 17 && sentEpoch.getLong(second) == 17 &&
                      sentEpoch.getLong(third) == 0 && sentEpoch.getLong(invalid) == 0,
              "Only the completed batch acknowledges its members; native late failure is atomic");
        needed.remove(invalid);
        Object fourth = constructor.newInstance(5, 1, 1, new byte[] {10, 11, 12, -1});
        needed.add(fourth);
        DynamicTextures.submit(17, bridge, twoPerBatch);
        check(sentEpoch.getLong(third) == 17 && sentEpoch.getLong(fourth) == 17,
              "Failed batch owners remain valid and retry successfully");
        DynamicTextures.submit(17, bridge, twoPerBatch);
        DynamicTextures.releaseSources();
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
