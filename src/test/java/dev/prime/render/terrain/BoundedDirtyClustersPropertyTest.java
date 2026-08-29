package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.terrain.SectionCluster;
import dev.prime.test.PrimeProperties;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.SectionPos;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.Test;

final class BoundedDirtyClustersPropertyTest {
    @Test
    void commandSequencesMatchTheReferenceStateMachine() {
        Generator<List<Integer>> commands = Generator.listsOf(
                IntDistribution.uniform(1, 160), Generator.integers());

        PrimeProperties.check(
                "bounded-dirty-clusters",
                384,
                commands,
                BoundedDirtyClustersPropertyTest::assertCommands);
    }

    private static void assertCommands(List<Integer> commands) {
        int maximumKeys = 1 + Math.floorMod(commands.getFirst(), 32);
        BoundedDirtyClusters actual = new BoundedDirtyClusters(maximumKeys);
        Model expected = new Model(maximumKeys);
        for (int index = 1; index < commands.size(); index++) {
            int value = commands.get(index);
            switch (Math.floorMod(value, 6)) {
                case 0 -> {
                    long key = clusterKey(value);
                    actual.addCluster(key);
                    expected.add(key);
                }
                case 1 -> {
                    int[] range = range(value);
                    actual.addExpandedBlockRange(
                            range[0], range[1], range[2],
                            range[3], range[4], range[5]);
                    expected.addExpandedRange(range);
                }
                case 2 -> {
                    actual.invalidateAll();
                    expected.invalidateAll();
                }
                case 3 -> assertBatch(expected.drain(), actual.drain(), index);
                case 4 -> {
                    actual.clear();
                    expected.clear();
                }
                case 5 -> {
                    long key = clusterKey(value);
                    actual.addCluster(key);
                    actual.addCluster(key);
                    expected.add(key);
                    expected.add(key);
                }
                default -> throw new AssertionError("unreachable command");
            }
        }
        assertBatch(expected.drain(), actual.drain(), commands.size());
        assertBatch(expected.drain(), actual.drain(), commands.size() + 1);
    }

    private static long clusterKey(int value) {
        int x = coordinate(value, 1);
        int y = coordinate(value, 11);
        int z = coordinate(value, 21);
        return SectionPos.asLong(x, y, z);
    }

    private static int[] range(int value) {
        int mixed = value * 0x9e37_79b9;
        return new int[] {
            coordinate(value, 0),
            coordinate(value, 10),
            coordinate(value, 20),
            coordinate(mixed, 0),
            coordinate(mixed, 10),
            coordinate(mixed, 20)
        };
    }

    private static int coordinate(int value, int shift) {
        return Math.floorMod(Integer.rotateRight(value, shift), 513) - 256;
    }

    private static void assertBatch(
            ExpectedBatch expected,
            BoundedDirtyClusters.Batch actual,
            int commandIndex) {
        assertEquals(expected.fullInvalidation(), actual.fullInvalidation(),
                "command=" + commandIndex);
        long[] actualKeys = actual.keys().clone();
        Arrays.sort(actualKeys);
        assertArrayEquals(expected.keys(), actualKeys, "command=" + commandIndex);
    }

    private record ExpectedBatch(boolean fullInvalidation, long[] keys) {
    }

    private static final class Model {
        private final int maximumKeys;
        private final Set<Long> keys = new HashSet<>();
        private boolean fullInvalidation;

        private Model(int maximumKeys) {
            this.maximumKeys = maximumKeys;
        }

        private void add(long key) {
            if (this.fullInvalidation) {
                return;
            }
            this.keys.add(key);
            if (this.keys.size() > this.maximumKeys) {
                this.invalidateAll();
            }
        }

        private void addExpandedRange(int[] range) {
            if (this.fullInvalidation) {
                return;
            }
            int minimumX = clusterOrigin(expandedMinimum(range[0], range[3]));
            int minimumY = clusterOrigin(expandedMinimum(range[1], range[4]));
            int minimumZ = clusterOrigin(expandedMinimum(range[2], range[5]));
            int maximumX = clusterOrigin(expandedMaximum(range[0], range[3]));
            int maximumY = clusterOrigin(expandedMaximum(range[1], range[4]));
            int maximumZ = clusterOrigin(expandedMaximum(range[2], range[5]));
            long countX = ((long) maximumX - minimumX) / SectionCluster.SECTION_SIZE + 1;
            long countY = ((long) maximumY - minimumY) / SectionCluster.SECTION_SIZE + 1;
            long countZ = ((long) maximumZ - minimumZ) / SectionCluster.SECTION_SIZE + 1;
            if (countX > this.maximumKeys
                    || countY > this.maximumKeys
                    || countZ > this.maximumKeys
                    || countX * countY > this.maximumKeys
                    || countX * countY * countZ > this.maximumKeys) {
                this.invalidateAll();
                return;
            }
            for (int z = minimumZ; z <= maximumZ; z += SectionCluster.SECTION_SIZE) {
                for (int y = minimumY; y <= maximumY; y += SectionCluster.SECTION_SIZE) {
                    for (int x = minimumX; x <= maximumX; x += SectionCluster.SECTION_SIZE) {
                        this.add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }

        private void invalidateAll() {
            this.keys.clear();
            this.fullInvalidation = true;
        }

        private ExpectedBatch drain() {
            long[] drained = this.keys.stream().mapToLong(Long::longValue).sorted().toArray();
            ExpectedBatch batch = new ExpectedBatch(this.fullInvalidation, drained);
            this.clear();
            return batch;
        }

        private void clear() {
            this.keys.clear();
            this.fullInvalidation = false;
        }

        private static int expandedMinimum(int first, int second) {
            return (int) Math.floorDiv((long) Math.min(first, second) - 1L, 16L);
        }

        private static int expandedMaximum(int first, int second) {
            return (int) Math.floorDiv((long) Math.max(first, second) + 1L, 16L);
        }

        private static int clusterOrigin(int section) {
            return Math.floorDiv(section, SectionCluster.SECTION_SIZE)
                    * SectionCluster.SECTION_SIZE;
        }
    }
}
