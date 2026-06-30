package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.PositionCache;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class AnalyticalMinTest {

    private final ScanService scanService = new ScanService(null);

    private static float rand(Random rng) {
        return (rng.nextFloat() - 0.5f) * 14000.0f; // [-7000, 7000] km
    }

    private static PositionCache buildCache(float[][] x, float[][] y, float[][] z) {
        IntIntHashMap idMap = new IntIntHashMap();
        idMap.put(1, 0);
        idMap.put(2, 1);
        OffsetDateTime[] times = {OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10)};
        return new PositionCache(idMap, new int[]{1, 2}, times, x, y, z);
    }

    @Test
    void minimumAtMidpointForHeadOnApproach() {
        // A: (0,0,0)->(10,0,0), B: (10,0,0)->(0,0,0)
        PositionCache cache = buildCache(
                new float[][]{{0, 10}, {10, 0}},
                new float[][]{{0, 0}, {0, 0}},
                new float[][]{{0, 0}, {0, 0}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[0]).isCloseTo(0.0, offset(1e-6));
        assertThat(result[1]).isCloseTo(0.5, offset(1e-6));
    }

    @Test
    void minimumClampedToStartWhenDiverging() {
        // A: (0,0,0)->(100,0,0), B: (0,0,0)->(-100,0,0)
        PositionCache cache = buildCache(
                new float[][]{{0, 100}, {0, -100}},
                new float[][]{{0, 0}, {0, 0}},
                new float[][]{{0, 0}, {0, 0}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[0]).isCloseTo(0.0, offset(1e-6));
        assertThat(result[1]).isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void minimumClampedToEndWhenConverging() {
        // A: (-100,0,0)->(0,0,0), B: (100,0,0)->(1,0,0)
        PositionCache cache = buildCache(
                new float[][]{{-100, 0}, {100, 1}},
                new float[][]{{0, 0}, {0, 0}},
                new float[][]{{0, 0}, {0, 0}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[0]).isCloseTo(1.0, offset(1e-6));
        assertThat(result[1]).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void minimumDistance3D() {
        // Perpendicular pass with 3 km z-offset. Min at t=0.5, distSq=9
        PositionCache cache = buildCache(
                new float[][]{{0, 10}, {5, 5}},
                new float[][]{{5, 5}, {0, 10}},
                new float[][]{{0, 0}, {3, 3}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[0]).isCloseTo(9.0, offset(1e-6));
        assertThat(result[1]).isCloseTo(0.5, offset(1e-6));
    }

    @Test
    void nearlyParallelTrajectoriesNoNaNOrInfinity() {
        // Same x velocity, tiny y crossing
        PositionCache cache = buildCache(
                new float[][]{{0.0f, 1000.0f}, {0.0f, 1000.0f}},
                new float[][]{{0.0f, 0.0f}, {-0.001f, 0.0005f}},
                new float[][]{{0.0f, 0.0f}, {0.0f, 0.0f}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[0]).as("distSq").isCloseTo(0.0, offset(1e-6));
        assertThat(result[1]).as("t").isCloseTo(2.0 / 3.0, offset(1e-6));
    }

    @Test
    void exactlyParallelTrajectoriesFallBackToHalf() {
        // Identical velocity vectors, constant 5 km separation (3^2+4^2=25)
        PositionCache cache = buildCache(
                new float[][]{{0.0f, 100.0f}, {3.0f, 103.0f}},
                new float[][]{{0.0f, 0.0f}, {0.0f, 0.0f}},
                new float[][]{{0.0f, 0.0f}, {4.0f, 4.0f}}
        );

        double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);

        assertThat(result[1]).isCloseTo(0.5, offset(1e-6));
        assertThat(result[0]).isCloseTo(25.0, offset(1e-6));
    }

    @Test
    void distSqAndTRemainWellFormedAcrossRandomized3DConfigurations() {
        // minDistSq must be non-negative and t must be clamped to [0,1]
        Random rng = new Random(21);
        for (int trial = 0; trial < 2000; trial++) {
            PositionCache cache = buildCache(
                    new float[][]{{rand(rng), rand(rng)}, {rand(rng), rand(rng)}},
                    new float[][]{{rand(rng), rand(rng)}, {rand(rng), rand(rng)}},
                    new float[][]{{rand(rng), rand(rng)}, {rand(rng), rand(rng)}}
            );
            double[] result = scanService.analyticalMin(cache, 0, 1, 0, 1);
            assertThat(result[0]).as("trial %d distSq", trial).isGreaterThanOrEqualTo(0.0);
            assertThat(result[1]).as("trial %d t", trial).isBetween(0.0, 1.0);
        }
    }
}
