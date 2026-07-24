package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.orekit.frames.Frame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Service
public class PropagationService {

    private static final Logger log = LoggerFactory.getLogger(PropagationService.class);

    public Map<Integer, TLEPropagator> buildPropagators(List<SatelliteScanInfo> satellites) {
        Map<Integer, TLEPropagator> propagators = new HashMap<>();

        for (SatelliteScanInfo sat : satellites) {
            TLE tle = new TLE(sat.tleLine1(), sat.tleLine2());
            propagators.put(sat.noradCatId(), TLEPropagator.selectExtrapolator(tle));
        }

        return propagators;
    }

    /**
     * Calculates SGP4 PV coordinates at stride points only. Returns SGP4 knot arrays sized [numSats][numKnots].
     * Position in km, velocity in km/s.
     */
    public KnotCache computeKnots(Map<Integer, TLEPropagator> propagators, OffsetDateTime startTime,
                                  OffsetDateTime endTime, double stepSeconds, int interpolationStride) {
        long stepNanos = Math.round(stepSeconds * 1_000_000_000L);
        int totalSteps = (int) Math.round(Duration.between(startTime, endTime).toNanos() / (stepSeconds * 1_000_000_000L)) + 1;
        OffsetDateTime[] times = new OffsetDateTime[totalSteps];
        for (int i = 0; i < totalSteps; i++) {
            times[i] = startTime.plusNanos(i * stepNanos);
        }

        Integer[] satIds = propagators.keySet().toArray(Integer[]::new);
        MutableIntIntMap noradIdToArrayId = new IntIntHashMap(satIds.length);
        int[] arrayIdToNoradId = new int[satIds.length];
        for (int i = 0; i < satIds.length; i++) {
            noradIdToArrayId.put(satIds[i], i);
            arrayIdToNoradId[i] = satIds[i];
        }

        int numSats = satIds.length;
        int numKnots = (totalSteps - 1) / interpolationStride + 1;

        float[][] kx = new float[numSats][numKnots];
        float[][] ky = new float[numSats][numKnots];
        float[][] kz = new float[numSats][numKnots];
        float[][] kvx = new float[numSats][numKnots];
        float[][] kvy = new float[numSats][numKnots];
        float[][] kvz = new float[numSats][numKnots];

        for (int s = 0; s < numSats; s++) {
            Arrays.fill(kx[s], Float.NaN);
        }

        IntStream.range(0, numSats).parallel().forEach(s -> {
            TLEPropagator prop = propagators.get(satIds[s]);

            for (int k = 0; k < numKnots; k++) {
                int step = k * interpolationStride;
                if (step >= totalSteps) break;
                try {
                    PVCoordinates pv = prop.getPVCoordinates(toAbsoluteDate(times[step]), prop.getFrame());
                    kx[s][k] = (float) (pv.getPosition().getX() / 1000.0);
                    ky[s][k] = (float) (pv.getPosition().getY() / 1000.0);
                    kz[s][k] = (float) (pv.getPosition().getZ() / 1000.0);
                    kvx[s][k] = (float) (pv.getVelocity().getX() / 1000.0);
                    kvy[s][k] = (float) (pv.getVelocity().getY() / 1000.0);
                    kvz[s][k] = (float) (pv.getVelocity().getZ() / 1000.0);
                } catch (Exception e) {
                    log.debug("Failed to propagate NORAD ID {} at knot {}: {}", satIds[s], k, e.getMessage());
                    break; // bad TLE
                }
            }
        });

        return new KnotCache(noradIdToArrayId, arrayIdToNoradId, times, stepNanos, interpolationStride,
                kx, ky, kz, kvx, kvy, kvz);
    }

    /**
     * Hermite interpolation from knot points to full position arrays.
     * H(t) = (2t^3 - 3t^2 + 1)*p0 + (t^3 - 2t^2 + t)*v0*dt + (-2t^3 + 3t^2)*p1 + (t^3 - t^2)*v1*dt
     */
    public PositionCache interpolate(KnotCache knots) {
        int numSats = knots.x.length;
        int totalSteps = knots.times.length;
        int interpolationStride = knots.interpolationStride;

        if (interpolationStride == 1) {
            // No interpolation
            return new PositionCache(knots.noradIdToArrayId, knots.arrayIdToNoradId, knots.times,
                    knots.x, knots.y, knots.z);
        }

        float dt = (float) (knots.stepNanos * interpolationStride / 1e9); // seconds between knots

        float[][] x = new float[numSats][totalSteps];
        float[][] y = new float[numSats][totalSteps];
        float[][] z = new float[numSats][totalSteps];

        for (int s = 0; s < numSats; s++) {
            Arrays.fill(x[s], Float.NaN);
        }

        IntStream.range(0, numSats).parallel().forEach(s -> {
            int numKnots = knots.x[s].length;

            for (int k = 0; k < numKnots - 1; k++) {
                if (Float.isNaN(knots.x[s][k]) || Float.isNaN(knots.x[s][k + 1])) break;

                int stepStart = k * interpolationStride;
                int stepEnd = Math.min((k + 1) * interpolationStride, totalSteps - 1);

                x[s][stepStart] = knots.x[s][k];
                y[s][stepStart] = knots.y[s][k];
                z[s][stepStart] = knots.z[s][k];

                x[s][stepEnd] = knots.x[s][k + 1];
                y[s][stepEnd] = knots.y[s][k + 1];
                z[s][stepEnd] = knots.z[s][k + 1];

                for (int step = stepStart + 1; step < stepEnd; step++) {
                    float t = (float) (step - stepStart) / (stepEnd - stepStart);
                    float t2 = t * t;
                    float t3 = t2 * t;

                    float h00 = 2 * t3 - 3 * t2 + 1;  // p0
                    float h10 = t3 - 2 * t2 + t;       // v0
                    float h01 = -2 * t3 + 3 * t2;      // p1
                    float h11 = t3 - t2;                // v1

                    x[s][step] = h00 * knots.x[s][k] + h10 * knots.vx[s][k] * dt + h01 * knots.x[s][k + 1] + h11 * knots.vx[s][k + 1] * dt;
                    y[s][step] = h00 * knots.y[s][k] + h10 * knots.vy[s][k] * dt + h01 * knots.y[s][k + 1] + h11 * knots.vy[s][k + 1] * dt;
                    z[s][step] = h00 * knots.z[s][k] + h10 * knots.vz[s][k] * dt + h01 * knots.z[s][k + 1] + h11 * knots.vz[s][k + 1] * dt;
                }
            }
        });

        return new PositionCache(knots.noradIdToArrayId, knots.arrayIdToNoradId, knots.times, x, y, z);
    }

