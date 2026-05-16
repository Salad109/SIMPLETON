package io.salad109.conjunctiondetector.conjunction.internal;

import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfoPair;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.orekit.frames.Frame;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.IntStream;

@Service
public class ScanService {

    private final PropagationService propagationService;

    public ScanService(PropagationService propagationService) {
        this.propagationService = propagationService;
    }

    /**
     * Check for close approaches using spatial indexing
     */
    public List<CoarseDetection> checkPairs(List<SatelliteScanInfo> satellites,
                                            PropagationService.PositionCache precomputedPositions,
                                            double toleranceKm, double cellSizeKm) {
        IntObjectHashMap<SatelliteScanInfo> satelliteById = new IntObjectHashMap<>(satellites.size());
        for (SatelliteScanInfo s : satellites) satelliteById.put(s.noradCatId(), s);
        int totalSteps = precomputedPositions.times().length;
        double tolSq = toleranceKm * toleranceKm; // skip sqrt by comparing squared distances

        // Parallelize over time steps
        return IntStream.range(0, totalSteps)
                .parallel()
                .boxed()
                .<CoarseDetection>mapMulti((step, consumer) -> {
                    SpatialGrid grid = new SpatialGrid(cellSizeKm, precomputedPositions.x(), precomputedPositions.y(), precomputedPositions.z(), step);

                    grid.forEachCandidatePair((idxA, idxB) -> {
                        double distSq = precomputedPositions.distanceSquaredAt(idxA, idxB, step);
                        if (distSq < tolSq) {
                            int noradA = precomputedPositions.arrayIdToNoradId()[idxA];
                            int noradB = precomputedPositions.arrayIdToNoradId()[idxB];
                            SatelliteScanInfo satA = satelliteById.get(noradA);
                            SatelliteScanInfo satB = satelliteById.get(noradB);
                            SatelliteScanInfoPair pair = noradA < noradB
                                    ? new SatelliteScanInfoPair(satA, satB)
                                    : new SatelliteScanInfoPair(satB, satA);
                            consumer.accept(new CoarseDetection(pair, distSq, step));
                        }
                    });
                })
                .toList();
    }

    /**
     * Sort detections, cluster by pair and step gap, extract the best detection per event.
     * Two detections belong to the same event if they're within 3 steps of each other.
     */
    public List<CoarseDetection> groupAndReduce(List<CoarseDetection> detections) {
        if (detections.isEmpty()) return List.of();

        List<CoarseDetection> sorted = detections.parallelStream()
                .sorted(Comparator
                        .comparingInt((CoarseDetection d) -> d.pair().a().noradCatId())
                        .thenComparingInt(d -> d.pair().b().noradCatId())
                        .thenComparingInt(CoarseDetection::stepIndex))
                .toList();

        List<CoarseDetection> bestPerEvent = new ArrayList<>();

        CoarseDetection best = sorted.getFirst();
        SatelliteScanInfoPair currentPair = best.pair();

        for (int i = 1; i < sorted.size(); i++) {
            CoarseDetection prev = sorted.get(i - 1);
            CoarseDetection curr = sorted.get(i);

            if (!curr.pair().equals(currentPair) || curr.stepIndex() - prev.stepIndex() > 3) {
                // Event boundary: different pair or time gap > 3 steps
                bestPerEvent.add(best);   // emit winner of the finished event
                best = curr;              // start new event with curr as initial best
                currentPair = curr.pair();
            } else if (curr.distanceSq() < best.distanceSq()) {
                best = curr;              // same event, curr is closer - new best
            }
            // else: same event, curr is farther - skip
        }
        // Close last event
        bestPerEvent.add(best);

        return bestPerEvent;
    }

    /**
     * Refine coarse detections to find accurate TCA and minimum distance.
     * Call SGP4 only for events that survive the analytical threshold check.
     */
    public List<RefinedEvent> refine(List<CoarseDetection> events, PropagationService.PositionCache cache,
                                     Map<Integer, TLEPropagator> propagators, double stepSeconds, double thresholdKm) {
        return events.parallelStream()
                .map(det -> refineDetection(det, cache, propagators, stepSeconds, thresholdKm))
                .filter(Objects::nonNull)
                .toList();
    }

