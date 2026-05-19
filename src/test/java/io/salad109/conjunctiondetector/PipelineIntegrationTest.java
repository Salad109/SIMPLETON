package io.salad109.conjunctiondetector;

import io.salad109.conjunctiondetector.conjunction.ConjunctionInfo;
import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.conjunction.ScanLogService;
import io.salad109.conjunctiondetector.conjunction.ScanResult;
import io.salad109.conjunctiondetector.ingestion.IngestionLogService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import io.salad109.conjunctiondetector.ingestion.SyncResult;
import io.salad109.conjunctiondetector.satellite.SatelliteBriefInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import io.salad109.conjunctiondetector.spacetrack.OmmRecord;
import io.salad109.conjunctiondetector.spacetrack.SpaceTrackClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "conjunction.schedule.cron=-"
)
@Testcontainers
@Sql(statements = "TRUNCATE conjunction, satellite, ingestion_log, scan_log RESTART IDENTITY CASCADE")
class PipelineIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @TestBean
    Clock clock;

    @MockitoBean
    SpaceTrackClient spaceTrackClient;

    @Autowired
    SatelliteService satelliteService;

    @Autowired
    IngestionService ingestionService;

    @Autowired
    ConjunctionService conjunctionService;

    @Autowired
    ScanLogService scanLogService;

    @Autowired
    IngestionLogService ingestionLogService;

    // Freeze just before the Iridium 33 / Cosmos 2251 collision (Feb 10 2009 16:55:59 UTC)
    static Clock clock() {
        return Clock.fixed(LocalDateTime.of(2009, 2, 10, 0, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static OmmRecord iridium33() {
        return buildOmm(24946, "IRIDIUM 33",
                LocalDateTime.of(2009, 2, 9, 18, 49, 39),
                "1 24946U 97051C   09040.78448243 +.00000153 +00000-0 +47668-4 0  9994",
                "2 24946 086.3994 121.7028 0002288 085.1644 274.9812 14.34219863597336");
    }

    private static OmmRecord cosmos2251() {
        return buildOmm(22675, "COSMOS 2251",
                LocalDateTime.of(2009, 2, 9, 11, 57, 36),
                "1 22675U 93036A   09040.49834364 -.00000001  00000-0  95251-5 0  9996",
                "2 22675 074.0355 019.4646 0016027 098.7014 261.5952 14.31135643817415");
    }

    private static OmmRecord buildOmm(int noradId, String name, LocalDateTime epoch, String tle1, String tle2) {
        return new OmmRecord(noradId, name, name, "PAYLOAD", null, null, null, null, null,
                epoch, epoch, name, tle1, tle2,
                BigDecimal.ONE, null, null,
                BigDecimal.ZERO, null, null, null, null, null, null,
                null, null, null, null, null, null, 0.0, null, null);
    }

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

    @Test
    void syncIsIdempotent() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), cosmos2251()));

        ingestionService.sync();
        long afterFirst = satelliteService.count();
        ingestionService.sync();
        long afterSecond = satelliteService.count();

        assertThat(afterSecond).as("second sync must not duplicate rows").isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo(2);

        SyncResult secondLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(secondLog.objectsInserted()).isZero();
        assertThat(secondLog.objectsUpdated()).isZero();
        assertThat(secondLog.objectsUnchanged()).isEqualTo(2);
        assertThat(secondLog.objectsDeleted()).isZero();
    }

    @Test
    void syncSkipsInvalidRecordsAndDeletesMissingOnes() throws IOException {
        // Stale OMM with epoch >30 days before frozen clock must be filtered out
        OmmRecord stale = buildOmm(99999, "STALE",
                LocalDateTime.of(2008, 12, 1, 0, 0),
                "1 99999U 00000A   08336.00000000 +.00000000 +00000-0 +00000-0 0  0000",
                "2 99999 000.0000 000.0000 0000000 000.0000 000.0000 00.00000000000000");
        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33(), cosmos2251(), stale));

        ingestionService.sync();
        assertThat(satelliteService.count()).as("stale record rejected at ingest").isEqualTo(2);
        SyncResult firstLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(firstLog.objectsSkipped()).isEqualTo(1);

        when(spaceTrackClient.fetchCatalog()).thenReturn(List.of(iridium33()));
        ingestionService.sync();
        assertThat(satelliteService.count()).as("cosmos pruned").isEqualTo(1);
        SyncResult secondLog = ingestionLogService.getRecent(1).getFirst();
        assertThat(secondLog.objectsDeleted()).isEqualTo(1);
    }

    @Test
    void syncFailureIsLoggedAsUnsuccessful() throws IOException {
        when(spaceTrackClient.fetchCatalog()).thenThrow(new IOException("Space-Track unreachable"));
        long before = satelliteService.count();

        ingestionService.sync();

        SyncResult log = ingestionLogService.getRecent(1).getFirst();
        assertThat(log.successful()).as("failed sync flagged unsuccessful").isFalse();
        assertThat(log.objectsInserted()).isZero();
        assertThat(satelliteService.count()).as("failed sync must not mutate catalog").isEqualTo(before);
    }

}
