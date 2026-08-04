package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

final class BenchmarkCsv {

    private final List<Column> columns;
    private final boolean needsStats;
    private final StringBuilder body = new StringBuilder();

    BenchmarkCsv(Group... groups) {
        this.columns = Stream.of(groups).flatMap(g -> g.columns.stream()).toList();
        this.needsStats = Stream.of(groups).anyMatch(g -> g.needsStats);
    }

    private static Column col(String name, String format, Cell cell) {
        return new Column(name, format, cell);
    }

    void addRow(BenchmarkRunner.BenchmarkResult result) {
        if (needsStats) {
            throw new IllegalStateException("CSV has match columns, use addRow(result, stats)");
        }
        addRow(result, null);
    }

    void addRow(BenchmarkRunner.BenchmarkResult result, EventMatcher.MatchStats stats) {
        body.append(columns.stream().map(c -> c.render(result, stats)).collect(joining(","))).append('\n');
    }

    String build() {
        return columns.stream().map(Column::name).collect(joining(",")) + "\n" + body;
    }

    enum Group {
        PARAMS(false,
                col("tolerance_km", "%.0f", (r, s) -> r.params().toleranceKm()),
                col("step_s", "%.4f", (r, s) -> r.params().stepSeconds()),
                col("cell_km", "%.1f", (r, s) -> r.params().cellSizeKm()),
                col("interp_stride", "%d", (r, s) -> r.params().stride()),
                col("knot_gap_s", "%.1f", (r, s) -> r.params().knotGapSeconds())),
        COUNTS(false,
                col("detections", "%d", (r, s) -> r.detections()),
                col("events", "%d", (r, s) -> r.events()),
                col("conj", "%d", (r, s) -> r.conjunctions())),
        TIMINGS(false,
                col("propagator_s", "%.6f", (r, s) -> r.propagatorTime() / 1000.0),
                col("sgp4_s", "%.6f", (r, s) -> r.sgp4Time() / 1000.0),
                col("interp_s", "%.6f", (r, s) -> r.interpTime() / 1000.0),
                col("check_s", "%.6f", (r, s) -> r.checkTime() / 1000.0),
                col("grouping_s", "%.6f", (r, s) -> r.groupingTime() / 1000.0),
                col("refine_s", "%.6f", (r, s) -> r.refineTime() / 1000.0),
                col("probability_s", "%.6f", (r, s) -> r.probabilityTime() / 1000.0),
                col("total_s", "%.6f", (r, s) -> r.totalTime() / 1000.0)),
        MATCH(true,
                col("matched", "%d", (r, s) -> s.matched()),
                col("ours_only", "%d", (r, s) -> s.oursOnly()),
                col("safe_only", "%d", (r, s) -> s.safeOnly()),
                col("jaccard", "%.6f", (r, s) -> s.jaccard())),
        MISS_ERROR(true,
                col("miss_err_median_m", "%.3f", (r, s) -> s.missErrorMedianM()),
                col("miss_err_p99_m", "%.3f", (r, s) -> s.missErrorP99M()));

        private final boolean needsStats;
        private final List<Column> columns;

        Group(boolean needsStats, Column... columns) {
            this.needsStats = needsStats;
            this.columns = List.of(columns);
        }
    }

    @FunctionalInterface
    private interface Cell {
        Object of(BenchmarkRunner.BenchmarkResult r, EventMatcher.MatchStats s);
    }

    private record Column(String name, String format, Cell cell) {
        String render(BenchmarkRunner.BenchmarkResult r, EventMatcher.MatchStats s) {
            return String.format(Locale.ROOT, format, cell.of(r, s));
        }
    }
}