    private RefinedEvent refineDetection(CoarseDetection best, PropagationService.PositionCache cache,
                                         Map<Integer, TLEPropagator> propagators, double stepSeconds, double thresholdKm) {
        SatelliteScanInfoPair pair = best.pair();
        int step = best.stepIndex();
        int totalSteps = cache.times().length;

        int idxA = cache.noradIdToArrayId().get(pair.a().noradCatId());
        int idxB = cache.noradIdToArrayId().get(pair.b().noradCatId());

        double gateKm = thresholdKm * 1.1;
        double gateSq = gateKm * gateKm;
        double bestDistSq = Double.MAX_VALUE;
        double bestT = 0;
        int bestIntervalStart = step;

        // Check interval (step-1, step)
        if (step > 0 && cache.isValid(idxA, step - 1) && cache.isValid(idxB, step - 1)
                && cache.isValid(idxA, step) && cache.isValid(idxB, step)) {
            double[] result = analyticalMin(cache, idxA, idxB, step - 1, step);
            if (result[0] < bestDistSq) {
                bestDistSq = result[0];
                bestT = result[1];
                bestIntervalStart = step - 1;
            }
        }

        // Check interval (step, step+1)
        if (step < totalSteps - 1 && cache.isValid(idxA, step) && cache.isValid(idxB, step)
                && cache.isValid(idxA, step + 1) && cache.isValid(idxB, step + 1)) {
            double[] result = analyticalMin(cache, idxA, idxB, step, step + 1);
            if (result[0] < bestDistSq) {
                bestDistSq = result[0];
                bestT = result[1];
                bestIntervalStart = step;
            }
        }

        // Early exit for events obviously above threshold
        if (bestDistSq > gateSq) {
            return null;
        }

        // Convert fractional t to absolute timestamp
        long intervalNanos = Math.round(stepSeconds * 1_000_000_000.0);
        OffsetDateTime tca = cache.times()[bestIntervalStart].plusNanos((long) (bestT * intervalNanos));

        PropagationService.MeasurementResult measurement = propagationService.propagateAndMeasure(pair, propagators, tca, thresholdKm);

        if (measurement.distanceKm() > thresholdKm) {
            return null;
        }

        return new RefinedEvent(pair, measurement.distanceKm(), tca, measurement.velocityMS(),
                measurement.pvA(), measurement.pvB(), measurement.frame(), measurement.absoluteDate());
    }

    /**
     * With linear interpolation between two positions, squared distance is a quadratic in t.
     * Solve for the minimum analytically. Returns {minDistSq, t} where t in [0,1].
     */
    double[] analyticalMin(PropagationService.PositionCache cache, int idxA, int idxB,
                           int s0, int s1) {
        double sepX = cache.x()[idxA][s0] - cache.x()[idxB][s0];
        double sepY = cache.y()[idxA][s0] - cache.y()[idxB][s0];
        double sepZ = cache.z()[idxA][s0] - cache.z()[idxB][s0];
        double deltaSepX = (cache.x()[idxA][s1] - cache.x()[idxA][s0]) - (cache.x()[idxB][s1] - cache.x()[idxB][s0]);
        double deltaSepY = (cache.y()[idxA][s1] - cache.y()[idxA][s0]) - (cache.y()[idxB][s1] - cache.y()[idxB][s0]);
        double deltaSepZ = (cache.z()[idxA][s1] - cache.z()[idxA][s0]) - (cache.z()[idxB][s1] - cache.z()[idxB][s0]);

        double distSq0 = sepX * sepX + sepY * sepY + sepZ * sepZ;
        double sepDotDelta = sepX * deltaSepX + sepY * deltaSepY + sepZ * deltaSepZ;
        double deltaSepSq = deltaSepX * deltaSepX + deltaSepY * deltaSepY + deltaSepZ * deltaSepZ;

        if (deltaSepSq == 0) {
            // Constant separation
            return new double[]{distSq0, 0.5};
        }

        double t = Math.clamp(-sepDotDelta / deltaSepSq, 0, 1);

        // Compute minimum distSq via discriminant form to avoid catastrophic cancellation.
        double minDistSq;
        if (t == 0.0) {
            minDistSq = distSq0;
        } else if (t == 1.0) {
            double sepEndX = sepX + deltaSepX;
            double sepEndY = sepY + deltaSepY;
            double sepEndZ = sepZ + deltaSepZ;
            minDistSq = sepEndX * sepEndX + sepEndY * sepEndY + sepEndZ * sepEndZ;
        } else {
            minDistSq = (distSq0 * deltaSepSq - sepDotDelta * sepDotDelta) / deltaSepSq;
        }

        return new double[]{minDistSq, t};
    }


    public record CoarseDetection(SatelliteScanInfoPair pair, double distanceSq, int stepIndex) {
    }

    public record RefinedEvent(SatelliteScanInfoPair pair, double distanceKm, OffsetDateTime tca,
                               double relativeVelocityMS,
                               PVCoordinates pvA, PVCoordinates pvB, Frame frame, AbsoluteDate absoluteDate) {
    }
}
