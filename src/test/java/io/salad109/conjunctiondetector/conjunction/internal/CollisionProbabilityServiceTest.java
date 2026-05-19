package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.ScanService.RefinedEvent;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfoPair;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

import java.io.File;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CollisionProbabilityServiceTest {

    private static final double MU = 398600.4418e9;
    private static final double LEO_RADIUS_M = 7_000_000.0;
    private static final double LEO_PERIGEE_KM = 700.0;
    private static final double HIGH_PERIGEE_KM = 3000.0;
    private static final OffsetDateTime TCA =
            OffsetDateTime.of(2025, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FRESH_EPOCH = TCA.minusHours(12);
    private static final double HEAD_ON_REL_VEL_MS = 2.0 * Math.sqrt(MU / LEO_RADIUS_M);
    private final CollisionProbabilityService service = new CollisionProbabilityService();

    @BeforeAll
    static void initOrekit() {
        File orekitData = new File("src/main/resources/orekit-data");
        if (orekitData.exists()) {
            DataContext.getDefault().getDataProvidersManager()
                    .addProvider(new DirectoryCrawler(orekitData));
        }
    }

    private static EventBuilder event() {
        return new EventBuilder();
    }

    @Test
    void lowRelativeVelocityYieldsZeroPc() {
        assertThat(pc(event().relVel(5.0))).isZero();
    }

    @Test
    void nullPvCoordinatesYieldsZeroPc() {
        SatelliteScanInfo a = new SatelliteScanInfo(11111, null, null, FRESH_EPOCH, LEO_PERIGEE_KM, "PAYLOAD");
        SatelliteScanInfo b = new SatelliteScanInfo(22222, null, null, FRESH_EPOCH, LEO_PERIGEE_KM, "PAYLOAD");
        RefinedEvent noPv = new RefinedEvent(new SatelliteScanInfoPair(a, b),
                0.05, TCA, HEAD_ON_REL_VEL_MS, null, null, null, null);
        assertThat(service.computeProbabilityAndBuild(noPv).getCollisionProbability()).isZero();
    }

    @Test
    void noradIdsAreCanonicallyOrdered() {
        Conjunction result = service.computeProbabilityAndBuild(event().norads(99999, 11111).build());
        assertThat(result.getObject1NoradId()).isEqualTo(11111);
        assertThat(result.getObject2NoradId()).isEqualTo(99999);
    }

    @Test
    void pcDecreasesAsMissDistanceGrows() {
        assertThat(pc(event().miss(0.05))).isGreaterThan(pc(event().miss(5.0)));
    }

    @Test
    void payloadPairYieldsHigherPcThanDebrisPair() {
        // 5m+5m vs 0.5m+0.5m
        assertThat(pc(event())).isGreaterThan(pc(event().types("DEBRIS", "DEBRIS")));
    }

    @Test
    void nullAndUnrecognizedObjectTypesBothFallBackToUnknownRadius() {
        assertThat(pc(event().types(null, null))).isEqualTo(pc(event().types("unk", "idk")));
    }

    @Test
    void asymmetricObjectTypesProduceCombinedRadiusBetweenSymmetricPairs() {
        // 5m+0.5m combined radius is between 5m+5m and 0.5m+0.5m
        double bigBig = pc(event());
        double smallSmall = pc(event().types("DEBRIS", "DEBRIS"));
        double mixed = pc(event().typeB("DEBRIS"));
        assertThat(mixed).isBetween(smallSmall, bigBig);
    }

    @Test
    void staleTleProducesDifferentPcThanFreshTle() {
        OffsetDateTime stale = TCA.minusDays(7);
        assertThat(pc(event().epochs(stale, stale))).isNotEqualTo(pc(event()));
    }

    @Test
    void highAltitudePerigeeSelectsDistinctCovarianceBranch() {
        assertThat(pc(event().perigee(HIGH_PERIGEE_KM))).isNotEqualTo(pc(event()));
    }

    private double pc(EventBuilder builder) {
        return service.computeProbabilityAndBuild(builder.build()).getCollisionProbability();
    }

    private static final class EventBuilder {
        private double missKm = 0.05;
        private double relVelMS = HEAD_ON_REL_VEL_MS;
        private double perigeeKmA = LEO_PERIGEE_KM;
        private double perigeeKmB = LEO_PERIGEE_KM;
        private String typeA = "PAYLOAD";
        private String typeB = "PAYLOAD";
        private OffsetDateTime epochA = FRESH_EPOCH;
        private OffsetDateTime epochB = FRESH_EPOCH;
        private int noradA = 11111;
        private int noradB = 22222;

        EventBuilder miss(double km) {
            this.missKm = km;
            return this;
        }

        EventBuilder relVel(double mps) {
            this.relVelMS = mps;
            return this;
        }

        EventBuilder perigee(double km) {
            this.perigeeKmA = km;
            this.perigeeKmB = km;
            return this;
        }

        EventBuilder types(String a, String b) {
            this.typeA = a;
            this.typeB = b;
            return this;
        }

        EventBuilder typeB(String t) {
            this.typeB = t;
            return this;
        }

        EventBuilder epochs(OffsetDateTime a, OffsetDateTime b) {
            this.epochA = a;
            this.epochB = b;
            return this;
        }

        EventBuilder norads(int a, int b) {
            this.noradA = a;
            this.noradB = b;
            return this;
        }

        RefinedEvent build() {
            SatelliteScanInfo a = new SatelliteScanInfo(noradA, null, null, epochA, perigeeKmA, typeA);
            SatelliteScanInfo b = new SatelliteScanInfo(noradB, null, null, epochB, perigeeKmB, typeB);

            double vCirc = Math.sqrt(MU / LEO_RADIUS_M);
            double missM = missKm * 1000.0;
            PVCoordinates pvA = new PVCoordinates(new Vector3D(LEO_RADIUS_M, 0, 0), new Vector3D(0, vCirc, 0));
            PVCoordinates pvB = new PVCoordinates(new Vector3D(LEO_RADIUS_M, 0, missM), new Vector3D(0, -vCirc, 0));

            Frame frame = FramesFactory.getEME2000();
            AbsoluteDate date = new AbsoluteDate(TCA.toInstant(), TimeScalesFactory.getUTC());

            return new RefinedEvent(new SatelliteScanInfoPair(a, b),
                    missKm, TCA, relVelMS, pvA, pvB, frame, date);
        }
    }
}
