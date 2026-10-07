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
import java.util.*;

/**
 * Linux:
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-step-knot -Dspring-boot.run.jvmArguments="-Xmx16g -Xms16g -XX:+AlwaysPreTouch -Dconjunction.schedule.cron=-"
 * Windows:
 * ./mvnw spring-boot:run "-Dspring-boot.run.profiles=benchmark-step-knot" "-Dspring-boot.run.jvmArguments=-Xmx16g -Xms16g -XX:+AlwaysPreTouch -Dconjunction.schedule.cron=-"
 */
@Component
@Profile("benchmark-step-knot")
public class StepKnotSweepBenchmark extends BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StepKnotSweepBenchmark.class);

    private static final int ITERATIONS = 5;
    private static final Duration TCA_TOLERANCE = Duration.ofSeconds(60);

    // Scored against a no-interpolation reference run.
    // Tolerance is set explicitly, wide enough to stay complete past the fastest closing speed in the catalog.
    private static final double BASELINE_TOLERANCE_KM = 84.0;
    private static final double BASELINE_STEP_SECONDS = 9.375;
    private static final int BASELINE_STRIDE = 1;
    private static final double BASELINE_CELL_KM = 105.0;

    // Every step divides the 6 h subwindow into whole steps. Below 9 s and above 15 s the step is slower.
    private static final double[] STEP_SECONDS_VALUES = {9, 10.8, 12, 13.5, 15};

    private static final double START_KNOT_GAP_SECONDS = 200.0;
    private static final double KNOT_GAP_DELTA = 50.0;
    private static final double MAX_KNOT_GAP_SECONDS = 500.0;

    private static final Path OUTPUT = Paths.get("docs", "5-step-knot-sweep", "conjunction_benchmark.csv");

    public StepKnotSweepBenchmark(SatelliteService satelliteService, PropagationService propagationService,
                                   ScanService scanService, CollisionProbabilityService collisionProbabilityService) {
        super(satelliteService, propagationService, scanService, collisionProbabilityService);
    }

    private static BenchmarkResult medianRun(List<BenchmarkResult> results) {
        return results.stream()
                .sorted(Comparator.comparingLong(BenchmarkResult::totalTime))
                .toList()
                .get(results.size() / 2);
    }

    @Override
    public void run(String @NonNull ... args) {
        log.info("");
        log.info("Starting step x knot gap sweep (cell=tolerance)");
        log.info("Lookahead: {}h, accuracy: Jaccard vs safe baseline (TCA tolerance {}s)",
                LOOKAHEAD_HOURS, TCA_TOLERANCE.toSeconds());
        log.info("");

        List<SatelliteScanInfo> satellites = satelliteService.getAllScanInfo();
        log.info("Loaded {} satellites", satellites.size());
        log.info("Using fixed start time: {}", FIXED_START_TIME);

        warmup(satellites);
        log.info("Ground truth (stride={})...", BASELINE_STRIDE);
        ScanParams baseline = new ScanParams(BASELINE_TOLERANCE_KM, BASELINE_STEP_SECONDS, BASELINE_STRIDE,
                BASELINE_CELL_KM);
        BenchmarkResult groundTruthResult = runBenchmark(satellites, baseline);
        List<EventKey> safeEvents = groundTruthResult.refinedEvents();
        log.info("Ground truth: {} conjunctions, {} refined events ({}s)",
                groundTruthResult.conjunctions(), safeEvents.size(), groundTruthResult.totalTime() / 1000.0);

        EventMatcher.MatchStats selfCheck = EventMatcher.match(safeEvents, safeEvents, TCA_TOLERANCE);
        log.info("Self-match sanity check: matched={}, oursOnly={}, safeOnly={}, jaccard={}",
                selfCheck.matched(), selfCheck.oursOnly(), selfCheck.safeOnly(),
                String.format(Locale.ROOT, "%.4f", selfCheck.jaccard()));
        if (selfCheck.jaccard() != 1.0) {
            log.error("Self-match did not produce jaccard=1.0; matcher is broken. Aborting.");
            System.exit(1);
        }

        BenchmarkCsv csv = new BenchmarkCsv(BenchmarkCsv.Group.PARAMS, BenchmarkCsv.Group.COUNTS,
                BenchmarkCsv.Group.TIMINGS, BenchmarkCsv.Group.MATCH);
        int evaluated = 0;

        for (double stepSeconds : STEP_SECONDS_VALUES) {
            for (int gapStep = 0; START_KNOT_GAP_SECONDS + gapStep * KNOT_GAP_DELTA <= MAX_KNOT_GAP_SECONDS; gapStep++) {
                double knotGap = START_KNOT_GAP_SECONDS + gapStep * KNOT_GAP_DELTA;
                ScanParams p = ScanParams.ofKnotGap(stepSeconds, knotGap);

                // Propagation is cached once per config and shared by its runs
                StopWatch propTimer = StopWatch.createStarted();
                Map<Integer, TLEPropagator> propagators = propagationService.buildPropagators(satellites);
                propTimer.stop();
                StopWatch sgp4Timer = StopWatch.createStarted();
                PropagationService.KnotCache knots = propagationService.computeKnots(
                        propagators, FIXED_START_TIME, FIXED_START_TIME.plusHours(LOOKAHEAD_HOURS),
                        stepSeconds, p.stride());
                sgp4Timer.stop();
                StopWatch interpTimer = StopWatch.createStarted();
                PropagationService.PositionCache positionCache = propagationService.interpolate(knots);
                interpTimer.stop();
                long propMs = propTimer.getTime();
                long sgp4Ms = sgp4Timer.getTime();
                long interpMs = interpTimer.getTime();
                log.info("Cached propagation for step={}s knotGap={}s (stride {}): prop={}ms sgp4={}ms interp={}ms",
                        String.format(Locale.ROOT, "%.4f", stepSeconds),
                        String.format(Locale.ROOT, "%.0f", knotGap), p.stride(), propMs, sgp4Ms, interpMs);

                List<BenchmarkResult> runs = new ArrayList<>();
                for (int i = 0; i < ITERATIONS; i++) {
                    runs.add(runScan(satellites, p, propagators, positionCache, propMs, sgp4Ms, interpMs));
                }
                BenchmarkResult result = medianRun(runs);
                EventMatcher.MatchStats stats =
                        EventMatcher.match(safeEvents, result.refinedEvents(), TCA_TOLERANCE);
                evaluated++;

                log.info(String.format(Locale.ROOT,
                        "[%d] step=%.4fs knotGap=%.0fs (%.1fkm) | %d conj | matched=%d oursOnly=%d safeOnly=%d | jaccard=%.4f | %.1fs",
                        evaluated, p.stepSeconds(), p.knotGapSeconds(), p.toleranceKm(),
                        result.conjunctions(),
                        stats.matched(), stats.oursOnly(), stats.safeOnly(),
                        stats.jaccard(),
                        result.totalTime() / 1000.0));

                csv.addRow(result, stats);
                // Rewritten after every config so a crash keeps what finished.
                writeString(OUTPUT, csv.build());
            }
        }

        log.info("");
        log.info("Grid search complete. {} points evaluated.", evaluated);

        log.info("Step x knot gap sweep complete.");
        System.exit(0);
    }
}
