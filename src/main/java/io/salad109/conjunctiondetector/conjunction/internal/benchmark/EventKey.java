package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.ScanService;

import java.time.OffsetDateTime;

record EventKey(PairKey pair, OffsetDateTime tca, double distanceKm) {

    static EventKey from(ScanService.RefinedEvent event) {
        int a = event.pair().a().noradCatId();
        int b = event.pair().b().noradCatId();
        PairKey pair = (a < b) ? new PairKey(a, b) : new PairKey(b, a);
        return new EventKey(pair, event.tca(), event.distanceKm());
    }

    record PairKey(int noradLow, int noradHigh) {
    }
}
