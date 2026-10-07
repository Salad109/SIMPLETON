package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;

record ScanParams(double toleranceKm, double stepSeconds, int stride, double cellSizeKm) {

    // Tolerance derived from the step and the cell as wide as it, as production does.
    static ScanParams of(double stepSeconds, int stride) {
        double toleranceKm = ScanService.coarseToleranceKm(stepSeconds, BenchmarkRunner.THRESHOLD_KM);
        return new ScanParams(toleranceKm, stepSeconds, stride, toleranceKm);
    }

    static ScanParams ofKnotGap(double stepSeconds, double knotGapSeconds) {
        return of(stepSeconds, PropagationService.knotStride(stepSeconds, knotGapSeconds));
    }

    // A cell other than production's, as a fraction of the derived tolerance, for the cell grid.
    static ScanParams of(double stepSeconds, int stride, double cellToleranceRatio) {
        ScanParams p = of(stepSeconds, stride);
        return new ScanParams(p.toleranceKm(), stepSeconds, stride, cellToleranceRatio * p.toleranceKm());
    }

    double knotGapSeconds() {
        return stride * stepSeconds;
    }
}
