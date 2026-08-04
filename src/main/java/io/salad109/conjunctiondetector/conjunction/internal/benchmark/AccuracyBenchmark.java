package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.DoubleStream;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-accuracy -Dspring-boot.run.jvmArguments="-Xmx20g -Xms20g -XX:+AlwaysPreTouch"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-accuracy" "-Dspring-boot.run.jvmArguments=-Xmx20g -Xms20g -XX:+AlwaysPreTouch"
 */
@Component
@Profile("benchmark-accuracy")
public class AccuracyBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AccuracyBenchmark.class);

    private static final int ITERATIONS = 5;
    // Center of the flat optimum band from docs/4.
    private static final double TOLERANCE_KM = 84.0;

    // Locked values for whichever axes are not under test.
    private static final double DEFAULT_STEP_SECONDS = 9.375;
    private static final double DEFAULT_CELL_KM = 70.0;
    private static final double DEFAULT_KNOT_GAP_SECONDS = 200.0;

    // Ground truth. Safe margins on purpose
    private static final double BASELINE_STEP_SECONDS = 9.375;
    private static final int BASELINE_STRIDE = 1;
    private static final double BASELINE_CELL_KM = 105.0;
    private static final Duration TCA_TOLERANCE = Duration.ofSeconds(60);

    // Every step divides the 6h subwindow into whole steps, so any winner is deployable unrounded.
    private static final double[] STEP_SECONDS_VALUES = {6, 6.75, 7.2, 8, 9, 9.375, 10, 10.8, 12, 13.5};
    private static final double[] KNOT_GAP_VALUES = {8, 40, 80, 120, 160, 200, 240, 280, 320, 360, 400, 440, 480, 520, 560, 600, 640, 680, 720, 760, 800, 840, 880, 920, 960, 1000};
    private static final double[] CELL_KM_VALUES = {84, 80, 76, 72, 68, 64, 60, 58, 56, 54, 52, 50, 48, 46, 44, 42, 40, 38, 36};
    private static final double[] TOLERANCE_VALUES = {24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152, 160};

    public AccuracyBenchmark(SatelliteService satelliteService, PropagationService propagationService,
                             ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        super(satelliteService, propagationService, scanService, collisionProbabilityService);
    }

    @Override
    public void run(String @NonNull ... args) {
        log.info("");
        log.info("Starting conjunction accuracy benchmark");
        log.info("");

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());

        log.info("Using fixed start time: {}", FIXED_START_TIME);
        log.info("Fixed tolerance: {} km, threshold: {} km, lookahead: {} h",
                TOLERANCE_KM, THRESHOLD_KM, LOOKAHEAD_HOURS);

        List<EventKey> safeEvents = runBaseline(satellites);

        for (Sweep s : sweeps()) {
            log.info("");
            log.info("Sweeping {} ({} configs)", s.name(), s.configs().size());
            log.info("Locked: {}", s.locked());
            sweep(satellites, safeEvents, s);
        }

        log.info("Benchmark complete");
        System.exit(0);
    }

    private List<Sweep> sweeps() {
        return List.of(
                new Sweep("step size", "1-step-size",
                        "cell=" + DEFAULT_CELL_KM + "km, knotGap=" + DEFAULT_KNOT_GAP_SECONDS + "s",
                        DoubleStream.of(STEP_SECONDS_VALUES)
                                .mapToObj(s -> ScanParams.ofKnotGap(TOLERANCE_KM, s,
                                        DEFAULT_KNOT_GAP_SECONDS, DEFAULT_CELL_KM))
                                .toList()),
                new Sweep("knot gap", "2-knot-gap",
                        "step=" + DEFAULT_STEP_SECONDS + "s, cell=" + DEFAULT_CELL_KM + "km",
                        DoubleStream.of(KNOT_GAP_VALUES)
                                .mapToObj(g -> ScanParams.ofKnotGap(TOLERANCE_KM, DEFAULT_STEP_SECONDS,
                                        g, DEFAULT_CELL_KM))
                                .toList()),
                new Sweep("cell size", "3-cell-size",
                        "step=" + DEFAULT_STEP_SECONDS + "s, knotGap=" + DEFAULT_KNOT_GAP_SECONDS + "s",
                        DoubleStream.of(CELL_KM_VALUES)
                                .mapToObj(c -> ScanParams.ofKnotGap(TOLERANCE_KM, DEFAULT_STEP_SECONDS,
                                        DEFAULT_KNOT_GAP_SECONDS, c))
                                .toList()),
                new Sweep("tolerance", "4-conjunction-tolerance",
                        "step=" + DEFAULT_STEP_SECONDS + "s, cell=" + DEFAULT_CELL_KM
                                + "km, knotGap=" + DEFAULT_KNOT_GAP_SECONDS + "s",
                        DoubleStream.of(TOLERANCE_VALUES)
                                .mapToObj(t -> ScanParams.ofKnotGap(t, DEFAULT_STEP_SECONDS,
                                        DEFAULT_KNOT_GAP_SECONDS, DEFAULT_CELL_KM))
                                .toList()));
    }

    private List<EventKey> runBaseline(List<SatelliteScanInfo> satellites) {
        ScanParams p = new ScanParams(TOLERANCE_KM, BASELINE_STEP_SECONDS,
                BASELINE_STRIDE, BASELINE_CELL_KM);
        log.info("");
        log.info("Baseline: tolerance={}km step={}s stride={} cell={}km (TCA match window {}s)",
                TOLERANCE_KM, BASELINE_STEP_SECONDS, BASELINE_STRIDE, BASELINE_CELL_KM,
                TCA_TOLERANCE.toSeconds());

        BenchmarkResult result = runBenchmark(satellites, p);
        List<EventKey> events = result.refinedEvents();
        log.info("Baseline: {} conjunctions in {}s", events.size(), result.totalTime() / 1000.0);

        EventMatcher.MatchStats selfCheck = EventMatcher.match(events, events, TCA_TOLERANCE);
        if (selfCheck.jaccard() != 1.0) {
            log.error("Baseline self-match gave jaccard={} instead of 1.0; matcher is broken. Aborting.",
                    selfCheck.jaccard());
            System.exit(1);
        }
        log.info("Baseline self-match OK (jaccard=1.0)");
        return events;
    }

    private void sweep(List<SatelliteScanInfo> satellites, List<EventKey> safeEvents, Sweep s) {
        BenchmarkCsv csv = new BenchmarkCsv(BenchmarkCsv.Group.PARAMS, BenchmarkCsv.Group.COUNTS,
                BenchmarkCsv.Group.TIMINGS, BenchmarkCsv.Group.MATCH, BenchmarkCsv.Group.MISS_ERROR);
        for (ScanParams p : s.configs()) {
            for (int i = 0; i < ITERATIONS; i++) {
                BenchmarkResult result = runBenchmark(satellites, p);
                EventMatcher.MatchStats stats = EventMatcher.match(safeEvents, result.refinedEvents(), TCA_TOLERANCE);
                csv.addRow(result, stats);
                if (i == 0) {
                    log.info("  -> tol={}km step={}s cell={}km knotGap={}s | jaccard={} matched={} oursOnly={} safeOnly={} missErr median={}m p99={}m",
                            String.format(Locale.ROOT, "%.0f", p.toleranceKm()),
                            String.format(Locale.ROOT, "%.4f", p.stepSeconds()),
                            String.format(Locale.ROOT, "%.1f", p.cellSizeKm()),
                            String.format(Locale.ROOT, "%.0f", p.knotGapSeconds()),
                            String.format(Locale.ROOT, "%.5f", stats.jaccard()),
                            stats.matched(), stats.oursOnly(), stats.safeOnly(),
                            String.format(Locale.ROOT, "%.1f", stats.missErrorMedianM()),
                            String.format(Locale.ROOT, "%.1f", stats.missErrorP99M()));
                }
            }
        }
        writeString(s.outputPath(), csv.build());
    }

    private record Sweep(String name, String docsDir, String locked, List<ScanParams> configs) {
        Path outputPath() {
            return Paths.get("docs", docsDir, "conjunction_benchmark.csv");
        }
    }
}
