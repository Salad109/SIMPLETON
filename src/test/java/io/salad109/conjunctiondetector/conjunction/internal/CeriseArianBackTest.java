package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

import java.io.File;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;


class CeriseArianBackTest {

    // Last TLEs before collision, from Space-Track GP history
    // CERISE (NORAD ID 23606)
    private static final String CERISE_TLE1 = "1 23606U 95033B   96205.39273562 +.00000083 +00000-0 +23247-4 0  9999";
    private static final String CERISE_TLE2 = "2 23606 098.1025 141.7519 0008991 067.4104 292.8048 14.67264268056023";

    // Ariane 1 debris (NORAD ID 18208)
    private static final String DEBRIS_TLE1 = "1 18208U 86019RF  96205.34413154 +.00001097 +00000-0 +20371-3 0  9993";
    private static final String DEBRIS_TLE2 = "2 18208 098.4535 334.7433 0014702 119.3840 240.8797 14.67242450509233";

    // Multiple detected passes. Source: "Collision of Cerise with Space Debris" by Alby, Lansard & Michal
    private static final OffsetDateTime[] PASSES = {
            OffsetDateTime.of(1996, 7, 24, 1, 37, 2, 0, ZoneOffset.UTC),
            OffsetDateTime.of(1996, 7, 24, 3, 15, 14, 100_000_000, ZoneOffset.UTC),
            OffsetDateTime.of(1996, 7, 24, 4, 53, 26, 100_000_000, ZoneOffset.UTC),
            OffsetDateTime.of(1996, 7, 24, 6, 31, 38, 200_000_000, ZoneOffset.UTC),
            OffsetDateTime.of(1996, 7, 24, 8, 9, 50, 200_000_000, ZoneOffset.UTC),
            OffsetDateTime.of(1996, 7, 24, 9, 48, 2, 500_000_000, ZoneOffset.UTC),
    };
    private static final double[] PASS_DISTANCES_KM = {2.6, 2.3, 1.8, 1.8, 1.6, 1.5};

    private final PropagationService propagationService = new PropagationService();
    private final ScanService scanService = new ScanService(propagationService);
    private final CollisionProbabilityService probabilityService = new CollisionProbabilityService();

    @BeforeAll
    static void initOrekit() {
        File orekitData = new File("src/main/resources/orekit-data");
        if (orekitData.exists()) {
            DataContext.getDefault().getDataProvidersManager()
                    .addProvider(new DirectoryCrawler(orekitData));
        }
    }

    @Test
    void sgp4FindsCloseApproachAtCollisionTime() {

        TLEPropagator ceriseProp = TLEPropagator.selectExtrapolator(new TLE(CERISE_TLE1, CERISE_TLE2));
        TLEPropagator debrisProp = TLEPropagator.selectExtrapolator(new TLE(DEBRIS_TLE1, DEBRIS_TLE2));

        AbsoluteDate collisionDate = new AbsoluteDate(PASSES[5].toInstant(), TimeScalesFactory.getUTC());

        PVCoordinates pvCerise = ceriseProp.getPVCoordinates(collisionDate, ceriseProp.getFrame());
        PVCoordinates pvDebris = debrisProp.getPVCoordinates(collisionDate, ceriseProp.getFrame());

        double distanceM = pvCerise.getPosition().subtract(pvDebris.getPosition()).getNorm();
        double relVelMS = pvCerise.getVelocity().subtract(pvDebris.getVelocity()).getNorm();

        System.out.printf("Distance at collision time: %.1f m (expected 1.5 km TLE-based, 0.687-0.917 km refined)%n", distanceM);
        System.out.printf("Relative velocity: %.1f m/s (expected ~14769 m/s)%n", relVelMS);

        assertThat(distanceM)
                .as("SGP4 miss distance at known collision time")
                .isLessThan(5000);
        assertThat(relVelMS)
                .as("relative velocity (expected ~14769 m/s)")
                .isCloseTo(14769, offset(1000.0));
    }

