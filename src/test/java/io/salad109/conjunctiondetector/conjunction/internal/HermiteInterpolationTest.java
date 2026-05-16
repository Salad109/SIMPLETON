package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.KnotCache;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.PositionCache;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class HermiteInterpolationTest {

    private final PropagationService propagationService = new PropagationService();

    private static float cubic(float t) {
        return 64.0f + 2.0f * t + 0.5f * t * t + 0.25f * t * t * t;
    }

    private static float cubicDeriv(float t) {
        return 2.0f + t + 0.75f * t * t;
    }

    private static KnotCache buildKnots(int stride, int totalSteps, long stepNanos,
                                        float[][] kx, float[][] ky, float[][] kz,
                                        float[][] kvx, float[][] kvy, float[][] kvz) {
        OffsetDateTime[] times = new OffsetDateTime[totalSteps];
        OffsetDateTime start = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < totalSteps; i++) times[i] = start.plusNanos(i * stepNanos);

        int numSats = kx.length;
        IntIntHashMap idMap = new IntIntHashMap();
        int[] arrayIdToNoradId = new int[numSats];
        for (int s = 0; s < numSats; s++) {
            idMap.put(s + 1, s);
            arrayIdToNoradId[s] = s + 1;
        }

        return new KnotCache(idMap, arrayIdToNoradId, times, stepNanos, stride,
                kx, ky, kz, kvx, kvy, kvz);
    }

    @Test
    void cubicMotionInterpolatesExactly() {
        int stride = 10;
        int totalSteps = 21;
        long stepNanos = 1_000_000_000L;
        int numKnots = 3;

        float[][] kx = new float[1][numKnots], ky = new float[1][numKnots], kz = new float[1][numKnots];
        float[][] kvx = new float[1][numKnots], kvy = new float[1][numKnots], kvz = new float[1][numKnots];

        for (int k = 0; k < numKnots; k++) {
            float t = k * stride * (stepNanos / 1e9f);
            kx[0][k] = cubic(t);
            kvx[0][k] = cubicDeriv(t);
        }

        KnotCache knots = buildKnots(stride, totalSteps, stepNanos, kx, ky, kz, kvx, kvy, kvz);
        PositionCache result = propagationService.interpolate(knots);

        for (int step = 0; step < totalSteps; step++) {
            float t = step * (stepNanos / 1e9f);
            assertThat(result.x()[0][step]).as("x at step " + step)
                    .isCloseTo(cubic(t), offset(0.01f));
        }
    }

    @Test
    void velocityMismatchCausesDeviationFromLinear() {
        // Zero velocity at both knots forces an S-curve; a linear-only impl would return 25 at quarter-step.
        int stride = 8;
        int totalSteps = 9;
        long stepNanos = 1_000_000_000L;

        KnotCache knots = buildKnots(stride, totalSteps, stepNanos,
                new float[][]{{0.0f, 100.0f}}, new float[][]{{0.0f, 0.0f}}, new float[][]{{0.0f, 0.0f}},
                new float[][]{{0.0f, 0.0f}}, new float[][]{{0.0f, 0.0f}}, new float[][]{{0.0f, 0.0f}});

        PositionCache result = propagationService.interpolate(knots);

        int quarterStep = stride / 4;
        float atQuarter = result.x()[0][quarterStep];
        float linearQuarter = 100.0f * quarterStep / stride;

        assertThat(atQuarter).isNotCloseTo(linearQuarter, offset(0.1f));
    }

    @Test
    void nanSatelliteDoesNotPoisonOtherSatellites() {
        float[] nan = {Float.NaN, Float.NaN, Float.NaN};
        KnotCache knots = buildKnots(4, 9, 1_000_000_000L,
                new float[][]{nan, {0.0f, 40.0f, 80.0f}},
                new float[][]{nan, {0.0f, 0.0f, 0.0f}},
                new float[][]{nan, {0.0f, 0.0f, 0.0f}},
                new float[][]{nan, {10.0f, 10.0f, 10.0f}},
                new float[][]{nan, {0.0f, 0.0f, 0.0f}},
                new float[][]{nan, {0.0f, 0.0f, 0.0f}});

        PositionCache result = propagationService.interpolate(knots);

        for (int step = 0; step < 9; step++) {
            assertThat(result.x()[0][step]).as("sat 0 (NaN) step %d", step).isNaN();
            assertThat(result.x()[1][step]).as("sat 1 (linear) step %d", step).isEqualTo(step * 10.0f);
        }
    }
}
