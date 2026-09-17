// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.terrain.CompiledClusterLights;
import dev.prime.render.terrain.CpuWorldLightTree;
import dev.prime.render.terrain.WorldLightTreeInput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gpu-shader")
final class LightTreeReplayGpuTest {
    private static final int QUERIES = 4096;

    @Test
    void compiledRecordsMatchLeafStreamSelectionAndReversePdfBitForBit() throws Exception {
        try (ShaderComputeRunner gpu = ShaderComputeRunner.openAddressed()) {
            for (int count : new int[] {1, 2, 19, 64}) {
                for (int origin : new int[] {0, 65536}) {
                    Fixture fixture = fixture(count, origin);
                    ByteBuffer result = gpu.dispatchAddressed(
                            "light_tree_replay.comp.spv", fixture.data(), fixture.pointers(),
                            QUERIES * 32, QUERIES);
                    for (int query = 0; query < QUERIES; query++) {
                        int offset = query * 32;
                        assertEquals(0, result.getInt(offset),
                                "lights=" + count + " origin=" + origin + " query=" + query
                                        + " expected=" + result.getInt(offset + 4)
                                        + " actual=" + result.getInt(offset + 8)
                                        + " expected PDF bits=" + result.getInt(offset + 16)
                                        + " actual PDF bits=" + result.getInt(offset + 20));
                    }
                }
            }
        }
    }

    private static Fixture fixture(int count, int origin) {
        var clusters = new ArrayList<WorldLightTreeInput.Entry>();
        for (int index = 0; index < count; index++) {
            float power = (float) Math.scalb(1.0, (index % 25) * 3 - 36);
            int direction = switch (index % 4) {
                case 0 -> 0xc0000000;
                case 1 -> 512 | (512 << 10);
                case 2 -> 0x40000000 | 512 | (512 << 10) | (511 << 20);
                default -> 0x80000000 | 0x15555555;
            };
            clusters.add(new WorldLightTreeInput.Entry(
                    index, index * 2, 0, 0,
                    new CompiledClusterLights.Summary(
                            1, 0, 0, 0, 2, 3, 1, power, direction)));
        }
        CpuWorldLightTree.Result tree = CpuWorldLightTree.build(
                WorldLightTreeInput.capture(clusters, origin, 0, 0));
        int[] packed = tree.pack();
        int nodes = 32;
        int leaves = nodes + Math.toIntExact(tree.leafByteOffset());
        int emitters = align(nodes + packed.length * 4);
        int sections = align(emitters + count * ShaderAbi.LIGHT_EMITTER_SIZE);
        int headers = align(sections + count * ShaderAbi.SECTION_RECORD_SIZE);
        int paths = align(headers + count * ShaderAbi.SECTION_LIGHT_HEADER_SIZE);
        int queries = align(paths + count * 4);
        ByteBuffer data = ByteBuffer.allocateDirect(queries + QUERIES * 32).order(ByteOrder.LITTLE_ENDIAN);
        int[] header = {nodes, leaves, emitters, sections, paths, queries, count, QUERIES};
        for (int index = 0; index < header.length; index++) data.putInt(index * 4, header[index]);
        for (int index = 0; index < packed.length; index++) data.putInt(nodes + index * 4, packed[index]);
        var pointers = new ArrayList<Integer>();
        for (int index = 0; index < count; index++) {
            int path = tree.lightPath(index);
            int node = 0;
            for (int level = 0; level < path >>> 27; level++) {
                node = packed[node * 8 + 5] + ((path >>> level) & 1);
            }
            data.putInt(paths + index * 4, path);
            int section = sections + index * ShaderAbi.SECTION_RECORD_SIZE;
            int localHeader = headers + index * ShaderAbi.SECTION_LIGHT_HEADER_SIZE;
            // Each synthetic cluster's root uses actual CPU-packed centroid/power/direction.
            data.putLong(section + 24, localHeader);
            pointers.add(section + 24);
            data.putFloat(section + 48, index * 32.0F - origin);
            data.putFloat(section + 52, index % 3 - 1.0F);
            data.putFloat(section + 56, index % 5 - 2.0F);
            data.putLong(localHeader + ShaderAbi.SECTION_LIGHT_HEADER_NODE_ADDRESS_OFFSET,
                    nodes + (long) node * ShaderAbi.LIGHT_NODE_SIZE);
            pointers.add(localHeader + ShaderAbi.SECTION_LIGHT_HEADER_NODE_ADDRESS_OFFSET);
            data.putInt(localHeader + ShaderAbi.SECTION_LIGHT_HEADER_EMITTER_COUNT_OFFSET, 1);
            int emitter = emitters + index * ShaderAbi.LIGHT_EMITTER_SIZE;
            data.putFloat(emitter, index * 2.0F);
            data.putFloat(emitter + 4, index % 3);
            data.putFloat(emitter + 8, index % 5);
            data.putFloat(emitter + 12, 3.0F);
            data.putFloat(emitter + 16, 2.0F);
            data.putFloat(emitter + 36, 3.0F);
            data.putFloat(emitter + 44, clusters.get(index).lights().power());
            data.putFloat(emitter + 56, 1.0F);
            data.putInt(emitter + ShaderAbi.LIGHT_EMITTER_METADATA_OFFSET + 8, index & 1);
        }
        SplittableRandom random = new SplittableRandom(0x4c49474854545245L);
        for (int query = 0; query < QUERIES; query++) {
            int offset = queries + query * 32;
            float x = (float) random.nextDouble(-32, count * 32.0 + 32);
            float y = (float) random.nextDouble(-16, 16);
            float z = (float) random.nextDouble(-16, 16);
            int mode = (query >>> 3) & 1;
            if ((query & 7) == 0) {
                x = 0; y = 0; z = 0;
            } else if ((query & 7) == 1) {
                x = 2.0F / 3.0F; y = 1; z = 0;
            }
            data.putFloat(offset, mode == 0 ? x - origin : x);
            data.putFloat(offset + 4, y);
            data.putFloat(offset + 8, z);
            float seed = switch (query & 7) {
                case 3 -> 0.0F;
                case 4 -> 1.0F;
                case 5 -> -1.0F;
                default -> random.nextFloat();
            };
            data.putFloat(offset + 12, seed);
            data.putFloat(offset + 16, query % 3 == 0 ? 1.0F : 0.0F);
            data.putFloat(offset + 20, query % 3 == 1 ? -1.0F : 0.0F);
            data.putInt(offset + 28, mode);
        }
        return new Fixture(data, pointers.stream().mapToInt(Integer::intValue).toArray());
    }

    private static int align(int value) { return (value + 15) & ~15; }
    private record Fixture(ByteBuffer data, int[] pointers) {}
}
