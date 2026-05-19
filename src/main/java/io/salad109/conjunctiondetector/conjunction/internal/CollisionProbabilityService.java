package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.conjunction.internal.ScanService.RefinedEvent;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.RealMatrix;
import org.orekit.frames.LOFType;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.StateCovariance;
import org.orekit.ssa.collision.shorttermencounter.probability.twod.Laas2015;
import org.orekit.ssa.collision.shorttermencounter.probability.twod.ShortTermEncounter2DPOCMethod;
import org.orekit.ssa.metrics.ProbabilityOfCollision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
public class CollisionProbabilityService {

    private static final Logger log = LoggerFactory.getLogger(CollisionProbabilityService.class);

    private static final double MU = 398600.4418e9; // m^3/s^2

    private static final double RADIUS_PAYLOAD_M = 5.0;
    private static final double RADIUS_ROCKET_BODY_M = 5.0;
    private static final double RADIUS_DEBRIS_M = 0.5;
    private static final double RADIUS_UNKNOWN_M = 1.0;

    // SGP4 1-sigma position uncertainty (m). Aida & Kirschner (2013) Table 1.
    // Growth rates: (6-7d value - epoch value) / 6.5d. Cross-track stays flat.

    // LEO (<2000 km)
    private static final double LEO_RADIAL_BASE_M = 176.0;
    private static final double LEO_INTRACK_BASE_M = 695.0;
    private static final double LEO_CROSSTRACK_BASE_M = 168.0;
    private static final double LEO_RADIAL_GROWTH_M_PER_DAY = 125.0;
    private static final double LEO_INTRACK_GROWTH_M_PER_DAY = 392.0;

    // Higher orbits (>2000 km) - extrapolated 2x base 0.5x growth
    private static final double HIGH_RADIAL_BASE_M = LEO_RADIAL_BASE_M * 2.0;
    private static final double HIGH_INTRACK_BASE_M = LEO_INTRACK_BASE_M * 2.0;
    private static final double HIGH_CROSSTRACK_BASE_M = LEO_CROSSTRACK_BASE_M * 2.0;
    private static final double HIGH_RADIAL_GROWTH_M_PER_DAY = LEO_RADIAL_GROWTH_M_PER_DAY * 0.5;
    private static final double HIGH_INTRACK_GROWTH_M_PER_DAY = LEO_INTRACK_GROWTH_M_PER_DAY * 0.5;

    private static final double LEO_ALTITUDE_THRESHOLD_KM = 2000.0;

    // Faster than numerical integration and more accurate than existing analytical methods. Serra et al. (2016)
    private final ShortTermEncounter2DPOCMethod pocMethod = new Laas2015();

    /**
     * Covariance synthesized from empirical SGP4 errors. Suitable only for screening.
     */
    public Conjunction computeProbabilityAndBuild(RefinedEvent event) {
        double pc = 0.0;

        if (event.pvA() != null && event.relativeVelocityMS() > 10.0) {
            try {
                pc = computePc(event);
            } catch (Exception e) {
                log.debug("Pc computation failed for pair ({}, {}): {}",
                        event.pair().a().noradCatId(), event.pair().b().noradCatId(), e.getMessage());
            }
        }

        int object1 = Math.min(event.pair().a().noradCatId(), event.pair().b().noradCatId());
        int object2 = Math.max(event.pair().a().noradCatId(), event.pair().b().noradCatId());

        return new Conjunction(null, object1, object2, event.distanceKm(),
                event.tca(), event.relativeVelocityMS(), pc);
    }

    private double computePc(RefinedEvent event) {
        SatelliteScanInfo satA = event.pair().a();
        SatelliteScanInfo satB = event.pair().b();

        Orbit orbitA = new CartesianOrbit(event.pvA(), event.frame(), event.absoluteDate(), MU);
        Orbit orbitB = new CartesianOrbit(event.pvB(), event.frame(), event.absoluteDate(), MU);

        StateCovariance covA = buildCovariance(satA, tleAgeDays(satA.epoch(), event.tca()), event);
        StateCovariance covB = buildCovariance(satB, tleAgeDays(satB.epoch(), event.tca()), event);

        double combinedRadius = estimateRadius(satA) + estimateRadius(satB);

        ProbabilityOfCollision result = pocMethod.compute(orbitA, covA, orbitB, covB, combinedRadius, 1e-15);

        return Math.clamp(result.getValue(), 0.0, 1.0);
    }

    private StateCovariance buildCovariance(SatelliteScanInfo sat, double tleAgeDays, RefinedEvent event) {
        boolean isLeo = sat.perigeeKm() < LEO_ALTITUDE_THRESHOLD_KM;

        double radialBase = isLeo ? LEO_RADIAL_BASE_M : HIGH_RADIAL_BASE_M;
        double intrackBase = isLeo ? LEO_INTRACK_BASE_M : HIGH_INTRACK_BASE_M;
        double crosstrackBase = isLeo ? LEO_CROSSTRACK_BASE_M : HIGH_CROSSTRACK_BASE_M;
        double radialGrowth = isLeo ? LEO_RADIAL_GROWTH_M_PER_DAY : HIGH_RADIAL_GROWTH_M_PER_DAY;
        double intrackGrowth = isLeo ? LEO_INTRACK_GROWTH_M_PER_DAY : HIGH_INTRACK_GROWTH_M_PER_DAY;

        double sigR = radialBase + radialGrowth * tleAgeDays;
        double sigT = intrackBase + intrackGrowth * tleAgeDays;
        double sigW = crosstrackBase; // flat per Aida Table 1

        RealMatrix cov = new Array2DRowRealMatrix(6, 6);
        cov.setEntry(0, 0, sigR * sigR);
        cov.setEntry(1, 1, sigT * sigT);
        cov.setEntry(2, 2, sigW * sigW);

        return new StateCovariance(cov, event.absoluteDate(), LOFType.QSW);
    }

    private double estimateRadius(SatelliteScanInfo sat) {
        String type = sat.objectType();
        if (type == null) return RADIUS_UNKNOWN_M;
        return switch (type) {
            case "PAYLOAD" -> RADIUS_PAYLOAD_M;
            case "ROCKET BODY" -> RADIUS_ROCKET_BODY_M;
            case "DEBRIS" -> RADIUS_DEBRIS_M;
            default -> RADIUS_UNKNOWN_M;
        };
    }

    private double tleAgeDays(OffsetDateTime epoch, OffsetDateTime tca) {
        return Math.max(0, Duration.between(epoch, tca).toSeconds() / 86400.0);
    }
}