    /**
     * Propagate both satellites to a given time and return distance, relative velocity, and PV coordinates.
     */
    MeasurementResult propagateAndMeasure(SatelliteScanInfoPair pair, Map<Integer, TLEPropagator> propagators,
                                          OffsetDateTime time, double thresholdKm) {
        AbsoluteDate date = toAbsoluteDate(time);

        try {
            TLEPropagator propA = propagators.get(pair.a().noradCatId());
            TLEPropagator propB = propagators.get(pair.b().noradCatId());

            PVCoordinates pvA, pvB;
            Frame frame;

            synchronized (propA) {
                frame = propA.getFrame();
                pvA = propA.getPVCoordinates(date, frame);
            }
            synchronized (propB) {
                pvB = propB.getPVCoordinates(date, frame);
            }

            double distance = calculateDistance(pvA, pvB);
            double velocity = distance <= thresholdKm ? calculateRelativeVelocity(pvA, pvB) : 0.0;

            return new MeasurementResult(distance, velocity, pvA, pvB, frame, date);
        } catch (Exception e) {
            log.warn("Failed to propagate for refinement: {}", e.getMessage());
            return new MeasurementResult(Double.MAX_VALUE, 0.0, null, null, null, null);
        }
    }

    /**
     * Calculate distance in kilometers between two PVCoordinates.
     */
    private double calculateDistance(PVCoordinates pvA, PVCoordinates pvB) {
        double dx = (pvA.getPosition().getX() - pvB.getPosition().getX()) / 1000.0;
        double dy = (pvA.getPosition().getY() - pvB.getPosition().getY()) / 1000.0;
        double dz = (pvA.getPosition().getZ() - pvB.getPosition().getZ()) / 1000.0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate relative velocity in meters per second between two PVCoordinates.
     */
    private double calculateRelativeVelocity(PVCoordinates pvA, PVCoordinates pvB) {
        double dvx = pvA.getVelocity().getX() - pvB.getVelocity().getX();
        double dvy = pvA.getVelocity().getY() - pvB.getVelocity().getY();
        double dvz = pvA.getVelocity().getZ() - pvB.getVelocity().getZ();
        return Math.sqrt(dvx * dvx + dvy * dvy + dvz * dvz);
    }

    private AbsoluteDate toAbsoluteDate(OffsetDateTime dateTime) {
        return new AbsoluteDate(
                dateTime.getYear(),
                dateTime.getMonthValue(),
                dateTime.getDayOfMonth(),
                dateTime.getHour(),
                dateTime.getMinute(),
                dateTime.getSecond() + dateTime.getNano() / 1e9,
                TimeScalesFactory.getUTC()
        );
    }

    public record KnotCache(MutableIntIntMap noradIdToArrayId, int[] arrayIdToNoradId, OffsetDateTime[] times,
                            long stepNanos, int interpolationStride,
                            float[][] x, float[][] y, float[][] z,
                            float[][] vx, float[][] vy, float[][] vz) {
    }

    public record PositionCache(MutableIntIntMap noradIdToArrayId, int[] arrayIdToNoradId, OffsetDateTime[] times,
                                float[][] x, float[][] y, float[][] z) {
        boolean isValid(int sat, int step) {
            return !Float.isNaN(x[sat][step]);
        }

        double distanceSquaredAt(int a, int b, int step) {
            float dx = x[a][step] - x[b][step];
            float dy = y[a][step] - y[b][step];
            float dz = z[a][step] - z[b][step];
            return dx * dx + dy * dy + dz * dz;
        }
    }

    record MeasurementResult(double distanceKm, double velocityMS, PVCoordinates pvA, PVCoordinates pvB, Frame frame,
                             AbsoluteDate absoluteDate) {
    }
}
