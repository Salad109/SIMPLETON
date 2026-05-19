package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService.PositionCache;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService.CoarseDetection;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckPairsTest {

    private final ScanService scanService = new ScanService(null);

    private static SatelliteScanInfo sat(int noradId) {
        return new SatelliteScanInfo(noradId, "", "", OffsetDateTime.now(ZoneOffset.UTC), 400.0, "PAYLOAD");
    }

    private static PositionCache cacheOf(int[] arrayIdToNoradId, float[][] x, float[][] y, float[][] z) {
        IntIntHashMap idMap = new IntIntHashMap();
        for (int i = 0; i < arrayIdToNoradId.length; i++) idMap.put(arrayIdToNoradId[i], i);
        int steps = x[0].length;
        OffsetDateTime t0 = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime[] times = new OffsetDateTime[steps];
        for (int i = 0; i < steps; i++) times[i] = t0.plusSeconds(i);
        return new PositionCache(idMap, arrayIdToNoradId, times, x, y, z);
    }

    @Test
    void detectsPairWithinToleranceAndOrdersCanonically() {
        PositionCache cache = cacheOf(
                new int[]{500, 200},
                new float[][]{{0f}, {3f}},
                new float[][]{{0f}, {0f}},
                new float[][]{{0f}, {0f}});

        List<CoarseDetection> result = scanService.checkPairs(
                List.of(sat(500), sat(200)), cache, 10.0, 10.0);

        assertThat(result).hasSize(1);
        CoarseDetection det = result.getFirst();
        assertThat(det.pair().a().noradCatId()).isEqualTo(200);
        assertThat(det.pair().b().noradCatId()).isEqualTo(500);
        assertThat(det.distanceSq()).isEqualTo(9.0);
        assertThat(det.stepIndex()).isZero();
    }

    @Test
    void pairInSameCellButOutsideToleranceIsRejected() {
        PositionCache cache = cacheOf(
                new int[]{100, 200},
                new float[][]{{0f}, {7f}},
                new float[][]{{0f}, {0f}},
                new float[][]{{0f}, {0f}});

        List<CoarseDetection> result = scanService.checkPairs(
                List.of(sat(100), sat(200)), cache, 5.0, 10.0);

        assertThat(result).isEmpty();
    }

    @Test
    void pairWithinToleranceButInNonAdjacentCellsIsMissedByGrid() {
        PositionCache cache = cacheOf(
                new int[]{100, 200},
                new float[][]{{0f}, {8f}},
                new float[][]{{0f}, {0f}},
                new float[][]{{0f}, {0f}});

        List<CoarseDetection> result = scanService.checkPairs(
                List.of(sat(100), sat(200)), cache, 10.0, 3.0);

        assertThat(result).isEmpty();
    }

    @Test
    void emitsOneDetectionPerStepWherePairIsClose() {
        PositionCache cache = cacheOf(
                new int[]{100, 200},
                new float[][]{{0f, 0f, 0f}, {2f, 100f, 4f}},
                new float[][]{{0f, 0f, 0f}, {0f, 0f, 0f}},
                new float[][]{{0f, 0f, 0f}, {0f, 0f, 0f}});

        List<CoarseDetection> result = scanService.checkPairs(
                List.of(sat(100), sat(200)), cache, 10.0, 10.0);

        assertThat(result).extracting(CoarseDetection::stepIndex)
                .containsExactlyInAnyOrder(0, 2);
        assertThat(result).extracting(CoarseDetection::distanceSq)
                .containsExactlyInAnyOrder(4.0, 16.0);
    }
}
