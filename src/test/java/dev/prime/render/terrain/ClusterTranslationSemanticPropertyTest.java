package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.test.PrimeProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.jupiter.api.Test;

final class ClusterTranslationSemanticPropertyTest {
    private static final int GRID_SIZE = 8;

    @Test
    void shuffledBoundaryRectanglesMatchTheIndependentCellModel() {
        Generator<List<Integer>> rectangles = Generator.listsOf(
                IntDistribution.uniform(1, 24), Generator.integers());

        PrimeProperties.check(
                "cluster-translation-semantic-cells",
                192,
                rectangles,
                ClusterTranslationSemanticPropertyTest::assertScenario);
    }

    @Test
    void emptyInputMatchesTheCellModel() {
        ClusterTranslationSemanticOracle.Scenario scenario =
                new ClusterTranslationSemanticOracle.Scenario(GRID_SIZE, List.of());
        ClusterTranslationSemanticOracle.Built built =
                ClusterTranslationSemanticOracle.build(scenario, List.of());

        assertEquals(
                ClusterTranslationSemanticOracle.expected(scenario),
                ClusterTranslationSemanticOracle.actual(scenario, built));
    }

    private static void assertScenario(List<Integer> values) {
        ClusterTranslationSemanticOracle.Scenario scenario = scenario(values);
        ClusterTranslationSemanticOracle.Canonical expected =
                ClusterTranslationSemanticOracle.expected(scenario);
        List<Integer> original = IntStream.range(0, values.size()).boxed().toList();
        for (int shuffle = 0; shuffle < 4; shuffle++) {
            ArrayList<Integer> order = new ArrayList<>(original);
            Collections.shuffle(order, new Random(0x51a7_0000L + shuffle));
            ClusterTranslationSemanticOracle.Built built =
                    ClusterTranslationSemanticOracle.build(scenario, order);
            assertEquals(
                    expected,
                    ClusterTranslationSemanticOracle.actual(scenario, built),
                    "shuffle=" + shuffle + ", values=" + values);
        }
    }

    private static ClusterTranslationSemanticOracle.Scenario scenario(List<Integer> values) {
        ArrayList<ClusterTranslationSemanticOracle.Face> faces =
                new ArrayList<>(values.size());
        for (int owner = 0; owner < values.size(); owner++) {
            int value = values.get(owner);
            int firstU = coordinate(value, 0);
            int secondU = coordinate(value, 5);
            int firstV = coordinate(value, 10);
            int secondV = coordinate(value, 15);
            int minimumU = Math.min(firstU, secondU);
            int maximumU = Math.max(firstU, secondU) + 1;
            int minimumV = Math.min(firstV, secondV);
            int maximumV = Math.max(firstV, secondV) + 1;
            boolean transmissive = (value & 3) == 0;
            int medium = transmissive ? 1 + Math.floorMod(value >>> 20, 3) : 0;
            int sprite = transmissive
                    ? medium - 1
                    : Math.floorMod(Integer.rotateRight(value, 23), 4);
            int colorCode = transmissive
                    ? Math.floorMod(Integer.rotateRight(value, 27), 2)
                    : Math.floorMod(Integer.rotateRight(value, 27), 4);
            int color = 0xff00_0000
                    | (32 + colorCode * 48) << 16
                    | (64 + colorCode * 32) << 8
                    | 96 + colorCode * 16;
            faces.add(new ClusterTranslationSemanticOracle.Face(
                    owner,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV,
                    (value & 1 << 30) == 0 ? 1 : -1,
                    transmissive
                            ? ClusterTranslationSemanticOracle.Kind.SOLID_TRANSMISSIVE
                            : ClusterTranslationSemanticOracle.Kind.OPAQUE,
                    medium,
                    sprite,
                    color,
                    (value & 0x1f) == 0x1f));
        }
        return new ClusterTranslationSemanticOracle.Scenario(GRID_SIZE, faces);
    }

    private static int coordinate(int value, int shift) {
        return Math.floorMod(Integer.rotateRight(value, shift), GRID_SIZE);
    }
}
