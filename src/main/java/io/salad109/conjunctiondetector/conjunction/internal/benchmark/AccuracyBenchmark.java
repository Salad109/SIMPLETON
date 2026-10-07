package io.salad109.conjunctiondetector.conjunction.internal.benchmark;

import io.salad109.conjunctiondetector.conjunction.internal.CollisionProbabilityService;
import io.salad109.conjunctiondetector.conjunction.internal.PropagationService;
import io.salad109.conjunctiondetector.conjunction.internal.ScanService;
import io.salad109.conjunctiondetector.satellite.SatelliteScanInfo;
import io.salad109.conjunctiondetector.satellite.SatelliteService;
import org.apache.commons.lang3.time.StopWatch;
import org.jspecify.annotations.NonNull;
import org.orekit.propagation.analytical.tle.TLEPropagator;
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
import java.util.Map;
import java.util.stream.DoubleStream;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-accuracy -Dspring-boot.run.jvmArguments="-Xmx20g -Xms20g -XX:+AlwaysPreTouch -Dconjunction.schedule.cron=-"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-accuracy" "-Dspring-boot.run.jvmArguments=-Xmx20g -Xms20g -XX:+AlwaysPreTouch -Dconjunction.schedule.cron=-"
 */
@Component
@Profile("benchmark-accuracy")
public class AccuracyBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AccuracyBenchmark.class);

    private static final int ITERATIONS = 5;

    // Locked values for whichever axes are not under test.
    private static final double DEFAULT_STEP_SECONDS = 12;
    private static final double DEFAULT_KNOT_GAP_SECONDS = 252.0;

    // Ground truth. Safe margins on purpose
    private static final double BASELINE_TOLERANCE_KM = 84.0;
    private static final double BASELINE_STEP_SECONDS = 9.375;
    private static final int BASELINE_STRIDE = 1;
    private static final double BASELINE_CELL_KM = 105.0;
    private static final Duration TCA_TOLERANCE = Duration.ofSeconds(60);

    // Every step divides the 6h subwindow into whole steps, so any winner is deployable unrounded.
    private static final double[] STEP_SECONDS_VALUES = {6, 6.75, 7.2, 8, 9, 9.375, 10, 10.8, 12, 13.5};
    private static final double[] KNOT_GAP_VALUES = {8, 40, 80, 120, 160, 200, 240, 280, 320, 360, 400, 440, 480, 520, 560, 600, 640, 680, 720, 760, 800, 840, 880, 920, 960, 1000};

    private static final double[] GRID_STEP_SECONDS_VALUES = {6, 7.2, 8, 9, 10, 10.8, 12, 13.5, 15};
    // Step x cell grid. The cell is a fraction of each step's derived tolerance, straddling production's 1.0.
    private static final double[] GRID_CELL_RATIO_VALUES = {0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.15, 1.2};
    private static final String CELL_GRID_DOCS_DIR = "3-cell-size";
    // Step x tolerance grid. The tolerance is swept explicitly against the derived one.
    private static final double[] GRID_TOLERANCE_VALUES = {40.0, 45.0, 50.0, 55.0, 60.0, 65.0, 70.0, 75.0, 80.0, 85.0, 90.0, 95.0, 100.0, 105.0, 110.0, 115.0, 120.0, 125.0, 130.0};
    private static final String TOLERANCE_GRID_DOCS_DIR = "4-conjunction-tolerance";

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
        log.info("Threshold: {} km, lookahead: {} h", THRESHOLD_KM, LOOKAHEAD_HOURS);

        warmup(satellites);
        List<EventKey> safeEvents = runBaseline(satellites);

        for (Sweep s : sweeps()) {
            log.info("");
            log.info("Sweeping {} ({} configs)", s.name(), s.configs().size());
            log.info("Locked: {}", s.locked());
            sweep(satellites, safeEvents, s);
        }
        grid(satellites, safeEvents, CELL_GRID_DOCS_DIR, "cell ratio", GRID_CELL_RATIO_VALUES, ScanParams::of);
        grid(satellites, safeEvents, TOLERANCE_GRID_DOCS_DIR, "tolerance", GRID_TOLERANCE_VALUES,
                (step, stride, tol) -> new ScanParams(tol, step, stride, tol));

        log.info("Benchmark complete");
        System.exit(0);
    }

    private List<Sweep> sweeps() {
        return List.of(
                new Sweep("step size", "1-step-size",
                        "cell=tolerance, knotGap=" + DEFAULT_KNOT_GAP_SECONDS + "s",
                        DoubleStream.of(STEP_SECONDS_VALUES)
                                .mapToObj(s -> ScanParams.ofKnotGap(s, DEFAULT_KNOT_GAP_SECONDS))
                                .toList()),
                new Sweep("knot gap", "2-knot-gap",
                        "step=" + DEFAULT_STEP_SECONDS + "s, cell=tolerance",
                        DoubleStream.of(KNOT_GAP_VALUES)
                                .mapToObj(g -> ScanParams.ofKnotGap(DEFAULT_STEP_SECONDS, g))
                                .toList()));
    }

    // One run per point since accuracy is deterministic. Every point at a step shares its propagation.
    private void grid(List<SatelliteScanInfo> satellites, List<EventKey> safeEvents, String docsDir, String axis,
                      double[] values, GridPoint point) {
        log.info("");
        log.info("Sweeping step x {} ({} configs), knotGap={}s", axis,
                GRID_STEP_SECONDS_VALUES.length * values.length, DEFAULT_KNOT_GAP_SECONDS);
        BenchmarkCsv csv = new BenchmarkCsv(BenchmarkCsv.Group.PARAMS, BenchmarkCsv.Group.COUNTS,
                BenchmarkCsv.Group.TIMINGS, BenchmarkCsv.Group.MATCH, BenchmarkCsv.Group.MISS_ERROR);
        for (double step : GRID_STEP_SECONDS_VALUES) {
            int stride = PropagationService.knotStride(step, DEFAULT_KNOT_GAP_SECONDS);
            StopWatch propTimer = StopWatch.createStarted();
            Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);
            propTimer.stop();
            StopWatch sgp4Timer = StopWatch.createStarted();
            PropagationService.KnotCache knots = propagationService.computeKnots(propagators, FIXED_START_TIME,
                    FIXED_START_TIME.plusHours(LOOKAHEAD_HOURS), step, stride);
            sgp4Timer.stop();
            StopWatch interpTimer = StopWatch.createStarted();
            PropagationService.PositionCache positionCache = propagationService.interpolate(knots);
            interpTimer.stop();

            for (double value : values) {
                ScanParams p = point.at(step, stride, value);
                BenchmarkResult result = runScan(satellites, p, propagators, positionCache,
                        propTimer.getTime(), sgp4Timer.getTime(), interpTimer.getTime());
                EventMatcher.MatchStats stats = EventMatcher.match(safeEvents, result.refinedEvents(), TCA_TOLERANCE);
                csv.addRow(result, stats);
                log.info("  -> step={}s tol={}km cell={}km | missed={} extra={}", p.stepSeconds(),
                        String.format(Locale.ROOT, "%.1f", p.toleranceKm()),
                        String.format(Locale.ROOT, "%.1f", p.cellSizeKm()), stats.safeOnly(), stats.oursOnly());
            }
            // Rewritten after every step so a crash keeps what finished.
            writeString(Paths.get("docs", docsDir, "conjunction_benchmark.csv"), csv.build());
        }
    }

    private List<EventKey> runBaseline(List<SatelliteScanInfo> satellites) {
        ScanParams p = new ScanParams(BASELINE_TOLERANCE_KM, BASELINE_STEP_SECONDS,
                BASELINE_STRIDE, BASELINE_CELL_KM);
        log.info("");
        log.info("Baseline: tolerance={}km step={}s stride={} cell={}km (TCA match window {}s)",
                BASELINE_TOLERANCE_KM, BASELINE_STEP_SECONDS, BASELINE_STRIDE, BASELINE_CELL_KM,
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
                            String.format(Locale.ROOT, "%.1f", p.toleranceKm()),
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

    private interface GridPoint {
        ScanParams at(double stepSeconds, int stride, double value);
    }

    private record Sweep(String name, String docsDir, String locked, List<ScanParams> configs) {
        Path outputPath() {
            return Paths.get("docs", docsDir, "conjunction_benchmark.csv");
        }
    }
}