    @Test
    void fullPipelineDetectsMultiplePasses() {

        double toleranceKm = 84.0;
        double cellSizeKm = 74.0;
        double stepSeconds = 10.8;
        int interpolationStride = 32;
        double thresholdKm = 5.0;

        OffsetDateTime ceriseEpoch = OffsetDateTime.of(1996, 7, 23, 9, 25, 32, 0, ZoneOffset.UTC);
        OffsetDateTime debrisEpoch = OffsetDateTime.of(1996, 7, 23, 8, 15, 33, 0, ZoneOffset.UTC);

        SatelliteScanInfo cerise = new SatelliteScanInfo(23606, CERISE_TLE1, CERISE_TLE2,
                ceriseEpoch, 670.0, "PAYLOAD");
        SatelliteScanInfo debris = new SatelliteScanInfo(18208, DEBRIS_TLE1, DEBRIS_TLE2,
                debrisEpoch, 670.0, "DEBRIS");

        List<SatelliteScanInfo> satellites = List.of(cerise, debris);
        Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);

        // Propagate and interpolate
        OffsetDateTime startTime = OffsetDateTime.of(1996, 7, 24, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime endTime = startTime.plusHours(10);
        PropagationService.KnotCache knots = propagationService.computeKnots(
                propagators, startTime, endTime, stepSeconds, interpolationStride);
        PropagationService.PositionCache cache = propagationService.interpolate(knots);

        // Coarse spatial scan
        List<ScanService.CoarseDetection> detections = scanService.checkPairs(
                satellites, cache, toleranceKm, cellSizeKm);

        assertThat(detections).as("coarse detections").isNotEmpty();

        // Group and reduce
        List<ScanService.CoarseDetection> events = scanService.groupAndReduce(detections);

        assertThat(events).as("grouped events").isNotEmpty();

        // Refine
        List<ScanService.RefinedEvent> refined = scanService.refine(
                events, cache, propagators, stepSeconds, thresholdKm);

        // 6 passes documented in the paper
        List<ScanService.RefinedEvent> sorted = refined.stream()
                .sorted(Comparator.comparing(ScanService.RefinedEvent::tca))
                .toList();
        assertThat(sorted).as("pipeline should detect all 6 passes").hasSize(6);

        for (int i = 0; i < 6; i++) {
            ScanService.RefinedEvent e = sorted.get(i);
            long tcaErrorMs = Math.abs(Duration.between(PASSES[i], e.tca()).toMillis());
            System.out.printf("Pass %d: TCA %s (error %dms)  dist %.3f km (paper %.1f km)  vel %.1f m/s%n",
                    i + 1, e.tca(), tcaErrorMs, e.distanceKm(), PASS_DISTANCES_KM[i], e.relativeVelocityMS());
            assertThat(tcaErrorMs)
                    .as("pass %d TCA error (detected %s, paper %s)", i + 1, e.tca(), PASSES[i])
                    .isLessThan(30_000);
            assertThat(e.relativeVelocityMS())
                    .as("pass %d relative velocity (paper: 14769 m/s)", i + 1)
                    .isCloseTo(14769, offset(1000.0));
        }

        // Last pass is the collision
        ScanService.RefinedEvent collisionPass = sorted.getLast();
        Conjunction conjunction = probabilityService.computeProbabilityAndBuild(collisionPass);

        System.out.printf("Closest approach: %.3f km (expected 1.5 km TLE-based, 0.687-0.917 km refined)%n",
                conjunction.getMissDistanceKm());
        System.out.printf("Collision probability: %.6e%n", conjunction.getCollisionProbability());

        assertThat(conjunction.getMissDistanceKm()).as("pipeline miss distance").isLessThan(5.0);
        assertThat(conjunction.getObject1NoradId()).as("lower NORAD ID first").isEqualTo(18208);
        assertThat(conjunction.getObject2NoradId()).isEqualTo(23606);
    }
}
