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
            }
        }

        int safeOnly = safe.size() - matched;
        return new MatchStats(matched, oursOnly, safeOnly);
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

    record MatchStats(int matched, int oursOnly, int safeOnly) {
        double jaccard() {
            int union = matched + oursOnly + safeOnly;
            return union == 0 ? 1.0 : matched / (double) union;
        }
    }
}
