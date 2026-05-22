package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

record ScanParams(double toleranceKm, int stepRatio, int stride, double cellRatio) {

    double stepSeconds() {
        return toleranceKm / stepRatio;
    }

    double cellSizeKm() {
        return toleranceKm / cellRatio;
    }
}
