package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.ScanService.CoarseDetection;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfoPair;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupAndReduceTest {

    private static final SatelliteScanInfoPair PAIR_AB = makePair(100, 200);
    private static final SatelliteScanInfoPair PAIR_CD = makePair(300, 400);
    private final ScanService scanService = new ScanService(null);

    private static SatelliteScanInfoPair makePair(int noradA, int noradB) {
        OffsetDateTime epoch = OffsetDateTime.now(ZoneOffset.UTC);
        SatelliteScanInfo a = new SatelliteScanInfo(noradA, "", "", epoch, 400.0, "PAYLOAD");
        SatelliteScanInfo b = new SatelliteScanInfo(noradB, "", "", epoch, 400.0, "PAYLOAD");
        return new SatelliteScanInfoPair(a, b);
    }

    @Test
    void consecutiveDetectionsClusterIntoOneEvent() {
        List<CoarseDetection> detections = List.of(
                new CoarseDetection(PAIR_AB, 25.0, 10),
                new CoarseDetection(PAIR_AB, 9.0, 11),
                new CoarseDetection(PAIR_AB, 16.0, 12)
        );

        List<CoarseDetection> result = scanService.groupAndReduce(detections);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().distanceSq()).isEqualTo(9.0);
        assertThat(result.getFirst().stepIndex()).isEqualTo(11);
    }

    @Test
    void gapOfExactlyThreeStaysInSameEvent() {
        List<CoarseDetection> detections = List.of(
                new CoarseDetection(PAIR_AB, 25.0, 10),
                new CoarseDetection(PAIR_AB, 9.0, 13)
        );

        List<CoarseDetection> result = scanService.groupAndReduce(detections);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().distanceSq()).isEqualTo(9.0);
    }

    @Test
    void gapOfFourSplits() {
        List<CoarseDetection> detections = List.of(
                new CoarseDetection(PAIR_AB, 25.0, 10),
                new CoarseDetection(PAIR_AB, 9.0, 14)
        );

        List<CoarseDetection> result = scanService.groupAndReduce(detections);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).distanceSq()).isEqualTo(25.0);
        assertThat(result.get(1).distanceSq()).isEqualTo(9.0);
    }

    @Test
    void differentPairsSplitIntoSeparateEvents() {
        List<CoarseDetection> detections = List.of(
                new CoarseDetection(PAIR_AB, 10.0, 5),
                new CoarseDetection(PAIR_CD, 20.0, 5)
        );

        List<CoarseDetection> result = scanService.groupAndReduce(detections);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).distanceSq()).isEqualTo(10.0);
        assertThat(result.get(0).pair()).isEqualTo(PAIR_AB);
        assertThat(result.get(1).distanceSq()).isEqualTo(20.0);
        assertThat(result.get(1).pair()).isEqualTo(PAIR_CD);
    }

    @Test
    void shuffledInputProducesSameResultAsOrdered() {
        // parallel scanning doesn't guarantee ordered items
        List<CoarseDetection> ordered = List.of(
                new CoarseDetection(PAIR_AB, 25.0, 10),
                new CoarseDetection(PAIR_AB, 9.0, 11),
                new CoarseDetection(PAIR_AB, 16.0, 12),
                new CoarseDetection(PAIR_CD, 50.0, 5),
                new CoarseDetection(PAIR_CD, 30.0, 6),
                new CoarseDetection(PAIR_AB, 100.0, 50),
                new CoarseDetection(PAIR_AB, 4.0, 51)
        );

        List<CoarseDetection> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled);

        List<CoarseDetection> fromOrdered = scanService.groupAndReduce(ordered);
        List<CoarseDetection> fromShuffled = scanService.groupAndReduce(shuffled);

        assertThat(fromShuffled).isEqualTo(fromOrdered);
    }
}
