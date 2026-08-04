package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

final class EventMatcher {

    private EventMatcher() {
    }

    static MatchStats match(List<EventKey> safe, List<EventKey> candidate, Duration tcaTolerance) {
        long toleranceNanos = tcaTolerance.toNanos();

        Map<EventKey.PairKey, List<EventKey>> safeByPair = new HashMap<>();
        for (EventKey k : safe) {
            safeByPair.computeIfAbsent(k.pair(), ignored -> new ArrayList<>()).add(k);
        }

        Set<EventKey> claimed = new HashSet<>();
        int matched = 0;
        int oursOnly = 0;
        List<Double> missErrorsM = new ArrayList<>();
        for (EventKey c : candidate) {
            List<EventKey> bucket = safeByPair.get(c.pair());
            if (bucket == null) {
                oursOnly++;
                continue;
            }
            EventKey best = findNearestUnclaimed(bucket, c.tca(), toleranceNanos, claimed);
            if (best == null) {
                oursOnly++;
            } else {
                claimed.add(best);
                matched++;
                missErrorsM.add(Math.abs(c.distanceKm() - best.distanceKm()) * 1000.0);
            }
        }

        int safeOnly = safe.size() - matched;
        missErrorsM.sort(null);
        return new MatchStats(matched, oursOnly, safeOnly,
                percentile(missErrorsM, 0.50), percentile(missErrorsM, 0.99));
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.min(Math.round(q * (sorted.size() - 1)), sorted.size() - 1L);
        return sorted.get(idx);
    }

    private static EventKey findNearestUnclaimed(List<EventKey> bucket, OffsetDateTime target,
                                                 long toleranceNanos, Set<EventKey> claimed) {
        EventKey best = null;
        long bestDelta = Long.MAX_VALUE;
        for (EventKey k : bucket) {
            if (claimed.contains(k)) continue;
            long delta = Math.abs(Duration.between(k.tca(), target).toNanos());
            if (delta > toleranceNanos) continue;
            if (delta < bestDelta) {
                bestDelta = delta;
                best = k;
            }
        }
        return best;
    }

    record MatchStats(int matched, int oursOnly, int safeOnly, double missErrorMedianM, double missErrorP99M) {
        double jaccard() {
            int union = matched + oursOnly + safeOnly;
            return union == 0 ? 1.0 : matched / (double) union;
        }
    }
}
