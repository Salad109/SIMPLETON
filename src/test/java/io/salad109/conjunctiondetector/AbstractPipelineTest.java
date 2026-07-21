package io.salad109.conjunctiondetector;

import io.salad109.conjunctiondetector.conjunction.ConjunctionService;
import io.salad109.conjunctiondetector.conjunction.ScanLogService;
import io.salad109.conjunctiondetector.ingestion.IngestionLogService;
import io.salad109.conjunctiondetector.ingestion.IngestionService;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import io.salad109.conjunctiondetector.spacetrack.GpRecord;
import io.salad109.conjunctiondetector.spacetrack.SpaceTrackClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Shared Spring and Testcontainers config and catalog data for integration tests.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "conjunction.schedule.cron=-"
)
abstract class AbstractPipelineTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

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

    @Autowired
    JdbcTemplate jdbcTemplate;

    // Freeze just before the Iridium 33 / Cosmos 2251 collision (Feb 10 2009 16:55:59 UTC)
    static Clock clock() {
        return Clock.fixed(LocalDateTime.of(2009, 2, 10, 0, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    static GpRecord iridium33() {
        return buildGp(24946, "IRIDIUM 33",
                LocalDateTime.of(2009, 2, 9, 18, 49, 39),
                "1 24946U 97051C   09040.78448243 +.00000153 +00000-0 +47668-4 0  9994",
                "2 24946 086.3994 121.7028 0002288 085.1644 274.9812 14.34219863597336");
    }

    static GpRecord cosmos2251() {
        return buildGp(22675, "COSMOS 2251",
                LocalDateTime.of(2009, 2, 9, 11, 57, 36),
                "1 22675U 93036A   09040.49834364 -.00000001  00000-0  95251-5 0  9996",
                "2 22675 074.0355 019.4646 0016027 098.7014 261.5952 14.31135643817415");
    }

    static GpRecord buildGp(int noradId, String name, LocalDateTime epoch, String tle1, String tle2) {
        return new GpRecord(noradId, name, name, "PAYLOAD", null, null, null, null, null,
                epoch, epoch, name, tle1, tle2,
                BigDecimal.ONE, null, null,
                BigDecimal.ZERO, null, null, null, null, null, null,
                null, null, null, null, null, null, 0.0, null, null);
    }

    // The container database is shared across every test, so wipe it before each
    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute("TRUNCATE conjunction, satellite, ingestion_log, scan_log RESTART IDENTITY CASCADE");
    }
}
