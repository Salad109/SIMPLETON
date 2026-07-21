package io.salad109.conjunctiondetector;

import io.salad109.conjunctiondetector.conjunction.ConjunctionInfo;
import io.salad109.conjunctiondetector.conjunction.ScanResult;
import io.salad109.conjunctiondetector.ingestion.SyncResult;
import io.salad109.conjunctiondetector.satellite.SatelliteBriefInfo;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class FullPipelineTest extends AbstractPipelineTest {

    @Test
    void syncAndScanPinsHistoricCollision() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), cosmos2251()));

        ingestionService.sync();
        assertThat(satelliteService.count()).as("both satellites persisted").isEqualTo(2);

        SyncResult syncLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(syncLog.successful()).isTrue();
        assertThat(syncLog.objectsInserted()).isEqualTo(2);
        assertThat(syncLog.objectsSkipped()).isZero();

        conjunctionService.findConjunctions();
        assertThat(conjunctionService.countActive())
                .as("scan must persist at least the historic event").isGreaterThanOrEqualTo(1);

        OffsetDateTime realTca = OffsetDateTime.of(2009, 2, 10, 16, 55, 59, 0, ZoneOffset.UTC);
        List<ConjunctionInfo> events = conjunctionService.getConjunctionInfosByNoradId(24946);
        assertThat(events).as("pipeline produced the Iridium-Cosmos event").anySatisfy(e -> {
            assertThat(e.object1NoradId()).as("canonical NORAD ordering").isEqualTo(22675);
            assertThat(e.object2NoradId()).isEqualTo(24946);
            assertThat(e.tca()).as("TCA within 30s of historic collision")
                    .isCloseTo(realTca, within(30, ChronoUnit.SECONDS));
            assertThat(e.missDistanceKm()).as("pipeline miss distance").isLessThan(5.0);
            assertThat(e.relativeVelocityMS()).as("relative velocity (expected ~11700 m/s)")
                    .isCloseTo(11700, offset(1000.0));
        });

        List<SatelliteBriefInfo> sats = satelliteService.getBriefInfos(PageRequest.of(0, 10)).getContent();
        assertThat(sats).hasSize(2).allSatisfy(s ->
                assertThat(s.conjunctions()).as("sat %d conjunction_count", s.noradCatId())
                        .isGreaterThanOrEqualTo(1));

        ScanResult scanLog = scanLogService.getRecent(1).getFirst();
        assertThat(scanLog.satellitesScanned()).isEqualTo(2);
        assertThat(scanLog.conjunctionsDetected()).as("scan log count matches live countActive")
                .isEqualTo((int) conjunctionService.countActive());
        assertThat(scanLog.durationMs()).isNotNegative();
    }
}
