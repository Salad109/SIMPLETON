package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

record ScanParams(double toleranceKm, double stepSeconds, int stride, double cellSizeKm) {

    static ScanParams ofKnotGap(double toleranceKm, double stepSeconds, double knotGapSeconds,
                                double cellSizeKm) {
        int stride = Math.max(1, (int) Math.round(knotGapSeconds / stepSeconds));
        return new ScanParams(toleranceKm, stepSeconds, stride, cellSizeKm);
    }

    double knotGapSeconds() {
        return stride * stepSeconds;
    }
}
