// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

final class PrimaryTransparentBranchGpuTest extends GpuShaderTest {
    private static final String SHADER = "primary_transparent_branch.comp.spv";

    @Test
    void stbnSelectsOneLightingBranchAndPreservesPairedEstimatorMean() throws Exception {
        byte[] table;
        try (var resource = getClass().getResourceAsStream("/prime/stbn/realtime_128x128x64x3.rg16ui")) {
            assertNotNull(resource);
            table = resource.readAllBytes();
        }
        int count = 128 * 128 * 64;
        ByteBuffer input = input(count, 0, count - 32, -1, table.length);
        input.put(table).flip();
        ByteBuffer output = runner.dispatch(SHADER, input, count * 32, count);
        BitSet addresses = new BitSet(count);
        int transmissionCount = 0;
        double[] mean = new double[3];
        for (int i = 0; i < count; ++i) {
            int offset = i * 32;
            float t = output.getFloat(offset), r = output.getFloat(offset + 4);
            assertTrue((t == 2 && r == 0) || (t == 0 && r == 2));
            int address = output.getInt(offset + 8);
            assertTrue(address >= 0 && address < count);
            addresses.set(address);
            assertEquals(0, output.getInt(offset + 12), "Selection and first NEE must have distinct STBN addresses");
            transmissionCount += output.getInt(offset + 28);
            for (int c = 0; c < 3; c++) mean[c] += output.getFloat(offset + 16 + c * 4);
        }
        assertEquals(count, addresses.cardinality());
        assertEquals(0.5, (double) transmissionCount / count, 0.001);
        double[] expected = {6.25, 5.5, 15};
        for (int c = 0; c < 3; c++) assertEquals(expected[c], mean[c] / count, 0.02);
    }

    @Test
    void everyQuantizedChoiceHandlesInvalidEventsAndTirWithoutEnergyLoss() throws Exception {
        int count = 65536 * 4;
        ByteBuffer output = runner.dispatch(SHADER, input(count, 1, 0, 0, 0).flip(), count * 16, count);
        for (int i = 0; i < count; i++) {
            boolean t = (i & 0x10000) != 0, r = (i & 0x20000) != 0;
            boolean choose = (i & 0x8000) == 0;
            float expectedT = t && r ? (choose ? 2 : 0) : t ? 1 : 0;
            float expectedR = t && r ? (choose ? 0 : 2) : r ? 1 : 0;
            assertEquals(expectedT, output.getFloat(i * 16));
            assertEquals(expectedR, output.getFloat(i * 16 + 4));
            assertEquals(choose ? 1 : 0, output.getInt(i * 16 + 8));
        }
    }

    @Test
    void missingGuideUpdatesOnlyTheObservedDistanceAndKeepsRadianceAndIdentity() throws Exception {
        float[] distances = {0, 2.5f, 65000, 100000, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        int count = distances.length * 8;
        ByteBuffer input = input(count, 2, 0, 0, distances.length * 4);
        for (float distance : distances) input.putFloat(distance);
        input.flip();
        ByteBuffer output = runner.dispatch(SHADER, input, count * 16, count);
        for (int i = 0; i < count; i++) {
            float d = distances[i >> 3];
            float expected = (i & 4) == 0 || !Float.isFinite(d) ? 65504 : Math.clamp(d, 0, 65504);
            boolean hasGuide = (i & 1) != 0, diffuse = (i & 2) != 0;
            assertEquals(expected, output.getFloat(i * 16));
            assertEquals(hasGuide && diffuse ? expected : 17, output.getFloat(i * 16 + 4));
            assertEquals(hasGuide && !diffuse ? expected : 19, output.getFloat(i * 16 + 8));
            assertEquals(1, output.getInt(i * 16 + 12));
        }
    }

    @Test
    void foldedTransportAndPersistentGuideSlotsStayDisjointThroughFourK() throws Exception {
        for (int pixels : new int[] {1, 127, 1920 * 1080, 3840 * 2160}) {
            int[] paths = {0, pixels - 1, pixels, 2 * pixels - 1};
            ByteBuffer input = input(paths.length, 3, pixels, 0, paths.length * 4);
            for (int path : paths) input.putInt(path);
            input.flip();
            ByteBuffer output = runner.dispatch(SHADER, input, paths.length * 48, paths.length);
            for (int i = 0; i < paths.length; i++) {
                int path = paths[i], offset = i * 48;
                assertEquals(path % pixels, output.getInt(offset));
                assertEquals(path, output.getInt(offset + 4));
                int start = output.getInt(offset + 8), owner = output.getInt(offset + 12);
                int words = output.getInt(offset + 16), surfaceWords = output.getInt(offset + 20);
                assertEquals(8 * pixels + 25 * path, start);
                assertEquals(start + 10, owner);
                assertEquals(17, words);
                assertTrue(owner + 7 <= start + surfaceWords);
                assertTrue(start + words <= output.getInt(offset + 24));
                int opposite = 8 * pixels + 25 * (path < pixels ? path + pixels : path - pixels);
                assertTrue(start + words <= opposite || opposite + surfaceWords <= start);
                int phase = output.getInt(offset + 32), guidePhase = output.getInt(offset + 36);
                assertEquals(58 * pixels + 12 * (path % pixels), guidePhase);
                assertEquals(guidePhase + (path >= pixels ? 6 : 0), phase);
                assertTrue(phase + 6 <= output.getInt(offset + 40));
            }
        }
    }

    @Test
    void detachedGuidesCannotOverwriteOtherPixelsLiveReceiverNormals() throws Exception {
        int pixels = 4, words = pixels * 70 + 4;
        ByteBuffer output = runner.dispatch(SHADER, input(1, 4, pixels, 0, 0).flip(), words * 4, 1);
        for (int path = 0; path < 2 * pixels; ++path) {
            int pixel = path % pixels, branch = path / pixels;
            int base = 58 * pixels + 12 * pixel + 6 * branch + 3;
            for (int lane = 0; lane < 3; ++lane) {
                int expected = pixel == 1 || pixel == 2 ? 0 : 100 + path * 3 + lane;
                assertEquals(expected, output.getInt((base + lane) * 4));
            }
        }
        for (int word = 0; word < 58 * pixels; ++word) assertEquals(0xdeadbeef, output.getInt(word * 4));
        for (int word = 70 * pixels; word < words; ++word) assertEquals(0xdeadbeef, output.getInt(word * 4));
    }

    private static ByteBuffer input(int count, int mode, int frame, int epoch, int extraBytes) {
        return ByteBuffer.allocateDirect(16 + extraBytes).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(count).putInt(mode).putInt(frame).putInt(epoch);
    }
}
